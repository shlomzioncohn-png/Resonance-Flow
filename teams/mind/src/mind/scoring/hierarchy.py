"""The parent-node shape that hierarchical value propagation reads.

A task's `parent_task_id` points at a node *outside* the task's own list. This
module defines what such a node looks like, so the propagation code and every
source of nodes agree on one shape: the eval's fixture
(`eval/hierarchy/nodes.json`, read by `load_hierarchy`) now, and an adapter over
Chain's traversal API in production.

Agreed between R1 (evaluation) and R3 (propagation). It lives in `scoring/`, not
`evaluation/`, so production scoring never imports from the evaluation package.

Two layers of rules, as in `mind.evaluation.interfaces`:

- **Per node** — field types and bounds, on `HierarchyNode`.
- **Across the node list** — unique ids, every non-null parent present, no
  cycles — on `HierarchyFixture`, so a violating file fails to load.

What this module cannot see — whether the tasks pointing *into* the fixture
name real nodes — is checked where both are loaded (`mind.evaluation.hierarchy`).
"""

from __future__ import annotations

from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field, model_validator


class HierarchyNode(BaseModel):
    """One parent node: an initiative or goal that tasks can sit under."""

    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    task_id: str = Field(
        pattern=r"^[a-z0-9_]+$",
        description=(
            "Unique across the fixture. Same character rule as Task.task_id, so a "
            "task's parent_task_id and a node's own parent_task_id resolve the same way."
        ),
    )
    parent_task_id: str | None = Field(
        description=(
            "task_id of this node's own parent, or null for a root. Required with no "
            "default: a root is stated explicitly, never implied by a missing field."
        ),
    )
    importance: float = Field(
        ge=0.0,
        le=1.0,
        description=(
            "How important this node is to the organization, 0-1. Set by hand: a "
            "fixed input, not computed from anything (not from depth, not from the "
            "tasks under it)."
        ),
    )
    label: str = Field(
        min_length=1,
        description="Short human-readable description. For readers only; no model reads it.",
    )


class HierarchyFixture(BaseModel):
    """A whole node file: every parent node the tasks can point at."""

    model_config = ConfigDict(extra="forbid")

    nodes: list[HierarchyNode] = Field(min_length=1)

    @model_validator(mode="after")
    def _task_ids_unique(self) -> HierarchyFixture:
        task_ids = [node.task_id for node in self.nodes]
        duplicates = sorted({tid for tid in task_ids if task_ids.count(tid) > 1})
        if duplicates:
            raise ValueError(f"node task_id must be unique; duplicated: {duplicates}")
        return self

    @model_validator(mode="after")
    def _parents_exist(self) -> HierarchyFixture:
        task_ids = {node.task_id for node in self.nodes}
        dangling = sorted(
            f"{node.task_id} -> {node.parent_task_id}"
            for node in self.nodes
            if node.parent_task_id is not None and node.parent_task_id not in task_ids
        )
        if dangling:
            raise ValueError(
                "parent_task_id must name a node in the fixture (an orphan is null, "
                f"not a missing id); dangling: {dangling}"
            )
        return self

    @model_validator(mode="after")
    def _acyclic(self) -> HierarchyFixture:
        parent_of = {node.task_id: node.parent_task_id for node in self.nodes}
        for start, first_parent in parent_of.items():
            seen = {start}
            current = first_parent
            while current is not None and current in parent_of:
                if current in seen:
                    raise ValueError(f"cycle in the node hierarchy, reached from {start!r}")
                seen.add(current)
                current = parent_of[current]
        return self

    def by_id(self) -> dict[str, HierarchyNode]:
        """Nodes keyed by task_id, for walking a chain upward."""
        return {node.task_id: node for node in self.nodes}


def load_hierarchy(path: str | Path) -> HierarchyFixture:
    """Load and validate a node file. Raises FileNotFoundError or a ValidationError."""
    return HierarchyFixture.model_validate_json(Path(path).read_text(encoding="utf-8"))
