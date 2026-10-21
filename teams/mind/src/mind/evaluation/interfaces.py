"""The persona evaluation scenario schema (Pydantic) and the ranking-model contract.

The Pydantic models in this file are the **binding source of truth** for a
persona evaluation scenario. `eval/personas/scenario.schema.json` is *generated*
from `PersonaEvaluationScenario` (`python -m mind.evaluation.schema`) so that
`make eval`, other teams and the mentor still have a language-agnostic artifact
to read; `tests/test_schema_snapshot.py` fails if the committed file drifts from
the models. See `eval/personas/README.md`.

Two layers of rules live here:

- **Structural** — required fields, types, enums, string/number bounds. Enforced
  by the field declarations, exactly like the old JSON Schema did.
- **Intra-scenario invariants** — rules that need more than one field of the
  *same* scenario (`expected_top_task_id` must name a real task; `source_detail`
  is required for some `source.type` values). Enforced by the `@model_validator`
  methods below, so a violating file fails to load.

Cross-file rules (a `scenario_id` duplicated across the whole directory) and the
"set isn't finished yet" checks (role coverage, 4-6 per role, 24-36 total) are *not* expressible on a single-scenario model and
stay in `mind.evaluation.personas.validate_personas` — the latter as warnings,
which Pydantic (raise-or-nothing) cannot represent.
"""

from __future__ import annotations

from datetime import datetime
from typing import Annotated, Literal, Protocol, runtime_checkable

from pydantic import BaseModel, ConfigDict, Field, model_validator

Role = Literal[
    "Developer", "Manager", "Higher Manager", "Field Engineer", "Salesperson", "CEO"
]
Seniority = Literal["Junior", "Mid", "Senior", "Executive"]
Department = Literal["Engineering", "Sales", "Operations"]
Tier = Literal["operational", "tactical", "strategic"]
TimeHorizon = Literal["today", "this_week", "this_month"]
SourceType = Literal["synthetic", "real", "AMI", "FUNSD", "other"]

# `source_detail` is required in practice when the scenario is derived from one of
# these origins — enforced in PersonaEvaluationScenario, not by the field itself.
# Typed with SourceType (not str) so a misspelled member is a mypy error, not a
# set entry that silently never matches.
SOURCE_TYPES_REQUIRING_DETAIL: frozenset[SourceType] = frozenset({"AMI", "FUNSD", "other"})


class Task(BaseModel):
    """A single candidate task within a persona evaluation scenario.

    The harness calls `RankingModel.score()` once per Task and builds the ranking
    from the returned scores — a model never sees the whole scenario.
    """

    model_config = ConfigDict(extra="forbid")

    task_id: str = Field(
        pattern=r"^[a-z0-9_]+$",
        description=(
            "Unique within this scenario. Referenced by expected_top_task_id. "
            "Same character rule as scenario_id (lowercase, digits, underscore)."
        ),
    )
    title: str = Field(min_length=1)
    description: str = Field(
        min_length=1,
        description=(
            "Enough detail that a model (and a human reader) can judge duration, "
            "urgency and relevance without outside knowledge."
        ),
    )
    deadline: datetime | None = Field(
        default=None,
        description=(
            "ISO 8601 timestamp, or null if the task has no hard deadline. "
            "Evaluated against context.current_datetime."
        ),
    )
    effort_estimate: int = Field(
        ge=1,
        description=(
            "Effort estimate for the task, in minutes — the eval set's concrete form "
            "of the canonical Task.effort_estimate (owned by Inbox). Recorded for "
            "Chain's downstream constraint-satisfaction step; the ranking model sees "
            "it, but a scenario's correct answer must not hinge on comparing it to "
            "context.free_time_minutes (see eval/personas/README.md)."
        ),
    )
    tier: Tier = Field(
        description=(
            "What KIND of task this is — independent of persona. A Developer can "
            "receive an operational or a tactical task; tier is not implied by role. "
            "Lowercase values, matching the canonical Task schema (Inbox) and "
            "src/mind/scoring/. See 'Persona ≠ Tier' in eval/personas/README.md."
        )
    )
    blocked: bool = Field(
        description=(
            "True if this task cannot currently be worked on right now (e.g. waiting "
            "on something outside this scenario). A blocked task should generally not "
            "be the expected_top_task_id."
        )
    )
    parent_task_id: str | None = Field(
        default=None,
        description=(
            "The task_id of this task's parent in the cross-tier organizational "
            "hierarchy (a developer task under a manager task, etc.), matching the "
            "canonical Task.parent_task_id. Points outside this scenario, at a node "
            "in eval/hierarchy/nodes.json (checked by "
            "mind.evaluation.hierarchy.validate_hierarchy_links, not here); the "
            "parent is never one of the scenario's candidate tasks. null = an "
            "orphan, on purpose. Read by the week-7 hierarchical value-propagation "
            "model. This is NOT the old same-tier `dependencies` array — that was "
            "removed deliberately and is not coming back."
        ),
    )


