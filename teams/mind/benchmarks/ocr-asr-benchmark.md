# MIND — OCR / ASR Local-Model Benchmark & Recommendation

## Purpose

Not "which config looks better" — a final, number-backed decision for
each modality, so extraction (week 4) knows exactly what to build
against, and Checkpoint 1 has real latency/resource numbers for
capacity planning.

## Scope

**OCR: done, 5 configs (3 Tesseract + 2 PaddleOCR), final decision below.**
**ASR: blocked — see "Known risk."** No AMI audio (or any ASR) sample
exists anywhere in this repo, or on any branch — checked directly
right before writing this, not assumed. There's nothing to run Whisper
against yet.

## OCR — setup

- **Tools:**
  - [Tesseract](https://github.com/tesseract-ocr/tesseract) 5.4.0 (local, open-source, no GPU).
  - [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) 3.7.0 / PaddleX, PP-OCRv5 models, `paddlepaddle` 3.3.1 CPU (local, open-source, no GPU) — added because the mentor flagged Tesseract's 64.7% word-recall ceiling as too low and asked for a second local tool to compare against.
- **Data:** the same 2 FUNSD documents already committed via
  `eval/datasets/.manifest.json`/`.sha256` (`82092117`,
  `82200067_0069`) — no new sample downloaded, for either tool.
- **Configs:**
  - **Tesseract** — 3 page-segmentation modes (`--psm`), a third added
    specifically to check whether accuracy keeps improving or
    plateaus/regresses past the first two:
    - `3` — Tesseract's own default (fully automatic page segmentation).
    - `6` — assume a single uniform block of text.
    - `11` — sparse text: find as much text as possible, in no
      particular order. Targets exactly FUNSD's layout (scattered,
      disconnected form fields, not flowing prose or one block).
  - **PaddleOCR** — 2 model-size configs, PP-OCRv5 (PaddleOCR's current
    generation), detection+recognition pair for each:
    - `paddleocr_mobile` — `PP-OCRv5_mobile_det` + `PP-OCRv5_mobile_rec`.
    - `paddleocr_server` — `PP-OCRv5_server_det` + `PP-OCRv5_server_rec`.
    - Document-orientation/unwarping/textline-orientation sub-models
      left off for both — FUNSD pages are already upright scans, and
      this keeps the comparison to "detection + recognition only,"
      the same thing Tesseract does.
- **Script:** `benchmarks/ocr_asr_benchmark.py` — reproducible;
  `python -m benchmarks.ocr_asr_benchmark` prints the raw per-document
  numbers this table is built from, for all 5 OCR configs and both
  modalities, ending in an explicit conclusion line.
- **Accuracy proxy:** word recall — fraction of FUNSD's ground-truth
  words (from the `.json` annotations) that appear anywhere in the
  tool's output, case/punctuation-insensitive. Not a real OCR metric
  (no CER/WER, no position matching) — "did the same words come out,"
  nothing more. Identical proxy, identical ground truth, for both tools.
- **Resource footprint:** wall-clock latency (`time.perf_counter`) and
  a **sampled** peak RSS memory + CPU time, polled via `psutil`
  (Python's stdlib `resource` module doesn't exist on Windows). For
  Tesseract this samples the external `tesseract` subprocess every
  10ms. PaddleOCR runs in-process (no subprocess to isolate), so its
  numbers are this whole Python process's peak RSS/CPU while
  `predict()` runs — not apples-to-apples with Tesseract's isolated
  subprocess figure, but the right number for "what must the host have
  while OCRing" either way. Tesseract's numbers are averaged over the
  2 documents; 3 runs showed identical word recall and near-identical
  memory, with only minor latency noise run-to-run. PaddleOCR was run
  once per doc given its cost (see below).

### PaddleOCR — setup (network block + workaround)

Getting PaddleOCR running at all took two real, unrelated blockers —
recorded here so the numbers below aren't mistaken for "PaddleOCR is
just slow by nature":

1. **Model download blocked by network filtering.** PaddleOCR ships no
   weights in the pip package — it fetches ~4 model packages
   (mobile+server × det+rec, ~5–90MB each) from HuggingFace, ModelScope,
   or BOS (Baidu) on first use. All three were blocked on this dev
   machine by **NetFree**, a system-level TLS-inspecting content filter
   (`C:\ProgramData\NetFree\CA\...`) — confirmed by the identical
   `418 Blocked by NetFree — risk-type` response from all three hosters
   for the actual model-weight downloads (the hosters' own landing
   pages loaded fine; only the binary/LFS endpoints were blocked).
   **Fix:** downloaded the 4 `.tar` packages on an unfiltered network
   from the direct BOS URLs (same URLs PaddleOCR itself tries):
   - `https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv5_mobile_det_infer.tar`
   - `https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv5_mobile_rec_infer.tar`
   - `https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv5_server_det_infer.tar`
   - `https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0/PP-OCRv5_server_rec_infer.tar`

   ...then extracted each into
   `~/.paddlex/official_models/<model_name>/` (flattening the tar's
   top-level `<model_name>_infer/` folder) so PaddleOCR finds them
   locally and never touches the network.
2. **`enable_mkldnn=False` required.** With mkldnn (Intel's CPU
   inference acceleration, on by default) enabled, every PaddleOCR call
   crashed: `NotImplementedError: ConvertPirAttribute2RuntimeAttribute
   not support [...]` — a oneDNN/PIR incompatibility in this
   `paddlepaddle` 3.3.1 build, unrelated to the network issue. Passing
   `enable_mkldnn=False` avoids the crash but **disables the CPU
   acceleration PaddleOCR is designed to use** — so the latency numbers
   below are a floor for this specific machine/paddlepaddle-version
   combination, not a ceiling for what PaddleOCR is capable of on
   hardware where mkldnn works.

