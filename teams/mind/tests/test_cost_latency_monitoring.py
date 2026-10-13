"""Cost and latency monitoring for every model call MIND makes (#479).

The path of this file is the one named in the issue, so its command keeps
working:

    pytest tests/test_cost_latency_monitoring.py -v

These tests go through the real entry points -- ocr.extract_text(),
asr.transcribe(), and BasicExtractor over the real DeepSeek client on a mocked
transport -- and check what lands in the ledger, the summary, and the log.

Cost has a unit per kind: OCR and ASR cost CPU seconds (MIND does not pay for
its machine by the hour, so they have no dollar price), and only LLM calls
cost US dollars, at the provider's price per token. A measure that does not
apply to a kind is reported as not applicable -- a null total *and* a null
count -- which is not the same as unknown (a null total with a count of the
calls that lacked it).

The rules underneath have their own unit tests, not repeated here:

* tests/observability/test_observability.py -- record_call() and its log line
* tests/observability/test_compute.py -- CpuClock
* tests/observability/test_model_call_summary.py -- the per-name aggregate
* tests/observability/test_limit_warnings.py -- each limit rule and warning,
  including the dollar-cost boundary, which needs LLM calls with a kind

LLM calls are R2's (extraction/extractor.py), and recording them with a kind,
token counts and an unpriced cost is a change proposed to her, not made here.
The xfail(strict=True) tests below spell out that change; once it lands they
pass, strict turns them into failures, and the marker comes off.
"""

from __future__ import annotations

import logging
import subprocess
import sys
import time
from collections.abc import Iterator
from types import SimpleNamespace

import pytest
from pydantic import ValidationError

from mind.config import MonitoringSettings
from mind.contracts.base_enums import SourceType
from mind.extraction.extractor import BasicExtractor
from mind.extraction.providers.pricing import estimate_cost
from mind.multimodal import asr, ocr
from mind.multimodal.asr import transcribe
from mind.multimodal.ocr import OCR_MODEL_NAME, extract_text
from mind.observability import tracking
from mind.observability.tracking import (
    CallKind,
    CallNameSummary,
    CallStatus,
    _reset_for_tests,
    get_recent_calls,
    model_call_summary,
)
from tests.extraction.test_deepseek import SOURCE_TEXT, VALID_ANSWER, _always, _envelope, _provider
from tests.test_asr_pipeline import (
    _CLEAN_RECORDING,
    _ROOT,
    FakeWhisper,
    _needs_weights,
    fake_segment,
    tone,
    use_model,
)
from tests.test_ocr_result import png_bytes, tesseract_prints, tsv

_posix_only = pytest.mark.skipif(
    sys.platform == "win32", reason="Windows reports no child-process CPU"
)
_windows_only = pytest.mark.skipif(
    sys.platform != "win32", reason="only Windows lacks child-process CPU"
)

try:
    ocr._find_tesseract()
    _TESSERACT_AVAILABLE = True
except RuntimeError:
    _TESSERACT_AVAILABLE = False
_needs_tesseract = pytest.mark.skipif(
    not _TESSERACT_AVAILABLE, reason="tesseract system binary not installed on this machine"
)


@pytest.fixture(autouse=True)
def _clean_ledger() -> None:
    _reset_for_tests()


