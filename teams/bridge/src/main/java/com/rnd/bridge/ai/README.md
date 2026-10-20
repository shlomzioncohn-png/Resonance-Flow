# com.rnd.bridge.ai - Bridge's shared AI infrastructure

Every model (LLM) call in Bridge goes through this package. You write the prompt and parse
the reply; the infrastructure handles everything else: the provider, the key, retries, fallback,
PII redaction, the daily limit, the free-only guard and monitoring.

## Using it from your service

```java
@Service
public class SlackSummaryService {

    private final LlmClient llm;

    public SlackSummaryService(LlmClients clients) {
        this.llm = clients.forUsage("slack-summary");   // any name for your use
    }

    public String summarize(String messages) {
        return llm.complete(LlmRequest.text(SYSTEM_PROMPT, messages)).text();
    }
}
```

That's all. With no entry under `bridge.ai.usages`, your usage gets `bridge.ai.default-model`
and `bridge.ai.defaults`. To call only when a key is set (and fall back to something
deterministic otherwise), check `clients.isConfigured("slack-summary")` first.

`complete` throws `LlmException` on failure. `LlmQuotaExhaustedException` (a subclass) means no
quota is left - the provider's free-tier cap or the usage's own `daily-call-limit` - and carries
`retryAt()`, when it renews. What to do with the item is your service's call, not the AI layer's.
Media classification publishes it at once, INCLUDED and unclassified, and logs
`CLASSIFICATION_SKIPPED_QUOTA item=... retry_at=...`; another service might hold its item and
retry after `retryAt()`.

## Settings (`bridge.ai.*` in application.yml)

| Level | What it holds | Who touches it |
|---|---|---|
| `providers` | base URL + API key, **each key once** | rarely - a new provider |
| `models` | **the one shared list** of models: provider, model id, `images`, `free`, `max-output-tokens`, `reasoning-effort` | adding a model = one entry here, for everyone |
| `usages` | optional per-usage choice: `model`, `fallback`, `redact-pii`, `daily-call-limit` | each service, only if the default doesn't fit |

```yaml
bridge:
  ai:
    usages:
      slack-summary:
        model: gemini-flash        # must be a key under bridge.ai.models
        fallback: [other-free]     # tried only on timeout / rate limit / 5xx
        daily-call-limit: 50
```

The keys come from environment variables (`BRIDGE_AI_GEMINI_API_KEY`), never from the yml.

## Adding another model or provider (e.g. Groq)

Only Gemini is configured today. Any provider that speaks the OpenAI chat-completions format
(Groq, OpenAI, DeepSeek, ...) needs **settings only, no code**: every guard above - normalization,
PII redaction, daily limit, free-only, fallback, retries, spent-quota fail-fast, monitoring -
applies to it automatically. What is per model, and must be set from *that* provider's docs:

```yaml
bridge:
  ai:
    providers:
      groq:                                  # once per provider
        base-url: https://api.groq.com/openai/v1
        api-key: ${BRIDGE_AI_GROQ_API_KEY:}  # env var, add it to .env.example (empty)
        timeout: 30s
    models:
      groq-llama:                            # the key usages point at
        provider: groq
        name: <the provider's model id>
        free: true                           # only if this key's tier really costs nothing
        images: false                        # true only if the model accepts images
        max-output-tokens: 1024
        # reasoning-effort: leave out unless the model documents it - "none" is Gemini's
        #                   way to turn thinking off, and an unknown value is a 400
    usages:
      media-meeting:
        fallback: [groq-llama]               # tried after a transient failure / spent quota
        daily-call-limit: 10                 # per usage; keep each model's total within its free quota
```

Check each provider's free-tier limits (requests per minute/day) and set `daily-call-limit` so
the usages sharing a model stay inside them. A provider with a different wire format (e.g.
Anthropic's native API) is a new `LlmProvider` implementation plus a `type` value.

## Rules the infrastructure enforces

- **Free only.** `free-only: true` (the default) makes Bridge refuse to start if any model in the
  list isn't marked `free: true`, so nothing can be billed - primary or fallback. The real
  guarantee is the key itself: a Google AI Studio key in a project with **no billing**.
- **Daily limit** per usage (default 10 calls/UTC day - Gemini's free tier is ~20/day per model,
  shared by every usage on it), so one usage can't burn the free quota.
- **Spent quota fails fast.** A 429 asking to wait longer than 30s (Gemini's "retry in 18h...")
  isn't retried; that model then fails at once, without a request, until the quota renews.
- **Thinking off, replies capped** per model (`reasoning-effort`, `max-output-tokens`). A reply
  cut off at the cap, or withheld by the provider's safety filter, is a failure - never parsed.
- **PII redaction** (on by default): email, phone, Israeli ID, credit card and IBAN in the text
  sent to the model become `[EMAIL]`, `[PHONE]`, ... Only the outgoing text changes - not your
  data, not what you publish. Names are not detected. Turn it off per usage with
  `redact-pii: false` (e.g. image-only requests).
- **Images** sent to a model without `images: true` are refused before anything is sent.
- **Startup fails** on any setting that points nowhere (unknown model, provider, default).

## Monitoring

One log line per model call, the same shape and field names as Mind's
(`teams/mind/src/mind/observability/tracking.py`) plus `usage`:

```
model_call usage=media-meeting model_name=gemini-3.6-flash kind=llm status=success duration_ms=1432.5 prompt_tokens=812 completion_tokens=64 cost_usd=0.000000
```

Over-limit calls also log `MODEL_CALL_SLOW` / `MODEL_CALL_EXPENSIVE` (WARNING). A free model's
cost is 0; a paid model without a price, or a provider that reported no tokens, is `unpriced`.

## Design

```
LlmClients.forUsage(name)          LlmClientRegistry: Factory + Registry, validates at startup
  -> TextNormalizingLlmClient       Decorator: NFKC + strip invisible/control chars (always on)
  -> PiiRedactingLlmClient          Decorator
  -> DailyCallLimitLlmClient        Decorator
  -> FallbackLlmClient              Decorator: primary, then each fallback
       -> MeteredLlmClient          Decorator: one model_call line per model call
            -> ModelLlmClient       one model bound to its provider; images check, max-output-tokens
                 -> LlmProvider     Strategy: OpenAiCompatibleProvider (Gemini, Groq, OpenAI, ...)
```

A new wire format (e.g. Anthropic's native API) is a new `LlmProvider` plus a `type` value -
nothing else changes.
