"""What `owner_mention` and `owner_is_recipient` accept, and what they refuse (#132).

Optional, like `dependency_mentions`: a value that cannot be used becomes "no
owner" and is logged, and never costs a repair round. The model names a person
or says the task is the recipient's; it never writes an address, which the
code takes from the email record itself.
"""

import logging

import pytest

from mind.extraction.models import (
    MAX_OWNER_MENTION_LENGTH,
    ExtractedFields,
    ExtractedFieldsWithClassification,
)


def _task(owner: object = None, recipient: object = False) -> ExtractedFields:
    return ExtractedFields.model_validate(
        {
            "title": "Sign the supplier contract",
            "has_dependencies": False,
            "owner_mention": owner,
            "owner_is_recipient": recipient,
        }
    )


def test_no_owner_is_the_default_in_both_shapes() -> None:
    answer = {"title": "Sign the supplier contract", "has_dependencies": False}

    for shape in (ExtractedFields, ExtractedFieldsWithClassification):
        task = shape.model_validate(answer)
        assert (task.owner_mention, task.owner_is_recipient) == (None, False)


def test_a_name_is_kept_as_written() -> None:
    assert _task("  Dana Cohen ").owner_mention == "Dana Cohen"


@pytest.mark.parametrize("written", ["", "N/A", "unknown", "None", "-"])
def test_placeholders_mean_no_owner(written: str) -> None:
    assert _task(written).owner_mention is None


@pytest.mark.parametrize(
    ("written", "kept"),
    [
        # One name written as a list: the right answer, written wrong.
        (["Dana Cohen"], "Dana Cohen"),
        # The same name twice is still one owner.
        (["Dana Cohen", " dana cohen"], "Dana Cohen"),
        ([], None),
        # Two names are two owners, and Inbox takes one: picking would be a guess.
        (["Dana Cohen", "Yossi Levi"], None),
        (["Dana Cohen", 3], None),
        (3, None),
        ({"name": "Dana"}, None),
    ],
)
def test_a_shape_that_is_not_one_name_never_raises(written: object, kept: str | None) -> None:
    assert _task(written).owner_mention == kept


@pytest.mark.parametrize(
    "written", ["dana.cohen@acme-demo.test", "Dana Cohen <dana.cohen@acme-demo.test>"]
)
def test_an_address_is_not_a_name(written: str, caplog: pytest.LogCaptureFixture) -> None:
    """The recipient's address comes from the email record, never from the
    model: it could copy the sender's instead."""

    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        assert _task(written).owner_mention is None

    assert "address" in caplog.text
    assert "dana" not in caplog.text.lower()


def test_a_runaway_mention_is_dropped_and_logged(caplog: pytest.LogCaptureFixture) -> None:
    """A mention as long as a sentence is the content echoed back, not a name."""

    runaway = "Dana from the finance team " * (MAX_OWNER_MENTION_LENGTH // 10)

    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        task = _task(runaway)

    assert task.owner_mention is None
    assert "longer than" in caplog.text


@pytest.mark.parametrize(("written", "read"), [(True, True), ("true", True), (None, False)])
def test_the_recipient_flag_reads_a_yes_or_a_no(written: object, read: bool) -> None:
    assert _task(recipient=written).owner_is_recipient is read


def test_a_recipient_flag_that_is_not_a_yes_or_no_never_raises(
    caplog: pytest.LogCaptureFixture,
) -> None:
    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        assert _task(recipient="maybe").owner_is_recipient is False

    assert "owner_is_recipient" in caplog.text


def test_the_recipient_and_a_name_together_are_both_kept() -> None:
    """Which one wins needs the item's recipients, which parsing does not see:
    a chat has none, and its name must not be lost to a stray flag. Settled in
    task_creation/owner.what_to_look_up."""

    task = _task("Dana", recipient=True)

    assert (task.owner_mention, task.owner_is_recipient) == ("Dana", True)


def test_a_name_other_than_the_recipient_is_kept() -> None:
    """ "Yossi will sign it" sent to Dana: the owner is Yossi."""

    task = _task("Yossi Levi", recipient=False)

    assert (task.owner_mention, task.owner_is_recipient) == ("Yossi Levi", False)


@pytest.mark.parametrize("written", ["A", "C", "A C", "B."])
def test_a_speakers_letter_is_not_a_name(written: str, caplog: pytest.LogCaptureFixture) -> None:
    """AMI labels its speakers A to D, and the model gave "A" as an owner."""

    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        assert _task(written).owner_mention is None

    assert "letter" in caplog.text


@pytest.mark.parametrize("written", ["Dana C.", "Li Wu", "C. Dana"])
def test_an_initial_beside_a_whole_word_is_still_a_name(written: str) -> None:
    assert _task(written).owner_mention == written


@pytest.mark.parametrize("written", ["???", "..."])
def test_a_mention_with_no_letters_says_so(written: str, caplog: pytest.LogCaptureFixture) -> None:
    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        assert _task(written).owner_mention is None

    assert "no letters" in caplog.text
    assert "single letters" not in caplog.text


@pytest.mark.parametrize(
    "written",
    ["someone", "Someone", "everyone", "you", "me", "we", "Nobody", "she", "Him", "the team"],
)
def test_a_word_for_nobody_in_particular_is_not_a_name(
    written: str, caplog: pytest.LogCaptureFixture
) -> None:
    """ "Can someone check the deal?": "someone" is in the content, so grounding
    would keep it, and the directory would be asked for it."""

    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        assert _task(written).owner_mention is None

    assert "not a person" in caplog.text
