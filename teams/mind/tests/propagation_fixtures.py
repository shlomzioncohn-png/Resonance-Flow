"""#473: constructed graphs with a known-correct order, for testing
hierarchical value propagation (#474) before touching real data.

Reuses `HierarchyNode`/`HierarchyFixture` (hierarchy.py, agreed with R1) for
the graph itself -- a leaf task and a goal/initiative node are the same
shape, a leaf is just a node nothing else points at as its parent. What
`HierarchyFixture` deliberately doesn't carry is the answer: this module
adds that.
"""

from __future__ import annotations

from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field, model_validator

from mind.scoring.hierarchy import HierarchyFixture

FIXTURES_DIR = Path(__file__).resolve().parent / "propagation_fixtures"


class PropagationFixture(BaseModel):
    """One constructed graph plus its known-correct answer.

    Exactly one of `expected_order` / `expected_error` is set: a graph
    either has a correct ranking, or it's deliberately invalid (the cycle
    case) and the correct behaviour is to refuse, not to rank.
    """

    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1)
    description: str = Field(min_length=1)
    graph: HierarchyFixture
    expected_order: list[str] = Field(default_factory=list)
    expected_error: str | None = None
    notes: str = ""

    @model_validator(mode="after")
    def _order_xor_error(self) -> PropagationFixture:
        if (self.expected_error is not None) == bool(self.expected_order):
            raise ValueError("exactly one of expected_order / expected_error must be set")
        return self

    def leaf_ids(self) -> set[str]:
        """task_ids nothing in the graph points at as a parent -- the
        tasks a propagation test ranks, as opposed to the goals/
        initiatives above them."""
        parent_ids = {n.parent_task_id for n in self.graph.nodes if n.parent_task_id is not None}
        return {n.task_id for n in self.graph.nodes} - parent_ids


def load_propagation_fixture(path: str | Path) -> PropagationFixture:
    """Load one fixture file. Raises OSError or a ValidationError."""
    return PropagationFixture.model_validate_json(Path(path).read_text(encoding="utf-8"))


def load_all_propagation_fixtures() -> list[PropagationFixture]:
    """Every fixture under propagation_fixtures/, sorted by filename so
    the order is stable across runs and platforms."""
    return [load_propagation_fixture(p) for p in sorted(FIXTURES_DIR.glob("*.json"))]
