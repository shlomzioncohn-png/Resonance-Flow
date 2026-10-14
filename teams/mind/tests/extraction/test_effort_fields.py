"""What the effort estimate accepts, and what it refuses (#352).

The same line as the classification fields: the value is optional, so an
answer that cannot be used becomes null and is logged, and never costs a
repair round. The basis travels with the minutes because the confidence that
reaches the contract is derived from it, not written by the model.
"""

import logging

import pytest

from mind.extraction.models import ClassificationFields, EffortBasis


def _minutes(written: object) -> int | None:
    """Read minutes the way a stated estimate would carry them."""

    fields = ClassificationFields(effort_estimate_minutes=written, effort_estimate_basis="STATED")
    return fields.effort_estimate_minutes


def test_no_estimate_is_the_default() -> None:
    fields = ClassificationFields()

    assert fields.effort_estimate_minutes is None
    assert fields.effort_estimate_basis is None


@pytest.mark.parametrize("written", [90, 90.0, "90", "90.0"])
def test_a_whole_number_of_minutes_is_read_however_it_was_written(written: object) -> None:
    assert _minutes(written) == 90


@pytest.mark.parametrize(
    ("written", "rounded"),
    # Halves go up, the way "rounded" reads. Python's round() sends them to
    # the even neighbour instead: 0.5 to 0, which is then rejected, and 2.5 to 2.
    [(45.4, 45), (45.6, 46), ("45.6", 46), (0.5, 1), (2.5, 3)],
)
def test_a_fraction_of_a_minute_is_rounded_away(written: object, rounded: int) -> None:
    """Unlike urgency, where 3.5 sits between two answers and becomes null.

    Half a minute is precision nobody asked for: 45.6 and 46 are the same
    estimate, so rounding changes the spelling of the answer, not the answer.
    """

    assert _minutes(written) == rounded


@pytest.mark.parametrize(
    "unusable",
    [0, -30, 0.4, float("inf"), float("nan"), "1.5 hours", "an hour", True, [30, 60], {"min": 30}],
)
def test_minutes_that_cannot_be_used_become_null_and_are_logged(
    unusable: object, caplog: pytest.LogCaptureFixture
) -> None:
    """Zero is not an estimate, 0.4 rounds to zero, and a unit is not minutes."""

    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        assert _minutes(unusable) is None

    assert "effort_estimate_minutes" in caplog.text


def test_a_month_of_working_time_is_still_an_estimate() -> None:
    assert _minutes(9600) == 9600


@pytest.mark.parametrize("too_long", [9601, 2**31])
def test_minutes_beyond_a_month_become_null_and_are_logged(
    too_long: int, caplog: pytest.LogCaptureFixture
) -> None:
    """The product works in short free windows, so more than a month of working
    time is a model error or a project, not a task. 2**31 would not even fit the
    int32 that effort_estimate_minutes is on the wire."""

    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        assert _minutes(too_long) is None

    assert "effort_estimate_minutes" in caplog.text


@pytest.mark.parametrize("written", ["stated", "Inferred", "  STATED  "])
def test_the_basis_forgives_case_and_spacing(written: str) -> None:
    fields = ClassificationFields(effort_estimate_minutes=30, effort_estimate_basis=written)

    assert fields.effort_estimate_basis in ("STATED", "INFERRED")


def test_an_invented_basis_becomes_null(caplog: pytest.LogCaptureFixture) -> None:
    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        fields = ClassificationFields(effort_estimate_basis="GUESSED")

    assert fields.effort_estimate_basis is None
    assert "GUESSED" in caplog.text


# --- the estimate and its basis travel together ----------------------------


@pytest.mark.parametrize("basis", [None, "GUESSED"])
def test_minutes_without_a_usable_basis_are_kept_as_inferred(
    basis: str | None, caplog: pytest.LogCaptureFixture
) -> None:
    """The weaker reading, rather than a repair round or a lost estimate.

    A repair round would put the whole item at risk over an optional field.
    Dropping the minutes would throw away an answer the model did give.
    INFERRED is the pessimistic assumption — it reaches Inbox as low
    confidence, which is exactly what the issue allows.
    """

    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        fields = ClassificationFields(effort_estimate_minutes=30, effort_estimate_basis=basis)

    assert fields.effort_estimate_minutes == 30
    assert fields.effort_estimate_basis is EffortBasis.INFERRED
    assert "INFERRED" in caplog.text


@pytest.mark.parametrize("minutes", [None, 0, "an hour"])
def test_a_basis_without_usable_minutes_becomes_null(
    minutes: object, caplog: pytest.LogCaptureFixture
) -> None:
    """Nothing was estimated, so there is nothing to be confident about."""

    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        fields = ClassificationFields(effort_estimate_minutes=minutes, effort_estimate_basis="STATED")

    assert fields.effort_estimate_minutes is None
    assert fields.effort_estimate_basis is None
    assert "effort_estimate_basis" in caplog.text


def test_no_estimate_at_all_is_quiet(caplog: pytest.LogCaptureFixture) -> None:
    """Both null is the honest "no signal" answer, not something to report."""

    with caplog.at_level(logging.WARNING, logger="mind.extraction"):
        ClassificationFields(effort_estimate_minutes=None, effort_estimate_basis=None)

    assert caplog.text == ""
