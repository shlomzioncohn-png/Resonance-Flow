"""Turning what extraction found into a suggested owner, or none (#132).

The sender is never the owner, as the issue says. The prompt asks for that,
but a sender who signs "Thanks, Avi" has written the name, so grounding keeps
it and the model sometimes gives it. The address in the email record is what
tells the code who sent it.
"""

import logging
from dataclasses import dataclass
from uuid import UUID, uuid4

import pytest

from mind.contracts import AssigneeType
from mind.task_creation.owner import (
    MIN_OWNER_SCORE,
    SuggestedOwner,
    choose_owner,
    is_the_sender,
    what_to_look_up,
)

SENDER = "avi.katz@acme-demo.test"


@pytest.mark.parametrize("mention", ["Avi", "Avi Katz", "Katz Avi", "avi katz", "Katz"])
def test_a_name_made_of_the_senders_address_is_the_sender(mention: str) -> None:
    assert is_the_sender(mention, SENDER)


@pytest.mark.parametrize(
    "mention",
    [
        "Dana",
        # One word outside the address: another Avi, perhaps.
        "Avi Levi",
        # The domain is the company, not a person.
        "Acme",
        "Demo",
    ],
)
def test_a_name_with_a_word_outside_the_address_is_not(mention: str) -> None:
    assert not is_the_sender(mention, SENDER)


def test_a_display_name_beside_the_address_counts_too() -> None:
    """ "Avraham Katz <ak@acme-demo.test>": the address alone would miss him."""

    assert is_the_sender("Avraham", "Avraham Katz <ak@acme-demo.test>")


def test_an_address_unlike_the_name_is_a_known_miss() -> None:
    """A heuristic, not an identity check: ak@ does not spell Avraham."""

    assert not is_the_sender("Avraham", "ak@acme-demo.test")


@pytest.mark.parametrize("sender", [None, ""])
def test_no_sender_names_nobody(sender: str | None) -> None:
    """Chats and meetings have no single sender to rule out."""

    assert not is_the_sender("Avi", sender)


# --- choosing one user from what the directory found ---------------------------


@dataclass(frozen=True)
class _Candidate:
    """The shape #131's DirectoryCandidate has: kind, id, similarity score."""

    assignee_type: AssigneeType
    id: UUID
    score: float


def _user(score: float) -> _Candidate:
    return _Candidate(AssigneeType.USER, uuid4(), score)


def _team(score: float) -> _Candidate:
    return _Candidate(AssigneeType.TEAM, uuid4(), score)


@pytest.mark.parametrize("score", [1.0, 0.95, 0.88, MIN_OWNER_SCORE])
def test_one_user_at_or_above_the_minimum_is_the_owner_at_its_score(score: float) -> None:
    """Full name or address 1.0, an initial 0.95, a typo 0.88, a first name alone 0.70."""

    user = _user(score)

    assert choose_owner([user]) == SuggestedOwner(user_id=user.id, confidence=score)


@pytest.mark.parametrize("score", [0.60, 0.56, 0.52])
def test_a_weak_match_is_no_owner(score: float, caplog: pytest.LogCaptureFixture) -> None:
    """A shortened name, a nickname, a typo in a lone first name."""

    with caplog.at_level(logging.INFO, logger="mind.task_creation"):
        assert choose_owner([_user(score)]) is None

    assert "below" in caplog.text


@pytest.mark.parametrize(("first", "second"), [(0.70, 0.70), (0.95, 0.93), (0.72, 0.68)])
def test_two_users_this_close_are_a_tie_and_no_owner(
    first: float, second: float, caplog: pytest.LogCaptureFixture
) -> None:
    """Two Danas, or a second one just under the minimum: picking would be a guess."""

    with caplog.at_level(logging.INFO, logger="mind.task_creation"):
        assert choose_owner([_user(first), _user(second)]) is None

    assert "tie" in caplog.text


def test_a_clear_winner_over_a_partial_match_is_the_owner() -> None:
    """ "Dana Cohen" in full, and another Dana on her first name only."""

    winner = _user(1.0)

    assert choose_owner([winner, _user(0.70)]) == SuggestedOwner(winner.id, 1.0)


@pytest.mark.parametrize("team_score", [1.0, 0.95])
def test_a_team_does_not_stand_in_a_users_way(team_score: float) -> None:
    """Teams are not suggested (no Inbox field yet), so only users compete."""

    user = _user(1.0)

    assert choose_owner([_team(team_score), user]) == SuggestedOwner(user.id, 1.0)
    assert choose_owner([user, _team(team_score)]) == SuggestedOwner(user.id, 1.0)


def test_only_a_team_is_no_owner_and_says_so(caplog: pytest.LogCaptureFixture) -> None:
    """ "Someone from Sales": the issue logs it and sends nothing."""

    with caplog.at_level(logging.INFO, logger="mind.task_creation"):
        assert choose_owner([_team(1.0), _user(0.55)]) is None

    assert "team" in caplog.text


