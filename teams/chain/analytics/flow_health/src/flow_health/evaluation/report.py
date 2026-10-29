"""evaluation_report.md: the numbers of one run in readable form."""
from __future__ import annotations

from ..detectors.common import PATTERNS


def _pct(x: float | None) -> str:
    return "n/a" if x is None else f"{x:.2f}"


def _row(name: str, c: dict) -> str:
    return f"| {name} | {c['tp']} | {c['fp']} | {c['fn']} | {_pct(c['precision'])} | {_pct(c['recall'])} |"


def render_report(ev: dict, meta: dict, mentor: dict | None) -> str:
    lines = [f"# Evaluation report: {meta['data']}", "",
             f"- data seed: {meta.get('seed')}, as of {meta.get('as_of')}",
             f"- config.yaml SHA-256: `{meta['config_sha256']}`"
             f" ({'frozen: matches the locked hash' if meta['config_frozen'] else 'NOT frozen: tuning allowed'})",
             f"- flags: {ev['n_flags']}", "",
             "## Precision and recall", "",
             "n/a = undefined (no flags, or nothing planted for that detector).", "",
             "| Detector | TP | FP | FN | Precision | Recall |", "|---|---|---|---|---|---|"]
    lines += [_row(p, ev["per_detector"][p]) for p in PATTERNS] + [_row("**Overall**", ev["overall"]), ""]

    lines += ["## Recall on low-severity (`subtle`) diseases, the borderline cases", "",
              "| Scope | Planted | Found | Recall |", "|---|---|---|---|"]
    for name, r in ev["recall_low_severity"].items():
        lines.append(f"| {name} | {r['planted']} | {r['found']} | {_pct(r['recall'])} |")
    lines += ["", "## Recall by severity", "", "| Severity | Planted | Found | Recall |", "|---|---|---|---|"]
    for name, r in ev["recall_by_severity"].items():
        lines.append(f"| {name} | {r['planted']} | {r['found']} | {_pct(r['recall'])} |")

    lines += ["", "## Decoys", "",
              f"{len(ev['fell_for_decoy'])} false positives touch a decoy task (`slow_legit`, `backlog`, `abandoned`); "
              "they are also counted in FP above."]
    lines += [f"- `{f}`" for f in ev["fell_for_decoy"]]
    lines += ["", "## Outside stage A", "",
              f"{len(ev['out_of_scope_flags'])} flags hit a planted disease that stage A does not look for "
              "(`growing_bottleneck`, `stuck_subgraph`). They count neither as TP nor as FP."]
    lines += ["", "## Missed diseases (FN)", ""]
    lines += [f"- `{d}`" for d in ev["missed_diseases"]] or ["- none"]

    lines += ["", "## Ranked list", "",
              "Rows are ordered by severity, then by score. The score is a percentile inside its own detector, so a score "
              "of one detector is not comparable with a score of another; between detectors the order inside a severity "
              "is therefore only indicative. The list is a recommendation: nothing is written to Chain.", "",
              "## Mentor review (face validity)", ""]
    if mentor is None:
        lines.append("Waiting for the mentor: `mentor_review.csv` has not been filled in yet.")
    else:
        lines.append(f"{mentor['reasonable']} of {mentor['answered']} answered flags were marked reasonable "
                     f"({mentor['total']} in the sample).")
        lines += ["", "Comments:"] + [f"- `{c['flag_id']}` ({c['reasonable']}): {c['comment']}" for c in mentor["comments"]]
    return "\n".join(lines) + "\n"
