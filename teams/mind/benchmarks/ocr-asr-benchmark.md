# MIND — OCR / ASR Local-Model Benchmark & Recommendation

## Purpose

Not "which config looks better" — a final, number-backed decision for
each modality, so extraction (week 4) knows exactly what to build
against, and Checkpoint 1 has real latency/resource numbers for
capacity planning.

## Scope

**OCR: done, 3 configs, final decision below.**
**ASR: blocked — see "Known risk."** No AMI (or any ASR) sample exists
anywhere in this repo, or on `main` — checked directly in the repo
right before writing this, not assumed. There's nothing to run Whisper
against yet.

## OCR — setup

- **Tool:** [Tesseract](https://github.com/tesseract-ocr/tesseract) 5.4.0 (local, open-source, no GPU).
- **Data:** the 2 FUNSD documents already committed via
  `eval/datasets/.manifest.json`/`.sha256` (`82092117`,
  `82200067_0069`) — no new sample downloaded.
- **Configs:** 3 Tesseract page-segmentation modes (`--psm`) — a third
  was added specifically to check whether accuracy keeps improving or
  plateaus/regresses past the first two, instead of stopping at "X
  looks better than Y":
  - `3` — Tesseract's own default (fully automatic page segmentation).
  - `6` — assume a single uniform block of text.
  - `11` — sparse text: find as much text as possible, in no
    particular order. Targets exactly FUNSD's layout (scattered,
    disconnected form fields, not flowing prose or one block).
- **Script:** `benchmarks/ocr_asr_benchmark.py` — reproducible;
  `python -m benchmarks.ocr_asr_benchmark` prints the raw per-document
  numbers this table is built from, for both modalities, ending in an
  explicit conclusion line.
- **Accuracy proxy:** word recall — fraction of FUNSD's ground-truth
  words (from the `.json` annotations) that appear anywhere in
  Tesseract's output, case/punctuation-insensitive. Not a real OCR
  metric (no CER/WER, no position matching) — "did the same words come
  out," nothing more.
- **Resource footprint:** wall-clock latency (`time.perf_counter`) and
  a **sampled** peak RSS memory + CPU time for the `tesseract`
  subprocess, polled every 10ms via `psutil`. Python's stdlib
  `resource` module doesn't exist on Windows, hence `psutil` instead —
  same rough intent, no profiler. Averaged over the 2 documents; 3 runs
  showed identical word recall and near-identical memory, with only
  minor latency noise run-to-run.

## OCR — results

| Config | Latency (avg) | Peak RSS (avg) | CPU time (avg) | Word recall (avg) |
|---|---:|---:|---:|---:|
| `--psm 3` (default) | 1.00s | 34.0 MB | 0.45s | 56.0% |
| `--psm 6` (single block) | 0.57s | 33.1 MB | 0.55s | 48.2% |
| **`--psm 11` (sparse text)** | **0.48s** | **32.8 MB** | **0.44s** | **64.7%** |

- **`--psm 3` (default):** automatic segmentation. Middling recall,
  and the slowest of the three in this run (latency noise between runs
  is real, but it never wins on speed either).
- **`--psm 6` (single block):** forces one block — worst recall of the
  three, as expected for form layouts with disconnected fields. No
  speed/memory advantage to offset the accuracy loss.
- **`--psm 11` (sparse text):** best on every axis measured here —
  highest recall *and* fastest *and* lowest memory. Matches the
  intuition: FUNSD pages are exactly the "scattered, disconnected
  text" case this mode targets, more so than "single block" or generic
  "automatic."

This is also the concrete value of the third config the issue asked
for: testing only `3` vs `6` (previous version of this document) would
have shipped **the wrong recommendation** — `psm3_default` looked best
of *those two*, but `psm11_sparse_text` beats it by 8.7 points of
recall once actually tried. Accuracy did not plateau between
configs 2 and 3; it kept improving, which is exactly the failure mode
of stopping at two that the issue warned about.

## OCR — final decision

**Use `--psm 11` (sparse text) for OCR.** Best word-recall proxy
(64.7%, vs. 56.0% for the default and 48.2% for single-block), and also
the fastest and lightest of the three in this run — no tradeoff to
weigh, it wins outright on every axis measured.

**Resource budget for capacity planning (Checkpoint 1):** ~0.5s
latency, ~33MB peak memory, ~0.45s CPU time per FUNSD-sized page, on
this dev machine (no GPU, no load — a shared/production host will
differ). Scale by expected page count and concurrency for real
capacity numbers once volume is known.

## ASR — known risk

**No decision. Blocked on data, not on tooling.** Local Whisper was the
intended choice per team direction (local models only), but there is
no AMI sample (or any audio sample) anywhere in this repo to benchmark
against — confirmed by checking `eval/datasets/` directly, not assumed
from a prior conversation. Whisper itself was never installed or run.

This is a real, open risk for Checkpoint 1 capacity planning, not a
recommendation dressed up to sound more certain than it is: **do not
assume Whisper `base` (today's placeholder in `.env.example`) is
correct** until this is actually measured. Unblocking this needs either
(a) a real AMI sample landing in `eval/datasets/`, or (b) an explicit
decision to download a small sample outside the normal "don't fetch new
data" rule, since none exists to reuse.

## Open item

Once AMI data exists, benchmark Whisper (at least 2 sizes, e.g.
`tiny`/`base`, plus a third per the same "don't stop at two" reasoning
above) the same way this document does for OCR, and replace this
section with a final decision.
