"""A run's report from its JSONL files (src/mind/observability/report.py) -- #482.

What the report must never do: present something it did not measure as zero,
add dollars to CPU seconds, add Chain requests to the scores they are inside,
or present degraded scores as the full scoring path.
"""

import json
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
    section = text.split("## Not measured in this run")[1]
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
    assert cells[1:5] == ["1", "6", "31.2 s", "20.0 s"]
    assert cells[5:10] == ["15.6 s", "3.0 s", "-", "-", "1.4 s"]  # to_text .. score
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


def test_the_command_needs_a_directory(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.delenv("MIND_MONITORING__CALLS_DIR", raising=False)
    with pytest.raises(SystemExit):
        report.main([])
