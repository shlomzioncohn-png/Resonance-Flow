"""Checks whether Tesseract's own per-word confidence predicts word_recall misses.

Run: python -m benchmarks.analyze_confidence (from teams/mind/)

The mentor's proposal for handling the residual OCR errors: have OCR emit a
per-word confidence alongside each recognized word, so extraction can
treat low-confidence words differently (flag, re-check, discount) instead
of trusting every word equally. Tesseract already computes this -- no
new tool needed, just a different output mode (`tsv`, one row per word
with a 0-100 `conf` column).

This script tests whether that confidence is actually a useful signal
here: is Tesseract itself "less sure" about the words that turned out
wrong (per analyze_word_misses.py's classification) than about the
words it got right? If yes, confidence-based filtering is a real,
usable mitigation -- not just a nice API shape.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from benchmarks.aligned_metrics import normalize
from benchmarks.analyze_word_misses import MissRow, analyze_doc, gt_word_pairs
from benchmarks.ocr_asr_benchmark import CHOSEN_PSM_ARGS, find_tesseract, load_manifest


def tesseract_confidences(tesseract_cmd: str, image_path: Path) -> dict[str, list[float]]:
    """normalized word -> list of confidences (0-100) Tesseract assigned it.

    A list, not a single value, because the same normalized word can
    appear more than once on a page with different confidences.
    """
    cmd = [tesseract_cmd, str(image_path), "stdout", *CHOSEN_PSM_ARGS, "tsv"]
    proc = subprocess.run(cmd, capture_output=True, check=True)
    lines = proc.stdout.decode("utf-8", errors="replace").splitlines()
    conf_by_word: dict[str, list[float]] = {}
    for line in lines[1:]:  # skip header
        fields = line.split("\t")
        if len(fields) != 12:
            continue
        level, conf_str, text = fields[0], fields[10], fields[11]
        if level != "5":  # level 5 = individual recognized word
            continue
        norm = normalize(text)
        conf = float(conf_str)
        if norm and conf >= 0:
            conf_by_word.setdefault(norm, []).append(conf)
    return conf_by_word


def collect_confidences(
    gt_pairs: list[tuple[str, str]],
    misses: list[MissRow],
    conf_by_word: dict[str, list[float]],
) -> tuple[list[float], dict[str, list[float]]]:
    """Split one page's confidences into hits and per-category misses.

    `misses` is a *list*, one row per missed ground-truth word occurrence. It
    used to be a dict keyed by the GT word text, so a word missed twice on a
    page (form documents repeat "date", "name", "5"...) overwrote itself and
    lost a row -- 11 of 98 rows on the FUNSD slice, which also fed the
    per-category counts in the decision doc (review feedback on PR #345).

    Both returned collections count *confidence values*, not miss rows: a row
    contributes every confidence Tesseract gave the token it produced, so a
    token that appears several times on the page adds several values.
    """
    miss_gt_norms = {normalize(row.gt_word) for row in misses}

    hits: list[float] = []
    for _, gt_norm in gt_pairs:
        if gt_norm in miss_gt_norms:
            continue  # a miss -- counted via its row below
        if gt_norm in conf_by_word:
            hits.extend(conf_by_word[gt_norm])

    by_category: dict[str, list[float]] = {}
    for row in misses:
        brought_norm = normalize(row.ocr_brought)
        if brought_norm in conf_by_word:
            by_category.setdefault(row.category, []).extend(conf_by_word[brought_norm])
    return hits, by_category


# The cut-off other code (PR #509) uses to call a word low-confidence; printed
# here as a share per group so a threshold can be checked against the
# distribution, not just the averages.
REFERENCE_THRESHOLD = 60.0


def main() -> None:
    tesseract_cmd = find_tesseract()

    hit_confidences: list[float] = []
    miss_confidences: dict[str, list[float]] = {}

    for doc_id, paths in load_manifest().items():
        conf_by_word = tesseract_confidences(tesseract_cmd, paths.image)
        hits, by_category = collect_confidences(
            gt_word_pairs(doc_id), analyze_doc(tesseract_cmd, doc_id), conf_by_word
        )
        hit_confidences.extend(hits)
        for category, confs in by_category.items():
            miss_confidences.setdefault(category, []).extend(confs)

    def avg(xs: list[float]) -> float:
        return sum(xs) / len(xs) if xs else float("nan")

    def below(xs: list[float]) -> float:
        return sum(x < REFERENCE_THRESHOLD for x in xs) / len(xs) if xs else float("nan")

    print(
        f"Correctly-recalled words (word_recall hits): n={len(hit_confidences)}, "
        f"avg confidence={avg(hit_confidences):.1f}, "
        f"share below {REFERENCE_THRESHOLD:.0f}: {below(hit_confidences):.0%}"
    )
    print()
    print("Confidence of the token Tesseract actually produced, for each miss category")
    print("(n = confidence values, not miss rows):")
    for category, confs in miss_confidences.items():
        print(
            f"  {category:16} n={len(confs):3d}  avg confidence={avg(confs):.1f}  "
            f"share below {REFERENCE_THRESHOLD:.0f}: {below(confs):.0%}"
        )

    # Averages alone hide how much the distributions overlap. For a few cut-offs:
    # how many correctly-read words would be flagged (false flags) vs. how many
    # of the real-error values (char_misread + different_word + not_detected --
    # excluding digit_merge/segmentation, whose content is mostly correct).
    real_errors = [
        c
        for cat in ("char_misread", "different_word", "not_detected")
        for c in miss_confidences.get(cat, [])
    ]
    print(f"\nFlagging words below a cut-off (hits n={len(hit_confidences)}, "
          f"real-error values n={len(real_errors)}):")
    print(f"  {'cut-off':>8} {'correct words flagged':>22} {'real errors caught':>20}")
    for cutoff in (40.0, 50.0, 60.0, 70.0, 80.0):
        false_flags = sum(c < cutoff for c in hit_confidences) / len(hit_confidences)
        caught = sum(c < cutoff for c in real_errors) / len(real_errors)
        print(f"  {cutoff:>8.0f} {false_flags:>21.0%} {caught:>19.0%}")


if __name__ == "__main__":
    main()
