"""The #359 demo checker reads a run's topics the way expected.json means them."""

import uuid
from typing import Any

import pytest
from demo_flow import common
from demo_flow.verify import Observed, evaluate, match_expected, trace

from mind.task_creation.correlation import correlation_id_for

_EXPECTED = common.read_json(common.EXPECTED)
_ITEMS = {name: uuid.uuid4() for name in ("meeting", "slack", "email")}
_MANIFEST = {
    "scenario_monday": "2026-10-19",
    "items": [
        {
            "name": name,
            "correlation_id": str(cid),
            "event": {"objects": [{"object_key": f"{name.upper()}/demo/{cid}.txt"}]},
        }
        for name, cid in _ITEMS.items()
    ],
}
# The scenario's deadlines on the manifest's Monday.
_DUE = {"T1": "2026-10-22", "T2": "2026-10-21", "T3": "2026-10-23"}


def _request(source: str, index: int, title: str, task: str, **fields: Any) -> dict[str, Any]:
    request = {
        "correlationId": str(correlation_id_for(_ITEMS[source], index)),
        "title": title,
        "tier": "TASK_TIER_TACTICAL",
        "urgency": 3,
        "roleContext": "TASK_ROLE_CONTEXT_SALESPERSON",
        "deadline": f"{_DUE[task]}T17:00:00Z",
        "suggestedOwnerUserId": str(uuid.uuid4()),
    }
    request.update(fields)
    return request


def _complete_run() -> Observed:
    """What a run that matches expected.json puts on the topics."""

    requests = [
        _request("meeting", 0, "Send Northwind the price quote", "T1"),
        _request("meeting", 1, "Finish the updated cost sheet", "T2", effortEstimateMinutes=240),
        _request("email", 0, "Return the signed supplier registration form", "T3"),
    ]
    observed = Observed(requested=requests)
    ids = {}
    for request, task in zip(requests, ("T1", "T2", "T3"), strict=True):
        ids[task] = str(uuid.uuid4())
        observed.created.append((request["correlationId"], {"id": ids[task]}))
        observed.scored.append({"taskId": ids[task]})
    observed.dependencies.append(
        {"taskId": ids["T1"], "dependencies": [{"dependsOnTaskId": ids["T2"]}]}
    )
    return observed


_DUPLICATE = "T1 is one task, not 2 (meeting + slack)"


def _merged_log(observed: Observed) -> str:
    """The landed runner's line for the Slack mention merged into T1, as creation_pipeline logs it."""

    t1_id = observed.created[0][1]["id"]
    return (
        f"INFO:mind.ingestion.creation_pipeline:creation: {_ITEMS['slack']} task 0 not sent, "
        f"a title of 34 chars (withheld, item not classified as non-sensitive) "
        f"is a duplicate of task {t1_id} (similarity 0.934)\n"
    )


def _failed(observed: Observed, landed_log: str | None = None) -> dict[str, str]:
    log = _merged_log(observed) if landed_log is None and observed.created else landed_log or ""
    checks = evaluate(observed, _MANIFEST, _EXPECTED, log)
    return {c.criterion: c.detail for c in checks if not c.passed}


@pytest.mark.parametrize(
    ("title", "task"),
    [
        ("Send Northwind the price quote", "T1"),
        ("Send pricing proposal to Northwind", "T1"),
        ("Finish the updated cost sheet", "T2"),
        ("Return signed supplier registration form", "T3"),
        ("Update everyone on the warehouse move", None),
    ],
)
def test_titles_match_the_expected_task_they_name(title, task):
    assert match_expected(title) == task


def test_a_run_that_matches_expected_json_passes_every_criterion():
    assert _failed(_complete_run()) == {}


def test_each_request_is_traced_to_the_item_it_came_from():
    sources = [task.source for task in trace(_complete_run(), _MANIFEST)]

    assert sources == ["meeting", "meeting", "email"]


def test_the_slack_mention_sent_as_a_second_task_fails_the_duplicate_check():
    observed = _complete_run()
    observed.requested.append(_request("slack", 0, "Send pricing proposal to Northwind", "T1"))

    failed = _failed(observed)

    assert failed[_DUPLICATE].startswith("2 request(s)")
    assert "(from slack)" in failed[_DUPLICATE]


def test_one_request_without_the_duplicate_line_is_not_a_merged_duplicate():
    failed = _failed(_complete_run(), landed_log="")

    assert failed[_DUPLICATE] == (
        "not proven, sent from meeting; slack: no duplicate decision for it in the landed "
        "runner's log"
    )


def test_a_mention_whose_extraction_failed_says_so():
    log = (
        f"ERROR:mind.extraction:extraction: giving up on SLACK/demo/{_ITEMS['slack']}.txt "
        "(SLACK) after 0 attempt(s): provider_error - LLMUnavailable: Gemini is unavailable (503)\n"
    )

    detail = _failed(_complete_run(), landed_log=log)[_DUPLICATE]

    assert detail.startswith("not proven, sent from meeting; slack: its extraction failed: ")
    assert "Gemini is unavailable (503)" in detail


def test_the_duplicate_line_must_name_the_task_that_was_sent():
    observed = _complete_run()
    log = _merged_log(observed).replace(observed.created[0][1]["id"], str(uuid.uuid4()))

    assert _DUPLICATE in _failed(observed, landed_log=log)


def test_a_meeting_that_yields_only_one_task_misses_t2_and_the_dependency():
    observed = _complete_run()
    observed.requested = [r for r in observed.requested if "cost sheet" not in r["title"]]

    failed = _failed(observed)

    assert "missing T2" in failed["three tasks come out, the expected ones"]
    assert "T1 depends on T2, on task.dependencies.proposed" in failed


def test_missing_fields_are_named_per_task():
    observed = _complete_run()
    for request in observed.requested:
        del request["suggestedOwnerUserId"]
    observed.requested[1]["effortEstimateMinutes"] = 30

    detail = _failed(observed)[
        "tasks carry tier, urgency, role_context, owner, effort and deadline where expected"
    ]

    assert "T1: no suggested owner (expected Dana)" in detail
    assert "T2: no suggested owner (expected Omer), effort 30 min (expected 180-300)" in detail


def test_a_task_inbox_never_created_fails_and_is_not_scored():
    observed = _complete_run()
    observed.created.pop()

    failed = _failed(observed)

    assert "T3" in failed["Inbox created every task Mind requested"]


def test_an_empty_run_fails_every_criterion():
    failed = _failed(Observed())

    assert len(failed) == len(evaluate(Observed(), _MANIFEST, _EXPECTED))
