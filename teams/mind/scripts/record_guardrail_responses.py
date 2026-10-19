#!/usr/bin/env python3
"""Record the live LLM's answers to the text cases of the guardrail eval (#488).

tests/test_guardrails.py cannot call a real provider: CI has no key, and a live
model answers differently on every run. So the text cases are run once here,
against the real provider, and every raw answer is saved to
eval/guardrails/recorded/<case_id>.json. The test then replays those answers
through ScriptedProvider, which runs R2's real parsing, repair loop and
confidence scoring on what the model actually said.

Manual on purpose, like scripts/smoke_gemini.py: it needs a key, spends quota,
and must never run in CI. Re-running it overwrites the recordings, so the
recorded date and model travel with each file.

Usage (from teams/mind):
    SECRETS_FILE_PATH=secrets.local.yaml python scripts/record_guardrail_responses.py

The provider is whichever `llm.provider` names. On a network that intercepts
TLS (NetFree), add --os-trust-store; see smoke_gemini.py for why.
"""

import argparse
import json
import sys
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from mind.contracts.base_enums import SourceType
from mind.extraction.extractor import REASON_PROVIDER_ERROR, BasicExtractor, ExtractionSuccess
from mind.extraction.providers.base import LLMProvider, LLMResponse
from mind.extraction.providers.factory import build_provider

_ROOT = Path(__file__).resolve().parent.parent
_CASES_FILE = _ROOT / "eval" / "guardrails" / "cases.json"
_RECORDED_DIR = _ROOT / "eval" / "guardrails" / "recorded"


class _RecordingProvider:
    """Passes every call through to the real provider and keeps its answer.

    Answers are kept in call order, repair rounds included, which is the order
    ScriptedProvider has to hand them back in.
    """

    def __init__(self, inner: LLMProvider) -> None:
        self._inner = inner
        self.name = inner.name
        self.answers: list[str] = []

    @property
    def model(self) -> str:
        return self._inner.model

    def complete(self, system: str, user: str) -> LLMResponse:
        response = self._inner.complete(system, user)
        self.answers.append(response.text)
        return response


def _record(case: dict[str, Any], provider: LLMProvider) -> dict[str, Any]:
    recorder = _RecordingProvider(provider)
    outcome = BasicExtractor(recorder).extract(
        case["input"]["text"],
        SourceType(case["input"]["source_type"]),
        item_key=f"guardrail/{case['id']}",
    )

    if isinstance(outcome, ExtractionSuccess):
        observed: dict[str, Any] = {
            "kind": "success",
            "title": outcome.fields.title,
            "confidence": outcome.confidence.value,
            "penalties": [p.reason for p in outcome.confidence.penalties],
            "schema_attempts": outcome.schema_attempts,
        }
    else:
        observed = {
            "kind": "failure",
            "reason": outcome.reason,
            "detail": outcome.detail,
            "schema_attempts": outcome.schema_attempts,
        }

    return {
        "case_id": case["id"],
        "provider": provider.name,
        "model": provider.model,
        "recorded_at": datetime.now(UTC).isoformat(timespec="seconds"),
        "answers": recorder.answers,
        "observed_live": observed,
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "--os-trust-store",
        action="store_true",
        help="verify TLS against the OS trust store instead of certifi "
        "(for networks that intercept TLS); requires the truststore package",
    )
    args = parser.parse_args()

    if args.os_trust_store:
        import truststore

        truststore.inject_into_ssl()

    cases = json.loads(_CASES_FILE.read_text(encoding="utf-8"))["cases"]
    text_cases = [c for c in cases if c["input"]["kind"] == "text"]
    _RECORDED_DIR.mkdir(parents=True, exist_ok=True)

    records: list[dict[str, Any]] = []
    with build_provider() as provider:
        for case in text_cases:
            record = _record(case, provider)
            observed = record["observed_live"]

            # A provider outage says nothing about the case. Nothing is written
            # unless every case answered, so the recordings on disk always come
            # from one run of one model, never a mix of an old and a new run.
            if observed["kind"] == "failure" and observed["reason"] == REASON_PROVIDER_ERROR:
                print(f"{case['id']}: provider error: {observed['detail']}", file=sys.stderr)
                print("nothing written; re-run", file=sys.stderr)
                return 1
            records.append(record)

    for record in records:
        path = _RECORDED_DIR / f"{record['case_id']}.json"
        path.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"{record['case_id']}: {json.dumps(record['observed_live'], ensure_ascii=False)}")
        print(f"  -> {path.relative_to(_ROOT)}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
