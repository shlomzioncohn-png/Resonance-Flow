# MIND Evaluation Harness

This directory contains the evaluation engine for the **Resonance Flow MIND team**.

The harness is designed to:

- Load a fixed set of persona scenarios.
- Execute a ranking model against those scenarios through a uniform interface.
- Compute performance metrics for the evaluated model.

## Project Context

- **Immutable Scenarios:** Scores a ranking model against `eval/personas/` — a fixed scenario set, never touched mid-comparison.
- **Foundation Gate:** Must be runnable by someone outside the MIND team.
- **Lifecycle:** Used to score the baseline (week 5; R2 publishes those scores to Chain in week 6) and for the stretch-goal comparison (week 7, demo day week 9).

## The Model Interface (Contract) — closed, approved

Every ranking model must implement `RankingModel` (`interfaces.py`) — a `typing.Protocol`,
so a model satisfies the contract structurally, without inheriting from it:

```python
class RankingModel(Protocol):
    def score(self, task: Task, context: ScoreContext) -> ScoreResult:
        """Return a ScoreResult (0-1 priority score) for a single task, given the
        current time via context. Higher score = higher priority."""
        ...
```

- `Task` (`interfaces.py`): `task_id`, `title`, `description`, `deadline`,
  `effort_estimate` (minutes; the eval set's form of the canonical
  `Task.effort_estimate`), `tier`, `blocked`, `parent_task_id` (nullable,
  reserved for the week-7 hierarchical-propagation eval).
- `ScoreContext`: just `current_datetime` — the model may judge how near a
  `deadline` is, and nothing else about the persona's situation (per the mentor's
  review). The harness fixes it to the scenario's `current_datetime`; it is never
  the real wall clock.
