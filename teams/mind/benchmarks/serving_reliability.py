"""#482: does the serving layer hold under the full flow? Two measurements.

    python -m benchmarks.serving_reliability asr-long [--seconds 600 900] [--runs 3]
    python -m benchmarks.serving_reliability api-load --dsn postgresql://... [--concurrency 32]

From `teams/mind`. Results print as JSON; `benchmarks/serving-reliability-482.md`
records a run of each.

**asr-long** -- the landed runner's longest job: one ASR call on a recording
as long as ASR decodes (`MAX_DURATION_S`), and one past it, made by tiling the
demo meeting (`demo_flow/artifacts/meeting.m4a`). Each length runs `--runs`
times in this one process, as the runner would: wall time, CPU seconds, the
process's memory after each call and at its peak, and whether the call hit
the cap, the timeout or a broken file. Memory that keeps rising from run to
run is the growth #482 asks about. Needs the Whisper weights under models/.

**api-load** -- Mind's API, as deployed (the one process Relay's chart runs),
under concurrent requests, with `/health` probed throughout the way Relay's
probes call it (a 1 s timeout, kubelet's default). Starts its own API on
`--port` against `--dsn` -- a disposable pgvector, never a shared one: it runs
the migrations there and writes `--tasks` random vectors -- with no Kafka
section, so the score publisher does not start. Then `--concurrency` clients
call `/v1/tasks/{id}/related` for `--seconds`, while a prober calls `/health`
every 250 ms and the API's memory is sampled every 500 ms.
"""

from __future__ import annotations

import argparse
import asyncio
import io
import json
import logging
import math
import os
import random
import re
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import wave
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlsplit

import psutil

MIND_DIR = Path(__file__).resolve().parent.parent
MEETING = MIND_DIR / "demo_flow" / "artifacts" / "meeting.m4a"
SAMPLE_RATE = 16_000
MB = 1024 * 1024


def _rss_mb(process: psutil.Process) -> float:
    return round(process.memory_info().rss / MB, 1)


def _peak_mb(process: psutil.Process) -> float | None:
    """The process's peak memory so far: Windows' peak working set, Linux's maxrss."""
    if sys.platform == "win32":
        return round(process.memory_info().peak_wset / MB, 1)
    import resource

    return round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024, 1)