def test_a_team_beside_a_weak_user_logs_both(caplog: pytest.LogCaptureFixture) -> None:
    """The user is there, only too weak: the log must not say there is none."""

    with caplog.at_level(logging.INFO, logger="mind.task_creation"):
        assert choose_owner([_team(1.0), _user(0.69)]) is None

    assert "team" in caplog.text
    assert "0.69, below 0.70" in caplog.text


def test_no_candidate_is_no_owner() -> None:
    """No match, or Inbox could not be asked: #131 gives an empty list for both."""

    assert choose_owner([]) is None


def test_the_order_given_does_not_matter() -> None:
    winner = _user(1.0)

    assert choose_owner([_user(0.70), winner]) == SuggestedOwner(winner.id, 1.0)


# --- what to ask the directory for -----------------------------------------------

DANA = "dana.cohen@acme-demo.test"


def _look_up(
    mention: str | None = None,
    recipient: bool = False,
    recipients: tuple[str, ...] = (DANA,),
    sender: str | None = SENDER,
) -> str | None:
    return what_to_look_up(mention, recipient, sender=sender, recipients=recipients)


def test_the_one_recipient_asked_is_looked_up_by_address() -> None:
    assert _look_up(recipient=True) == DANA


@pytest.mark.parametrize("recipients", [(DANA, "moshe.katz@acme-demo.test"), ()])
def test_the_recipient_without_exactly_one_address_is_nobody(
    recipients: tuple[str, ...], caplog: pytest.LogCaptureFixture
) -> None:
    """Several: which one was asked is a guess. None: a chat or a meeting."""

    with caplog.at_level(logging.INFO, logger="mind.task_creation"):
        assert _look_up(recipient=True, recipients=recipients) is None

    assert f"{len(recipients)} recipient" in caplog.text


def test_a_name_that_is_not_the_sender_is_looked_up_by_name() -> None:
    assert _look_up("Yossi Levi") == "Yossi Levi"


def test_the_senders_own_name_is_never_looked_up(caplog: pytest.LogCaptureFixture) -> None:
    with caplog.at_level(logging.INFO, logger="mind.task_creation"):
        assert _look_up("Avi") is None

    assert "sender" in caplog.text
    assert "Avi" not in caplog.text


def test_a_name_without_a_sender_is_looked_up() -> None:
    """A chat's "Malki: I'm on it": there is no one sender to rule out."""

    assert _look_up("Malki", recipients=(), sender=None) == "Malki"


def test_neither_answer_is_nothing_to_look_up() -> None:
    assert _look_up() is None


def test_with_one_recipient_the_recipient_comes_before_a_name(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """ "Hi Dana, please sign" sent to Dana: both answers, one person, and her
    address is surer than her first name. Logged without either, to count it."""

    with caplog.at_level(logging.INFO, logger="mind.task_creation"):
        assert _look_up("Dana", recipient=True) == DANA

    assert "both" in caplog.text
    assert "Dana" not in caplog.text and "dana" not in caplog.text


@pytest.mark.parametrize("recipients", [(), (DANA, "moshe.katz@acme-demo.test")])
def test_without_one_recipient_the_name_is_looked_up(recipients: tuple[str, ...]) -> None:
    """A chat's stray recipient flag must not cost it Malki."""

    assert _look_up("Malki", recipient=True, recipients=recipients, sender=None) == "Malki"


def test_the_recipient_log_does_not_call_a_chat_an_email(
    caplog: pytest.LogCaptureFixture,
) -> None:
    with caplog.at_level(logging.INFO, logger="mind.task_creation"):
        _look_up(recipient=True, recipients=())

    assert "email" not in caplog.text
    assert "0 recipients" in caplog.text


def test_someone_sharing_the_senders_first_name_is_not_the_sender() -> None:
    """dana@ writes "Please ask Dana Levi to sign": another Dana, kept."""

    assert not is_the_sender("Dana Levi", "dana@acme-demo.test")


def test_a_fuller_name_than_the_record_knows_is_a_known_miss() -> None:
    """One way only: the record's "Lisa" does not spell "Lisa Grant", and
    blocking it would block every other Lisa too."""

    assert not is_the_sender("Lisa Grant", "Lisa")


@pytest.mark.parametrize("sender", [DANA, "Dana Cohen <Dana.Cohen@acme-demo.test>"])
def test_an_email_to_oneself_has_no_owner(sender: str, caplog: pytest.LogCaptureFixture) -> None:
    """The one recipient is the sender: the issue rules the sender out."""

    with caplog.at_level(logging.INFO, logger="mind.task_creation"):
        assert _look_up(recipient=True, sender=sender) is None

    assert "sender" in caplog.text
