package com.rnd.bridge.ai;

import com.rnd.bridge.ai.guard.DailyCallLimitLlmClient;
import com.rnd.bridge.ai.guard.FallbackLlmClient;
import com.rnd.bridge.ai.guard.PiiRedactingLlmClient;
import com.rnd.bridge.ai.guard.PiiRedactor;
import com.rnd.bridge.ai.guard.TextNormalizingLlmClient;
import com.rnd.bridge.ai.observability.MeteredLlmClient;
import com.rnd.bridge.ai.provider.LlmProvider;
import com.rnd.bridge.ai.provider.ModelLlmClient;
import com.rnd.bridge.ai.provider.OpenAiCompatibleProvider;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Factory + Registry for {@link LlmClient}s: reads bridge.ai.*, validates it once at startup, and
 * assembles each usage's chain the first time it's asked for, then hands out that same instance
 * (so its daily counter is shared by every caller of the usage):
 * <pre>
 *   TextNormalizing -&gt; PiiRedacting -&gt; DailyCallLimit -&gt; Fallback[ Metered(primary), Metered(fallback 1), ... ]
 * </pre>
 * Normalization always runs, first, so redaction's patterns see canonical text.
 * Redaction and the daily limit apply once per call; monitoring is per model, so a fallback shows
 * up as two model_call lines.
 * <p>
 * Startup fails (IllegalStateException, naming the setting) on: a model whose provider doesn't
 * exist, a provider type nobody implements, a usage pointing at an unknown model, a missing
 * default-model, and - while free-only is on, the default - any model not marked {@code free}.
 * That last check is what keeps a paid model from ever being called, primary or fallback.
 */
@Component
public class LlmClientRegistry implements LlmClients {

    private static final Logger log = LoggerFactory.getLogger(LlmClientRegistry.class);

    private final AiProperties properties;
    private final Map<String, LlmProvider> providers;
    private final Clock clock;
    private final PiiRedactor redactor = new PiiRedactor();
    private final Map<String, LlmClient> chains = new ConcurrentHashMap<>();

    @Autowired
    public LlmClientRegistry(AiProperties properties, RestClient.Builder restClientBuilder, Clock clock) {
        this(properties, clock, (name, config) -> new OpenAiCompatibleProvider(name, config, restClientBuilder, clock));
    }

    LlmClientRegistry(AiProperties properties, Clock clock,
                      BiFunction<String, AiProperties.Provider, LlmProvider> providerFactory) {
        this.properties = properties;
        this.clock = clock;
        validate(properties);
        Map<String, LlmProvider> built = new HashMap<>();
        properties.providers().forEach((name, config) -> built.put(name, providerFactory.apply(name, config)));
        this.providers = Map.copyOf(built);
        log.info("AI: {} model(s), default {}, free-only {}, usages configured: {}",
                properties.models().size(), properties.defaultModel(), properties.freeOnly(),
                properties.usages().keySet());
    }

    @Override
    public LlmClient forUsage(String usage) {
        if (properties.models().isEmpty()) {
            throw new IllegalStateException("No AI models configured (bridge.ai.models is empty)");
        }
        return chains.computeIfAbsent(usage, this::build);
    }

    @Override
    public boolean isConfigured(String usage) {
        if (properties.models().isEmpty()) {
            return false;
        }
        AiProperties.Model primary = properties.models().get(primaryModelKey(usage));
        return providers.get(primary.provider()).configured();
    }

    private LlmClient build(String usage) {
        AiProperties.Usage settings = properties.usages().get(usage);
        List<String> modelKeys = new ArrayList<>();
        modelKeys.add(primaryModelKey(usage));
        if (settings != null) {
            modelKeys.addAll(settings.fallback());
        }

        List<LlmClient> inOrder = new ArrayList<>();
        for (String key : modelKeys) {
            AiProperties.Model model = properties.models().get(key);
            LlmClient modelClient = new ModelLlmClient(key, model, providers.get(model.provider()));
            inOrder.add(new MeteredLlmClient(modelClient, usage, model, properties.monitoring()));
        }
        LlmClient chain = new FallbackLlmClient(usage, inOrder);

        int dailyLimit = settings != null && settings.dailyCallLimit() != null
                ? settings.dailyCallLimit() : properties.defaults().dailyCallLimit();
        if (dailyLimit > 0) {
            chain = new DailyCallLimitLlmClient(chain, usage, dailyLimit, clock);
        }
        boolean redactPii = settings != null && settings.redactPii() != null
                ? settings.redactPii() : properties.defaults().redactPii();
        if (redactPii) {
            chain = new PiiRedactingLlmClient(chain, redactor);
        }
        chain = new TextNormalizingLlmClient(chain);
        log.info("AI usage '{}': models {}, daily limit {}, PII redaction {}",
                usage, modelKeys, dailyLimit > 0 ? dailyLimit : "off", redactPii ? "on" : "off");
        return chain;
    }

    private String primaryModelKey(String usage) {
        AiProperties.Usage settings = properties.usages().get(usage);
        return settings != null && settings.model() != null ? settings.model() : properties.defaultModel();
    }

    private static void validate(AiProperties p) {
        p.providers().forEach((name, provider) -> {
            if (!OpenAiCompatibleProvider.TYPE.equals(provider.type())) {
                fail("bridge.ai.providers." + name + ".type '" + provider.type() + "' is not supported (only "
                        + OpenAiCompatibleProvider.TYPE + ")");
            }
            if (provider.baseUrl() == null || provider.baseUrl().isBlank()) {
                fail("bridge.ai.providers." + name + ".base-url is not set");
            }
        });
        p.models().forEach((key, model) -> {
            if (model.provider() == null || !p.providers().containsKey(model.provider())) {
                fail("bridge.ai.models." + key + ".provider '" + model.provider() + "' is not in bridge.ai.providers");
            }
            if (model.name() == null || model.name().isBlank()) {
                fail("bridge.ai.models." + key + ".name is not set");
            }
            if (model.maxOutputTokens() != null && model.maxOutputTokens() <= 0) {
                fail("bridge.ai.models." + key + ".max-output-tokens must be positive");
            }
            if (p.freeOnly() && !model.free()) {
                fail("bridge.ai.free-only is on, but bridge.ai.models." + key + " is not marked free: true - "
                        + "Bridge refuses to start rather than risk a paid call");
            }
        });
        if (p.models().isEmpty()) {
            return;
        }
        requireModel(p, p.defaultModel(), "bridge.ai.default-model");
        p.usages().forEach((usage, settings) -> {
            if (settings.model() != null) {
                requireModel(p, settings.model(), "bridge.ai.usages." + usage + ".model");
            }
            settings.fallback().forEach(key -> requireModel(p, key, "bridge.ai.usages." + usage + ".fallback"));
            if (settings.dailyCallLimit() != null && settings.dailyCallLimit() < 0) {
                fail("bridge.ai.usages." + usage + ".daily-call-limit must be 0 (off) or positive");
            }
        });
    }

    private static void requireModel(AiProperties p, String key, String setting) {
        if (key == null || !p.models().containsKey(key)) {
            fail(setting + " '" + key + "' is not in bridge.ai.models " + p.models().keySet());
        }
    }

    private static void fail(String message) {
        throw new IllegalStateException(message);
    }
}
