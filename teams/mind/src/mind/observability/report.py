"""Cost and latency of Mind's model calls, from their JSONL files (#482, #484).

    python -m mind.observability.report [DIR ...] [--since 7d] [--out report.md]

Each DIR is one run -- a directory of `*.jsonl`, one file per process
(sink.py) -- or a directory searched for runs: `demo_flow/runs/`, a folder
under `benchmarks/`. With no DIR: $MIND_MONITORING__CALLS_DIR, else
`demo_flow/runs/` under the current directory. `--since` keeps only the
calls recorded in the last 7d / 24h / 30m / 2w, or since a date.

Writes a Markdown report: what was read, the totals and the cost per item,
where the time and the cost went per stage and per model, the runs against
each other, and how far each number can be trusted. Only what the files
hold: a stage nothing recorded is listed as not measured, never as zero.

How the numbers add up, and where they do not:

* Latency is each call's wall time. A stage's total is the sum of its calls.
  A Chain request made inside a score (`/ancestors`) is recorded on a row of
  its own as well: it breaks that score down and is never added to it. Which
  ones those are is read off the times -- a `chain` call in the same process,
  for the same task, inside a score's window -- not assumed from the kind:
  the rescore cascade's `/children` request comes after the score, and its
  time is added like any other call's.
* Cost has two units that are never added together: US dollars for LLM
  calls, CPU seconds for local work (OCR, ASR, embedding, scoring). CPU
  seconds are machine time, not a bill, and are never turned into dollars.
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
* An item belongs to its run. The demo sends the same items every run, so
  the same `item_ref` comes back in each; an item is (run, item_ref).
* Every line of every file is read. The in-memory ring buffer (tracking.py,
  the last 10,000 calls) and `/debug/model-calls` are not used, so a long
  run is never cut down to its tail here.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import statistics
import sys
from collections import defaultdict
from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from pathlib import Path

from mind.observability.sink import SCHEMA
from mind.observability.tracking import CallKind, percentile

#: Stages of the full flow that should be in a run, and what records them.
#: A kind with no record in the run is reported as not measured.
EXPECTED_KINDS: dict[str, str] = {
    CallKind.ASR: "ASR (`asr.transcribe`)",
    CallKind.OCR: "OCR (`ocr.extract_text`)",
    CallKind.LLM: "LLM extraction and classification (`BasicExtractor._call`)",
    CallKind.EMBEDDING: "embedding (`embeddings.embed`/`embed_batch`)",
    CallKind.VECTOR_QUERY: "vector query (`PgTenantStore.search`)",
    CallKind.SCORING: "scoring (`RecordedScorer`, in the API)",
    CallKind.CHAIN: "Chain's traversal API (`RecordingClient`, in the API)",
}

_CPU_KINDS = {CallKind.OCR, CallKind.ASR, CallKind.EMBEDDING, CallKind.SCORING}

#: Where the demo keeps its runs, relative to `teams/mind/` (demo_flow/common.py).
DEFAULT_RUNS_DIR = Path("demo_flow") / "runs"

#: "What would 10,000 items cost" -- the question #484 says will be asked.
PROJECTION_ITEMS = 10_000

#: Below this many calls, a p95 is close to the max and says little on its own.
_SMALL_SAMPLE = 20

#: More items than this and the per-item table is left out; the per-type
#: figures stand for them.
_ITEM_TABLE_MAX_ROWS = 50


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
    run: str = ""

    @property
    def start(self) -> datetime:
        return self.end - timedelta(milliseconds=self.duration_ms)


@dataclass
class Loaded:
    calls: list[Call]
    files: list[Path]
    invalid_lines: int = 0
    #: Runs read, in the order they started.
    runs: list[str] = field(default_factory=list)
    #: `--since`: calls recorded before it are left out, and counted.
    since: datetime | None = None
    before_since: int = 0
    #: A run found under two paths (a demo run copied into benchmarks/) is
    #: read once; the other path is listed here.
    duplicate_runs: list[str] = field(default_factory=list)


def run_name(directory: Path) -> str:
    """A run's name: its directory's, or its parent's for a `calls/` folder
    (`runs/<run-id>/calls/`, as the demo writes them)."""
    directory = directory.resolve()
    return directory.parent.name if directory.name == "calls" else directory.name


def discover(root: Path) -> list[Path]:
    """The runs under `root`: `root` itself when it holds `*.jsonl`, else every
    directory below it that does."""
    if any(root.glob("*.jsonl")):
        return [root]
    return sorted({path.parent for path in root.rglob("*.jsonl")})


def load(directory: Path, *, run: str | None = None) -> Loaded:
    """Every valid line of every `*.jsonl` in `directory`, one run; a line that
    is not `mind.model_call/1` is counted, not read."""
    name = run if run is not None else run_name(directory)
    files = sorted(directory.glob("*.jsonl"))
    loaded = Loaded(calls=[], files=files)
    for path in files:
        with path.open(encoding="utf-8") as lines:
            for text in lines:
                call = _parse(text, run=name)
                if call is None:
                    loaded.invalid_lines += 1
                else:
                    loaded.calls.append(call)
    loaded.calls.sort(key=lambda c: c.end)
    loaded.runs = [name] if loaded.calls else []
    return loaded


def load_runs(roots: Iterable[Path], *, since: datetime | None = None) -> Loaded:
    """Every run under `roots`, each once, with the calls before `since` left out."""
    merged = Loaded(calls=[], files=[], since=since)
    seen: dict[str, Path] = {}
    for root in roots:
        for directory in discover(root):
            name = run_name(directory)
            if name in seen:
                merged.duplicate_runs.append(f"`{name}` at {directory} (read from {seen[name]})")
                continue
            seen[name] = directory
            one = load(directory, run=name)
            merged.calls += one.calls
            merged.files += one.files
            merged.invalid_lines += one.invalid_lines
    if since is not None:
        kept = [c for c in merged.calls if c.end >= since]
        merged.before_since = len(merged.calls) - len(kept)
        merged.calls = kept
    merged.calls.sort(key=lambda c: c.end)
    first_start: dict[str, datetime] = {}
    for call in merged.calls:
        first_start[call.run] = min(first_start.get(call.run, call.start), call.start)
    merged.runs = sorted(first_start, key=first_start.__getitem__)
    return merged


def _parse(text: str, *, run: str = "") -> Call | None:
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
            run=run,
        )
    except (ValueError, KeyError, TypeError, AttributeError):
        return None


_SINCE = re.compile(r"(\d+)([mhdw])")
_SINCE_UNITS = {"m": "minutes", "h": "hours", "d": "days", "w": "weeks"}


def parse_since(text: str, *, now: datetime | None = None) -> datetime:
    """`7d`, `24h`, `30m`, `2w` back from now, or an ISO date/time (UTC if it
    names no zone)."""
    match = _SINCE.fullmatch(text.strip())
    if match:
        now = now if now is not None else datetime.now(UTC)
        return now - timedelta(**{_SINCE_UNITS[match[2]]: int(match[1])})
    try:
        when = datetime.fromisoformat(text.strip())
    except ValueError:
        raise ValueError(f"--since takes 7d, 24h, 30m, 2w or a date, not {text!r}") from None
    return when if when.tzinfo is not None else when.replace(tzinfo=UTC)


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

    @property
    def cpu_complete(self) -> bool:
        return self.cpu_measured == self.cpu_applicable

    @property
    def usd_complete(self) -> bool:
        return self.priced == self.llm_calls

    def latency(self) -> tuple[float, float, float]:
        """p50, p95 and max, in ms."""
        durations = sorted(self.durations)
        return percentile(durations, 50), percentile(durations, 95), max(durations, default=0.0)

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


def _usd(value: float) -> str:
    return f"${value:,.2f}" if value >= 1 else f"${value:.4f}"


def _share(part: float, whole: float) -> str:
    return f"{100 * part / whole:.0f}%" if whole > 0 else "-"


# --- items --------------------------------------------------------------------------


def items_of(calls: Iterable[Call]) -> dict[tuple[str, str], list[Call]]:
    """Calls by item, an item being (run, item_ref); calls with no item left out."""
    items: dict[tuple[str, str], list[Call]] = defaultdict(list)
    for call in calls:
        if call.item is not None:
            items[(call.run, call.item)].append(call)
    return items


def item_type(group: Iterable[Call]) -> str:
    """What the item was, read off which local models ran on it: `audio`
    (ASR), `scan` (OCR), both, or `text` (neither)."""
    kinds = {c.kind for c in group}
    parts = [
        name for kind, name in ((CallKind.ASR, "audio"), (CallKind.OCR, "scan")) if kind in kinds
    ]
    return " + ".join(parts) or "text"


def _span_ms(group: Sequence[Call]) -> float:
    return (max(c.end for c in group) - min(c.start for c in group)).total_seconds() * 1000


@dataclass
class ItemStats:
    """Per-item measures over a set of items. An item with an unpriced LLM
    call (or an unmeasured CPU) has a known part only, and is counted."""

    usd: list[float] = field(default_factory=list)
    usd_lower: int = 0
    cpu: list[float] = field(default_factory=list)
    cpu_lower: int = 0
    spans: list[float] = field(default_factory=list)

    def add(self, group: Sequence[Call]) -> None:
        t = _totals(_own_time(group))
        self.usd.append(t.usd)
        self.usd_lower += not t.usd_complete
        self.cpu.append(t.cpu_s)
        self.cpu_lower += not t.cpu_complete
        self.spans.append(_span_ms(group))

    @property
    def count(self) -> int:
        return len(self.spans)

    def usd_text(self) -> str:
        bound = ">=" if self.usd_lower else ""
        return (
            f"{bound}{_usd(statistics.median(self.usd))} / {bound}{_usd(statistics.mean(self.usd))}"
        )

    def cpu_text(self) -> str:
        bound = ">=" if self.cpu_lower else ""
        return f"{bound}{statistics.median(self.cpu):.2f} / {bound}{statistics.mean(self.cpu):.2f}"

    def span_text(self) -> str:
        return f"{_s(statistics.median(self.spans))} / {_s(max(self.spans))}"

    def projection_text(self, items: int = PROJECTION_ITEMS) -> str:
        usd = statistics.mean(self.usd) * items
        cpu_hours = statistics.mean(self.cpu) * items / 3600
        return (
            f"{'>=' if self.usd_lower else ''}{_usd(usd)} and "
            f"{'>=' if self.cpu_lower else ''}{cpu_hours:,.1f} CPU-hours"
        )


def _item_stats(groups: Iterable[Sequence[Call]]) -> ItemStats:
    stats = ItemStats()
    for group in groups:
        stats.add(group)
    return stats


# --- the report ---------------------------------------------------------------------


def render(loaded: Loaded, *, title: str) -> str:
    calls = loaded.calls
    out: list[str] = [f"# {title}", ""]
    out += _what_was_read(loaded)
    if not calls:
        out += ["No calls recorded.", ""]
        return "\n".join(out)
    out += _summary(calls, runs=len(loaded.runs))
    out += _cost_by_stage(calls)
    out += _by_stage(calls)
    out += _by_model(calls)
    out += _scoring(calls)
    out += _by_item(calls, many_runs=len(loaded.runs) > 1)
    out += _runs_compared(calls, loaded.runs)
    out += _reliability(loaded)
    out += _notes()
    return "\n".join(out)


def _what_was_read(loaded: Loaded) -> list[str]:
    calls = loaded.calls
    processes = sorted({(c.process, c.pid) for c in calls})
    runs = loaded.runs
    out = ["## What was read", ""]
    if len(runs) == 1:
        out.append(f"- Run: `{runs[0]}`")
    elif runs:
        shown = ", ".join(f"`{r}`" for r in runs[:10]) + (", ..." if len(runs) > 10 else "")
        out.append(f"- Runs: {len(runs)} -- {shown}")
    if len(processes) <= 6:
        out.append(
            f"- Files: {len(loaded.files)} -- "
            + ", ".join(f"`{p}` (pid {pid})" for p, pid in processes)
        )
    else:
        out.append(f"- Files: {len(loaded.files)}, from {len(processes)} processes")
    out.append(
        f"- Calls recorded: {len(calls)}; lines not read (not `{SCHEMA}`): {loaded.invalid_lines}"
    )
    if loaded.since is not None:
        out.append(
            f"- Since {loaded.since.isoformat(timespec='seconds')}: "
            f"{loaded.before_since} earlier call(s) left out"
        )
    if calls:
        out.append(
            f"- From {calls[0].start.isoformat(timespec='seconds')} "
            f"to {calls[-1].end.isoformat(timespec='seconds')}"
        )
    return [*out, ""]


def _summary(calls: Sequence[Call], *, runs: int) -> list[str]:
    whole = _totals(_own_time(calls))
    items = items_of(calls)
    by_type: dict[str, list[list[Call]]] = defaultdict(list)
    for group in items.values():
        by_type[item_type(group)].append(group)
    out = [
        "## Totals and cost per item",
        "",
        (
            f"{len(items)} item(s) in {runs} run(s). Totals: **{whole.usd_text()}** in LLM calls, "
            f"**{whole.cpu_text()} CPU seconds** of local compute, {_s(whole.total_ms)} of wall "
            "time in calls (Chain requests inside a score counted once, in the score)."
        ),
        "",
    ]
    if not items:
        return [*out, "No call is attributed to an item, so there is no per-item figure.", ""]
    out += [
        (
            "Per item: the LLM dollars and the local CPU seconds of its calls, never added "
            "together. Item type is read off which local models ran on it. "
            f"The last column is the mean times {PROJECTION_ITEMS:,}."
        ),
        "",
        (
            "| Items | Count | USD per item, median / mean | CPU s per item, median / mean | "
            f"Span, median / max | {PROJECTION_ITEMS:,} such items |"
        ),
        "|---|---|---|---|---|---|",
    ]
    rows = sorted(by_type.items(), key=lambda kv: -len(kv[1]))
    rows.append(("**all items**", list(items.values())))
    for name, groups in rows:
        s = _item_stats(groups)
        out.append(
            f"| {name} | {s.count} | {s.usd_text()} | {s.cpu_text()} | {s.span_text()} | "
            f"{s.projection_text()} |"
        )
    all_items = _item_stats(items.values())
    out += [
        "",
        (
            ">= marks a lower bound: "
            f"{all_items.usd_lower} item(s) have an LLM call with no price, "
            f"{all_items.cpu_lower} a local call whose CPU was not measured. "
            f"The {PROJECTION_ITEMS:,}-item figure assumes this data's mix of item types; "
            "a different mix costs differently -- the per-type rows say how."
        ),
        "",
    ]
    return out


#: Clock slack when matching a Chain request to the score around it: a record's
#: end is the wall clock when it was recorded, its duration a monotonic clock.
_NESTING_SLACK = timedelta(milliseconds=5)


def nested_in_a_score(calls: Sequence[Call]) -> set[int]:
    """The `chain` calls (by index into `calls`) made inside a score: same
    run, process and task, and within the score's window. They break the
    score down; every other call is time of its own."""
    scores: dict[tuple[str, str, int, str], list[Call]] = defaultdict(list)
    for call in calls:
        if call.kind == CallKind.SCORING and call.task is not None:
            scores[(call.run, call.process, call.pid, call.task)].append(call)
    nested: set[int] = set()
    for index, call in enumerate(calls):
        if call.kind != CallKind.CHAIN or call.task is None:
            continue
        for score in scores.get((call.run, call.process, call.pid, call.task), ()):
            if (
                score.start - _NESTING_SLACK <= call.start
                and call.end <= score.end + _NESTING_SLACK
            ):
                nested.add(index)
                break
    return nested


def _own_time(calls: Sequence[Call]) -> list[Call]:
    """`calls` without the Chain requests already inside a score in them."""
    nested = nested_in_a_score(calls)
    return [c for i, c in enumerate(calls) if i not in nested]


def _cost_by_stage(calls: Sequence[Call]) -> list[str]:
    own = _own_time(calls)
    whole = _totals(own)
    groups: dict[str, list[Call]] = defaultdict(list)
    for call in own:
        groups[call.stage or "(none)"].append(call)
    out = [
        "## Where the cost goes, by stage",
        "",
        (
            "Each stage's share of the measured total: dollars of the priced LLM calls, CPU "
            "seconds of the measured local calls, wall time of every call. Chain requests inside "
            "a score are in the score's row only."
        ),
        "",
        "| Stage | Calls | USD | % of USD | CPU s | % of CPU | Wall time | % of wall |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for stage, group in sorted(groups.items(), key=lambda kv: -_totals(kv[1]).total_ms):
        t = _totals(group)
        out.append(
            f"| {stage} | {t.count} | {t.usd_text()} | "
            f"{_share(t.usd, whole.usd) if t.llm_calls else '-'} | {t.cpu_text()} | "
            f"{_share(t.cpu_s, whole.cpu_s) if t.cpu_applicable else '-'} | "
            f"{_s(t.total_ms)} | {_share(t.total_ms, whole.total_ms)} |"
        )
    return [*out, ""]


def _by_stage(calls: Sequence[Call]) -> list[str]:
    groups: dict[tuple[str, str, str], list[Call]] = defaultdict(list)
    for call in calls:
        groups[(call.stage or "(none)", call.kind or "-", call.model_name)].append(call)
    nested = len(nested_in_a_score(calls))
    out = [
        "## Latency and cost, by stage and call",
        "",
        (
            "Wall time per call. A `chain` row made inside a score (same task, inside its "
            f"window: {nested} call(s) here) breaks that score down and is not added again "
            "below; the others are."
        ),
        "",
        (
            "| Stage | Kind | Call | Calls | Errors | Degraded | p50 ms | p95 ms | Max ms | Total | "
            "CPU s | USD | Tokens in / out |"
        ),
        "|---|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for (stage, kind, name), group in sorted(
        groups.items(), key=lambda kv: -_totals(kv[1]).total_ms
    ):
        t = _totals(group)
        p50, p95, top = t.latency()
        out.append(
            f"| {stage} | {kind} | `{name}` | {t.count} | {t.errors} | {t.degraded} | "
            f"{_ms(p50)} | {_ms(p95)} | {_ms(top)} | "
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


def _by_model(calls: Sequence[Call]) -> list[str]:
    groups: dict[tuple[str, str], list[Call]] = defaultdict(list)
    for call in calls:
        groups[(call.model_name, call.kind or "-")].append(call)
    out = [
        "## By model",
        "",
        (
            "Every call of a model, whatever stage made it. Per call: over the calls the "
            "measure is known for."
        ),
        "",
        (
            "| Call | Kind | Calls | Errors | p50 ms | p95 ms | Max ms | CPU s | CPU s per call | "
            "USD | USD per call | Tokens in / out |"
        ),
        "|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for (name, kind), group in sorted(groups.items(), key=lambda kv: -_totals(kv[1]).total_ms):
        t = _totals(group)
        p50, p95, top = t.latency()
        cpu_each = f"{t.cpu_s / t.cpu_measured:.2f}" if t.cpu_measured else "n/a"
        usd_each = _usd(t.usd / t.priced) if t.priced else "n/a"
        out.append(
            f"| `{name}` | {kind} | {t.count} | {t.errors} | {_ms(p50)} | {_ms(p95)} | "
            f"{_ms(top)} | {t.cpu_text()} | {cpu_each} | {t.usd_text()} | {usd_each} | "
            f"{t.tokens_text()} |"
        )
    return [*out, ""]


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


def _by_item(calls: Sequence[Call], *, many_runs: bool) -> list[str]:
    items = items_of(calls)
    unattributed = [c for c in calls if c.item is None]
    out = [
        "## Per item",
        "",
        (
            "Item: `item_ref` (a digest of tenant and source_ref, observability/context.py). "
            "Span: first call's start to last call's end, waits between stages included."
        ),
        "",
    ]
    if len(items) > _ITEM_TABLE_MAX_ROWS:
        out += [
            (
                f"{len(items)} items: too many for a row each; the per-type figures above "
                "stand for them."
            ),
            "",
        ]
    else:
        run_cell = "Run | " if many_runs else ""
        out += [
            (
                f"| {run_cell}Item | Type | Tasks | Calls | Span | Sum of calls | to_text | "
                "extraction | duplicate_check | task_created | score | CPU s | USD | "
                "Degraded scores |"
            ),
            "|---" * (14 + many_runs) + "|",
        ]
        stages = ("to_text", "extraction", "duplicate_check", "task_created", "score")
        for (run, item), group in sorted(items.items(), key=lambda kv: kv[1][0].start):
            own = _own_time(group)
            t = _totals(own)
            by_stage = {s: _totals(c for c in own if c.stage == s) for s in stages}
            cells = [_s(by_stage[s].total_ms) if by_stage[s].count else "-" for s in stages]
            tasks = len({c.task for c in group if c.task is not None})
            degraded = sum(c.degraded for c in group if c.kind == CallKind.SCORING)
            out.append(
                f"| {f'`{run}` | ' if many_runs else ''}`{item}` | {item_type(group)} | "
                f"{tasks} | {len(own)} | {_s(_span_ms(group))} | {_s(t.total_ms)} | "
                + " | ".join(cells)
                + f" | {t.cpu_text()} | {t.usd_text()} | {degraded} |"
            )
        out.append("")
    out += [
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


def _p95_of(calls: Iterable[Call], kind: str) -> str:
    durations = sorted(c.duration_ms for c in calls if c.kind == kind)
    return _ms(percentile(durations, 95)) if durations else "-"


def _runs_compared(calls: Sequence[Call], runs: Sequence[str]) -> list[str]:
    if len(runs) < 2:
        return []
    by_run: dict[str, list[Call]] = defaultdict(list)
    for call in calls:
        by_run[call.run].append(call)
    kinds = (CallKind.ASR, CallKind.OCR, CallKind.LLM, CallKind.SCORING)
    out = [
        "## Runs compared",
        "",
        (
            "One row per run, oldest first: did a change help or hurt? Per item is the mean "
            "over the run's items; compare runs with the same mix of items (the same demo "
            "order) -- an audio item costs many times a text one."
        ),
        "",
        (
            "| Run | Started | Items | Calls | Errors | Degraded scores | USD per item | "
            "CPU s per item | " + " | ".join(f"p95 {k} ms" for k in kinds) + " |"
        ),
        "|---" * (8 + len(kinds)) + "|",
    ]
    for run in runs:
        group = by_run[run]
        t = _totals(group)
        items = items_of(group)
        if items:
            s = _item_stats(items.values())
            usd = f"{'>=' if s.usd_lower else ''}{_usd(statistics.mean(s.usd))}"
            cpu = f"{'>=' if s.cpu_lower else ''}{statistics.mean(s.cpu):.2f}"
        else:
            usd = cpu = "-"
        degraded = sum(c.degraded for c in group if c.kind == CallKind.SCORING)
        started = min(c.start for c in group).isoformat(timespec="minutes")
        out.append(
            f"| `{run}` | {started} | {len(items)} | {t.count} | {t.errors} | {degraded} | "
            f"{usd} | {cpu} | " + " | ".join(_p95_of(group, k) for k in kinds) + " |"
        )
    return [*out, ""]


def _reliability(loaded: Loaded) -> list[str]:
    calls = loaded.calls
    seen = {c.kind for c in calls}
    missing = [label for kind, label in EXPECTED_KINDS.items() if kind not in seen]
    out = ["## How reliable these numbers are", "", "### Not measured", ""]
    if missing:
        out += [f"- {label}: no record -- **not measured, not zero**" for label in missing]
    else:
        out.append("- Every expected stage has records.")
    unmeasured_cpu = sum(1 for c in calls if c.kind in _CPU_KINDS and c.cpu_s is None)
    unpriced = sum(1 for c in calls if c.kind == CallKind.LLM and c.cost_usd is None)
    unreported = sum(
        1
        for c in calls
        if c.kind == CallKind.LLM and (c.prompt_tokens is None or c.completion_tokens is None)
    )
    out += [
        f"- Calls whose CPU seconds were not measured: {unmeasured_cpu}"
        + (" (OCR on Windows reports no child-process CPU)" if unmeasured_cpu else ""),
        f"- LLM calls with no dollar price: {unpriced}",
        f"- LLM calls with no token counts: {unreported}",
        "",
        "### Read with care",
        "",
    ]
    degraded = sum(c.degraded for c in calls if c.kind == CallKind.SCORING)
    scores = sum(c.kind == CallKind.SCORING for c in calls)
    if degraded:
        out.append(
            f"- {degraded} of {scores} scores are degraded (Chain could not be asked): their "
            "latency and CPU are the fallback path's, not full scoring's."
        )
    small: dict[tuple[str, str], int] = defaultdict(int)
    for call in calls:
        small[(call.stage or "(none)", call.model_name)] += 1
    thin = sorted(
        f"{stage}/`{name}` ({n})" for (stage, name), n in small.items() if n < _SMALL_SAMPLE
    )
    if thin:
        out.append(
            f"- Fewer than {_SMALL_SAMPLE} calls, so p95 is close to the max and says little "
            "on its own: " + ", ".join(thin)
        )
    no_item = sum(c.item is None for c in calls)
    if no_item:
        out.append(
            f"- {no_item} call(s) have no item (the rescore worker, among others): in the "
            "totals, not in any per-item figure."
        )
    if loaded.invalid_lines:
        out.append(f"- {loaded.invalid_lines} line(s) were not `{SCHEMA}` and were not read.")
    if loaded.duplicate_runs:
        out.append("- Found twice and read once: " + "; ".join(loaded.duplicate_runs))
    if loaded.since is not None:
        out.append(
            f"- `--since` cuts by when a call was recorded: a run that started before "
            f"{loaded.since.isoformat(timespec='seconds')} is only partly in."
        )
    out.append(
        f"- The {PROJECTION_ITEMS:,}-item figure is the mean per item times {PROJECTION_ITEMS:,}: "
        "an estimate at this data's mix of item types, not a measurement."
    )
    return [*out, ""]


def _notes() -> list[str]:
    return [
        "## How to read it",
        "",
        (
            "- Dollars are LLM calls only, at the provider's price per token; local work costs CPU "
            "seconds -- machine time, not a bill -- never converted to dollars. The two are "
            "never added."
        ),
        "- A total beside an `unpriced` / `unmeasured` count, or marked >=, is a lower bound.",
        (
            "- CPU seconds: OCR/ASR are process-wide (Tesseract and Whisper use several cores, "
            "so CPU can exceed wall time); scoring is its own thread's, which Windows counts "
            "in 15.6 ms steps -- a 0.00 there is below that, not free."
        ),
        (
            "- Not recorded at all: Kafka, MinIO, Inbox and the time between stages -- they show "
            "only in an item's span."
        ),
        (
            "- Every line of the JSONL files is read: the in-memory ring buffer (last 10,000 "
            "calls) is not used, so a long run is not cut to its tail."
        ),
        "",
    ]


def _since_arg(text: str) -> datetime:
    try:
        return parse_since(text)
    except ValueError as error:
        raise argparse.ArgumentTypeError(str(error)) from None


def _default_roots(error: Callable[[str], object]) -> list[Path]:
    configured = os.environ.get("MIND_MONITORING__CALLS_DIR")
    if configured:
        return [Path(configured)]
    if DEFAULT_RUNS_DIR.is_dir():
        return [DEFAULT_RUNS_DIR]
    error(
        f"no DIR given, MIND_MONITORING__CALLS_DIR is not set, and there is no "
        f"{DEFAULT_RUNS_DIR} here (run it from teams/mind/)"
    )
    return []


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m mind.observability.report", description=__doc__.split("\n\n")[0]
    )
    parser.add_argument(
        "dirs",
        nargs="*",
        type=Path,
        metavar="DIR",
        help=(
            "a run's directory of *.jsonl, or a directory of runs (default: "
            f"$MIND_MONITORING__CALLS_DIR, else {DEFAULT_RUNS_DIR})"
        ),
    )
    parser.add_argument(
        "--since",
        type=_since_arg,
        help="only calls recorded in the last 7d / 24h / 30m / 2w, or since a date",
    )
    parser.add_argument("--out", type=Path, help="write the report here instead of stdout")
    parser.add_argument("--title", default="Mind: per-stage latency and cost")
    args = parser.parse_args(argv)
    roots = args.dirs or _default_roots(parser.error)
    for root in roots:
        if not root.is_dir():
            parser.error(f"{root} is not a directory")
    text = render(load_runs(roots, since=args.since), title=args.title)
    if args.out is not None:
        args.out.write_text(text, encoding="utf-8")
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
