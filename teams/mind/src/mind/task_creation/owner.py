"""The suggested owner of a task, or none (#132).

Why the sender is ruled out here and not in grounding, and the rule and
numbers for choosing a user: README.md, "The owner".
"""

import logging
import re
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from email.utils import parseaddr
from typing import Protocol
from uuid import UUID

from mind.contracts import AssigneeType

logger = logging.getLogger("mind.task_creation")

# A word of a name or of an address's local part: letters and digits, so
# "avi.katz" and "avi_katz" are both "avi" and "katz".
_WORD = re.compile(r"[^\W_]+")

#: The lowest score sent as an owner: a first name alone (0.70 in #131).
#: Below it are a shortened name, a nickname or a typo in a lone first name.
MIN_OWNER_SCORE = 0.70

#: Two users closer than this are a tie, and a tie is no owner.
TIE_MARGIN = 0.05


class Candidate(Protocol):
    """What #131's DirectoryCandidate carries, read and nothing else."""

    @property
    def assignee_type(self) -> AssigneeType: ...
    @property
    def id(self) -> UUID: ...
    @property
    def score(self) -> float: ...


class OwnerDirectory(Protocol):
    """The part of #131's InboxDirectory the owner needs: never raises, and
    answers [] both for no match and for an Inbox it could not ask."""

    def find_candidates(self, tenant_id: UUID, name: str) -> Sequence[Candidate]: ...


@dataclass(frozen=True)
class SuggestedOwner:
    """What travels to Inbox: a user id and how sure Mind is of it."""

    user_id: UUID
    confidence: float


def _person_words(person: str) -> set[str]:
    """The words a person's name can be spelled from, for a sender or a
    recipient: the display name, if there is one, and the address before its
    "@". Never the domain, which is the company's: "Acme" is not the sender."""

    words: set[str] = set()
    for part in person.replace("<", " ").replace(">", " ").split():
        local = part.split("@", 1)[0]
        words.update(_WORD.findall(local.lower()))
    return words


def is_the_sender(owner_mention: str, sender: str | None) -> bool:
    """Whether every word of the mention is a word of the sender.

    The issue rules the sender out, and the prompt asks for it, but a sender
    who signs "Thanks, Avi" has written the name, so grounding keeps it. One
    way only: "Dana Levi" is someone else for dana@, though "Lisa Grant" is
    then missed for a sender the record knows only as "Lisa". A heuristic: an
    address that does not spell the name ("ak@" for Avraham) is missed too.
    """

    if not sender:
        return False
    return _spelled_from(owner_mention, sender)


def _spelled_from(owner_mention: str, person: str) -> bool:
    mention = set(_WORD.findall(owner_mention.lower()))
    return bool(mention) and mention <= _person_words(person)


def what_to_look_up(
    owner_mention: str | None,
    owner_is_recipient: bool,
    *,
    sender: str | None,
    recipients: tuple[str, ...],
) -> str | None:
    """What to ask the directory for: an address, a name, or None for nothing.

    The recipient is looked up by the address in the email record, and only
    when there is one: which of several was asked is a guess. Given with a
    name, the two must be one person ("Hi Dana, please sign" sent to Dana),
    told by the recipient's display name or address; then the address is
    surer. If they are two ("Yossi Levi will sign it" sent to Dana), one
    answer is wrong, and the address would send it at 1.0: no owner. Without one
    recipient (a chat, several) the name is looked up instead, so a stray
    flag costs nothing, and the same when the one recipient is the sender
    (an email to oneself). A name that is the sender's own is never looked up.
    Logged when an answer is dropped, never with the name or the address.
    """

    if owner_is_recipient:
        sender_address = parseaddr(sender)[1].lower() if sender else ""
        address = parseaddr(recipients[0])[1] if len(recipients) == 1 else ""
        to_self = bool(address) and address.lower() == sender_address
        if address and not to_self:
            if owner_mention is None:
                return address
            if _spelled_from(owner_mention, recipients[0]):
                logger.info(
                    "owner: both the recipient and a name, one person; keeping the recipient"
                )
                return address
            logger.info("owner: the name given is not the recipient's; no owner sent")
            return None
        logger.info(
            "owner: the task is the recipient's, but %s; %s",
            "the one recipient is the sender"
            if to_self
            else f"the item has {len(recipients)} recipients",
            "looking up the name" if owner_mention else "no owner sent",
        )
    if owner_mention is None:
        return None
    if is_the_sender(owner_mention, sender):
        logger.info("owner: the name is the sender's own; no owner sent")
        return None
    return owner_mention


def choose_owner(candidates: Iterable[Candidate]) -> SuggestedOwner | None:
    """The one user the directory found, at its score as the confidence, or None.

    Teams are left out, as Inbox has no field for one: they neither win nor
    stand in a user's way. None, logged with the reason, when no user reaches
    MIN_OWNER_SCORE, or when a second one is within TIE_MARGIN of the best,
    even one under the minimum: two Danas are a guess either way.
    """

    found = list(candidates)
    users = sorted(
        (c for c in found if c.assignee_type is AssigneeType.USER),
        key=lambda c: c.score,
        reverse=True,
    )
    if not users or round(users[0].score, 6) < MIN_OWNER_SCORE:
        reasons = []
        if any(c.assignee_type is AssigneeType.TEAM for c in found):
            reasons.append("the name matches a team")
        if users:
            reasons.append(
                f"the best user scores {users[0].score:.2f}, below {MIN_OWNER_SCORE:.2f}"
            )
        if reasons:
            logger.info("owner: %s; no owner sent", "; ".join(reasons))
        return None
    best = users[0]
    if len(users) > 1 and round(best.score - users[1].score, 6) < TIE_MARGIN:
        logger.info(
            "owner: a tie, two users score %.2f and %.2f; no owner sent",
            best.score,
            users[1].score,
        )
        return None
    return SuggestedOwner(user_id=best.id, confidence=best.score)
