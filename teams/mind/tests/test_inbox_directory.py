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
import yaml
from pydantic import ValidationError

from mind.config import InboxDirectorySettings
from mind.contracts import AssigneeType
from mind.integrations.inbox_directory import DirectoryCandidate, InboxDirectory


@pytest.fixture(autouse=True)
def _no_inbox_directory_env(monkeypatch):
    """Whatever MIND_INBOX_DIRECTORY__* the shell exports must not reach a test."""

    for name in [n for n in os.environ if n.startswith("MIND_INBOX_DIRECTORY__")]:
        monkeypatch.delenv(name)
    # And the chart's files: unset, the settings look at the chart's own mount
    # paths, which a developer's machine does not have.
    monkeypatch.delenv("CONFIG_FILE_PATH", raising=False)
    monkeypatch.delenv("SECRETS_FILE_PATH", raising=False)


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

    assert str(settings.base_url) == "http://inbox.test/"
    assert settings.actor_id == actor_id
    assert settings.cache_ttl_seconds == 60


def test_settings_reject_an_actor_that_is_not_a_uuid():
    with pytest.raises(ValidationError):
        InboxDirectorySettings(_env_file=None, actor_id="not-a-uuid")


@pytest.mark.parametrize(
    "address",
    ["inbox:8080", "inbox.internal", "ftp://inbox.test", "http://inbox:port"],
    ids=["no-scheme-with-port", "no-scheme", "not-http", "port-not-a-number"],
)
def test_an_address_that_is_not_an_http_url_stops_the_settings_from_loading(address):
    with pytest.raises(ValidationError, match="base_url"):
        InboxDirectorySettings(_env_file=None, base_url=address)


def test_an_exported_but_empty_address_or_actor_means_not_set(monkeypatch):
    monkeypatch.setenv("MIND_INBOX_DIRECTORY__BASE_URL", "  ")
    monkeypatch.setenv("MIND_INBOX_DIRECTORY__ACTOR_ID", "")

    settings = InboxDirectorySettings(_env_file=None)

    assert settings.base_url is None
    assert settings.actor_id is None
    assert not InboxDirectory(settings, client=_unreachable_client()).configured


def test_a_min_score_that_would_drop_a_lone_first_name_stops_the_settings_loading():
    with pytest.raises(ValidationError, match="min_score"):
        InboxDirectorySettings(_env_file=None, min_score=0.7)
    with pytest.raises(ValidationError, match="min_score"):
        InboxDirectorySettings(_env_file=None, min_score=0.9)

    assert InboxDirectorySettings(_env_file=None, min_score=0.69).min_score == 0.69


def _chart_files(tmp_path, monkeypatch, config=None, secrets=None):
    """Write config.yaml / secrets.yaml like the chart mounts them, and point at them."""

    config_path, secrets_path = tmp_path / "config.yaml", tmp_path / "secrets.yaml"
    if config is not None:
        config_path.write_text(yaml.dump(config))
    if secrets is not None:
        secrets_path.write_text(yaml.dump(secrets))
    monkeypatch.setenv("CONFIG_FILE_PATH", str(config_path))
    monkeypatch.setenv("SECRETS_FILE_PATH", str(secrets_path))


def test_the_address_comes_from_config_yaml_and_the_actor_from_secrets_yaml(
    tmp_path, monkeypatch
):
    actor = uuid4()
    _chart_files(
        tmp_path,
        monkeypatch,
        config={"inbox": {"url": "http://inbox.chart"}},
        secrets={"inbox": {"actor_id": str(actor)}},
    )

    settings = InboxDirectorySettings(_env_file=None)

    assert str(settings.base_url) == "http://inbox.chart/"
    assert settings.actor_id == actor
    assert InboxDirectory(settings, client=_unreachable_client()).configured


