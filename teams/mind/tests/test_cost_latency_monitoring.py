"""Cost and latency monitoring for every model call MIND makes (#479).

The path of this file is the one named in the issue, so its command keeps
working:

    pytest tests/test_cost_latency_monitoring.py -v

These tests go through the real entry points -- ocr.extract_text(),
asr.transcribe(), and BasicExtractor over the real DeepSeek client on a mocked
transport -- and check what lands in the ledger, the summary, and the log.
The rules underneath have their own unit tests, not repeated here:

* tests/observability/test_observability.py -- record_call() and its log line
* tests/observability/test_compute.py -- CpuClock and compute_cost()
* tests/observability/test_model_call_summary.py -- the per-name aggregate
* tests/observability/test_limit_warnings.py -- each limit rule and warning

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

from mind.config import ComputeCostSettings, MonitoringSettings
from mind.contracts.base_enums import SourceType
from mind.extraction.extractor import BasicExtractor
from mind.extraction.providers.pricing import estimate_cost
from mind.multimodal import asr, ocr
from mind.multimodal.asr import transcribe
from mind.multimodal.ocr import OCR_MODEL_NAME, extract_text
from mind.observability import compute, tracking
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

RATE = 0.036  # USD per CPU-hour: 100 CPU-seconds cost $0.001

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


def use_rate(monkeypatch: pytest.MonkeyPatch, usd_per_cpu_hour: float | None) -> None:
    monkeypatch.setattr(compute, "usd_per_cpu_hour", lambda: usd_per_cpu_hour)


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


def row(call_name: str) -> CallNameSummary:
    [found] = [r for r in model_call_summary().by_call_name if r.call_name == call_name]
    return found


def warnings(caplog: pytest.LogCaptureFixture, marker: str) -> list[str]:
    return [
        r.getMessage()
        for r in caplog.records
        if r.levelno == logging.WARNING and r.getMessage().startswith(marker)
    ]


# --- every OCR call records its latency and a compute-cost estimate ----------------


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


@_posix_only
@pytest.mark.usefixtures("tesseract_found")
def test_an_ocr_call_counts_tesseracts_own_cpu_and_prices_it(monkeypatch: pytest.MonkeyPatch):
    # Tesseract is a child process: its CPU is not this process's process_time().
    tesseract_burns_cpu(monkeypatch, 0.3)
    use_rate(monkeypatch, RATE)
    one_ocr_call()
    [call] = get_recent_calls()
    assert call.cpu_s is not None and call.cpu_s >= 0.3
    assert call.cost == pytest.approx(call.cpu_s / 3600 * RATE)


@_windows_only
@pytest.mark.usefixtures("tesseract_found")
def test_where_tesseracts_cpu_cannot_be_read_it_is_unknown_not_zero(
    monkeypatch: pytest.MonkeyPatch,
):
    tesseract_burns_cpu(monkeypatch, 0.1)
    use_rate(monkeypatch, RATE)  # a rate is set, but there is no CPU figure to price
    one_ocr_call()
    [call] = get_recent_calls()
    assert (call.cpu_s, call.cost) == (None, None)


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


# --- every ASR call records its latency and a compute-cost estimate ----------------


def test_an_asr_call_is_recorded_with_its_latency_and_cpu(monkeypatch: pytest.MonkeyPatch):
    one_asr_call(monkeypatch, BusyWhisper([fake_segment(" Hi.", 0.0, 1.0)]))
    [call] = get_recent_calls()
    assert (call.kind, call.status) == (CallKind.ASR, CallStatus.SUCCESS)
    assert call.duration_ms > 0
    assert call.cpu_s is not None and call.cpu_s >= 0.2  # Whisper's CPU is this process's


def test_an_asr_call_is_priced_at_the_configured_rate(monkeypatch: pytest.MonkeyPatch):
    use_rate(monkeypatch, RATE)
    one_asr_call(monkeypatch, BusyWhisper([fake_segment(" Hi.", 0.0, 1.0)]))
    [call] = get_recent_calls()
    assert call.cpu_s is not None
    assert call.cost == pytest.approx(call.cpu_s / 3600 * RATE)


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
def test_a_real_whisper_call_is_recorded_and_priced(monkeypatch: pytest.MonkeyPatch):
    use_rate(monkeypatch, RATE)
    assert transcribe(_CLEAN_RECORDING.read_bytes()).success
    [call] = get_recent_calls()
    assert call.model_name == "whisper-base.en-int8-local"
    assert (call.kind, call.status) == (CallKind.ASR, CallStatus.SUCCESS)
    assert call.duration_ms > 0
    assert call.cpu_s is not None and call.cpu_s > 0
    assert call.cost is not None and call.cost > 0


# --- every LLM call (R2's) records its latency and cost ----------------------------


def llm_call(usage: dict[str, int] | None, model: str = "deepseek-chat") -> None:
    handler, _ = _always(_envelope(VALID_ANSWER, model=model, usage=usage))
    BasicExtractor(_provider(handler), sleep=lambda _: None).extract(SOURCE_TEXT, SourceType.EMAIL)


def test_an_llm_call_is_recorded_with_its_latency_and_token_cost():
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


# --- unknown cost versus zero cost ---------------------------------------------------


@pytest.mark.usefixtures("tesseract_found")
def test_with_no_rate_local_calls_are_unpriced_not_free(monkeypatch: pytest.MonkeyPatch):
    tesseract_prints(monkeypatch, tsv(("word", 90.0)))
    one_ocr_call()
    one_asr_call(monkeypatch)
    for name in (OCR_MODEL_NAME, "whisper-fake-local"):
        r = row(name)
        assert (r.cost_total_usd, r.unpriced_count) == (None, 1), name


def test_a_zero_rate_makes_a_call_free_not_unpriced(monkeypatch: pytest.MonkeyPatch):
    use_rate(monkeypatch, 0.0)
    one_asr_call(monkeypatch)
    [call] = get_recent_calls()
    assert call.cost == 0.0
    r = row("whisper-fake-local")
    assert (r.cost_total_usd, r.unpriced_count) == (0.0, 0)


def test_cpu_measured_as_almost_nothing_is_a_number_not_unmeasured(
    monkeypatch: pytest.MonkeyPatch,
):
    one_asr_call(monkeypatch)  # a fake Whisper uses next to no CPU -- still measured
    r = row("whisper-fake-local")
    assert r.cpu_s_total is not None and r.cpu_unmeasured_count == 0


@pytest.mark.usefixtures("tesseract_found")
def test_an_unpriced_call_under_a_cost_limit_is_not_expensive(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
):
    use_limits(monkeypatch, ocr_max_cost_usd=0.0)  # any known cost would be over this
    tesseract_prints(monkeypatch, tsv(("word", 90.0)))
    with caplog.at_level(logging.INFO, logger="mind.observability"):
        one_ocr_call()
    assert warnings(caplog, "MODEL_CALL_EXPENSIVE") == []
    r = row(OCR_MODEL_NAME)
    assert (r.expensive_count, r.unpriced_count) == (0, 1)


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


@pytest.mark.parametrize(
    ("limit", "expensive"),
    [
        pytest.param(0.0051, False, id="under"),
        pytest.param(0.005, False, id="exactly-at-the-limit"),
        pytest.param(0.004999, True, id="just-over"),
    ],
)
def test_the_asr_cost_limit_boundary(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
    limit: float,
    expensive: bool,
):
    # A real call's CPU (so its cost) differs run to run: pin what transcribe() is charged.
    monkeypatch.setattr(asr, "compute_cost", lambda _cpu_s: 0.005)
    use_limits(monkeypatch, asr_max_cost_usd=limit)
    with caplog.at_level(logging.INFO, logger="mind.observability"):
        one_asr_call(monkeypatch)
    assert get_recent_calls()[0].cost == 0.005
    assert len(warnings(caplog, "MODEL_CALL_EXPENSIVE")) == int(expensive)
    assert row("whisper-fake-local").expensive_count == int(expensive)


@pytest.mark.usefixtures("tesseract_found")
def test_warnings_and_summary_counters_agree_across_a_mixed_run(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
):
    # Tight limits, so the same real calls land on both sides of them.
    use_limits(
        monkeypatch,
        ocr_max_duration_ms=30_000,
        asr_max_duration_ms=0.001,  # every ASR call is slow
        asr_max_cost_usd=0.0,  # every priced ASR call is expensive
    )
    tesseract_prints(monkeypatch, *[tsv(("word", 90.0))] * 3)
    with caplog.at_level(logging.INFO, logger="mind.observability"):
        for seconds in (10.0, 31.0, 45.0):  # one fine, two slow
            ocr_takes(monkeypatch, seconds)
            one_ocr_call()
        one_asr_call(monkeypatch)  # no rate yet: slow, unpriced -- not expensive
        use_rate(monkeypatch, RATE)
        one_asr_call(monkeypatch, BusyWhisper([fake_segment(" Hi.", 0.0, 1.0)]))  # both
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
        "whisper-fake-local": (2, 2, 1),
        "deepseek-chat": (1, 0, 0),
    }
    asr_row = row("whisper-fake-local")
    assert (asr_row.unpriced_count, asr_row.cpu_unmeasured_count) == (1, 0)


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
    assert by_name["whisper-fake-local"]["kind"] == "asr"
    assert by_name["whisper-fake-local"]["cost_total_usd"] is None  # unpriced -> null, not 0
    assert by_name["deepseek-chat"]["cost_total_usd"] == pytest.approx(
        estimate_cost("deepseek-chat", 400, 40)
    )


# --- configuration -------------------------------------------------------------------
# Each test sets or clears the variables it reads: tests/conftest.py loads .env
# into the environment, and an env var always beats the .env file.

_MONITORING_FIELDS = [
    "OCR_MAX_DURATION_MS",
    "ASR_MAX_DURATION_MS",
    "LLM_MAX_DURATION_MS",
    "OCR_MAX_COST_USD",
    "ASR_MAX_COST_USD",
    "LLM_MAX_COST_USD",
    "SUMMARY_EVERY_N_CALLS",
]


@pytest.fixture
def clean_monitoring_env(monkeypatch: pytest.MonkeyPatch) -> None:
    for name in _MONITORING_FIELDS:
        monkeypatch.delenv(f"MIND_MONITORING__{name}", raising=False)
    monkeypatch.delenv("MIND_COMPUTE_COST__USD_PER_CPU_HOUR", raising=False)


@pytest.mark.usefixtures("clean_monitoring_env")
def test_the_cpu_rate_is_unset_by_default():
    assert ComputeCostSettings(_env_file=None).usd_per_cpu_hour is None  # type: ignore[call-arg]


@pytest.mark.usefixtures("clean_monitoring_env")
def test_the_cpu_rate_is_read_from_the_environment(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setenv("MIND_COMPUTE_COST__USD_PER_CPU_HOUR", "0.042")
    assert ComputeCostSettings(_env_file=None).usd_per_cpu_hour == 0.042  # type: ignore[call-arg]


@pytest.mark.usefixtures("clean_monitoring_env")
@pytest.mark.parametrize("value", ["-1", "cheap"])
def test_a_bad_cpu_rate_is_rejected(monkeypatch: pytest.MonkeyPatch, value: str):
    monkeypatch.setenv("MIND_COMPUTE_COST__USD_PER_CPU_HOUR", value)
    with pytest.raises(ValidationError):
        ComputeCostSettings(_env_file=None)  # type: ignore[call-arg]


@pytest.mark.usefixtures("clean_monitoring_env")
def test_the_monitoring_defaults():
    s = MonitoringSettings(_env_file=None)  # type: ignore[call-arg]
    assert (s.ocr_max_duration_ms, s.asr_max_duration_ms, s.llm_max_duration_ms) == (
        30_000,
        120_000,
        30_000,
    )
    assert (s.ocr_max_cost_usd, s.asr_max_cost_usd, s.llm_max_cost_usd) == (None, None, 0.01)
    assert s.summary_every_n_calls == 100


@pytest.mark.usefixtures("clean_monitoring_env")
def test_monitoring_limits_are_read_from_the_environment(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setenv("MIND_MONITORING__OCR_MAX_DURATION_MS", "5000")
    monkeypatch.setenv("MIND_MONITORING__ASR_MAX_COST_USD", "0.002")
    monkeypatch.setenv("MIND_MONITORING__SUMMARY_EVERY_N_CALLS", "0")
    s = MonitoringSettings(_env_file=None)  # type: ignore[call-arg]
    assert (s.ocr_max_duration_ms, s.asr_max_cost_usd, s.summary_every_n_calls) == (5000, 0.002, 0)


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
