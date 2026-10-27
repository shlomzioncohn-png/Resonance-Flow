# embeddings

Vectors, semantic search, duplicate/related-task detection.

## Decisions (closed)

- **Vector store: pgvector** — see [`vector-store-comparison.md`](../../../benchmarks/vector-store-comparison.md).
- **Embedding model: `BAAI/bge-small-en-v1.5`**, 384-dim, CPU-only, English-only. Asymmetric -- queries need `is_query=True`. Local weights optional via `MODEL_CACHE_DIR`.
- **Load target** (week 8, provisional): 10,000+ records, <300ms p95.

## What's here

```
__init__.py          public interface -- embed()/embed_batch() (+ async), SearchResult, VectorIndex, VectorStore, DEMO_TENANT_ID, nearest_tasks(), resolve_dependency_targets()
embeddings.py         embed() family -- the real model
base.py               VectorIndex (whole index) + VectorStore (one tenant) contracts + SearchResult
pgvector_store.py     the ONLY file that knows Postgres/pgvector: PgVectorStore (the index) + PgTenantStore (one tenant)
index_consumer.py     #470 -- Kafka task.{created,updated,completed} handlers
index_consumer_runner.py  #470 -- the process: python -m mind.embeddings.index_consumer_runner
search.py             nearest_tasks() -- the shared embed()+search() primitive behind #471/#245
dependency_resolution.py  #245 -- resolve_dependency_targets(): free-text dependency mentions -> ranked candidate task_ids
duplicate_detection.py  #471 -- find_duplicate(): is a candidate task a duplicate of one already indexed
related_tasks.py      #472 -- find_related(): live semantic related-task search, exposed to other teams via #450
loader.py             fetches + verifies the real FUNSD manifest slice
ingest.py             loader -> embed_batch_async -> upsert
verify.py             table row count vs. manifest record count
```

Config lives in `mind.config`. Only `PgVectorStore` is constructed directly outside `__init__.py` -- no factory/singleton.

## Tenants (#841)

`task_embeddings` columns: `task_id`, `embedding`, `is_completed`, `content_hash`, `tenant_id` (UUID, btree index, migration `0004`). Without a tenant, a duplicate, a dependency or a related task could come from another customer.

- **`PgVectorStore` is the index, not a store.** It owns the pool (`open()`, `close()`, `clear()`) and implements `VectorIndex`: `for_tenant(tenant_id)` and `tenant_of(task_id)`. It is not a `VectorStore`, so mypy rejects a search that has no tenant.
- **`index.for_tenant(tenant_id)` is a `VectorStore`** for one tenant. Every write sets `tenant_id`, every read and search adds `WHERE tenant_id = %s`. `nearest_tasks()`, `resolve_dependency_targets()`, `find_duplicate()` and `find_related()` keep their signatures: they take that scoped store.
- **Where the tenant comes from:** `task.created` / `task.updated` carry it (index consumer). `task.completed` does not, so its handler reads it from the row with `tenant_of()`. So does `GET /v1/tasks/{task_id}/related`: the tenant is taken from the task's own row, never from the caller, and the endpoint is unchanged. A non-UUID `tenant_id` on an event is logged and skipped. `ingest.py`, `scripts/` and `benchmarks/` use `DEMO_TENANT_ID` (Inbox's built-in System tenant).
- **Rows from before #841** have `tenant_id = NULL`. They never match a search and `tenant_of()` returns `None` for them. The next `task.updated` fills them: under the event's tenant the row has no `content_hash`, so it is re-embedded once and `upsert()` writes the tenant. `upsert()` never takes over a row that belongs to another tenant (it logs a warning). Once no `NULL` rows are left, a later migration can make the column `NOT NULL`.
- **`hnsw.iterative_scan` is always on**, because the tenant condition alone can leave fewer than `k` matching rows inside the `ef_search` window. For a large tenant Postgres uses the HNSW index with the tenant as a filter. For a small one it uses the `tenant_id` btree and sorts exactly. Both return the right rows.

```python
from contextlib import asynccontextmanager
from fastapi import FastAPI
from mind.config import get_settings
from mind.embeddings.pgvector_store import PgVectorStore


@asynccontextmanager
async def lifespan(app: FastAPI):
    store = PgVectorStore(get_settings().vector_store)
    try:
        await store.open()
        app.state.vector_store = store
        yield
    finally:
        await store.close()
```

