"""The persona set and eval/hierarchy/nodes.json agree, and say what the README says.

The first group checks the cross-file rules on constructed data. The second pins
the real set's hierarchy design (#507): the two scenarios chosen for the week-7
"changed / did not change" question, the deliberate orphans and the two-level
chain. If one of these fails, either the data drifted or the README is now wrong.
"""

import pytest

from mind.evaluation.hierarchy import (
    HIERARCHY_PATH,
    validate_hierarchy_fixture,
    validate_hierarchy_links,
)
from mind.evaluation.personas import PERSONAS_DIR, load_personas
from mind.evaluation.placeholder_scenarios import PLACEHOLDER_SCENARIOS
from mind.scoring.hierarchy import HierarchyFixture, load_hierarchy

_FIXTURE = HierarchyFixture.model_validate(
    {
        "nodes": [
            {"task_id": "goal", "parent_task_id": None, "importance": 0.8, "label": "Goal"},
        ]
    }
)


def _graph(*edges):
    """A fixture from (task_id, parent_task_id) pairs."""
    return HierarchyFixture.model_validate(
        {
            "nodes": [
                {"task_id": t, "parent_task_id": p, "importance": 0.5, "label": t}
                for t, p in edges
            ]
        }
    )


def test_a_forest_with_a_two_level_chain_is_a_valid_fixture():
    assert validate_hierarchy_fixture(_graph(("goal", None), ("initiative", "goal"))) == []


def test_fixture_with_a_parent_missing_from_it_is_an_error():
    errors = validate_hierarchy_fixture(_graph(("child", "nowhere")))

    assert len(errors) == 1
    assert "'nowhere' is not a node" in errors[0]


@pytest.mark.parametrize(
    "edges",
    [
        [("loop", "loop")],
        [("a", "b"), ("b", "c"), ("c", "a")],
    ],
)
def test_fixture_with_a_cycle_is_an_error(edges):
    errors = validate_hierarchy_fixture(_graph(*edges))

    assert errors
    assert all("cycle" in e for e in errors)


def _scenario_with_parent(parent_task_id):
    scenario = PLACEHOLDER_SCENARIOS[0].model_copy(deep=True)
    scenario.tasks[0].parent_task_id = parent_task_id
    return scenario


def test_parent_that_names_a_node_is_accepted():
    assert validate_hierarchy_links([_scenario_with_parent("goal")], _FIXTURE) == []


def test_orphan_task_is_accepted():
    assert validate_hierarchy_links([_scenario_with_parent(None)], _FIXTURE) == []


def test_parent_missing_from_the_fixture_is_an_error():
    errors = validate_hierarchy_links([_scenario_with_parent("nowhere")], _FIXTURE)

    assert len(errors) == 1
    assert "'nowhere'" in errors[0]


def test_node_id_that_is_also_a_task_id_is_an_error():
    scenario = PLACEHOLDER_SCENARIOS[0]
    clashing = HierarchyFixture.model_validate(
        {
            "nodes": [
                {
                    "task_id": scenario.tasks[0].task_id,
                    "parent_task_id": None,
                    "importance": 0.5,
                    "label": "Clashes with a candidate task",
                }
            ]
        }
    )

    errors = validate_hierarchy_links([scenario], clashing)

    assert len(errors) == 1
    assert "used both" in errors[0]


# --- the real set -------------------------------------------------------------


@pytest.fixture(scope="module")
def scenarios():
    return {s.scenario_id: s for s in load_personas(PERSONAS_DIR)}


@pytest.fixture(scope="module")
def nodes():
    return load_hierarchy(HIERARCHY_PATH).by_id()


def _parent(scenario, task_id):
    return next(t for t in scenario.tasks if t.task_id == task_id).parent_task_id


def test_real_set_links_cleanly_to_the_real_fixture(scenarios):
    fixture = load_hierarchy(HIERARCHY_PATH)

    assert validate_hierarchy_fixture(fixture) == []
    assert validate_hierarchy_links(list(scenarios.values()), fixture) == []


def test_some_scenarios_have_parents_and_some_tasks_are_orphans(scenarios):
    tasks = [t for s in scenarios.values() for t in s.tasks]

    assert any(t.parent_task_id is not None for t in tasks)
    assert any(t.parent_task_id is None for t in tasks)


