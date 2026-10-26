"""The suggested owner of a task, or none (#132).

Why the sender is ruled out here and not in grounding, and the rule and
numbers for choosing a user: README.md, "The owner".
"""

import logging
import re
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
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


def _sender_words(sender: str) -> set[str]:
    """The words a sender's name can be spelled from: the display name, if
    there is one, and the address before its "@". Never the domain, which is
    the company's: "Acme" is not the sender."""

    words: set[str] = set()
    for part in sender.replace("<", " ").replace(">", " ").split():
        local = part.split("@", 1)[0]
        words.update(_WORD.findall(local.lower()))
    return words


def is_the_sender(owner_mention: str, sender: str | None) -> bool:
    """Whether the mention and the sender are spelled from the same words,
    one taking in all of the other's.

    The issue rules the sender out, and the prompt asks for it, but a sender
    who signs "Thanks, Avi" has written the name, so grounding keeps it. Both
    ways: "Avi" for avi.katz@, and "Lisa Grant" for a sender the record knows
    only as "Lisa". A heuristic: an address that does not spell the name
    ("ak@" for Avraham) is missed, and a mention that shares only part of the
    sender ("Avi Levi" for avi.katz@) is someone else.
    """

    if not sender:
        return False
    mention = set(_WORD.findall(owner_mention.lower()))
    words = _sender_words(sender)
    return bool(mention) and bool(words) and (mention <= words or words <= mention)


def what_to_look_up(
    owner_mention: str | None,
    owner_is_recipient: bool,
    *,
    sender: str | None,
    recipients: tuple[str, ...],
) -> str | None:
    """What to ask the directory for: an address, a name, or None for nothing.

    The recipient comes first, as when `ExtractedFields` is parsed: given both
    answers, the name is not looked at (the model drops it there already).
    The recipient is looked up by the address in the email record, and only
    when there is one: which of several was asked is a guess. A name is
    looked up unless it is the sender's own. Logged when an answer is
    dropped, never with the name or the address.
    """

    if owner_is_recipient:
        if len(recipients) == 1:
            return recipients[0]
        logger.info(
            "owner: the task is the recipient's, and the item has %d recipients; no owner sent",
            len(recipients),
        )
        return None
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