def test_the_chart_files_win_over_env_and_env_fills_what_they_leave_out(tmp_path, monkeypatch):
    env_actor = uuid4()
    _chart_files(tmp_path, monkeypatch, config={"inbox": {"url": "http://inbox.chart"}})
    monkeypatch.setenv("MIND_INBOX_DIRECTORY__BASE_URL", "http://inbox.env")
    monkeypatch.setenv("MIND_INBOX_DIRECTORY__ACTOR_ID", str(env_actor))

    settings = InboxDirectorySettings(_env_file=None)

    assert str(settings.base_url) == "http://inbox.chart/"  # the file wins
    assert settings.actor_id == env_actor  # the file has none, so the env fills in


def test_missing_chart_files_mean_not_set_and_do_not_stop_anything(tmp_path, monkeypatch):
    _chart_files(tmp_path, monkeypatch)  # points at files that do not exist

    settings = InboxDirectorySettings(_env_file=None)

    assert settings.base_url is None
    assert settings.actor_id is None


def test_an_inbox_section_with_nothing_in_it_means_not_set(tmp_path, monkeypatch):
    _chart_files(
        tmp_path,
        monkeypatch,
        config={"inbox": {"url": None}},
        secrets={"inbox": None},
    )

    settings = InboxDirectorySettings(_env_file=None)

    assert settings.base_url is None
    assert settings.actor_id is None


def test_an_address_in_config_yaml_that_is_not_a_url_stops_the_settings_from_loading(
    tmp_path, monkeypatch
):
    _chart_files(tmp_path, monkeypatch, config={"inbox": {"url": "inbox:8080"}})

    with pytest.raises(ValidationError, match="base_url"):
        InboxDirectorySettings(_env_file=None)


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
    message = caplog.records[0].getMessage()
    assert "inbox.url" in message and "config.yaml" in message
    assert "inbox.actor_id" in message and "secrets.yaml" in message


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
    assert "inbox.actor_id" in records[0].getMessage()
    assert "secrets.yaml" in records[0].getMessage()


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
    assert len(requests) == 2  # the second page is cut off while it arrives, at 20s of 15s
    assert "did not finish within 15" in caplog.text
    assert directory._cache == {}


class _TrickleStream(httpx.SyncByteStream):
    """A body that arrives in pieces, each one taking ten seconds."""

    def __init__(self, clock, pieces):
        self._clock = clock
        self._pieces = pieces

    def __iter__(self):
        for piece in self._pieces:
            self._clock.now += 10
            yield piece


def test_one_slow_page_is_cut_off_while_it_arrives_not_only_between_pages(caplog):
    clock, requests = _Clock(), []

    def handler(request):
        requests.append(request)
        pieces = [b'{"content": [', b'], "total_pages": 1}']
        return httpx.Response(200, stream=_TrickleStream(clock, pieces))

    directory = _directory(handler, clock=clock, max_fetch_seconds=15)
    with caplog.at_level(logging.WARNING, logger="mind.integrations"):
        result = directory.find_candidates(uuid4(), "Dana")

    assert result == []
    assert len(requests) == 1  # the very first page already ran past the budget
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
    tenant = uuid4()
    someone_else = _user(tenant, display_name="Yossi Katz")

    def handler(request):
        rows = [someone_else] if request.url.path == "/api/v1/users" else []
        return httpx.Response(200, json=_page(rows))

    result, records = _find_and_log(handler, caplog, tenant)

    assert result == []  # nobody called Dana, which is not a problem
    assert records == []


def test_a_tenant_for_which_inbox_gives_nothing_at_all_is_logged_once(caplog):
    # With Inbox's tenantId filter a wrong tenant id is an empty answer, not rows the
    # filter here drops, so nothing else would say so.
    tenant, clock, requests = uuid4(), _Clock(), []
    directory = _cached_directory(clock, requests, cache_ttl_seconds=60)

    with caplog.at_level(logging.WARNING, logger="mind.integrations"):
        directory.find_candidates(tenant, "Dana")
        clock.now += 61  # the lists are asked for again
        directory.find_candidates(tenant, "Dana")

    assert len(requests) == 4
    assert len(caplog.records) == 1
    assert "no users and no teams" in caplog.records[0].getMessage()


