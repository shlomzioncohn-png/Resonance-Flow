"""OCR + ASR local-model benchmark.

Run: python -m benchmarks.ocr_asr_benchmark (from teams/mind/)

OCR: Tesseract in 3 page-segmentation-mode (PSM) configs, plus PaddleOCR
in 2 model-size configs (mobile/server) -- all run on the FUNSD
Foundation Gate real-data slice (the same 2 documents already committed
via eval/datasets/.manifest.json -- see eval/datasets/README.md; no new
data downloaded for this).

PaddleOCR requires 4 model packages (mobile+server, det+rec) that
PaddleOCR/PaddleX normally auto-download from HuggingFace/ModelScope/BOS
on first use -- all three were blocked on this dev network by a NetFree
content filter (confirmed: same "418 Blocked by NetFree" response from
all three hosters). Downloaded manually on an unfiltered network instead
and placed under the local PaddleX model cache
(~/.paddlex/official_models/<model_name>/, extracted from the hosters'
own `<model_name>_infer.tar`) -- see "PaddleOCR -- setup" in
ocr-asr-benchmark.md for the exact URLs. `enable_mkldnn=False` below
works around an unrelated NotImplementedError crash in this
paddlepaddle build's oneDNN/CPU backend; it makes both PaddleOCR configs
slower than PaddleOCR is capable of on hardware where mkldnn works, so
its latency numbers are a floor for this machine, not a ceiling for the
tool.

ASR: BLOCKED. No AMI audio (or any ASR) sample exists anywhere in this
repo or on any branch -- checked again right before writing this. An
AMI package does exist on `michal/real-data-slice`, but it's the
manual-annotation package only (abstractive summaries, XML) -- no
audio, by design (it's for a different ticket, extraction ground
truth). There is nothing to run Whisper against yet. Documented here as
a known risk (see ocr-asr-benchmark.md "Known risk"), not silently
skipped.

Deliberately rough throughout (see ocr-asr-benchmark.md): wall-clock
latency, a sampled peak-RSS/CPU-time footprint via psutil (Python's
stdlib `resource` module doesn't exist on Windows), and two accuracy
proxies against FUNSD's ground truth -- neither is a real OCR metric
(no CER/WER with proper alignment, no position matching):
- word_recall: exact-match, "did the same word come out at all."
- char_similarity: per-GT-word best character-level similarity
  (difflib.SequenceMatcher ratio) to *any* word OCR produced -- added
  after word_recall alone made 3 near-miss configs (e.g. `street` read
  as `sireet`) look like outright failures instead of near-hits. Not a
  substitute for real CER (no positional alignment -- deliberately, so
  it still means something for psm11's "no particular order" output),
  just a second, more forgiving lens on the same output.
"""

from __future__ import annotations

import difflib
import json
import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

import psutil

if hasattr(sys.stdout, "reconfigure"):
    # Windows consoles default to a codepage (e.g. cp1255) that can't
    # encode every character PaddleOCR/Tesseract output -- avoid a crash
    # on print() alone.
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

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

