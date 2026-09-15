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

# Used by CI: fails the build if the combined dummy-model top-1 accuracy drifts
# out of the designed band (see "Cheap-baseline scores" in eval/personas/README.md
# — pick-first is ~30%; ~50% would mean the set lost its headroom the way the
# pre-2026-09-06 26-scenario set did). Also runs the time_horizon rule check.
eval-ci:
	python -m eval.check_time_horizon
	python -m mind.evaluation.run --personas eval/personas --min-accuracy 0.20 --max-accuracy 0.50

docker-build:
	docker compose build

docker-up:
	docker compose up
