from datetime import datetime

import pytest

from mind.evaluation.baseline_models import LongestDescriptionModel
from mind.evaluation.interfaces import ScoreContext, Task

CONTEXT = ScoreContext(current_datetime=datetime.fromisoformat("2026-10-01T09:00:00"))


def _task(description: str | None, **overrides) -> Task:
    fields = {
        "task_id": "task_a",
        "title": "A task",
        "description": description,
        "effort_estimate": 10,
        "tier": "operational",
        "blocked": False,
        **overrides,
    }
    return Task(**fields)


def test_no_description_scores_as_the_shortest_instead_of_crashing():
    # A real task often has none (#174: T1 of the demo run crashed this probe).
    model = LongestDescriptionModel()

    none = model.score(_task(None), CONTEXT).score
    some = model.score(_task("x"), CONTEXT).score

    assert none == 0.0
    assert some > none


def test_a_longer_description_scores_higher():
    model = LongestDescriptionModel()

    short = model.score(_task("short"), CONTEXT).score
    long = model.score(_task("a much longer description " * 10), CONTEXT).score

    assert long > short


@pytest.mark.parametrize("length", [1, 100, 10_000, 1_000_000])
def test_the_score_stays_inside_the_zero_to_one_scale(length):
    score = LongestDescriptionModel().score(_task("x" * length), CONTEXT).score

    assert 0.0 <= score <= 1.0


def test_it_reads_nothing_but_the_length():
    model = LongestDescriptionModel()
    plain = _task("same length text")

    other = _task(
        "same length text",
        deadline=datetime.fromisoformat("2026-10-01T09:30:00"),
        blocked=True,
        tier="strategic",
        title="a completely different title",
    )

    assert model.score(other, CONTEXT).score == model.score(plain, CONTEXT).score
