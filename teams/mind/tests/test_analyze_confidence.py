"""Tests for benchmarks/analyze_confidence.py -- how misses are tallied.

The per-category counts in the OCR decision doc come from collect_confidences().
It used to key the misses by ground-truth word, so a word missed twice on a page
overwrote itself and a row was silently lost (11 of 98 on the FUNSD slice).
"""

from benchmarks.analyze_confidence import collect_confidences
from benchmarks.analyze_word_misses import MissRow


def miss(gt_word: str, ocr_brought: str, category: str = "char_misread") -> MissRow:
    return MissRow(
        doc="d", gt_word=gt_word, ocr_brought=ocr_brought, similarity=0.8, category=category  # type: ignore[arg-type]
    )


def test_a_word_missed_twice_keeps_both_rows():
    # form documents repeat words ("name", "5", ...); both misses must count
    misses = [miss("NAME", "me"), miss("NAME", "me")]
    _, by_category = collect_confidences(
        [("NAME", "name"), ("NAME", "name")], misses, {"me": [40.0]}
    )
    assert by_category["char_misread"] == [40.0, 40.0]


def test_each_miss_row_is_counted_in_its_own_category():
    misses = [miss("5", "15", "digit_merge"), miss("5", "12", "different_word")]
    _, by_category = collect_confidences(
        [("5", "5")], misses, {"15": [50.0], "12": [70.0]}
    )
    assert by_category == {"digit_merge": [50.0], "different_word": [70.0]}


def test_hits_exclude_words_that_were_missed_and_include_the_rest():
    gt = [("street", "street"), ("name", "name")]
    hits, _ = collect_confidences(gt, [miss("name", "me")], {"street": [90.0], "name": [10.0]})
    assert hits == [90.0]  # "name" is a miss, so its confidence is not a hit's


def test_a_miss_whose_brought_token_has_no_confidence_is_skipped_not_an_error():
    _, by_category = collect_confidences([("x", "x")], [miss("x", "zzz")], {"other": [1.0]})
    assert by_category == {}


def test_analysis_scripts_use_the_chosen_psm_not_a_hardcoded_one(monkeypatch):
    # A future PSM change in ocr_asr_benchmark.CHOSEN_PSM_ARGS must reach both
    # analysis scripts; hardcoding "--psm 11" there would silently keep analysing psm11.
    import subprocess
    from pathlib import Path
    from types import SimpleNamespace

    from benchmarks import analyze_confidence, analyze_word_misses

    marker = ["--psm", "99"]
    monkeypatch.setattr(analyze_confidence, "CHOSEN_PSM_ARGS", marker)
    monkeypatch.setattr(analyze_word_misses, "CHOSEN_PSM_ARGS", marker)

    commands: list[list[str]] = []

    def fake_run(cmd, **kwargs):
        commands.append(cmd)
        return SimpleNamespace(stdout=b"header\n")

    monkeypatch.setattr(subprocess, "run", fake_run)
    analyze_confidence.tesseract_confidences("tesseract", Path("page.png"))
    assert commands[0][-3:-1] == marker

    seen: list[list[str]] = []

    def fake_run_ocr(cmd, image_path, psm_args):
        seen.append(psm_args)
        raise StopIteration  # enough: we only need the psm_args analyze_doc passed

    monkeypatch.setattr(analyze_word_misses, "run_ocr", fake_run_ocr)
    monkeypatch.setattr(
        analyze_word_misses, "load_manifest", lambda: {"d": SimpleNamespace(image=Path("p.png"))}
    )
    try:
        analyze_word_misses.analyze_doc("tesseract", "d")
    except StopIteration:
        pass
    assert seen == [marker]


def test_chosen_psm_is_the_psm11_config_entry():
    from benchmarks.ocr_asr_benchmark import CHOSEN_PSM_ARGS, OCR_CONFIGS

    assert OCR_CONFIGS["psm11_sparse_text"] == CHOSEN_PSM_ARGS


def test_all_three_analyses_share_one_normalize():
    # word_recall, the miss classification and the confidence numbers must
    # normalise words identically; separate copies could drift silently.
    from benchmarks import (
        aligned_metrics,
        analyze_confidence,
        analyze_word_misses,
        ocr_asr_benchmark,
    )

    assert analyze_confidence.normalize is aligned_metrics.normalize
    assert analyze_word_misses.normalize is aligned_metrics.normalize
    assert ocr_asr_benchmark._normalize is aligned_metrics.normalize
