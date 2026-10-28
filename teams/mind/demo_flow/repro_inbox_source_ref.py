"""#359 finding, for Inbox: a second task from the same source item is dropped.

Since #690 one text yields several tasks, so Mind sends one creation request
per task, each with its own correlation_id but all with the source item's
`source_ref`. Inbox's `TaskService.createTask` looks a request up by
(tenant, source_type, source_ref) first (`findExisting`, backed by
`uq_tasks_org_source_type_source_ref`) and returns the task it finds. So
the second request creates nothing and no `task.created` answers it.

This sends two such requests, built and published exactly as Mind's
pipeline does (`to_creation_requested_event`, `ProtobufProducer`), then reads
`task.created` for their correlation_ids:

    python -m demo_flow.repro_inbox_source_ref

against a running demo stack (`python -m demo_flow.run`, which leaves it up),
from teams/mind. Exit code 1 when the bug reproduces: one answer for two
requests. Expected: two task.created, one per correlation_id.
"""

from __future__ import annotations

import logging
import os
import sys
import time
from uuid import uuid4

from demo_flow import common, run, verify
from mind.contracts.base_enums import SourceType
from mind.contracts.task_creation_requested import TaskCreationRequested
from mind.task_creation.event import to_creation_requested_event

logger = logging.getLogger("demo_flow.repro")

TITLES = ("Finish the updated cost sheet", "Send Northwind the price quote")


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
    os.environ.setdefault("CONFIG_FILE_PATH", str(run.CONFIG_FILE))
    from mind.messaging import ProtobufProducer

    # One meeting's source_ref (a Zoom uuid in the demo), shared by its tasks.
    source_ref = f"repro-{uuid4()}"
    sent = {}
    with ProtobufProducer() as producer:
        for title in TITLES:
            correlation_id = uuid4()
            request = TaskCreationRequested(
                tenant_id=common.DEFAULT_TENANT_ID,
                title=title,
                source_type=SourceType.MEETING,
                source_ref=source_ref,
                confidence=0.9,
                has_dependencies=False,
            )
            producer.publish(
                verify.REQUESTED,
                to_creation_requested_event(request, correlation_id),
                key=str(correlation_id),
            )
            sent[str(correlation_id)] = title
            logger.info("sent %s %r source_ref=%s", correlation_id, title, source_ref)

    deadline = time.monotonic() + 30

    def answered_all(observed: verify.Observed) -> bool:
        headers = {header for header, _ in observed.created}
        return all(cid in headers for cid in sent) or time.monotonic() > deadline

    observed = verify.collect(run.BOOTSTRAP_SERVERS, answered_all, timeout_s=40)
    answers = {header: event for header, event in observed.created if header in sent}
    for request_id, title in sent.items():
        event = answers.get(request_id)
        logger.info(
            "%s %r: %s",
            request_id,
            title,
            f"task.created, task {event['id']}" if event else "NO task.created within 30 s",
        )
    if len(answers) == len(sent):
        logger.info("not reproduced: Inbox created one task per request")
        return 0
    logger.error(
        "reproduced: %d request(s) with source_ref %s, %d task(s) created",
        len(sent),
        source_ref,
        len(answers),
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
