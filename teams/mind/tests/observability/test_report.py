"""A run's report from its JSONL files (src/mind/observability/report.py) -- #482, #484.

What the report must never do: present something it did not measure as zero,
add dollars to CPU seconds, add Chain requests to the scores they are inside,
or present degraded scores as the full scoring path.
"""

import json
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from mind.observability import report
from mind.observability.context import item_ref
from mind.observability.sink import to_json_line
from mind.observability.tracking import CallKind, CallStatus, ModelCallRecord

T0 = 1_792_900_000.0  # a fixed epoch second, so spans are exact
ITEM = item_ref("550e8400-e29b-41d4-a716-446655440000", "slack:C1/1")
TASK = "4d0c6f2a-8b1e-4c3d-9a7f-1e2b3c4d5e6f"
OTHER_TASK = "9e8d7c6b-5a49-4382-a716-2f1e0d9c8b7a"


def rec(
    name: str,
    kind: CallKind,
    duration_ms: float,
    at: float,
    *,
    stage: str | None = None,
    item: str | None = ITEM,
    task: str | None = None,
    **fields: object,
) -> ModelCallRecord:
    return ModelCallRecord(
        name,
        duration_ms,
        fields.pop("cost", None),  # type: ignore[arg-type]
        kind=kind,
        item=item,
        task=task,
        stage=stage,
        timestamp=T0 + at,
        **fields,  # type: ignore[arg-type]
    )


def write(directory: Path, process: str, records: list[ModelCallRecord]) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / f"model-calls-{process}-1.jsonl").open("a", encoding="utf-8") as f:
        for r in records:
            f.write(to_json_line(r, process=process, pid=1))


def a_run(directory: Path, *, degraded: bool = True) -> None:
    """One item, as the demo makes it: landed runner and API, two files."""
    write(
        directory,
        "mind-landed",
        [
            rec("whisper", CallKind.ASR, 14_000, 14.0, stage="to_text", cpu_s=37.0),
            rec("tesseract", CallKind.OCR, 1_600, 16.0, stage="to_text", cpu_s=None),
            rec(
                "gemini",
                CallKind.LLM,
                2_000,
                18.0,
                stage="extraction",
                cost=0.003,
                prompt_tokens=3600,
                completion_tokens=190,
            ),
            rec("gemini", CallKind.LLM, 1_000, 19.0, stage="extraction"),  # unpriced
        ],
    )
    write(
        directory,
        "mind-api",
        [
            rec(
                # Inside the score: 29.80-29.95 s, the score 29.75-30.00 s.
                "chain-ancestors",
                CallKind.CHAIN,
                150,
                29.95,
                stage="score",
                task=TASK,
                status=CallStatus.ERROR if degraded else CallStatus.SUCCESS,
            ),
            rec(
                "hierarchical-ranking",
                CallKind.SCORING,
                250,
                30.0,
                stage="score",
                task=TASK,
                cpu_s=0.01,
                degraded=degraded,
            ),
            rec(
                # After it: the rescore cascade's lookup, 30.05-31.20 s.
                "chain-children",
                CallKind.CHAIN,
                1_150,
                31.2,
                stage="score",
                task=TASK,
                status=CallStatus.ERROR if degraded else CallStatus.SUCCESS,
            ),
        ],
    )


def rendered(directory: Path) -> str:
    return report.render(report.load(directory), title="run")


def test_both_processes_files_are_read(tmp_path: Path):
    a_run(tmp_path)
    loaded = report.load(tmp_path)
    assert len(loaded.files) == 2 and len(loaded.calls) == 7
    assert {c.process for c in loaded.calls} == {"mind-landed", "mind-api"}


def test_a_stage_with_no_record_is_not_measured_not_zero(tmp_path: Path):
    a_run(tmp_path)
    text = rendered(tmp_path)
    section = text.split("### Not measured")[1].split("### Read with care")[0]
    assert "embedding" in section and "vector query" in section
    assert "not measured, not zero" in section
    assert "ASR" not in section.split("- Calls whose")[0]  # measured, so not listed


def test_known_and_unknown_costs_are_kept_apart(tmp_path: Path):
    a_run(tmp_path)
    text = rendered(tmp_path)
    # $0.003 priced + one call with no price: a lower bound, never "$0.003".
    assert "$0.0030 + 1 unpriced (lower bound)" in text
    # OCR's CPU was not measured: counted beside ASR's 37 s, not added as 0.
    assert "unknown (1 unmeasured)" in text
    assert "LLM calls with no dollar price: 1" in text


