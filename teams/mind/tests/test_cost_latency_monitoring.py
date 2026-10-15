"""Cost and latency monitoring for every model call MIND makes (#479).

The path of this file is the one named in the issue, so its command keeps
working:

    pytest tests/test_cost_latency_monitoring.py -v

These tests go through the real entry points -- ocr.extract_text(),
asr.transcribe(), and BasicExtractor over ScriptedProvider, R2's public test
fake (tests/extraction/fakes.py) -- and check what lands in the ledger, the
summary, and the log. How a provider parses token usage out of an HTTP
response is R2's, tested in tests/extraction/test_deepseek.py / test_gemini.py.

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

LLM calls are made in R2's extraction/extractor.py; recording them with a
kind, token counts and an unpriced cost was agreed with her on the PR. The
tests that spelled that change out ran as xfail(strict=True) until it landed.
"""

from __future__ import annotations

import dataclasses
import json
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
from mind.extraction.extractor import BasicExtractor, ExtractionSuccess
from mind.extraction.providers.base import LLMTimeout
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
from tests.extraction.fakes import ScriptedProvider
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

# The real settings loader. tests/conftest.py replaces tracking.monitoring_settings
# for every test, after this module has been imported, so this is the original.
_real_monitoring_settings = tracking.monitoring_settings

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


# A valid extraction of _SOURCE: one call, no repair round.
_ANSWER = json.dumps(
    {
        "title": "Send the Q3 report to finance",
        "description": "Finance asked for the Q3 report before the close.",
        "source_ref": "MSG-4471",
        "has_dependencies": False,
    }
)
_SOURCE = "Please send the Q3 report to finance before the close. (MSG-4471)"


class _NoUsageProvider(ScriptedProvider):
    """A provider that, like some real ones on some answers, reports no token usage."""

    def complete(self, system: str, user: str):
        response = super().complete(system, user)
        return dataclasses.replace(response, prompt_tokens=None, completion_tokens=None)


def llm_call(model: str = "deepseek-chat", *, reports_usage: bool = True) -> None:
    """One extraction -- one LLM call -- through BasicExtractor._call.

    ScriptedProvider reports the answer's model id and token counts:
    "deepseek-chat" is priced, its default "scripted-model" is not.
    """
    provider = (ScriptedProvider if reports_usage else _NoUsageProvider)(_ANSWER, model=model)
    BasicExtractor(provider, sleep=lambda _: None).extract(_SOURCE, SourceType.EMAIL)


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
def test_an_ocr_calls_window_includes_rendering_the_pdf(monkeypatch: pytest.MonkeyPatch):
    # Rendering the pages runs in this process before Tesseract does -- part
    # of what the call costs, wall clock and CPU alike. A fake wall clock that
    # only rendering moves makes the duration exact.
    clock = [1000.0]
    monkeypatch.setattr(ocr, "time", SimpleNamespace(perf_counter=lambda: clock[0]))

    def render(content: bytes, max_pages: int):
        clock[0] += 7.0  # rendering takes 7 s of wall clock...
        burn_cpu(0.2)  # ...and 0.2 s of CPU
        return [png_bytes()], 1

    monkeypatch.setattr(ocr, "_is_pdf", lambda content, filename: True)
    monkeypatch.setattr(ocr, "_pdf_to_page_images", render)
    tesseract_prints(monkeypatch, tsv(("word", 90.0)))
    assert extract_text(b"%PDF-1.4 a scan", filename="a.pdf").success
    [call] = get_recent_calls()
    assert call.duration_ms == pytest.approx(7000)
    if sys.platform != "win32":  # Windows cannot read Tesseract's CPU: cpu_s is None
        assert call.cpu_s is not None and call.cpu_s >= 0.2


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


def test_an_asr_calls_window_includes_decoding_the_audio(monkeypatch: pytest.MonkeyPatch):
    real_decode = asr._decode

    def decode(content: bytes, max_duration_s: float):
        burn_cpu(0.2)  # decoding happens in this process, before VAD and Whisper
        return real_decode(content, max_duration_s)

    monkeypatch.setattr(asr, "_decode", decode)
    one_asr_call(monkeypatch)  # a fake Whisper that uses next to no CPU itself
    [call] = get_recent_calls()
    assert call.cpu_s is not None and call.cpu_s >= 0.2


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
    llm_call()
    [call] = get_recent_calls()
    assert call.model_name == "deepseek-chat"
    assert call.duration_ms >= 0
    assert call.prompt_tokens is not None and call.completion_tokens is not None
    assert call.cost == pytest.approx(
        estimate_cost("deepseek-chat", call.prompt_tokens, call.completion_tokens)
    )
    assert call.cost is not None and call.cost > 0


def test_an_llm_call_is_recorded_as_llm_with_its_token_counts():
    llm_call()
    [call] = get_recent_calls()
    assert call.kind == CallKind.LLM
    assert call.completion_tokens == len(_ANSWER)  # what ScriptedProvider reports
    assert call.prompt_tokens is not None and call.prompt_tokens > 0


@pytest.mark.parametrize(
    ("model", "reports_usage"),
    [
        pytest.param("scripted-model", True, id="no-price"),
        pytest.param("deepseek-chat", False, id="no-usage"),
    ],
)
def test_an_llm_call_that_cannot_be_priced_is_unpriced_not_free(model: str, reports_usage: bool):
    llm_call(model, reports_usage=reports_usage)
    [call] = get_recent_calls()
    assert call.cost is None