def test_fixture_has_a_two_level_chain(nodes):
    depth_two = [
        n for n in nodes.values()
        if n.parent_task_id is not None and nodes[n.parent_task_id].parent_task_id is None
    ]

    assert depth_two


def test_changed_case_correct_answer_sits_under_the_more_important_parent(scenarios, nodes):
    # The week-7 "designed to change" scenario (README). It replaced
    # manager_status_digest_vs_reorg_proposal, which the baseline already gets right.
    scenario = scenarios["manager_restructuring_team_signoff_vs_maintenance_team_signoff"]
    correct = nodes[_parent(scenario, "task_atlas_signoff")]
    distractor = nodes[_parent(scenario, "task_birchwood_signoff")]

    assert scenario.expected_top_task_id == "task_atlas_signoff"
    assert correct.importance > distractor.importance
    assert correct.parent_task_id is not None  # the two-level chain


def test_unchanged_case_orphan_answer_beats_a_distractor_with_an_important_parent(
    scenarios, nodes
):
    scenario = scenarios["developer_prod_crash_vs_backlog_feature"]

    assert scenario.expected_top_task_id == "task_crash"
    assert _parent(scenario, "task_crash") is None
    assert nodes[_parent(scenario, "task_backlog_widget")].importance >= 0.8


# --- the README table, as data --------------------------------------------------

LOW, MEDIUM, HIGH = 0.2, 0.5, 0.8

# Every task that has a parent, as the importances along its chain (parent first,
# then grandparent). This is the "Every scenario with a hierarchy" table in
# src/mind/evaluation/README.md; a task not listed here must be an orphan, and a
# scenario not listed here must be fully orphan. Change both together.
EXPECTED_CHAINS: dict[str, dict[str, tuple[float, ...]]] = {
    "manager_status_digest_vs_reorg_proposal": {
        "task_reorg": (HIGH, HIGH),
        "task_status": (LOW,),
    },
    "developer_prod_crash_vs_backlog_feature": {"task_backlog_widget": (HIGH,)},
    "developer_owns_arch_decision_vs_dep_bump": {"task_arch": (HIGH,)},
    "developer_blocked_oauth_vs_pr_review": {"task_oauth": (HIGH,)},
    "manager_sprint_plan_vs_1on1_notes": {"task_sprint": (MEDIUM,)},
    "higher_manager_stalled_renewal_vs_market_expansion": {
        "task_renewal": (HIGH,),
        "task_expansion": (MEDIUM,),
    },
    "salesperson_expense_report_vs_departed_champion": {"task_champion": (HIGH,)},
    "salesperson_contract_redlines_vs_cold_prospecting": {
        "task_redlines": (MEDIUM,),
        "task_prospecting": (HIGH,),
    },
    "higher_manager_regulatory_filing_vs_strategy_section": {"task_strategy": (HIGH,)},
    "ceo_regulator_call_vs_board_prep": {"task_board": (HIGH, HIGH)},
    "manager_blocked_hire_vs_budget_review": {
        "task_hire": (HIGH,),
        "task_budget": (MEDIUM,),
    },
    "higher_manager_blocked_budget_vs_headcount_plan": {
        "task_budget": (HIGH,),
        "task_headcount": (HIGH,),
    },
    "ceo_overdue_launch_statement_vs_earnings_prep": {
        "task_statement": (LOW,),
        "task_earnings": (HIGH,),
    },
    "higher_manager_board_deck_vs_reorg_memo": {
        "task_deck": (HIGH,),
        "task_memo": (LOW,),
    },
    "ceo_investor_update_vs_allhands_prep": {
        "task_investor": (HIGH,),
        "task_allhands": (HIGH,),
    },
    "manager_escalation_call_vs_design_review": {
        "task_escalation": (MEDIUM,),
        "task_review": (MEDIUM, MEDIUM),
    },
    "salesperson_processing_cutoff_vs_call_summary": {
        "task_orderform": (HIGH,),
        "task_summary": (HIGH,),
    },
    "field_engineer_customer_degradation_vs_internal_tooling": {"task_script": (MEDIUM,)},
    "salesperson_dead_rfp_vs_live_signing_round": {
        "task_rfp": (LOW,),
        "task_signing": (HIGH,),
    },
    "ceo_own_investor_commitment_vs_unaccepted_keynote_request": {
        "task_investor": (HIGH,),
        "task_acquisitions": (LOW,),
    },
    "higher_manager_approved_vision_memo_vs_expiring_capacity_offer": {
        "task_vision": (HIGH,),
        "task_capacity": (HIGH,),
    },
    # hierarchy_decides/: the hierarchy decides the first four, the last two are controls.
    "manager_restructuring_team_signoff_vs_maintenance_team_signoff": {
        "task_atlas_signoff": (HIGH, HIGH),
        "task_birchwood_signoff": (LOW,),
    },
    "salesperson_core_target_proposal_vs_paused_vertical_proposal": {
        "task_northgate": (HIGH,),
        "task_pinecrest": (LOW,),
    },
    "higher_manager_migration_budget_vs_reporting_requests": {
        "task_orion_budget": (HIGH,),
        "task_ledger_budget": (LOW,),
        "task_ledger_staffing": (LOW,),
    },
    "ceo_expansion_partnership_vs_wound_down_line_partnership": {
        "task_halvorsen": (MEDIUM, HIGH),
        "task_quayle": (MEDIUM, LOW),
    },
    "developer_payment_incident_vs_migration_steps": {
        "task_port_config": (HIGH, HIGH),
        "task_port_tests": (HIGH, HIGH),
    },
    "field_engineer_site_outage_vs_rollout_survey": {
        "task_site_restore": (LOW,),
        "task_rollout_survey": (HIGH,),
    },
}

