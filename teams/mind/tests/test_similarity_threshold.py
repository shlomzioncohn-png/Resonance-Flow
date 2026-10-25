"""The lexical threshold checked on the hand-labelled pairs of `extraction_eval_design.md` §5.

Gold sentences are real AMI items; the extracted side is the invented one from §5,
and which pairs are "the same task" was decided by hand in September, before any
pipeline output existed. Checking τ against them is a design check, not tuning
(§6, "Lock discipline").

What the check found (2026-10-25, §6): pair by pair, τ = 0.60 does not separate
the two groups. Same-task pairs score 0.69 and up, but different-task pairs from
the same meeting reach 0.72, because the meetings repeat the same words
("designer", "present", "concept"). One-to-one matching still picks exactly the
hand-labelled pairs, because each true pair outscores the wrong ones. These
tests pin that, including when the extractor misses one item.
"""

import pytest

from mind.evaluation.extraction_eval import evaluate_extraction
from mind.evaluation.similarity import LEXICAL_THRESHOLD, lexical_similarity

# meeting -> (gold by id, extracted by id, the same-task pairs as (gold id, extracted id))
MEETINGS: dict[str, tuple[dict[str, str], dict[str, str], set[tuple[str, str]]]] = {
    # §5.1: paraphrase and abbreviation; E5 is a discussion point, not an action.
    "IS1008b": (
        {
            "G1": "The Industrial Designer will make a presentation on the components concept "
            "for the product.",
            "G2": "The User Interface Designer will make a presentation on the user interface "
            "concept.",
            "G3": "The Marketing Expert will present the marketing concept.",
            "G4": "The Project Manager will research companies that can provide the Industrial "
            "Designer with help on integrating voice recognition into her design.",
        },
        {
            "E1": "Industrial Designer to present the components concept",
            "E2": "UI Designer to present the interface concept",
            "E3": "Marketing Expert to present the marketing concept",
            "E4": "PM to find companies to help with voice recognition",
            "E5": "Team to decide on the remote's functions",
        },
        {("G1", "E1"), ("G2", "E2"), ("G3", "E3"), ("G4", "E4")},
    ),
    # §5.3: repeated wording ("will work on ... the remote") across the items.
    "ES2002a": (
        {
            "G1": "the industrial designer will work on the working design of the remote",
            "G2": "the user interface designer will work on the technical functions of the remote",
            "G3": "the marketing executive will work on what requirements the remote has to fulfill",
        },
        {
            "E1": "industrial designer: work on the working design",
            "E2": "UI designer: work on technical functions of the remote",
            "E3": "marketing exec: define product requirements",
        },
        {("G1", "E1"), ("G2", "E2"), ("G3", "E3")},
    ),
}


def _matched_ids(
    gold: dict[str, str], extracted: dict[str, str], keep: list[str]
) -> set[tuple[str, str]]:
    """Run the harness on the `keep` extracted items; return the matched pairs as ids."""

    report = evaluate_extraction(
        [extracted[e] for e in keep],
        list(gold.values()),
        similarity=lexical_similarity,
        threshold=LEXICAL_THRESHOLD,
    )
    gold_id = {text: g for g, text in gold.items()}
    extracted_id = {text: e for e, text in extracted.items()}
    return {(gold_id[m.annotated], extracted_id[m.extracted]) for m in report.matched}


@pytest.mark.parametrize("meeting", list(MEETINGS))
def test_every_same_task_pair_clears_the_threshold(meeting: str) -> None:
    gold, extracted, same = MEETINGS[meeting]
    for g, e in same:
        assert lexical_similarity(gold[g], extracted[e]) >= LEXICAL_THRESHOLD, (g, e)


@pytest.mark.parametrize("meeting", list(MEETINGS))
def test_matching_finds_exactly_the_hand_labelled_pairs(meeting: str) -> None:
    gold, extracted, same = MEETINGS[meeting]
    assert _matched_ids(gold, extracted, list(extracted)) == same


@pytest.mark.parametrize(
    ("meeting", "missing"),
    [(meeting, e) for meeting, (_, _, same) in MEETINGS.items() for _, e in sorted(same)],
)
def test_a_missed_item_does_not_pull_in_a_wrong_match(meeting: str, missing: str) -> None:
    """The extractor misses one item: its gold sentence stays unmatched, nothing else moves."""

    gold, extracted, same = MEETINGS[meeting]
    keep = [e for e in extracted if e != missing]
    assert _matched_ids(gold, extracted, keep) == {(g, e) for g, e in same if e != missing}
