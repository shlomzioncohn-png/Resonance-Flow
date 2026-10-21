.PHONY: install dev test lint fmt run run-local run-index-consumer run-score-publisher run-landed-consumer eval eval-ci schema migrate migrate-local docker-build docker-up proto

install:
	pip install -e .

dev:
	pip install -e ".[dev]"

# See scripts/gen_proto.py -- single source of truth, also called from
# the Dockerfile and tests/conftest.py.
proto:
	python scripts/gen_proto.py

migrate:
	alembic upgrade head

test: proto
	pytest --cov=src/mind

lint: proto
	ruff check src tests benchmarks demo_flow
	mypy src benchmarks demo_flow
	python -m mind.evaluation.schema --check
	python -m mind.evaluation.personas_checksum --check

schema:
	python -m mind.evaluation.schema

fmt:
	ruff format src tests

run: proto
	uvicorn mind.api.main:app --reload --port 8000

# #481: MIND reads its connection settings only from the files
# $CONFIG_FILE_PATH / $SECRETS_FILE_PATH name, so `run` and `migrate` refuse to
# start without them. These run them against the committed examples
# (placeholders only), with `docker compose up -d vector-store` as the database.
# Target-specific, so they reach the `run`/`migrate` recipes too; a value given
# on the command line wins, e.g. `make run-local SECRETS_FILE_PATH=secrets.local.yaml`.
run-local migrate-local: export CONFIG_FILE_PATH = config.example.yml
run-local migrate-local: export SECRETS_FILE_PATH = secrets.example.yml

run-local: run

migrate-local: migrate

run-index-consumer: proto
	python -m mind.embeddings.index_consumer_runner

run-score-publisher: proto
	python -m mind.scoring.score_publisher_runner

run-landed-consumer: proto
	python -m mind.ingestion.landed_consumer_runner

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
#    sits at 22/42 = 52.4% with the six hard scenarios and the six
#    hierarchy_decides ones (whose correct answer is never the longest in
#    either group).
eval-ci:
	python -m eval.check_time_horizon
	python -m mind.evaluation.run --personas eval/personas --model dummy --min-accuracy 0.20 --max-accuracy 0.50
	python -m mind.evaluation.run --personas eval/personas --model skip-blocked-nearest-deadline --max-accuracy 0.68
	python -m mind.evaluation.run --personas eval/personas --model longest-description --max-accuracy 0.65

# #359 demo flow inputs -- see demo_flow/README.md. The feed needs Bridge's
# local MinIO + Redpanda + schema registry (teams/bridge: docker compose up -d
# minio redpanda schema-registry); add ARGS="--dry-run" to only build and validate.
demo-artifacts:
	python -m demo_flow.build_artifacts

demo-check-media:
	python -m demo_flow.check_media

demo-feed: proto
	python -m demo_flow.feed --order natural $(ARGS)

demo-feed-slack-first: proto
	python -m demo_flow.feed --order slack-first $(ARGS)

docker-build:
	docker compose build

docker-up:
	docker compose up
