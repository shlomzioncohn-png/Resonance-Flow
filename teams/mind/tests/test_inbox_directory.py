"""
Tests for the Inbox directory client (#131): its contract and construction,
the reading of Inbox's lists, what happens when Inbox cannot answer, and how
long a list is remembered. Matching has its own tests when that step lands.

What is pinned first is what #132 relies on from day one: the candidate type,
the settings that decide whether the client can call Inbox at all, and that a
client that cannot get an answer says "no candidates" instead of failing.
"""

import logging
import os
from uuid import uuid4

import httpx
import pytest
from pydantic import ValidationError

from mind.config import InboxDirectorySettings
from mind.contracts import AssigneeType
from mind.integrations.inbox_directory import DirectoryCandidate, InboxDirectory


@pytest.fixture(autouse=True)
def _no_inbox_directory_env(monkeypatch):
    """Whatever MIND_INBOX_DIRECTORY__* the shell exports must not reach a test."""

    for name in [n for n in os.environ if n.startswith("MIND_INBOX_DIRECTORY__")]:
        monkeypatch.delenv(name)


def _settings(**overrides):
    values = {"base_url": "http://inbox.test", "actor_id": uuid4()}
    values.update(overrides)
    # _env_file=None: a developer's own .env must not leak into the test.
    return InboxDirectorySettings(_env_file=None, **values)


def _unreachable_client():
    """A client whose transport fails the test if anything calls Inbox."""

    def refuse(request):
        raise AssertionError(f"unexpected call to Inbox: {request.url}")

    return httpx.Client(transport=httpx.MockTransport(refuse))


def test_candidate_holds_type_id_and_score():
    user_id = uuid4()

    candidate = DirectoryCandidate(assignee_type=AssigneeType.USER, id=user_id, score=0.8)

    assert candidate.assignee_type is AssigneeType.USER
    assert candidate.id == user_id
    assert candidate.score == 0.8


@pytest.mark.parametrize("score", [-0.01, 1.01])
def test_candidate_rejects_a_score_outside_zero_to_one(score):
    with pytest.raises(ValidationError):
        DirectoryCandidate(assignee_type=AssigneeType.TEAM, id=uuid4(), score=score)


def test_candidate_is_immutable_and_rejects_extra_fields():
    candidate = DirectoryCandidate(assignee_type=AssigneeType.USER, id=uuid4(), score=1.0)

    with pytest.raises(ValidationError):
        candidate.score = 0.1
    with pytest.raises(ValidationError):
        DirectoryCandidate(
            assignee_type=AssigneeType.USER, id=uuid4(), score=1.0, name="Dana"
        )


def test_settings_have_no_default_address_or_actor():
    settings = InboxDirectorySettings(_env_file=None)

    assert settings.base_url is None
    assert settings.actor_id is None


def test_settings_are_read_from_prefixed_env_vars(monkeypatch):
    actor_id = uuid4()
    monkeypatch.setenv("MIND_INBOX_DIRECTORY__BASE_URL", "http://inbox.test")
    monkeypatch.setenv("MIND_INBOX_DIRECTORY__ACTOR_ID", str(actor_id))
    monkeypatch.setenv("MIND_INBOX_DIRECTORY__CACHE_TTL_SECONDS", "60")

    settings = InboxDirectorySettings(_env_file=None)

    assert settings.base_url == "http://inbox.test"
    assert settings.actor_id == actor_id
    assert settings.cache_ttl_seconds == 60


def test_settings_reject_an_actor_that_is_not_a_uuid():
    with pytest.raises(ValidationError):
        InboxDirectorySettings(_env_file=None, actor_id="not-a-uuid")


def test_an_exported_but_empty_address_or_actor_means_not_set(monkeypatch):
    monkeypatch.setenv("MIND_INBOX_DIRECTORY__BASE_URL", "  ")
    monkeypatch.setenv("MIND_INBOX_DIRECTORY__ACTOR_ID", "")

    settings = InboxDirectorySettings(_env_file=None)

    assert settings.base_url is None
    assert settings.actor_id is None
    assert not InboxDirectory(settings, client=_unreachable_client()).configured


