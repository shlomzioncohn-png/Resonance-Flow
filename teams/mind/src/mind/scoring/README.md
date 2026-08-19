# scoring

Segmented ranking + (stretch) hierarchical value propagation.

- One scoring rule per tier (operational / tactical / strategic) using tier-native features.
- Stretch goal (week 7): propagate importance down `parent_task_id` via Chain's dependency-query API.
- Boundary: MIND publishes **the per-task score**. Everything downstream (feasibility, selection) belongs to Chain.
- Explanation generation lives here too — the system must say *why* a task scored the way it did.
