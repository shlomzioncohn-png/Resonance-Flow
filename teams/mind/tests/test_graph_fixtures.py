"""#473: constructed dependency graphs with a known-correct order. Tests
the fixtures themselves, not propagation (#474, next week) -- these are
the test bed, not the thing under test."""

import pytest
from pydantic import ValidationError

from mind.evaluation.hierarchy import validate_hierarchy_fixture
from tests.propagation_fixtures import (
    PropagationFixture,
    load_all_propagation_fixtures,
)

REQUIRED_GRAPH_NAMES = {
    "chain",
    "branch",
    "deep_nesting",
    "orphan",
    "deprioritized_initiative",
    "cycle",
}


def _leaf_chain_parents(fixture: PropagationFixture, leaf_id: str) -> list[str]:
    """Walk leaf_id's parent_task_id chain upward. Raises ValueError on a
    cycle instead of looping forever -- a test helper exercising exactly
    the failure mode #473 asks the real algorithm to guard against."""
    by_id = fixture.graph.by_id()
    chain: list[str] = []
    visited = {leaf_id}
    current = by_id[leaf_id].parent_task_id
    while current is not None:
        if current in visited:
            raise ValueError(f"cycle detected walking up from {leaf_id}: revisited {current}")
        visited.add(current)
        chain.append(current)
        current = by_id[current].parent_task_id
    return chain


@pytest.fixture(scope="module")
def fixtures() -> list[PropagationFixture]:
    loaded = load_all_propagation_fixtures()
    assert loaded, "no propagation fixtures found -- load_all_propagation_fixtures() is broken"
    return loaded


def test_all_six_required_graphs_are_defined_and_loadable(fixtures):
    names = {f.name for f in fixtures}
    assert names == REQUIRED_GRAPH_NAMES


def test_each_graph_has_a_unique_clear_name(fixtures):
    names = [f.name for f in fixtures]
    assert len(names) == len(set(names))
    assert all(name.strip() for name in names)


def test_non_cycle_fixtures_have_no_hierarchy_errors(fixtures):
    """Dangling-reference and cycle checks collapsed into one assertion:
    a non-cycle fixture must produce zero validation errors, full stop --
    no substring filtering that could silently stop matching if the
    validator's error wording ever changes."""
    for fixture in fixtures:
        if fixture.expected_error is not None:
            continue
        assert validate_hierarchy_fixture(fixture.graph) == []


def test_rankable_fixtures_expected_order_is_exactly_the_leaf_set_once_each(fixtures):
    for fixture in fixtures:
        if fixture.expected_error is not None:
            continue
        assert sorted(fixture.expected_order) == sorted(fixture.leaf_ids()), (
            f"{fixture.name}: expected_order must list each leaf exactly once"
        )


def test_propagation_fixture_rejects_both_expected_order_and_expected_error_set():
    graph = {"nodes": [{"task_id": "root", "parent_task_id": None, "importance": 0.5, "label": "Root"}]}
    with pytest.raises(ValidationError):
        PropagationFixture.model_validate(
            {
                "name": "x",
                "description": "x",
                "graph": graph,
                "expected_order": ["root"],
                "expected_error": "boom",
            }
        )


def test_propagation_fixture_rejects_neither_expected_order_nor_expected_error_set():
    graph = {"nodes": [{"task_id": "root", "parent_task_id": None, "importance": 0.5, "label": "Root"}]}
    with pytest.raises(ValidationError):
        PropagationFixture.model_validate({"name": "x", "description": "x", "graph": graph})


def test_leaf_ids_excludes_parents_and_includes_only_childless_nodes():
    graph = {
        "nodes": [
            {"task_id": "root", "parent_task_id": None, "importance": 0.5, "label": "Root"},
            {"task_id": "child_a", "parent_task_id": "root", "importance": 0.5, "label": "A"},
            {"task_id": "child_b", "parent_task_id": "root", "importance": 0.5, "label": "B"},
        ]
    }
    fixture = PropagationFixture.model_validate(
        {
            "name": "x",
            "description": "x",
            "graph": graph,
            "expected_order": ["child_a", "child_b"],
        }
    )
    assert fixture.leaf_ids() == {"child_a", "child_b"}


def test_the_cycle_fixture_documents_an_error_not_an_order(fixtures):
    cycle = next(f for f in fixtures if f.name == "cycle")
    assert cycle.expected_error is not None
    assert cycle.expected_order == []


def test_only_the_cycle_fixture_has_an_expected_error(fixtures):
    for fixture in fixtures:
        if fixture.name == "cycle":
            continue
        assert fixture.expected_error is None, f"{fixture.name} should not expect an error"


def test_the_cycle_fixture_is_genuinely_cyclic(fixtures):
    """The inverse of the previous test, for the one fixture meant to be
    cyclic -- catches an author mistake that left it acyclic by accident,
    which would make the whole fixture pointless."""
    cycle = next(f for f in fixtures if f.name == "cycle")
    errors = validate_hierarchy_fixture(cycle.graph)
    assert errors
    assert any("cycle" in e for e in errors)


def test_the_orphan_fixture_has_a_leaf_with_no_parent(fixtures):
    orphan = next(f for f in fixtures if f.name == "orphan")
    by_id = orphan.graph.by_id()
    assert any(by_id[leaf_id].parent_task_id is None for leaf_id in orphan.leaf_ids())


def test_the_deep_nesting_fixture_is_actually_deep(fixtures):
    deep_nesting = next(f for f in fixtures if f.name == "deep_nesting")
    depths = {leaf_id: len(_leaf_chain_parents(deep_nesting, leaf_id)) for leaf_id in deep_nesting.leaf_ids()}
    assert max(depths.values()) >= 4


def test_the_branch_fixture_inverts_own_importance_against_parent_importance(fixtures):
    """Sanity-checks the trap is actually set: the fixture only proves
    anything if the low-importance parent's children score higher than
    the high-importance parent's children on their own features alone."""
    branch = next(f for f in fixtures if f.name == "branch")
    by_id = branch.graph.by_id()
    high_parent = next(n for n in branch.graph.nodes if n.parent_task_id is None and n.importance > 0.5)
    low_parent = next(n for n in branch.graph.nodes if n.parent_task_id is None and n.importance < 0.5)
    high_children_own = [by_id[lid].importance for lid in branch.leaf_ids() if by_id[lid].parent_task_id == high_parent.task_id]
    low_children_own = [by_id[lid].importance for lid in branch.leaf_ids() if by_id[lid].parent_task_id == low_parent.task_id]
    assert max(high_children_own) < min(low_children_own)


def test_the_deprioritized_initiative_fixture_isolates_parent_importance_alone(fixtures):
    """The two leaves must share the same own importance, or a passing
    test wouldn't prove the parent's importance is what decided it."""
    fixture = next(f for f in fixtures if f.name == "deprioritized_initiative")
    by_id = fixture.graph.by_id()
    own_scores = {by_id[leaf_id].importance for leaf_id in fixture.leaf_ids()}
    assert len(own_scores) == 1