class Persona(BaseModel):
    """Who the user is, in the terms the system cares about."""

    model_config = ConfigDict(extra="forbid")

    role: Role = Field(
        description=(
            "Who the user is (see MIND-01-System-Overview.md role table). Same six "
            "values across the whole persona set."
        )
    )
    seniority: Seniority | None = Field(
        default=None,
        description=(
            "Only set when seniority genuinely changes the correct answer for this "
            "scenario (e.g. a Junior Developer is not expected to pick up an "
            "architecture-review task a Senior would). Omit or null otherwise."
        ),
    )
    department: Department | None = Field(
        default=None,
        description=(
            "Optional, nullable. Some roles (e.g. CEO) are not scoped to a single "
            "department; leave null rather than forcing a value."
        ),
    )


class Context(BaseModel):
    """The situation the persona is in right now."""

    model_config = ConfigDict(extra="forbid")

    free_time_minutes: int = Field(
        ge=1,
        description=(
            "How much free time the persona has right now. The single most "
            "load-bearing field in the scenario — the same task list can have a "
            "different correct answer at 20 minutes vs. 120 minutes."
        ),
    )
    current_datetime: datetime = Field(
        description=(
            "ISO 8601 timestamp establishing 'now' for this scenario, so "
            "task.deadline can be evaluated as near/far/passed."
        )
    )
    time_horizon: TimeHorizon = Field(
        description=(
            "The window the persona is planning within. A CEO scenario will "
            "typically use a wider horizon than a Developer scenario."
        )
    )
    constraints: list[str] = Field(
        default_factory=list,
        description=(
            "Any additional situational constraint the model must know about to "
            "answer correctly (e.g. 'no meetings can be scheduled'). Empty list when "
            "there are none."
        ),
    )


class Source(BaseModel):
    """Where this scenario came from — for later performance analysis by origin."""

    model_config = ConfigDict(extra="forbid")

    type: SourceType = Field(
        description=(
            "'real' = hand-written from a genuine (non-Gong) situation; 'AMI'/'FUNSD' "
            "= derived from those public corpora; 'synthetic' = fabricated for "
            "coverage; 'other' = anything else, explained in source_detail."
        )
    )
    source_detail: str | None = Field(
        default=None,
        description=(
            "Free-text pointer to the concrete origin (e.g. an AMI meeting ID). "
            "Required in practice when type is 'AMI', 'FUNSD' or 'other'."
        ),
    )


class PersonaEvaluationScenario(BaseModel):
    """One fixed evaluation scenario for MIND's persona evaluation harness.

    Every scenario in eval/personas/, regardless of persona (Developer, Manager,
    Higher Manager, Field Engineer, Salesperson, CEO), conforms to this one
    model. Only the values differ between personas — never the structure.
    """

    model_config = ConfigDict(extra="forbid")

    scenario_id: str = Field(
        pattern=r"^[a-z0-9_]+$",
        description=(
            "Unique identifier across the whole eval/personas/ set. Convention: "
            "<role>_<short_slug>, e.g. developer_late_pr_review."
        ),
    )
    persona: Persona
    context: Context
    tasks: list[Task] = Field(
        min_length=2,
        description=(
            "The candidate tasks the persona is choosing between. At least two, so "
            "the scenario actually tests a choice."
        ),
    )
    expected_top_task_id: str = Field(
        description=(
            "The task_id (from `tasks`) a correct model must pick as the single best "
            "use of the persona's free time. Must equal the task_id of exactly one "
            "entry in `tasks` (enforced below)."
        )
    )
    expected_reason: str = Field(
        min_length=20,
        description=(
            "Short, checkable explanation of why expected_top_task_id is correct, "
            "grounded in per-task signals the ranking model can actually see: a hard "
            "deadline, blocked, tier, or an importance/scope judgment from the "
            "description. Must NOT hinge on context.free_time_minutes or a duration "
            "comparison — that is Chain's concern, not the ranking model's."
        ),
    )
    source: Source
    team_lead_approved: bool = Field(
        description=(
            "Whether the team lead has reviewed this scenario in a PR and signed off "
            "on it. This is the only approval flag on a scenario. Set explicitly by "
            "the author: start at false, the team lead (not the author) flips it to "
            "true."
        )
    )

    @model_validator(mode="after")
    def _task_ids_unique(self) -> PersonaEvaluationScenario:
        task_ids = [task.task_id for task in self.tasks]
        duplicates = sorted({tid for tid in task_ids if task_ids.count(tid) > 1})
        if duplicates:
            raise ValueError(
                f"task_id must be unique within a scenario; duplicated: {duplicates}"
            )
        return self

    @model_validator(mode="after")
    def _expected_top_task_id_names_a_task(self) -> PersonaEvaluationScenario:
        task_ids = {task.task_id for task in self.tasks}
        if self.expected_top_task_id not in task_ids:
            raise ValueError(
                f"expected_top_task_id {self.expected_top_task_id!r} is not one of "
                f"this scenario's task_ids {sorted(task_ids)}"
            )
        return self

    @model_validator(mode="after")
    def _source_detail_present_when_required(self) -> PersonaEvaluationScenario:
        if self.source.type in SOURCE_TYPES_REQUIRING_DETAIL and not self.source.source_detail:
            raise ValueError(
                f"source.type is {self.source.type!r} but source_detail is empty"
            )
        return self


