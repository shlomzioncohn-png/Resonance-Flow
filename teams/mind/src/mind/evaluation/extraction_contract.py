"""The contract for one extracted action item, as the extraction-quality eval reads it.

`evaluate_extraction` (`extraction_eval.py`) compares *strings*. This module is the
typed shape those strings come from: the minimal projection of an extracted task
that the eval actually depends on — the title, the optional description, and the
meeting-id join key. Nothing else: `role_context` is deliberately absent, because
the classification stage fills it, not extraction (`extraction/assembly.py` leaves
it unset). It joins as an optional field when the owner-match diagnostic
(`extraction_eval_design.md` §3.7) is built — adding an optional field breaks no
existing payload.

Same pattern as `RankingModel` in `interfaces.py`: R1 defines the contract here,
the extraction pipeline (R2 / Yonit) produces output that satisfies it. Being a
Pydantic model rather than a paragraph means the pipeline's output is *checked*
against it, not merely described by it — the code cannot drift from the spec
because the code is the spec. This is the same reason `PersonaEvaluationScenario`
is a model and not a Word doc.

Field names match `TaskCreationRequested` (Yonit's MIND->Inbox event,
`contracts/task_creation_requested.py`, from PR #197) exactly,
so her pipeline feeds this eval with no translation layer. `extra="ignore"` (not
`PersonaEvaluationScenario`'s `extra="forbid"`) so a full `TaskCreationRequested`
payload validates as-is — this model is a deliberate subset of a larger upstream
object, not a hand-authored file where an unknown key means a typo. "As-is" holds
for corpus-loader payloads only: a Bridge-path payload has `source_ref=None` and
is rejected, by design — the eval needs the meeting id to join against the
annotations (see the `source_ref` field).

STATUS: proposed by R1, pending confirmation from Yonit that the extraction
pipeline output fits this shape (issue step 4). Not yet agreed — see
`extraction_eval_design.md` §"Meeting IDs" for the open `TODO(Yonit)`.
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


class ExtractedItem(BaseModel):
    """One extracted action item, projected to the fields the extraction eval reads.

    The full extracted task (`TaskCreationRequested`) also carries tier, urgency,
    confidence, deadline, owner suggestions, etc. Extraction precision/recall asks
    "did the pipeline find the right action items", not "did it classify them
    right", so none of those are here and `evaluate_extraction` never sees them.
    """

    model_config = ConfigDict(extra="ignore")

    title: str = Field(
        min_length=1,
        description=(
            "The action item, one line. The head of the string the matcher "
            "compares. Matches TaskCreationRequested.title."
        ),
    )
    description: str | None = Field(
        default=None,
        min_length=1,
        description=(
            "Optional detail. When present it is appended to `title` as "
            "`title + '. ' + description` to form the comparison string — an "
            "AMI gold sentence carries roughly a title-plus-detail of content "
            "(extraction_eval_design.md §3.3). Absent is `None` only: an empty "
            "string is rejected, so a consumer has one case instead of two (the "
            "extraction pipeline already maps blank placeholders to `None`). "
            "Matches TaskCreationRequested.description."
        ),
    )
    source_ref: str = Field(
        min_length=1,
        description=(
            "The meeting this item was extracted from — the join key against the "
            "annotation set. The AMI meeting code verbatim, e.g. 'IS1008b' (the "
            "corpus loader sets it from the manifest record id; "
            "extraction_eval_design.md §'Meeting IDs'). "
            "TaskCreationRequested.source_ref is nullable because the Bridge "
            "ingestion path carries no meeting id; it is REQUIRED here because the "
            "eval only ever consumes corpus-loader output, where it is always set."
        ),
    )

    def to_comparison_text(self) -> str:
        """The string `evaluate_extraction` matches for this item (design §3.3).

        `title + '. ' + description` when there is a description, else just
        `title`. Normalization (casing, whitespace, trailing period) happens
        inside `evaluate_extraction`, not here.
        """
        if self.description is not None:
            return f"{self.title}. {self.description}"
        return self.title
