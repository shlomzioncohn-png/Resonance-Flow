"""Every recorded call, also as one JSON line in a file of this process's own (#482).

The in-memory ledger dies with its process, and Mind runs as more than one:
the landed runner records OCR, ASR, LLM and embedding calls, the API records
scoring and Chain. With `MIND_MONITORING__CALLS_DIR` set, each process also
writes every record_call() to

    <calls_dir>/model-calls-<process>-<pid>.jsonl

so a report can read one run's calls from all its processes afterwards.

**One file per process, never a shared one.** Appending to one file from two
processes is not safe everywhere: on Windows the C runtime's append mode
seeks to the end and then writes, so two processes can overwrite each other.
A file per process has a single writer by construction; inside it, a lock
keeps each line whole across threads, and each line is flushed as written so
a process that dies leaves every call it recorded before. A restarted
process gets a new pid, so a new file.

**A failed write never fails a call.** An OSError (disk full, directory gone,
no permission) is counted, logged at ERROR on the first failure and every
100th after it, and the call is still in the in-memory ledger and the log.

The line format, `mind.model_call/1`, is documented in the README ("The
JSONL file"); `to_json_line` is its one writer.
"""

from __future__ import annotations

import json
import logging
import os
import re
import sys
import threading
from datetime import UTC, datetime
from pathlib import Path
from typing import IO, TYPE_CHECKING, Any

if TYPE_CHECKING:
    from mind.observability.tracking import ModelCallRecord

logger = logging.getLogger("mind.observability")

SCHEMA = "mind.model_call/1"

#: Log a write failure the first time and then every this many.
_LOG_EVERY_N_FAILURES = 100

_UNSAFE = re.compile(r"[^A-Za-z0-9_.-]+")


def default_process_name() -> str:
    """The running script's name, e.g. `landed_consumer_runner` or `uvicorn`."""
    stem = Path(sys.argv[0]).stem if sys.argv and sys.argv[0] else ""
    return _UNSAFE.sub("-", stem).strip("-") or "python"


def to_json_line(record: ModelCallRecord, *, process: str, pid: int) -> str:
    """One record as a `mind.model_call/1` line, newline included.

    Every key is always present; a measure that is unknown or does not apply
    is null -- which of the two follows from `kind`, by the same rules as the
    summary (README, "The summary").
    """
    line: dict[str, Any] = {
        "schema": SCHEMA,
        "ts": datetime.fromtimestamp(record.timestamp, UTC).isoformat(timespec="microseconds"),
        "process": process,
        "pid": pid,
        "model_name": record.model_name,
        "kind": record.kind.value if record.kind is not None else None,
        "status": record.status.value,
        "duration_ms": round(record.duration_ms, 3),
        "cpu_s": round(record.cpu_s, 6) if record.cpu_s is not None else None,
        "cost_usd": record.cost,
        "prompt_tokens": record.prompt_tokens,
        "completion_tokens": record.completion_tokens,
        "degraded": record.degraded,
        "item": record.item,
        "task": record.task,
        "stage": record.stage,
    }
    return json.dumps(line, separators=(",", ":"), allow_nan=False) + "\n"


class JsonlSink:
    """Appends records to `<directory>/model-calls-<process>-<pid>.jsonl`.

    The file and the directory are created on the first write, not before:
    a process that records nothing leaves no empty file behind.
    """

    def __init__(self, directory: Path, process: str, pid: int | None = None) -> None:
        self._process = _UNSAFE.sub("-", process).strip("-") or "python"
        self._pid = pid if pid is not None else os.getpid()
        self.path = Path(directory) / f"model-calls-{self._process}-{self._pid}.jsonl"
        self._lock = threading.Lock()
        self._file: IO[str] | None = None
        self.failures = 0

    def write(self, record: ModelCallRecord) -> bool:
        """Append one line; False (and the failure counted) if it could not be written."""
        try:
            line = to_json_line(record, process=self._process, pid=self._pid)
        except ValueError as exc:  # a NaN/inf measure: not valid JSON
            return self._failed(exc)
        with self._lock:
            try:
                if self._file is None:
                    self.path.parent.mkdir(parents=True, exist_ok=True)
                    self._file = self.path.open("a", encoding="utf-8", newline="\n")
                self._file.write(line)
                self._file.flush()
                return True
            except OSError as exc:
                self._close_quietly()
                return self._failed(exc)

    def _failed(self, exc: Exception) -> bool:
        self.failures += 1
        if self.failures % _LOG_EVERY_N_FAILURES == 1:
            logger.error(
                "MODEL_CALL_SINK_FAILED path=%s failures=%d: %s -- the call is still in "
                "the in-memory ledger and the log",
                self.path,
                self.failures,
                exc,
            )
        return False

    def _close_quietly(self) -> None:
        # Opened again on the next write: the directory may come back.
        if self._file is not None:
            try:
                self._file.close()
            except OSError:
                pass
            self._file = None

    def close(self) -> None:
        with self._lock:
            self._close_quietly()
