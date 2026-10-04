# MIND Evaluation Harness

This directory contains the evaluation engine for the **Resonance Flow MIND team**.

The harness is designed to:

- Load a fixed set of persona scenarios.
- Execute a ranking model against those scenarios through a uniform interface.
- Compute performance metrics for the evaluated model.

## Project Context

- **Immutable Scenarios:** Scores a ranking model against `eval/personas/` — a fixed scenario set, never touched mid-comparison.
- **Foundation Gate:** Must be runnable by someone outside the MIND team.
- **Lifecycle:** Used for both the baseline (week 6) and the stretch-goal comparison (week 7, demo day week 9).

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
separately and prints a combined number too, and `report.json` carries all
three. Pass a single group dir (`--personas eval/personas/same_tier`) to score
just that group. Loading goes through
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
labels would not have. This will not change once baseline-vs-stretch scoring
starts (week 6). See also the "Open decisions" table in `teams/mind/CLAUDE.md`.

For each scenario, the metric checks whether the task the model scored highest matches
that scenario's `expected_top_task_id`. The final number is the proportion of scenarios
where the model picked the correct top-priority task.

To keep the door open for a future switch to a different metric without re-running every
model, the harness report keeps every candidate's score and reasoning per scenario
(`ScenarioResult.candidates`), not just the winner.

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

The report also carries the matched pairs, the missed annotations, and the false
positives — the raw evidence, kept for "why did this run score that?" analysis in
later weeks, the same requirement `ScenarioResult.candidates` exists for above.

### The matching rule

An extracted task and an annotated sentence are almost never byte-identical
("UI Designer to present the interface concept" vs. "The User Interface Designer
will make a presentation on the user interface concept"). The rule
(`extraction_eval_design.md` §3):

1. Normalize both sides — lowercase, collapse whitespace, drop a trailing period.
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
```

`pair.json` is `{"extracted": [...], "annotated": [...]}`. With no `--input`, the
fixture in `extraction_fixture.py` runs — three true positives, one false
positive, one false negative, precision = recall = 0.75, worked out by hand in
that file's docstring. The similarity the CLI uses is a pure-Python Jaccard placeholder
(`placeholder_similarity`, paired with `PLACEHOLDER_THRESHOLD = 0.5`); the real backend (embeddings, or a `rapidfuzz` baseline)
is not built yet — blocked on Ayala's answer (`extraction_eval_design.md` §4).
`metrics.extraction_precision_recall` is a stricter placeholder still (exact
string match); it is not this eval.

### Current status

**Fixture-validated, not yet run against real pipeline output** — see
[Cross-team: should the Task model / API error format be shared contracts? #21](https://github.com/KamaTechOrg/Resonance-Flow/issues/21).
The extracted side needs Yonit's extraction pipeline and Michal's ASR to be
producing output; the annotated side (AMI action items) wires in via a manifest
edit, not a code change (`extraction_eval_design.md` §"Scope").
