"""#349: long-running process that reads `bridge.raw.landed` and turns each
landed item into text for extraction. Own process, like score_publisher_runner.

The hand-off to extraction (#350) is not wired yet: for now each text ready
for extraction is logged by where it came from and its length, never its
content, which also shows the stage in a demo run.

Usage:
    make run-landed-consumer
    python -m mind.ingestion.landed_consumer_runner
"""

import logging
from typing import Any

from mind.config import get_settings
from mind.contracts import RawObjectEvent
from mind.ingestion.landed_consumer import LandedHandler
from mind.ingestion.minio_reader import minio_reader
from mind.ingestion.seen_revisions import InMemoryRevisionStore
from mind.ingestion.to_text import Texts
from mind.messaging import ProtobufConsumer
from mind.messaging.raw_object_event_pb2 import RawObjectEvent as RawObjectEventMessage
from mind.multimodal import asr

logger = logging.getLogger(__name__)

_GROUP_ID = "mind-ingestion"
_TOPIC = "bridge.raw.landed"
# One message may run several OCR (up to 200 s each) and ASR (up to 240 s
# each) calls; librdkafka's default of 5 minutes between polls would have
# Kafka hand the message on mid-way, over and over. Only a handler stuck this
# long is caught by it: a dead process is noticed by heartbeat, not by this.
_MAX_POLL_INTERVAL_MS = 30 * 60 * 1000


def _log_texts(event: RawObjectEvent, texts: Texts) -> None:
    # No content: Bridge may have classified the item HIGH sensitivity, and
    # logs travel further than the content was meant to.
    for record, text in texts:
        logger.info(
            "landed: %s %s ready for extraction, %d chars",
            event.correlation_id,
            record.object_ref,
            len(text),
        )


def main() -> None:
    logging.basicConfig(level=get_settings().log_level)
    # Load Whisper now, not inside the first audio message's handler (R4, #478).
    asr.warm_up()
    handle = LandedHandler(
        read_object=minio_reader(),
        store=InMemoryRevisionStore(),
        on_texts=_log_texts,
    )

    def _handler(event: Any, msg: object) -> None:
        # Any: run() has already deserialized it into a RawObjectEventMessage.
        # No try/except: a failure that may pass later must propagate, so the
        # offset stays uncommitted and the message comes again after a restart.
        handle(event, msg)

    consumer = ProtobufConsumer(
        group_id=_GROUP_ID,
        topics=[_TOPIC],
        conf_overrides={"max.poll.interval.ms": _MAX_POLL_INTERVAL_MS},
    )
    consumer.run(RawObjectEventMessage, _handler, idle_timeout=None)


if __name__ == "__main__":
    main()
