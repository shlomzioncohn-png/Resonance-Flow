#!/usr/bin/env python3
"""#178: the extraction eval on real AMI meetings, through main's real extraction.

Each meeting's human transcript (`ingestion/ami_transcripts.py`, #784) goes to
`BasicExtractor` as one MEETING item whose `item_key` is the AMI meeting id;
the outcomes are scored with `evaluate_meetings` against the meeting's gold
`<actions>` (`load_gold_actions`), with the lexical backend at its locked
tau (`similarity.py`, `extraction_eval_design.md` §6).

Manual on purpose, like `smoke_gemini.py`: it needs a key, it spends quota,
and it must never run in CI. The transcript is AMI's human one, not ASR
output, so this measures extraction alone (mentor's call, 2026-10-25); ASR
is added on top later.

The model's answers are written to `--outcomes` (default
`extraction_outcomes.json`) before scoring, so the LLM is paid for once:
`--from-outcomes` re-scores a saved file with no call at all. A re-score
needs only titles and descriptions; confidence is not kept. The scored
report (micro/macro, per-meeting matches, failures) goes to `--report`
(default `extraction_report.json`).

Usage:
    CONFIG_FILE_PATH=config.local.yaml SECRETS_FILE_PATH=secrets.178.local.yaml \\
        python scripts/run_ami_extraction_eval.py --meeting IS1008b   # one meeting first
    ... python scripts/run_ami_extraction_eval.py                     # the whole v1 slice
    python scripts/run_ami_extraction_eval.py --from-outcomes extraction_outcomes.json
"""

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from mind.contracts.base_enums import SourceType
from mind.evaluation.extraction_eval import ExtractionSliceReport, evaluate_meetings
from mind.evaluation.run_extraction_eval import slice_summary_lines
from mind.evaluation.similarity import LEXICAL_THRESHOLD, lexical_similarity
from mind.extraction.confidence import ConfidenceBreakdown
from mind.extraction.extractor import (
    BasicExtractor,
    ExtractedTask,
    ExtractionFailure,
    ExtractionSuccess,
)
from mind.extraction.models import ExtractedFields
from mind.ingestion.ami_transcripts import load_gold_actions, load_transcript

# The v1 slice (`extraction_eval_design.md`, "Scope"): the meetings in ami.manifest.json.
V1_MEETINGS = ["IB4003", "IS1008b", "IS1008d"]

# Matched pairs scoring in this band are reviewed by hand (design §6, #178 step 4):
# the zone where a wrong pair could clear tau.
REVIEW_BAND = (LEXICAL_THRESHOLD, 0.75)

Outcome = ExtractionSuccess | ExtractionFailure


def _extract(meetings: list[str]) -> tuple[list[Outcome], str]:
    from mind.extraction.providers.factory import build_provider
    from mind.extraction.providers.settings import load_gemini_settings

    settings = load_gemini_settings()
    if not settings.configured:
        raise SystemExit(
            "no Gemini api_key found. Point CONFIG_FILE_PATH and SECRETS_FILE_PATH at "
            "local config/secrets files holding llm.gemini.*"
        )
    print(f"model: {settings.model}")
    outcomes: list[Outcome] = []
    with build_provider("gemini") as provider:
        extractor = BasicExtractor(provider)
        for meeting_id in meetings:
            transcript = load_transcript(meeting_id)
            print(f"extracting {meeting_id} ({len(transcript.text)} chars)...")
            outcomes.append(
                extractor.extract(transcript.text, SourceType.MEETING, item_key=meeting_id)
            )
    return outcomes, settings.model


def _to_json(outcomes: list[Outcome], model: str) -> dict[str, Any]:
    rows: list[dict[str, Any]] = []
    for outcome in outcomes:
        if isinstance(outcome, ExtractionFailure):
            rows.append(
                {
                    "meeting_id": outcome.item_key,
                    "failed": True,
                    "reason": outcome.reason,
                    "detail": outcome.detail,
                    "environmental": outcome.environmental,
                }
            )
        else:
            rows.append(
                {
                    "meeting_id": outcome.item_key,
                    "failed": False,
                    "tasks": [task.fields.model_dump(mode="json") for task in outcome.tasks],
                }
            )
    return {"model": model, "outcomes": rows}


