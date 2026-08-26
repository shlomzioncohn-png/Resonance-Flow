.PHONY: install dev test lint fmt run eval docker-build docker-up

install:
	pip install -e .

dev:
	pip install -e ".[dev]"

test:
	pytest --cov=src/mind

lint:
	ruff check src tests
	mypy src

fmt:
	ruff format src tests

run:
	uvicorn mind.api.main:app --reload --port 8000

eval:
	python -m mind.evaluation.run --personas eval/personas

docker-build:
	docker compose build

docker-up:
	docker compose up
