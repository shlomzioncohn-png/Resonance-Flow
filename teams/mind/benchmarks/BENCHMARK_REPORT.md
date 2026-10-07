# MIND Vector Store — Benchmark & Semantic Evaluation Report

Evaluation of the `mind_vector` pgvector-backed PoC (`Choose a vector database/mind_vector_poc/`):
validate the task-data contract, generate a mock task corpus, run the full
`embed -> upsert -> search` pipeline with metadata, and check semantic-query
quality with positive and negative test cases. (Latency at the full
10,000-vector load target, plus recall, are covered separately in
[`../scale_and_recall/SCALE_AND_RECALL_REPORT.md`](../scale_and_recall/SCALE_AND_RECALL_REPORT.md) —
this report's own latency numbers are only at 500 vectors.)

Environment: fresh `pgvector/pgvector:pg16` Docker container, accessed only
through the public `mind_vector` interface (`embed`/`upsert`/`search`) — no
test bypassed the abstraction.

## TL;DR

| Question | Answer |
|---|---|
| Does latency meet the <300ms p95 target at 500 vectors? | **Yes**, by a wide margin (query p95 = 5.75ms — ~50x under budget). See `../scale_and_recall/` for the 10,000-vector (target-scale) result. |
| Does the "customer pricing proposal" query return relevant top-5 results? | **Yes** — 4 of 5 results were pricing/sales tasks, top-1 exact match. |
| Do 10-20 semantic queries reliably return relevant results? | **Partially.** Top-1 accuracy is 100%, but avg top-5 precision is only **42%**, and **11/20 (55%)** queries leaked an explicitly-unrelated category into the top-5. |
| Root cause of the semantic misses? | The PoC's `embed()` is a hashing bag-of-words vectorizer, not a real semantic model — already flagged as a known limitation in `README.md`. It matches literal word overlap, not meaning. This eval quantifies that gap for the first time. |
| Would a real embedding model actually fix it? | **Yes, confirmed** — swapping in `all-MiniLM-L6-v2` on the same corpus/queries raised avg top-5 precision from 41% to 68% (§6). |

---

## 1. Data contract check

Before generating test data, the expected input/output shape was cross-checked
against the interface owner's own definitions (`base.py`, `pgvector_store.py`,
`README.md`, `tests/test_vector_store.py`). Full writeup: [`data_contract.md`](data_contract.md).

**Result: matches.** `task_id` + `text` is the confirmed contract, and it's the
same shape the interface's own acceptance test
(`test_future_data_source_uses_same_interface`) already exercises for
multi-source data (GitHub issues, CRM tickets, etc.).

Two gaps were found and are **not** blockers for this evaluation, but are
worth the team's attention:

1. **No metadata field in the interface** — by design (metadata lives in
   MIND's own `Tasks` table, joined by `task_id`, per the architecture in
   `MIND-Vector-Store-Comparison-EN-updated.md` §2). This evaluation mirrors
   that: metadata is kept in a local sidecar
   ([`task_metadata_store.json`](task_metadata_store.json)) and joined back
   onto `search()` results by `task_id`, never sent through `mind_vector`.
2. **`search()` returns no similarity score** — `config.py` defines
   `DUPLICATE_SIMILARITY_THRESHOLD = 0.90` for duplicate detection, but no
   caller can currently apply it because nothing returns a distance/score.
   This is a real gap for the duplicate-detection feature the README
   describes, independent of the embedding-quality issue below.

---

## 2. Latency benchmarks

Same methodology as the original PoC benchmark (real wall-clock time
through the public interface, one call at a time, no batching):

| Dataset size | Op | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) |
|---|---|---:|---:|---:|---:|
| 500 vectors | upsert | 6.81 | 8.86 | 13.86 | 29.39 |
| 500 vectors | search | 4.88 | 5.75 | 5.98 | 7.09 |

Raw output: [`../../benchmark_results.txt`](../../benchmark_results.txt).

**Target from `../../../MIND-Vector-Store-Comparison-EN-updated.md` (<300ms p95): PASS**, with roughly
50x headroom at 500 vectors. No latency concerns at this scale, but this
is well short of the 10,000+ vector load target — that gap is closed in
[`../scale_and_recall/SCALE_AND_RECALL_REPORT.md`](../scale_and_recall/SCALE_AND_RECALL_REPORT.md),
which re-runs this same measurement at the full target scale **and** adds
the recall testing this report doesn't cover. (An earlier intermediate
1000-vector-only run existed here; removed as superseded once the 10,000-row
scale+recall run covered the same ground more rigorously — see that report.)

---

## 3. Mock task corpus

80 mock tasks generated across 8 categories representative of MIND's expected
upstream sources, each with `task_id`, `text`, and `metadata`
(title, description, category, source, priority, created_at). See
[`mock_tasks.py`](mock_tasks.py).