def _from_json(data: dict[str, Any]) -> list[Outcome]:
    outcomes: list[Outcome] = []
    for row in data["outcomes"]:
        if row["failed"]:
            outcomes.append(
                ExtractionFailure(
                    source_type=SourceType.MEETING,
                    item_key=row["meeting_id"],
                    reason=row["reason"],
                    detail=row["detail"],
                    schema_attempts=0,
                    provider_calls=0,
                    environmental=row["environmental"],
                )
            )
            continue
        # Confidence is not saved, and the classification fields (tier, urgency,
        # ...) a classifying run adds are dropped: the eval reads neither.
        unknown = ConfidenceBreakdown(base=0.0, penalties=(), value=0.0)
        outcomes.append(
            ExtractionSuccess(
                tasks=tuple(
                    ExtractedTask(
                        fields=ExtractedFields.model_validate(
                            {k: v for k, v in t.items() if k in ExtractedFields.model_fields}
                        ),
                        confidence=unknown,
                    )
                    for t in row["tasks"]
                ),
                source_type=SourceType.MEETING,
                item_key=row["meeting_id"],
                model=data["model"],
                schema_attempts=0,
                provider_calls=0,
            )
        )
    return outcomes


def _print_details(report: ExtractionSliceReport) -> None:
    low, high = REVIEW_BAND
    for meeting in report.meetings:
        failed = "  FAILED" if meeting.failed else ""
        print(
            f"\n--- {meeting.meeting_id}{failed}: TP {meeting.true_positives}  "
            f"FP {meeting.false_positives}  FN {meeting.false_negatives}"
        )
        for pair in meeting.report.matched:
            review = "  <- review by hand" if low <= pair.similarity < high else ""
            print(f"  ~ {pair.similarity:.2f}  {pair.extracted!r}{review}")
            print(f"           gold: {pair.annotated!r}")
        for item in meeting.report.missed:
            print(f"  - missed:  {item!r}")
        for item in meeting.report.false_positives:
            print(f"  + extra:   {item!r}")


def main() -> int:
    # The report prints quotes from the transcript; a Windows console cannot encode all of them.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "--meeting",
        action="append",
        choices=V1_MEETINGS,
        help="run only this meeting (repeatable); default: all of the v1 slice",
    )
    parser.add_argument(
        "--outcomes",
        type=Path,
        default=Path("extraction_outcomes.json"),
        help="where to save the model's answers (default: extraction_outcomes.json)",
    )
    parser.add_argument(
        "--report",
        type=Path,
        default=Path("extraction_report.json"),
        help="where to save the scored report (default: extraction_report.json)",
    )
    parser.add_argument(
        "--from-outcomes",
        type=Path,
        default=None,
        help="re-score a saved outcomes file instead of calling the model",
    )
    parser.add_argument(
        "--os-trust-store",
        action="store_true",
        help="verify TLS against the OS trust store (see smoke_gemini.py)",
    )
    args = parser.parse_args()

    if args.from_outcomes is not None:
        data = json.loads(args.from_outcomes.read_text(encoding="utf-8"))
        outcomes = _from_json(data)
        model = data["model"]
        meetings = sorted({o.item_key for o in outcomes if o.item_key is not None})
        print(f"re-scoring {args.from_outcomes} (model {model}); no model call")
    else:
        if args.os_trust_store:
            import truststore

            truststore.inject_into_ssl()
        meetings = args.meeting or V1_MEETINGS
        outcomes, model = _extract(meetings)
        args.outcomes.write_text(
            json.dumps(_to_json(outcomes, model), indent=2, ensure_ascii=False), encoding="utf-8"
        )
        print(f"model answers saved to {args.outcomes}")

    gold = {meeting_id: load_gold_actions(meeting_id) for meeting_id in meetings}
    report = evaluate_meetings(
        outcomes, gold, similarity=lexical_similarity, threshold=LEXICAL_THRESHOLD
    )

    print(f"\n=== Extraction eval on AMI (human transcripts), model {model} ===")
    print(f"similarity: lexical_similarity, threshold {LEXICAL_THRESHOLD}")
    print(slice_summary_lines(report))
    _print_details(report)

    args.report.write_text(
        json.dumps(
            {
                "model": model,
                "transcripts": "AMI human transcripts (ami_transcripts.py)",
                "similarity_backend": "lexical_similarity",
                "threshold": LEXICAL_THRESHOLD,
                "report": report.model_dump(mode="json"),
            },
            indent=2,
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    print(f"\nscored report saved to {args.report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