def _percentile(values: list[float], pct: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    rank = (pct / 100) * (len(ordered) - 1)
    lo, hi = int(rank), min(int(rank) + 1, len(ordered) - 1)
    return round(ordered[lo] + (ordered[hi] - ordered[lo]) * (rank - lo), 1)


# --- asr-long -------------------------------------------------------------------------


def long_recording(seconds: float) -> bytes:
    """`seconds` of the demo meeting, tiled, as a 16 kHz mono WAV."""
    import numpy as np
    from faster_whisper.audio import decode_audio

    audio = decode_audio(str(MEETING), sampling_rate=SAMPLE_RATE)
    samples = int(seconds * SAMPLE_RATE)
    tiled = np.tile(audio, math.ceil(samples / len(audio)))[:samples]
    pcm = (np.clip(tiled, -1.0, 1.0) * 32767).astype("<i2")
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(SAMPLE_RATE)
        out.writeframes(pcm.tobytes())
    return buffer.getvalue()


class _AsrCallLine(logging.Handler):
    """Keeps asr.py's own `asr_call` line: it says whether the timeout or the cap hit."""

    def __init__(self) -> None:
        super().__init__()
        self.last: str | None = None

    def emit(self, record: logging.LogRecord) -> None:
        message = record.getMessage()
        if message.startswith("asr_call "):
            self.last = message


def _flag(line: str | None, name: str) -> bool | None:
    match = re.search(rf"\b{name}=(True|False)\b", line or "")
    return None if match is None else match.group(1) == "True"


def asr_long(lengths: list[float], runs: int, model: str) -> dict[str, Any]:
    os.environ.setdefault("CONFIG_FILE_PATH", str(MIND_DIR / "config.example.yml"))
    os.environ.setdefault("SECRETS_FILE_PATH", str(MIND_DIR / "secrets.example.yml"))
    # The example config has no asr section: the model comes from here, not a local .env.
    os.environ["WHISPER_MODEL"] = model
    from mind.multimodal.asr import MAX_DURATION_S, TRANSCRIBE_TIMEOUT_S, transcribe, warm_up

    line = _AsrCallLine()
    logging.getLogger("mind.multimodal.asr").addHandler(line)
    logging.getLogger("mind.multimodal.asr").setLevel(logging.INFO)
    process = psutil.Process()
    before_load = _rss_mb(process)
    started = time.perf_counter()
    if not warm_up():
        raise SystemExit("Whisper did not load: weights missing under models/? (models/README.md)")
    result: dict[str, Any] = {
        "model": model,
        "max_duration_s": MAX_DURATION_S,
        "transcribe_timeout_s": TRANSCRIBE_TIMEOUT_S,
        "rss_mb_before_model": before_load,
        "warm_up_s": round(time.perf_counter() - started, 1),
        "rss_mb_after_model": _rss_mb(process),
        "logical_cpus": psutil.cpu_count(),
        "calls": [],
    }
    for seconds in lengths:
        content = long_recording(seconds)
        for run in range(1, runs + 1):
            line.last = None
            cpu_before, wall_before = time.process_time(), time.perf_counter()
            transcript = transcribe(content, filename=f"meeting-x{seconds:.0f}s.wav")
            wall = time.perf_counter() - wall_before
            result["calls"].append(
                {
                    "recording_s": seconds,
                    "run": run,
                    "success": transcript.success,
                    "wall_s": round(wall, 1),
                    "cpu_s": round(time.process_time() - cpu_before, 1),
                    "transcribed_s": round(transcript.duration_s, 1),
                    "rtf": round(wall / transcript.duration_s, 3)
                    if transcript.duration_s
                    else None,
                    "truncated": transcript.truncated,
                    "timed_out": _flag(line.last, "timed_out"),
                    "capped": _flag(line.last, "capped"),
                    "incomplete_file": transcript.incomplete_file,
                    "segments": len(transcript.segments),
                    "rss_mb_after": _rss_mb(process),
                    "peak_rss_mb": _peak_mb(process),
                }
            )
            print(json.dumps(result["calls"][-1]), file=sys.stderr, flush=True)
    return result


# --- api-load -------------------------------------------------------------------------


def _config_files(dsn: str, directory: Path) -> dict[str, str]:
    """config.yaml and secrets.yaml for `dsn`, with no kafka section (#481's shape)."""
    import yaml

    parts = urlsplit(dsn)
    config = {
        "vector_store": {
            "url": f"{parts.hostname}:{parts.port or 5432}",
            "user": unquote(parts.username or ""),
            "db": parts.path.lstrip("/"),
        },
        "service": {"log_level": "WARNING"},
    }
    secrets = {"vector_store": {"password": unquote(parts.password or "")}}
    (directory / "config.yaml").write_text(yaml.safe_dump(config), encoding="utf-8")
    (directory / "secrets.yaml").write_text(yaml.safe_dump(secrets), encoding="utf-8")
    return {
        "CONFIG_FILE_PATH": str(directory / "config.yaml"),
        "SECRETS_FILE_PATH": str(directory / "secrets.yaml"),
    }


def _seed(env: dict[str, str], tasks: int) -> tuple[str, list[str]]:
    """Run the migrations, then index `tasks` random unit vectors under one tenant."""
    subprocess.run(
        [sys.executable, "-m", "alembic", "upgrade", "head"], cwd=MIND_DIR, env=env, check=True
    )
    os.environ.update(env)
    from mind.config import get_settings
    from mind.embeddings.embeddings import EMBEDDING_DIM
    from mind.embeddings.pgvector_store import PgVectorStore

    tenant = uuid.uuid4()
    ids = [str(uuid.uuid4()) for _ in range(tasks)]
    rng = random.Random(482)

    def unit() -> list[float]:
        v = [rng.gauss(0, 1) for _ in range(EMBEDDING_DIM)]
        norm = math.sqrt(sum(x * x for x in v))
        return [x / norm for x in v]

    async def write() -> None:
        store = PgVectorStore(get_settings().vector_store)
        await store.open()
        try:
            slice_ = store.for_tenant(tenant)
            for task_id in ids:
                await slice_.upsert(task_id, unit())
        finally:
            await store.close()

    loop = asyncio.SelectorEventLoop()  # psycopg's async mode rejects Windows' Proactor loop
    try:
        loop.run_until_complete(write())
    finally:
        loop.close()
    return str(tenant), ids


def _interpreter(launched: psutil.Process) -> psutil.Process:
    """The process actually running the API. On Windows a venv's python.exe is a
    launcher that starts the real interpreter as its child: measure that one."""
    children = launched.children(recursive=True)
    return max([launched, *children], key=lambda p: p.memory_info().rss)


class _Prober(threading.Thread):
    """Calls /health every 250 ms on a client and a thread of its own, so what it
    measures is the API's answer, not a queue in the load generator. A probe
    over 1 s is one kubelet's default timeout would have failed."""

    def __init__(self, base: str) -> None:
        super().__init__(daemon=True)
        self.base = base
        self.stop = threading.Event()
        self.ms: list[float] = []
        self.bad: list[str] = []

    def run(self) -> None:
        import httpx

        with httpx.Client(base_url=self.base, timeout=5.0) as client:
            while not self.stop.is_set():
                started = time.perf_counter()
                try:
                    response = client.get("/health")
                    if response.status_code != 200:
                        self.bad.append(f"status {response.status_code}")
                except httpx.HTTPError as error:
                    self.bad.append(type(error).__name__)
                self.ms.append((time.perf_counter() - started) * 1000)
                self.stop.wait(0.25)

    def summary(self, prefix: str) -> dict[str, Any]:
        return {
            f"{prefix}_probes": len(self.ms),
            f"{prefix}_failed": len(self.bad),
            f"{prefix}_failures": sorted(set(self.bad)),
            f"{prefix}_over_1s": sum(ms > 1000 for ms in self.ms),
            f"{prefix}_p50_ms": _percentile(self.ms, 50),
            f"{prefix}_p95_ms": _percentile(self.ms, 95),
            f"{prefix}_max_ms": round(max(self.ms), 1) if self.ms else None,
        }


def _probe_for(base: str, seconds: float) -> _Prober:
    prober = _Prober(base)
    prober.start()
    time.sleep(seconds)
    prober.stop.set()
    prober.join()
    return prober


async def _load(
    base: str, ids: list[str], concurrency: int, seconds: float, api: psutil.Process
) -> dict[str, Any]:
    import httpx

    related_ms: list[float] = []
    related_errors = 0
    rss: list[float] = []
    deadline = time.monotonic() + seconds
    rng = random.Random(1)
    prober = _Prober(base)
    prober.start()

    async with httpx.AsyncClient(
        base_url=base, timeout=10.0, limits=httpx.Limits(max_connections=concurrency)
    ) as client:

        async def worker() -> None:
            nonlocal related_errors
            while time.monotonic() < deadline:
                started = time.perf_counter()
                try:
                    response = await client.get(f"/v1/tasks/{rng.choice(ids)}/related")
                    ok = response.status_code == 200
                except httpx.HTTPError:
                    ok = False
                related_ms.append((time.perf_counter() - started) * 1000)
                related_errors += not ok

        async def sampler() -> None:
            while time.monotonic() < deadline:
                rss.append(_rss_mb(api))
                await asyncio.sleep(0.5)

        await asyncio.gather(sampler(), *(worker() for _ in range(concurrency)))
    prober.stop.set()
    prober.join()

    return {
        "related_requests": len(related_ms),
        "related_errors": related_errors,
        "related_per_s": round(len(related_ms) / seconds, 1),
        "related_p50_ms": _percentile(related_ms, 50),
        "related_p95_ms": _percentile(related_ms, 95),
        "related_max_ms": round(max(related_ms), 1) if related_ms else None,
        **prober.summary("health_loaded"),
        "api_rss_mb_min": min(rss) if rss else None,
        "api_rss_mb_max": max(rss) if rss else None,
    }


def api_load(dsn: str, tasks: int, concurrency: int, seconds: float, port: int) -> dict[str, Any]:
    import httpx

    with tempfile.TemporaryDirectory(prefix="mind-api-load-") as tmp:
        env = os.environ | _config_files(dsn, Path(tmp))
        env.pop("MIND_MONITORING__CALLS_DIR", None)
        _, ids = _seed(env, tasks)
        base = f"http://127.0.0.1:{port}"
        api = subprocess.Popen(
            [sys.executable, "-m", "uvicorn", "mind.api.main:app", "--host", "127.0.0.1"]
            + [
                "--port",
                str(port),
                "--loop",
                "asyncio:SelectorEventLoop",
                "--log-level",
                "warning",
            ],
            cwd=MIND_DIR,
            env=env,
        )
        try:
            started = time.perf_counter()
            while True:
                try:
                    if httpx.get(f"{base}/health", timeout=1.0).status_code == 200:
                        break
                except httpx.HTTPError:
                    pass
                if api.poll() is not None or time.perf_counter() - started > 120:
                    raise SystemExit("the API did not become ready within 120 s")
                time.sleep(0.25)
            launched = psutil.Process(api.pid)
            process = _interpreter(launched)
            ready = {
                "ready_s": round(time.perf_counter() - started, 1),
                "api_pid_measured": process.pid,
                "api_rss_mb_idle": _rss_mb(process),
                **_probe_for(base, 5.0).summary("health_idle"),
            }
            loaded = asyncio.run(_load(base, ids, concurrency, seconds, process))
            after = {"api_rss_mb_after": _rss_mb(process), "api_still_running": api.poll() is None}
        finally:
            # The launcher and the interpreter under it, both.
            for proc in [*psutil.Process(api.pid).children(recursive=True)]:
                proc.kill()
            api.terminate()
            try:
                api.wait(timeout=10)
            except subprocess.TimeoutExpired:
                api.kill()
    return {
        "tasks_indexed": tasks,
        "concurrency": concurrency,
        "seconds": seconds,
        "logical_cpus": psutil.cpu_count(),
        **ready,
        **loaded,
        **after,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    long = sub.add_parser("asr-long", help="ASR on a recording at and past MAX_DURATION_S")
    long.add_argument("--seconds", type=float, nargs="+", default=[600.0, 900.0])
    long.add_argument("--runs", type=int, default=3)
    long.add_argument("--model", default="base.en", help="Whisper size, as the demo uses")
    load = sub.add_parser("api-load", help="the API under concurrent requests, /health probed")
    load.add_argument(
        "--dsn", required=True, help="a DISPOSABLE pgvector: it is migrated and written"
    )
    load.add_argument("--tasks", type=int, default=200)
    load.add_argument("--concurrency", type=int, default=32)
    load.add_argument("--seconds", type=float, default=30.0)
    load.add_argument("--port", type=int, default=8810)
    args = parser.parse_args(argv)
    if args.command == "asr-long":
        out = asr_long(args.seconds, args.runs, args.model)
    else:
        out = api_load(args.dsn, args.tasks, args.concurrency, args.seconds, args.port)
    print(json.dumps(out, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