def test_configured_needs_both_an_address_and_an_actor():
    assert InboxDirectory(_settings(), client=_unreachable_client()).configured
    assert not InboxDirectory(_settings(base_url=None), client=_unreachable_client()).configured
    assert not InboxDirectory(_settings(actor_id=None), client=_unreachable_client()).configured


def test_unconfigured_client_warns_once_and_finds_nobody(caplog):
    with caplog.at_level(logging.WARNING, logger="mind.integrations"):
        directory = InboxDirectory(_settings(actor_id=None), client=_unreachable_client())
        first = directory.find_candidates(uuid4(), "Dana")
        second = directory.find_candidates(uuid4(), "Dana")

    assert first == []
    assert second == []
    assert len(caplog.records) == 1
    assert "MIND_INBOX_DIRECTORY__ACTOR_ID" in caplog.records[0].getMessage()


def test_configured_client_does_not_warn(caplog):
    with caplog.at_level(logging.WARNING, logger="mind.integrations"):
        InboxDirectory(_settings(), client=_unreachable_client())

    assert caplog.records == []


def test_closing_does_not_close_a_client_it_was_given():
    client = _unreachable_client()

    with InboxDirectory(_settings(), client=client):
        pass

    assert not client.is_closed


# --- Reading Inbox's lists (step 2) -------------------------------------------------
# These call the private _active_users/_active_teams directly: find_candidates
# only uses them once the matching step is built.


def _record(tenant_id, status, **fields):
    """The part a user and a team share as Inbox answers it: snake_case, tenant as an object."""

    return {
        "id": str(uuid4()),
        "status": status,
        "tenant": {"id": str(tenant_id), "name": "Acme", "slug": "acme"},
        **fields,
    }


def _user(tenant_id, *, status="ACTIVE", **fields):
    return _record(tenant_id, status, **{"display_name": "Dana Cohen", **fields})


def _team(tenant_id, *, status="ACTIVE", **fields):
    return _record(tenant_id, status, **{"name": "Sales", **fields})


def _page(content, total_pages=1):
    """Inbox's PageResponseDto."""

    return {"content": content, "page": 0, "size": 200, "total_elements": 0,
            "total_pages": total_pages}


def _directory(handler, clock=None, **settings_overrides):
    client = httpx.Client(transport=httpx.MockTransport(handler))
    extra = {} if clock is None else {"clock": clock}
    return InboxDirectory(_settings(**settings_overrides), client=client, **extra)


def test_reads_every_page_not_only_the_first():
    tenant = uuid4()
    pages = [[_user(tenant)], [_user(tenant), _user(tenant)], [_user(tenant)]]
    requests = []

    def handler(request):
        requests.append(request)
        page = int(request.url.params["page"])
        return httpx.Response(200, json=_page(pages[page], total_pages=len(pages)))

    users = _directory(handler)._active_users(tenant)

    assert len(users) == 4
    assert [r.url.params["page"] for r in requests] == ["0", "1", "2"]


def test_asks_for_a_large_page_in_a_fixed_order():
    tenant = uuid4()
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(200, json=_page([]))

    _directory(handler)._active_users(tenant)

    assert requests[0].url.params["size"] == "200"
    assert requests[0].url.params["sort"] == "id,asc"


def test_sends_the_configured_actor_and_address():
    tenant = uuid4()
    actor = uuid4()
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(200, json=_page([]))

    directory = _directory(handler, base_url="http://inbox.test/", actor_id=actor)
    directory._active_users(tenant)
    directory._active_teams(tenant)

    assert [(r.url.host, r.url.path) for r in requests] == [
        ("inbox.test", "/api/v1/users"),
        ("inbox.test", "/api/v1/teams"),
    ]
    assert all(r.headers["X-Actor-Id"] == str(actor) for r in requests)


def test_keeps_only_active_users_of_the_given_tenant():
    tenant, other_tenant = uuid4(), uuid4()
    wanted = _user(tenant)
    content = [
        wanted,
        _user(tenant, status="SUSPENDED"),
        _user(tenant, status="DISABLED"),
        _user(tenant, status="PENDING"),
        _user(other_tenant),
    ]

    def handler(request):
        return httpx.Response(200, json=_page(content))

    users = _directory(handler)._active_users(tenant)

    assert [str(u.id) for u in users] == [wanted["id"]]


