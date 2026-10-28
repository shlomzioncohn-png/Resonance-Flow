# CRM LLM Enrichment — Demo Runbook

Runs the CRM sync with LLM enrichment against the synthetic dataset, no Kafka, no real
CRM credentials. See `crm-llm-enrichment-design.md` for the design and its trade-offs.

## Prerequisites

- A Google AI Studio API key, from a project with **no billing attached** (free tier
  only).
- Windows only: if this checkout's path contains non-ASCII characters, `protoc` fails
  to build. Map an ASCII drive letter first (`teams/bridge/CLAUDE.md` §8):
  ```
  subst R: "<path to the parent folder of Resonance-Flow>"
  ```
  then run every command below from `R:\Resonance-Flow\teams\bridge` instead. Remove
  the mapping when done: `subst R: /D`.
- `teams/bridge/CLAUDE.md` §8 also has this machine's local Maven/JDK path quirks, if
  `mvn` isn't on `PATH` or the default JDK breaks the build.

## 1. Set up your key

```bash
cp teams/bridge/.env.example teams/bridge/.env
```

Edit `teams/bridge/.env` and set `BRIDGE_AI_GEMINI_API_KEY=<your key>`. Never put a
real key in `.env.example` - it's committed; `.env` is gitignored.

## 2. Set the demo's environment variables

In addition to whatever `.env` already has, for this run:

```bash
LLM_ENRICHMENT_ENABLED=true
LLM_ENRICHMENT_SUPPORTED_SOURCES=CRM_DEAL
```

Make sure `SPRING_PROFILES_ACTIVE` does **not** include `kafka`, `hubspot` or
`salesforce` for this demo - the goal is the synthetic dataset (`JsonDealRepository`)
logged via `LoggingPayloadPublisher`, which needs none of Kafka, a live CRM, or a
reachable Postgres. If `CRM_ORGANIZATION_ID` in your `.env` isn't a real UUID, override
it too (e.g. `00000000-0000-0000-0000-000000000000`) - `CrmTaskValidator` requires
UUID shape unconditionally, independent of this feature.

## 3. Start the app

```bash
mvn spring-boot:run
```

Wait for `Started BridgeApplication` in the console.

## 4. Trigger a sync

```bash
curl -X POST http://localhost:8080/bridge/crm/sync
```

Returns a JSON summary (`read`/`published`/`rejected`/`errors`/`skippedAtRead`,
overall and per source).

## 5. Where to see the before/after

- `LoggingPayloadPublisher` logs each resulting task as
  `payload=Task[... title=..., description=..., effortEstimateMinutes=..., ...]`. An
  enriched description contains `Original CRM notes:` followed by the original text
  below it.
- `MeteredLlmClient` logs one `model_call usage=crm-deal-enrichment ... status=success`
  (or `error`) line per real model call.
- `CrmDealLlmEnricher` logs `CRM deal enrichment reply rejected ... reason=...` or
  `... call failed ... exception=...` for anything that fell back to ETL - sourceRef
  and a fixed category only, never the reply or the deal's own text.

## 6. Expected counts

Against the synthetic dataset (32 deals as of `dataset_version` 1.1.0): 8 are skipped
(terminal status or blank description - no model call at all, nothing to log). Of the
remaining ~24, how many succeed vs. fail depends on the model's live quota at that
moment, not just this sync - a real run saw 16 enriched, 0 rejected, 8 failed on the
model's RPM (see the design doc's trade-offs). Rejected and failed are different
outcomes: failed (quota/timeout/provider error) is retried on every sync; rejected
(bad reply) is cached for ~6h and not retried until then.

## 7. Run it again

POST `/bridge/crm/sync` a second time with the dataset unchanged: every previously
enriched deal is a cache hit - no new `model_call` log line for an unchanged
sourceRef+content, same response shape as before.

## 8. Turn it off

Stop the app, or set `LLM_ENRICHMENT_ENABLED=false` (or drop `CRM_DEAL` from
`LLM_ENRICHMENT_SUPPORTED_SOURCES`) and restart - CRM sync then produces plain ETL
tasks again, with no model calls at all.
