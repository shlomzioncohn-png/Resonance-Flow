from mind.evaluation.dummy_model import DummyModel
from mind.evaluation.harness import PLACEHOLDER_SCENARIOS, Report, run


def test_run_returns_valid_report():
    report = run(DummyModel(), PLACEHOLDER_SCENARIOS)

    assert isinstance(report, Report)
    assert report.total_scenarios == len(PLACEHOLDER_SCENARIOS)
    assert 0.0 <= report.top1_accuracy <= 1.0
    assert len(report.scenario_results) == report.total_scenarios


def test_scenario_result_keeps_all_candidate_scores():
    report = run(DummyModel(), PLACEHOLDER_SCENARIOS)

    for result, scenario in zip(report.scenario_results, PLACEHOLDER_SCENARIOS):
        assert len(result.candidates) == len(scenario["tasks"])
