"""Which item, task and stage a recorded call belongs to (src/mind/observability/context.py) -- #482.

The context is set once, where a message is handled, and record_call() reads
it: so what must hold is that it reaches every call made for that message --
in asyncio tasks and worker threads too -- that it does not reach a call made
for anything else, and that only ids, in a fixed shape, ever get in.
"""

import asyncio
import logging
import threading
import uuid

import pytest

from mind.observability.context import (
    STAGES,
    CallContext,
    call_context,
    current_context,
    item_ref,
    message_context,
)
from mind.observability.tracking import CallKind, _reset_for_tests, record_call

TENANT = "550e8400-e29b-41d4-a716-446655440000"
TASK = "4d0c6f2a-8b1e-4c3d-9a7f-1e2b3c4d5e6f"
ITEM = item_ref(TENANT, "slack:C024BE91L/1729000000.000100")


@pytest.fixture(autouse=True)
def _clean_call_records():
    _reset_for_tests()


def recorded() -> CallContext:
    record = record_call("m", 1.0, None, kind=CallKind.SCORING)
    return CallContext(item=record.item, task=record.task, stage=record.stage)


# --- item_ref -------------------------------------------------------------------------


def test_an_item_ref_is_a_short_digest_not_the_source_ref():
    source_ref = "gmail:18c2f0a1b2c3d4e5"
    ref = item_ref(TENANT, source_ref)
    assert len(ref) == 16 and int(ref, 16) >= 0
    assert source_ref not in ref and "gmail" not in ref


def test_the_same_item_gives_the_same_ref_and_another_tenant_another():
    assert item_ref(TENANT, "ref-1") == item_ref(TENANT, "ref-1")
    assert item_ref(TENANT, "ref-1") != item_ref(TENANT, "ref-2")
    assert item_ref(TENANT, "ref-1") != item_ref(str(uuid.uuid4()), "ref-1")


# --- missing context ------------------------------------------------------------------


def test_a_call_outside_any_context_has_none(caplog: pytest.LogCaptureFixture):
    with caplog.at_level(logging.INFO, logger="mind.observability"):
        assert recorded() == CallContext()
    [line] = [m for m in caplog.messages if m.startswith("model_call ")]
    # The line a call made before #482 logged, unchanged.
    assert " stage=" not in line and " item=" not in line and " task=" not in line


# --- setting it ------------------------------------------------------------------------


def test_a_call_inside_a_context_carries_it(caplog: pytest.LogCaptureFixture):
    with (
        caplog.at_level(logging.INFO, logger="mind.observability"),
        call_context(item=ITEM, task=TASK, stage="score"),
    ):
        assert recorded() == CallContext(item=ITEM, task=TASK, stage="score")
    [line] = [m for m in caplog.messages if m.startswith("model_call ")]
    assert line.endswith(f" stage=score item={ITEM} task={TASK}")


def test_a_nested_context_overrides_only_what_it_names_and_is_undone_on_leaving():
    with call_context(item=ITEM, stage="landed"):
        with call_context(stage="extraction"):
            assert recorded() == CallContext(item=ITEM, stage="extraction")
        assert recorded() == CallContext(item=ITEM, stage="landed")
    assert recorded() == CallContext()


def test_the_context_is_undone_when_the_block_raises():
    with pytest.raises(RuntimeError), call_context(item=ITEM, stage="landed"):
        raise RuntimeError("handler failed")
    assert current_context() == CallContext()


def test_a_task_id_is_kept_in_its_canonical_form():
    with call_context(task=TASK.upper()):
        assert recorded().task == TASK


@pytest.mark.parametrize(
    "fields",
    [
        {"item": "gmail:18c2f0a1b2c3d4e5"},  # a raw source_ref, not an item_ref
        {"item": ITEM.upper()},
        {"task": "Send the price quote"},  # content, not an id
        {"stage": "Send the price quote"},
        {"stage": "unknown_stage"},
    ],
)
def test_anything_but_an_id_of_the_right_shape_is_refused(fields: dict[str, str]):
    with pytest.raises(ValueError), call_context(**fields):
        pass
    assert current_context() == CallContext()  # nothing was set


def test_every_stage_the_runners_use_is_known():
    assert {"landed", "to_text", "extraction", "duplicate_check", "task_created", "score"} == STAGES


# --- message_context: never fails a message ------------------------------------------


def test_a_message_context_builds_the_item_from_tenant_and_source_ref():
    with message_context(stage="score", tenant_id=TENANT, source_ref="ref-1", task=TASK):
        assert recorded() == CallContext(item=item_ref(TENANT, "ref-1"), task=TASK, stage="score")


@pytest.mark.parametrize(
    ("tenant_id", "source_ref", "task"),
    [
        ("", "ref-1", TASK),  # proto3 default: no tenant
        (TENANT, "", TASK),  # no source_ref
        (TENANT, "ref-1", ""),  # no task id
        (TENANT, "ref-1", "not-a-uuid"),
    ],
)
def test_a_message_missing_a_field_is_handled_with_less_context(
    tenant_id: str, source_ref: str, task: str
):
    with message_context(stage="score", tenant_id=tenant_id, source_ref=source_ref, task=task):
        context = recorded()
    assert context.stage == "score"
    assert context.item == (item_ref(tenant_id, source_ref) if tenant_id and source_ref else None)
    assert context.task == (TASK if task == TASK else None)


# --- where it reaches -------------------------------------------------------------------


def test_it_reaches_an_asyncio_task_and_a_worker_thread():
    # How the landed runner works: a loop driven inside the handler, and the
    # embedding calls in asyncio.to_thread.
    async def pipeline() -> tuple[CallContext, CallContext]:
        in_task = recorded()
        in_thread = await asyncio.to_thread(recorded)
        return in_task, in_thread

    loop = asyncio.new_event_loop()
    try:
        with call_context(item=ITEM, stage="duplicate_check"):
            in_task, in_thread = loop.run_until_complete(pipeline())
    finally:
        loop.close()
    assert in_task == in_thread == CallContext(item=ITEM, stage="duplicate_check")


def test_it_does_not_reach_another_thread():
    # The publisher's other consumer and the rescore worker are threads of
    # their own: one message's context must not attribute their calls.
    seen: list[CallContext] = []
    with call_context(item=ITEM, task=TASK, stage="score"):
        other = threading.Thread(target=lambda: seen.append(recorded()))
        other.start()
        other.join()
    assert seen == [CallContext()]
