"""Cross-file rules between the persona set and its hierarchy fixture.

`HierarchyFixture` (mind.scoring.hierarchy) checks the node file on its own:
unique ids, parents present, no cycles. What it cannot see is the other side —
the scenario tasks pointing *into* it. Those rules need both files loaded, so
they live here, next to `validate_personas`, and return messages instead of
raising, the same way:

- every non-null `Task.parent_task_id` names a node in the fixture (an orphan is
  null, never an id that does not exist);
- no node id is also a scenario task_id, so a parent can never be mistaken for a
  candidate the baseline scores.
"""

from __future__ import annotations

from mind.evaluation.interfaces import PersonaEvaluationScenario
from mind.evaluation.personas import HIERARCHY_DIR
from mind.scoring.hierarchy import HierarchyFixture

HIERARCHY_PATH = HIERARCHY_DIR / "nodes.json"


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