class ScoreFactors(BaseModel):
    """The contributions behind one `ScoreResult.score`.

    Deliberately generic: the contract is shared by every ranking model, so it
    does not name any model's factors. Each model owns its own fixed factor
    vocabulary (and the test that its keys stay inside it) in its own package.
    NaN/inf are rejected here; `run.py` also refuses to write them to report.json.
    """

    model_config = ConfigDict(extra="forbid", frozen=True, allow_inf_nan=False)

    contributions: dict[Annotated[str, Field(min_length=1)], float] = Field(
        min_length=1,
        description=(
            "Named contributions that produced the score, keyed by factor name. Each "
            "model owns its own factor vocabulary (a fixed set, enforced in that "
            "model's package, not here); a model with one rule per tier may return "
            "different keys per tier. Any finite value, negative allowed (e.g. an "
            "explicit penalty). Not required to sum to `score`."
        ),
    )
    blocked: bool = Field(
        default=False,
        description="True when the model applied a blocked adjustment to this score.",
    )
    base_score: float | None = Field(
        default=None,
        description="The score before any blocked adjustment, when the model has one.",
    )


class ScoreResult(BaseModel):
    """What a ranking model returns for one task."""

    model_config = ConfigDict(extra="forbid")

    score: float = Field(
        ge=0.0,
        le=1.0,
        description=(
            "Priority score on a global 0-1 scale (higher = more important to do "
            "now). Each tier may be scored by its own rule, but the model "
            "calibrates the results onto this one scale so tasks of different "
            "tiers are comparable."
        ),
    )
    reasoning: str | None = Field(
        default=None,
        description=(
            "Optional short explanation for the score — compared against a "
            "scenario's expected_reason in later analysis, not by the harness."
        ),
    )
    factors: ScoreFactors | None = Field(
        default=None,
        description=(
            "Optional breakdown of what produced `score`; None means no breakdown. "
            "Written into the harness report per candidate, never used for ranking. "
            "Exists so a later explanation step can be built from the factors that "
            "actually produced the score, instead of reverse-engineering the formula."
        ),
    )


class ScoreContext(BaseModel):
    """The only situational information a ranking model gets alongside a task.

    Deliberately minimal: it lets the model judge how near `task.deadline` is and
    nothing else about the persona's situation (not free_time_minutes, not
    constraints — those are Chain's concern). The harness always sets
    `current_datetime` from the scenario; it is never defaulted to the wall clock.
    """

    model_config = ConfigDict(extra="forbid")

    current_datetime: datetime = Field(
        description=(
            "'Now', as the model should see it. Set by the harness from the "
            "current_datetime of the scenario being scored."
        )
    )


@runtime_checkable
class RankingModel(Protocol):
    """Contract every ranking model must implement to run through the evaluation harness."""

    def score(self, task: Task, context: ScoreContext) -> ScoreResult:
        """Return a `ScoreResult` (0-1 priority score) for a single task, given
        the current time via `context`. Higher score = higher priority."""
        ...
