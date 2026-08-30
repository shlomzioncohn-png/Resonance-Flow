# MIND — OCR / ASR Local-Model Benchmark & Recommendation

## Scope

Only **OCR** is benchmarked this week. Of the two real-data slices
Foundation Gate needs (FUNSD for OCR, AMI for ASR), only the FUNSD
slice has actually been downloaded so far (see
`eval/datasets/.manifest.json`) — no AMI data exists locally yet. Per
the issue's own fallback ("if only one of the two is available,
benchmark it and complete the other in week 3"), ASR is deferred to
week 3 rather than blocking this week's OCR work.

## Setup

- **Tool:** [Tesseract](https://github.com/tesseract-ocr/tesseract) 5.4.0 (local, open-source, no GPU).
- **Data:** the 2 FUNSD documents already committed via
  `eval/datasets/.manifest.json`/`.sha256` (`82092117`,
  `82200067_0069`) — no new sample downloaded.
- **Configs:** 2 Tesseract page-segmentation modes (`--psm`):
  `3` (Tesseract's own default — fully automatic page segmentation)
  and `6` (assume a single uniform block of text).
- **Script:** `benchmarks/ocr_benchmark.py` — reproducible, prints the
  raw per-document numbers this table is built from.
- **Accuracy proxy:** word recall — fraction of FUNSD's ground-truth
  words (from the ` .json` annotations) that appear anywhere in
  Tesseract's output, case/punctuation-insensitive. Not a real OCR
  metric (no CER/WER, no position matching) — "did the same words come
  out," nothing more, per the issue's own "rough manual check" bar.
- **Resource footprint:** wall-clock latency (`time.perf_counter`) and
  a **sampled** peak RSS memory + CPU time for the `tesseract`
  subprocess itself, polled every 10ms via `psutil`. Python's stdlib
  `resource` module (suggested in the issue) doesn't exist on
  Windows, hence `psutil` instead — same rough intent, no profiler.
  Averaged over the 2 documents; 2 runs showed identical word recall
  and near-identical memory, with only minor latency noise run-to-run.

## Results

| Config | Latency (avg) | Peak RSS (avg) | CPU time (avg) | Word recall (avg) |
|---|---:|---:|---:|---:|
| `--psm 3` (default) | 0.74s | 33.8 MB | 0.63s | **56.0%** |
| `--psm 6` (single block) | 0.76s | 34.2 MB | 0.72s | 48.2% |

- **`--psm 3` (default):** Tesseract's automatic page segmentation
  finds and OCRs the scattered text regions on its own. Best word
  recall of the two, essentially tied on latency/memory.
- **`--psm 6` (single block):** Forces the whole page into one text
  block. Worse recall on both documents — expected, since FUNSD pages
  are forms with disconnected fields, not a single flowing block of
  text; forcing that assumption loses words the automatic segmentation
  would have caught. No speed or memory advantage to offset the
  accuracy loss.

## Recommendation

**Use `--psm 3` (Tesseract's default)** — it has the better accuracy
proxy (56.0% vs 48.2% word recall) with no meaningful cost in latency,
memory, or CPU time. `--psm 6`'s single-block assumption is a poor fit
for FUNSD-style forms specifically; it might still be worth a second
look for OCR inputs that genuinely are one uniform block of prose
(e.g. a scanned letter), but that's not this week's data.

## Open item

AMI (ASR) benchmark deferred to week 3, per scope above — this
document will get a matching Whisper section once that data exists
locally.
