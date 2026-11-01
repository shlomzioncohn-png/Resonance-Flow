import json
import shutil
from collections import Counter
from pathlib import Path

import pytest

from mind.evaluation.baseline_models import LongestDescriptionModel
from mind.evaluation.harness import run
from mind.evaluation.interfaces import PersonaEvaluationScenario
from mind.evaluation.personas import (
    ALL_ROLES,
    HIERARCHY_DECIDES_GROUP,
    MAX_SCENARIOS_PER_ROLE,
    MAX_TOTAL_SCENARIOS,
    MIN_SCENARIOS_PER_ROLE,
    MIN_TOTAL_SCENARIOS,
    PersonaSchemaError,
    correct_answer_deadline_violates_time_horizon,
    load_personas,
    validate_personas,
)

REAL_PERSONAS_DIR = Path(__file__).resolve().parents[1] / "eval" / "personas"
REAL_SCHEMA_PATH = REAL_PERSONAS_DIR / "scenario.schema.json"

# The committed benchmark is fixed (see eval/personas/README.md). If this number
# changes, it must change deliberately, with the mentor's sign-off — bump it here
# in the same commit so the change is visible in review.
EXPECTED_REAL_SCENARIO_COUNT = 42  # 36 original + 6 in hierarchy_decides/ (#507)

# `make eval-ci` gate for the length-bias probe (see the Makefile comment).
LONGEST_DESCRIPTION_CEILING = 0.65

# The six hard scenarios added in 2026-10 (PR #535).
HARD_SCENARIO_IDS = {
    "developer_inflated_p0_vs_understated_data_leak",
    "manager_idle_new_hires_vs_informational_steering_deck",
    "higher_manager_approved_vision_memo_vs_expiring_capacity_offer",
    "field_engineer_released_controller_vs_blocked_fibre_fix",
    "salesperson_dead_rfp_vs_live_signing_round",
    "ceo_own_investor_commitment_vs_unaccepted_keynote_request",
}

# eval/personas/hierarchy_decides/ (#507): the parent hierarchy decides four of them,
# two are controls where a hard signal must beat it.
HIERARCHY_DECIDES_IDS = {
    "manager_restructuring_team_signoff_vs_maintenance_team_signoff",
    "salesperson_core_target_proposal_vs_paused_vertical_proposal",
    "higher_manager_migration_budget_vs_reporting_requests",
    "ceo_expansion_partnership_vs_wound_down_line_partnership",
    "developer_payment_incident_vs_migration_steps",
    "field_engineer_site_outage_vs_rollout_survey",
}


def make_scenario(
    scenario_id: str = "developer_late_pr_review",
    role: str = "Developer",
    expected_top_task_id: str = "task_b",
    team_lead_approved: bool = True,
    source_type: str = "synthetic",
    source_detail: str | None = None,
) -> dict:
    return {
        "scenario_id": scenario_id,
        "persona": {"role": role},
        "context": {
            "free_time_minutes": 30,
            "current_datetime": "2026-08-23T14:00:00",
            "time_horizon": "today",
        },
        "tasks": [
            {
                "task_id": "task_a",
                "title": "Write architecture doc",
                "description": "Long-form design doc, not urgent",
                "effort_estimate": 120,
                "tier": "strategic",
                "blocked": False,
                "parent_task_id": None,
            },
            {
                "task_id": "task_b",
                "title": "Review PR #482",
                "description": "Code review for the auth refactor PR, deadline today",
                "deadline": "2026-08-23T17:00:00",
                "effort_estimate": 20,
                "tier": "operational",
                "blocked": False,
                "parent_task_id": None,
            },
        ],
        "expected_top_task_id": expected_top_task_id,
        "expected_reason": "Task b has a same-day deadline and Task a has none, so Task b is the pick.",
        "source": {"type": source_type, "source_detail": source_detail},
        "team_lead_approved": team_lead_approved,
    }


def make_scenario_model(**kwargs) -> PersonaEvaluationScenario:
    return PersonaEvaluationScenario.model_validate(make_scenario(**kwargs))


def write_personas_dir(tmp_path: Path, scenarios: list[dict]) -> Path:
    personas_dir = tmp_path / "personas"
    personas_dir.mkdir()
    shutil.copy(REAL_SCHEMA_PATH, personas_dir / "scenario.schema.json")
    for scenario in scenarios:
        (personas_dir / f"{scenario['scenario_id']}.json").write_text(
            json.dumps(scenario), encoding="utf-8"
        )
    return personas_dir


# -- load_personas ------------------------------------------------------------


