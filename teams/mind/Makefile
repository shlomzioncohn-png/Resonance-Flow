.PHONY: install dev test lint fmt run eval eval-ci schema docker-build docker-up

install:
	pip install -e .

dev:
	pip install -e ".[dev]"

test:
	pytest --cov=src/mind

lint:
	ruff check src tests
	mypy src
	python -m mind.evaluation.schema --check

schema:
	python -m mind.evaluation.schema

fmt:
	ruff format src tests

run:
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
#    table (~63% designed). Failing above 70% means the set drifted back
#    toward the no-headroom 85% baseline the 2026-09-06 rebuild fixed.
eval-ci:
	python -m eval.check_time_horizon
	python -m mind.evaluation.run --personas eval/personas --model dummy --min-accuracy 0.20 --max-accuracy 0.50
	python -m mind.evaluation.run --personas eval/personas --model skip-blocked-nearest-deadline --max-accuracy 0.70

docker-build:
	docker compose build

docker-up:
	docker compose up
