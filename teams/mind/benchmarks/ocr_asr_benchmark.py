"""OCR + ASR local-model benchmark.

Run: python -m benchmarks.ocr_asr_benchmark (from teams/mind/)

OCR: Tesseract in 3 page-segmentation-mode (PSM) configurations, run on
the FUNSD Foundation Gate real-data slice (the same 2 documents already
committed via eval/datasets/.manifest.json -- see eval/datasets/README.md;
no new data downloaded for this).

ASR: BLOCKED. No AMI (or any ASR) sample exists anywhere in this repo or
on `main` -- checked again right before writing this. There is nothing
to run Whisper against yet. Documented here as a known risk (see
ocr-asr-benchmark.md "Known risk"), not silently skipped.

Deliberately rough throughout (see ocr-asr-benchmark.md): wall-clock
latency, a sampled peak-RSS/CPU-time footprint via psutil (Python's
stdlib `resource` module doesn't exist on Windows), and a word-overlap
accuracy proxy against FUNSD's ground truth -- not a real OCR metric
(CER/WER), just "did the same words come out".
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import time
from pathlib import Path

import psutil

_HERE = Path(__file__).parent
DATA_DIR = _HERE.parent / "local-data" / "funsd" / "dataset" / "testing_data"
DOCS = ["82092117", "82200067_0069"]

# Three PSM configs (issue asked for at least 2; a third closes the
# "does accuracy keep improving, or does it plateau/regress" question
# instead of stopping at the first two).
# 3  = Tesseract's own default: fully automatic page segmentation.
# 6  = assume a single uniform block of text.
# 11 = sparse text: find as much text as possible, in no particular
#      order -- FUNSD pages are forms with scattered, disconnected
#      fields, which is exactly the layout this mode targets.
OCR_CONFIGS = {
    "psm3_default": ["--psm", "3"],
    "psm6_single_block": ["--psm", "6"],
    "psm11_sparse_text": ["--psm", "11"],
}

_WINDOWS_DEFAULT_INSTALL = r"C:\Program Files\Tesseract-OCR\tesseract.exe"


def find_tesseract() -> str:
    found = shutil.which("tesseract")
    if found:
        return found
    if Path(_WINDOWS_DEFAULT_INSTALL).exists():
        return _WINDOWS_DEFAULT_INSTALL
    raise RuntimeError("tesseract not found on PATH or at the default Windows install path")


def _normalize(word: str) -> str:
    return re.sub(r"[^\w]", "", word).lower()


def ground_truth_words(doc_id: str) -> list[str]:
    path = DATA_DIR / "annotations" / f"{doc_id}.json"
    with path.open(encoding="utf-8") as f:
        data = json.load(f)
    words: list[str] = []
    for block in data["form"]:
        for w in block["words"]:
            normalized = _normalize(w["text"])
            if normalized:
                words.append(normalized)
    return words


def run_ocr(tesseract_cmd: str, image_path: Path, psm_args: list[str]) -> dict:
    """Run tesseract as a subprocess and sample its memory/CPU while it runs.

    Sampling every 10ms is rough (may miss a very brief true peak on a
    fast run) -- adequate for this week's comparison, not a substitute
    for a real profiler.
    """
    cmd = [tesseract_cmd, str(image_path), "stdout", *psm_args]
    start = time.perf_counter()
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    ps_proc = psutil.Process(proc.pid)
    peak_rss = 0
    cpu_time = 0.0
    while proc.poll() is None:
        try:
            peak_rss = max(peak_rss, ps_proc.memory_info().rss)
            times = ps_proc.cpu_times()
            cpu_time = times.user + times.system
        except psutil.NoSuchProcess:
            break
        time.sleep(0.01)
    stdout, stderr = proc.communicate()
    duration = time.perf_counter() - start
    if proc.returncode != 0:
        raise RuntimeError(f"tesseract failed: {stderr.decode(errors='replace')}")
    return {
        "text": stdout.decode("utf-8", errors="replace"),
        "duration_s": duration,
        "peak_rss_mb": peak_rss / (1024 * 1024),
        "cpu_time_s": cpu_time,
    }


def word_recall(ocr_text: str, gt_words: list[str]) -> float:
    """Fraction of ground-truth words that appear somewhere in the OCR
    output (order-insensitive, case/punctuation-insensitive). A rough
    proxy, not CER/WER."""
    ocr_words = {_normalize(w) for w in ocr_text.split() if _normalize(w)}
    gt_set = set(gt_words)
    if not gt_set:
        return 0.0
    return len(gt_set & ocr_words) / len(gt_set)


def run_ocr_benchmark() -> dict[str, dict]:
    tesseract_cmd = find_tesseract()
    print(f"tesseract: {tesseract_cmd}\n")

    rows = []
    for config_name, psm_args in OCR_CONFIGS.items():
        for doc_id in DOCS:
            image_path = DATA_DIR / "images" / f"{doc_id}.png"
            gt_words = ground_truth_words(doc_id)
            result = run_ocr(tesseract_cmd, image_path, psm_args)
            recall = word_recall(result["text"], gt_words)
            row = {
                "config": config_name,
                "doc": doc_id,
                "duration_s": round(result["duration_s"], 3),
                "peak_rss_mb": round(result["peak_rss_mb"], 1),
                "cpu_time_s": round(result["cpu_time_s"], 3),
                "word_recall": round(recall, 3),
                "gt_word_count": len(gt_words),
            }
            rows.append(row)
            print(row)

    print("\n--- OCR per-config averages ---")
    averages: dict[str, dict] = {}
    for config_name in OCR_CONFIGS:
        config_rows = [r for r in rows if r["config"] == config_name]
        n = len(config_rows)
        avg = {
            "duration_s": sum(r["duration_s"] for r in config_rows) / n,
            "peak_rss_mb": sum(r["peak_rss_mb"] for r in config_rows) / n,
            "cpu_time_s": sum(r["cpu_time_s"] for r in config_rows) / n,
            "word_recall": sum(r["word_recall"] for r in config_rows) / n,
        }
        averages[config_name] = avg
        print(
            f"{config_name}: duration={avg['duration_s']:.3f}s "
            f"peak_rss={avg['peak_rss_mb']:.1f}MB cpu_time={avg['cpu_time_s']:.3f}s "
            f"word_recall={avg['word_recall']:.3f}"
        )
    return averages


def run_asr_benchmark() -> None:
    print("\n--- ASR (Whisper) ---")
    print(
        "BLOCKED: no AMI (or any ASR) sample exists in this repo or on `main` "
        "(checked eval/datasets/ -- only FUNSD is there). Nothing to run "
        "Whisper against. See ocr-asr-benchmark.md 'Known risk'."
    )


def main() -> None:
    ocr_averages = run_ocr_benchmark()
    run_asr_benchmark()

    best_config = max(ocr_averages, key=lambda c: ocr_averages[c]["word_recall"])
    best = ocr_averages[best_config]
    print("\n--- Conclusion ---")
    print(
        f"OCR: use {best_config} -- best word recall "
        f"({best['word_recall']:.3f}) among {list(ocr_averages)}, "
        f"duration={best['duration_s']:.3f}s, peak_rss={best['peak_rss_mb']:.1f}MB. "
        "ASR: NO DECISION -- blocked on missing AMI data (known risk, see doc)."
    )


if __name__ == "__main__":
    main()