def test_load_personas_valid_scenario(tmp_path):
    scenario = make_scenario()
    personas_dir = write_personas_dir(tmp_path, [scenario])

    loaded = load_personas(personas_dir)

    assert len(loaded) == 1
    assert isinstance(loaded[0], PersonaEvaluationScenario)
    assert loaded[0].scenario_id == scenario["scenario_id"]
    assert loaded[0].expected_top_task_id == "task_b"
    assert [t.task_id for t in loaded[0].tasks] == ["task_a", "task_b"]


def test_load_personas_missing_directory_raises(tmp_path):
    with pytest.raises(FileNotFoundError):
        load_personas(tmp_path / "does_not_exist")


def test_load_personas_empty_directory_returns_empty_list(tmp_path):
    personas_dir = write_personas_dir(tmp_path, [])
    assert load_personas(personas_dir) == []


def test_load_personas_recurses_into_group_subdirectories(tmp_path):
    # The real set is split into same_tier/ and cross_tier/ subdirectories;
    # loading the parent must pick up both, loading one subdir must pick up just it.
    personas_dir = write_personas_dir(tmp_path, [])
    same_tier = personas_dir / "same_tier"
    cross_tier = personas_dir / "cross_tier"
    same_tier.mkdir()
    cross_tier.mkdir()
    (same_tier / "a.json").write_text(
        json.dumps(make_scenario(scenario_id="developer_same_tier_a")), encoding="utf-8"
    )
    (cross_tier / "b.json").write_text(
        json.dumps(make_scenario(scenario_id="developer_cross_tier_b")), encoding="utf-8"
    )

    assert {s.scenario_id for s in load_personas(personas_dir)} == {
        "developer_same_tier_a",
        "developer_cross_tier_b",
    }
    assert [s.scenario_id for s in load_personas(same_tier)] == ["developer_same_tier_a"]


def test_load_personas_schema_invalid_scenario_raises(tmp_path):
    scenario = make_scenario()
    del scenario["team_lead_approved"]  # required field missing -> schema violation

    personas_dir = write_personas_dir(tmp_path, [scenario])
    with pytest.raises(PersonaSchemaError, match="team_lead_approved"):
        load_personas(personas_dir)


def test_load_personas_rejects_the_removed_mentor_approved_field(tmp_path):
    # mentor_approved was dropped (team_lead_approved is the only approval flag);
    # the model forbids extras, so a stale file still carrying it fails loudly.
    scenario = make_scenario()
    scenario["mentor_approved"] = False

    personas_dir = write_personas_dir(tmp_path, [scenario])
    with pytest.raises(PersonaSchemaError, match="mentor_approved"):
        load_personas(personas_dir)


def test_load_personas_dangling_expected_top_task_id_raises(tmp_path):
    # Now an intra-scenario invariant enforced by the model, so it fails at load.
    scenario = make_scenario(expected_top_task_id="task_does_not_exist")
    personas_dir = write_personas_dir(tmp_path, [scenario])
    with pytest.raises(PersonaSchemaError, match="expected_top_task_id"):
        load_personas(personas_dir)


def test_load_personas_missing_source_detail_raises(tmp_path):
    scenario = make_scenario(source_type="AMI", source_detail=None)
    personas_dir = write_personas_dir(tmp_path, [scenario])
    with pytest.raises(PersonaSchemaError, match="source_detail"):
        load_personas(personas_dir)


def test_load_personas_reports_every_offending_file(tmp_path):
    good = make_scenario(scenario_id="developer_good")
    bad = make_scenario(scenario_id="developer_bad", expected_top_task_id="nope")
    personas_dir = write_personas_dir(tmp_path, [good, bad])
    with pytest.raises(PersonaSchemaError, match="developer_bad"):
        load_personas(personas_dir)


@pytest.mark.parametrize(
    "content",
    [
        b'{"scenario_id": "\xff\xfe broken"}',  # not valid UTF-8
        b'{"scenario_id": "developer_cut_off", ',  # valid UTF-8, not valid JSON
    ],
)
def test_load_personas_reports_an_unreadable_file_instead_of_crashing(tmp_path, content):
    personas_dir = write_personas_dir(tmp_path, [make_scenario(scenario_id="developer_good")])
    (personas_dir / "developer_unreadable.json").write_bytes(content)

    with pytest.raises(PersonaSchemaError, match="developer_unreadable.json: not valid UTF-8 JSON"):
        load_personas(personas_dir)


# -- validate_personas ------------------------------------------------------


def test_validate_personas_well_formed_set_has_no_errors():
    report = validate_personas([make_scenario_model()])
    assert report.ok
    assert report.errors == []


