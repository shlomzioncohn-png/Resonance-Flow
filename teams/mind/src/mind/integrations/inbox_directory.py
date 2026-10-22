"""Turn a name found in text into Inbox user and team candidates (#131).

Inbox works with ids, not names, so when extraction finds "Dana" as the person
responsible, something has to say which Inbox user that is. Inbox has no search
by name -- only lists of users and teams -- so this client pulls the lists for a
tenant and matches the name here. #132 uses the result to propose an owner.

Two choices worth stating out loud:

* The score is how similar the name is to a name in Inbox, from 0 to 1. It is
  not how sure a model is. Turning a score into the confidence Mind sends is
  #132's job, which is why the result is its own type and not
  `AssigneeCandidate`, whose `confidence` means something else.
* Nothing here raises for an Inbox problem. A caller that gets no candidates
  sends no owner, which is always safe; a crash would block the extraction.

This file holds the contract, the construction, the fetching of Inbox's lists,
what happens when Inbox cannot be reached, and how long a fetched list is
believed. The matching of names itself is in name_matching.py. See README.md.
"""

import json
import logging
import time
from collections import Counter
from collections.abc import Callable
from dataclasses import dataclass
from typing import Self
from uuid import UUID

import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from mind.config import InboxDirectorySettings
from mind.contracts import AssigneeType
from mind.integrations.name_matching import best_score, words

logger = logging.getLogger("mind.integrations")

_USERS_PATH = "/api/v1/users"
_TEAMS_PATH = "/api/v1/teams"
_ACTOR_HEADER = "X-Actor-Id"
_ACTIVE = "ACTIVE"

# Inbox's default page is 20 and its largest is Spring's 2000. 200 keeps a
# tenant of a few hundred people to a couple of calls without asking for the
# most Inbox will give.
_PAGE_SIZE = 200
# A stop so a response that never ends cannot loop forever: 100 pages of 200.
_MAX_PAGES = 100
# Far above a page of 200 rows (about 100 KB) and far below what could hurt: a
# bound on what one answer may put in memory, whatever the time budget allows.
_MAX_BODY_BYTES = 5 * 1024 * 1024


