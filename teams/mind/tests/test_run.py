import json

import pytest

from mind.evaluation.run import _report_json


def test_report_json_round_trips_a_normal_report():
    output = {"combined": {"top1_accuracy": 0.5, "factors": {"impact": -0.25}}}
    assert json.loads(_report_json(output)) == output


@pytest.mark.parametrize("bad", [float("nan"), float("inf"), float("-inf")])
def test_report_json_refuses_non_finite_values(bad):
    # A NaN that slipped past ScoreFactors (e.g. a dict mutated after validation)
    # must fail the run, not reach report.json as invalid JSON.
    with pytest.raises(ValueError):
        _report_json({"combined": {"factors": {"contributions": {"impact": bad}}}})
