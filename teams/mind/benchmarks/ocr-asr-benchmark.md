# MIND — OCR / ASR Local-Model Benchmark & Recommendation

**Decision: OCR uses Tesseract (`--psm 11`), not PaddleOCR.** PaddleOCR
(mobile and server, PP-OCRv5) was benchmarked as the alternative the
mentor asked for and did not win on the cost/accuracy tradeoff — see
"OCR — final decision" below. What that rests on: unaligned word recall
(64.7% vs. 66.5%), latency and memory. PaddleOCR emits text *lines*, not
words, so it has no aligned-CER score yet (see "OCR — aligned CER").

**Primary accuracy number: aligned CER, 0.37 for `--psm 11`** (lower is
better; 0 is perfect) — and that is a *pessimistic* figure, not a final
one: most of it is unmatched words that mostly sit on an OCR box (merges
and splits), which one-to-one matching charges as errors. An earlier
"91.2% character similarity" figure was **retracted** — it is not an
accuracy (see "Retracted").

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
- **Accuracy metrics** (against FUNSD's ground truth):
  - **Aligned CER — primary.** Words are paired by bounding-box overlap
    (IoU ≥ 0.3, one-to-one); CER is computed on each pair; an unmatched
    GT word is a deletion and an unmatched OCR word an insertion:
    `CER = (substitutions + deletions + insertions) / GT characters`.
    Lower is better, 0 is perfect, and it can exceed 1 when the OCR
    emits more garbage than there is ground truth. Position-based, so
    reading order (which `--psm 11` doesn't have) doesn't matter.
    `benchmarks/aligned_metrics.py`. Tesseract only — it needs word
    boxes, and PaddleOCR emits text lines.
  - **Word recall — secondary.** Fraction of FUNSD's ground-truth words
    that appear *anywhere* in the output, case/punctuation-insensitive.
    Recall only: it never looks at what the OCR produced beyond the
    ground truth, so it is not an accuracy. Same definition for both
    tools.
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

**On the mentor's original concern** (is 64.7% word recall too low?):
the heavier PaddleOCR moved *unaligned word recall* from 64.7% to 66.5%
— a 2-point lift for ~80x the latency. Word recall is recall-only, and
PaddleOCR has no aligned score, so this does not show the two tools are
equally accurate; it shows the recall-and-cost evidence doesn't justify
the cost.

The 98 word-recall misses were also classified by cause — see
[`word-recall-error-analysis.md`](./word-recall-error-analysis.md). Treat
that as a heuristic explanation of why *exact match* fails (digits merged
with a neighbouring field, word-boundary mismatches against FUNSD's
annotations, a misspelled ground-truth word), **not** as a measurement of
accuracy: its "59% aren't real errors" figure used a best-match-anywhere
rule with loose thresholds, the same family of idea as the retracted
metric below. It should be redone with box-based classification.

## OCR — aligned CER (primary accuracy metric)

Per config, mean of the 2 FUNSD documents, IoU ≥ 0.3 (Tesseract only):

| Config | Aligned CER ↓ | Aligned word precision | Aligned word recall | (unaligned word recall) |
|---|---:|---:|---:|---:|
| `tesseract --psm 3` | 0.422 | **0.72** | 0.49 | 56.0% |
| `tesseract --psm 6` | 0.655 | 0.44 | 0.36 | 48.2% |
| `tesseract --psm 11` | **0.368** | 0.60 | **0.53** | 64.7% |

`--psm 11` has the lowest CER and the highest aligned recall, but
**`--psm 3` is more precise** (0.72 vs. 0.60: `--psm 11` emits more
words, including fragments). So "`--psm 11` wins on every axis" is no
longer true — it wins on CER and recall, not precision. Per document,
`--psm 11`: 0.334 and 0.402.

**How the matching threshold was chosen.** ICDAR's usual IoU ≥ 0.5
turned out to fit this data badly: FUNSD's word boxes and Tesseract's
follow different conventions — centers line up (median offset ~0.5px) but
heights differ, up to ~2x on some pages. Measured on the 218 words
Tesseract read *exactly* and that sit at the same spot (a known-true
pairing):

| Matching rule | Known-true pairs recovered | Wrong-text candidates per GT word |
|---|---:|---:|
| IoU ≥ 0.5 | 52% | 0.00 |
| **IoU ≥ 0.3** | **93%** | **0.00** |
| overlap coefficient ≥ 0.5 | 100% | 0.27 |
| center-in-either-box | 100% | 0.42 |

0.3 was picked for matching quality on those pairs, not for how good the
CER looked. Caveat: known-true pairs are by construction the correctly
read words, so recovery for misread words can't be measured this way.

**The ranking depends on that threshold.** Aligned CER at other
thresholds:

| Config | IoU ≥ 0.3 | IoU ≥ 0.5 | IoU ≥ 0.7 |
|---|---:|---:|---:|
| `--psm 3` | 0.422 | 0.997 | 1.500 |
| `--psm 6` | 0.655 | 1.022 | 1.543 |
| `--psm 11` | 0.368 | 1.065 | 1.587 |

At 0.5 and above matching collapses, the score mostly measures box
mismatch, and `--psm 11` (more, smaller fragments) comes out *worst*. So
the case for `--psm 11` over `--psm 3` is "lowest CER by 0.054 at the
threshold chosen for matching quality, with n=2 documents" — real but
not robust, and not a large margin.

**What the 0.37 is made of** (`--psm 11`, both documents pooled; 373 GT
words, 605 character edits): 277 words matched, 96 GT words unmatched
(deletions), 51 OCR words unmatched (insertions). Edit mass: 25%
substitutions inside matched pairs, 48% deleted-word characters, 27%
inserted-word characters. Of the 96 unmatched GT words, **70 (73%) have an
OCR box sitting on them** (merge, split, or box mismatch) and 26 (27%)
have none (really missing); 61% of unmatched OCR words sit on at least one
GT word. One-to-one matching charges a merge/split as a deletion plus an
insertion even when the characters are right, so **0.37 is a pessimistic
bound on Tesseract's character error, not the error itself.** The next
step is many-to-one (CLEval-style) matching to separate real misses from
tokenization differences.

**Sanity check — garbage must be penalized.** The mentor's experiment,
against the new metric (random lowercase words placed at random page
positions, appended to the real `--psm 11` output):

| Garbage words added | Aligned CER (real output + garbage) | Garbage only |
|---:|---:|---:|
| 0 | 0.368 | 1.000 (nothing) |
| 50 | 0.734 | 1.315 |
| 200 | 1.747 | 2.346 |
| 1000 | 7.456 | 7.786 |

It rises monotonically and exceeds 1 — pinned in
`tests/test_aligned_metrics.py`, along with identity → 0, empty OCR → 1,
and reading-order invariance.

**Limitations.** n=2 documents. FUNSD's annotation is noisy (e.g.
`comunication` is misspelled in the ground truth; empty-text boxes are
ignored). Text is normalized (case and punctuation dropped), so
punctuation-only OCR noise isn't penalized. PaddleOCR can't be scored
one-to-one at word level (it emits lines).

## Retracted: the "91.2% character similarity"

An earlier version of this document and `src/mind/multimodal/README.md`
presented 91.2% — a "character similarity" that took, for each ground-truth
word, its best match among *all* OCR words — as the "real accuracy". It
isn't an accuracy: there is no zero point and extra output is never
penalized, so it rewards volume. Against FUNSD's ground truth, random
gibberish scored 35% (50 words), 42% (200), 48% (1000) and 53% (5000)
while unaligned word recall stayed ~0%. The real `--psm 11` output is
well above that floor, so it does read real text, but 91.2% is not a
percent-correct. The function was removed.

## OCR — final decision

**Use `tesseract --psm 11` (sparse text) as the default.** It has the
lowest aligned CER of the three Tesseract configs (0.37, vs. 0.42 for
`--psm 3` and 0.66 for `--psm 6`) and the highest aligned word recall
(0.53), though `--psm 3` is more precise (0.72 vs. 0.60) and the margin
over it depends on the matching threshold (see "OCR — aligned CER"). The
0.37 is a pessimistic bound — most of it is unmatched words that mostly
sit on an OCR box (merges/splits) — and **not** the "~91% accuracy" an
earlier version of this document claimed (retracted above).

The Tesseract-vs-PaddleOCR choice rests on cost, not on an accuracy
comparison we can make: unaligned word recall is close (64.7% vs. 66.5%
for `paddleocr_mobile` — 2.1 points, recall-only), PaddleOCR has no
aligned score (it emits lines, not words), and it costs ~80x the latency
and ~16x the memory. For a pipeline that needs to process pages at any
real volume, that cost difference is decisive on its own absent a
specific accuracy-critical use case. If one appears, the first step is
to score PaddleOCR on the same footing (many-to-one matching), not to
assume it is better.

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

## OCR — handling the real residual errors: per-word confidence

Whatever the exact error rate is, the errors aren't evenly spread —
Tesseract already scores its own confidence per recognized word (0-100,
from its `tsv` output mode; no new tool or model needed), and that
confidence differs between the groups found in the miss analysis. Tested
via `benchmarks/analyze_confidence.py` (`python -m
benchmarks.analyze_confidence`). The groups come from the heuristic miss
classification (see the caveat above), so read this as directional
evidence, not a validated detector:

| Group | n | avg. Tesseract confidence |
|---|---:|---:|
| Correctly-recalled words (word_recall hits) | 586 | **80.4** |
| `segmentation` misses | 33 | 63.8 |
| `digit_merge` misses | 22 | 61.9 |
| `char_misread` misses | 17 | 53.0 |
| `not_detected` misses | 3 | 47.2 |
| **`different_word` misses (the risky category)** | 19 | **46.3** |

Every miss category scores meaningfully below the 80.4 average for
correct words, and `different_word` — the category flagged above as
the one actually worth worrying about for extraction — scores lowest
of all (46.3, ~34 points below correct words).

**Recommendation:** the multimodal OCR interface should return, per
recognized word, both the text **and** Tesseract's confidence score
(already computed internally — this is a change to what we read out of
Tesseract's output, not a new capability to build). Extraction (once
built, Phase 2) can then discount or flag low-confidence words instead
of trusting every word equally — a threshold around 60-65 would catch
most `char_misread`/`different_word`/`not_detected` cases while keeping
nearly all correct words (80.4 avg) and most `digit_merge`/
`segmentation` cases (which don't need special handling anyway, since
their content is already correct). Exact threshold needs tuning once
extraction exists to measure against real downstream impact, not
picked from this n=98 sample alone.

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

**Test the English-only (`.en`) variants, not just the standard
multilingual models** (`tiny.en`/`base.en`/`small.en`/`medium.en` --
no `.en` variant exists for `large`) -- MIND's app is English-only
(same reasoning as the data policy above: Enron/SAMSum/CRM data is all
English), and Whisper's `.en` models are trained specifically for
English, typically scoring better than the multilingual model of the
same size on English audio. Include at least one `.en` config in
whatever sizes get benchmarked, not only the multilingual default.