def test_dollars_and_cpu_seconds_are_never_added(tmp_path: Path):
    a_run(tmp_path)
    [all_calls] = [line for line in rendered(tmp_path).splitlines() if "**All calls**" in line]
    assert "37.01 + 1 unmeasured CPU seconds" in all_calls
    assert "$0.0030 + 1 unpriced (lower bound) in LLM calls" in all_calls


def test_a_chain_request_inside_a_score_is_counted_once_and_one_after_it_is_its_own(
    tmp_path: Path,
):
    a_run(tmp_path)
    text = rendered(tmp_path)
    [all_calls] = [line for line in text.splitlines() if "**All calls**" in line]
    # 14000 + 1600 + 2000 + 1000 + 250 + 1150: the /ancestors request is inside
    # the score's 250 ms, the /children one after it is time of its own.
    assert "20.0 s of wall time" in all_calls
    assert "made inside a score: 1, after it (the rescore cascade): 1" in text


def test_nesting_is_read_off_the_times_not_the_kind():
    loaded = [
        report._parse(to_json_line(r, process="mind-api", pid=1))
        for r in (
            rec("hierarchical-ranking", CallKind.SCORING, 250, 30.0, stage="score", task=TASK),
            rec("chain-ancestors", CallKind.CHAIN, 150, 29.95, stage="score", task=TASK),
            rec("chain-children", CallKind.CHAIN, 1_150, 31.2, stage="score", task=TASK),
            # Same window, another task: not inside this score.
            rec("chain-ancestors", CallKind.CHAIN, 150, 29.95, stage="score", task=OTHER_TASK),
        )
    ]
    calls = [c for c in loaded if c is not None]
    assert report.nested_in_a_score(calls) == {1}


def test_a_run_where_every_score_is_degraded_says_so(tmp_path: Path):
    a_run(tmp_path, degraded=True)
    text = rendered(tmp_path)
    assert "**full path: 0**" in text and "**degraded (Chain could not be asked): 1**" in text
    assert "Every score in this run is degraded" in text
    assert "It is not a measurement of full scoring." in text
    assert "Chain requests: 2, of which failed: 2" in text


def test_a_run_with_full_scores_has_no_degraded_warning(tmp_path: Path):
    a_run(tmp_path, degraded=False)
    text = rendered(tmp_path)
    assert "**full path: 1**" in text
    assert "**degraded (Chain could not be asked): 0**" in text
    assert "Every score in this run is degraded" not in text
    assert "scores are degraded" not in text


def test_an_item_spans_from_its_first_call_to_its_last_across_processes(tmp_path: Path):
    a_run(tmp_path)
    [row] = [line for line in rendered(tmp_path).splitlines() if line.startswith(f"| `{ITEM}`")]
    cells = [c.strip() for c in row.strip("|").split("|")]
    # First call starts at 0 s (14 s ASR ending at 14 s), last ends at 31.2 s.
    assert cells[1:6] == ["audio + scan", "1", "6", "31.2 s", "20.0 s"]
    assert cells[6:11] == ["15.6 s", "3.0 s", "-", "-", "1.4 s"]  # to_text .. score
    assert cells[-1] == "1"  # one degraded score


def test_calls_with_no_item_are_counted_with_where_they_came_from(tmp_path: Path):
    write(tmp_path, "mind-api", [rec("hierarchical-ranking", CallKind.SCORING, 5, 1.0, item=None)])
    assert "Calls with no item: 1 -- scoring/no stage" in rendered(tmp_path)


def test_a_line_that_is_not_the_format_is_counted_not_read(tmp_path: Path):
    a_run(tmp_path)
    with (tmp_path / "model-calls-mind-api-1.jsonl").open("a", encoding="utf-8") as f:
        f.write("not json\n")
        f.write(json.dumps({"schema": "something/else"}) + "\n")
    loaded = report.load(tmp_path)
    assert (len(loaded.calls), loaded.invalid_lines) == (7, 2)


def test_no_calls_is_said_plainly(tmp_path: Path):
    assert "No calls recorded." in rendered(tmp_path)


def test_the_command_writes_the_report(tmp_path: Path):
    a_run(tmp_path / "calls")
    out = tmp_path / "report.md"
    assert report.main([str(tmp_path / "calls"), "--out", str(out)]) == 0
    assert out.read_text(encoding="utf-8").startswith("# Mind: per-stage latency and cost")


def test_the_command_needs_a_directory(monkeypatch: pytest.MonkeyPatch, tmp_path: Path):
    monkeypatch.delenv("MIND_MONITORING__CALLS_DIR", raising=False)
    monkeypatch.chdir(tmp_path)  # no demo_flow/runs here
    with pytest.raises(SystemExit):
        report.main([])