def test_the_nothing_at_all_warning_comes_back_after_people_do(caplog):
    tenant, clock, requests = uuid4(), _Clock(), []
    state = {}
    directory = _cached_directory(clock, requests, state, cache_ttl_seconds=60)

    with caplog.at_level(logging.WARNING, logger="mind.integrations"):
        directory.find_candidates(tenant, "Dana")
        state["users"] = [_user(tenant)]
        clock.now += 61
        directory.find_candidates(tenant, "Dana")
        state["users"] = []
        clock.now += 61
        directory.find_candidates(tenant, "Dana")

    assert len(caplog.records) == 2


def test_rows_that_come_back_but_are_all_inactive_get_one_explanation_not_two(caplog):
    tenant = uuid4()

    def handler(request):
        rows = [_user(tenant, status="SUSPENDED")] if request.url.path == "/api/v1/users" else []
        return httpx.Response(200, json=_page(rows))

    result, records = _find_and_log(handler, caplog, tenant)

    assert result == []
    assert len(records) == 1
    assert "none are active" in records[0].getMessage()


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


# --- Finding candidates, end to end (matching) --------------------------------------
# Inbox's lists go in, DirectoryCandidates come out. How a name is scored is in
# test_name_matching.py; here it is what find_candidates does with the scores.


def _inbox(users=(), teams=(), **settings):
    """A directory whose Inbox holds exactly these users and teams."""

    def handler(request):
        rows = users if request.url.path == "/api/v1/users" else teams
        return httpx.Response(200, json=_page(list(rows)))

    return _directory(handler, **settings)


def _found(candidates):
    return [(c.assignee_type, str(c.id), round(c.score, 2)) for c in candidates]


def test_users_and_teams_come_back_best_first_and_ties_in_a_fixed_order():
    tenant = uuid4()
    cohen = _user(tenant, display_name="Dana Cohen")
    levi = _user(tenant, display_name="Dana Levi")
    yossi = _user(tenant, display_name="Yossi Katz")
    team = _team(tenant, name="Dana")

    found = _inbox([cohen, levi, yossi], [team]).find_candidates(tenant, "Dana")

    tied = sorted([cohen["id"], levi["id"]])
    assert _found(found) == [
        (AssigneeType.TEAM, team["id"], 1.0),
        (AssigneeType.USER, tied[0], 0.7),
        (AssigneeType.USER, tied[1], 0.7),
    ]


def test_only_active_people_and_teams_of_the_given_tenant_come_back():
    tenant, other = uuid4(), uuid4()
    wanted = _user(tenant, display_name="Dana Cohen")
    users = [
        wanted,
        _user(tenant, display_name="Dana Cohen", status="SUSPENDED"),
        _user(other, display_name="Dana Cohen"),
    ]
    teams = [_team(tenant, name="Dana Cohen", status="ARCHIVED"), _team(other, name="Dana Cohen")]

    found = _inbox(users, teams).find_candidates(tenant, "Dana Cohen")

    assert _found(found) == [(AssigneeType.USER, wanted["id"], 1.0)]


def test_a_user_known_only_by_first_and_last_name_is_found_by_the_full_name():
    tenant = uuid4()
    user = _user(tenant, display_name=None, first_name="Dana", last_name="Cohen")

    found = _inbox([user]).find_candidates(tenant, "Dana Cohen")

    assert _found(found) == [(AssigneeType.USER, user["id"], 1.0)]


def test_a_first_name_alone_is_a_partial_match_for_someone_with_a_last_name():
    tenant = uuid4()
    user = _user(tenant, display_name=None, first_name="Dana", last_name="Cohen")

    found = _inbox([user]).find_candidates(tenant, "Dana")

    assert _found(found) == [(AssigneeType.USER, user["id"], 0.7)]


