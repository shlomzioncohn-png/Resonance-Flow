"""#359: the demo's runner echoes Mind's output on any console."""

import io

from demo_flow.run import echo

#: A progress bar's full block, which the Windows console's cp1255 cannot show.
_BLOCK = "█"


def test_a_character_the_console_cannot_show_is_replaced_not_raised():
    raw = io.BytesIO()
    console = io.TextIOWrapper(raw, encoding="cp1255", newline="\n")

    echo(f"[mind-landed] Loading weights: 100%|{_BLOCK * 2}| 199/199\n", console)

    assert raw.getvalue().decode("cp1255") == "[mind-landed] Loading weights: 100%|??| 199/199\n"


def test_a_line_the_console_can_show_is_written_as_it_is():
    console = io.StringIO()

    echo("[mind-api] score_publisher: started\n", console)

    assert console.getvalue() == "[mind-api] score_publisher: started\n"


# --- #482: each Mind process writes its model calls to the run's calls/ -----------------


def test_each_mind_process_writes_its_calls_to_the_run_under_its_own_name(tmp_path):
    from demo_flow.run import CALLS_DIR_NAME, MindProcess

    env = {"PATH": "x", "MIND_LANDED_CONSUMER__GROUP_ID": "demo-landed-run"}
    landed = MindProcess("mind-landed", "mind.ingestion.landed_consumer_runner")
    api = MindProcess("mind-api", "uvicorn")

    landed_env = landed.monitoring_env(env, tmp_path)
    api_env = api.monitoring_env(env, tmp_path)

    assert landed_env["MIND_MONITORING__CALLS_DIR"] == str(tmp_path / CALLS_DIR_NAME)
    assert api_env["MIND_MONITORING__CALLS_DIR"] == str(tmp_path / CALLS_DIR_NAME)
    assert (
        landed_env["MIND_MONITORING__PROCESS_NAME"],
        api_env["MIND_MONITORING__PROCESS_NAME"],
    ) == (
        "mind-landed",
        "mind-api",
    )
    # Everything else as it was, and the shared env itself untouched.
    assert {k: v for k, v in landed_env.items() if not k.startswith("MIND_MONITORING__")} == env
    assert "MIND_MONITORING__CALLS_DIR" not in env


def test_the_run_ends_with_a_report_of_its_calls(tmp_path):
    from demo_flow.run import CALLS_DIR_NAME, write_report

    from mind.observability.sink import to_json_line
    from mind.observability.tracking import CallKind, ModelCallRecord

    calls = tmp_path / CALLS_DIR_NAME
    calls.mkdir()
    record = ModelCallRecord("whisper", 14_000.0, None, kind=CallKind.ASR, cpu_s=37.0)
    (calls / "model-calls-mind-landed-1.jsonl").write_text(
        to_json_line(record, process="mind-landed", pid=1), encoding="utf-8"
    )

    out = write_report(tmp_path)

    assert out == tmp_path / "report.md"
    assert f"# Demo run {tmp_path.name}" in out.read_text(encoding="utf-8")


def test_a_run_that_recorded_nothing_has_no_report(tmp_path):
    from demo_flow.run import write_report

    assert write_report(tmp_path) is None
    assert not (tmp_path / "report.md").exists()
