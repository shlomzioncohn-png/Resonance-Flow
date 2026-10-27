"""Every recorded call as a JSON line in a file of the process's own (src/mind/observability/sink.py) -- #482.

What must hold: the line format is exactly the documented one; a run's two
processes (landed runner, API) can write at the same time without losing or
mixing a line; and a write that fails never fails the call it records.
"""

import json
import logging
import math
import os
import subprocess
import sys
import textwrap
import threading
import time
from pathlib import Path

import pytest

from mind.config import MonitoringSettings
from mind.observability import tracking
from mind.observability.context import call_context, item_ref
from mind.observability.sink import SCHEMA, JsonlSink, default_process_name, to_json_line
from mind.observability.tracking import (
    CallKind,
    CallStatus,
    ModelCallRecord,
    _reset_for_tests,
    get_recent_calls,
    record_call,
)

TASK = "4d0c6f2a-8b1e-4c3d-9a7f-1e2b3c4d5e6f"
ITEM = item_ref("550e8400-e29b-41d4-a716-446655440000", "ref-1")

#: Every key a line has, always -- the documented `mind.model_call/2` format.
KEYS = {
    "schema",
    "ts",
    "process",
    "pid",
    "model_name",
    "kind",
    "status",
    "duration_ms",
    "cpu_s",
    "cost_usd",
    "prompt_tokens",
    "completion_tokens",
    "degraded",
    "audio_s",
    "item",
    "task",
    "stage",
}


@pytest.fixture(autouse=True)
def _clean_call_records():
    _reset_for_tests()
    yield
    _reset_for_tests()  # closes a sink a test opened


def write_calls_to(monkeypatch: pytest.MonkeyPatch, directory: Path, process: str) -> None:
    """As the env would: MIND_MONITORING__CALLS_DIR and __PROCESS_NAME."""
    settings = MonitoringSettings.model_construct(calls_dir=directory, process_name=process)
    monkeypatch.setattr(tracking, "monitoring_settings", lambda: settings)
    _reset_for_tests()


def lines(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]


# --- off unless asked --------------------------------------------------------------------


def test_without_a_calls_dir_nothing_is_written(tmp_path: Path):
    # The default: MIND_MONITORING__CALLS_DIR not set.
    assert MonitoringSettings.model_construct().calls_dir is None
    record_call("m", 1.0, None)
    assert tracking.calls_sink() is None


def test_a_process_that_records_nothing_leaves_no_file(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
):
    write_calls_to(monkeypatch, tmp_path / "run", "mind-api")
    assert tracking.calls_sink() is not None
    assert not (tmp_path / "run").exists()


# --- the format ----------------------------------------------------------------------------


def test_a_call_is_one_line_in_this_processs_file(monkeypatch: pytest.MonkeyPatch, tmp_path: Path):
    write_calls_to(monkeypatch, tmp_path, "mind-api")

    with call_context(item=ITEM, task=TASK, stage="score"):
        record_call(
            "hierarchical-ranking", 12.3456, None, kind=CallKind.SCORING, cpu_s=0.01, degraded=True
        )

    path = tmp_path / f"model-calls-mind-api-{os.getpid()}.jsonl"
    [line] = lines(path)
    assert set(line) == KEYS
    assert line == {
        "schema": SCHEMA,
        "ts": line["ts"],
        "process": "mind-api",
        "pid": os.getpid(),
        "model_name": "hierarchical-ranking",
        "kind": "scoring",
        "status": "success",
        "duration_ms": 12.346,
        "cpu_s": 0.01,
        "cost_usd": None,
        "prompt_tokens": None,
        "completion_tokens": None,
        "degraded": True,
        "audio_s": None,
        "item": ITEM,
        "task": TASK,
        "stage": "score",
    }
    assert line["ts"].endswith("+00:00")  # UTC, ISO 8601


def test_an_llm_call_outside_any_context_has_its_tokens_and_dollars_and_null_ids():
    record = ModelCallRecord(
        "gemini-3.6-flash",
        1500.0,
        0.00344,
        kind=CallKind.LLM,
        status=CallStatus.SUCCESS,
        prompt_tokens=3637,
        completion_tokens=190,
    )
    line = json.loads(to_json_line(record, process="landed_consumer_runner", pid=7))
    assert (line["kind"], line["cost_usd"], line["prompt_tokens"], line["completion_tokens"]) == (
        "llm",
        0.00344,
        3637,
        190,
    )
    assert (line["cpu_s"], line["item"], line["task"], line["stage"]) == (None, None, None, None)
    assert line["degraded"] is False


def test_a_line_is_one_line_whatever_the_model_name_holds():
    record = ModelCallRecord('odd "name"\nwith a newline', 1.0, None)
    text = to_json_line(record, process="p", pid=1)
    assert text.endswith("\n") and text.count("\n") == 1
    assert json.loads(text)["model_name"] == 'odd "name"\nwith a newline'


