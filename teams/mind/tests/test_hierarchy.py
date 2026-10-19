"""mind.scoring.hierarchy: the parent-node shape and its load-time rules."""

import json

import pytest
from pydantic import ValidationError

from mind.scoring.hierarchy import HierarchyFixture, HierarchyNode, load_hierarchy


def _node(task_id, parent_task_id=None, importance=0.5, label="A node"):
    return {
        "task_id": task_id,
        "parent_task_id": parent_task_id,
        "importance": importance,
        "label": label,
    }


def test_two_level_chain_loads():
    fixture = HierarchyFixture.model_validate(
        {
            "nodes": [
                _node("annual_goal", importance=0.8),
                _node("quarterly_initiative", parent_task_id="annual_goal", importance=0.8),
            ]
        }
    )

    by_id = fixture.by_id()
    assert by_id["quarterly_initiative"].parent_task_id == "annual_goal"
    assert by_id["annual_goal"].parent_task_id is None


def test_parent_task_id_is_required_even_for_a_root():
    node = _node("root")
    del node["parent_task_id"]

    with pytest.raises(ValidationError, match="parent_task_id"):
        HierarchyNode.model_validate(node)


@pytest.mark.parametrize("importance", [-0.1, 1.1, float("nan"), float("inf")])
def test_importance_outside_0_1_or_not_finite_is_rejected(importance):
    with pytest.raises(ValidationError):
        HierarchyNode.model_validate(_node("root", importance=importance))


@pytest.mark.parametrize("importance", [0.0, 1.0])
def test_importance_bounds_are_inclusive(importance):
    node = HierarchyNode.model_validate(_node("root", importance=importance))

    assert node.importance == importance


@pytest.mark.parametrize("task_id", ["Upper_case", "has-dash", "has space", ""])
def test_task_id_uses_the_task_id_character_rule(task_id):
    with pytest.raises(ValidationError):
        HierarchyNode.model_validate(_node(task_id))


def test_empty_label_is_rejected():
    with pytest.raises(ValidationError):
        HierarchyNode.model_validate(_node("root", label=""))


def test_unknown_field_is_rejected():
    with pytest.raises(ValidationError):
        HierarchyNode.model_validate({**_node("root"), "weight": 0.5})


def test_empty_node_list_is_rejected():
    with pytest.raises(ValidationError):
        HierarchyFixture.model_validate({"nodes": []})


def test_duplicate_task_id_is_rejected():
    with pytest.raises(ValidationError, match="duplicated"):
        HierarchyFixture.model_validate({"nodes": [_node("root"), _node("root")]})


# Cycles and dangling parents are the propagation's behaviour to define (#473), and
# its test graphs must be able to contain them, so the shared model loads them.
# The eval forbids both in its own fixture: tests/test_hierarchy_links.py.


def test_shared_model_loads_a_parent_missing_from_the_graph():
    fixture = HierarchyFixture.model_validate(
        {"nodes": [_node("child", parent_task_id="nowhere")]}
    )

    assert fixture.by_id()["child"].parent_task_id == "nowhere"


@pytest.mark.parametrize(
    "nodes",
    [
        [_node("loop", parent_task_id="loop")],
        [
            _node("a", parent_task_id="b"),
            _node("b", parent_task_id="c"),
            _node("c", parent_task_id="a"),
        ],
    ],
)
def test_shared_model_loads_a_cycle(nodes):
    fixture = HierarchyFixture.model_validate({"nodes": nodes})

    assert len(fixture.nodes) == len(nodes)


def test_load_hierarchy_reads_a_file(tmp_path):
    path = tmp_path / "nodes.json"
    path.write_text(json.dumps({"nodes": [_node("root", importance=0.2)]}), encoding="utf-8")

    fixture = load_hierarchy(path)

    assert fixture.by_id()["root"].importance == 0.2


def test_load_hierarchy_missing_file_raises(tmp_path):
    with pytest.raises(FileNotFoundError):
        load_hierarchy(tmp_path / "missing.json")
