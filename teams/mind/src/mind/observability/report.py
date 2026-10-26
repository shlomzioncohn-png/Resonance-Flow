"""A run's per-stage and per-item breakdown, from its JSONL files (#482).

    python -m mind.observability.report [CALLS_DIR] [--out report.md]

Reads every `*.jsonl` in CALLS_DIR (default: $MIND_MONITORING__CALLS_DIR) --
one file per process (sink.py) -- and writes a Markdown report: what was
measured, where the time and the cost went, per stage and per item, and what
is missing. Only what the files hold: a stage nothing recorded is listed as
not measured, never as zero.

How the numbers add up, and where they do not:

* Latency is each call's wall time. A stage's total is the sum of its calls.
  A Chain request made inside a score (`/ancestors`) is recorded on a row of
  its own as well: it breaks that score down and is never added to it. Which
  ones those are is read off the times -- a `chain` call in the same process,
  for the same task, inside a score's window -- not assumed from the kind:
  the rescore cascade's `/children` request comes after the score, and its
  time is added like any other call's.
* Cost has two units that are never added together: US dollars for LLM
  calls, CPU seconds for local work (OCR, ASR, embedding, scoring).
* Known and unknown are kept apart. A dollar total covers the priced calls
  only, and the unpriced ones are counted beside it, so a total with any
  unpriced call is a lower bound. The same for CPU seconds not measured
  (OCR on Windows) and tokens not reported.
* A degraded score -- Chain could not be asked, so the score is the
  baseline's alone -- is counted and reported apart from full scores, and
  its latency is not the full scoring path's.
* An item's span runs from its first call's start to its last call's end. It
  includes everything between them that recorded nothing: Kafka, Inbox, the
  wait for task.created. It is not the sum of its calls.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from collections import defaultdict
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path

from mind.observability.sink import SCHEMA
from mind.observability.tracking import CallKind, percentile

#: Stages of the full flow that should be in a run, and what records them.
#: A kind with no record in the run is reported as not measured.
EXPECTED_KINDS: dict[str, str] = {
    CallKind.ASR: "ASR (`asr.transcribe`)",
    CallKind.OCR: "OCR (`ocr.extract_text`)",
    CallKind.LLM: "LLM extraction and classification (`BasicExtractor._call`)",
    CallKind.EMBEDDING: "embedding (`embeddings.embed`) -- needs R3's change",
    CallKind.VECTOR_QUERY: "vector query (`PgTenantStore.search`) -- needs R3's change",
    CallKind.SCORING: "scoring (`RecordedScorer`, in the API)",
    CallKind.CHAIN: "Chain's traversal API (`RecordingClient`, in the API)",
}

_CPU_KINDS = {CallKind.OCR, CallKind.ASR, CallKind.EMBEDDING, CallKind.SCORING}


@dataclass(frozen=True)
class Call:
    """One line of a `mind.model_call/1` file."""

    process: str
    pid: int
    model_name: str
    kind: str | None
    status: str
    duration_ms: float
    cpu_s: float | None
    cost_usd: float | None
    prompt_tokens: int | None
    completion_tokens: int | None
    degraded: bool
    item: str | None
    task: str | None
    stage: str | None
    end: datetime

    @property
    def start(self) -> datetime:
        return self.end - timedelta(milliseconds=self.duration_ms)


@dataclass
class Loaded:
    calls: list[Call]
    files: list[Path]
    invalid_lines: int = 0


def load(directory: Path) -> Loaded:
    """Every valid line of every `*.jsonl` in `directory`; a line that is not
    `mind.model_call/1` is counted, not read."""
    files = sorted(directory.glob("*.jsonl"))
    loaded = Loaded(calls=[], files=files)
    for path in files:
        with path.open(encoding="utf-8") as lines:
            for text in lines:
                call = _parse(text)
                if call is None:
                    loaded.invalid_lines += 1
                else:
                    loaded.calls.append(call)
    loaded.calls.sort(key=lambda c: c.end)
    return loaded


def _parse(text: str) -> Call | None:
    try:
        line = json.loads(text)
        if line.get("schema") != SCHEMA:
            return None
        return Call(
            process=str(line["process"]),
            pid=int(line["pid"]),
            model_name=str(line["model_name"]),
            kind=line["kind"],
            status=str(line["status"]),
            duration_ms=float(line["duration_ms"]),
            cpu_s=line["cpu_s"],
            cost_usd=line["cost_usd"],
            prompt_tokens=line["prompt_tokens"],
            completion_tokens=line["completion_tokens"],
            degraded=bool(line["degraded"]),
            item=line["item"],
            task=line["task"],
            stage=line["stage"],
            end=datetime.fromisoformat(line["ts"]),
        )
    except (ValueError, KeyError, TypeError, AttributeError):
        return None


# --- aggregation --------------------------------------------------------------------


@dataclass
class Totals:
    """Calls added up, each measure with what it is missing kept beside it."""

    durations: list[float] = field(default_factory=list)
    errors: int = 0
    degraded: int = 0
    cpu_s: float = 0.0
    cpu_measured: int = 0
    cpu_applicable: int = 0
    usd: float = 0.0
    priced: int = 0
    llm_calls: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0
    tokens_reported: int = 0

    def add(self, call: Call) -> None:
        self.durations.append(call.duration_ms)
        self.errors += call.status != "success"
        self.degraded += call.degraded
        if call.kind in _CPU_KINDS:
            self.cpu_applicable += 1
            if call.cpu_s is not None:
                self.cpu_s += call.cpu_s
                self.cpu_measured += 1
        if call.kind == CallKind.LLM:
            self.llm_calls += 1
            if call.cost_usd is not None:
                self.usd += call.cost_usd
                self.priced += 1
            if call.prompt_tokens is not None and call.completion_tokens is not None:
                self.prompt_tokens += call.prompt_tokens
                self.completion_tokens += call.completion_tokens
                self.tokens_reported += 1

    @property
    def count(self) -> int:
        return len(self.durations)

    @property
    def total_ms(self) -> float:
        return sum(self.durations)

    def cpu_text(self) -> str:
        if not self.cpu_applicable:
            return "n/a"
        missing = self.cpu_applicable - self.cpu_measured
        if not self.cpu_measured:
            return f"unknown ({missing} unmeasured)"
        return f"{self.cpu_s:.2f}" + (f" + {missing} unmeasured" if missing else "")

    def usd_text(self) -> str:
        if not self.llm_calls:
            return "n/a"
        missing = self.llm_calls - self.priced
        if not self.priced:
            return f"unknown ({missing} unpriced)"
        return f"${self.usd:.4f}" + (f" + {missing} unpriced (lower bound)" if missing else "")

    def tokens_text(self) -> str:
        if not self.llm_calls:
            return "n/a"
        missing = self.llm_calls - self.tokens_reported
        text = f"{self.prompt_tokens} / {self.completion_tokens}" if self.tokens_reported else "-"
        return text + (f" + {missing} unreported" if missing else "")


def _totals(calls: Iterable[Call]) -> Totals:
    totals = Totals()
    for call in calls:
        totals.add(call)
    return totals


def _ms(value: float) -> str:
    return f"{value:,.0f}" if value >= 100 else f"{value:,.1f}"


def _s(ms: float) -> str:
    return f"{ms / 1000:,.1f} s"


# --- the report ---------------------------------------------------------------------


def render(loaded: Loaded, *, title: str) -> str:
    calls = loaded.calls
    out: list[str] = [f"# {title}", ""]
    if not calls:
        out += ["No calls recorded.", ""]
        return "\n".join(out)

    processes = sorted({(c.process, c.pid) for c in calls})
    out += [
        "## The run",
        "",
        f"- Files: {len(loaded.files)} -- "
        + ", ".join(f"`{p}` (pid {pid})" for p, pid in processes),
        f"- Calls recorded: {len(calls)}; lines not read (not `{SCHEMA}`): {loaded.invalid_lines}",
        (
            f"- From {calls[0].start.isoformat(timespec='seconds')} "
            f"to {calls[-1].end.isoformat(timespec='seconds')}"
        ),
        "",
    ]
    out += _by_stage(calls)
    out += _scoring(calls)
    out += _by_item(calls)
    out += _missing(calls)
    out += _notes()
    return "\n".join(out)


#: Clock slack when matching a Chain request to the score around it: a record's
#: end is the wall clock when it was recorded, its duration a monotonic clock.
_NESTING_SLACK = timedelta(milliseconds=5)


def nested_in_a_score(calls: Sequence[Call]) -> set[int]:
    """The `chain` calls (by index into `calls`) made inside a score: same
    process and task, and within the score's window. They break the score
    down; every other call is time of its own."""
    scores = [c for c in calls if c.kind == CallKind.SCORING and c.task is not None]
    nested: set[int] = set()
    for index, call in enumerate(calls):
        if call.kind != CallKind.CHAIN or call.task is None:
            continue
        for score in scores:
            if (
                (score.process, score.pid, score.task) == (call.process, call.pid, call.task)
                and score.start - _NESTING_SLACK <= call.start
                and call.end <= score.end + _NESTING_SLACK
            ):
                nested.add(index)
                break
    return nested


def _own_time(calls: Sequence[Call]) -> list[Call]:
    """`calls` without the Chain requests already inside a score in them."""
    nested = nested_in_a_score(calls)
    return [c for i, c in enumerate(calls) if i not in nested]


def _by_stage(calls: Sequence[Call]) -> list[str]:
    groups: dict[tuple[str, str, str], list[Call]] = defaultdict(list)
    for call in calls:
        groups[(call.stage or "(none)", call.kind or "-", call.model_name)].append(call)
    nested = len(nested_in_a_score(calls))
    out = [
        "## Where the time and the cost go, by stage",
        "",
        (
            "Wall time per call. A `chain` row made inside a score (same task, inside its "
            f"window: {nested} call(s) here) breaks that score down and is not added again "
            "below; the others are."
        ),
        "",
        (
            "| Stage | Kind | Call | Calls | Errors | Degraded | p50 ms | p95 ms | Total | "
            "CPU s | USD | Tokens in / out |"
        ),
        "|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for (stage, kind, name), group in sorted(
        groups.items(), key=lambda kv: -_totals(kv[1]).total_ms
    ):
        t = _totals(group)
        durations = sorted(t.durations)
        out.append(
            f"| {stage} | {kind} | `{name}` | {t.count} | {t.errors} | {t.degraded} | "
            f"{_ms(percentile(durations, 50))} | {_ms(percentile(durations, 95))} | "
            f"{_s(t.total_ms)} | {t.cpu_text()} | {t.usd_text()} | {t.tokens_text()} |"
        )
    whole = _totals(_own_time(calls))
    out += [
        "",
        (
            f"**All calls** (Chain requests inside a score counted once, in the score): "
            f"{_s(whole.total_ms)} of wall time, {whole.cpu_text()} CPU seconds, "
            f"{whole.usd_text()} in LLM calls."
        ),
        "",
    ]
    return out


def _scoring(calls: Sequence[Call]) -> list[str]:
    scores = [c for c in calls if c.kind == CallKind.SCORING]
    chain = [c for c in calls if c.kind == CallKind.CHAIN]
    if not scores and not chain:
        return []
    full = [c for c in scores if not c.degraded and c.status == "success"]
    degraded = [c for c in scores if c.degraded]
    failed_chain = [c for c in chain if c.status != "success"]
    inside = len(nested_in_a_score(calls))
    out = [
        "## Scoring: full and degraded",
        "",
        (
            f"- Scores: {len(scores)} -- **full path: {len(full)}**, "
            f"**degraded (Chain could not be asked): {len(degraded)}**, "
            f"failed: {sum(c.status != 'success' for c in scores)}"
        ),
        (
            f"- Chain requests: {len(chain)}, of which failed: {len(failed_chain)}; "
            f"made inside a score: {inside}, after it (the rescore cascade): {len(chain) - inside}"
        ),
    ]
    if degraded:
        d = sorted(c.duration_ms for c in degraded)
        out.append(
            f"- Degraded scores took p50 {_ms(percentile(d, 50))} ms, "
            f"p95 {_ms(percentile(d, 95))} ms."
        )
    if failed_chain:
        f = sorted(c.duration_ms for c in failed_chain)
        out.append(
            f"- Failed Chain requests took p50 {_ms(percentile(f, 50))} ms, "
            f"p95 {_ms(percentile(f, 95))} ms each."
        )
    if degraded and not full:
        out += [
            "",
            (
                "> **Every score in this run is degraded.** Its latency is the fallback path's -- "
                "a failed Chain request, then the baseline alone -- not the full scoring path's, "
                "and its score has no ancestors in it. It is not a measurement of full scoring."
            ),
        ]
    elif degraded:
        out += [
            "",
            (
                f"> {len(degraded)} of {len(scores)} scores are degraded: read the scoring "
                "latency above as a mix of two paths."
            ),
        ]
    return [*out, ""]


def _by_item(calls: Sequence[Call]) -> list[str]:
    items: dict[str, list[Call]] = defaultdict(list)
    for call in calls:
        if call.item is not None:
            items[call.item].append(call)
    unattributed = [c for c in calls if c.item is None]
    out = [
        "## Per item",
        "",
        (
            "Item: `item_ref` (a digest of tenant and source_ref, observability/context.py). "
            "Span: first call's start to last call's end, waits between stages included."
        ),
        "",
        (
            "| Item | Tasks | Calls | Span | Sum of calls | to_text | extraction | duplicate_check | "
            "task_created | score | CPU s | USD | Degraded scores |"
        ),
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    stages = ("to_text", "extraction", "duplicate_check", "task_created", "score")
    for item, group in sorted(items.items(), key=lambda kv: kv[1][0].start):
        own = _own_time(group)
        t = _totals(own)
        span = (max(c.end for c in group) - min(c.start for c in group)).total_seconds() * 1000
        by_stage = {s: _totals(c for c in own if c.stage == s) for s in stages}
        cells = [_s(by_stage[s].total_ms) if by_stage[s].count else "-" for s in stages]
        tasks = len({c.task for c in group if c.task is not None})
        degraded = sum(c.degraded for c in group if c.kind == CallKind.SCORING)
        out.append(
            f"| `{item}` | {tasks} | {len(own)} | {_s(span)} | {_s(t.total_ms)} | "
            + " | ".join(cells)
            + f" | {t.cpu_text()} | {t.usd_text()} | {degraded} |"
        )
    out += [
        "",
        f"Calls with no item: {len(unattributed)}"
        + (
            " -- "
            + ", ".join(sorted({f"{c.kind or '-'}/{c.stage or 'no stage'}" for c in unattributed}))
            if unattributed
            else ""
        ),
        "",
    ]
    return out


def _missing(calls: Sequence[Call]) -> list[str]:
    seen = {c.kind for c in calls}
    missing = [label for kind, label in EXPECTED_KINDS.items() if kind not in seen]
    out = ["## Not measured in this run", ""]
    if missing:
        out += [f"- {label}: no record -- **not measured, not zero**" for label in missing]
    else:
        out.append("- Every expected stage has records.")
    unmeasured_cpu = sum(1 for c in calls if c.kind in _CPU_KINDS and c.cpu_s is None)
    unpriced = sum(1 for c in calls if c.kind == CallKind.LLM and c.cost_usd is None)
    out += [
        f"- Calls whose CPU seconds were not measured: {unmeasured_cpu}"
        + (" (OCR on Windows reports no child-process CPU)" if unmeasured_cpu else ""),
        f"- LLM calls with no dollar price: {unpriced}",
        "",
    ]
    return out


def _notes() -> list[str]:
    return [
        "## How to read it",
        "",
        (
            "- Dollars are LLM calls only, at the provider's price per token; local work costs CPU "
            "seconds, never converted to dollars. The two are never added."
        ),
        "- A total beside an `unpriced` / `unmeasured` count is a lower bound.",
        (
            "- CPU seconds: OCR/ASR are process-wide (Tesseract and Whisper use several cores, "
            "so CPU can exceed wall time); scoring is its own thread's, which Windows counts "
            "in 15.6 ms steps -- a 0.00 there is below that, not free."
        ),
        (
            "- Not recorded at all: Kafka, MinIO, Inbox and the time between stages -- they show "
            "only in an item's span."
        ),
        "",
    ]


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m mind.observability.report", description=__doc__.split("\n\n")[0]
    )
    parser.add_argument(
        "calls_dir",
        nargs="?",
        type=Path,
        default=os.environ.get("MIND_MONITORING__CALLS_DIR"),
        help="directory of a run's *.jsonl files (default: $MIND_MONITORING__CALLS_DIR)",
    )
    parser.add_argument("--out", type=Path, help="write the report here instead of stdout")
    parser.add_argument("--title", default="Mind: per-stage latency and cost")
    args = parser.parse_args(argv)
    if args.calls_dir is None:
        parser.error("no CALLS_DIR given and MIND_MONITORING__CALLS_DIR is not set")
    directory = Path(args.calls_dir)
    if not directory.is_dir():
        parser.error(f"{directory} is not a directory")
    text = render(load(directory), title=args.title)
    if args.out is not None:
        args.out.write_text(text, encoding="utf-8")
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
