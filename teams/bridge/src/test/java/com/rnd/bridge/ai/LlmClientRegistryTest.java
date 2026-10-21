package com.rnd.bridge.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rnd.bridge.ai.provider.LlmProvider;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LlmClientRegistryTest {

    private final Map<String, FakeProvider> fakes = new HashMap<>();

    @Test
    void usageWithoutItsOwnEntryGetsTheDefaultModel() {
        LlmClientRegistry registry = registry(true, "free-a", models(free("a", "model-a")), Map.of());

        LlmResponse reply = registry.forUsage("anything").complete(LlmRequest.text("s", "hello"));

        assertThat(reply.model()).isEqualTo("model-a");
    }

    @Test
    void usageCanPickAnotherModelFromTheSharedList() {
        LlmClientRegistry registry = registry(true, "free-a",
                models(free("a", "model-a"), free("b", "model-b")),
                Map.of("special", new AiProperties.Usage("free-b", null, null, null)));

        assertThat(registry.forUsage("special").complete(LlmRequest.text("s", "t")).model()).isEqualTo("model-b");
        assertThat(registry.forUsage("other").complete(LlmRequest.text("s", "t")).model()).isEqualTo("model-a");
    }

    @Test
    void theSameUsageIsTheSameChainSoItsDailyLimitIsShared() {
        LlmClientRegistry registry = registry(true, "free-a", models(free("a", "model-a")),
                Map.of("u", new AiProperties.Usage(null, null, null, 1)));

        assertThat(registry.forUsage("u")).isSameAs(registry.forUsage("u"));
        registry.forUsage("u").complete(LlmRequest.text("s", "t"));
        assertThatThrownBy(() -> registry.forUsage("u").complete(LlmRequest.text("s", "t")))
                .isInstanceOf(LlmQuotaExhaustedException.class);
    }

    @Test
    void piiIsRedactedByDefaultAndCanBeTurnedOffPerUsage() {
        LlmClientRegistry registry = registry(true, "free-a", models(free("a", "model-a")),
                Map.of("raw", new AiProperties.Usage(null, null, false, null)));

        registry.forUsage("default-use").complete(LlmRequest.text("s", "mail me at a@b.com"));
        registry.forUsage("raw").complete(LlmRequest.text("s", "mail me at a@b.com"));

        assertThat(fakes.get("a").sentTexts).containsExactly("mail me at [EMAIL]", "mail me at a@b.com");
    }

    @Test
    void fallbackModelAnswersWhenThePrimaryIsOverloaded() {
        LlmClientRegistry registry = registry(true, "free-a",
                models(free("a", "model-a"), free("b", "model-b")),
                Map.of("u", new AiProperties.Usage("free-a", List.of("free-b"), null, null)));
        fakes.get("a").failure = new LlmException("503", true);

        assertThat(registry.forUsage("u").complete(LlmRequest.text("s", "t")).model()).isEqualTo("model-b");
    }

    @Test
    void freeOnlyRefusesToStartWithAPaidModelInTheList() {
        assertThatThrownBy(() -> registry(true, "free-a",
                models(free("a", "model-a"), Map.entry("paid", new AiProperties.Model("a", "pro", false, false, 1.0, 2.0, null, null))),
                Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("free-only").hasMessageContaining("paid");
    }

    @Test
    void paidModelIsAllowedOnlyWhenFreeOnlyIsExplicitlyOff() {
        LlmClientRegistry registry = registry(false, "paid",
                models(Map.entry("paid", new AiProperties.Model("a", "pro", false, false, 1.0, 2.0, null, null))), Map.of());

        assertThat(registry.forUsage("u").complete(LlmRequest.text("s", "t")).model()).isEqualTo("pro");
    }

    @Test
    void usagePointingAtAnUnknownModelFailsStartup() {
        assertThatThrownBy(() -> registry(true, "free-a", models(free("a", "model-a")),
                Map.of("u", new AiProperties.Usage("nope", null, null, null))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("bridge.ai.usages.u.model");
    }

    @Test
    void unknownFallbackOrDefaultModelFailsStartup() {
        assertThatThrownBy(() -> registry(true, "free-a", models(free("a", "model-a")),
                Map.of("u", new AiProperties.Usage(null, List.of("nope"), null, null))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("fallback");
        assertThatThrownBy(() -> registry(true, "missing", models(free("a", "model-a")), Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("default-model");
    }

    @Test
    void modelWithoutImagesRefusesARequestWithImagesBeforeSending() {
        LlmClientRegistry registry = registry(true, "free-a", models(free("a", "model-a")), Map.of());

        assertThatThrownBy(() -> registry.forUsage("u")
                .complete(new LlmRequest("s", "t", List.of(new byte[] {1}))))
                .isInstanceOf(LlmException.class).hasMessageContaining("images");
        assertThat(fakes.get("a").sentTexts).isEmpty();
    }

    @Test
    void isConfiguredFollowsThePrimaryModelsProviderKey() {
        LlmClientRegistry registry = registry(true, "free-a", models(free("a", "model-a")), Map.of());
        assertThat(registry.isConfigured("u")).isTrue();

        fakes.get("a").configured = false;
        assertThat(registry.isConfigured("u")).isFalse();
    }

    @Test
    void noModelsAtAllMeansNothingIsConfigured() {
        LlmClientRegistry registry = new LlmClientRegistry(properties(true, null, Map.of(), Map.of(), Map.of()),
                Clock.systemUTC(), (name, config) -> { throw new AssertionError("no providers"); });

        assertThat(registry.isConfigured("u")).isFalse();
        assertThatThrownBy(() -> registry.forUsage("u")).isInstanceOf(IllegalStateException.class);
    }

    // --- fixtures: one provider per model key letter, named after it -----------------------------

    private LlmClientRegistry registry(boolean freeOnly, String defaultModel,
                                       Map<String, AiProperties.Model> models,
                                       Map<String, AiProperties.Usage> usages) {
        Map<String, AiProperties.Provider> providers = new HashMap<>();
        models.values().forEach(m -> providers.put(m.provider(),
                new AiProperties.Provider("openai-compatible", "http://localhost", "key", Duration.ofSeconds(5))));
        return new LlmClientRegistry(properties(freeOnly, defaultModel, providers, models, usages), Clock.systemUTC(),
                (name, config) -> fakes.computeIfAbsent(name, n -> new FakeProvider()));
    }

    private static AiProperties properties(boolean freeOnly, String defaultModel,
                                           Map<String, AiProperties.Provider> providers,
                                           Map<String, AiProperties.Model> models,
                                           Map<String, AiProperties.Usage> usages) {
        return new AiProperties(freeOnly, defaultModel, providers, models, usages,
                new AiProperties.Defaults(true, 200), new AiProperties.Monitoring(Duration.ofSeconds(30), 0.02));
    }

    private static Map.Entry<String, AiProperties.Model> free(String provider, String name) {
        return Map.entry("free-" + provider, new AiProperties.Model(provider, name, false, true, null, null, null, null));
    }

    @SafeVarargs
    private static Map<String, AiProperties.Model> models(Map.Entry<String, AiProperties.Model>... entries) {
        return Map.ofEntries(entries);
    }

    private static final class FakeProvider implements LlmProvider {
        final List<String> sentTexts = new ArrayList<>();
        boolean configured = true;
        LlmException failure;

        @Override
        public boolean configured() {
            return configured;
        }

        @Override
        public LlmResponse complete(String modelName, LlmRequest request) {
            if (failure != null) {
                throw failure;
            }
            sentTexts.add(request.userText());
            return new LlmResponse("ok", modelName, 1, 1);
        }
    }
}