# What we read of Inbox's responses. `extra="ignore"` here, unlike our own
# contracts: these are Inbox's models, which carry many fields we do not use
# and may gain more, and none of that should break a lookup. Inbox answers in
# snake_case (spring.jackson.property-naming-strategy: SNAKE_CASE).
class _TenantRef(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: UUID


class _InboxUser(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: UUID
    display_name: str | None = None
    first_name: str | None = None
    last_name: str | None = None
    username: str | None = None
    status: str
    tenant: _TenantRef


class _InboxTeam(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: UUID
    name: str
    status: str
    tenant: _TenantRef


class _Page[Item: (_InboxUser, _InboxTeam)](BaseModel):
    """One page of Inbox's `PageResponseDto`, of users or of teams."""

    model_config = ConfigDict(extra="ignore")

    content: list[Item]
    total_pages: int = Field(ge=0)


class _GaveUp(Exception):
    """Reading one of Inbox's lists was stopped by one of our own limits.

    A list cut short is not a smaller list: the person the name belongs to may
    be in the part that was never read, so it counts as a failure and is not
    kept.
    """


type _Lists = tuple[tuple[_InboxUser, ...], tuple[_InboxTeam, ...]]


@dataclass(frozen=True)
class _CachedLists:
    """What Inbox gave for one tenant, and when (on the client's clock).

    Tuples, so a caller that sorts or trims what it was handed cannot change
    what the next lookup is given.
    """

    fetched_at: float
    users: tuple[_InboxUser, ...]
    teams: tuple[_InboxTeam, ...]


class DirectoryCandidate(BaseModel):
    """A user or team in Inbox that a name might refer to."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    assignee_type: AssigneeType
    id: UUID
    # Similarity between the name searched for and this candidate's closest
    # name. 1.0 is an exact match once case and spacing are ignored.
    score: float = Field(ge=0.0, le=1.0)


def _user_names(user: _InboxUser) -> list[str | None]:
    """Every name a user may be called by: display name, full name, username.

    The full name is the first and last name together. A first name is never
    offered on its own next to a last name: "Dana" would then be an exact match
    for every Dana, and an exact match is what tells #132 it can stop looking.
    """

    full_name = " ".join(part for part in (user.first_name, user.last_name) if part)
    return [user.display_name, full_name or None, user.username]


def _keep_active[Row: (_InboxUser, _InboxTeam)](
    rows: list[Row], tenant_id: UUID, what: str, warned: set[tuple[UUID, str]]
) -> tuple[Row, ...]:
    """The rows of `tenant_id` that are active.

    Inbox has no filter for this, so it is done here. When Inbox returned rows
    and none survive, that is logged: it is what a wrong status spelling or an
    actor that cannot see the tenant looks like, and without a line here both
    would pass as "nobody has that name". Only counts and statuses are logged,
    never names. Once per tenant and kind in `warned`, not on every refresh of
    the cache; it is logged again if the rows come back and then go.
    """

    kept = tuple(r for r in rows if r.tenant.id == tenant_id and r.status == _ACTIVE)
    key = (tenant_id, what)
    if kept:
        warned.discard(key)
    elif rows and key not in warned:
        warned.add(key)
        in_tenant = [r for r in rows if r.tenant.id == tenant_id]
        logger.warning(
            "Inbox returned %d %s but none are active in this tenant "
            "(%d belong to it, statuses seen: %s)",
            len(rows),
            what,
            len(in_tenant),
            dict(Counter(r.status for r in in_tenant)),
        )
    return kept


class InboxDirectory:
    """A synchronous, read-only client for Inbox's Users and Teams lookup.

    Synchronous like the LLM providers: extraction runs one item at a time
    behind a Kafka consumer, so concurrency belongs to whoever owns that loop.
    """

    def __init__(
        self,
        settings: InboxDirectorySettings | None = None,
        client: httpx.Client | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        """Build a client. Pass `client` in tests to supply a mock transport,
        and `clock` to move time by hand instead of sleeping.

        A missing `base_url` or `actor_id` does not stop construction, unlike
        the LLM providers' missing key: the extraction must go on without an
        owner, so it is logged once here and every lookup returns nothing.
        """

        self._settings = settings if settings is not None else InboxDirectorySettings()

        if not self.configured:
            logger.warning(
                "Inbox directory is not configured (inbox.url in config.yaml and inbox.actor_id "
                "in secrets.yaml; locally MIND_INBOX_DIRECTORY__BASE_URL and __ACTOR_ID also "
                "work): owner lookups will return no candidates"
            )

        # Only a client we created is a client we may close.
        self._owns_client = client is None
        self._client = client or httpx.Client(
            timeout=httpx.Timeout(self._settings.timeout_seconds)
        )

        # Monotonic, not wall-clock: a clock change must not make a list look
        # fresher or staler than it is.
        self._clock = clock
        # One entry per tenant, so it is bounded by the number of tenants and
        # needs no eviction. No lock: extraction runs one item at a time behind
        # a Kafka consumer, and two threads asking for the same tenant at once
        # would only fetch it twice.
        self._cache: dict[UUID, _CachedLists] = {}
        # Tenants (and kinds) whose "none are active" warning has been logged.
        self._warned_empty: set[tuple[UUID, str]] = set()

    @property
    def configured(self) -> bool:
        """Whether there is an Inbox address and an actor to call it as."""

        return self._settings.base_url is not None and self._settings.actor_id is not None

    def find_candidates(self, tenant_id: UUID, name: str) -> list[DirectoryCandidate]:
        """Users and teams of `tenant_id` that `name` might refer to, best first.

        Only active ones, only from that tenant. Empty when nothing matches, or
        when Inbox cannot be asked -- never an exception.
        """

        mention_words = words(name)
        if not mention_words:
            return []

        loaded = self._load(tenant_id)
        if loaded is None:
            return []
        users, teams = loaded

        scored = [
            *((AssigneeType.USER, u.id, best_score(mention_words, _user_names(u))) for u in users),
            *((AssigneeType.TEAM, t.id, best_score(mention_words, [t.name])) for t in teams),
        ]
        candidates = [
            DirectoryCandidate(assignee_type=kind, id=id_, score=score)
            for kind, id_, score in scored
            if score >= self._settings.min_score
        ]
        # Best first; the id breaks a tie so the order is the same on every run.
        candidates.sort(key=lambda c: (-c.score, str(c.id)))
        return candidates

    def _load(self, tenant_id: UUID) -> _Lists | None:
        """Both of Inbox's lists for `tenant_id`, or None if Inbox cannot give them.

        This is where "never raises" is kept. Every way Inbox can fail ends in
        a log line and None, and the log line says which kind of failure it was
        because they are fixed in different places: a refused actor is a
        setting that is wrong on every call, a timeout or a dropped connection
        usually passes by itself.

        A failure on either list means no candidates at all. Half an answer
        could hide the person the name belongs to, and no owner is always a
        safe thing to send. Reading both lists also has one time budget
        (`max_fetch_seconds`), so a slow Inbox cannot hold an extraction for
        longer than that.
        """

        if not self.configured:
            return None  # already said so, once, when the client was built

        cached = self._cache.get(tenant_id)
        ttl = self._settings.cache_ttl_seconds
        if cached is not None and self._clock() - cached.fetched_at < ttl:
            return cached.users, cached.teams

        deadline = self._clock() + self._settings.max_fetch_seconds
        try:
            users = self._active_users(tenant_id, deadline)
            teams = self._active_teams(tenant_id, deadline)
        except httpx.TimeoutException:
            logger.warning(
                "Inbox did not answer within %ss: no owner candidates",
                self._settings.timeout_seconds,
            )
        except httpx.HTTPStatusError as exc:
            status = exc.response.status_code
            if status in (httpx.codes.UNAUTHORIZED, httpx.codes.FORBIDDEN):
                # Every call will fail the same way until someone fixes it.
                logger.error(
                    "Inbox refused the actor (%d): check inbox.actor_id in secrets.yaml "
                    "(locally MIND_INBOX_DIRECTORY__ACTOR_ID) and its role. No owner candidates",
                    status,
                )
            else:
                logger.warning("Inbox answered %d: no owner candidates", status)
        except httpx.HTTPError as exc:
            # Connection refused, DNS failure, a socket dropped mid-read.
            logger.warning(
                "Could not reach Inbox (%s: %s): no owner candidates", type(exc).__name__, exc
            )
        except _GaveUp as exc:
            logger.warning("%s: no owner candidates", exc)
        except (json.JSONDecodeError, UnicodeDecodeError):
            logger.warning("Inbox answered with a body that is not JSON: no owner candidates")
        except ValidationError as exc:
            # Field names only. The error text of a ValidationError quotes the
            # offending record, which here is a person's name, email and phone.
            fields = sorted({".".join(str(p) for p in e["loc"]) for e in exc.errors()})
            logger.warning(
                "Inbox answered in an unexpected shape (fields: %s): no owner candidates",
                ", ".join(fields[:5]),
            )
        else:
            # Only an answer is kept. A failure is not, or Mind would go on
            # without owners for a whole TTL after Inbox came back.
            if ttl > 0:
                self._cache[tenant_id] = _CachedLists(self._clock(), users, teams)
            return users, teams
        return None

    def _active_users(
        self, tenant_id: UUID, deadline: float | None = None
    ) -> tuple[_InboxUser, ...]:
        """Every active user of `tenant_id` that the actor can see."""

        users = self._fetch_all(_USERS_PATH, _Page[_InboxUser], deadline)
        return _keep_active(users, tenant_id, "users", self._warned_empty)

    def _active_teams(
        self, tenant_id: UUID, deadline: float | None = None
    ) -> tuple[_InboxTeam, ...]:
        """Every active team of `tenant_id` that the actor can see."""

        teams = self._fetch_all(_TEAMS_PATH, _Page[_InboxTeam], deadline)
        return _keep_active(teams, tenant_id, "teams", self._warned_empty)

    def _fetch_all[Item: (_InboxUser, _InboxTeam)](
        self, path: str, page_type: type[_Page[Item]], deadline: float | None = None
    ) -> list[Item]:
        """Read every page of one Inbox list.

        Inbox has no search, so the whole list is the only way to find a name.
        Pages are asked for in a fixed order (`sort=id`): without one, a row
        added between two calls could make a page skip or repeat people.

        Errors are not handled here: a failed call raises, and `_load` turns
        each kind into a log line. So does a list that is stopped short, by
        `deadline` (a time on this client's clock) or by `_MAX_PAGES`, as
        `_GaveUp`.
        """

        base_url = self._settings.base_url
        actor_id = self._settings.actor_id
        if base_url is None or actor_id is None:
            return []
        url = str(base_url).rstrip("/") + path

        items: list[Item] = []
        page = 0
        while page < _MAX_PAGES:
            self._check_deadline(path, deadline)
            raw = self._read_body(url, actor_id, page, path, deadline)
            body = page_type.model_validate(json.loads(raw))
            items.extend(body.content)
            page += 1
            if page >= body.total_pages:
                return items
        raise _GaveUp(f"Inbox has more than {_MAX_PAGES} pages of {path}")

    def _read_body(
        self, url: str, actor_id: UUID, page: int, path: str, deadline: float | None
    ) -> bytes:
        """One page of one list, as bytes, read piece by piece.

        Streamed so the deadline can be checked while the body arrives. The
        timeout of the HTTP client is for each wait on its own: a server that
        sends a little every few seconds never trips it, and without a check
        between the pieces a single page could take as long as it liked.
        """

        with self._client.stream(
            "GET",
            url,
            params={"page": page, "size": _PAGE_SIZE, "sort": "id,asc"},
            headers={_ACTOR_HEADER: str(actor_id)},
        ) as response:
            response.raise_for_status()
            pieces: list[bytes] = []
            size = 0
            for piece in response.iter_bytes():
                self._check_deadline(path, deadline)
                size += len(piece)
                if size > _MAX_BODY_BYTES:
                    raise _GaveUp(f"A page of {path} is larger than {_MAX_BODY_BYTES} bytes")
                pieces.append(piece)
        return b"".join(pieces)

    def _check_deadline(self, path: str, deadline: float | None) -> None:
        if deadline is not None and self._clock() > deadline:
            raise _GaveUp(
                f"Reading {path} did not finish within {self._settings.max_fetch_seconds}s"
            )

    def close(self) -> None:
        """Close the HTTP client, if this directory owns it."""

        if self._owns_client:
            self._client.close()

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.close()
