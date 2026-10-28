# CRM Deal LLM Enrichment — Revised Design

Project: Resonance-Flow / Bridge
Status: Design Proposal — Not Implemented
Scope: Salesforce and HubSpot

## 1. Objective
Enhance CRM tasks using an LLM to generate actionable next steps from deal descriptions.
Baseline ETL: creates a task using deterministic rules.
LLM Enrichment: improves the title, description, and optionally the estimated effort.
The LLM must not modify task identity, status, urgency, or deadline.
Core Principle: LLM failures must never interrupt CRM synchronization.

## 2. Architecture
Existing flow:
Salesforce / HubSpot → Deal → DealToTaskMapper → AbstractTaskMapper → Task → Protobuf → Kafka → Inbox

Proposed enrichment flow:
1. AbstractTaskMapper generates baseline ETL values.
2. SourceRoutingTaskEnricher selects the enrichment implementation.
3. CrmDealLlmEnricher checks the cache.
4. On a cache miss, the enricher calls Gemini through LlmClients.
5. The model response is parsed and validated.
6. The validated result is converted to EnrichmentOutput.
7. The existing mapper merges the enrichment into the task.
8. The task continues through the existing publishing pipeline.
Fallback: if enrichment fails, return EnrichmentOutput.empty() and preserve the
deterministic task.

## 3. LLM Input and Output
Input — Version 1: use only fields already available in EnrichmentInput: title,
description, status, deadline. The model does not receive the raw CRM stage, deal
amount, or days in stage in v1. Limit the description sent to the model to 2,000
characters.

Expected JSON output:
{
  "title": "Prepare updated pricing proposal",
  "description": "Review the current offer and prepare revised pricing.",
  "effortEstimateMinutes": 45
}

Validation:
- Title must be non-empty and no longer than 120 characters (an application design choice).
- Effort must be between 5 and 480 minutes, when provided.
- JSON must match the expected structure.
- The model must be instructed not to invent facts.
- v1 validates structure only; factual correctness is not verified by code - the
  model is instructed not to invent facts, and protected fields stay under ETL
  control. Decision approved by the team lead: grounding with regex does not prove
  correctness and may reject good replies.
- Invalid responses are rejected.
No urgency, status, or deadline overrides are permitted.
Confidence: leave confidence null in v1 unless a meaningful, validated confidence policy
is introduced. Do not use an arbitrary constant as a model-confidence estimate.
AI provenance is currently not included in the StructuredTaskEvent sent to Inbox.
Exposing it requires a separate contract decision.

## 4. Cache Design
Purpose: avoid repeated LLM calls for CRM deals that have not changed.
Cache identity (single-tenant proof of concept): CRM sourceRef, content fingerprint,
prompt version. The fingerprint is calculated from title, description, status, deadline,
prompt version. Do not include asOf or calculated urgency, because they may change with
time without any change to the deal content. If multi-tenant deployments are supported,
tenant identity must be included explicitly in the cache namespace.
Cache hit: reuse the previously validated enrichment.
Cache miss: call the LLM, validate its response, and store successful enrichment.
LLM failure: use the baseline ETL values and do not cache a failed result.
Storage: proof of concept = thread-safe in-memory cache inside the Bridge JVM - a single
LinkedHashMap (insertion order) guarded by one lock, so entries and FIFO eviction order
can never disagree; lost on restart. Production = persistent storage (see section 13).
The model call itself runs with no lock held, so a slow call never stalls unrelated
cache operations; two threads that both miss on the same key at the same time may both
call the model (no in-flight deduplication).
Cache entries must not prevent urgency and other time-dependent ETL values from being
recalculated.
An interrupted rate-limiter wait aborts the call and falls back to the ETL task; any
failure inside the cache's compute step (quota, timeout, interrupted wait, or any other
exception) is never cached, successful or rejected.

## 5. API Quota — Resolved
The Gemini free-tier quota is counted per model. crm-deal-enrichment runs on its own
Gemini model entry (gemini-crm), separate from gemini-flash used by media-meeting and
media-document, so it has its own quota and does not consume theirs. Same provider and
same API key; no new provider. The daily-call-limit is set from the model's quota shown
at https://ai.dev/rate-limit. Local development uses each developer's own key in .env.
Confirmed with Esther (owner of com.rnd.bridge.ai).

## 6. Spring Integration
CrmDealLlmEnricher implements TaskEnricher: prepares the prompt, checks and updates the
cache, calls the shared LLM client, validates the output, returns EnrichmentOutput.
SourceRoutingTaskEnricher routes by source type: CRM_DEAL → CrmDealLlmEnricher; other
supported sources → existing RuleBasedTaskEnricher.
Dependency injection: the router is the primary TaskEnricher bean (@Primary or explicit
configuration). Inject RuleBasedTaskEnricher by its concrete type to avoid circular
dependencies and ambiguous bean resolution.
Feature flag: the existing llm.enrichment.enabled flag remains disabled by default. For
an isolated CRM demo: LLM_ENRICHMENT_SUPPORTED_SOURCES=CRM_DEAL. With this
configuration, non-CRM sources bypass enrichment entirely; to preserve GitHub/Jira
rule-based enrichment in production, include those sources in the supported list.

## 7. Inbox Integration — Blocking Dependency
The current Inbox implementation does not update an existing task when another event
arrives with the same sourceRef. PR #727 is intended to address this but is still open.
Consequence: a later successful LLM enrichment may not update a task previously ingested
with baseline ETL values.
For the demo: use a clean Inbox dataset for the initial enriched event, or demonstrate
the result inside Bridge; verify that the model quota is available; do not claim that
repeated synchronization updates existing Inbox tasks until this is implemented and
tested.

