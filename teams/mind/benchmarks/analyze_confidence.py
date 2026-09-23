"""Checks whether Tesseract's own per-word confidence predicts word_recall misses.

Run: python -m benchmarks.analyze_confidence (from teams/mind/)

The mentor's proposal for handling the residual ~9% character-level
error (100% - the 91.2% char_similarity score): have OCR emit a
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

import re
import subprocess
import sys
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from benchmarks.analyze_word_misses import analyze_doc, gt_word_pairs
from benchmarks.ocr_asr_benchmark import find_tesseract, load_manifest


def _normalize(word: str) -> str:
    return re.sub(r"[^\w]", "", word).lower()


def tesseract_confidences(tesseract_cmd: str, image_path: Path) -> dict[str, list[float]]:
    """normalized word -> list of confidences (0-100) Tesseract assigned it.

    A list, not a single value, because the same normalized word can
    appear more than once on a page with different confidences.
    """
    cmd = [tesseract_cmd, str(image_path), "stdout", "--psm", "11", "tsv"]
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
        norm = _normalize(text)
        conf = float(conf_str)
        if norm and conf >= 0:
            conf_by_word.setdefault(norm, []).append(conf)
    return conf_by_word


def main() -> None:
    tesseract_cmd = find_tesseract()

    hit_confidences: list[float] = []
    miss_confidences: dict[str, list[float]] = {}

    for doc_id, paths in load_manifest().items():
        conf_by_word = tesseract_confidences(tesseract_cmd, paths.image)

        misses = {row.gt_word: row for row in analyze_doc(tesseract_cmd, doc_id)}
        miss_gt_norms = {_normalize(orig) for orig in misses}

        for gt_orig, gt_norm in gt_word_pairs(doc_id):
            if gt_norm in miss_gt_norms:
                continue  # handled via the miss table below
            if gt_norm in conf_by_word:
                hit_confidences.extend(conf_by_word[gt_norm])

        for row in misses.values():
            brought_norm = _normalize(row.ocr_brought)
            if brought_norm in conf_by_word:
                miss_confidences.setdefault(row.category, []).extend(conf_by_word[brought_norm])

    def avg(xs: list[float]) -> float:
        return sum(xs) / len(xs) if xs else float("nan")

    print(f"Correctly-recalled words (word_recall hits): n={len(hit_confidences)}, avg confidence={avg(hit_confidences):.1f}")
    print()
    print("Confidence of the token Tesseract actually produced, for each miss category:")
    for category, confs in miss_confidences.items():
        print(f"  {category:16} n={len(confs):3d}  avg confidence={avg(confs):.1f}")


if __name__ == "__main__":
    main()