def test_an_unsafe_process_name_cannot_leave_the_directory(tmp_path: Path):
    sink = JsonlSink(tmp_path, "../../etc/passwd", pid=1)
    assert sink.path.parent == tmp_path
    assert default_process_name()  # never empty


# --- concurrent writers --------------------------------------------------------------------


def test_threads_of_one_process_never_split_a_line(monkeypatch: pytest.MonkeyPatch, tmp_path: Path):
    write_calls_to(monkeypatch, tmp_path, "mind-landed")

    def burst(thread: int) -> None:
        with call_context(stage="score"):
            for i in range(200):
                record_call(f"m{thread}", float(i), None, kind=CallKind.SCORING)

    threads = [threading.Thread(target=burst, args=(t,)) for t in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    [path] = tmp_path.iterdir()
    written = lines(path)  # every line parses: none was split or interleaved
    assert len(written) == 8 * 200
    assert {line["model_name"] for line in written} == {f"m{t}" for t in range(8)}


_WRITER = textwrap.dedent(
    """
    import sys, threading, time
    from pathlib import Path
    from mind.observability.context import call_context, item_ref
    from mind.observability.tracking import CallKind, record_call

    go, name = Path(sys.argv[1]), sys.argv[2]

    def burst(thread):
        with call_context(item=item_ref("tenant", f"{name}-{thread}"), stage="score"):
            for i in range(250):
                record_call(f"{name}-m{thread}", float(i), None, kind=CallKind.SCORING)

    threads = [threading.Thread(target=burst, args=(t,)) for t in range(4)]
    while not go.exists():  # both processes start writing at the same moment
        time.sleep(0.005)
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    """
)


def test_two_processes_writing_at_once_each_keep_every_line(tmp_path: Path):
    # The demo's two processes, mind-landed and mind-api, into one run directory.
    run_dir, go = tmp_path / "run", tmp_path / "go"
    env = os.environ | {"MIND_MONITORING__CALLS_DIR": str(run_dir)}
    writers = [
        subprocess.Popen(
            [sys.executable, "-c", _WRITER, str(go), name],
            env=env | {"MIND_MONITORING__PROCESS_NAME": name},
            stderr=subprocess.PIPE,
            text=True,
        )
        for name in ("mind-landed", "mind-api")
    ]
    time.sleep(0.5)  # both past their imports, waiting on `go`
    go.touch()
    for writer in writers:
        _, err = writer.communicate(timeout=120)
        assert writer.returncode == 0, err

    files = sorted(run_dir.iterdir())
    assert [f.name.rsplit("-", 1)[0] for f in files] == [
        "model-calls-mind-api",
        "model-calls-mind-landed",
    ]
    for path in files:
        written = lines(path)  # every line whole and parseable
        assert len(written) == 4 * 250
        [process] = {line["process"] for line in written}
        assert path.name.startswith(f"model-calls-{process}-")
        assert all(line["model_name"].startswith(f"{process}-") for line in written)
        assert len({line["pid"] for line in written}) == 1


# --- a failed write never fails the call ----------------------------------------------------


def test_a_write_that_fails_is_counted_logged_and_the_call_still_recorded(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, caplog: pytest.LogCaptureFixture
):
    blocked = tmp_path / "not-a-directory"
    blocked.write_text("a file where the directory should be")
    write_calls_to(monkeypatch, blocked, "mind-api")

    with caplog.at_level(logging.ERROR, logger="mind.observability"):
        records = [record_call("m", 1.0, None, kind=CallKind.SCORING) for _ in range(3)]

    assert len(get_recent_calls()) == 3 and records[0].model_name == "m"
    sink = tracking.calls_sink()
    assert sink is not None and sink.failures == 3
    # Logged once, not once per call: a full disk must not flood the log.
    assert len([m for m in caplog.messages if m.startswith("MODEL_CALL_SINK_FAILED")]) == 1


def test_writing_resumes_once_the_directory_can_be_written_again(tmp_path: Path):
    directory = tmp_path / "run"
    directory.write_text("in the way")
    sink = JsonlSink(directory, "mind-api", pid=1)
    record = ModelCallRecord("m", 1.0, None, kind=CallKind.SCORING)

    assert sink.write(record) is False
    directory.unlink()
    assert sink.write(record) is True
    sink.close()
    assert len(lines(sink.path)) == 1


def test_a_measure_json_cannot_hold_is_a_failed_write_not_an_error(tmp_path: Path):
    sink = JsonlSink(tmp_path, "mind-api", pid=1)
    assert sink.write(ModelCallRecord("m", math.nan, None)) is False
    assert sink.failures == 1
    assert not sink.path.exists()


def test_an_asr_call_carries_the_audio_it_ran_on():
    # #484: a cost per audio minute needs the minutes.
    record = ModelCallRecord("whisper", 7000.0, None, kind=CallKind.ASR, cpu_s=26.0, audio_s=67.5)
    line = json.loads(to_json_line(record, process="mind-landed", pid=1))
    assert (line["schema"], line["audio_s"]) == ("mind.model_call/2", 67.5)
