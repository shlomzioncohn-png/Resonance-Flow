"""#359: the whole demo flow, one command, every stage in the log.

    python -m demo_flow.run                        # natural order
    python -m demo_flow.run --order slack-first    # Slack before the meeting
    make demo-flow / make demo-flow-slack-first     # the same, where make exists

What it does, each step a STAGE line:

1. **preflight**: Docker answers, the LLM key is in stack/secrets.yaml,
   tesseract and the embedding model's weights are found. Fails here, in a sentence, rather than mid-demo.
2. **stack**: `docker compose down -v`, then `up --wait`
   (stack/docker-compose.yml): an empty broker, registry, MinIO, Inbox and
   vector store on every run, so a run never sees the last one's tasks.
3. **topics, schemas and migrations**: the topics every party reads,
   created up front (a consumer of a topic not yet there stops on the
   error); every schema registered by Relay's scripts, as in the cluster
   (since #899 Mind's producers only look schemas up); and Mind's
   vector-store migrations.
4. **Mind**: the landed runner (`bridge.raw.landed` -> OCR/ASR -> extraction
   -> duplicate check -> `task.creation.requested`, and the `task.created`
   listener) and Mind's API, which runs the score publisher (#920), as two
   processes on this machine, their output here with a prefix and in
   runs/<run-id>/.
5. **feed**: `demo_flow.feed` lands the three items and publishes their
   pointer events, in the order asked for.
6. **result**: reads the topics until everything expected.json predicts
   has come out, or nothing has moved for `--settle` seconds, then
   checks it (`demo_flow.verify`) and prints one PASS/FAIL per criterion.

The stack is left up afterwards for a look around (Redpanda Console on
:8090, MinIO on :9091); `--down` takes it down instead. Mind's processes
always stop with the run. The exit code is 0 only when every criterion
passes.
"""

from __future__ import annotations

import argparse
import logging
import os
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import IO

from demo_flow import common, feed, verify

logger = logging.getLogger("demo_flow.run")

MIND_DIR = common.DEMO_DIR.parent
STACK_DIR = common.DEMO_DIR / "stack"
COMPOSE_FILE = STACK_DIR / "docker-compose.yml"
CONFIG_FILE = STACK_DIR / "config.yaml"
SECRETS_FILE = STACK_DIR / "secrets.yaml"

BOOTSTRAP_SERVERS = "localhost:9092"
SCHEMA_REGISTRY_URL = "http://localhost:8085/apis/ccompat/v6"
INBOX_HEALTH_URL = "http://localhost:8082/actuator/health"
MINIO_ENDPOINT = "http://localhost:9000"
#: Mind's API, which runs the score publisher since #920. Not 8000, so a
#: developer's own API is left alone.
MIND_API_PORT = 8800
MIND_API_HEALTH_URL = f"http://127.0.0.1:{MIND_API_PORT}/health"

#: Every topic a party in the run reads. Inbox's own two are here so its
#: consumers start cleanly; nothing in the demo publishes to them.
TOPICS = (
    "bridge.raw.landed",
    *verify.TOPICS,
    "task.updated",
    "bridge.structured.tasks",
    "task.scheduling.updated",
)

#: Mind's local model weights (models/README.md). The demo loads both models
#: from here, never from the Hugging Face Hub: the dev network blocks its
#: download CDN, and a demo should not depend on it anyway.
MODELS_DIR = MIND_DIR / "models"
EMBEDDING_MODEL_DIR = MODELS_DIR / "bge-small-en-v1.5"

#: Where the Windows installer puts tesseract without adding it to PATH.
_WINDOWS_TESSERACT_DIR = Path(r"C:\Program Files\Tesseract-OCR")


class DemoError(Exception):
    """A step that cannot go on, with the sentence that says why."""


def stage(number: int, name: str) -> None:
    logger.info("=" * 14 + " STAGE %d/6 %s " + "=" * 14, number, name)


