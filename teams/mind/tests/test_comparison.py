import pytest

from mind.evaluation.comparison import ComparisonReport, diff_reports
from mind.evaluation.harness import CandidateScore, Report, ScenarioResult

# Two Report objects, built entirely by hand — no mind.evaluation.harness.run(),
# no RankingModel, no real baseline or stretch model. Every task_id, score,
# expected/predicted answer below is made up, chosen to cover one scenario per
# direction diff_reports must distinguish: wrong->right, right->wrong,
# wrong->a-different-wrong, and unchanged.

BASELINE_REPORT = Report(
    model_name="SegmentedRankingBaseline",
    total_scenarios=4,
    correct_top1=1,  # only s2 passed
    top1_accuracy=0.25,  # 1/4
    scenario_results=[
        ScenarioResult(
            scenario_id="s1",
            expected_top_task_id="b",
            predicted_top_task_id="a",  # baseline picks the wrong task
            passed=False,
            candidates=[
                CandidateScore(task_id="a", score=0.9),
                CandidateScore(task_id="b", score=0.1),
            ],
        ),
        ScenarioResult(
            scenario_id="s2",
            expected_top_task_id="a",
            predicted_top_task_id="a",  # baseline is correct here
            passed=True,
            candidates=[
                CandidateScore(task_id="a", score=0.9),
                CandidateScore(task_id="b", score=0.1),
            ],
        ),
        ScenarioResult(
            scenario_id="s3",
            expected_top_task_id="c",
            predicted_top_task_id="a",  # baseline picks a, also wrong
            passed=False,
            candidates=[
                CandidateScore(task_id="a", score=0.9),
                CandidateScore(task_id="b", score=0.05),
                CandidateScore(task_id="c", score=0.05),
            ],
        ),
        ScenarioResult(
            scenario_id="s4",
            expected_top_task_id="b",
            predicted_top_task_id="a",  # baseline wrong
            passed=False,
            candidates=[
                CandidateScore(task_id="a", score=0.9),
                CandidateScore(task_id="b", score=0.1),
            ],
        ),
    ],
)

STRETCH_REPORT = Report(
    model_name="HierarchicalPropagationStretch",
    total_scenarios=4,
    correct_top1=1,  # only s1 passed
    top1_accuracy=0.25,  # 1/4
    scenario_results=[
        ScenarioResult(
            scenario_id="s1",
            expected_top_task_id="b",
            predicted_top_task_id="b",  # stretch fixes it
            passed=True,
            candidates=[
                CandidateScore(task_id="a", score=0.2),
                CandidateScore(task_id="b", score=0.8),
            ],
        ),
        ScenarioResult(
            scenario_id="s2",
            expected_top_task_id="a",
            predicted_top_task_id="b",  # stretch breaks it
            passed=False,
            candidates=[
                CandidateScore(task_id="a", score=0.3),
                CandidateScore(task_id="b", score=0.7),
            ],
        ),
        ScenarioResult(
            scenario_id="s3",
            expected_top_task_id="c",
            predicted_top_task_id="b",  # stretch picks b instead of a — still wrong
            passed=False,
            candidates=[
                CandidateScore(task_id="a", score=0.1),
                CandidateScore(task_id="b", score=0.8),
                CandidateScore(task_id="c", score=0.1),
            ],
        ),
        ScenarioResult(
            scenario_id="s4",
            expected_top_task_id="b",
            predicted_top_task_id="a",  # still wrong, same predicted task as baseline
            passed=False,
            candidates=[
                CandidateScore(task_id="a", score=0.85),
                CandidateScore(task_id="b", score=0.15),
            ],
        ),
    ],
)


@pytest.fixture
def comparison() -> ComparisonReport:
    return diff_reports(BASELINE_REPORT, STRETCH_REPORT)


def _diff(comparison: ComparisonReport, scenario_id: str):
    return next(d for d in comparison.scenario_diffs if d.scenario_id == scenario_id)


def test_wrong_to_right_direction(comparison: ComparisonReport):
    diff = _diff(comparison, "s1")

    assert diff.changed is True
    assert diff.direction == "wrong_to_right"
    assert diff.baseline_predicted_top_task_id == "a"
    assert diff.stretch_predicted_top_task_id == "b"


