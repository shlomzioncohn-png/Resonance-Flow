"""`owner_mention` checked against the content it claims to come from (#132).

A name is held more strictly than a dependency mention: no word may be
missing, and the words must stand next to each other, in either order. A name
put together from two people the content does mention is an invented owner.
Only a name written as a name counts: the words inside an email address do
not, since the recipient's address comes from the record, not from here.
"""

import logging
import time

import pytest

from mind.extraction.grounding import Said, ground_owner_mention, said_in
from mind.extraction.models import ExtractedFields


def _owner(mention: str | None) -> ExtractedFields:
    return ExtractedFields(
        title="Sign the supplier contract", has_dependencies=False, owner_mention=mention
    )


def _grounded(mention: str | None, content: str) -> str | None:
    return ground_owner_mention(_owner(mention), content).owner_mention


@pytest.mark.parametrize(
    ("mention", "content"),
    [
        ("Dana Cohen", "Please ask Dana Cohen to sign the contract."),
        ("Dana Cohen", "Please ask dana\ncohen to sign the contract."),
        # The same two words side by side, the other way round.
        ("Dana Cohen", "Cohen, Dana signs for finance."),
        # Short words count: nothing is dropped from a name.
        ("Li Wu", "Li Wu will sign the contract."),
        ("Dana C.", "Dana C. will sign the contract."),
    ],
)
def test_a_name_the_content_writes_is_kept(mention: str, content: str) -> None:
    assert _grounded(mention, content) == mention


@pytest.mark.parametrize(
    ("mention", "content"),
    [
        # Both words are said, by two other people.
        ("Dana Cohen", "Dana Levi and Moshe Cohen already signed."),
        # A whole word must match a whole word.
        ("Dan", "Dana will sign the contract."),
        ("Dana Cohen", "Dana will sign the contract."),
        ("Yossi Levi", "Dana will sign the contract."),
    ],
)
def test_a_name_the_content_does_not_write_is_dropped(mention: str, content: str) -> None:
    assert _grounded(mention, content) is None


def test_dropping_is_logged_without_the_name(caplog: pytest.LogCaptureFixture) -> None:
    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        _grounded("Yossi Levi", "Dana will sign the contract.")

    assert "does not appear in the content" in caplog.text
    assert "Yossi" not in caplog.text


EMAIL = (
    "From: yossi.levi@acme-demo.test\n"
    "To: dana.cohen@acme-demo.test\n"
    "Subject: Supplier contract for Moshe Katz\n\n"
    "Hi, you are asked to sign the supplier contract before Friday.\n"
    "Thanks, Avi"
)


@pytest.mark.parametrize("mention", ["Dana", "Dana Cohen", "Yossi Levi"])
def test_a_name_inside_an_address_is_not_written(mention: str) -> None:
    """Neither the recipient nor the sender is named outside their address."""

    assert _grounded(mention, EMAIL) is None


def test_an_address_does_not_join_the_words_around_it() -> None:
    content = "Ask Dana dana.levi@acme-demo.test Cohen to sign."

    assert _grounded("Dana Cohen", content) is None


@pytest.mark.parametrize("mention", ["Moshe Katz", "Avi"])
def test_a_name_written_outside_the_addresses_is_kept(mention: str) -> None:
    """The subject line counts, and so does a signature: grounding closes
    invention, not who wrote the message. Only the prompt keeps the sender out."""

    assert _grounded(mention, EMAIL) == mention


def test_no_owner_is_left_alone() -> None:
    fields = _owner(None)

    assert ground_owner_mention(fields, EMAIL) is fields


def test_the_items_words_read_once_are_used_instead_of_the_text() -> None:
    """Like the mentions and the deadline: a meeting of fifteen tasks is read
    once, not fifteen times. Given `said`, the text is not read again."""

    said = said_in("Ask Dana Cohen to sign. From: avi.katz@acme-demo.test")

    assert ground_owner_mention(_owner("Dana Cohen"), "", said).owner_mention == "Dana Cohen"
    assert ground_owner_mention(_owner("Avi Katz"), "", said).owner_mention is None


def test_a_long_run_of_letters_is_read_in_linear_time() -> None:
    """A Gmail message goes as it is, base64url and all, up to the content cap.
    Searching it for addresses from every character took 25 s at 100k."""

    started = time.perf_counter()
    said_in("A" * 200_000)

    assert time.perf_counter() - started < 2


def test_said_cannot_be_built_without_the_owners_words() -> None:
    """A Said with no name_words would drop every owner as not in the content."""

    with pytest.raises(TypeError):
        Said(frozenset(), frozenset())  # type: ignore[call-arg]