# Pairs the README says sit under the very same node (the "shared parent" controls).
SHARED_PARENT = {
    "higher_manager_blocked_budget_vs_headcount_plan": ("task_budget", "task_headcount"),
    "salesperson_processing_cutoff_vs_call_summary": ("task_orderform", "task_summary"),
    "higher_manager_migration_budget_vs_reporting_requests": (
        "task_ledger_budget",
        "task_ledger_staffing",
    ),
    "developer_payment_incident_vs_migration_steps": ("task_port_config", "task_port_tests"),
}


def _ancestors(nodes, parent_task_id):
    """Node ids from `parent_task_id` up to its root, failing on a cycle."""
    chain = []
    current = parent_task_id
    while current is not None:
        assert len(chain) <= len(nodes), f"cycle reached from {parent_task_id!r}"
        chain.append(current)
        current = nodes[current].parent_task_id
    return chain


def _chain(nodes, parent_task_id):
    return tuple(nodes[node_id].importance for node_id in _ancestors(nodes, parent_task_id))


def test_every_task_has_exactly_the_hierarchy_the_readme_table_states(scenarios, nodes):
    actual = {
        scenario_id: {
            t.task_id: _chain(nodes, t.parent_task_id)
            for t in scenario.tasks
            if t.parent_task_id is not None
        }
        for scenario_id, scenario in scenarios.items()
    }
    actual = {scenario_id: chains for scenario_id, chains in actual.items() if chains}

    assert actual == EXPECTED_CHAINS


def test_shared_parent_pairs_point_at_the_same_node(scenarios):
    for scenario_id, (first, second) in SHARED_PARENT.items():
        scenario = scenarios[scenario_id]
        assert _parent(scenario, first) == _parent(scenario, second), scenario_id


def test_every_importance_is_one_of_the_three_documented_levels(nodes):
    off_scale = {n.task_id: n.importance for n in nodes.values()}
    off_scale = {k: v for k, v in off_scale.items() if v not in (LOW, MEDIUM, HIGH)}

    assert off_scale == {}


def test_every_node_belongs_to_exactly_one_scenario(scenarios, nodes):
    # "Each scenario has its own small tree": a node reached from two scenarios
    # couples them, and a node reached from none is dead data nobody reviews.
    owners: dict[str, set[str]] = {node_id: set() for node_id in nodes}
    for scenario_id, scenario in scenarios.items():
        for task in scenario.tasks:
            for node_id in _ancestors(nodes, task.parent_task_id):
                owners[node_id].add(scenario_id)

    shared = {n: sorted(s) for n, s in owners.items() if len(s) > 1}
    unused = sorted(n for n, s in owners.items() if not s)
    assert shared == {}
    assert unused == []
