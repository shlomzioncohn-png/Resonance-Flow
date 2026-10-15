"""The eval's rules for its hierarchy fixture, and between it and the persona set.

`HierarchyFixture` (mind.scoring.hierarchy) only checks the node shape and unique
ids. It is shared with the propagation code, whose test graphs (#473) must be
able to contain a cycle or a dangling parent, so it does not forbid either. The
eval's own fixture must have neither, and that rule lives here, with the ones
that need the scenarios too. All of them return messages instead of raising,
like `validate_personas`:

- `validate_hierarchy_fixture`: every non-null node `parent_task_id` names a node
  in the fixture, and no chain loops back on itself;
- `validate_hierarchy_links`: every non-null `Task.parent_task_id` names a node in
  the fixture (an orphan is null, never an id that does not exist), and no node id
  is also a scenario task_id, so a parent can never be mistaken for a candidate
  the baseline scores.
"""

from __future__ import annotations

from mind.evaluation.interfaces import PersonaEvaluationScenario
from mind.evaluation.personas import HIERARCHY_DIR
from mind.scoring.hierarchy import HierarchyFixture

# The node file's name inside a hierarchy directory. The one place it is spelled
# out: run.py reads `<--hierarchy dir> / NODES_FILENAME`, and HIERARCHY_PATH is the
# real set's file.
NODES_FILENAME = "nodes.json"
HIERARCHY_PATH = HIERARCHY_DIR / NODES_FILENAME


def validate_hierarchy_fixture(fixture: HierarchyFixture) -> list[str]:
    """Errors in the eval fixture's own graph; an empty list means it is a forest."""
    parent_of = {node.task_id: node.parent_task_id for node in fixture.nodes}
    errors = [
        f"{node_id}: parent_task_id {parent!r} is not a node in the fixture "
        "(an orphan is null, not a missing id)"
        for node_id, parent in sorted(parent_of.items())
        if parent is not None and parent not in parent_of
    ]

    for start, first_parent in sorted(parent_of.items()):
        seen = {start}
        current = first_parent
        while current is not None and current in parent_of:
            if current in seen:
                errors.append(f"{start}: its parent chain loops back to {current!r} (a cycle)")
                break
            seen.add(current)
            current = parent_of[current]

    return errors


def validate_hierarchy_links(
    scenarios: list[PersonaEvaluationScenario], fixture: HierarchyFixture
) -> list[str]:
    """Errors linking `scenarios` to `fixture`; an empty list means they agree."""
    node_ids = {node.task_id for node in fixture.nodes}
    errors: list[str] = []

    for scenario in scenarios:
        for task in scenario.tasks:
            if task.parent_task_id is not None and task.parent_task_id not in node_ids:
                errors.append(
                    f"{scenario.scenario_id}/{task.task_id}: parent_task_id "
                    f"{task.parent_task_id!r} is not a node in the hierarchy fixture"
                )

    task_ids = {task.task_id for scenario in scenarios for task in scenario.tasks}
    for collision in sorted(node_ids & task_ids):
        errors.append(f"{collision}: used both as a hierarchy node and as a scenario task_id")

    return errors