## Real data: the FUNSD slice (not R2)

No R2 (Cloudflare object storage) loader/storage exists in this repo. `loader.py` fetches the real 2-document FUNSD slice (PR #141's manifest), verifies checksums, caches under `local-data/`.

```bash
python -m mind.embeddings.ingest    # fetch + verify + embed + upsert
python -m mind.embeddings.verify    # table row count vs. manifest
```

## Setup

```bash
make dev
export MIND_ENVIRONMENT=local
# #481: connection settings come from these files, not a DSN env var --
# see src/mind/api/README.md "Configuration (#481)".
export CONFIG_FILE_PATH=config.example.yml SECRETS_FILE_PATH=secrets.example.yml
```

`embed()` downloads `BAAI/bge-small-en-v1.5` on first use unless `MODEL_CACHE_DIR` points at a local copy.

## Foundation Gate benchmark (week 3)

End-to-end walkthrough for someone outside the MIND team (#469's acceptance criterion):

```bash
cd teams/mind
docker compose up -d vector-store
make dev
make migrate-local
export MIND_ENVIRONMENT=local
export CONFIG_FILE_PATH=config.example.yml SECRETS_FILE_PATH=secrets.example.yml

python scripts/load_real_slice.py   # loads eval/real_slice/'s 16 real tasks
python scripts/query_sample.py      # one sample similarity query
```

Expected: `Loaded 16 real tasks from eval/real_slice/ into the vector store.`, then a deterministic ranked result for `query_sample.py` -- `embed()` has no randomness, so it's reproducible.

Full latency/recall benchmark against the same real slice: [`benchmarks/vector-store-comparison.md`](../../../benchmarks/vector-store-comparison.md)'s "Foundation Gate benchmark (week 3)" section and [`benchmarks/foundation_gate_benchmark.py`](../../../benchmarks/foundation_gate_benchmark.py).

## Run the tests

```bash
pytest
```

## Using the interface elsewhere in the app

```python
from mind.config import get_settings
from mind.embeddings import embed_async, task_text
from mind.embeddings.pgvector_store import PgVectorStore

index = PgVectorStore(get_settings().vector_store)  # one per process, open()/close() it
store = index.for_tenant(task.tenant_id)

vector = await embed_async(
    task_text(task.title, task.description)
)  # is_query=False (default) for indexing
await store.upsert(task.id, vector)

similar = await store.search(vector, k=5)  # best match first
await store.delete(task.id)
```

For a query against stored passages (e.g. a search endpoint), use `store.search_text(query, k)` instead -- it embeds with `is_query=True` so the flag can't be forgotten.

`task_text()` is the canonical title+description join -- every producer must use it, or their vectors aren't comparable to each other.

`is_completed`, `upsert_if_absent()`, `mark_completed()` and `mark_incomplete()` back the #470 index consumer:

```python
await store.upsert_if_absent(task_id, vector)
await store.mark_completed(task_id)
await store.mark_incomplete(task_id)  # e.g. a DONE task got reopened
```

`nearest_tasks(store, text, *, k, is_query=False, exclude_completed=False)` (`search.py`) is the shared primitive behind #471 and #245 -- `embed(text, is_query=is_query)` then `store.search(vector, k=k, exclude_completed=exclude_completed)`, no threshold. `is_query=True` for #245 (mention vs. passage); `False` for #471's symmetric comparison.

`resolve_dependency_targets(store, references, *, k=5, threshold=None, is_query=True)` (`dependency_resolution.py`) is #245/#244: a `list[str]` of free-text dependency mentions in, one ranked candidate list out per string. **"No match" is the default**: `threshold=None` reads `Settings.dependency_match_threshold` and filters, same as #244's own call. Pass `threshold=0.0` explicitly for the raw, unfiltered ranked list (Yonit's original #245 use case). Always requests `exclude_completed=True` from the store (index_consumer.py never deletes, only flips the flag -- a DONE task must never come back as a live match), regardless of `threshold`. The exclusion happens inside the SQL query itself (`PgVectorStore`'s `WHERE NOT is_completed`), not as a Python filter afterward -- otherwise a small `k` (e.g. `find_duplicate`'s `k=1`) could fetch only completed rows and be left with nothing, even with a real open match just outside that window:

```python
from mind.embeddings import resolve_dependency_targets

candidates = await resolve_dependency_targets(
    store, ["the DB upgrade"]
)  # filtered, [[SearchResult(...), ...]]
candidates = await resolve_dependency_targets(
    store, ["the DB upgrade"], threshold=0.0
)  # raw, unfiltered
```

`find_duplicate(store, title, description, *, threshold=None)` (`duplicate_detection.py`, #471) is the same pattern: `threshold=None` reads `Settings.duplicate_similarity_threshold`.

`store.get_vector(task_id)` returns the embedding already stored for a task_id (or `None` if it isn't indexed), for callers that already have a task in the system and don't want to re-embed its text. `store.search(..., exclude_task_id=...)` excludes a single task_id from the results, the same SQL-level way `exclude_completed` does -- needed whenever the query vector IS a stored row's own vector, or that row matches itself first.

`find_related(store, task_id, *, k=5, threshold=None)` (`related_tasks.py`, #472) is Mind's one capability called from outside the service, through R4's HTTP layer (#450) -- see "Serving this over HTTP" below for the endpoint. Up to k tasks related to `task_id`, ranked, `task_id` itself always excluded from its own results. `threshold=None` reads `Settings.related_task_threshold` -- a **lower, separate** bar from `duplicate_similarity_threshold`: the same pair can be related without being a duplicate (e.g. a prod and a staging deploy of the same change), so reusing the duplicate threshold here would miss exactly that case. Unlike `resolve_dependency_targets()`/`find_duplicate()`, a completed task is **not** excluded -- relatedness is topical context for display (what Lens/Inbox show alongside a task), not a live blocking check, so a finished related task is still useful context. A `task_id` with no embedding yet, an index with nothing else in it, and no candidate clearing the threshold all return `[]`, never an error:

```python
from mind.embeddings import find_related

related = await find_related(store, "task-123")  # [] if nothing qualifies
```

## Threshold calibration

`benchmarks/threshold_calibration.py` picks both thresholds from real similarity scores, not a guess. Method: a 21-task pool (`benchmarks/threshold_calibration_tasks.json`, hand-authored -- kept separate from `eval/real_slice`/`eval/datasets` on purpose, to avoid overlapping with R2/Michal's own evaluation data), 26 test sentences per calibration: 12 true matches (including deliberately hard near-miss pairs -- `prod` vs `staging`, `v2.3` vs `v2.4`, `login` vs `logout` crashes) and 14 true non-matches (including a couple that deliberately share vocabulary with a real task without being a duplicate of it -- `upgrading the office wifi router`, `Write a guide for new hires on our DB setup`), scored against the real store.

The threshold is chosen to separate real matches from real non-matches only. A close sibling that a matched case outranks (e.g. login-bug beating logout-bug into second place) is a ranking question, not a threshold question -- the real answer is still returned, still correct, and it's still above the bar; penalizing the threshold for that isn't right. An earlier version of this script did exactly that, which pushed `dependency_match_threshold` up to 0.754 and silently broke 2 genuine matches (API docs, prod deploy) beyond the single one (security audit) this README used to document. The sweep now scores `expected_similarity` (true positives) only against the best-other score of true non-match cases (true negatives) -- not against a matched case's runner-up -- and picks the midpoint of whichever gap between adjacent observed values misclassifies the fewest cases, not a value snapped onto one example. Re-run anytime: `python -m benchmarks.threshold_calibration`.

**`duplicate_similarity_threshold` = 0.761** (`is_query=False`, full task vs. full task):

| text | expected | exp. sim | best other | other sim |
|---|---|---|---|---|
| Move the prod DB to the newer version | task-db-upgrade | 0.804 | task-deploy-staging | 0.692 |
| Order food for the company offsite | task-catering | 0.801 | task-offboard-employee | 0.642 |
| Set up accounts for the new engineer joining | task-onboard-hire | 0.859 | task-ui-redesign | 0.613 |
| Review access logs across services this quarter | task-security-audit | 0.805 | task-perf-review | 0.640 |
| Investigate the urgent support ticket from a customer | task-customer-escalation | 0.841 | task-load-test | 0.603 |
| Document the new endpoints from this sprint | task-api-docs | 0.855 | task-release-notes | 0.638 |
| Push the DB migration out to prod | task-deploy-prod | **0.712 (below the chosen threshold -- a false negative)** | task-db-backup | 0.749 |
| Push the DB migration out to staging | task-deploy-staging | 0.828 | task-db-backup | 0.756 |
| Publish the 2.3 release notes | task-release-v23 | 0.881 | task-release-v24 | 0.843 |
| Publish the 2.4 release notes | task-release-v24 | 0.886 | task-release-v23 | 0.848 |
| App crashes when logging in on phones | task-login-bug | 0.867 | task-logout-bug | 0.829 |
| App crashes when logging out on phones | task-logout-bug | 0.869 | task-login-bug | 0.833 |
| Water the office plants | (none) | - | task-api-docs | 0.533 |
| Book a conference room for Friday | (none) | - | task-catering | 0.598 |
| Fix the coffee machine in the break room | (none) | - | task-catering | 0.582 |
| File this quarter's tax paperwork | (none) | - | task-budget-forecast | 0.678 |
| Replace the printer on the third floor | (none) | - | task-api-docs | 0.579 |
| Book the dentist appointment for next week | (none) | - | task-load-test | 0.584 |
| Plan the company holiday party | (none) | - | task-catering | 0.720 |
| Book flights for the upcoming conference | (none) | - | task-budget-forecast | 0.613 |
| Review expense reports from last month's travel | (none) | - | task-perf-review | 0.647 |
| Clean out the supply closet | (none) | - | task-offboard-employee | 0.617 |
| Update the office holiday calendar | (none) | - | task-api-docs | 0.664 |
| Renovate the parking garage entrance | (none) | - | task-ui-redesign | 0.561 |
| Upgrade the office wifi router firmware | (none) | - | task-api-docs | 0.649 |
| Write a guide for new hires on our DB setup | (none) | - | task-onboard-hire | 0.696 |

**`dependency_match_threshold` = 0.666** (`is_query=True`, mention vs. passage):

| text | expected | exp. sim | best other | other sim |
|---|---|---|---|---|
| the DB upgrade | task-db-upgrade | 0.790 | task-deploy-staging | 0.667 |
| team offsite food | task-catering | 0.757 | task-offboard-employee | 0.527 |
| onboarding the new hire | task-onboard-hire | 0.803 | task-offboard-employee | 0.564 |
| security audit | task-security-audit | **0.650 (below the chosen threshold -- a false negative)** | task-offboard-employee | 0.510 |
| customer escalation | task-customer-escalation | 0.754 | task-offboard-employee | 0.559 |
| API docs | task-api-docs | 0.674 | task-release-v23 | 0.517 |
| prod deploy | task-deploy-prod | 0.681 | task-deploy-staging | 0.670 |
| staging deploy | task-deploy-staging | 0.848 | task-deploy-prod | 0.738 |
| v2.3 notes | task-release-v23 | 0.768 | task-release-v24 | 0.707 |
| v2.4 notes | task-release-v24 | 0.772 | task-release-v23 | 0.717 |
| login crash | task-login-bug | 0.834 | task-logout-bug | 0.797 |
| logout crash | task-logout-bug | 0.866 | task-login-bug | 0.744 |
| the weather forecast | (none) | - | task-budget-forecast | 0.580 |
| lunch plans for today | (none) | - | task-catering | 0.634 |
| fixing the office coffee machine | (none) | - | task-offboard-employee | 0.497 |
| quarterly tax filing | (none) | - | task-perf-review | 0.656 |
| replacing the office printer | (none) | - | task-api-docs | 0.539 |
| scheduling the dentist appointment | (none) | - | task-load-test | 0.536 |
| the company holiday party | (none) | - | task-catering | 0.580 |
| booking flights for the conference | (none) | - | task-budget-forecast | 0.575 |
| reviewing expense reports for travel | (none) | - | task-perf-review | 0.645 |
| organizing the supply closet | (none) | - | task-budget-forecast | 0.572 |
| the new company holiday calendar | (none) | - | task-load-test | 0.550 |
| the parking garage renovation | (none) | - | task-offboard-employee | 0.530 |
| upgrading the office wifi router | (none) | - | task-api-docs | 0.596 |
| writing the team's weekly newsletter | (none) | - | task-standup-notes | 0.658 |

**`related_task_threshold` = 0.711** (task-to-task, via each task's own stored vector -- not fresh text):

Unlike the two tables above, both sides here are real task_ids from the 21-task pool, compared task-to-task (matching how `find_related()` actually queries, via `get_vector()`). The "related" positives are #471's own hard-pair siblings -- the issue names this exact case (a prod deploy and a staging deploy of the same change) as related, not duplicate -- plus db-upgrade/db-backup (same DB-maintenance theme, clearly not the same task). Every other task in the pool has no designed relation, so its best-other score is this calibration's noise floor.

| task | expected related | exp. sim | best other | other sim |
|---|---|---|---|---|
| task-deploy-prod | task-deploy-staging | 0.919 | task-db-backup | 0.755 |
| task-deploy-staging | task-deploy-prod | 0.919 | task-db-upgrade | 0.720 |
| task-release-v23 | task-release-v24 | 0.961 | task-release-notes | 0.854 |
| task-release-v24 | task-release-v23 | 0.961 | task-release-notes | 0.852 |
| task-login-bug | task-logout-bug | 0.938 | task-customer-escalation | 0.563 |
| task-logout-bug | task-login-bug | 0.938 | task-customer-escalation | 0.539 |
| task-db-upgrade | task-db-backup | 0.762 | task-deploy-prod | 0.742 |
| task-db-backup | task-db-upgrade | 0.762 | task-deploy-prod | 0.755 |
| task-catering | (none) | - | task-onboard-hire | 0.589 |
| task-standup-notes | (none) | - | task-release-notes | 0.638 |
| task-onboard-hire | (none) | - | task-offboard-employee | 0.616 |
| task-offboard-employee | (none) | - | task-onboard-hire | 0.616 |
| task-security-audit | (none) | - | task-perf-review | 0.633 |
| task-vendor-contract | (none) | - | task-api-docs | 0.603 |
| task-customer-escalation | (none) | - | task-load-test | 0.588 |
| task-perf-review | (none) | - | task-security-audit | 0.633 |
| task-budget-forecast | (none) | - | task-release-notes | 0.659 |
| task-ui-redesign | (none) | - | task-api-docs | 0.613 |
| task-api-docs | (none) | - | task-release-notes | 0.620 |
| task-load-test | (none) | - | task-customer-escalation | 0.588 |

`task-release-notes` was deliberately left out of the negative side of this table -- a real run showed it scoring 0.85+ against both v2.3 and v2.4's release notes, i.e. it genuinely *is* related to both ("release notes" generalizes "v2.3 release notes"). Labeling it a negative would have been a wrong label, the same mistake this section's method now avoids for the other two thresholds.

**Real findings, not smoothed over:**
- Each calibration has exactly one false negative in this sample, and neither is a threshold problem:
  - **Duplicate:** task-deploy-prod (0.712) scores below 0.761, but raising or lowering the threshold doesn't fix it -- `find_duplicate`'s own `k=1` lookup would never even retrieve it: task-db-backup outranks it (0.749 vs 0.712). This is a ranking confusion between genuinely similar siblings, independent of where the threshold sits.
  - **Dependency:** "security audit" (0.650) is a genuinely low-similarity paraphrase, not a methodology artifact -- lowering the threshold to catch it would let in "writing the team's weekly newsletter" (0.658), a true non-match that happens to share vocabulary with task-standup-notes.
  - **Related:** none -- this table separates cleanly (lowest positive 0.762 vs. highest negative 0.659, once task-release-notes is excluded for the reason above).
- `prod`/`staging`, `v2.3`/`v2.4`, and `login`/`logout` pairs still score within ~0.03-0.06 of each other. This no longer affects the threshold itself (a correct answer outranking its own sibling isn't penalized -- see above), but a consumer that needs that exact distinction should still not rely on similarity alone (e.g. exact substring/number matching as a second check).

**Known limitation:** chosen on 21 hand-authored synthetic tasks (20 cases for the related-task calibration, 26 synthetic test sentences each for the other two), not real production data. Must be re-validated once real tasks/mentions exist (week 8) -- re-run the same script against real data without changing the method.

## #472: related-task search latency

`benchmarks/related_tasks_benchmark.py` measures `find_related()` end to end (`get_vector()` + `search()`, not just raw `search()` -- #87's `vector_search_benchmark.py` already covers that alone), twice:

- **At the order of magnitude this repo actually has real data for today** (the same 21-task pool above -- the real `task_embeddings` table has 0 rows in a fresh local dev environment; see "Known limitations" below): **p50 14.07ms, p95 20.41ms** (100 queries).
- **At 10,000+ synthetic filler rows** (same approach as #87), to check the path still clears the target at the scale #469's week-8 proof is about -- **not** a claim about current production volume: **p50 18.84ms, p95 23.76ms**, comfortably under the 300ms target.

Re-run: `python -m benchmarks.related_tasks_benchmark`.

## Serving this over HTTP (#450, R4)

Agreed with R4 and wired into `api/v1/router.py` (`src/mind/contracts/related_tasks.py` for the response model) -- see `docs/API.md` for the full contract:

```
GET /v1/tasks/{task_id}/related
-> 200 {"related": [{"task_id": str, "similarity": float}, ...]}
```

The router reads the tenant of `task_id` from its row (`index.tenant_of()`) and calls `find_related()` on `index.for_tenant(...)`, so results never cross tenants (#841).

`related: []` covers every "nothing to return" case alike -- an unindexed `task_id` (or one indexed before #841, with no tenant yet), an index with nothing else in it, or no candidate clearing the threshold -- matching `find_related()`'s own contract (see above). Not a 404: Mind doesn't own the canonical task registry, so "unknown to Mind's index" isn't the same claim as "doesn't exist" -- a clean empty result, not an error, for all of them. `threshold` isn't exposed as a request param (R4's call); `k` could be added later as an optional query param without breaking anything.

## Connection resilience

`index_consumer_runner.py` treats any exception from a handler as fatal
(`os._exit`, so a stuck/dead connection can't quietly retry forever against
the same backend) -- so `PgVectorStore`'s pool can't hand out a connection
it doesn't believe is live. The one deliberate exception: an `on_indexed`
callback (#918) is wrapped in its own try/except inside `handle_task_created`
and never escapes to this fatal path -- its own state, if any, is durable
(Postgres), so a failed attempt is safe to retry from the next indexed
task rather than worth taking the whole consumer down for.

**Open question (review, not settled here):** that `except Exception` is
broad -- it catches a config/logic bug in the callback the same way it
catches a transient Postgres hiccup, silently. Narrowing it to
Postgres-specific exceptions (`psycopg.OperationalError`, pool errors)
would mean `index_consumer.py`'s dispatch has to know about a specific
backend, which today it doesn't -- it's backend-agnostic by design. A
narrower catch could instead live in `index_consumer_runner.py` (which
already knows it's Postgres), wrapping the callback there rather than in
`handle_task_created` itself. Not done here; flagging for the team
lead's call on whether that coupling is worth it.

Three mechanisms, each covering a gap the others don't:

- `check=AsyncConnectionPool.check_connection` -- probes a connection
  before handing it to a caller; a dead one is discarded and replaced
  instead of reaching application code at all.
- TCP keepalives (`keepalives_idle=30`, `keepalives_interval=10`,
  `keepalives_count=3`) -- the only way an *idle* connection sitting in the
  pool notices it was silently dropped (NAT/LB/firewall), since nothing
  else touches it until the next checkout.
- `tcp_user_timeout=10000` (10s) -- bounds how long a query sent on an
  already-dead connection can hang waiting for a TCP retransmit that will
  never be ACKed, instead of the OS default (minutes).

All three are defaults only, set via the pool's `kwargs=`, and skipped per
key if the configured DSN already sets that key -- an env-specific DSN
override always wins.

## Known limitations (flag these in review)

- Real-data coverage is just the 2-document FUNSD slice (PR #141) -- enough to validate pipeline shape, not recall quality.
- The real `task_embeddings` table has 0 rows in a fresh local dev environment -- #472's latency numbers at "the current real order of magnitude" use the 21-task calibration pool as a stand-in for that order of magnitude, not an actual production measurement.
