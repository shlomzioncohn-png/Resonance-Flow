"""CLI for the extraction-quality eval: compare extracted action items to annotations.

    python -m mind.evaluation.run_extraction_eval                  # runs the bundled fixture
    python -m mind.evaluation.run_extraction_eval --input pair.json # runs a chosen input

`--input` points to a JSON file shaped `{"extracted": [...], "annotated": [...]}`
(two lists of strings). With no `--input`, the hand-built fixture in
`extraction_fixture.py` is used — the "dummy model before the real model"
bootstrap, mirroring `run.py --model dummy`. Real pipeline output plugs in here
once Yonit's extraction and Michal's ASR are producing it.

`--threshold` and the similarity backend are the swappable matching rule (see
`extraction_eval.evaluate_extraction`). The similarity is the pure-Python
`placeholder_similarity` and the default threshold is the fixture's 0.5 —
**not** the locked lexical τ = 0.60 from `extraction_eval_design.md` §6, which is
calibrated for the `rapidfuzz` backend that is not built yet.

Reporting mirrors `run.py`: a short summary, then the full report as JSON (also
written to `extraction_report.json`), so the matched/missed/false-positive detail
is kept for later "why" analysis.
"""

import argparse
import dataclasses
import json
import sys
from pathlib import Path

from mind.evaluation.extraction_eval import (
    PLACEHOLDER_THRESHOLD,
    ExtractionEvalReport,
    evaluate_extraction,
    placeholder_similarity,
)
from mind.evaluation.extraction_fixture import FIXTURE_ANNOTATED, FIXTURE_EXTRACTED


def _summary_lines(report: ExtractionEvalReport) -> str:
    return (
        "[extraction-quality]\n"
        f"  Precision: {report.precision:.1%} "
        f"({report.true_positives}/{report.true_positives + len(report.false_positives)} "
        f"extracted items matched an annotation)\n"
        f"  Recall:    {report.recall:.1%} "
        f"({report.true_positives}/{report.true_positives + len(report.missed)} "
        f"annotations were found)\n"
        f"  F1:        {report.f1:.1%}"
    )


def _detail_lines(report: ExtractionEvalReport) -> str:
    lines = [f"  Matched ({len(report.matched)}):"]
    for pair in report.matched:
        lines.append(f"    ~ {pair.extracted!r}")
        lines.append(f"      ↔ {pair.annotated!r}  (similarity {pair.similarity:.2f})")
    lines.append(f"  Missed / false negatives ({len(report.missed)}):")
    lines.extend(f"    - {item!r}" for item in report.missed)
    lines.append(f"  False positives ({len(report.false_positives)}):")
    lines.extend(f"    + {item!r}" for item in report.false_positives)
    return "\n".join(lines)


def _load_input(path: Path) -> tuple[list[str], list[str]]:
    with path.open(encoding="utf-8") as f:
        raw = json.load(f)
    try:
        extracted = list(raw["extracted"])
        annotated = list(raw["annotated"])
    except (KeyError, TypeError) as e:
        raise ValueError(
            f'{path}: expected a JSON object with "extracted" and "annotated" lists'
        ) from e
    if not all(isinstance(s, str) for s in [*extracted, *annotated]):
        raise ValueError(f"{path}: every item in both lists must be a string")
    return extracted, annotated


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run the extraction-quality eval (precision/recall of extracted action items)."
    )
    parser.add_argument(
        "--input",
        type=str,
        default=None,
        help='JSON file shaped {"extracted": [...], "annotated": [...]}. '
        "Omit to run the bundled hand-built fixture.",
    )
    parser.add_argument(
        "--threshold",
        type=float,
        default=PLACEHOLDER_THRESHOLD,
        help=f"Similarity at/above which a pair counts as the same item (default {PLACEHOLDER_THRESHOLD}). "
        "Not the locked lexical tau=0.60 — that is for the rapidfuzz backend, not the placeholder.",
    )
    args = parser.parse_args()

    if args.input is None:
        source = "bundled fixture (extraction_fixture.py)"
        extracted, annotated = FIXTURE_EXTRACTED, FIXTURE_ANNOTATED
    else:
        source = args.input
        try:
            extracted, annotated = _load_input(Path(args.input))
        except (FileNotFoundError, ValueError, json.JSONDecodeError) as e:
            print(f"Could not load input from {args.input!r}: {e}", file=sys.stderr)
            sys.exit(1)

    report = evaluate_extraction(
        extracted,
        annotated,
        similarity=placeholder_similarity,
        threshold=args.threshold,
    )

    print("\n=== Extraction-Quality Eval ===")
    print(f"Input:              {source}")
    print("Similarity backend: placeholder_similarity (placeholder — Jaccard token overlap)")
    print(f"Threshold:          {args.threshold}")
    print(f"Items:              {len(extracted)} extracted, {len(annotated)} annotated\n")
    print(_summary_lines(report))
    print()
    print(_detail_lines(report))
    print("===============================\n")

    output = {
        "similarity_backend": "placeholder_similarity",
        "threshold": args.threshold,
        "input": source,
        "report": dataclasses.asdict(report),
    }
    output["report"]["true_positives"] = report.true_positives

    print("--- Extraction Eval Report (full detail, also written to extraction_report.json) ---")
    print(json.dumps(output, indent=4))
    print("-----------------------------------------------------------------------------------\n")

    with open("extraction_report.json", "w", encoding="utf-8") as f:
        json.dump(output, f, indent=4)


if __name__ == "__main__":
    main()
