"""The evaluation harness: run a ranking model against the persona scenario set.

`run(model, scenarios)` is the entry point named in the R1 role spec
(`run(model, scenarios) -> report`). It is deliberately the only place that
turns per-task scores into a ranking: a `RankingModel` only ever scores one
`Task` at a time (plus a `ScoreContext` carrying the current time — see
`interfaces.RankingModel`) and never picks a winner itself — keeping the model
contract identical to the system-wide boundary with Chain ("MIND scores, it does
not decide").
"""

from pydantic import BaseModel, ConfigDict

from mind.evaluation.interfaces import (
    PersonaEvaluationScenario,
    RankingModel,
    ScoreContext,
    ScoreFactors,
)
from mind.evaluation.metrics import top1_accuracy

# The report models are Pydantic, not dataclasses, so a report read back from
# JSON (`Report.model_validate(...)`) is validated exactly like one built by
# `run()`: unknown fields, NaN/inf and malformed factors fail on load.
_REPORT_CONFIG = ConfigDict(extra="forbid", allow_inf_nan=False)


class CandidateScore(BaseModel):
    """One candidate task's score within a scenario."""

    model_config = _REPORT_CONFIG

    task_id: str
    score: float
    reasoning: str | None = None
    # A deep copy of the model's `ScoreFactors`, not a reference to its object.
    factors: ScoreFactors | None = None


class ScenarioResult(BaseModel):
    """The outcome of running the model against a single scenario."""

    model_config = _REPORT_CONFIG

    scenario_id: str
    expected_top_task_id: str  # the scenario's labelled correct answer
    predicted_top_task_id: str  # the task the model scored highest
    passed: bool  # predicted_top_task_id == expected_top_task_id
    candidates: list[CandidateScore]  # every candidate's score, not just the winner


class Report(BaseModel):
    """Aggregate result of one harness run.

    - `model_name`: the RankingModel implementation's class name.
    - `total_scenarios`: how many scenarios were evaluated.
    - `correct_top1`: of those, how many the model got right
      (`predicted_top_task_id == expected_top_task_id`).
    - `top1_accuracy`: `correct_top1 / total_scenarios` (0.0 if no scenarios).
    - `scenario_results`: per-scenario detail, in input order.
    """

    model_config = _REPORT_CONFIG

    model_name: str
    total_scenarios: int
    correct_top1: int
    top1_accuracy: float
    scenario_results: list[ScenarioResult]


class EvaluationOutput(BaseModel):
    """The whole of report.json: one combined report plus one per scenario group.

    - `model_name`: the RankingModel implementation's class name.
    - `combined`: the report over every loaded scenario.
    - `groups`: one report per scenario group (e.g. `same_tier`, `cross_tier`).
    - `roles`: one report per `persona.role`, split out of `combined` (see
      `split_by_role`), so they always add up to it.
    """

    model_config = _REPORT_CONFIG

    model_name: str
    combined: Report
    groups: dict[str, Report]
    roles: dict[str, Report] = {}


def run(model: RankingModel, scenarios: list[PersonaEvaluationScenario]) -> Report:
    """Score every scenario with `model` and return an aggregate `Report`.

    For each scenario: build a `ScoreContext` fixed to the scenario's
    `current_datetime`, call `model.score(task, context)` once per candidate
    task, take the highest-scoring task as the model's prediction (ties break
    toward the first task in file order), and compare it to
    `expected_top_task_id`. The `Report` carries top-1 accuracy plus every
    candidate's raw score (and reasoning and factors), so a later switch to a
    graded metric (MRR/nDCG) needs no re-run.
    """
    predictions: list[str] = []
    scenario_results: list[ScenarioResult] = []

    for scenario in scenarios:
        context = ScoreContext(current_datetime=scenario.context.current_datetime)
        scored = [(task, model.score(task, context)) for task in scenario.tasks]
        best_task, _ = max(scored, key=lambda pair: pair[1].score)

        predictions.append(best_task.task_id)
        scenario_results.append(
            ScenarioResult(
                scenario_id=scenario.scenario_id,
                expected_top_task_id=scenario.expected_top_task_id,
                predicted_top_task_id=best_task.task_id,
                passed=best_task.task_id == scenario.expected_top_task_id,
                candidates=[
                    CandidateScore(
                        task_id=t.task_id,
                        score=r.score,
                        reasoning=r.reasoning,
                        factors=(
                            r.factors.model_copy(deep=True) if r.factors is not None else None
                        ),
                    )
                    for t, r in scored
                ],
            )
        )

    return Report(
        model_name=model.__class__.__name__,
        total_scenarios=len(scenarios),
        correct_top1=sum(r.passed for r in scenario_results),
        top1_accuracy=top1_accuracy(predictions, scenarios),
        scenario_results=scenario_results,
    )


def split_by_role(
    scenarios: list[PersonaEvaluationScenario], report: Report
) -> dict[str, Report]:
    """Split an existing `report` into one `Report` per `persona.role`, keyed and
    ordered by role name.

    Built from the report's own `scenario_results` instead of re-running the
    model, so the per-role numbers always add up to the combined one.
    `scenarios` must be the list `report` was run on, in the same order. A role
    with no scenario gets no entry (`validate_personas` warns about that
    separately).
    """
    by_role: dict[str, list[ScenarioResult]] = {}
    for scenario, result in zip(scenarios, report.scenario_results, strict=True):
        if scenario.scenario_id != result.scenario_id:
            raise ValueError(
                f"scenarios and report are out of order: {scenario.scenario_id!r} "
                f"vs {result.scenario_id!r}"
            )
        by_role.setdefault(scenario.persona.role, []).append(result)

    return {
        role: Report(
            model_name=report.model_name,
            total_scenarios=len(results),
            correct_top1=sum(r.passed for r in results),
            top1_accuracy=sum(r.passed for r in results) / len(results),
            scenario_results=results,
        )
        for role, results in sorted(by_role.items())
    }