# --- #484: many runs, --since, per model, per item, runs compared ------------------


def simple_item(
    directory: Path, item: str, at: float, *, usd: float, cpu: float | None = None
) -> None:
    """One item: an LLM call at `usd`, and an ASR call at `cpu` CPU seconds if given."""
    records = [rec("gemini", CallKind.LLM, 2_000, at, stage="extraction", item=item, cost=usd)]
    if cpu is not None:
        records.insert(
            0, rec("whisper", CallKind.ASR, 10_000, at - 2.0, stage="to_text", item=item, cpu_s=cpu)
        )
    write(directory, "mind-landed", records)


def row(text: str, first_cell: str) -> list[str]:
    [line] = [line for line in text.splitlines() if line.startswith(f"| {first_cell} |")]
    return [c.strip() for c in line.strip("|").split("|")]


def test_a_directory_of_runs_reads_each_run_named_after_its_folder(tmp_path: Path):
    a_run(tmp_path / "20261026T110028Z-natural" / "calls")
    a_run(tmp_path / "20261026T115606Z-slack-first" / "calls")
    loaded = report.load_runs([tmp_path])
    assert loaded.runs == ["20261026T110028Z-natural", "20261026T115606Z-slack-first"]
    assert len(loaded.calls) == 14 and len(loaded.files) == 4


def test_a_run_found_twice_is_read_once_and_said(tmp_path: Path):
    a_run(tmp_path / "demo" / "run-1" / "calls")
    a_run(tmp_path / "benchmarks" / "run-1" / "calls")  # the same run, copied
    loaded = report.load_runs([tmp_path / "benchmarks", tmp_path / "demo"])
    assert loaded.runs == ["run-1"] and len(loaded.calls) == 7
    assert "Found twice and read once: `run-1`" in report.render(loaded, title="t")


def test_the_same_item_in_two_runs_is_two_items(tmp_path: Path):
    a_run(tmp_path / "run-1")
    a_run(tmp_path / "run-2")
    calls = report.load_runs([tmp_path]).calls
    assert set(report.items_of(calls)) == {("run-1", ITEM), ("run-2", ITEM)}


@pytest.mark.parametrize(
    ("text", "back"),
    [
        ("30m", timedelta(minutes=30)),
        ("24h", timedelta(hours=24)),
        ("7d", timedelta(days=7)),
        ("2w", timedelta(weeks=2)),
    ],
)
def test_since_counts_back_from_now(text: str, back: timedelta):
    now = datetime(2026, 10, 26, 12, tzinfo=UTC)
    assert report.parse_since(text, now=now) == now - back


def test_since_takes_a_date_in_utc_unless_it_names_a_zone():
    assert report.parse_since("2026-10-20") == datetime(2026, 10, 20, tzinfo=UTC)
    assert report.parse_since("2026-10-20T10:00+02:00") == datetime(2026, 10, 20, 8, tzinfo=UTC)
    with pytest.raises(ValueError, match="7d, 24h"):
        report.parse_since("last week")


def test_since_leaves_earlier_calls_out_and_counts_them(tmp_path: Path):
    a_run(tmp_path)  # the landed runner's calls end by T0+19 s, the API's after 29 s
    loaded = report.load_runs([tmp_path], since=datetime.fromtimestamp(T0 + 25.0, UTC))
    assert (len(loaded.calls), loaded.before_since) == (3, 4)
    assert "4 earlier call(s) left out" in report.render(loaded, title="t")


def test_the_command_takes_since(tmp_path: Path):
    a_run(tmp_path / "calls")
    out = tmp_path / "report.md"
    assert report.main([str(tmp_path / "calls"), "--since", "2026-01-01", "--out", str(out)]) == 0
    assert "0 earlier call(s) left out" in out.read_text(encoding="utf-8")
    with pytest.raises(SystemExit):
        report.main([str(tmp_path / "calls"), "--since", "yesterday"])


def test_with_no_directory_the_command_reads_the_demo_runs(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
):
    monkeypatch.delenv("MIND_MONITORING__CALLS_DIR", raising=False)
    a_run(tmp_path / "demo_flow" / "runs" / "run-1" / "calls")
    monkeypatch.chdir(tmp_path)
    out = tmp_path / "report.md"
    assert report.main(["--out", str(out)]) == 0
    assert "- Run: `run-1`" in out.read_text(encoding="utf-8")


