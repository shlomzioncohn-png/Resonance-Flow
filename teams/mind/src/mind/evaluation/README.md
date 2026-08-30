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
    def score(self, task: Task) -> float:
        """Returns a priority score for a single task. Higher score = higher priority."""
        ...
```

`Task` is a Pydantic model (`interfaces.py`) with these fields: `task_id`, `title`,
`description`, `deadline`, `effort_estimate` (minutes; the eval set's form of the
canonical `Task.effort_estimate`), `tier`, `blocked`, `parent_task_id` (nullable,
reserved for the week-7 hierarchical-propagation eval). `score()` receives only a
single `Task` — no persona, no context, no sibling tasks.

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

`--personas` (default `eval/personas`) points to the scenario `*.json` files
(the generated `scenario.schema.json` in that directory is skipped) — see
`eval/personas/README.md`. Loading goes through
`mind.evaluation.personas.load_personas`/`validate_personas`. `load_personas`
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

From a fresh clone, with no prior knowledge of this repo:

```bash
cd teams/mind
cp .env.example .env       # no values are actually required for this to work
make dev                   # installs everything the harness needs
make eval                  # runs the harness against eval/personas/, prints a summary + full report, writes report.json
```
`make eval` exits `0` on success. A "[persona set warning] ..." line is expected
and not a failure — it just means the persona set (`eval/personas/`) isn't fully
built out and mentor-approved yet; the run itself still completes and reports a
score. Only a non-zero exit code, or text under "Persona set validation FAILED",
means something is actually broken.

## Metric

The harness evaluates models using **Top-1 Accuracy** (`metrics.top1_accuracy`) — closed,
approved.

For each scenario, the metric checks whether the task the model scored highest matches
that scenario's `expected_top_task_id`. The final number is the proportion of scenarios
where the model picked the correct top-priority task.

To keep the door open for a future switch to a different metric without re-running every
model, the harness report keeps the full list of every candidate's score per scenario
(`ScenarioResult.candidates`), not just the winner.
