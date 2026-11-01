"""The hand-labelled pairs every similarity backend's τ is checked on (`extraction_eval_design.md` §5, §6).

Gold sentences are real AMI items; the extracted side is the invented one from §5,
and which pairs are "the same task" was decided by hand in September, before any
pipeline output existed. A backend's τ is chosen and checked on these pairs only,
never on precision/recall from a real run (§6, "Lock discipline"). Shared by the
lexical check (`tests/test_similarity_threshold.py`) and the embedding one
(`scripts/derive_embedding_threshold.py`), so both read the same pairs.
"""

from collections.abc import Callable

from mind.evaluation.extraction_eval import evaluate_extraction

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


def matched_ids(
    meeting: str,
    keep: list[str],
    *,
    similarity: Callable[[str, str], float],
    threshold: float,
) -> set[tuple[str, str]]:
    """Run the harness on `meeting`'s `keep` extracted items; return the matched pairs as ids."""

    gold, extracted, _ = MEETINGS[meeting]
    report = evaluate_extraction(
        [extracted[e] for e in keep],
        list(gold.values()),
        similarity=similarity,
        threshold=threshold,
    )
    gold_id = {text: g for g, text in gold.items()}
    extracted_id = {text: e for e, text in extracted.items()}
    return {(gold_id[m.annotated], extracted_id[m.extracted]) for m in report.matched}


def missing_one_cases() -> list[tuple[str, str]]:
    """Every (meeting, extracted id) whose true pair is dropped in a "missed item" run."""

    return [(meeting, e) for meeting, (_, _, same) in MEETINGS.items() for _, e in sorted(same)]


def matching_failures(similarity: Callable[[str, str], float], threshold: float) -> list[str]:
    """The checks a τ must pass, and which of them fail (empty = τ passes).

    The criterion both backends' τ is held to: at τ, one-to-one matching returns
    exactly the hand-labelled pairs of each meeting, also when each true
    extracted item is left out (no wrong pair slips in for the missing one).
    Pair-by-pair separation is not asked for: same-meeting wrong pairs can
    outscore weak true ones, and the matching keeps them out (§6).
    """

    failures = []
    for meeting, (_, extracted, same) in MEETINGS.items():
        got = matched_ids(meeting, list(extracted), similarity=similarity, threshold=threshold)
        if got != same:
            failures.append(f"{meeting} all items: {sorted(got)}")
    for meeting, missing in missing_one_cases():
        _, extracted, same = MEETINGS[meeting]
        keep = [e for e in extracted if e != missing]
        expected = {(g, e) for g, e in same if e != missing}
        got = matched_ids(meeting, keep, similarity=similarity, threshold=threshold)
        if got != expected:
            failures.append(f"{meeting} without {missing}: {sorted(got)}")
    return failures