def test_keeps_only_active_teams_of_the_given_tenant():
    tenant, other_tenant = uuid4(), uuid4()
    wanted = _team(tenant)
    content = [wanted, _team(tenant, status="ARCHIVED"), _team(other_tenant)]

    def handler(request):
        return httpx.Response(200, json=_page(content))

    teams = _directory(handler)._active_teams(tenant)

    assert [str(t.id) for t in teams] == [wanted["id"]]


def test_ignores_fields_it_does_not_read_and_accepts_missing_names():
    tenant = uuid4()
    user = _user(tenant, email="dana@acme.test", role="EMPLOYEE", display_name=None)

    def handler(request):
        return httpx.Response(200, json=_page([user]))

    users = _directory(handler)._active_users(tenant)

    assert len(users) == 1
    assert users[0].display_name is None
    assert users[0].first_name is None


def test_an_unconfigured_client_reads_nothing_and_calls_nothing():
    directory = InboxDirectory(_settings(base_url=None), client=_unreachable_client())

    assert directory._active_users(uuid4()) == ()
    assert directory._active_teams(uuid4()) == ()


def test_a_list_longer_than_the_page_limit_is_a_failure_not_a_short_list(monkeypatch, caplog):
    tenant = uuid4()
    requests = []
    monkeypatch.setattr("mind.integrations.inbox_directory._MAX_PAGES", 3)

    def handler(request):
        requests.append(request)
        return httpx.Response(200, json=_page([_user(tenant)], total_pages=1000))

    directory = _directory(handler)
    with caplog.at_level(logging.WARNING, logger="mind.integrations"):
        result = directory.find_candidates(tenant, "Dana")

    assert result == []
    assert len(requests) == 3
    assert "more than 3 pages of /api/v1/users" in caplog.text
    # What was read is not kept as if it were the whole list.
    assert directory._cache == {}


# --- When Inbox cannot answer (step 3) ----------------------------------------------
# find_candidates cannot match yet, so it returns [] even on success. What tells a
# failure from a success here is the log: one line for every failure, none for a
# good answer. Once matching lands, the same cases also assert on the result.


def _find_and_log(handler, caplog, tenant=None):
    """Look "Dana" up in `tenant` (a new one if none is given) and return the log."""

    with caplog.at_level(logging.INFO, logger="mind.integrations"):
        result = _directory(handler).find_candidates(tenant or uuid4(), "Dana")
    return result, caplog.records


def _raising(exc_type):
    def handler(request):
        raise exc_type("boom", request=request)

    return handler


@pytest.mark.parametrize(
    ("exc_type", "expected"),
    [
        (httpx.ReadTimeout, "did not answer"),
        (httpx.ConnectError, "Could not reach Inbox"),
    ],
)
def test_a_timeout_or_a_dropped_connection_gives_no_candidates_and_a_warning(
    exc_type, expected, caplog
):
    result, records = _find_and_log(_raising(exc_type), caplog)

    assert result == []
    assert len(records) == 1
    assert records[0].levelno == logging.WARNING
    assert expected in records[0].getMessage()


@pytest.mark.parametrize("status", [401, 403])
def test_a_refused_actor_gives_no_candidates_and_an_error(status, caplog):
    def handler(request):
        return httpx.Response(status, json={"detail": "no"})

    result, records = _find_and_log(handler, caplog)

    assert result == []
    assert len(records) == 1
    assert records[0].levelno == logging.ERROR
    assert f"refused the actor ({status})" in records[0].getMessage()
    assert "MIND_INBOX_DIRECTORY__ACTOR_ID" in records[0].getMessage()


@pytest.mark.parametrize("status", [404, 500, 503])
def test_any_other_error_status_gives_no_candidates_and_a_warning(status, caplog):
    def handler(request):
        return httpx.Response(status, text="down")

    result, records = _find_and_log(handler, caplog)

    assert result == []
    assert len(records) == 1
    assert records[0].levelno == logging.WARNING
    assert f"answered {status}" in records[0].getMessage()


