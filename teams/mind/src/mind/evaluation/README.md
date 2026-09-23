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
  comparison against `expected_reason`, not used by the harness).

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
under/over count, a scenario that isn't `mentor_approved`) which only print a
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

The run exits `0` on success. Several `[persona set warning] ... not yet
mentor_approved` lines are **expected** — they just mean the scenarios haven't
been through mentor sign-off yet; the run still completes and reports a score.
Only a non-zero exit code, or text under "Persona set validation FAILED", means
something is actually broken. A "missing role" / "expected 4-6" warning would
mean the set is incomplete — there should be none.

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

The file carries the date, the model name, `model_commit`, the metric, the
persona set (path, `commit`, scenario count, how many are
`team_lead_approved` / `mentor_approved`), the combined / per-group / per-role
results, and every scenario's prediction with all candidate scores (for the
manual spot-check and the week-7 comparison).

- **It is never overwritten.** If the file exists, the run fails before
  scoring; a re-run goes to a new, dated file.
- **Both commits are required and must look like a git sha** (7-40 lowercase
  hex), so a forgotten argument, a branch name or a stray paste fails instead
  of being recorded. That is a shape check only: it cannot tell a swapped pair
  or a wrong sha, so take each one from the git command above.
- **Run from a clean working tree.** The sha only describes what was scored if
  nothing under `src/` or `eval/personas/` is uncommitted; the tool does not
  check this.

`baseline_record.load_baseline_record(path)` reads a record back into the
`Report` it was written from, so the week-7 comparison (#487) diffs the stretch
run against the recorded baseline instead of a re-run. It refuses a record whose
per-scenario results don't add up to its own totals.

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
