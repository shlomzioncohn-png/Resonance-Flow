"""Run a ranking model against one side of the frozen split.

    python -m mind.scoring.dev_eval                 # dev, full per-scenario detail
    python -m mind.scoring.dev_eval --split holdout # holdout, aggregate ONLY

The asymmetry is the point. `--split dev` prints every scenario, its winner and
the factor breakdown, because that is what calibration needs. `--split holdout`
prints one accuracy number and nothing else — not which scenarios failed, not
their scores — because reading holdout failures and then adjusting a weight
silently converts the holdout into more training data.

This is a development tool. R1's reported baseline is still
`python -m mind.evaluation.run --personas eval/personas`, over all 30.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from mind.evaluation.harness import run
from mind.evaluation.personas import load_personas
from mind.scoring.eval_split import partition
from mind.scoring.segmented_model import SegmentedRankingModel


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--split", choices=("dev", "holdout"), default="dev")
    parser.add_argument("--personas", type=str, default="eval/personas")
    args = parser.parse_args()

    dev, holdout = partition(load_personas(Path(args.personas)))
    scenarios = dev if args.split == "dev" else holdout
    report = run(SegmentedRankingModel(), scenarios)

    if args.split == "holdout":
        print(
            f"[holdout] {report.model_name}: "
            f"{report.top1_accuracy:.1%} ({report.correct_top1}/{report.total_scenarios})"
        )
        return

    for result in report.scenario_results:
        print(f"{'PASS' if result.passed else 'FAIL'}  {result.scenario_id}")
        if not result.passed:
            print(
                f"      expected {result.expected_top_task_id}, got {result.predicted_top_task_id}"
            )
        for candidate in sorted(result.candidates, key=lambda c: -c.score):
            print(f"      {candidate.score:.3f}  {candidate.task_id:22} {candidate.reasoning}")
    print(
        f"\n[dev] {report.model_name}: "
        f"{report.top1_accuracy:.1%} ({report.correct_top1}/{report.total_scenarios})"
    )


if __name__ == "__main__":
    main()