def test_validate_personas_empty_set_is_error_not_warning():
    # "measured nothing" must not pass the way "measured everything" does.
    report = validate_personas([])
    assert not report.ok
    assert any("no scenarios loaded" in e for e in report.errors)


def test_validate_personas_duplicate_scenario_id_is_error():
    report = validate_personas([make_scenario_model(), make_scenario_model()])
    assert not report.ok
    assert any("duplicate scenario_id" in e for e in report.errors)


def test_validate_personas_missing_role_is_warning_not_error():
    report = validate_personas([make_scenario_model(role="Developer")])
    assert report.ok
    assert any("CEO" in w for w in report.warnings)


def test_validate_personas_role_count_out_of_range_is_warning_not_error():
    # Only 2 Developer scenarios: below MIN_SCENARIOS_PER_ROLE (4).
    scenarios = [
        make_scenario_model(scenario_id="developer_scenario_1", role="Developer"),
        make_scenario_model(scenario_id="developer_scenario_2", role="Developer"),
    ]
    report = validate_personas(scenarios)
    assert report.ok
    assert any("Developer" in w and "2" in w for w in report.warnings)


def test_validate_personas_total_count_out_of_range_is_warning_not_error():
    report = validate_personas([make_scenario_model()])  # 1 total, below MIN (24)
    assert report.ok
    assert any(
        "total" in w and f"{MIN_TOTAL_SCENARIOS}-{MAX_TOTAL_SCENARIOS}" in w
        for w in report.warnings
    )


# -- the real eval/personas/ directory --------------------------------------
#
# Every other test in this file builds scenarios in tmp_path; these load the
# actual committed set, so a bad edit to a real scenario file fails CI here
# (mirrors the shell snippet in the README's "Contributing a scenario" section).


def test_real_persona_set_loads_and_has_the_expected_size():
    scenarios = load_personas(REAL_PERSONAS_DIR)
    assert len(scenarios) == EXPECTED_REAL_SCENARIO_COUNT
    assert all(isinstance(s, PersonaEvaluationScenario) for s in scenarios)
    assert len({s.scenario_id for s in scenarios}) == len(scenarios)


def test_real_persona_set_fills_description_tier_and_effort():
    # The schema lets them be null since #175, for tasks built from real output
    # (eval/real_shaped/). The hand-written set still states all three: a
    # scenario that left one out by mistake would be scored on data its author
    # never chose, e.g. an assumed tier.
    empty = [
        f"{s.scenario_id}/{t.task_id}: {field}"
        for s in load_personas(REAL_PERSONAS_DIR)
        for t in s.tasks
        for field in ("description", "tier", "effort_estimate")
        if getattr(t, field) is None
    ]
    assert empty == []


def test_real_persona_set_validates_with_no_errors():
    report = validate_personas(load_personas(REAL_PERSONAS_DIR))
    assert report.errors == []


def test_real_persona_set_has_no_warnings():
    # Role coverage, per-role counts and the total are all expected to be in range.
    report = validate_personas(load_personas(REAL_PERSONAS_DIR))
    assert report.warnings == []


def test_real_persona_set_covers_every_role_within_the_expected_range():
    scenarios = load_personas(REAL_PERSONAS_DIR)
    per_role = Counter(s.persona.role for s in scenarios)
    assert set(per_role) == ALL_ROLES
    for role in ALL_ROLES:
        assert MIN_SCENARIOS_PER_ROLE <= per_role[role] <= MAX_SCENARIOS_PER_ROLE, (
            f"{role}: {per_role[role]}"
        )


# -- set-wide invariants over the real eval/personas/ directory -------------
#
# eval/personas/README.md documents five set-wide design invariants; each was
# its own review round with the mentor. Only the first (time_horizon) used to
# be mechanically checked (eval/check_time_horizon.py, now a thin wrapper
# around the same function these tests call). The other four were prose only
# -- a scenario edit could silently break one and CI would stay green. These
# tests re-derive all five against the committed set so that stops being true.


def test_real_persona_set_correct_answer_deadline_inside_time_horizon():
    # See "Deadline proximity" in eval/personas/README.md: the correct answer's
    # own deadline must sit inside its scenario's time_horizon window. Only a
    # distractor's deadline may fall beyond it.
    scenarios = load_personas(REAL_PERSONAS_DIR)
    violations = [
        v for s in scenarios if (v := correct_answer_deadline_violates_time_horizon(s))
    ]
    assert violations == []


