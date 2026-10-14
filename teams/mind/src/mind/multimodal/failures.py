"""The failure vocabulary OCR and ASR share: what kind of failure, and what to do about it.

R2's consumer (#349) asked for one handling branch for both pipelines without
multimodal knowing her contract (#478): each pipeline keeps its own reason enum
(OCRFailureReason, ASRFailureReason -- the codes to log and to branch on finely),
and every reason belongs to exactly one FailureCategory, which says how the
caller should treat the raw object:

  DATA         the input itself is the problem (empty, unreadable, nothing in it).
               Retrying gives the same result: drop it and move on (e.g. commit the
               Kafka offset past it).
  ENVIRONMENT  nothing is wrong with the input; the model, a binary or the host is.
               Do not treat the input as processed (do not commit past it); try
               again once the environment is fixed -- "may succeed later", not
               "will", so bound the retries.
  BUG          an unexpected exception in our own code. Retrying fails the same way
               and would block everything behind it, but the input is not at fault
               either: park it (dead-letter) rather than drop it silently.

``retryable`` on a result is ``failure_category is ENVIRONMENT`` -- kept because
OCR callers already read it, but it cannot tell DATA (drop) from BUG (park);
``failure_category`` can.
"""

from __future__ import annotations

from enum import Enum


class FailureCategory(str, Enum):
    DATA = "DATA"
    ENVIRONMENT = "ENVIRONMENT"
    BUG = "BUG"