def test_two_people_named_dana_tie_even_when_one_holds_the_username_dana():
    # Review of PR 926: a one-word username must not make her the clear winner.
    tenant = uuid4()
    cohen = _user(tenant, display_name="Dana Cohen", username="dana")
    levi = _user(tenant, display_name="Dana Levi", username="dana.levi")

    found = _inbox([cohen, levi]).find_candidates(tenant, "Dana")

    assert [round(c.score, 2) for c in found] == [0.7, 0.7]
    assert {str(c.id) for c in found} == {cohen["id"], levi["id"]}


def test_a_close_one_word_username_does_not_beat_the_real_name():
    tenant = uuid4()
    fuss = _user(tenant, display_name="Michal Fuss", username="michalf")

    found = _inbox([fuss]).find_candidates(tenant, "Michal")

    assert _found(found) == [(AssigneeType.USER, fuss["id"], 0.7)]


def test_a_mention_that_is_an_address_finds_the_user_with_that_email_ignoring_case():
    tenant = uuid4()
    cohen = _user(tenant, email="Dana.Cohen@Acme.com")
    levi = _user(tenant, display_name="Dana Levi", email="dana.levi@acme.com")

    found = _inbox([cohen, levi]).find_candidates(tenant, " dana.cohen@acme.COM ")

    assert _found(found) == [(AssigneeType.USER, cohen["id"], 1.0)]


def test_an_address_with_no_match_does_not_fall_back_to_the_name_in_it():
    tenant = uuid4()
    cohen = _user(tenant, display_name="Dana Cohen", email="dana.cohen@acme.com")

    found = _inbox([cohen]).find_candidates(tenant, "dana.cohen@gmail.com")

    assert found == []


def test_two_users_whose_emails_differ_only_in_case_are_both_returned_as_a_tie():
    tenant = uuid4()
    first = _user(tenant, email="dana.cohen@acme.com")
    second = _user(tenant, email="Dana.Cohen@acme.com")

    found = _inbox([first, second]).find_candidates(tenant, "dana.cohen@acme.com")

    assert [c.score for c in found] == [1.0, 1.0]
    assert [str(c.id) for c in found] == sorted([first["id"], second["id"]])


def test_a_user_with_no_email_and_a_team_never_match_an_address():
    tenant = uuid4()
    no_email = _user(tenant, display_name="dana.cohen@acme.com")  # not an email field
    team = _team(tenant, name="dana.cohen@acme.com")

    found = _inbox([no_email], [team]).find_candidates(tenant, "dana.cohen@acme.com")

    assert found == []


def test_a_second_lookup_of_an_address_makes_no_http_request():
    tenant, clock, requests = uuid4(), _Clock(), []
    cohen = _user(tenant, email="dana.cohen@acme.com")
    directory = _cached_directory(clock, requests, {"users": [cohen]}, cache_ttl_seconds=300)

    first = directory.find_candidates(tenant, "dana.cohen@acme.com")
    second = directory.find_candidates(tenant, "Dana.Cohen@ACME.com")

    assert _found(first) == _found(second) == [(AssigneeType.USER, cohen["id"], 1.0)]
    assert len(requests) == 2  # one read of users and one of teams, for both lookups


def test_an_address_lookup_gives_nothing_and_does_not_raise_when_inbox_is_down(caplog):
    def handler(request):
        return httpx.Response(503, text="down")

    with caplog.at_level(logging.WARNING, logger="mind.integrations"):
        found = _directory(handler).find_candidates(uuid4(), "dana.cohen@acme.com")

    assert found == []
    assert len(caplog.records) == 1
    assert "answered 503" in caplog.records[0].getMessage()