# Two PaddleOCR model-size configs -- the "mobile vs. server model" the
# issue asked for. Both are PP-OCRv5 (PaddleOCR's current generation);
# "mobile"/"server" is model capacity, not an actual deploy target --
# both run on this CPU dev machine.
PADDLE_CONFIGS = {
    "paddleocr_mobile": {
        "text_detection_model_name": "PP-OCRv5_mobile_det",
        "text_recognition_model_name": "PP-OCRv5_mobile_rec",
    },
    "paddleocr_server": {
        "text_detection_model_name": "PP-OCRv5_server_det",
        "text_recognition_model_name": "PP-OCRv5_server_rec",
    },
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


def char_similarity_score(ocr_text: str, gt_words: list[str]) -> float:
    """Average, over every GT word, of its best character-level
    similarity (difflib ratio, 0-1) to any single word OCR produced.

    Gives partial credit for near-misses that word_recall scores as a
    complete failure (`street` read as `sireet` -> ~0.83, not 0). Not
    positional -- doesn't assume matching order between GT and OCR
    output -- which is what makes it usable for psm11 ("no particular
    order") too, unlike a proper aligned CER."""
    ocr_words = [_normalize(w) for w in ocr_text.split() if _normalize(w)]
    if not gt_words:
        return 0.0
    if not ocr_words:
        return 0.0
    scores = [
        max(difflib.SequenceMatcher(None, gt_word, ocr_word).ratio() for ocr_word in ocr_words)
        for gt_word in gt_words
    ]
    return sum(scores) / len(scores)


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
            char_sim = char_similarity_score(result["text"], gt_words)
            row = {
                "config": config_name,
                "doc": doc_id,
                "duration_s": round(result["duration_s"], 3),
                "peak_rss_mb": round(result["peak_rss_mb"], 1),
                "cpu_time_s": round(result["cpu_time_s"], 3),
                "word_recall": round(recall, 3),
                "char_similarity": round(char_sim, 3),
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
            "char_similarity": sum(r["char_similarity"] for r in config_rows) / n,
        }
        averages[config_name] = avg
        print(
            f"{config_name}: duration={avg['duration_s']:.3f}s "
            f"peak_rss={avg['peak_rss_mb']:.1f}MB cpu_time={avg['cpu_time_s']:.3f}s "
            f"word_recall={avg['word_recall']:.3f} char_similarity={avg['char_similarity']:.3f}"
        )
    return averages


def _sample_current_process(stop_event: threading.Event, proc: psutil.Process, peak: list[int]) -> None:
    while not stop_event.is_set():
        try:
            peak[0] = max(peak[0], proc.memory_info().rss)
        except psutil.NoSuchProcess:
            break
        time.sleep(0.05)


def run_paddle_ocr(ocr, image_path: Path) -> dict:
    """Run one already-loaded PaddleOCR pipeline on one image, sampling
    this process's own memory/CPU while it runs.

    Unlike Tesseract (a separate subprocess we sample from outside),
    PaddleOCR runs in-process -- so "peak RSS" here is the whole
    interpreter's memory (already includes the loaded model, numpy,
    paddle, etc.), not an isolated OCR-only number. Not apples-to-apples
    with the Tesseract subprocess numbers for that reason; still the
    right number for capacity planning ("what must this process's host
    have while OCRing").
    """
    proc = psutil.Process()
    stop_event = threading.Event()
    peak = [proc.memory_info().rss]
    sampler = threading.Thread(target=_sample_current_process, args=(stop_event, proc, peak))
    cpu_before = proc.cpu_times()
    sampler.start()
    start = time.perf_counter()
    results = list(ocr.predict(str(image_path)))
    duration = time.perf_counter() - start
    stop_event.set()
    sampler.join()
    cpu_after = proc.cpu_times()
    cpu_time = (cpu_after.user - cpu_before.user) + (cpu_after.system - cpu_before.system)

    texts: list[str] = []
    for res in results:
        texts.extend(res.get("rec_texts", []) or [])
    return {
        "text": " ".join(texts),
        "duration_s": duration,
        "peak_rss_mb": peak[0] / (1024 * 1024),
        "cpu_time_s": cpu_time,
    }


def run_paddle_benchmark() -> dict[str, dict]:
    """Benchmark PaddleOCR in the 2 model-size configs in PADDLE_CONFIGS.

    Requires the 4 PP-OCRv5 model packages already present locally under
    ~/.paddlex/official_models/ -- see the module docstring and
    ocr-asr-benchmark.md "PaddleOCR -- setup" for why (network block)
    and exactly how they were placed there.
    """
    from paddleocr import PaddleOCR  # heavy, optional import -- see [benchmark] extra

    rows = []
    for config_name, model_names in PADDLE_CONFIGS.items():
        print(f"\nLoading {config_name}...")
        load_start = time.perf_counter()
        ocr = PaddleOCR(
            **model_names,
            use_doc_orientation_classify=False,
            use_doc_unwarping=False,
            use_textline_orientation=False,
            enable_mkldnn=False,  # works around a oneDNN crash on this machine -- see module docstring
        )
        load_s = time.perf_counter() - load_start
        print(f"{config_name}: model load took {load_s:.1f}s (one-time cost -- not counted in per-page latency below)")

        for doc_id in DOCS:
            image_path = DATA_DIR / "images" / f"{doc_id}.png"
            gt_words = ground_truth_words(doc_id)
            result = run_paddle_ocr(ocr, image_path)
            recall = word_recall(result["text"], gt_words)
            char_sim = char_similarity_score(result["text"], gt_words)
            row = {
                "config": config_name,
                "doc": doc_id,
                "duration_s": round(result["duration_s"], 3),
                "peak_rss_mb": round(result["peak_rss_mb"], 1),
                "cpu_time_s": round(result["cpu_time_s"], 3),
                "word_recall": round(recall, 3),
                "char_similarity": round(char_sim, 3),
                "gt_word_count": len(gt_words),
            }
            rows.append(row)
            print(row)

    print("\n--- PaddleOCR per-config averages ---")
    averages: dict[str, dict] = {}
    for config_name in PADDLE_CONFIGS:
        config_rows = [r for r in rows if r["config"] == config_name]
        n = len(config_rows)
        avg = {
            "duration_s": sum(r["duration_s"] for r in config_rows) / n,
            "peak_rss_mb": sum(r["peak_rss_mb"] for r in config_rows) / n,
            "cpu_time_s": sum(r["cpu_time_s"] for r in config_rows) / n,
            "word_recall": sum(r["word_recall"] for r in config_rows) / n,
            "char_similarity": sum(r["char_similarity"] for r in config_rows) / n,
        }
        averages[config_name] = avg
        print(
            f"{config_name}: duration={avg['duration_s']:.3f}s "
            f"peak_rss={avg['peak_rss_mb']:.1f}MB cpu_time={avg['cpu_time_s']:.3f}s "
            f"word_recall={avg['word_recall']:.3f} char_similarity={avg['char_similarity']:.3f}"
        )
    return averages


def run_asr_benchmark() -> None:
    print("\n--- ASR (Whisper) ---")
    print(
        "BLOCKED: no AMI audio (or any ASR) sample exists in this repo or on "
        "any branch. `michal/real-data-slice` has an AMI package, but it's "
        "manual-annotation text only (abstractive summaries, XML) -- no "
        "audio, by design (ground truth for a different ticket). Nothing to "
        "run Whisper against. See ocr-asr-benchmark.md 'Known risk'."
    )


def main() -> None:
    tesseract_averages = run_ocr_benchmark()
    paddle_averages = run_paddle_benchmark()
    run_asr_benchmark()

    all_averages = {**tesseract_averages, **paddle_averages}
    best_config = max(all_averages, key=lambda c: all_averages[c]["word_recall"])
    best = all_averages[best_config]
    print("\n--- Conclusion ---")
    print(
        f"OCR: best word recall is {best_config} "
        f"({best['word_recall']:.3f}, char_similarity={best['char_similarity']:.3f}) "
        f"among {list(all_averages)}, duration={best['duration_s']:.3f}s, "
        f"peak_rss={best['peak_rss_mb']:.1f}MB. Recall alone doesn't decide this "
        "when latency differs by orders of magnitude -- see "
        "ocr-asr-benchmark.md for the reasoned recommendation.\n"
        "ASR: NO DECISION -- blocked on missing AMI audio (known risk, see doc)."
    )


if __name__ == "__main__":
    main()