def test_every_line_is_read_past_the_ring_buffer_size(tmp_path: Path):
    n = 10_050  # the in-memory ring buffer keeps 10,000; the files keep every call
    with (tmp_path / "model-calls-mind-landed-1.jsonl").open("w", encoding="utf-8") as f:
        for i in range(n):
            call = rec("gemini", CallKind.LLM, 1.0, i * 0.01, stage="extraction", cost=0.001)
            f.write(to_json_line(call, process="mind-landed", pid=1))
    loaded = report.load(tmp_path)
    assert len(loaded.calls) == n
    assert f"Calls recorded: {n};" in report.render(loaded, title="t")


def test_latency_has_its_max_beside_p50_and_p95(tmp_path: Path):
    write(
        tmp_path,
        "mind-landed",
        [rec("gemini", CallKind.LLM, ms, i, stage="extraction") for i, ms in enumerate((1, 2, 9))],
    )
    cells = row(rendered(tmp_path), "extraction | llm")
    # Stage, Kind, Call, Calls, Errors, Degraded, p50, p95, Max
    assert cells[3] == "3" and cells[6:9] == ["2.0", "8.3", "9.0"]


def test_by_model_adds_a_models_calls_across_stages(tmp_path: Path):
    write(
        tmp_path,
        "mind-landed",
        [
            rec("gemini", CallKind.LLM, 100, 1, stage="extraction", cost=0.002),
            rec("gemini", CallKind.LLM, 300, 2, stage="landed", cost=0.004),
        ],
    )
    cells = row(rendered(tmp_path).split("## By model")[1], "`gemini`")
    assert cells[1:3] == ["llm", "2"]
    assert cells[9:11] == ["$0.0060", "$0.0030"]  # USD, USD per call


def test_cost_per_item_by_type_and_for_10000_items(tmp_path: Path):
    simple_item(tmp_path, "a1", 10, usd=0.004, cpu=30.0)
    simple_item(tmp_path, "a2", 30, usd=0.006, cpu=42.0)
    simple_item(tmp_path, "t1", 50, usd=0.002)
    text = rendered(tmp_path)
    audio = row(text, "audio")
    assert audio[1:4] == ["2", "$0.0050 / $0.0050", "36.00 / 36.00"]
    assert audio[5] == "$50.00 and 100.0 CPU-hours"  # 10,000 x the mean
    assert row(text, "text")[1:3] == ["1", "$0.0020 / $0.0020"]
    assert row(text, "**all items**")[1:3] == ["3", "$0.0040 / $0.0040"]
    assert "3 item(s) in 1 run(s)" in text


def test_a_per_item_figure_with_a_gap_is_marked_a_lower_bound(tmp_path: Path):
    a_run(tmp_path)  # one unpriced LLM call, one OCR call with no CPU
    cells = row(rendered(tmp_path), "audio + scan")
    assert cells[2] == ">=$0.0030 / >=$0.0030"
    assert cells[3] == ">=37.01 / >=37.01"
    assert cells[5].startswith(">=$30.00 and >=")


def test_item_type_is_read_off_the_local_models_that_ran():
    def calls(*kinds: CallKind) -> list[report.Call]:
        lines = [to_json_line(rec("m", k, 1, 1), process="p", pid=1) for k in kinds]
        return [c for c in map(report._parse, lines) if c is not None]

    assert report.item_type(calls(CallKind.ASR, CallKind.LLM)) == "audio"
    assert report.item_type(calls(CallKind.OCR)) == "scan"
    assert report.item_type(calls(CallKind.LLM, CallKind.SCORING)) == "text"


def test_runs_are_compared_only_when_there_are_several(tmp_path: Path):
    a_run(tmp_path / "run-1")
    assert "## Runs compared" not in report.render(report.load_runs([tmp_path]), title="t")
    a_run(tmp_path / "run-2")
    text = report.render(report.load_runs([tmp_path]), title="t")
    cells = row(text.split("## Runs compared")[1], "`run-1`")
    # Run, Started, Items, Calls, Errors, Degraded, USD/item, CPU/item, p95 asr/ocr/llm/scoring
    assert cells[2:6] == ["1", "7", "2", "1"]
    assert cells[8:] == ["14,000", "1,600", "1,950", "250"]


def test_a_small_sample_is_flagged(tmp_path: Path):
    a_run(tmp_path)
    text = rendered(tmp_path).split("### Read with care")[1]
    assert "Fewer than 20 calls" in text and "to_text/`whisper` (1)" in text


def test_many_items_leave_the_per_item_table_out(tmp_path: Path):
    for i in range(report._ITEM_TABLE_MAX_ROWS + 1):
        simple_item(tmp_path, f"i{i}", 10 + i, usd=0.001)
    assert f"{report._ITEM_TABLE_MAX_ROWS + 1} items: too many for a row each" in rendered(tmp_path)