def test_the_tenant_is_sent_to_inbox_as_a_filter_and_rows_are_still_filtered_here():
    tenant, other = uuid4(), uuid4()
    requests = []
    wanted = _user(tenant, display_name="Dana Cohen")
    stranger = _user(other, display_name="Dana Cohen")  # an Inbox that ignores the filter

    def handler(request):
        requests.append(request)
        rows = [wanted, stranger] if request.url.path == "/api/v1/users" else []
        return httpx.Response(200, json=_page(rows))

    found = _directory(handler).find_candidates(tenant, "Dana Cohen")

    assert [r.url.params["tenantId"] for r in requests] == [str(tenant), str(tenant)]
    assert _found(found) == [(AssigneeType.USER, wanted["id"], 1.0)]


def test_a_user_is_found_by_username():
    tenant = uuid4()
    user = _user(tenant, display_name="DC", username="dana.cohen")

    found = _inbox([user]).find_candidates(tenant, "dana.cohen")

    assert _found(found) == [(AssigneeType.USER, user["id"], 1.0)]


def test_nobody_by_that_name_gives_an_empty_list():
    tenant = uuid4()

    found = _inbox([_user(tenant)], [_team(tenant)]).find_candidates(tenant, "Yossi")

    assert found == []


def test_a_mention_with_no_words_gives_an_empty_list():
    tenant = uuid4()

    assert _inbox([_user(tenant)]).find_candidates(tenant, "") == []
    assert _inbox([_user(tenant)]).find_candidates(tenant, " . ") == []


def test_min_score_drops_a_weak_match_and_the_default_keeps_it():
    tenant = uuid4()
    user = _user(tenant, display_name="Dana Cohen")  # "Dena" is a typo in a lone first name

    kept = _inbox([user]).find_candidates(tenant, "Dena")
    dropped = _inbox([user], min_score=0.6).find_candidates(tenant, "Dena")

    assert len(kept) == 1
    assert 0.5 < kept[0].score < 0.6
    assert dropped == []


def test_the_none_are_active_warning_is_logged_once_not_on_every_refresh(caplog):
    tenant, clock, requests = uuid4(), _Clock(), []
    state = {"users": [_user(tenant, status="active")]}
    directory = _cached_directory(clock, requests, state, cache_ttl_seconds=60)

    with caplog.at_level(logging.WARNING, logger="mind.integrations"):
        directory.find_candidates(tenant, "Dana")
        clock.now += 61  # the cache is stale: Inbox is asked again
        directory.find_candidates(tenant, "Dana")
        clock.now += 61
        directory.find_candidates(tenant, "Dana")

    assert len(requests) == 6  # three refreshes of two lists
    assert len(caplog.records) == 1


def test_the_warning_comes_back_after_the_people_do(caplog):
    tenant, clock, requests = uuid4(), _Clock(), []
    state = {"users": [_user(tenant, status="active")]}
    directory = _cached_directory(clock, requests, state, cache_ttl_seconds=60)

    with caplog.at_level(logging.WARNING, logger="mind.integrations"):
        directory.find_candidates(tenant, "Dana")
        state["users"] = [_user(tenant)]
        clock.now += 61
        directory.find_candidates(tenant, "Dana")
        state["users"] = [_user(tenant, status="active")]
        clock.now += 61
        directory.find_candidates(tenant, "Dana")

    assert len(caplog.records) == 2


def test_an_answer_larger_than_the_cap_is_a_failure_and_is_not_kept(monkeypatch, caplog):
    tenant = uuid4()
    monkeypatch.setattr("mind.integrations.inbox_directory._MAX_BODY_BYTES", 10)

    def handler(request):
        return httpx.Response(200, json=_page([_user(tenant)]))

    directory = _directory(handler)
    with caplog.at_level(logging.WARNING, logger="mind.integrations"):
        result = directory.find_candidates(tenant, "Dana")

    assert result == []
    assert "is larger than 10 bytes" in caplog.text
    assert directory._cache == {}