def test_a_models_failures_and_successes_share_one_row():
    # The first call times out, the retry succeeds: one model, so one row --
    # its error_count and p95 include the failure.
    provider = ScriptedProvider(LLMTimeout("too slow"), _ANSWER, model="deepseek-chat")
    BasicExtractor(provider, sleep=lambda _: None).extract(_SOURCE, SourceType.EMAIL)
    [r] = model_call_summary().by_call_name
    assert (r.call_name, r.call_count, r.error_count) == ("deepseek-chat", 2, 1)


class _AliasProvider(ScriptedProvider):
    """Sends an alias; the API answers with the model it resolved it to."""

    def complete(self, system: str, user: str):
        return dataclasses.replace(super().complete(system, user), model="deepseek-chat")


def test_an_llm_call_is_keyed_by_the_model_sent_and_priced_by_the_model_billed():
    provider = _AliasProvider(_ANSWER, model="deepseek-latest")
    BasicExtractor(provider, sleep=lambda _: None).extract(_SOURCE, SourceType.EMAIL)
    [call] = get_recent_calls()
    assert call.model_name == "deepseek-latest"  # a stable key, whatever the API answers
    assert call.prompt_tokens is not None and call.completion_tokens is not None
    assert call.cost == pytest.approx(  # the price of the model that actually ran
        estimate_cost("deepseek-chat", call.prompt_tokens, call.completion_tokens)
    )


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
    llm_call()
    llm_call()
    first, second = get_recent_calls()
    assert first.cost is not None and second.cost is not None
    r = row("deepseek-chat")
    assert r.cost_usd_total == pytest.approx(first.cost + second.cost)
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
        llm_call()  # within both LLM limits

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
    llm_call()
    llm_cost = get_recent_calls()[-1].cost

    body = TestClient(app).get("/debug/model-calls").json()
    by_name = {r["call_name"]: r for r in body["by_call_name"]}
    assert body["total_calls"] == 2
    asr_row = by_name["whisper-fake-local"]
    assert asr_row["kind"] == "asr"
    assert asr_row["cpu_seconds_total"] is not None  # CPU seconds, measured
    assert (asr_row["cost_usd_total"], asr_row["unpriced_count"]) == (None, None)  # n/a
    assert llm_cost is not None
    assert by_name["deepseek-chat"]["cost_usd_total"] == pytest.approx(llm_cost)


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
        10_000,
    )
    assert s.llm_max_cost_usd == 0.02
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
    monkeypatch.setenv("MIND_MONITORING__SUMMARY_EVERY_N_CALLS", "none")
    s = MonitoringSettings(_env_file=None)  # type: ignore[call-arg]
    assert (s.llm_max_cost_usd, s.ocr_max_duration_ms, s.summary_every_n_calls) == (
        None,
        None,
        None,
    )


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


# --- the real settings loader, environment to record_call ---------------------------
# Everything above runs with the loader replaced (tests/conftest.py). These go
# through the real one: MIND_MONITORING__* in the environment -> monitoring_settings()
# -> record_call() -- where an invalid value used to fail every model call.


@pytest.fixture
def real_settings_loader(monkeypatch: pytest.MonkeyPatch, clean_monitoring_env: None):
    _real_monitoring_settings.cache_clear()
    monkeypatch.setattr(tracking, "monitoring_settings", _real_monitoring_settings)
    yield
    _real_monitoring_settings.cache_clear()


@pytest.mark.usefixtures("real_settings_loader", "tesseract_found")
@pytest.mark.parametrize(
    ("name", "value"),
    [
        pytest.param("OCR_MAX_DURATION_MS", "abc", id="not-a-number"),
        pytest.param("LLM_MAX_COST_USD", "-1", id="out-of-range"),
        pytest.param("SUMMARY_EVERY_N_CALLS", "often", id="a-bad-interval"),
    ],
)
def test_an_invalid_monitoring_value_never_breaks_a_model_call(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture, name: str, value: str
):
    monkeypatch.setenv(f"MIND_MONITORING__{name}", value)
    tesseract_prints(monkeypatch, tsv(("word", 90.0)))
    with caplog.at_level(logging.INFO, logger="mind.observability"):
        one_ocr_call()  # each asserts its call succeeded
        one_asr_call(monkeypatch)
        outcome = BasicExtractor(
            ScriptedProvider(_ANSWER, model="deepseek-chat"), sleep=lambda _: None
        ).extract(_SOURCE, SourceType.EMAIL)
    assert isinstance(outcome, ExtractionSuccess)
    assert len(get_recent_calls()) == 3  # and every one was still recorded
    errors = [r.getMessage() for r in caplog.records if r.levelno == logging.ERROR]
    assert len(errors) == 1  # logged once, not on every call
    assert "MIND_MONITORING__" in errors[0] and "using the defaults" in errors[0]
    assert row(OCR_MODEL_NAME).duration_limit_ms == 30_000  # the defaults apply


@pytest.mark.usefixtures("real_settings_loader")
def test_a_valid_limit_from_the_environment_reaches_record_call(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
):
    monkeypatch.setenv("MIND_MONITORING__LLM_MAX_DURATION_MS", "5")
    with caplog.at_level(logging.INFO, logger="mind.observability"):
        tracking.record_call("deepseek-chat", 6.0, None, kind=CallKind.LLM)
    [line] = warnings(caplog, "MODEL_CALL_SLOW")
    assert "limit_ms=5.0" in line


@pytest.mark.usefixtures("real_settings_loader")
def test_none_turns_the_summary_line_off_through_the_real_loader(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
):
    monkeypatch.setenv("MIND_MONITORING__SUMMARY_EVERY_N_CALLS", "none")
    with caplog.at_level(logging.INFO, logger="mind.observability"):
        for _ in range(3):
            tracking.record_call("m", 1.0, None)
    assert not [m for m in caplog.messages if m.startswith("model_call_summary")]
    assert not [r for r in caplog.records if r.levelno >= logging.ERROR]