def test_real_persona_set_blocked_task_holds_the_nearest_deadline():
    # See "blocked as a distractor" in eval/personas/README.md: whenever a
    # scenario has a blocked, deadlined task, that task's deadline must be the
    # nearest of any task in the scenario -- otherwise a naive
    # nearest-deadline heuristic would already skip it for the wrong reason.
    scenarios = load_personas(REAL_PERSONAS_DIR)
    violations = []
    for s in scenarios:
        blocked_with_deadline = {
            t.task_id for t in s.tasks if t.blocked and t.deadline is not None
        }
        if not blocked_with_deadline:
            continue
        deadlined = [(t.task_id, t.deadline) for t in s.tasks if t.deadline is not None]
        if len(deadlined) < 2:
            continue
        nearest_id = min(deadlined, key=lambda pair: pair[1])[0]
        if nearest_id not in blocked_with_deadline:
            violations.append(
                f"{s.scenario_id}: nearest deadline belongs to {nearest_id!r}, "
                f"not the blocked task(s) {blocked_with_deadline}"
            )
    assert violations == []


def test_real_persona_set_every_task_fits_free_time():
    # Every scenario in the 30-scenario rebuild is designed so both/all
    # candidate tasks fit context.free_time_minutes -- Chain's effort filter
    # must never be able to eliminate a distractor and hand the model a free
    # answer. See "Cheap-baseline scores" in eval/personas/README.md.
    scenarios = load_personas(REAL_PERSONAS_DIR)
    violations = [
        f"{s.scenario_id}: task {t.task_id!r} effort_estimate={t.effort_estimate} "
        f"> free_time_minutes={s.context.free_time_minutes}"
        for s in scenarios
        for t in s.tasks
        if t.effort_estimate > s.context.free_time_minutes
    ]
    assert violations == []


def test_real_persona_set_correct_answer_position_distribution():
    # Locked distribution documented in eval/personas/README.md's Status
    # section, guarding against the position bias the pre-2026-09-06 set had.
    # No 3-task scenario in the original set may put the correct answer first
    # (index 0). hierarchy_decides/ puts it first in two of its six on purpose, so
    # "pick the first task" is not scored 0/6 there by construction (#507 review).
    scenarios = load_personas(REAL_PERSONAS_DIR)
    hierarchy_ids = {s.scenario_id for s in load_personas(REAL_PERSONAS_DIR / HIERARCHY_DECIDES_GROUP)}
    position_counts: Counter[int] = Counter()
    three_task_scenarios_with_answer_first = []
    for s in scenarios:
        task_ids = [t.task_id for t in s.tasks]
        idx = task_ids.index(s.expected_top_task_id)
        position_counts[idx] += 1
        if len(task_ids) == 3 and idx == 0 and s.scenario_id not in hierarchy_ids:
            three_task_scenarios_with_answer_first.append(s.scenario_id)

    assert three_task_scenarios_with_answer_first == []
    assert dict(position_counts) == {0: 12, 1: 18, 2: 12}


def test_real_persona_set_no_duplicate_task_text_with_different_tier():
    # See "tier assignment" in eval/personas/README.md: tier describes what
    # kind of work a task is, not how important it is in a given scenario. If
    # the exact same task (title + description + effort_estimate) appears in
    # two files, it must carry the same tier in both.
    scenarios = load_personas(REAL_PERSONAS_DIR)
    seen: dict[tuple[str, str, int], tuple[str, str]] = {}
    violations = []
    for s in scenarios:
        for t in s.tasks:
            key = (t.title, t.description, t.effort_estimate)
            if key in seen:
                prev_tier, prev_scenario_id = seen[key]
                if prev_tier != t.tier:
                    violations.append(
                        f"{t.task_id!r} in {s.scenario_id!r} is {t.tier!r}, but the "
                        f"identical task in {prev_scenario_id!r} is {prev_tier!r}"
                    )
            else:
                seen[key] = (t.tier, s.scenario_id)
    assert violations == []


def test_real_persona_set_does_not_reward_description_length():
    # The mentor's finding: the correct answer was the longest description in all six hard
    # scenarios, so "pick the longest text" scored well without reading anything.
    # The ceiling is the same one `make eval-ci` enforces.
    scenarios = load_personas(REAL_PERSONAS_DIR)
    report = run(LongestDescriptionModel(), scenarios)
    assert report.top1_accuracy <= LONGEST_DESCRIPTION_CEILING


def test_in_the_hard_scenarios_the_correct_answer_is_never_the_longest_description():
    hard = [s for s in load_personas(REAL_PERSONAS_DIR) if s.scenario_id in HARD_SCENARIO_IDS]
    assert {s.scenario_id for s in hard} == HARD_SCENARIO_IDS
    longest_is_correct = [
        s.scenario_id
        for s in hard
        if max(s.tasks, key=lambda t: len(t.description)).task_id == s.expected_top_task_id
    ]
    assert longest_is_correct == []


