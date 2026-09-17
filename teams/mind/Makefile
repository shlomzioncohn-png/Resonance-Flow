.PHONY: install dev test lint fmt run eval schema docker-build docker-up proto

install:
	pip install -e .

dev:
	pip install -e ".[dev]"

proto:
	python -m grpc_tools.protoc --proto_path=proto --python_out=src/mind/messaging proto/*.proto

test: proto
	pytest --cov=src/mind

lint: proto
	ruff check src tests
	mypy src
	python -m mind.evaluation.schema --check

schema:
	python -m mind.evaluation.schema

fmt:
	ruff format src tests

run: proto
	uvicorn mind.api.main:app --reload --port 8000

eval:
	python -m mind.evaluation.run --personas eval/personas

docker-build:
	docker compose build

docker-up:
	docker compose up