def test_an_answer_that_is_not_json_gives_no_candidates_and_a_warning(caplog):
    def handler(request):
        return httpx.Response(200, text="<html>maintenance</html>")

    result, records = _find_and_log(handler, caplog)

    assert result == []
    assert len(records) == 1
    assert records[0].levelno == logging.WARNING
    assert "not JSON" in records[0].getMessage()


@pytest.mark.parametrize(
    "body",
    [{"content": "not a list", "total_pages": 1}, {}],
    ids=["wrong-type", "missing-content"],
)
def test_an_answer_in_the_wrong_shape_gives_no_candidates_and_a_warning(body, caplog):
    def handler(request):
        return httpx.Response(200, json=body)

    result, records = _find_and_log(handler, caplog)

    assert result == []
    assert len(records) == 1
    assert records[0].levelno == logging.WARNING
    assert "unexpected shape" in records[0].getMessage()


def test_a_bad_record_is_logged_by_field_name_and_never_by_its_contents(caplog):
    tenant = uuid4()
    person = _user(tenant, status=None, email="dana.secret@acme.test", display_name="Dana Secret")

    def handler(request):
        return httpx.Response(200, json=_page([person]))

    result, records = _find_and_log(handler, caplog)

    assert result == []
    assert len(records) == 1
    assert "status" in records[0].getMessage()
    assert "Dana Secret" not in caplog.text
    assert "dana.secret" not in caplog.text


def test_an_address_that_is_not_a_url_is_logged_as_an_error_and_does_not_raise(caplog):
    def handler(request):
        return httpx.Response(200, json=_page([]))

    # httpx accepts "http://[inbox" without complaint; a port that is not a number it does not.
    directory = _directory(handler, base_url="http://inbox:port")
    with caplog.at_level(logging.INFO, logger="mind.integrations"):
        result = directory.find_candidates(uuid4(), "Dana")

    assert result == []
    assert len(caplog.records) == 1
    assert caplog.records[0].levelno == logging.ERROR
    assert "MIND_INBOX_DIRECTORY__BASE_URL" in caplog.records[0].getMessage()


def test_rows_that_all_fail_the_filter_are_logged_with_counts_not_names(caplog):
    tenant = uuid4()

    def handler(request):
        if request.url.path == "/api/v1/users":
            rows = [_user(tenant, status="active"), _user(tenant, status="active")]
            return httpx.Response(200, json=_page(rows))
        return httpx.Response(200, json=_page([]))

    result, records = _find_and_log(handler, caplog, tenant)

    assert result == []
    assert len(records) == 1
    message = records[0].getMessage()
    assert "returned 2 users but none are active" in message
    assert "'active': 2" in message
    assert "Dana" not in message


def test_rows_of_another_tenant_only_are_logged_too(caplog):
    def handler(request):
        if request.url.path == "/api/v1/users":
            return httpx.Response(200, json=_page([_user(uuid4())]))
        return httpx.Response(200, json=_page([]))

    result, records = _find_and_log(handler, caplog)

    assert result == []
    assert len(records) == 1
    assert "0 belong to it" in records[0].getMessage()


def test_an_inbox_that_is_too_slow_is_given_up_on_within_the_time_budget(caplog):
    tenant, clock, requests = uuid4(), _Clock(), []

    def handler(request):
        requests.append(request)
        clock.now += 10  # every answer takes ten seconds
        return httpx.Response(200, json=_page([_user(tenant)], total_pages=1000))

    directory = _directory(handler, clock=clock, max_fetch_seconds=15)
    with caplog.at_level(logging.WARNING, logger="mind.integrations"):
        result = directory.find_candidates(tenant, "Dana")

    assert result == []
    assert len(requests) == 2  # the third page would start 20s in, past the 15s budget
    assert "did not finish within 15" in caplog.text
    assert directory._cache == {}


def test_a_failure_on_the_teams_list_throws_away_the_users_too(caplog):
    tenant = uuid4()
    tenant_user = _user(tenant)

    def handler(request):
        if request.url.path == "/api/v1/users":
            return httpx.Response(200, json=_page([tenant_user]))
        return httpx.Response(500, text="down")

    result, records = _find_and_log(handler, caplog, tenant)

    assert result == []
    assert len(records) == 1
    assert "answered 500" in records[0].getMessage()


