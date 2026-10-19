"""Tests for the frozen dev/holdout split of the persona set.

The split is only worth something if it covers the set exactly. Without these,
#535 added six scenarios and nothing failed until someone called `partition()`.
"""

from collections import Counter
from pathlib import Path

import pytest

from mind.evaluation.personas import ALL_ROLES, HIERARCHY_DECIDES_GROUP, load_personas
from mind.scoring.eval_split import (
    ALL_SPLIT_SCENARIO_IDS,
    DEV_SCENARIO_IDS,
    HIERARCHY_DECIDES_SCENARIO_IDS,
    HOLDOUT_SCENARIO_IDS,
    ScenarioSetDriftError,
    partition,
)

PERSONAS_DIR = Path(__file__).resolve().parents[1] / "eval" / "personas"


def test_every_scenario_in_the_set_has_a_half() -> None:
    """A scenario added to eval/personas must fail here, not later in someone's run."""

    loaded = {scenario.scenario_id for scenario in load_personas(PERSONAS_DIR)}

    unplaced = sorted(loaded - ALL_SPLIT_SCENARIO_IDS)
    vanished = sorted(ALL_SPLIT_SCENARIO_IDS - loaded)

    assert not unplaced, (
        f"not in the dev/holdout/hierarchy_decides split: {unplaced}. Place them in "
        "mind/scoring/eval_split.py by its filename-only rules (see its docstring)."
    )
    assert not vanished, f"in the split but no longer in eval/personas: {vanished}"


def test_no_scenario_is_in_both_halves() -> None:
    assert not DEV_SCENARIO_IDS & HOLDOUT_SCENARIO_IDS
    assert not HIERARCHY_DECIDES_SCENARIO_IDS & (DEV_SCENARIO_IDS | HOLDOUT_SCENARIO_IDS)


def test_the_hierarchy_decides_list_is_exactly_that_folder() -> None:
    in_folder = {s.scenario_id for s in load_personas(PERSONAS_DIR / HIERARCHY_DECIDES_GROUP)}

    assert in_folder == HIERARCHY_DECIDES_SCENARIO_IDS


def test_hierarchy_decides_scenarios_are_returned_in_neither_half() -> None:
    """Not a calibration target and not a valid holdout (see eval_split's docstring)."""

    dev, holdout = partition(load_personas(PERSONAS_DIR))
    returned = {s.scenario_id for s in dev} | {s.scenario_id for s in holdout}

    assert not returned & HIERARCHY_DECIDES_SCENARIO_IDS
    assert returned == DEV_SCENARIO_IDS | HOLDOUT_SCENARIO_IDS


def test_every_role_has_at_least_two_holdout_scenarios() -> None:
    """One holdout scenario per role says close to nothing about that role."""

    _, holdout = partition(load_personas(PERSONAS_DIR))
    per_role = Counter(scenario.persona.role for scenario in holdout)

    assert {role: per_role[role] for role in ALL_ROLES if per_role[role] < 2} == {}


def test_a_scenario_missing_from_the_input_is_refused_not_dropped() -> None:
    """Otherwise a deleted scenario file quietly shrinks every dev run."""

    scenarios = load_personas(PERSONAS_DIR)
    without_one = [s for s in scenarios if s.scenario_id != min(DEV_SCENARIO_IDS)]

    with pytest.raises(ScenarioSetDriftError, match=min(DEV_SCENARIO_IDS)):
        partition(without_one)