def test_right_to_wrong_direction(comparison: ComparisonReport):
    diff = _diff(comparison, "s2")

    assert diff.changed is True
    assert diff.direction == "right_to_wrong"
    assert diff.baseline_predicted_top_task_id == "a"
    assert diff.stretch_predicted_top_task_id == "b"


def test_wrong_to_different_wrong_direction(comparison: ComparisonReport):
    diff = _diff(comparison, "s3")

    assert diff.changed is True
    assert diff.direction == "wrong_to_different_wrong"
    assert diff.baseline_predicted_top_task_id == "a"
    assert diff.stretch_predicted_top_task_id == "b"


def test_unchanged_scenario_has_no_direction(comparison: ComparisonReport):
    diff = _diff(comparison, "s4")

    assert diff.changed is False
    assert diff.direction is None
    assert diff.baseline_predicted_top_task_id == diff.stretch_predicted_top_task_id == "a"


def test_score_deltas_are_stretch_minus_baseline(comparison: ComparisonReport):
    diff = _diff(comparison, "s1")

    assert diff.score_deltas["a"] == pytest.approx(0.2 - 0.9)
    assert diff.score_deltas["b"] == pytest.approx(0.8 - 0.1)


def test_direction_counts_tally_across_the_set(comparison: ComparisonReport):
    assert comparison.direction_counts == {
        "wrong_to_right": 1,
        "right_to_wrong": 1,
        "wrong_to_different_wrong": 1,
    }


def test_changed_count(comparison: ComparisonReport):
    assert comparison.changed_count == 3


def test_accuracy_before_and_after(comparison: ComparisonReport):
    assert comparison.baseline_accuracy == BASELINE_REPORT.top1_accuracy
    assert comparison.stretch_accuracy == STRETCH_REPORT.top1_accuracy
    assert comparison.baseline_accuracy == pytest.approx(0.25)  # only s2 passed
    assert comparison.stretch_accuracy == pytest.approx(0.25)  # only s1 passed


def test_model_names_are_carried_through(comparison: ComparisonReport):
    assert comparison.baseline_model_name == "SegmentedRankingBaseline"
    assert comparison.stretch_model_name == "HierarchicalPropagationStretch"


def test_total_scenarios_matches_the_matched_set(comparison: ComparisonReport):
    assert comparison.total_scenarios == 4
    assert len(comparison.scenario_diffs) == 4


def test_mismatched_scenario_sets_raise():
    extra_result = ScenarioResult(
        scenario_id="s5",
        expected_top_task_id="a",
        predicted_top_task_id="a",
        passed=True,
        candidates=[CandidateScore(task_id="a", score=1.0)],
    )
    lopsided_stretch = Report(
        model_name="HierarchicalPropagationStretch",
        total_scenarios=5,
        correct_top1=STRETCH_REPORT.correct_top1 + 1,
        top1_accuracy=(STRETCH_REPORT.correct_top1 + 1) / 5,
        scenario_results=[*STRETCH_REPORT.scenario_results, extra_result],
    )

    with pytest.raises(ValueError, match="different scenario sets"):
        diff_reports(BASELINE_REPORT, lopsided_stretch)


def test_mismatched_expected_top_task_id_for_same_scenario_id_is_a_bug():
    inconsistent_baseline = Report(
        model_name="SegmentedRankingBaseline",
        total_scenarios=1,
        correct_top1=1,
        top1_accuracy=1.0,
        scenario_results=[
            ScenarioResult(
                scenario_id="s1",
                expected_top_task_id="a",
                predicted_top_task_id="a",
                passed=True,
                candidates=[CandidateScore(task_id="a", score=1.0), CandidateScore(task_id="b", score=0.0)],
            ),
        ],
    )
    inconsistent_stretch = Report(
        model_name="HierarchicalPropagationStretch",
        total_scenarios=1,
        correct_top1=1,
        top1_accuracy=1.0,
        scenario_results=[
            ScenarioResult(
                scenario_id="s1",
                expected_top_task_id="b",  # deliberately inconsistent with the baseline above
                predicted_top_task_id="b",
                passed=True,
                candidates=[CandidateScore(task_id="a", score=0.0), CandidateScore(task_id="b", score=1.0)],
            ),
        ],
    )

    with pytest.raises(AssertionError, match="same scenario_id must mean the same scenario"):
        diff_reports(inconsistent_baseline, inconsistent_stretch)