def _run(command: Sequence[str | Path], *, env: dict[str, str] | None = None, what: str) -> str:
    result = subprocess.run(
        [str(part) for part in command],
        cwd=MIND_DIR,
        env=env,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if result.returncode != 0:
        raise DemoError(
            f"{what} failed (exit {result.returncode}):\n{result.stdout}{result.stderr}"
        )
    return result.stdout


def _compose(*args: str) -> list[str | Path]:
    return ["docker", "compose", "-f", COMPOSE_FILE, *args]


# ---------------------------------------------------------------- preflight


def mind_env(run_id: str) -> dict[str, str]:
    """The environment Mind's processes, the migrations and the checks run with.

    Kafka, the vector store and the LLM come from stack/config.yaml and
    secrets.yaml (#481: the only source); MinIO and the group id from here.
    """

    env = dict(os.environ)
    env.update(
        {
            "CONFIG_FILE_PATH": str(CONFIG_FILE),
            "SECRETS_FILE_PATH": str(SECRETS_FILE),
            "MIND_OBJECT_STORAGE__ENDPOINT": MINIO_ENDPOINT,
            "MIND_OBJECT_STORAGE__BUCKET": "bridge-raw",
            "MIND_OBJECT_STORAGE__ACCESS_KEY": "minioadmin",
            "MIND_OBJECT_STORAGE__SECRET_KEY": "minioadmin",
            # A group of its own per run (ingestion/README.md, "Running it").
            "MIND_LANDED_CONSUMER__GROUP_ID": f"demo-landed-{run_id}",
            "MODEL_CACHE_DIR": str(MODELS_DIR),
            "PYTHONUNBUFFERED": "1",
            "PYTHONIOENCODING": "utf-8",
        }
    )
    if shutil.which("tesseract", path=env.get("PATH")) is None and _WINDOWS_TESSERACT_DIR.is_dir():
        env["PATH"] = f"{_WINDOWS_TESSERACT_DIR}{os.pathsep}{env.get('PATH', '')}"
    return env


def preflight(env: dict[str, str]) -> None:
    if shutil.which("docker") is None:
        raise DemoError("docker is not installed or not on PATH")
    _run(["docker", "version"], what="docker (is Docker Desktop running?)")

    if not SECRETS_FILE.is_file():
        raise DemoError(
            f"no {SECRETS_FILE.relative_to(MIND_DIR)}: copy secrets.example.yaml next to it "
            "and put the LLM provider's api_key in it"
        )
    # The provider settings read the same two files Mind's processes will.
    os.environ["CONFIG_FILE_PATH"] = env["CONFIG_FILE_PATH"]
    os.environ["SECRETS_FILE_PATH"] = env["SECRETS_FILE_PATH"]
    from mind.extraction.providers import settings as llm

    provider = llm.configured_provider_name()
    if provider == "gemini":
        configured = llm.load_gemini_settings().configured
    elif provider == "deepseek":
        configured = llm.load_deepseek_settings().configured
    else:
        raise DemoError(f"llm.provider is {provider!r}; the demo knows gemini and deepseek")
    if not configured:
        raise DemoError(f"llm.{provider}.api_key is empty in {SECRETS_FILE.name}")
    logger.info("LLM provider: %s (key set)", provider)

    tesseract = shutil.which("tesseract", path=env.get("PATH"))
    if tesseract is None:
        raise DemoError(
            "tesseract is not on PATH: the scanned form cannot be OCR'd (models/README.md)"
        )
    logger.info("tesseract: %s", tesseract)

    weights = [EMBEDDING_MODEL_DIR / "model.safetensors", EMBEDDING_MODEL_DIR / "pytorch_model.bin"]
    if not any(path.is_file() for path in weights):
        raise DemoError(
            f"no embedding model in {EMBEDDING_MODEL_DIR.relative_to(MIND_DIR)} (no model.safetensors): "
            "duplicate detection cannot run. Put BAAI/bge-small-en-v1.5 there, see models/README.md"
        )
    logger.info("embedding model: %s", EMBEDDING_MODEL_DIR.relative_to(MIND_DIR))


# -------------------------------------------------------------------- stack


def start_stack() -> None:
    logger.info("taking down the previous demo stack and its data, if any")
    _run(_compose("down", "-v", "--remove-orphans"), what="docker compose down")
    logger.info("starting the stack (the first run also builds Inbox's image: a few minutes)")
    _run(_compose("up", "-d", "--build", "--wait"), what="docker compose up")
    _wait_for_inbox()


def _wait_for_inbox(timeout_s: float = 240.0) -> None:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(INBOX_HEALTH_URL, timeout=2) as response:
                if b'"UP"' in response.read():
                    logger.info("Inbox is up (%s)", INBOX_HEALTH_URL)
                    return
        except OSError:
            pass
        time.sleep(2)
    logs = _run(_compose("logs", "--tail", "40", "inbox"), what="docker compose logs")
    raise DemoError(f"Inbox did not report UP within {timeout_s:.0f} s. Its last lines:\n{logs}")


def register_schemas() -> None:
    """Every subject in Relay's list, registered by Relay's scripts (stack/seed-schemas.sh)."""

    output = _run(_compose("run", "--rm", "schema-seed"), what="registering the schemas")
    registered = [line for line in output.splitlines() if line.startswith("Registered ")]
    logger.info(
        "schemas registered: %d subjects (teams/relay/ci/schema-subjects.tsv)", len(registered)
    )


def create_topics() -> None:
    # Inbox is up by now and has created the topics it consumes, so some are
    # already there: rpk then exits 1 with TOPIC_ALREADY_EXISTS on their lines.
    result = subprocess.run(
        [
            str(part)
            for part in _compose("exec", "-T", "redpanda", "rpk", "topic", "create", *TOPICS)
        ],
        cwd=MIND_DIR,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    failed = [
        line
        for line in result.stdout.splitlines()[1:]
        if line.split() and line.split()[-1] != "OK" and "TOPIC_ALREADY_EXISTS" not in line
    ]
    if failed or not result.stdout.strip():
        raise DemoError(f"creating the topics failed:\n{result.stdout}{result.stderr}")
    logger.info("topics: %s", ", ".join(TOPICS))


def migrate(env: dict[str, str]) -> None:
    _run(
        [sys.executable, "-m", "alembic", "upgrade", "head"],
        env=env,
        what="vector-store migrations",
    )
    logger.info("vector store migrated (vector_store in %s)", CONFIG_FILE.name)


# --------------------------------------------------------------------- Mind


@dataclass
class MindProcess:
    """One of Mind's processes, its output echoed with a prefix and kept in a file."""

    name: str
    module: str
    args: tuple[str, ...] = ()
    process: subprocess.Popen[str] | None = None
    last_output: float = field(default_factory=time.monotonic)
    restarts: int = 0

    def start(self, env: dict[str, str], log_dir: Path) -> None:
        self.process = subprocess.Popen(
            [sys.executable, "-m", self.module, *self.args],
            cwd=MIND_DIR,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        # Appended: a restart's output goes on in the same file.
        log_file = (log_dir / f"{self.name}.log").open("a", encoding="utf-8")
        threading.Thread(
            target=self._pump, args=(self.process.stdout, log_file), name=self.name, daemon=True
        ).start()
        logger.info(
            "started %s (pid %d): python -m %s",
            self.name,
            self.process.pid,
            " ".join([self.module, *self.args]),
        )

    def _pump(self, stream: IO[str] | None, log_file: IO[str]) -> None:
        assert stream is not None
        with log_file:
            for line in stream:
                self.last_output = time.monotonic()
                log_file.write(line)
                log_file.flush()
                print(f"[{self.name}] {line}", end="", flush=True)

    def exit_code(self) -> int | None:
        return None if self.process is None else self.process.poll()

    def stop(self) -> None:
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()


def wait_for_consumer_group(group_id: str, processes: list[MindProcess], timeout_s: float) -> None:
    """Until the landed runner has joined its group (Whisper loaded, consuming)
    and Mind's API reports ready (the score publisher started)."""

    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        for process in processes:
            if process.exit_code() is not None:
                raise DemoError(
                    f"{process.name} exited (code {process.exit_code()}) before the feed"
                )
        groups = _run(
            _compose("exec", "-T", "redpanda", "rpk", "group", "list"), what="rpk group list"
        )
        if group_id in groups and _api_ready():
            logger.info("the landed runner is consuming (group %s)", group_id)
            logger.info("Mind's API is ready, score publisher included (%s)", MIND_API_HEALTH_URL)
            return
        time.sleep(2)
    raise DemoError(
        f"the landed runner did not join {group_id}, or Mind's API was not ready, "
        f"within {timeout_s:.0f} s"
    )


def _api_ready() -> bool:
    """Whether the API's /health is 200: only once its whole start-up has run."""

    try:
        with urllib.request.urlopen(MIND_API_HEALTH_URL, timeout=2) as response:
            return bool(response.status == 200)
    except OSError:
        return False


# ------------------------------------------------------------------- result


def expected_complete(observed: verify.Observed, expected: dict) -> bool:
    """Everything expected.json predicts is on the topics: nothing more to wait for."""

    count = len(expected["tasks"])
    return (
        len(observed.created) >= count
        and len(observed.scored) >= count
        and len(observed.dependencies) >= len(expected["dependencies"])
    )


#: How often a Mind process that stopped on an error is started again, as the
#: cluster's restart policy would (docker-compose.yml: restart: on-failure).
#: Since #919 an LLM outage (503 after the retries, 429) stops the landed
#: runner on purpose, so its message is redelivered rather than skipped.
MAX_RESTARTS = 3


def watch(
    processes: list[MindProcess],
    env: dict[str, str],
    log_dir: Path,
    settle_s: float,
    timeout_s: float,
) -> verify.Observed:
    expected = common.read_json(common.EXPECTED)
    last_change = time.monotonic()
    seen = 0

    def done(observed: verify.Observed) -> bool:
        nonlocal last_change, seen
        now = time.monotonic()
        if observed.count() != seen:
            seen = observed.count()
            last_change = now
            logger.info(
                "on the topics so far: %d requested, %d created, %d scored, %d dependency message(s)",
                len(observed.requested),
                len(observed.created),
                len(observed.scored),
                len(observed.dependencies),
            )
        if expected_complete(observed, expected):
            logger.info("everything expected.json predicts has come out")
            return True
        for process in processes:
            code = process.exit_code()
            if code is None:
                continue
            if code != 0 and process.restarts < MAX_RESTARTS:
                process.restarts += 1
                logger.warning(
                    "%s exited (code %s): starting it again, as the cluster would (%d/%d); "
                    "its unfinished message is redelivered",
                    process.name,
                    code,
                    process.restarts,
                    MAX_RESTARTS,
                )
                process.start(env, log_dir)
                continue
            logger.error("%s exited (code %s): stopping the wait", process.name, code)
            return True
        quiet_since = max([last_change, *(p.last_output for p in processes)])
        if now - quiet_since >= settle_s:
            logger.info("nothing moved for %.0f s: taking what is there", settle_s)
            return True
        return False

    return verify.collect(BOOTSTRAP_SERVERS, done, timeout_s)


# --------------------------------------------------------------------- main


def run(args: argparse.Namespace) -> int:
    run_id = f"{datetime.now(UTC):%Y%m%dT%H%M%SZ}-{args.order}"
    log_dir = common.RUNS_DIR / run_id
    log_dir.mkdir(parents=True, exist_ok=True)
    env = mind_env(run_id)
    processes = [
        MindProcess("mind-landed", "mind.ingestion.landed_consumer_runner"),
        # The score publisher runs inside the API since #920. SelectorEventLoop:
        # uvicorn's own on Windows is the Proactor loop, which psycopg's async
        # mode rejects (the same as the landed runner's); on Linux it is the
        # default.
        MindProcess(
            "mind-api",
            "uvicorn",
            (
                "mind.api.main:app",
                "--host",
                "127.0.0.1",
                "--port",
                str(MIND_API_PORT),
                "--loop",
                "asyncio:SelectorEventLoop",
            ),
        ),
    ]
    logger.info("demo run %s, logs in %s", run_id, log_dir)
    try:
        stage(1, "preflight")
        preflight(env)

        stage(2, "stack: Redpanda, schema registry, MinIO, Inbox, vector store")
        start_stack()

        stage(3, "topics, schemas and migrations")
        create_topics()
        register_schemas()
        migrate(env)

        stage(4, "Mind: landed runner, and the API with the score publisher")
        for process in processes:
            process.start(env, log_dir)
        wait_for_consumer_group(
            env["MIND_LANDED_CONSUMER__GROUP_ID"], processes, args.start_timeout
        )

        stage(5, f"feed: three items, {args.order} order")
        feed_args = ["--order", args.order, "--run-id", run_id]
        if args.gap is not None:
            feed_args += ["--gap", str(args.gap)]
        if feed.main(feed_args) != 0:
            raise DemoError("the feed failed")

        stage(6, "result")
        observed = watch(processes, env, log_dir, args.settle, args.timeout)
        manifest = common.read_json(common.RUNS_DIR / f"{run_id}.json")
        landed_log = (log_dir / f"{processes[0].name}.log").read_text(encoding="utf-8")
        checks = verify.evaluate(observed, manifest, common.read_json(common.EXPECTED), landed_log)
        text = verify.report(checks, verify.trace(observed, manifest))
        print(text, flush=True)
        (log_dir / "result.txt").write_text(text + "\n", encoding="utf-8")
        return 0 if all(check.passed for check in checks) else 1
    except DemoError as error:
        logger.error("%s", error)
        return 2
    finally:
        for process in processes:
            process.stop()
        if args.down:
            _run(_compose("down", "-v"), what="docker compose down")
            logger.info("stack taken down")
        else:
            logger.info(
                "stack left up: Redpanda Console http://localhost:8090, MinIO http://localhost:9091 "
                "(minioadmin/minioadmin). `python -m demo_flow.run --down-only` takes it down."
            )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--order", choices=sorted(feed.ORDERS), default="natural")
    parser.add_argument("--gap", type=float, help="seconds between items (default: the feed's)")
    parser.add_argument(
        "--settle",
        type=float,
        default=120.0,
        help="stop waiting once nothing has moved for this long (default 120 s)",
    )
    parser.add_argument("--timeout", type=float, default=900.0, help="longest wait for the result")
    parser.add_argument(
        "--start-timeout",
        type=float,
        default=300.0,
        help="longest wait for Mind to start consuming",
    )
    parser.add_argument("--down", action="store_true", help="take the stack down after the run")
    parser.add_argument("--down-only", action="store_true", help="only take the stack down")
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")

    if args.down_only:
        _run(_compose("down", "-v"), what="docker compose down")
        logger.info("stack taken down")
        return 0
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
