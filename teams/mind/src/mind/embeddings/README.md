# embeddings

Vectors, semantic search, duplicate/related-task detection.

## Decisions (closed)

- **Vector store: pgvector** — see [`vector-store-comparison.md`](../../../benchmarks/vector-store-comparison.md).
- **Embedding model: `BAAI/bge-small-en-v1.5`**, 384-dim, CPU-only, English-only. Asymmetric -- queries need `is_query=True`. Local weights optional via `MODEL_CACHE_DIR`.
- **Load target** (week 8, provisional): 10,000+ records, <300ms p95.

## What's here

```
__init__.py          public interface -- embed()/embed_batch() (+ async), SearchResult, VectorStore, nearest_tasks(), resolve_dependency_targets()
embeddings.py         embed() family -- the real model
base.py               VectorStore abstract contract + SearchResult
pgvector_store.py     the ONLY file that knows Postgres/pgvector
index_consumer.py     #470 -- Kafka task.{created,updated,completed} handlers
index_consumer_runner.py  #470 -- the process: python -m mind.embeddings.index_consumer_runner
search.py             nearest_tasks() -- the shared embed()+search() primitive behind #471/#245
dependency_resolution.py  #245 -- resolve_dependency_targets(): free-text dependency mentions -> ranked candidate task_ids
loader.py             fetches + verifies the real FUNSD manifest slice
ingest.py             loader -> embed_batch_async -> upsert
verify.py             table row count vs. manifest record count
```

Config lives in `mind.config`. Only `PgVectorStore` is constructed directly outside `__init__.py` -- no factory/singleton.

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
export MIND_VECTOR_STORE__DSN="postgresql://mind:mind@localhost:5432/mind"
```

`embed()` downloads `BAAI/bge-small-en-v1.5` on first use unless `MODEL_CACHE_DIR` points at a local copy.

## Foundation Gate benchmark (week 3)

End-to-end walkthrough for someone outside the MIND team (#469's acceptance criterion):

```bash
cd teams/mind
docker compose up -d vector-store
make dev
export MIND_ENVIRONMENT=local
export MIND_VECTOR_STORE__DSN="postgresql://mind:mind@localhost:5432/mind"

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

store = PgVectorStore(get_settings().vector_store)

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

**Real findings, not smoothed over:**
- Each calibration has exactly one false negative in this sample, and neither is a threshold problem:
  - **Duplicate:** task-deploy-prod (0.712) scores below 0.761, but raising or lowering the threshold doesn't fix it -- `find_duplicate`'s own `k=1` lookup would never even retrieve it: task-db-backup outranks it (0.749 vs 0.712). This is a ranking confusion between genuinely similar siblings, independent of where the threshold sits.
  - **Dependency:** "security audit" (0.650) is a genuinely low-similarity paraphrase, not a methodology artifact -- lowering the threshold to catch it would let in "writing the team's weekly newsletter" (0.658), a true non-match that happens to share vocabulary with task-standup-notes.
- `prod`/`staging`, `v2.3`/`v2.4`, and `login`/`logout` pairs still score within ~0.03-0.06 of each other. This no longer affects the threshold itself (a correct answer outranking its own sibling isn't penalized -- see above), but a consumer that needs that exact distinction should still not rely on similarity alone (e.g. exact substring/number matching as a second check).

**Known limitation:** chosen on 21 hand-authored synthetic tasks and 52 synthetic test sentences (26 per calibration), not real production data. Must be re-validated once real tasks/mentions exist (week 8) -- re-run the same script against real data without changing the method.

## Known limitations (flag these in review)

- Real-data coverage is just the 2-document FUNSD slice (PR #141) -- enough to validate pipeline shape, not recall quality.
