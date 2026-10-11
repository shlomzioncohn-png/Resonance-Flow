# POC — AI Insights on the Personal Task Screen

Input files for the experiment: three developers independently test a different capability of
the same LLM, all based on the same base JSON. The intended feature: personal, positive,
constructive feedback on work patterns — not employee ranking or managerial tracking. INBOX data
only, no overlap with CHAIN's responsibilities. Facts and calculations are done in code; the LLM
is used to identify insights, phrase recommendations, and verify claims — it is never the source
of truth.

## Files

| File | For | Contains |
|---|---|---|
| `shared-data.json` | All three developers | The unified JSON: current/previous period, on-time completion, breakdown by estimated effort, currently-open tasks. |
| `config.json` | All three developers | **Only** what actually becomes part of the fixed instructions (system prompt): the `short`/`medium`/`long` boundary definitions (30 / 120 minutes), so the model can correctly phrase sentences like "tasks over 2 hours". This is a fixed rule that doesn't vary per employee, so it's written as fixed text in the instructions, not as a file attached to the call. |
| `developer-2-recommendation-insights.json` | Developer 2 — Recommendation Generation | Five ready-made insights; for each, check whether a practical recommendation is warranted, or `null` when none is needed. |
| `developer-3-verification-insights.json` | Developer 3 — Insight Verification | Eight claims to check against `shared-data.json`. **This is the file sent to the LLM.** |
| `developer-3-expected-results.json` | Developer 3 — Insight Verification | The expected result (`SUPPORTED` / `CONTRADICTED` / `INSUFFICIENT_EVIDENCE`) for each claim, by `id`. **Not sent to the LLM** — used only for scoring at the end. |

## Guiding principle

For every LLM call: `shared-data.json` + the relevant insights file for that developer, plus the
fixed instructions (system prompt) — the boundary-definition line from `config.json`
(short/medium/long). The expected-results file is never part of the model's input.

Developer 1 (Insight Discovery) works only against `shared-data.json`, with no ready-made
insights list — her task is to get the LLM to identify the insights on its own.

## System rules that never reach the LLM at all

These aren't in `config.json` because they aren't part of the system prompt — they're only
relevant to the code that computes `shared-data.json` before it's sent, so they're documented
here as text rather than as a file:

- **`analysisWindowDays` (30 days)** — determines which dates to compute for the current/previous
  period. The result (`period.current.from/to`, `period.previous.from/to`) is already "baked in"
  as data inside `shared-data.json`; the model doesn't need to know the window is 30 days to
  justify the insight.
- **`minSample` (currently 5, global)** — a threshold the code checks *before* calling the LLM: if
  there isn't enough sample data, the insight isn't sent at all, or a computed result such as
  `"enoughData": true/false` is included in the data instead. The number 5 itself shouldn't reach
  the model.
- **"Significant change" threshold** — still open, no value defined yet (see §8 of the POC
  summary).
