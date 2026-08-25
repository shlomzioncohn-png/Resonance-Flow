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
`description`, `deadline`, `estimated_duration_minutes`, `tier`, `blocked`.

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

`--personas` (default `eval/personas`) must point to a directory containing
`scenario.schema.json` plus the scenario `*.json` files it validates against —
see `eval/personas/README.md`. Loading goes through
`mind.evaluation.personas.load_personas`/`validate_personas`: a structurally
invalid scenario file or a broken cross-scenario invariant (e.g. a dangling
`expected_top_task_id`) fails the run; a role with no scenario yet or a
scenario that isn't `mentor_approved` only prints a warning.

`placeholder_scenarios.PLACEHOLDER_SCENARIOS` still exists, but only as a
small fixed fixture for unit-testing `harness.run()` in isolation
(`tests/test_harness.py`) — it is not used by this CLI.

## Metric

The harness evaluates models using **Top-1 Accuracy** (`metrics.top1_accuracy`) — closed,
approved.

For each scenario, the metric checks whether the task the model scored highest matches
that scenario's `expected_top_task_id`. The final number is the proportion of scenarios
where the model picked the correct top-priority task.

To keep the door open for a future switch to a different metric without re-running every
model, the harness report keeps the full list of every candidate's score per scenario
(`ScenarioResult.candidates`), not just the winner.