| Category | Count | Simulated source |
|---|---:|---|
| sales_pricing | 10 | crm |
| support_billing | 10 | crm |
| engineering_bugs | 10 | github |
| devops_infra | 10 | github |
| hr_admin | 10 | internal |
| marketing | 10 | internal |
| product_feature | 10 | internal |
| customer_support_general | 10 | zendesk |

Pipeline run for all 80: `text -> embed() -> vector -> upsert(task_id, vector)`,
with `embedding + task_id + metadata` persisted (`vector` into pgvector via
`mind_vector`; `metadata` into the local sidecar). Insert latency:
**avg 6.64ms, p95 8.89ms** — consistent with the 500-vector benchmark
above and the later 10,000-row scale test (`../scale_and_recall/`).

---

## 4. Semantic query evaluation (20 queries)

Each query checks two things against the top-5 results:
- **Positive**: does the query's `expected_category` dominate the top-5?
- **Negative**: does an explicitly-unrelated category leak into the top-5?

Full detail (all 20 queries, full top-5 lists): [`semantic_eval_results.json`](semantic_eval_results.json).
Query definitions: [`queries.py`](queries.py).

| # | Query | Expected category | Result |
|---|---|---|---|
| 1 | **"customer pricing proposal"** (team-lead's example) | sales_pricing | **PASS** — top-1 exact match, 4/5 top-5 in category |
| 2 | quote for enterprise customer discount | sales_pricing | PASS |
| 3 | negotiate contract terms with a large client | sales_pricing | FAIL — "Process resignation notice" (hr_admin) leaked in |
| 4 | invoice refund request from client | support_billing | FAIL — "Annual leave request approval" (hr_admin) leaked in |
| 5 | customer complains about billing overcharge | support_billing | PASS |
| 6 | duplicate invoice sent to a client | support_billing | FAIL — "Duplicate invoices being generated" (engineering_bugs) leaked in on literal word overlap ("duplicate", "invoice") |
| 7 | null pointer exception in checkout flow | engineering_bugs | FAIL — "Trade show booth planning" (marketing), "Update emergency contact info" (hr_admin) leaked in |
| 8 | app crashes on empty search | engineering_bugs | FAIL — "Custom quote for reseller" (sales_pricing) leaked in |
| 9 | flaky test failing intermittently in CI | engineering_bugs | PASS |
| 10 | server crashed in production overnight | devops_infra | PASS |
| 11 | kubernetes pod stuck restarting | devops_infra | PASS |
| 12 | database deployment rollback needed | devops_infra | FAIL — "Beta program feedback triage" (product_feature) leaked in |
| 13 | employee onboarding paperwork for new hire | hr_admin | FAIL — "Certificate renewal" (devops_infra) leaked in |
| 14 | annual leave request approval | hr_admin | FAIL — "Discount approval request", "Upsell proposal..." (sales_pricing) leaked in on the word "approval"/"proposal" |
| 15 | payroll discrepancy for a contractor | hr_admin | FAIL — "Newsletter content review" (marketing) leaked in |
| 16 | social media campaign for product launch | marketing | PASS |
| 17 | newsletter content review for customers | marketing | PASS |
| 18 | new analytics widget on the dashboard | product_feature | FAIL — "Update employee benefits" (hr_admin) leaked in |
| 19 | roadmap planning for next quarter | product_feature | FAIL — "Broken pagination on task list", "Crash on empty search query" (engineering_bugs) leaked in |
| 20 | user can't log into their account after password reset | customer_support_general | PASS — 5/5 top-5 in category |

**Summary: 9/20 passed, 11/20 failed. Top-1 accuracy 100%. Avg top-5 precision 42%.**

---

## 5. Failure cases

### Latency — none
All latency numbers, at both 500 and 1000 vectors, are well within the
<300ms p95 requirement. Not a current risk.

### Semantic quality — 11/20 queries (55%)
Every failure traces to the same root cause: `embeddings.py`'s `embed()` uses
`HashingVectorizer` + TF-IDF reweighting — a deterministic bag-of-word-hashes
technique, explicitly documented in the file's own docstring as **not** a
semantic embedding model. It reliably finds near-exact phrase matches (hence
100% top-1 accuracy — the query text is often close to a task's title), but
has no notion of meaning:
- **Synonym/paraphrase blindness**: "duplicate invoice sent to a client"
  doesn't preferentially match `support_billing` phrasing over an
  `engineering_bugs` task that happens to share the literal words
  "duplicate" and "invoice".
- **Generic-word collisions**: "annual leave request **approval**" pulls in
  `sales_pricing` tasks titled "Discount **approval** request" and "Upsell
  **proposal**..." purely because of shared common words, not topical
  relation.

This matches exactly what `README.md`'s "Known POC limitations" section
already predicted — this evaluation is the first time it's been measured
with numbers (42% avg top-5 precision) rather than flagged qualitatively.

### Data-contract gap (see §1)
`search()` has no similarity-score return path, so
`DUPLICATE_SIMILARITY_THRESHOLD` in `config.py` cannot currently be applied
by any caller.

### Test isolation gap (found while re-running the existing suite)
After loading the 80-task mock corpus into the same table `tests/` uses,
`test_search_is_independent` (which the file's own docstring claims runs
"on its own, no DB involved" beyond the store) started failing: with `k=3`
and 80+ extra rows now in the table, the expected `far_id` no longer makes
the top-3. The test was never actually isolated from whatever else is in
the table — it happened to pass before only because the table was empty.
Not something this evaluation needed to fix, but worth flagging: any shared
dev/CI Postgres used for both this test suite and manual/eval runs will hit
this. (Table was truncated back to empty after this evaluation so the
existing suite passes clean again.)

---

## 6. Before/after: real embedding model

To confirm the root cause and quantify the fix, the same 80-task corpus and
20 queries were re-run with `sentence-transformers/all-MiniLM-L6-v2` in
place of `HashingVectorizer` — in-memory brute-force cosine similarity, no
pgvector/`mind_vector` code touched. Script:
[`compare_real_model.py`](compare_real_model.py), full results:
[`model_comparison_results.json`](model_comparison_results.json).

| Metric | HashingVectorizer (current PoC) | all-MiniLM-L6-v2 (real model) |
|---|---:|---:|
| Top-1 accuracy | 100% | 100% |
| Avg top-5 precision | 41% | **68%** |
| Passed / 20 | 9 | **14** |

6 of the 11 originally-failing queries pass outright with the real model —
including exactly the synonym/paraphrase cases predicted in §5 (e.g.
"invoice refund request from client", "null pointer exception in checkout
flow", "app crashes on empty search"). The remaining 6 failures (e.g.
"annual leave request approval", "roadmap planning for next quarter") are
shorter/more generic queries where even a real model has to disambiguate
from limited context — a corpus-size and query-specificity limitation, not
an embedding-model bug.

**This confirms the embedding model is the primary lever**: same corpus, same
queries — swapping only the embedding function took avg top-5 precision from
41% to 68%. (The hashing baseline here is 41% vs. 42% in §4 — this script
uses exact brute-force cosine similarity for both models, while §4 goes
through pgvector's approximate HNSW index; the 1-point gap is index
approximation noise, not a discrepancy in the method.)

---

## 7. Recommendations

1. **Swap `embed()` for a real sentence-embedding model** (e.g.
   `sentence-transformers/all-MiniLM-L6-v2`, already suggested in
   `embeddings.py`'s own docstring) before relying on semantic search in
   production. §6 above shows this alone raises avg top-5 precision from
   41% to 68% on the same corpus and queries. Note: `all-MiniLM-L6-v2`
   outputs 384-dim vectors vs. the current `EMBEDDING_DIM = 256`, so the
   pgvector column (`VECTOR(256)`) and `EMBEDDING_DIM` constant need to
   change together with the swap.
2. **Add a similarity score to `search()`** (e.g. `search_with_scores()`
   returning `list[tuple[task_id, float]]`) so
   `DUPLICATE_SIMILARITY_THRESHOLD` becomes usable.
3. **Re-run this same harness** (`run_semantic_eval.py`, same 80-task corpus,
   same 20 queries) after the model swap for an apples-to-apples
   before/after comparison — that's exactly why the corpus and queries were
   built as reusable, deterministic modules rather than one-off scripts.
4. ~~Re-run the latency benchmark at ~10,000 vectors before final
   sign-off~~ — **done**, along with recall measurement (which this report
   doesn't cover at all): see
   [`../scale_and_recall/SCALE_AND_RECALL_REPORT.md`](../scale_and_recall/SCALE_AND_RECALL_REPORT.md).

---

## Files produced by this evaluation

| File | Contents |
|---|---|
| [`data_contract.md`](data_contract.md) | Input/output contract check against `base.py`/`pgvector_store.py`/tests, gaps found |
| [`mock_tasks.py`](mock_tasks.py) | 80-task mock corpus generator (8 categories, realistic metadata) |
| [`queries.py`](queries.py) | 20 semantic query test cases (positive + negative expectations) |
| [`run_semantic_eval.py`](run_semantic_eval.py) | Full pipeline: load corpus, run 20 queries, score pass/fail |
| [`semantic_eval_results.json`](semantic_eval_results.json) | Full machine-readable results (all 20 queries, full top-5 lists) |
| [`task_metadata_store.json`](task_metadata_store.json) | Mock "Tasks table" sidecar (task_id -> metadata), joined onto search results |
| [`compare_real_model.py`](compare_real_model.py) | Before/after: same corpus/queries with a real sentence-embedding model instead of the hashing placeholder |
| [`model_comparison_results.json`](model_comparison_results.json) | Full results of the before/after comparison |
| `BENCHMARK_REPORT.md` | This report |
