"""Classifies every word_recall miss (Tesseract --psm 11, FUNSD) by why it failed.

Run: python -m benchmarks.analyze_word_misses (from teams/mind/)

Answers the mentor's question directly: is the 64.7% word-recall ceiling
mostly real OCR failures, or mostly artifacts of an exact-match,
word-level metric? Takes every GT word that word_recall counted as a
miss, across both FUNSD documents, classifies each into one of 5
categories by heuristic, and prints the full breakdown + every example
for manual spot-checking (this is what get sent to the mentor).

Categories (checked in this order):
1. digit_merge    -- GT word is all-digits and appears as a contiguous
                      substring inside a longer all-digits OCR token
                      (e.g. GT "3428" inside OCR "432159428"). OCR read
                      the characters correctly, just merged two fields
                      that print adjacent to each other into one token.
2. segmentation   -- GT word is a short (<=5 char) substring/superstring
                      of the best-matching OCR word (e.g. GT "oh"
                      inside OCR "ohio"). OCR's content is right; the
                      GT annotation's word boundary just falls
                      differently than OCR's tokenization.
3. char_misread   -- best-matching OCR word is a close but garbled,
                      non-dictionary variant of the GT word (ratio ratio
                      >= 0.7, not a substring case) -- a real
                      character-level OCR error (e.g. "sireet").
4. different_word -- best match is a plausible, different token (ratio
                      0.4-0.7, not a substring relationship) -- OCR
                      produced wrong text, not just a garbled version of
                      the right text.
5. not_detected   -- no reasonably similar word anywhere in the OCR
                      output (best ratio < 0.4). Genuinely missed.

This is a heuristic classifier, not a linguistic one -- thresholds were
tuned by spot-checking ~20 examples by hand, not independently
validated at the full n. Good enough to answer "mostly near-misses or
mostly real gaps," not a publishable taxonomy. A few categorization
calls are still judgment calls -- flagged inline where relevant (e.g.
`comunication` vs `communication`: the GT annotation itself is
misspelled, so OCR reading it as `communication` is arguably *correct*,
not a miss at all -- the classifier still buckets it as char_misread
since it can't tell "GT typo" from "OCR typo" automatically).
"""

from __future__ import annotations

import difflib
import json
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from benchmarks.ocr_asr_benchmark import find_tesseract, load_manifest, run_ocr

_CATEGORY_ORDER = ["digit_merge", "segmentation", "char_misread", "different_word", "not_detected"]

_CATEGORY_LABEL = {
    "digit_merge": "Digits merged with an adjacent field (content correct, tokenization wrong)",
    "segmentation": "GT/OCR word-boundary mismatch (OCR's content is right)",
    "char_misread": "Character-level OCR misread (real recognition error)",
    "different_word": "OCR produced a different, unrelated token",
    "not_detected": "Not detected at all (nothing similar in OCR output)",
}


def _normalize(word: str) -> str:
    return re.sub(r"[^\w]", "", word).lower()


def gt_word_pairs(doc_id: str) -> list[tuple[str, str]]:
    """(original_text, normalized_text) for every GT word, in document order."""
    path = load_manifest()[doc_id]["annotation"]
    data = json.loads(path.read_text(encoding="utf-8"))
    pairs = []
    for block in data["form"]:
        for w in block["words"]:
            norm = _normalize(w["text"])
            if norm:
                pairs.append((w["text"], norm))
    return pairs


def classify(gt_norm: str, ocr_norm_tokens: list[str], best_match: str | None, best_ratio: float) -> str:
    if gt_norm.isdigit() and any(tok != gt_norm and tok.isdigit() and gt_norm in tok for tok in ocr_norm_tokens):
        return "digit_merge"
    if best_match and len(gt_norm) <= 5 and (gt_norm in best_match or best_match in gt_norm) and best_ratio >= 0.4:
        return "segmentation"
    if best_ratio >= 0.7:
        return "char_misread"
    if best_ratio >= 0.4:
        return "different_word"
    return "not_detected"


def analyze_doc(tesseract_cmd: str, doc_id: str) -> list[dict]:
    image_path = load_manifest()[doc_id]["image"]
    result = run_ocr(tesseract_cmd, image_path, ["--psm", "11"])
    ocr_raw_tokens = result["text"].split()
    ocr_norm_tokens = [_normalize(w) for w in ocr_raw_tokens if _normalize(w)]
    ocr_norm_set = set(ocr_norm_tokens)

    rows = []
    for gt_orig, gt_norm in gt_word_pairs(doc_id):
        if gt_norm in ocr_norm_set:
            continue  # word_recall hit -- not a miss, nothing to classify
        best_match, best_ratio = None, 0.0
        for tok in ocr_norm_tokens:
            ratio = difflib.SequenceMatcher(None, gt_norm, tok).ratio()
            if ratio > best_ratio:
                best_match, best_ratio = tok, ratio
        category = classify(gt_norm, ocr_norm_tokens, best_match, best_ratio)
        rows.append(
            {
                "doc": doc_id,
                "gt_word": gt_orig,
                "ocr_brought": best_match or "(nothing close)",
                "similarity": round(best_ratio, 2),
                "category": category,
            }
        )
    return rows


def main() -> None:
    tesseract_cmd = find_tesseract()
    doc_ids = list(load_manifest())
    all_rows: list[dict] = []
    for doc_id in doc_ids:
        all_rows.extend(analyze_doc(tesseract_cmd, doc_id))

    print(f"Total word_recall misses across {len(doc_ids)} documents (psm11): {len(all_rows)}\n")

    print("--- Breakdown by category ---")
    for cat in _CATEGORY_ORDER:
        cat_rows = [r for r in all_rows if r["category"] == cat]
        pct = 100 * len(cat_rows) / len(all_rows) if all_rows else 0
        print(f"{cat:16} {len(cat_rows):3d}/{len(all_rows)} ({pct:4.1f}%)  -- {_CATEGORY_LABEL[cat]}")

    print("\n--- Full example list (sent to the mentor as-is) ---")
    print(f"{'GT word':<20} {'OCR brought':<20} {'sim':>4}  category")
    print("-" * 70)
    for cat in _CATEGORY_ORDER:
        for r in all_rows:
            if r["category"] != cat:
                continue
            print(f"{r['gt_word']:<20} {r['ocr_brought']:<20} {r['similarity']:>4.2f}  {r['category']}")


if __name__ == "__main__":
    main()