def test_a_good_answer_logs_nothing(caplog):
    def handler(request):
        return httpx.Response(200, json=_page([]))

    result, records = _find_and_log(handler, caplog)

    assert result == []
    assert records == []


# --- Remembering Inbox's lists (step 4) ---------------------------------------------
# A successful lookup is two calls (users, then teams), each one page here, so
# "how many times Inbox was called" is the thing to count.


class _Clock:
    """Time moved by hand: no test sleeps."""

    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        return self.now


def _cached_directory(clock, requests, state=None, **settings):
    """A directory whose Inbox answers from `state`, which a test can change."""

    state = state if state is not None else {}

    def handler(request):
        requests.append(request)
        status = state.get("status", 200)
        if status != 200:
            return httpx.Response(status, text="down")
        users = state.get("users", []) if request.url.path == "/api/v1/users" else []
        return httpx.Response(200, json=_page(users))

    return _directory(handler, clock=clock, **settings)


def test_a_second_lookup_within_the_ttl_makes_no_http_request():
    tenant, clock, requests = uuid4(), _Clock(), []
    directory = _cached_directory(clock, requests, cache_ttl_seconds=300)

    directory.find_candidates(tenant, "Dana")
    assert len(requests) == 2

    clock.now += 299
    directory.find_candidates(tenant, "Yossi")

    assert len(requests) == 2


def test_a_lookup_once_the_ttl_has_passed_asks_inbox_again():
    tenant, clock, requests = uuid4(), _Clock(), []
    directory = _cached_directory(clock, requests, cache_ttl_seconds=300)

    directory.find_candidates(tenant, "Dana")
    clock.now += 300  # exactly the TTL is already too old
    directory.find_candidates(tenant, "Dana")

    assert len(requests) == 4


def test_each_tenant_has_its_own_remembered_lists():
    tenant_a, tenant_b, clock, requests = uuid4(), uuid4(), _Clock(), []
    directory = _cached_directory(clock, requests)

    directory.find_candidates(tenant_a, "Dana")
    directory.find_candidates(tenant_b, "Dana")
    assert len(requests) == 4

    directory.find_candidates(tenant_a, "Dana")
    directory.find_candidates(tenant_b, "Dana")

    assert len(requests) == 4


def test_a_failure_is_not_remembered_so_the_next_lookup_tries_again():
    tenant, clock, requests = uuid4(), _Clock(), []
    state = {"status": 500}
    directory = _cached_directory(clock, requests, state)

    directory.find_candidates(tenant, "Dana")  # users fails, teams is never asked
    assert len(requests) == 1

    state["status"] = 200  # Inbox is back; no time has passed
    directory.find_candidates(tenant, "Dana")

    assert len(requests) == 3


def test_a_ttl_of_zero_turns_the_cache_off():
    tenant, clock, requests = uuid4(), _Clock(), []
    directory = _cached_directory(clock, requests, cache_ttl_seconds=0)

    directory.find_candidates(tenant, "Dana")
    directory.find_candidates(tenant, "Dana")

    assert len(requests) == 4


def test_the_remembered_lists_are_believed_until_the_ttl_and_then_replaced():
    tenant, clock, requests = uuid4(), _Clock(), []
    state = {"users": [_user(tenant)]}
    directory = _cached_directory(clock, requests, state, cache_ttl_seconds=300)

    users, _ = directory._load(tenant)
    assert len(users) == 1

    state["users"] = []  # the person left Inbox, but we have not asked again
    clock.now += 100
    users, _ = directory._load(tenant)
    assert len(users) == 1

    clock.now += 200
    users, _ = directory._load(tenant)
    assert users == ()


def test_what_a_lookup_is_handed_cannot_be_used_to_change_the_remembered_lists():
    tenant, clock, requests = uuid4(), _Clock(), []
    state = {"users": [_user(tenant), _user(tenant)]}
    directory = _cached_directory(clock, requests, state)

    users, teams = directory._load(tenant)

    assert isinstance(users, tuple)
    assert isinstance(teams, tuple)
    with pytest.raises(AttributeError):
        users.pop()
    assert len(directory._load(tenant)[0]) == 2
