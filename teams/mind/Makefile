.PHONY: install dev test lint fmt run eval eval-ci schema migrate docker-build docker-up proto

install:
	pip install -e .

dev:
	pip install -e ".[dev]"

proto:
	python -m grpc_tools.protoc --proto_path=proto --python_out=src/mind/messaging proto/*.proto

migrate:
	alembic upgrade head

test: proto
	pytest --cov=src/mind

lint: proto
	ruff check src tests benchmarks
	mypy src benchmarks
	python -m mind.evaluation.schema --check
	python -m mind.evaluation.personas_checksum --check

schema:
	python -m mind.evaluation.schema

fmt:
	ruff format src tests

run: proto
	uvicorn mind.api.main:app --reload --port 8000

eval:
	python -m mind.evaluation.run --personas eval/personas

# Used by CI. Three independent checks:
#  - time_horizon rule (eval/check_time_horizon.py — thin wrapper; the real
#    check is mind.evaluation.personas.correct_answer_deadline_violates_time_horizon,
#    covered by tests/test_personas.py).
#  - DummyModel run: a position-bias guard, NOT a headroom guard. DummyModel
#    returns a constant score, so it never reads deadline/blocked at all — it
#    can only tell you where the correct answer sits in each file, not whether
#    a deadline heuristic is near the ceiling. Band: ~30% pick-first (see
#    "Cheap-baseline scores" in eval/personas/README.md).
#  - SkipBlockedNearestDeadlineModel run: the actual headroom guard, because
#    this model reads deadline and blocked the way a real heuristic would.
#    Matches the "skip blocked, then nearest deadline" baseline in the README
#    table. Gate is 0.68, not the originally-designed 0.63, because the
#    manager_overdue_preread_vs_tomorrow_review rewrite (2026-09-16, "overdue
#    but still the most urgent thing") measures 66.7% here for a reason that
#    isn't cheating: NearestDeadlineModel's score is 0.5 - hours/10_000, so
#    ANY overdue deadline (hours negative) scores above 0.5 and ANY future
#    deadline (hours positive) scores below 0.5, regardless of magnitude --
#    an overdue deadline is mathematically always "nearest" under this
#    formula. No third task can fix that (verified: adding one due same-day
#    still loses to the overdue task, since even 1 second overdue outscores
#    1 minute from now). 0.68 leaves ~1.7 points of margin above the measured
#    66.7% instead of the ~7 points the original 0.70/63% design had; still
#    fails well below the no-headroom 85% baseline the 2026-09-06 rebuild fixed.
#  - LongestDescriptionModel run: a length-bias guard. It scores a task by the
#    length of its description and nothing else, so it can only do well if the
#    set's correct answers are, by construction, its longest descriptions (they
#    carry the explanation of why they are correct). Gate is 0.65: the 30
#    original scenarios already measure 22/30 on their own, so the combined set
#    sits at 22/36 = 61.1% with the six hard scenarios (whose correct answer is
#    never the longest), and a seventh would not fit under it.
eval-ci:
	python -m eval.check_time_horizon
	python -m mind.evaluation.run --personas eval/personas --model dummy --min-accuracy 0.20 --max-accuracy 0.50
	python -m mind.evaluation.run --personas eval/personas --model skip-blocked-nearest-deadline --max-accuracy 0.68
	python -m mind.evaluation.run --personas eval/personas --model longest-description --max-accuracy 0.65

docker-build:
	docker compose build

docker-up:
	docker compose up
