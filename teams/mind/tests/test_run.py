import pytest

from mind.evaluation.harness import CandidateScore, EvaluationOutput, Report, ScenarioResult
from mind.evaluation.interfaces import ScoreFactors
from mind.evaluation.run import _report_json


def _output() -> EvaluationOutput:
    report = Report(
        model_name="SomeModel",
        total_scenarios=1,
        correct_top1=1,
        top1_accuracy=1.0,
        scenario_results=[
            ScenarioResult(
                scenario_id="some_scenario",
                expected_top_task_id="task_a",
                predicted_top_task_id="task_a",
                passed=True,
                candidates=[
                    CandidateScore(
                        task_id="task_a",
                        score=0.5,
                        factors=ScoreFactors(contributions={"impact": -0.25}),
                    )
                ],
            )
        ],
    )
    return EvaluationOutput(model_name="SomeModel", combined=report, groups={"g": report})


def test_report_json_round_trips_the_output():
    output = _output()
    assert EvaluationOutput.model_validate_json(_report_json(output)) == output


@pytest.mark.parametrize("bad", [float("nan"), float("inf"), float("-inf")])
def test_report_json_refuses_non_finite_values(bad):
    # The bypass from the PR #497 review: a dict mutated after validation. The
    # models can't see it, so the write itself must fail, not emit invalid JSON.
    output = _output()
    factors = output.combined.scenario_results[0].candidates[0].factors
    assert factors is not None
    factors.contributions["impact"] = bad

    with pytest.raises(ValueError):
        _report_json(output)
