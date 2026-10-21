"""One meeting-shaped text through everything after the model answers (#690).

The answer is scripted, so this proves nothing about what a model finds; the
live run on the same text does that. What it proves is that our own steps
hold together on a realistic input: parsing the list, grounding and scoring
each task on its own, cleaning the mentions, and one proposal per task.
"""

from pathlib import Path
from uuid import uuid4

from mind.contracts.base_enums import SourceType
from mind.extraction.assembly import to_task_creation_requested
from mind.extraction.extractor import BasicExtractor, ExtractionSuccess
from tests.extraction.fakes import ScriptedProvider, answer_of

MEETING = (Path(__file__).parent / "fixtures" / "extraction" / "design_meeting.txt").read_text(
    encoding="utf-8"
)

# What a good answer to the meeting looks like, with two slips planted in it:
# a `false` beside a named dependency, and a thread id the meeting never said.
ANSWER = answer_of(
    {
        "title": "Prepare the components concept",
        "description": "Mainly whether voice recognition fits in the case.",
        "source_ref": None,
        "has_dependencies": False,
        "dependency_mentions": ["the chip budget"],
    },
    {
        "title": "Get the chip budget from finance",
        "description": "By Wednesday.",
        "source_ref": None,
        "has_dependencies": True,
        "dependency_mentions": ["the components concept"],
    },
    {
        "title": "Work out the button layout",
        "description": "For the user interface concept.",
        "source_ref": "DES-9999",
        "has_dependencies": False,
    },
    {
        "title": "Check what the competitors charge",
        "description": "For the marketing concept.",
        "source_ref": None,
        "has_dependencies": False,
    },
)


def test_a_meeting_becomes_one_linked_proposal_per_action_item() -> None:
    provider = ScriptedProvider(ANSWER)
    outcome = BasicExtractor(provider, sleep=lambda _seconds: None).extract(
        MEETING, SourceType.MEETING, item_key="meetings/design-review"
    )

    assert isinstance(outcome, ExtractionSuccess)
    assert MEETING.strip() in provider.calls[0][1]  # sent whole, not truncated
    concept, budget, layout, pricing = outcome.tasks
    assert outcome.item_key == "meetings/design-review"

    # The pair that names each other: both linked, in the meeting's own words.
    for task in (concept, budget):
        assert task.fields.has_dependencies is True
        assert all(mention.lower() in MEETING.lower() for mention in task.fields.dependency_mentions)

    # The invented thread id is dropped and charged to that task alone.
    assert layout.fields.source_ref is None
    reasons = [[p.reason for p in t.confidence.penalties] for t in outcome.tasks]
    assert [("invented_source_ref" in r) for r in reasons] == [False, False, True, False]
    assert pricing.fields.dependency_mentions == []

    proposals = to_task_creation_requested(outcome, tenant_id=uuid4())
    assert [p.title for p in proposals] == [t.fields.title for t in outcome.tasks]
    assert all("dependency_mentions" not in p.model_dump() for p in proposals)