## OCR — results

| Config | Latency (avg) | Peak RSS (avg) | CPU time (avg) | Word recall (avg) |
|---|---:|---:|---:|---:|
| `tesseract --psm 3` (default) | 0.95s | 35.1 MB | 0.44s | 56.0% |
| `tesseract --psm 6` (single block) | 0.57s | 34.7 MB | 0.54s | 48.2% |
| `tesseract --psm 11` (sparse text) | **0.49s** | **34.5 MB** | 0.45s | 64.7% |
| `paddleocr_mobile` | 40.4s | 569.1 MB | 109.4s | **66.5%** |
| `paddleocr_server` | 890.4s | 655.5 MB | 3375.3s | 64.7% |

(PaddleOCR's `cpu_time` exceeds wall-clock latency because it uses
multiple threads — that's expected, not a measurement error.)

- **Tesseract `--psm 11`** remains the best of the 3 Tesseract configs,
  for the same reason as before: FUNSD's scattered form-field layout
  matches "sparse text" better than "single block" or generic
  "automatic." See prior analysis below (unchanged).
- **`paddleocr_mobile`** has the single highest recall measured
  (66.5%) — but only **2.1 points above `tesseract --psm 11`**, for
  **~80x the latency** (40.4s vs. 0.49s) and **~16x the peak memory**
  (569MB vs. 34.5MB).
- **`paddleocr_server` did not beat `paddleocr_mobile`** — it tied
  Tesseract's `psm11` recall (64.7%) exactly, while costing **~22x
  `paddleocr_mobile`'s latency** (890s vs. 40s) and **~30x its CPU
  time**. The larger model bought nothing on this sample; if anything
  it went slightly backward on doc `82092117` (see raw rows in the
  script's output). This may be an artifact of `enable_mkldnn=False`
  disproportionately hurting the larger model, of the 2-document
  sample size, or both — not something to over-read with n=2, but
  real enough that "bigger PaddleOCR model = better" should not be
  assumed without re-testing once mkldnn is fixed.

**Answering the mentor's original concern directly:** adding a second,
heavier local tool moved word recall from 64.7% to 66.5% — a 2-point
ceiling lift, not a step change, and only from the smaller PaddleOCR
model. The 64.7% Tesseract ceiling looks like it reflects the
difficulty of this proxy/sample (dense, scattered form text; a rough
word-overlap metric) more than a weak tool choice — a heavier local
model does not unlock materially higher accuracy here.

## OCR — final decision

**Use `tesseract --psm 11` (sparse text) as the default.** It is
essentially tied with the best PaddleOCR result (64.7% vs. 66.5% —
2.1 points) at a small fraction of the cost (~80x less latency, ~16x
less memory than `paddleocr_mobile`). For a pipeline that needs to
process pages at any real volume, that cost difference is decisive on
its own; 2 points of recall does not offset an 80x latency multiplier
absent a specific accuracy-critical use case.

**If a use case ever needs the extra ~2 points badly enough to accept
~40s/page and ~570MB, `paddleocr_mobile` is the fallback — not
`paddleocr_server`,** which cost 22x more than `paddleocr_mobile` for
equal-or-worse recall in this test. This should be re-verified once
`enable_mkldnn` works on the target hardware, since that constraint is
specific to this dev machine's paddlepaddle build, not to PaddleOCR as
a tool.

**Resource budget for capacity planning (Checkpoint 1):**
- `tesseract --psm 11` (recommended default): ~0.5s latency, ~35MB peak
  memory, ~0.45s CPU time per FUNSD-sized page.
- `paddleocr_mobile` (accuracy fallback): ~40s latency, ~570MB peak
  memory, ~110s CPU time per page.

All figures are on this dev machine (CPU only, no load, no mkldnn for
PaddleOCR) — a shared/production host, or one where the mkldnn crash
doesn't occur, will differ, likely favorably for PaddleOCR. Scale by
expected page count and concurrency for real capacity numbers once
volume is known.

## ASR — known risk

**No decision. Blocked on data, not on tooling.** Local Whisper was the
intended choice per team direction (local models only), but there is
still no AMI audio (or any audio) sample anywhere in this repo or on
any branch to benchmark against — re-checked across all remote
branches, not just `main`, right before writing this.

One near-miss worth recording: `michal/real-data-slice` (unmerged) does
add an AMI package (`eval/datasets/ami.manifest.json` /
`eval/real_slice/manager_ami_*.json`) — but its own manifest says
explicitly *"text annotations only, no audio"*. It's the manual
abstractive-summary XML layer, downloaded for a different ticket
(extraction ground truth, #176), not the AMI signals/audio package.
There is still nothing to feed Whisper. Whisper itself was never
installed or run.

This is a real, open risk for Checkpoint 1 capacity planning, not a
recommendation dressed up to sound more certain than it is: **do not
assume Whisper `base` (today's placeholder in `.env.example`) is
correct** until this is actually measured. Unblocking this needs either
(a) someone downloading AMI's separate signals/audio package (not the
manual-annotations one already on `michal/real-data-slice`) into
`eval/datasets/`, or (b) an explicit decision to fetch a small audio
sample outside the normal "don't fetch new data" rule, since none
exists to reuse.

## Open item

Once AMI data exists, benchmark Whisper (at least 2 sizes, e.g.
`tiny`/`base`, plus a third per the same "don't stop at two" reasoning
above) the same way this document does for OCR, and replace this
section with a final decision.