## 8. Security and Reliability
- Use synthetic CRM data for the initial demonstration.
- Do not send unnecessary owner information or personal data.
- Use the shared PII redaction layer.
- Treat CRM descriptions as untrusted data.
- Keep deal text separate from system instructions.
- Validate model responses before creating enrichment output.
- Never allow the LLM to execute actions or modify protected task fields.
- Do not expose sensitive prompts or credentials in logs.

## 9. Acceptance Tests
| Test | Expected result |
| Valid LLM response | Enriched title, description, and optional effort |
| Invalid JSON | Original ETL task |
| Quota exhausted | Original ETL task |
| LLM timeout | Original ETL task |
| Unchanged deal | Cache hit, no external call |
| Changed deal | New LLM request |
| Prompt version changed | Cache invalidated |
| Non-CRM source | Existing behavior preserved according to feature configuration |
| Invalid effort | Enrichment rejected |
| Protected fields | No change to status, urgency, deadline, or sourceRef |
| Terminal status / blank description | No LLM call, original ETL task |
| Rejected reply, unchanged deal | Not retried until the content changes |
Tests must use a fake LLM client and synthetic data.

## 10. Implementation Plan
Phase 1 — Core LLM enricher: prompt creation, JSON parsing, validation, fallback, unit
tests. No live Gemini calls.
Phase 2 — Integration: SourceRoutingTaskEnricher, Spring injection, dedicated model
config, feature flag.
Phase 3 — Cache: change detection with content fingerprints and prompt versioning.
Phase 4 — Demo: run enrichment on synthetic CRM deals and record a before-and-after
demonstration; verify Inbox ingestion separately.
Phase 5 — Production readiness: Inbox updates, production caching, privacy approvals,
additional CRM context.

## 11. Engineering Trade-offs
- Deterministic ETL vs. LLM inference: keep ETL authoritative while using the LLM for
  language understanding.
- Freshness vs. API consumption: cache validated results to reduce quota usage, with
  invalidation on relevant changes.
- Enrichment vs. availability: prefer a valid baseline task over blocking
  synchronization when the model fails.
- Simplicity vs. production durability: start with in-memory caching for the proof of
  concept, then persistent storage for production.
- Dedicated model vs. shared quota: a separate Gemini model gives CRM enrichment its own
  quota instead of competing with media classification.
- Synchronous enrichment: simpler, at the cost of a slower first sync (each cache miss
  is a 1-10 s model call inside the sync).
- Structural validation vs. provable correctness: v1 validates only the model's JSON
  structure (title/description/effort bounds), not whether its content is factually
  correct. Team-lead decision: regex-based grounding against the input text doesn't
  prove correctness and can reject a perfectly good reply; the model is instructed not
  to invent facts instead, and protected fields stay under ETL control regardless.
- In-memory cache vs. durability: lost on every restart, keyed without an explicit
  tenant (single-tenant proof of concept, see section 4), rejected replies cached for a
  bounded 6h TTL (not retried every sync, but eventually retried), evicted FIFO rather
  than true LRU (simpler, still genuinely bounded).
- Per-minute vs. per-day quota: a live check against the real API hit the model's
  requests-per-minute limit (RPM 15) well before its daily cap (RPD 500) - a CRM sync
  otherwise fires calls back to back with no pacing. Fixed with a minimum spacing
  between real calls (`bridge.crm.enrichment.min-call-interval`, default 4s = 60/15);
  the trade-off is a slower first sync (~24 real calls ≈ 1.5 minutes) in exchange for
  not burning calls on 429s.
- effortEstimateMinutes is a rough v1 estimate, not a calibrated one - the live check
  returned 30 minutes for every enriched example, which reads as the model's default
  fallback more than a judged per-deal estimate.
- Inbox (#727, open) doesn't update an existing task when a later event arrives with
  the same sourceRef, so enrichment only reaches tasks Inbox hasn't already ingested
  from ETL. Separately, StructuredTaskEvent carries no AI-provenance field, so even
  once #727 lands, Inbox has no way to tell an enriched task apart from an ETL one.
- reasoning-effort is left unset for gemini-crm: confirmed for gemini-flash
  (gemini-3.6-flash) but not for this model, and an unsupported value is a 400 - left
  out rather than guessed, pending confirmation with Esther.

## 12. Definition of Done — Version 1
- CRM enrichment produces validated actionable task text.
- Model failures preserve normal ETL output.
- Unchanged deals reuse cached enrichment.
- Non-CRM behavior is not unintentionally changed.
- Unit tests cover success, failure, and caching.
- The feature can be disabled independently.
- The demo clearly distinguishes verified functionality from pending integration work.

## 13. Additions agreed after review
A. Skip the LLM call (return EnrichmentOutput.empty(), no request) when the task status
   is terminal (DONE / CANCELLED), or the description is null or blank.
B. Negative cache: an invalid model response (bad JSON / failed validation) is cached
   as "rejected" for that fingerprint, so the same unchanged deal does not burn quota on
   every sync. Quota exhaustion, timeouts and provider errors are not cached.
D. The model answers in the same language as the input.
E. Production storage: prefer a table in Bridge's existing Postgres over Redis (Redis
   is not part of this project).
F. Resolved (2026-10-26, owner: Ruth): prepend - the enriched description, then the
   original CRM text - unless the model's description is a whitespace-normalized
   duplicate of the original (or fully contained in it), in which case the original is
   left unchanged rather than prepending a no-op copy.

Design summary: add an optional LLM layer to CRM task mapping, reuse the shared AI
infrastructure, minimize model calls through caching, and preserve synchronization
reliability through strict validation and deterministic fallback.