def test_in_at_least_one_hard_scenario_the_nearest_deadline_is_the_correct_answer():
    # Otherwise "the nearest deadline is always a trap" becomes a rule a model can learn.
    hard = [s for s in load_personas(REAL_PERSONAS_DIR) if s.scenario_id in HARD_SCENARIO_IDS]
    nearest_is_correct = []
    for s in hard:
        dated = [t for t in s.tasks if t.deadline is not None and not t.blocked]
        if min(dated, key=lambda t: t.deadline).task_id == s.expected_top_task_id:
            nearest_is_correct.append(s.scenario_id)
    assert nearest_is_correct


def test_in_the_hard_scenarios_the_correct_answer_is_not_systematically_the_shortest():
    # Fixing "the correct answer is the longest" must not create the mirror-image shortcut.
    hard = [s for s in load_personas(REAL_PERSONAS_DIR) if s.scenario_id in HARD_SCENARIO_IDS]
    shortest_is_correct = [
        s.scenario_id
        for s in hard
        if min(s.tasks, key=lambda t: len(t.description)).task_id == s.expected_top_task_id
    ]
    assert len(shortest_is_correct) <= len(hard) // 2


# -- eval/personas/hierarchy_decides/ (#507) -------------------------------------


@pytest.fixture(scope="module")
def hierarchy_decides():
    return load_personas(REAL_PERSONAS_DIR / HIERARCHY_DECIDES_GROUP)


def _nearest_dated(s):
    return min((t for t in s.tasks if t.deadline is not None), key=lambda t: t.deadline)


def _correct(s):
    return next(t for t in s.tasks if t.task_id == s.expected_top_task_id)


def test_hierarchy_decides_holds_exactly_the_planned_scenarios_one_per_role(hierarchy_decides):
    assert {s.scenario_id for s in hierarchy_decides} == HIERARCHY_DECIDES_IDS
    assert Counter(s.persona.role for s in hierarchy_decides) == {role: 1 for role in ALL_ROLES}


def test_hierarchy_decides_has_at_least_three_decided_and_one_or_two_controls(
    hierarchy_decides,
):
    # Decided: the correct answer is NOT the nearest deadline (a feature-only reading
    # picks a sibling). Control: a hard signal holds the nearest deadline and wins.
    controls = [s for s in hierarchy_decides if _nearest_dated(s) is _correct(s)]
    decided = [s for s in hierarchy_decides if s not in controls]

    assert len(decided) >= 3
    assert 1 <= len(controls) <= 2


def test_in_decided_scenarios_the_rivals_are_near_equal_on_features(hierarchy_decides):
    # The nearest-deadline rival shares the correct answer's tier and effort and is due
    # within two hours of it: only the hierarchy separates them, and not as a full tie.
    for s in hierarchy_decides:
        correct, rival = _correct(s), _nearest_dated(s)
        if rival is correct:
            continue
        assert correct.deadline is not None, s.scenario_id
        assert (correct.tier, correct.effort_estimate) == (rival.tier, rival.effort_estimate), (
            s.scenario_id
        )
        assert 0 < (correct.deadline - rival.deadline).total_seconds() <= 2 * 3600, s.scenario_id


def test_in_hierarchy_decides_no_two_candidates_fit_the_free_time_together(hierarchy_decides):
    # The mentor's #535 finding: if two tasks fit together, doing both loses nothing and
    # the "correct" first pick is ambiguous.
    for s in hierarchy_decides:
        efforts = sorted((t.effort_estimate for t in s.tasks), reverse=True)
        assert efforts[0] + efforts[1] > s.context.free_time_minutes, s.scenario_id


def test_in_hierarchy_decides_the_correct_answer_is_never_the_longest_description(
    hierarchy_decides,
):
    longest_is_correct = [
        s.scenario_id
        for s in hierarchy_decides
        if max(s.tasks, key=lambda t: len(t.description)).task_id == s.expected_top_task_id
    ]
    assert longest_is_correct == []


def test_in_hierarchy_decides_the_correct_answer_is_not_systematically_the_shortest(
    hierarchy_decides,
):
    shortest_is_correct = [
        s.scenario_id
        for s in hierarchy_decides
        if min(s.tasks, key=lambda t: len(t.description)).task_id == s.expected_top_task_id
    ]
    assert len(shortest_is_correct) <= len(hierarchy_decides) // 2
