"""The success/failure envelope every media-to-text pipeline returns (OCR, ASR).

R2's ``bridge.raw.landed`` consumer (#349) calls OCR for ``modality=SCANNED_DOC``
and ASR for ``modality=AUDIO``, and both feed the same extraction pipeline as
text-native sources -- so the seam is one shape, not two (#478). The fields she
branches on live here once: ``success``, a typed ``reason``, the ``error``
message for logs, and ``retryable``. Each pipeline subclasses this with its own
failure-reason enum and its own content fields (pages for OCR, segments for ASR).

The reason names are kept aligned across pipelines where the meaning is the same
(``EMPTY_INPUT``, ``UNREADABLE_FORMAT``, ``INTERNAL_ERROR``), so a consumer can
handle the data / environment / bug split the same way for both.
"""

from __future__ import annotations

from collections.abc import Mapping
from enum import Enum
from typing import ClassVar

from pydantic import BaseModel, computed_field, model_validator

from mind.multimodal.failures import FailureCategory


class MediaTextResult[ReasonT: Enum](BaseModel):
    """Base of OCRResult and ASRResult. Always returned, never raised.

    ``success=False`` means "could not extract" -- ``reason`` says why (a
    classified code to branch on), ``error`` is the human-readable message for
    logs, ``failure_category`` says how to treat the input (DATA: drop it,
    ENVIRONMENT: retry later, BUG: park it -- see failures.py), and
    ``retryable`` is ``failure_category is ENVIRONMENT``. ``reason`` is set
    exactly when ``success`` is False; ``failure_category`` with it.

    A subclass maps every one of its reasons to a category in
    ``FAILURE_CATEGORIES`` (tests check none is left out).
    """

    FAILURE_CATEGORIES: ClassVar[Mapping[Enum, FailureCategory]] = {}

    success: bool
    text: str = ""
    reason: ReasonT | None = None
    error: str | None = None

    @computed_field  # type: ignore[prop-decorator]
    @property
    def failure_category(self) -> FailureCategory | None:
        return None if self.reason is None else self.FAILURE_CATEGORIES[self.reason]

    @computed_field  # type: ignore[prop-decorator]
    @property
    def retryable(self) -> bool:
        return self.failure_category is FailureCategory.ENVIRONMENT

    @model_validator(mode="after")
    def _reason_iff_failure(self) -> MediaTextResult[ReasonT]:
        if self.success and (self.reason is not None or self.error is not None):
            raise ValueError("a successful result carries no reason or error")
        if not self.success and (self.reason is None or not self.error):
            raise ValueError("a failed result needs both a reason and an error message")
        return self