@pytest.fixture
def tesseract_found(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(ocr, "_find_tesseract", lambda: "tesseract")


def use_limits(monkeypatch: pytest.MonkeyPatch, **limits: object) -> None:
    monkeypatch.setattr(
        tracking, "monitoring_settings", lambda: MonitoringSettings.model_construct(**limits)
    )


def burn_cpu(seconds: float) -> None:
    started = time.process_time()
    while time.process_time() - started < seconds:
        pass


def tesseract_burns_cpu(monkeypatch: pytest.MonkeyPatch, seconds: float) -> None:
    """Each tesseract run is a real child process using `seconds` of CPU, then reading one word."""
    code = f"import time\nt = time.process_time()\nwhile time.process_time() - t < {seconds}: pass"
    real_run = subprocess.run

    def run(cmd, **kwargs):
        real_run([sys.executable, "-c", code], check=True)
        return subprocess.CompletedProcess(cmd, 0, stdout=tsv(("word", 90.0)), stderr=b"")

    monkeypatch.setattr(subprocess, "run", run)


def ocr_takes(monkeypatch: pytest.MonkeyPatch, seconds: float) -> None:
    """Make the OCR call's wall clock read exactly `seconds` between start and end."""
    readings: Iterator[float] = iter([1000.0, 1000.0 + seconds])
    monkeypatch.setattr(ocr, "time", SimpleNamespace(perf_counter=lambda: next(readings)))


class BusyWhisper(FakeWhisper):
    """A fake Whisper that, like the real one, spends CPU in this process."""

    def transcribe(self, audio, **kwargs):
        burn_cpu(0.2)
        return super().transcribe(audio, **kwargs)


def one_ocr_call() -> None:
    assert extract_text(png_bytes(), filename="a.png").success


def one_asr_call(monkeypatch: pytest.MonkeyPatch, model: FakeWhisper | None = None) -> None:
    use_model(monkeypatch, model or FakeWhisper([fake_segment(" Hi.", 0.0, 1.0)]))
    assert transcribe(tone(1.0)).success


def llm_call(usage: dict[str, int] | None, model: str = "deepseek-chat") -> None:
    handler, _ = _always(_envelope(VALID_ANSWER, model=model, usage=usage))
    BasicExtractor(_provider(handler), sleep=lambda _: None).extract(SOURCE_TEXT, SourceType.EMAIL)


def row(call_name: str) -> CallNameSummary:
    [found] = [r for r in model_call_summary().by_call_name if r.call_name == call_name]
    return found


def warnings(caplog: pytest.LogCaptureFixture, marker: str) -> list[str]:
    return [
        r.getMessage()
        for r in caplog.records
        if r.levelno == logging.WARNING and r.getMessage().startswith(marker)
    ]


# --- every OCR call records its latency and its CPU seconds ------------------------


@pytest.mark.usefixtures("tesseract_found")
def test_an_ocr_call_is_recorded_with_its_latency(monkeypatch: pytest.MonkeyPatch):
    tesseract_prints(monkeypatch, tsv(("word", 90.0)))
    one_ocr_call()
    [call] = get_recent_calls()
    assert (call.model_name, call.kind, call.status) == (
        OCR_MODEL_NAME,
        CallKind.OCR,
        CallStatus.SUCCESS,
    )
    assert call.duration_ms > 0
    assert call.cost is None  # a local model costs CPU seconds, not dollars


@_posix_only
@pytest.mark.usefixtures("tesseract_found")
def test_an_ocr_call_counts_tesseracts_own_cpu(monkeypatch: pytest.MonkeyPatch):
    # Tesseract is a child process: its CPU is not this process's process_time().
    tesseract_burns_cpu(monkeypatch, 0.3)
    one_ocr_call()
    [call] = get_recent_calls()
    assert call.cpu_s is not None and call.cpu_s >= 0.3


@_windows_only
@pytest.mark.usefixtures("tesseract_found")
def test_where_tesseracts_cpu_cannot_be_read_it_is_unknown_not_zero(
    monkeypatch: pytest.MonkeyPatch,
):
    tesseract_burns_cpu(monkeypatch, 0.1)
    one_ocr_call()
    [call] = get_recent_calls()
    assert call.cpu_s is None
    r = row(OCR_MODEL_NAME)
    assert (r.cpu_seconds_total, r.cpu_unmeasured_count) == (None, 1)  # unknown, counted


@pytest.mark.usefixtures("tesseract_found")
@pytest.mark.parametrize(
    "fail",
    [
        pytest.param(subprocess.TimeoutExpired("tesseract", 20), id="tesseract-times-out"),
        pytest.param(ValueError("a bug in our parser"), id="a-bug-in-the-loop"),
    ],
)
def test_a_failed_ocr_call_is_still_recorded_as_an_error(
    monkeypatch: pytest.MonkeyPatch, fail: Exception
):
    if isinstance(fail, subprocess.TimeoutExpired):

        def run(cmd, **kwargs):
            raise fail

        monkeypatch.setattr(subprocess, "run", run)
    else:
        tesseract_prints(monkeypatch, tsv(("word", 90.0)))

        def parse(_tsv: str):
            raise fail

        monkeypatch.setattr(ocr, "_parse_tsv", parse)
    assert not extract_text(png_bytes(), filename="a.png").success
    [call] = get_recent_calls()
    assert (call.kind, call.status) == (CallKind.OCR, CallStatus.ERROR)


@pytest.mark.usefixtures("tesseract_found")
def test_input_rejected_before_tesseract_runs_is_not_a_model_call():
    assert not extract_text(b"not an image at all", filename="a.png").success
    assert get_recent_calls() == []


@_needs_tesseract
def test_a_real_tesseract_call_is_recorded():
    result = extract_text(png_bytes(), filename="blank.png")
    # A blank page is NO_TEXT_RECOGNIZED -- but tesseract really ran, so it is a call.
    assert result.success is False
    [call] = get_recent_calls()
    assert call.kind == CallKind.OCR and call.duration_ms > 0
    if sys.platform != "win32":
        assert call.cpu_s is not None and call.cpu_s > 0


# --- every ASR call records its latency and its CPU seconds ------------------------


def test_an_asr_call_is_recorded_with_its_latency_and_cpu(monkeypatch: pytest.MonkeyPatch):
    one_asr_call(monkeypatch, BusyWhisper([fake_segment(" Hi.", 0.0, 1.0)]))
    [call] = get_recent_calls()
    assert (call.kind, call.status) == (CallKind.ASR, CallStatus.SUCCESS)
    assert call.duration_ms > 0
    assert call.cpu_s is not None and call.cpu_s >= 0.2  # Whisper's CPU is this process's
    assert call.cost is None  # a local model costs CPU seconds, not dollars


def test_a_failed_asr_call_is_still_recorded_as_an_error(monkeypatch: pytest.MonkeyPatch):
    use_model(monkeypatch, FakeWhisper(error=RuntimeError("CTranslate2: out of memory")))
    assert not transcribe(tone(1.0)).success
    [call] = get_recent_calls()
    assert (call.kind, call.status) == (CallKind.ASR, CallStatus.ERROR)
    assert call.cpu_s is not None  # measured up to the failure


@pytest.fixture
def real_whisper(monkeypatch: pytest.MonkeyPatch) -> None:
    """The real base.en weights, loaded fresh -- as tests/test_asr_pipeline.py sets them up."""
    monkeypatch.setattr(asr, "_model", None)
    monkeypatch.setenv("WHISPER_MODEL", "base.en")
    monkeypatch.delenv("WHISPER_MODELS_DIR", raising=False)
    monkeypatch.setenv("MODEL_CACHE_DIR", str(_ROOT / "models"))


@_needs_weights
@pytest.mark.usefixtures("real_whisper")
def test_a_real_whisper_call_is_recorded_with_its_cpu_seconds():
    assert transcribe(_CLEAN_RECORDING.read_bytes()).success
    [call] = get_recent_calls()
    assert call.model_name == "whisper-base.en-int8-local"
    assert (call.kind, call.status) == (CallKind.ASR, CallStatus.SUCCESS)
    assert call.duration_ms > 0
    assert call.cpu_s is not None and call.cpu_s > 0
    assert call.cost is None


# --- every LLM call (R2's) records its latency and its dollar cost -----------------


def test_an_llm_call_is_recorded_with_its_latency_and_dollar_cost():
    llm_call({"prompt_tokens": 400, "completion_tokens": 40})
    [call] = get_recent_calls()
    assert call.model_name == "deepseek-chat"
    assert call.duration_ms >= 0
    assert call.cost == pytest.approx(estimate_cost("deepseek-chat", 400, 40))
    assert call.cost is not None and call.cost > 0


@pytest.mark.xfail(strict=True, reason="pending R2's approval: kind and tokens on LLM calls")
def test_an_llm_call_is_recorded_as_llm_with_its_token_counts():
    llm_call({"prompt_tokens": 400, "completion_tokens": 40})
    [call] = get_recent_calls()
    assert call.kind == CallKind.LLM
    assert (call.prompt_tokens, call.completion_tokens) == (400, 40)


@pytest.mark.xfail(
    strict=True, reason="pending R2's approval: unpriced LLM calls cost None, not 0.0"
)
@pytest.mark.parametrize(
    ("usage", "model"),
    [
        pytest.param({"prompt_tokens": 400, "completion_tokens": 40}, "deepseek-v9", id="no-price"),
        pytest.param(None, "deepseek-chat", id="no-usage"),
    ],
)
def test_an_llm_call_that_cannot_be_priced_is_unpriced_not_free(
    usage: dict[str, int] | None, model: str
):
    llm_call(usage, model=model)
    [call] = get_recent_calls()
    assert call.cost is None


# --- units: CPU seconds for local models, dollars for LLM ---------------------------


@pytest.mark.usefixtures("tesseract_found")
def test_local_models_have_no_dollar_cost_and_say_so(monkeypatch: pytest.MonkeyPatch):
    tesseract_prints(monkeypatch, tsv(("word", 90.0)))
    one_ocr_call()
    one_asr_call(monkeypatch)
    for name in (OCR_MODEL_NAME, "whisper-fake-local"):
        r = row(name)
        # Not applicable -- not "1 unpriced call", which would read as a gap.
        assert (r.cost_usd_total, r.unpriced_count, r.cost_limit_usd) == (None, None, None), name
        assert r.tokens_unreported_count is None, name
        assert r.cpu_unmeasured_count is not None, name  # CPU seconds do apply


def test_cpu_measured_as_almost_nothing_is_a_number_not_unmeasured(
    monkeypatch: pytest.MonkeyPatch,
):
    one_asr_call(monkeypatch)  # a fake Whisper uses next to no CPU -- still measured
    r = row("whisper-fake-local")
    assert r.cpu_seconds_total is not None and r.cpu_unmeasured_count == 0


def test_an_llm_calls_cost_is_summed_in_dollars():
    llm_call({"prompt_tokens": 400, "completion_tokens": 40})
    llm_call({"prompt_tokens": 400, "completion_tokens": 40})
    r = row("deepseek-chat")
    assert r.cost_usd_total == pytest.approx(2 * estimate_cost("deepseek-chat", 400, 40))
    assert r.unpriced_count == 0


# --- limit boundaries, warnings and counters, through a real call ------------------


@pytest.mark.usefixtures("tesseract_found")
@pytest.mark.parametrize(
    ("seconds", "slow"),
    [
        pytest.param(29.999, False, id="under"),
        pytest.param(30.0, False, id="exactly-at-the-limit"),
        pytest.param(30.001, True, id="just-over"),
    ],
)
def test_the_ocr_duration_limit_boundary(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture, seconds: float, slow: bool
):
    tesseract_prints(monkeypatch, tsv(("word", 90.0)))
    ocr_takes(monkeypatch, seconds)  # the default OCR limit is 30 s
    with caplog.at_level(logging.INFO, logger="mind.observability"):
        one_ocr_call()
    assert get_recent_calls()[0].duration_ms == pytest.approx(seconds * 1000)
    assert len(warnings(caplog, "MODEL_CALL_SLOW")) == int(slow)
    assert row(OCR_MODEL_NAME).slow_count == int(slow)


@pytest.mark.usefixtures("tesseract_found")
def test_warnings_and_summary_counters_agree_across_a_mixed_run(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
):
    # Tight limits, so the same real calls land on both sides of them.
    use_limits(monkeypatch, ocr_max_duration_ms=30_000, asr_max_duration_ms=0.001)
    tesseract_prints(monkeypatch, *[tsv(("word", 90.0))] * 3)
    with caplog.at_level(logging.INFO, logger="mind.observability"):
        for seconds in (10.0, 31.0, 45.0):  # one fine, two slow
            ocr_takes(monkeypatch, seconds)
            one_ocr_call()
        one_asr_call(monkeypatch)  # slow -- never expensive: no dollar cost
        one_asr_call(monkeypatch, BusyWhisper([fake_segment(" Hi.", 0.0, 1.0)]))  # slow
        llm_call({"prompt_tokens": 400, "completion_tokens": 40})  # no kind yet: no limits

    summary = model_call_summary()
    assert summary.total_calls == 6
    for r in summary.by_call_name:
        slow = [w for w in warnings(caplog, "MODEL_CALL_SLOW") if f"model_name={r.call_name} " in w]
        expensive = [
            w for w in warnings(caplog, "MODEL_CALL_EXPENSIVE") if f"model_name={r.call_name} " in w
        ]
        assert (len(slow), len(expensive)) == (r.slow_count, r.expensive_count), r.call_name
    counts = {
        r.call_name: (r.call_count, r.slow_count, r.expensive_count) for r in summary.by_call_name
    }
    assert counts == {
        OCR_MODEL_NAME: (3, 2, 0),
        "whisper-fake-local": (2, 2, 0),
        "deepseek-chat": (1, 0, 0),
    }
    asr_row = row("whisper-fake-local")
    assert (asr_row.cpu_unmeasured_count, asr_row.unpriced_count) == (0, None)


def test_the_summary_is_served_over_http_after_real_calls(monkeypatch: pytest.MonkeyPatch):
    # The API imports the embedding stack; a lean image without it skips this one.
    pytest.importorskip("sentence_transformers")
    from fastapi.testclient import TestClient

    from mind.api.main import app

    one_asr_call(monkeypatch)
    llm_call({"prompt_tokens": 400, "completion_tokens": 40})

    body = TestClient(app).get("/debug/model-calls").json()
    by_name = {r["call_name"]: r for r in body["by_call_name"]}
    assert body["total_calls"] == 2
    asr_row = by_name["whisper-fake-local"]
    assert asr_row["kind"] == "asr"
    assert asr_row["cpu_seconds_total"] is not None  # CPU seconds, measured
    assert (asr_row["cost_usd_total"], asr_row["unpriced_count"]) == (None, None)  # n/a
    assert by_name["deepseek-chat"]["cost_usd_total"] == pytest.approx(
        estimate_cost("deepseek-chat", 400, 40)
    )


# --- configuration -------------------------------------------------------------------
# Each test sets or clears the variables it reads: tests/conftest.py loads .env
# into the environment, and an env var always beats the .env file.

_MONITORING_FIELDS = [
    "OCR_MAX_DURATION_MS",
    "ASR_MAX_DURATION_MS",
    "LLM_MAX_DURATION_MS",
    "LLM_MAX_COST_USD",
    "SUMMARY_EVERY_N_CALLS",
]


@pytest.fixture
def clean_monitoring_env(monkeypatch: pytest.MonkeyPatch) -> None:
    for name in _MONITORING_FIELDS:
        monkeypatch.delenv(f"MIND_MONITORING__{name}", raising=False)


@pytest.mark.usefixtures("clean_monitoring_env")
def test_the_monitoring_defaults():
    s = MonitoringSettings(_env_file=None)  # type: ignore[call-arg]
    assert (s.ocr_max_duration_ms, s.asr_max_duration_ms, s.llm_max_duration_ms) == (
        30_000,
        120_000,
        30_000,
    )
    assert s.llm_max_cost_usd == 0.01
    assert s.summary_every_n_calls == 100


def test_only_llm_calls_have_a_dollar_limit():
    # OCR/ASR cost CPU seconds; a CPU-seconds limit waits for a measured baseline.
    cost_limits = [name for name in MonitoringSettings.model_fields if "cost" in name]
    assert cost_limits == ["llm_max_cost_usd"]


@pytest.mark.usefixtures("clean_monitoring_env")
def test_monitoring_limits_are_read_from_the_environment(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setenv("MIND_MONITORING__OCR_MAX_DURATION_MS", "5000")
    monkeypatch.setenv("MIND_MONITORING__LLM_MAX_COST_USD", "0.002")
    monkeypatch.setenv("MIND_MONITORING__SUMMARY_EVERY_N_CALLS", "0")
    s = MonitoringSettings(_env_file=None)  # type: ignore[call-arg]
    assert (s.ocr_max_duration_ms, s.llm_max_cost_usd, s.summary_every_n_calls) == (5000, 0.002, 0)


@pytest.mark.usefixtures("clean_monitoring_env")
def test_none_in_the_environment_turns_a_check_off(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setenv("MIND_MONITORING__LLM_MAX_COST_USD", "none")
    monkeypatch.setenv("MIND_MONITORING__OCR_MAX_DURATION_MS", "none")
    s = MonitoringSettings(_env_file=None)  # type: ignore[call-arg]
    assert (s.llm_max_cost_usd, s.ocr_max_duration_ms) == (None, None)


@pytest.mark.usefixtures("clean_monitoring_env")
@pytest.mark.parametrize(
    ("name", "value"),
    [
        pytest.param("OCR_MAX_DURATION_MS", "0", id="a-zero-duration-limit"),
        pytest.param("ASR_MAX_DURATION_MS", "-5", id="a-negative-duration-limit"),
        pytest.param("LLM_MAX_COST_USD", "-0.01", id="a-negative-cost-limit"),
        pytest.param("SUMMARY_EVERY_N_CALLS", "-1", id="a-negative-interval"),
        pytest.param("LLM_MAX_DURATION_MS", "", id="an-empty-value-use-none-instead"),
        pytest.param("OCR_MAX_DURATION_MS", "slow", id="not-a-number"),
    ],
)
def test_a_bad_monitoring_value_is_rejected(monkeypatch: pytest.MonkeyPatch, name: str, value: str):
    monkeypatch.setenv(f"MIND_MONITORING__{name}", value)
    with pytest.raises(ValidationError):
        MonitoringSettings(_env_file=None)  # type: ignore[call-arg]