- `ScoreResult`: `score` (float on a global **0-1** scale — each tier may be
  scored by its own rule, but results are calibrated onto one scale so tiers are
  comparable) and an optional `reasoning` string (kept in the report for later
  comparison against `expected_reason`, not used by the harness), plus optional
  `factors` (`ScoreFactors`, `None` = no breakdown) — what produced the score:
  - `contributions`: `{name: float}`, at least one entry, finite values (negative
    allowed), not required to sum to `score`. The contract names no factors:
    each model owns its own fixed vocabulary, and the test that keeps its keys
    inside it, in its own package.
  - `blocked`: whether the model applied a blocked adjustment to this score.
  - `base_score`: the score before that adjustment, when the model has one.

  Carried per candidate into the report (a copy, not the model's object), never
  used for ranking; it's there so an explanation step can be built from the
  real inputs to the score.

The report itself is Pydantic too, all in `harness.py`: `report.json` is an
`EvaluationOutput` (`model_name`, `combined`, `groups`), each report a `Report`
→ `ScenarioResult` → `CandidateScore`. So the file read back with
`EvaluationOutput.model_validate_json(...)` is validated the same way as a report
built by `run()`: unknown fields, NaN/inf and malformed factors fail on load.
`run.py` also refuses to write NaN/inf to `report.json` rather than produce
invalid JSON (it uses `json.dumps(..., allow_nan=False)`, not `model_dump_json()`,
which would silently write NaN as `null`).

`score()` still never sees `persona`, `free_time_minutes`, `constraints`, or the
sibling tasks.

The whole persona scenario schema now lives in `interfaces.py` as Pydantic models
(`PersonaEvaluationScenario` and its `Persona` / `Context` / `Task` / `Source`
parts) — that model is the binding source of truth. `eval/personas/scenario.schema.json`
is generated from it (`make schema` / `python -m mind.evaluation.schema`) and kept
honest by `tests/test_schema_snapshot.py`; `make lint` runs the staleness check.

The harness (`harness.py`) calls `score()` once per candidate task in a scenario, and
builds the ranking itself from the returned scores — a model never ranks a whole
scenario or picks a winner directly. This keeps the contract consistent with the
system-wide boundary: **MIND scores, it does not decide** (see
`MIND-01-System-Overview.md` §4) — the per-task score is the API surface, same as the
boundary with Chain.

## Running the Evaluation

You can run the evaluation harness locally from the command line.

Pass the name of the model you wish to evaluate using the `--model` argument:

```bash
python -m mind.evaluation.run --model dummy
```

Replace `dummy` with the name of the model you want to evaluate.

`--personas` (default `eval/personas`) points to the personas directory. The
scenario files live in two subdirectories — `same_tier/` and `cross_tier/` (see
`eval/personas/README.md`) — and `load_personas` recurses into both;
`scenario.schema.json` at the root is skipped. `make eval` scores each group
separately, breaks the result down per `persona.role`, and prints a combined
number too; `report.json` carries all of them (`combined`, `groups`, `roles`).
The per-role numbers are split out of the combined run, not re-scored, so
they always add up to it. Pass a single group dir
(`--personas eval/personas/same_tier`) to score just that group. Loading goes through
`mind.evaluation.personas.load_personas`/`validate_personas` (the coverage /
count checks run against the combined set). `load_personas`
parses each file into a `PersonaEvaluationScenario` (Pydantic); a structurally
invalid file or a broken intra-scenario invariant (a dangling
`expected_top_task_id`, a missing `source_detail`) raises and fails the run.
`validate_personas` then adds the cross-file check (a duplicate `scenario_id`
fails the run) and the not-ready-yet checks (a role with no scenario, an
under/over count) which only print a
warning.

`placeholder_scenarios.PLACEHOLDER_SCENARIOS` still exists, but only as a
small fixed list of `PersonaEvaluationScenario` objects for unit-testing
`harness.run()` in isolation (`tests/test_harness.py`) — it is not used by this CLI.

## For mentors: running this without MIND's help

From a fresh clone, with no prior knowledge of this repo. Needs **Python 3.11 or
newer** (tested through 3.14) and nothing else.

```bash
cd teams/mind
python -m venv .venv                       # optional but recommended
source .venv/bin/activate                  # Windows: .venv\Scripts\activate
cp .env.example .env                       # no values are actually required
python -m pip install -e ".[dev]"          # installs everything the harness needs
python -m mind.evaluation.run --model dummy # runs it; prints a summary + the full JSON report; writes report.json
```

With GNU `make` installed you can use the shortcuts instead: `make dev` then
`make eval` (same commands). On Windows without `make`, use the lines above —
and run the test tools as `python -m pytest` / `python -m ruff check src tests` /
`python -m mypy src` (the bare `pytest`/`ruff` names may not be on PATH).

The run exits `0` on success. Any `[persona set warning] ...` lines (a role with
no scenario, an out-of-range count) are informational; the run still completes and
reports a score.
Only a non-zero exit code, or text under "Persona set validation FAILED", means
something is actually broken. When running the default `eval/personas` set, a
"missing role" / "expected 4-6" warning would mean the set is incomplete — there
should be none. (Those coverage warnings *are* expected when you point
`--personas` at `eval/real_slice` — see **Real data slice** below.)

## Metric

The harness evaluates models using **Top-1 Accuracy** (`metrics.top1_accuracy`).

**Decision status: closed (final).** Top-1 was chosen over MRR / nDCG with the
mentor; each scenario carries exactly one labelled correct answer
(`expected_top_task_id`), which is what top-1 needs and what MRR/nDCG-with-graded-
labels would not have. This will not change once baseline scoring starts
(week 5). See also the "Open decisions" table in `teams/mind/CLAUDE.md`.

For each scenario, the metric checks whether the task the model scored highest matches
that scenario's `expected_top_task_id`. The final number is the proportion of scenarios
where the model picked the correct top-priority task.

To keep the door open for a future switch to a different metric without re-running every
model, the harness report keeps every candidate's score and reasoning per scenario
(`ScenarioResult.candidates`), not just the winner.

## Baseline score

The number the week-7 stretch is measured against (#486). Once recorded, an
entry here is not overwritten; a re-run gets its own dated entry.

### How a baseline is recorded

`report.json` is not the record: it is gitignored and rewritten on every run.
The baseline is written to its own committed file:

```bash
git rev-parse HEAD                                 # -> <model-sha>
git log -1 --format=%H -- eval/personas            # -> <persona-set-sha>
python -m mind.evaluation.run --model <model> \
    --record-baseline eval/baselines/baseline_score_<YYYY-MM-DD>.json \
    --model-commit <model-sha> --persona-set-commit <persona-set-sha>
```

The file carries a `format_version`, the date, the model name, `model_commit`, the metric, the
persona set (path, `checksum`, `commit`, scenario count, how many are
`team_lead_approved`), the combined / per-group / per-role
results, and every scenario's prediction with all candidate scores (for the
manual spot-check and the week-7 comparison).

- **It is never overwritten.** If the file exists, the run fails before
  scoring; a re-run goes to a new, dated file.
- **A failed run writes nothing.** The record is written last, after the
  `--min-accuracy` / `--max-accuracy` gates; a run that exits non-zero on a gate
  leaves no file behind (the file is write-once, so a leftover would block the
  real run that follows).
- **The persona set is identified by a checksum, computed automatically.** The
  record stores `personas_checksum.compute_personas_checksum` of the persona
  directory that was scored (the same checksum `make lint` checks against
  `eval/personas/personas.sha256`). It is computed from the files themselves, so
  it cannot be typed wrong, and it describes exactly what was scored, including
  uncommitted edits to the scenarios. `load_baseline_record` refuses to load a
  baseline when the current set's checksum differs from the recorded one: that is
  the "same fixed set" guarantee for the baseline-vs-stretch comparison.
- **Both commits are required and must look like a git sha** (7-40 lowercase
  hex), so a forgotten argument, a branch name or a stray paste fails instead
  of being recorded. That is a shape check only: it cannot tell a swapped pair
  or a wrong sha, so take each one from the git command above. For the persona
  set the commit is only an extra label; the checksum is what identifies it.
- **Run the model from a clean working tree.** `model_commit` only describes
  what was scored if nothing under `src/` is uncommitted; the tool does not
  check this. (The persona set no longer depends on this: its checksum covers
  uncommitted scenario edits.)

The format is one Pydantic model, `baseline_record.BaselineRecord`, used for
both writing and reading. Its checks run on every write and every read: each
accuracy (combined, per group, per role) must equal its own
`correct_top1 / total_scenarios`, `combined` must agree with the per-scenario
results, and each scenario's `passed` must match its prediction. A record that
fails any of them is refused, with an error naming the field. The per-group and
per-role slices must also add up to `combined`.
`format_version` (currently `1`) lets a reader say "this record is an older/newer
format" if a field is ever renamed or removed, instead of a validation error.
`baseline_record.load_baseline_record(path, personas_path=...)` reads a record back into the
`Report` it was written from, so the week-7 comparison (#487) diffs the stretch
run against the recorded baseline instead of a re-run.

### 2026-09-23: no week-5 baseline number exists yet

- **Checked:** `origin/main` @ `c7e6c507` (2026-09-23).
- **Finding:** `src/mind/scoring/` contains only `README.md` and `__init__.py`,
  with no ranking model. The harness has only ever scored `DummyModel` and the
  heuristics in `baseline_models.py`, none of which is the baseline.
- **Why:** the segmented-ranking model (#354) has not merged. It is open as
  PR #498, which also depends on `ScoreResult.factors` (PR #497, open).
- **Consequence:** there is no week-5 baseline number anywhere, so #428 step 3
  ("re-confirm the baseline number") has nothing to confirm yet.
- **Next:** measured over all 30 scenarios in `eval/personas/` once #354 lands
  (expected start of week 7, 2026-10-04), then recorded here with the model
  commit and the persona-set version.

## Real data slice

The Foundation Gate requires the harness to run on a small slice of a **real**
dataset, not only hand-written scenarios. That slice lives in `eval/real_slice/`,
kept separate from `eval/personas/` on purpose: the persona set is fixed and
team-lead-approved so the baseline and week-7 scores stay comparable against it, whereas
the real slice exists only to prove the harness survives real data. The two are
never mixed.

```bash
python -m mind.evaluation.run --personas eval/real_slice
```

### Corpus

**AMI Meeting Corpus**, manual-annotations package `ami_public_manual_1.6.2`
(text only — no audio downloaded), licensed **CC BY 4.0**. The reproducible
pointer is committed as `eval/datasets/ami.manifest.json` +
`eval/datasets/ami.sha256`; the corpus files themselves stay out of git (under
`local-data/`). To regenerate the slice, download the package named in the
manifest and unzip it to `local-data/ami/ami_public_manual_1.6.2/`.

Action items come from the abstractive-summary layer: each
`abstractive/<meeting>.abssumm.xml` has an `<actions>` block whose `<sentence>`
elements are the human-annotated action items. Those sentences become a
scenario's `tasks`. All three scenarios use persona role **Manager** — the AMI
Project Manager is the participant who leaves a meeting owning the action-item
list — and are `team_lead_approved: false`, which is
expected: the real slice is not part of the graded set.

### Meetings used

| Scenario | AMI meeting | Items | Chosen top task | Why (model-visible signals only) |
|---|---|---|---|---|
| `manager_ami_ib4003_office_move` | IB4003 — AMI office-move meeting | 8 | `task_walls_feasibility` | The proposal-merge task is `blocked`; the follow-up meeting is a calendar event; the walls question is the open prerequisite the seating plan depends on. |
| `manager_ami_is1008b_functional_design` | IS1008b — remote-control design project, functional-design meeting | 4 | `task_research_vr_help` | Two of the three concept-prep tasks (UI, marketing) are independent; the third (components-concept) is treated as blocked on this same research — an inferred dependency, not one AMI states outright. Completing this one resolves that blocker. |
| `manager_ami_is1008d_budget_close` | IS1008d — same project, final meeting | 4 | `task_negotiate_cost` | Two of the four tasks are `blocked` by their own text ("after production has begun"); of the rest, the budget negotiation outranks routine reporting. |

### What broke on real data, and what was fixed

Nothing in the harness *crashed*, but real data exposed several rough edges:

1. **Annotation sentences are not self-contained.** AMI action items use
   pronouns referring to earlier sentences ("*He* will also present the new
   promotion plan…"), future-tense assignments naming other roles ("The
   Industrial Designer will…"), and sometimes no terminal punctuation ("The team
   will meet next Tuesday at two o'clock"). The schema's `description` is meant
   to let a task be judged in isolation.
   *Fixed:* each `description` quotes the action sentence verbatim, then adds one
   clause of meeting context and resolves the pronoun — meaning unchanged.

2. **AMI action items carry no deadlines and no explicit priority.** The ground
   truth cannot lean on deadline proximity the way a hand-written scenario can.
   *Fixed:* every `expected_reason` is grounded in `blocked`, `tier`, and
   importance stated in the description. `current_datetime` for all three
   scenarios uses the **real** meeting date from `corpusResources/meetings.xml`
   in the package (`dateOnly` per meeting: IB4003 `02-11-2004`; IS1008b/IS1008d
   `10-12-2004`), not an invented one — there was no consistent date scheme
   elsewhere in the repo for staying synthetic to protect (checked: `eval/personas/`'s
   30 scenarios are all dated 2026, this slice was already the outlier before
   this fix). One deadline was made concrete: IB4003's "next Tuesday at two
   o'clock" resolves against the real date without inventing anything, because
   `02-11-2004` is itself a Tuesday — "next Tuesday" is `2004-11-09`.

3. **The XML declares `encoding="ISO-8859-1"`, but that header is wrong for
   this corpus.** Verified against all 5,080 XML files in the package: zero
   fail a plain UTF-8 read. 63 files contain non-ASCII bytes, and every one of
   them is `\xc2\xa0` — a non-breaking space encoded in UTF-8 — under a header
   that still claims ISO-8859-1. Honoring the declared encoding, which is what
   an earlier version of this note told #176 to do, decodes `\xc2\xa0` as two
   Latin-1 characters (`Â` + a non-breaking space) — corruption, introduced on
   purpose by following the header.
   *Fixed:* the note for #176 is the reverse of what it said before — do not
   trust the declared encoding on this corpus; read every file as UTF-8. Also
   normalize non-breaking spaces (`\xa0`) to regular spaces before any string
   comparison: an invisible non-breaking space is not equal to a normal space,
   so left unnormalized it silently turns a true positive into a false
   negative in the matching rule. Nothing needed for this slice itself (its
   three files are pure ASCII); recorded here as the corrected caveat for
   #176.

4. **`validate_personas` coverage warnings fire on the slice.** Running
   `--personas eval/real_slice` prints `no scenario yet for role(s): …`,
   `Manager: has 3 scenario(s), expected 4-6`, and `3 scenario(s) total,
   expected 24-36`. These check the *persona set's* completeness and do not
   apply here. They are warnings only — exit code is still `0` and a score is
   still printed. Not treated as failures for the slice; documented rather than
   silenced, to avoid a harness code change.

5. **The grouped report is empty for a flat folder.** `run.py` splits its report
   by subdirectory (`same_tier/`, `cross_tier/` in the persona set);
   `eval/real_slice/` is flat, so `report.json` carries `"groups": {}` and only
   the combined report is meaningful. Expected for the slice.

6. **The schema requires `effort_estimate` and `tier` on every task; AMI
   supplies neither.** Every value across all three scenarios (15-120 minutes,
   `operational`/`tactical`) is invented — the same kind of gap as the missing
   deadlines and priority in item 2, but for two structurally required fields.
   The two carry different risk. `effort_estimate` is safe: the schema
   requires a correct answer to never hinge on comparing it against
   `free_time_minutes`, so an invented value cannot move the ground truth.
   `tier` is **not** safe: the ranking model does see `tier`, and
   `expected_reason` is allowed to lean on it, so an invented `tier` can
   change which answer is correct. The tiers used here were chosen to match
   each task's real scope (presentation/reporting prep = `operational`,
   negotiation and cost decisions = `tactical`), not tuned toward a
   particular answer, but there is no way to verify that choice against AMI
   itself — it is not in the annotation.

**Note on `manager_ami_is1008b_functional_design` specifically:** after the
`blocked` fix above (see the self-contradiction fix in the scenario file),
its three unblocked tasks are identical in every structured, model-visible
field (`blocked=False`, `tier=operational`, `deadline=None`). The only field
that differs is `effort_estimate` (45 vs. 90 vs. 90) — exactly the field item
6 says a correct answer must never hinge on. The real distinguishing signal
lives only in the free-text `description`, which is allowed for a model that
reads it, but a deterministic feature-based model (e.g. the week-5
`skip-blocked-nearest-deadline` baseline) does not read description text at
all. This scenario is therefore a **description-level scenario**: unsolvable
by design for a features-only model, not a ranking bug if one misses it.

**The same gap exists in `manager_ami_is1008d_budget_close`.** Its two
unblocked candidates, `task_negotiate_cost` and `task_present_promo_plan`,
are also identical in `blocked` (`False`) and `tier` (both `tactical`) and
`deadline` (both `None`) — only `effort_estimate` (90 vs. 45) differs. Unlike
the IS1008b case, the distinguishing text here is not an inference: the LCD
contingency in `task_negotiate_cost`'s description is directly backed by
AMI's own `<decisions>` layer, and `task_present_promo_plan` self-describes
as routine reporting. So the description-level signal is honest, but it is
still description-level — a features-only model has the same "unsolvable by
design" gap here as in IS1008b, for the same reason.

### Result

`python -m mind.evaluation.run --personas eval/real_slice` loads all three
scenarios, runs the `dummy` model, prints **Top-1 accuracy 66.7% (2/3)** and
exits `0`. That number is not a model result — `dummy` scores every task equally
and the harness breaks ties toward the first-listed task — it only confirms the
harness ingests real-corpus scenarios and produces a report end to end.

With the real `skip-blocked-nearest-deadline` baseline (`--model
skip-blocked-nearest-deadline`; landed on `main` after this branch was cut,
picked up by rebasing this branch onto `main` on 2026-09-24), the slice scores
**33.3% (1/3)** — worse than dummy, and every one of the three results is
explained by the model's scoring formula (`blocked` -> `0.0`, no deadline ->
a flat `0.01`, a deadline -> always higher than `0.01`), not by the model
understanding anything about the slice:

- `manager_ami_ib4003_office_move`: **miss.** The model picks
  `task_followup_meeting` — the only task with a deadline — over the correct
  `task_walls_feasibility`, which has none. This is the scenario's intended
  trap: the deadline belongs to a fixed calendar event, not prioritizable
  work, and only the `description` says so. A model that reads `deadline`
  alone, with no text, falls for it.
- `manager_ami_is1008b_functional_design`: **miss**, exactly as the
  description-level-scenario note above predicts. All unblocked candidates
  score the same flat `0.01` (no deadline), so the harness's tie-break
  (first-listed task) decides, landing on `task_ui_concept`, not the
  correct answer.
- `manager_ami_is1008d_budget_close`: **hit** — by the same tie-break
  mechanism, not signal comprehension. `task_negotiate_cost` and
  `task_present_promo_plan` also tie at `0.01`; `task_negotiate_cost` happens
  to be listed first, so it wins. Not evidence the model understood the
  budget dependency.

So the one correct prediction is a tie-break coincidence, the same mechanism
that drove `dummy`'s 66.7%. This is the answer the rebase was meant to
establish: the real-data slice does not yet measure ranking quality for any
model that has actually been run against it — it proves only that the harness
pipes real AMI data through any `RankingModel` implementation end to end.

## Extraction-quality eval

A **second, separate** harness — not a variant of the ranking metric above, which
is why it is its own section. Top-1 accuracy asks "given a list of tasks, did the
model rank the right one first?". This asks a question one step upstream: **"did
the pipeline extract the right action items from a meeting at all?"** — measured
as precision / recall / F1 of the extracted items against a human-annotated
reference (AMI's tagged action items). It lives in `extraction_eval.py`
(`evaluate_extraction`), with a CLI at `python -m mind.evaluation.run_extraction_eval`.
The full design, including the hand-sketch on real AMI meetings, is in
`extraction_eval_design.md`.

### What it measures

Given `extracted` action items and `annotated` (reference) ones, both as strings:

- **precision** = `TP / len(extracted)` — of what was extracted, how much was real.
- **recall** = `TP / len(annotated)` — of the real action items, how many were found.
- **f1** — harmonic mean. Micro-F1 over the whole slice is the number CI will
  regress on (`extraction_eval_design.md` §3.5).
- **counts** — `n_extracted` and `n_annotated` (the denominators above) and
  `true_positives` (the numerator), so a report is readable on its own.

The report (`ExtractionEvalReport`, a frozen pydantic model — `model_dump()` gives
the JSON the CLI writes) also carries the matched pairs, the missed annotations,
and the false positives — the raw evidence, kept for "why did this run score that?" analysis in
later weeks, the same requirement `ScenarioResult.candidates` exists for above.

### The matching rule

An extracted task and an annotated sentence are almost never byte-identical
("UI Designer to present the interface concept" vs. "The User Interface Designer
will make a presentation on the user interface concept"). The rule
(`extraction_eval_design.md` §3):

1. Normalize both sides — lowercase, collapse whitespace, drop a trailing period.
   "Whitespace" is whatever `str.split()` treats as such, which includes the
   non-breaking space `\xa0` — so `\xa0` needs no separate `.replace`. It does
   **not** include `\u200b` (zero-width space), which would survive
   normalization. Neither matters for the AMI action items themselves: the 306
   `<actions>` sentences contain no non-ASCII character at all (the `\xc2\xa0`
   bytes found elsewhere in the package, see "Real data slice" above, are not in
   them).
2. Score every (extracted, annotated) pair with a `similarity(a, b) -> float` in
   `[0, 1]`.
3. Match greedily, one-to-one: highest similarity first, take a pair iff both
   endpoints are still free and the score is `>= threshold`. A merge (two
   annotations covered by one extracted item) therefore shows up as one true
   positive plus one missed annotation, not hidden.
4. `TP` = matched pairs, `FP` = unmatched extracted, `FN` = unmatched annotated.

`similarity` and `threshold` are **required parameters of `evaluate_extraction`,
never hardcoded**: Ayala's embedding-similarity function swaps in at the call site
with no change to the harness, and the locked lexical threshold
(`extraction_eval_design.md` §6 — τ = 0.60, for the `rapidfuzz` backend) can be
revised as a visible call-site change rather than a silent code edit.

**A bad score fails loudly.** `evaluate_extraction` raises `ValueError` if
`threshold`, or any score `similarity` returns, is not a real number in `[0, 1]`
(NaN, infinity, out of range, `None`, a string, a `bool`). This matters most for
NaN: `nan < threshold` is `False`, so without the check a NaN score is matched as
if it had cleared the bar and the eval reports a *perfect* run instead of
failing. It also catches an embedding backend whose cosine in `[-1, 1]` was not
rescaled to `[0, 1]`.

### The extracted-item contract

`evaluate_extraction` compares strings, but a real extracted item is a structured
object. `extraction_contract.py` pins which fields of the pipeline's output the
eval reads and what they must mean, as a Pydantic model — in code so the
pipeline's output is *checked* against it, not just described. The pipeline's own
outbound contract stays `TaskCreationRequested`.

`ExtractedItem` carries only what the eval reads:

| field | required | role |
|---|---|---|
| `title` | yes | head of the comparison string |
| `description` | no | appended to `title` (`title + ". " + description`) when present — design §3.3 |

**Which field carries the meeting id (issue #233, step 1): none.** `source_ref` in
the extraction pipeline is a pointer found *inside the content* (a message id, a
URL) and is dropped when the text does not contain it (`extraction/grounding.py`),
so for a meeting transcript it is `None`. The eval is what sends each meeting to
the extractor, so it holds the meeting id itself: it passes it in as the
extractor's `item_key` and reads it back from `ExtractionSuccess` /
`ExtractionFailure`, grouping items by meeting before they are scored.

`ExtractedItem.to_comparison_text()` produces the string `evaluate_extraction`
matches. Pipeline output enters the eval through
`evaluate_extracted_items(items, annotated, *, similarity, threshold)`, which takes
`ExtractedItem`s — so the extracted side has to pass the contract before it is
scored — and is otherwise a thin wrapper over `evaluate_extraction` (the
string-level version the CLI and fixture use). Field names match the pipeline's
`ExtractedFields` and `TaskCreationRequested` (`contracts/task_creation_requested.py`)
and `extra="ignore"`, so a full payload validates as-is — `source_ref` is simply
ignored, and extraction's classification fields
(`tier`, `urgency`, `role_context`, `confidence`, `deadline`, …) are deliberately
absent: extraction P/R measures "did we find the right action items", not "did we
classify them right". `role_context` is also not *set* at extraction — the
classification stage fills it (`extraction/assembly.py`); it joins this contract
as an optional field when the owner-match diagnostic (design §3.7) is built.

**Status: proposed by R1, not yet agreed with Yonit** — pending her confirmation
that the pipeline output fits this shape and that `item_key` passes through
unchanged (`extraction_eval_design.md`
§"Meeting IDs", `TODO(Yonit)`).

### This eval is approximate

It compares free text to free text. A "true positive" means *our rule* — a
similarity function and a threshold that are judgement calls — decided two
sentences are the same commitment. Two models' scores are comparable only because
they were scored with the *same frozen* rule; the absolute number carries an
error bar we do not quantify. The job is to catch regressions and large
differences on a fixed reference, not to certify absolute extraction quality.

### Running it

```bash
python -m mind.evaluation.run_extraction_eval                   # the bundled hand-built fixture
python -m mind.evaluation.run_extraction_eval --input pair.json
python -m mind.evaluation.run_extraction_eval --threshold 0.6   # default: PLACEHOLDER_THRESHOLD (0.5)
```

`pair.json` is `{"extracted": [...], "annotated": [...]}` — exactly those two keys,
lists of strings only (validated by the `ExtractionEvalInput` pydantic model; an
unknown key or a non-string item is rejected with the offending location, e.g.
`extracted.1`). A bad input file or an invalid `--threshold` prints the error to
stderr and exits with code 1. The full report is also written to
`extraction_report.json` in the working directory (gitignored). With no `--input`, the
fixture in `extraction_fixture.py` runs — three true positives, one false
positive, one false negative, precision = recall = 0.75, worked out by hand in
that file's docstring. The similarity the CLI uses is a pure-Python Jaccard placeholder
(`placeholder_similarity`, paired with `PLACEHOLDER_THRESHOLD = 0.5`); the real backend (embeddings, or a `rapidfuzz` baseline)
is not built yet — blocked on Ayala's answer (`extraction_eval_design.md` §4).
`metrics.extraction_precision_recall` is a stricter placeholder still (exact
string match); it is not this eval.

### Current status

**Fixture-validated, not yet run against real pipeline output** — see
[Cross-team: should the Task model / API error format be shared contracts? #21](https://github.com/KamaTechOrg/Resonance-Flow/issues/21)
(tracking issues for this eval: #176 design, #177 implementation).
The extracted side needs Yonit's extraction pipeline and Michal's ASR to be
producing output; the annotated side (AMI action items) wires in via a manifest
edit, not a code change (`extraction_eval_design.md` §"Scope").

### Known limitation: one task per text

The basic extraction stage returns **exactly one task per input text**
(`BasicExtractor.extract`, `extraction/extractor.py`). An AMI meeting carries
several action items — 4 to 8 in the current slice — so a whole meeting sent as
one text yields one task, and recall on that meeting is capped at `1/n` (IB4003:
at most 1/8). A score measured under this limit reflects the pipeline's shape, not
its extraction quality, and must not be read as one.

This is a product gap, not an eval one — a real meeting arriving through Bridge
hits the same limit — and is tracked in
[#690](https://github.com/KamaTechOrg/Resonance-Flow/issues/690). Nothing in this
eval changes when it is fixed: whether the pipeline returns a list per text or
meetings are segmented upstream, the eval groups results by meeting through
`item_key` (see "The extracted-item contract").
