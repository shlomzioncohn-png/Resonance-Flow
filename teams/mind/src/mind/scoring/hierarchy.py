"""The parent-node shape that hierarchical value propagation reads.

A task's `parent_task_id` points at a node *outside* the task's own list. This
module defines what such a node looks like, so the propagation code and every
source of nodes agree on one shape: the eval's fixture
(`eval/hierarchy/nodes.json`, read by `load_hierarchy`) now, and an adapter over
Chain's traversal API in production.

Agreed between R1 (evaluation) and R3 (propagation). It lives in `scoring/`, not
`evaluation/`, so production scoring never imports from the evaluation package.

Only the *shape* is enforced here: field types and bounds on `HierarchyNode`,
and unique ids on `HierarchyFixture` (without them `by_id` would silently drop a
node). Whether a graph may contain a cycle, or a parent that is not in it, is
deliberately **not** decided here: that is the propagation's behaviour to define
(#473), and its test graphs need to be able to contain both. The eval's own
fixture must have neither; that is checked by `mind.evaluation.hierarchy`, next
to the rule that scenario tasks point at real nodes.
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

    def by_id(self) -> dict[str, HierarchyNode]:
        """Nodes keyed by task_id, for walking a chain upward."""
        return {node.task_id: node for node in self.nodes}


def load_hierarchy(path: str | Path) -> HierarchyFixture:
    """Load a node file and check its shape. Raises OSError or a ValidationError.

    A graph with a cycle or a dangling parent loads; whether that is acceptable
    is the caller's rule (see the module docstring)."""
    return HierarchyFixture.model_validate_json(Path(path).read_text(encoding="utf-8"))
