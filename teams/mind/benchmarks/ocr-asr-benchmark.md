# MIND — OCR / ASR Local-Model Benchmark & Recommendation

**Decision: OCR uses Tesseract (`--psm 11`).** Decided with the mentor, on
latency and memory: ~0.5s and ~34MB per page, vs. ~40-46s and ~570-620MB for
PaddleOCR mobile (CPU), and no heavy dependency in the image (PaddleOCR needs
`paddlepaddle` plus model weights, whose downloads were also blocked on the dev
network). **This is an accuracy trade, made knowingly:** on FUNSD (n=2)
PaddleOCR mobile has ~2.7x fewer character errors — grouped CER 0.074 vs. 0.200
for Tesseract `--psm 11`, and a check with no box matching agrees (page-level
CER 0.070 vs. 0.168). The first reasoning for Tesseract — word recall looking
tied (64.7% vs. 66.5%) — was a measurement artifact (PaddleOCR drops the spaces
inside a line, which exact-match word recall undercounts) and is **not** part of
this decision. If accuracy later becomes the binding constraint, PaddleOCR
mobile is the documented upgrade path — see "OCR — final decision".

**Accuracy of the chosen engine: grouped CER 0.20 for `--psm 11`** (character
error rate; lower is better, 0 is perfect), with a strict one-to-one CER of 0.37
as a conservative bound — see "OCR — CER: grouped and strict". n=2 documents.
An earlier "91.2% character similarity" figure was **retracted** — it is not an
accuracy (see "Retracted").

**Decision: ASR uses Whisper `base.en`** (faster-whisper, int8, CPU), measured
against `tiny.en` and `small.en` on two AMI meetings (63 min of audio), two
runs:
- **`base.en`:** rough WER 0.263–0.265, ~10x faster than real time on a quiet
  machine (RTF 0.10; up to 0.3 measured under load).
- **`small.en`:** 0.9–1.6 points more accurate, but ~5x slower.
- **`tiny.en`:** a rough WER gap of only 0.4–1.3 points (inside the noise),
  but lower word precision in both runs — the reason for base over tiny.

**The evidence is thin and written down as such:** n=2 meetings, a rough word
diff rather than a real WER, and run-to-run variance about the size of the
gaps — see "ASR — final decision" and "ASR — known risks".

## Purpose

Not "which config looks better" — a final, number-backed decision for
each modality, so extraction (week 4) knows exactly what to build
against, and Checkpoint 1 has real latency/resource numbers for
capacity planning.

## Scope

**OCR: done, 5 configs (3 Tesseract + 2 PaddleOCR); Tesseract `--psm 11` chosen — see "OCR — final decision".**
**ASR: done, 3 Whisper sizes (`tiny.en` / `base.en` / `small.en`); `base.en` chosen — see "ASR — final decision".**
Measured on the same three axes as OCR (latency, an accuracy proxy, resource
footprint) so the two halves compare. A model-size decision only: the ASR
pipeline itself and a real WER harness are out of scope (#135).

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
- **Accuracy metrics** (against FUNSD's ground truth; details in "OCR — CER"):
  - **Grouped CER — primary.** GT and OCR words that overlap by bounding
    box are clustered and each cluster is scored as one string (text
    concatenated in position order), so merged/split words and reading
    order don't matter, while wrong, missing and extra characters do:
    `CER = (substitutions + deletions + insertions) / GT characters`. Lower
    is better, 0 is perfect, and it can exceed 1 when the OCR emits more
    garbage than there is ground truth. Works for any engine that reports
    boxes — Tesseract words and PaddleOCR lines.
    `benchmarks/aligned_metrics.py`.
  - **Strict CER — conservative bound (Tesseract only).** One-to-one word
    pairing by IoU ≥ 0.3; a merge or split costs a deletion plus an
    insertion, so it overstates the error. Meaningless for PaddleOCR's
    lines.
  - **Word recall — secondary.** Fraction of FUNSD's ground-truth words
    that appear *anywhere* in the output, case/punctuation-insensitive.
    Recall only, and it depends on how the output is split into words — it
    undercounts PaddleOCR, which drops spaces inside a line — so it is not
    an accuracy and not a fair cross-engine comparison.
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

| Config | Latency (avg) | Peak RSS (avg) | CPU time (avg) | Grouped CER ↓ | Word recall (unaligned) |
|---|---:|---:|---:|---:|---:|
| `tesseract --psm 3` | 0.95s | 35.1 MB | 0.44s | 0.294 | 56.0% |
| `tesseract --psm 6` | 0.57s | 34.7 MB | 0.54s | 0.354 | 48.2% |
| `tesseract --psm 11` | **0.49s** | **34.5 MB** | 0.45s | 0.200 | 64.7% |
| `paddleocr_mobile` | 40.4s | 569.1 MB | 109.4s | **0.074** | **66.5%** |
| `paddleocr_server` | 890.4s | 655.5 MB | 3375.3s | 0.102 | 64.7% |

Latency/memory are from the first run, on an otherwise idle machine. A
second run (CPU shared with other work) gave `paddleocr_mobile` 45.7s /
617MB and `paddleocr_server` 440s / 837MB; per-page server latency has
ranged 299–1,482s across runs, so read it as "minutes per page", not a
stable number. PaddleOCR's `cpu_time` exceeds latency because it is
multi-threaded. PaddleOCR ran with `enable_mkldnn=False` (see setup).
Unaligned word recall **undercounts PaddleOCR** — it drops the spaces inside
a line — so compare engines on grouped CER, not on that column.

- **Tesseract `--psm 11`** is the best of the 3 Tesseract configs on CER
  and recall (`--psm 3` is more precise; see the CER section).
- **`paddleocr_mobile`** has by far the lowest CER: 0.074 vs. 0.200 — about
  2.7x fewer character errors — at ~80-90x the latency (40-46s vs. 0.5s)
  and ~16-18x the peak memory (570-620MB vs. 34.5MB).
- **`paddleocr_server` is not better than `paddleocr_mobile`**: CER 0.102 vs.
  0.074, and mixed per document — slightly better on `82092117` (0.029 vs.
  0.039), clearly worse on `82200067_0069` (0.175 vs. 0.110, where it left 19
  GT words with no OCR box vs. 8) — at 10-20x the latency. n=2, and
  `enable_mkldnn=False` may hurt the larger model more; but nothing here
  supports paying for it.

**On the mentor's original concern** (is 64.7% word recall too low?): it was
partly a metric problem. Word recall is recall-only and depends on how the
output is split into words. Measured on characters, Tesseract `--psm 11`
scores a grouped CER of 0.20, and PaddleOCR mobile 0.074.

The 98 word-recall misses were also classified by cause — see
[`word-recall-error-analysis.md`](./word-recall-error-analysis.md). Treat
that as a heuristic explanation of why *exact match* fails (digits merged
with a neighbouring field, word-boundary mismatches against FUNSD's
annotations, a misspelled ground-truth word), **not** as a measurement of
accuracy: its "59% aren't real errors" figure used a best-match-anywhere
rule with loose thresholds, the same family of idea as the retracted
metric below. It should be redone with box-based classification.

## OCR — CER: grouped (primary) and strict (conservative bound)

Two position-based CERs against FUNSD's ground truth, both independent of
reading order (`--psm 11` has none). Lower is better, 0 is perfect, and
either can exceed 1 when the OCR emits more garbage than there is ground
truth. Code: `benchmarks/aligned_metrics.py`.

- **Grouped — primary.** GT and OCR words that overlap by bounding box
  (overlap coefficient ≥ 0.5: intersection over the *smaller* box) are
  clustered; each cluster is scored as one string — the GT words' text and
  the OCR words' text, each concatenated in position order — so a merged or
  split word with correct characters costs nothing, while wrong, missing and
  extra characters do. Unmatched GT words are deletions, unmatched OCR words
  insertions. It works for any engine that reports boxes, so it can score
  PaddleOCR's text lines too. *Why this one is primary — decided before
  looking at its numbers:* CER should measure characters, and strict
  matching mixes in tokenization (a zip code read as `432159428` instead of
  `43215` + `9428` has zero wrong characters yet costs a deletion plus an
  insertion).
- **Strict — conservative bound.** One-to-one pairing of words by IoU ≥ 0.3,
  CER on each pair; a merge or split is charged as a deletion plus an
  insertion, so it overstates the OCR's error. Tesseract only (PaddleOCR's
  lines can't be paired one-to-one with words). Reported with word
  precision/recall at the same pairing.

Mean of the 2 FUNSD documents (Tesseract; overlap/IoU thresholds as above):

| Config | Grouped CER ↓ | Strict CER | Strict word precision / recall | Unaligned word recall |
|---|---:|---:|---:|---:|
| `tesseract --psm 3` | 0.294 | 0.422 | **0.72** / 0.49 | 56.0% |
| `tesseract --psm 6` | 0.354 | 0.655 | 0.44 / 0.36 | 48.2% |
| `tesseract --psm 11` | **0.200** | **0.368** | 0.60 / **0.53** | 64.7% |

`--psm 11` has the lowest CER on both measures and the highest recall, but
**`--psm 3` is more precise** (0.72 vs. 0.60: `--psm 11` emits more words,
including fragments) — so "`--psm 11` wins on every axis" is not true. The
grouped ranking holds at every overlap threshold (0.3 / 0.5 / 0.7):
`--psm 11` 0.187 / 0.200 / 0.289, `--psm 3` 0.292 / 0.294 / 0.349,
`--psm 6` 0.352 / 0.354 / 0.429. But it is n=2, and the lead is uneven —
per document, grouped CER: `--psm 11` 0.120 and 0.280, `--psm 3` 0.127 and
0.460, so on `82092117` the two nearly tie and the margin comes mostly from
the other page.

**PaddleOCR on the same footing.** Grouped scoring only needs boxes, so
PaddleOCR's text lines can be scored too (strict can't: lines vs. words).
Mean of the two documents:

| Config | Grouped CER ↓ | Per document | Page-level CER (no box matching) |
|---|---:|---:|---:|
| `paddleocr_mobile` | **0.074** | 0.039, 0.110 | 0.070 |
| `paddleocr_server` | 0.102 | 0.029, 0.175 | not computed |
| `tesseract --psm 11` | 0.200 | 0.120, 0.280 | 0.168 |
| `tesseract --psm 3` | 0.294 | 0.127, 0.460 | 0.289 |
| `tesseract --psm 6` | 0.354 | 0.233, 0.475 | 0.317 |

The page-level column is an independent cross-check that uses no box
matching at all: both sides' words are put in one reading order for the whole
page and compared as one string against one string. PaddleOCR mobile's lead
holds under every variation tried: overlap threshold 0.3 / 0.5 / 0.7 gives
0.075 / 0.075 / 0.080 (vs. 0.187 / 0.200 / 0.289 for `--psm 11`); line
tolerance 0.3-1.0 leaves it flat at 0.075; and the page-level number agrees
(0.070 vs. 0.168). Composition (both documents pooled): only 8 of 373 GT words
(2%) have no PaddleOCR box on them (25 for `--psm 11`), 3 PaddleOCR lines
have no GT word, pooled CER 0.065. Its groups are larger (97 groups, 70 of
them merged/split, since one line covers several words), which is exactly what
grouped scoring is built to handle.

Caveats specific to this comparison: n=2 FUNSD fax scans. I could not check
how FUNSD's ground-truth text and boxes were originally produced (the dataset
paper was blocked here); if an OCR engine produced them, engines that read
like it could be favored — unverified. Neither engine got preprocessing;
PaddleOCR ran with orientation/unwarping off and mkldnn off.

**What the grouped CER is made of** (`--psm 11`, both documents pooled; 373
GT words, 301 character edits): 244 groups (189 one-to-one, 55 merged or
split; the largest, 25 words, is a narrow multi-line paragraph), 25 GT words
with no OCR box on them (deletions), 15 OCR words with no GT word (insertions).
Edit mass: 56% inside groups (wrong characters, plus any reading-order
disagreement within multi-line groups), 30% deleted-word characters, 14%
inserted-word characters. About 93% of GT words have an OCR box on them, so
what remains is mostly wrong characters inside matched words — pooled CER
0.178, mean of the two documents 0.200. (`--psm 3` is different: 71% of its
edit mass is 81 GT words it never put a box on.)

**A fix that moved the number, and the check on it.** The first version built
the reading order of the OCR side using the OCR boxes' own height. OCR boxes
are up to ~2x taller than FUNSD's (centers agree, heights don't), which
folded neighbouring lines together and ordered the same words differently
than the GT — e.g. a 57-character paragraph that scored 23 edits although
most of its words were right. The fix: one line tolerance for both sides
(0.6 x the smaller median box height). It moved `--psm 11` from 0.268 to
0.200, which is large enough to distrust, so: the CER is flat for tolerance
factors 0.3–0.8 (`--psm 11` 0.200, `--psm 3` 0.294, `--psm 6` 0.354–0.365)
and only rises at ≥ 1.0 (0.212 / 0.306 / 0.385), exactly when lines start to
merge again. A unit test fails if the per-side behavior comes back.

**How the strict threshold was chosen.** ICDAR's usual IoU ≥ 0.5 fits this
data badly: FUNSD's word boxes and Tesseract's follow different conventions
— centers line up (median offset ~0.5px) but heights differ, up to ~2x on
some pages. Measured on the 218 words Tesseract read *exactly* and that sit
at the same spot (a known-true pairing):

| Matching rule | Known-true pairs recovered | Wrong-text candidates per GT word |
|---|---:|---:|
| IoU ≥ 0.5 | 52% | 0.00 |
| **IoU ≥ 0.3** | **93%** | **0.00** |
| overlap coefficient ≥ 0.5 | 100% | 0.27 |
| center-in-either-box | 100% | 0.42 |

0.3 was picked for matching quality on those pairs, not for how good the CER
looked. (The overlap-coefficient rule — which recovers everything — is what
grouped scoring uses; there the "wrong-text" candidates are mostly the
legitimate merged/split neighbours it is meant to group.) Caveat:
known-true pairs are by construction the correctly read words.

**Strict CER depends on its threshold — grouped much less.** Strict CER at
IoU ≥ 0.3 / 0.5 / 0.7: `--psm 3` 0.422 / 0.997 / 1.500, `--psm 6` 0.655 /
1.022 / 1.543, `--psm 11` 0.368 / 1.065 / 1.587. At 0.5 and above strict
matching collapses, measures box mismatch, and `--psm 11` (more, smaller
fragments) comes out *worst*. Strict: of its 96 unmatched GT words (`--psm
11`), 73% have an OCR box sitting on them — merges, splits and box
mismatches, which is what grouped scoring separates out.

**Sanity check — garbage must be penalized.** The mentor's experiment against
the grouped metric (random lowercase words at random page positions, appended
to the real `--psm 11` output):

| Garbage words added | Grouped CER (real output + garbage) | Garbage only |
|---:|---:|---:|
| 0 | 0.200 | 1.000 (nothing) |
| 50 | 0.564 | 1.318 |
| 200 | 1.565 | 2.270 |
| 1000 | 7.199 | 7.523 |

It rises monotonically and passes 1 — pinned, for both scorers, in
`tests/test_aligned_metrics.py` and `tests/test_grouped_metrics.py`
(identity → 0, empty OCR → 1, reading-order and list-order invariance, extra
output never lowers CER; mutation-checked).

**Limitations.** n=2 documents. FUNSD's annotation is noisy (e.g.
`comunication` is misspelled in the ground truth; empty-text boxes are
ignored). Text is normalized (case and punctuation dropped), so
punctuation-only OCR noise isn't penalized. Grouped scoring ignores word
boundaries by design — one page-wide box with the right text in the right
order would score 0 — which is fine for a character error rate and for
Tesseract's word-level output, but it is not a tokenization metric (strict is
the one that sees that). Reading order *inside* a multi-line group is a
heuristic.

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

**Use `tesseract --psm 11` (sparse text).** Decided with the mentor on latency
and memory:

| | Tesseract `--psm 11` (chosen) | PaddleOCR mobile |
|---|---|---|
| Accuracy (grouped CER, lower is better) | 0.200 | **0.074** (~2.7x fewer character errors) |
| Latency per page (CPU) | **~0.5s** | ~40-46s (mkldnn off; with it on, or on a GPU, likely less — untested) |
| Memory (peak RSS) | **~34MB** | ~570-620MB (whole Python process) |
| Install | system package `tesseract-ocr` | `paddlepaddle` + `paddleocr` + model weights; weight downloads were blocked on the dev network (manual workaround); image growth not measured |
| Output | words + boxes + per-word confidence (`tsv`) | text lines + boxes + per-line score |
| Known issue here | — | `enable_mkldnn=False` needed (oneDNN crash in this build) |

**What we give up.** About 2.7x more character errors than PaddleOCR mobile on
FUNSD (grouped CER 0.20 vs. 0.074; n=2, so the exact ratio is uncertain but the
direction held under every variation tried — see "OCR — CER"). Tesseract's
remaining errors are real: mostly wrong characters inside matched words, plus
some words it puts no box on (about 7% of GT words for `--psm 11`).

**How the loss is mitigated.** Tesseract reports a confidence per word, and
that confidence differs between correctly read words and the miss groups (see
the per-word-confidence section below) — so the OCR interface should return it
and let extraction discount low-confidence words. That evidence is directional
and Tesseract-only.

**What the earlier reasoning got wrong, for the record.** The first decision for
Tesseract rested on word recall looking tied. It wasn't a fair comparison, and
this decision doesn't use it.

**Upgrade path if accuracy becomes the binding constraint:** PaddleOCR mobile
(not `paddleocr_server`: less accurate than mobile — CER 0.102 vs. 0.074 — and
minutes per page). Before switching: (1) re-measure latency with mkldnn on /
on the target hardware, (2) measure the image-size impact (`paddlepaddle` +
weights — Relay needs to know, see `docs/relay-handoff.md`), (3) confirm on more
than 2 documents and on a non-FUNSD sample, (4) evaluate PaddleOCR's per-line
score the way Tesseract's per-word confidence was.

**Resource budget for capacity planning (Checkpoint 1):**
- `tesseract --psm 11`: ~0.5s latency, ~35MB peak memory, ~0.45s CPU time per
  FUNSD-sized page.
- For reference, `paddleocr_mobile`: ~40-46s, ~570-620MB, ~100s CPU per page;
  `paddleocr_server`: minutes per page (299-1,482s observed), ~650-840MB.

## OCR — handling the real residual errors: per-word confidence

Whatever the exact error rate is, the errors aren't evenly spread —
Tesseract already scores its own confidence per recognized word (0-100,
from its `tsv` output mode; no new tool or model needed), and that
confidence differs between the groups found in the miss analysis. Tested
via `benchmarks/analyze_confidence.py` (`python -m
benchmarks.analyze_confidence`). The groups come from the heuristic miss
classification (see the caveat above), so read this as directional
evidence, not a validated detector, and for Tesseract only (PaddleOCR's
per-line score hasn't been evaluated).

**Correction (review feedback on PR #345).** The first version of this
analysis tallied misses in a dict keyed by the ground-truth word, so a word
missed twice on a page (form documents repeat "date", "name", "5"...)
overwrote itself: **11 of the 98 miss rows were silently dropped**, and the
per-category counts and averages below were computed from the 87 that
survived. Fixed (misses are now a list; regression-tested). Before → after:
`digit_merge` n=22 → 32 and avg 61.9 → 59.1, `segmentation` 33 → 34 and 63.8 →
62.8, `char_misread` 17 → 19 and 53.0 → 52.4, `different_word` 19 → 20 and
46.3 → 45.4, `not_detected` unchanged, correctly-read words unchanged. The
picture is the same, but `digit_merge` now sits just *below* 60, which matters
for any threshold near it (PR #509 uses 60.0).

| Group | n (confidence values) | avg. Tesseract confidence | share below 60 |
|---|---:|---:|---:|
| Correctly-recalled words (word_recall hits) | 586 | **80.4** | 12% |
| `segmentation` misses | 34 | 62.8 | 38% |
| `digit_merge` misses | 32 | 59.1 | 34% |
| `char_misread` misses | 19 | 52.4 | 47% |
| `not_detected` misses | 3 | 47.2 | 100% |
| **`different_word` misses (the risky category)** | 20 | **45.4** | 55% |

`n` counts confidence values, not miss rows: a miss row contributes every
confidence Tesseract gave the token it produced, so a token that appears
several times adds several values.

The averages look like a strong signal (80 vs. 45-59) but **the
distributions overlap heavily.** Flagging words below a cut-off, on the real
errors (`char_misread` + `different_word` + `not_detected`, n=42) vs. the
correctly-read words (n=586):

| Cut-off | Correct words flagged | Real errors caught |
|---:|---:|---:|
| 40 | 5% | 40% |
| 50 | 7% | 52% |
| 60 | 12% | 55% |
| 70 | 23% | 64% |
| 80 | 39% | 81% |

No cut-off both catches most errors and leaves the correct words alone: at 60
roughly half the real errors slip through while one correct word in eight is
flagged; catching 81% (cut-off 80) flags 39% of the correct words. So
confidence is a useful but **weak-to-moderate** signal, not a detector — an
earlier version of this section claimed a 60-65 cut-off would "catch most"
errors while keeping "nearly all" correct words; that was inferred from the
averages and was never computed, and is wrong.

**Recommendation:** the multimodal OCR interface should return, per
recognized word, both the text **and** Tesseract's confidence score
(already computed internally — this is a change to what we read out of
Tesseract's output, not a new capability to build). Extraction (once
built, Phase 2) can use it to discount or flag low-confidence words, as one
signal among others rather than a filter — and the cut-off should be chosen
against real downstream impact once extraction exists, with the trade-off
above in view, not picked from this n=98 sample alone.

All figures are on this dev machine (CPU only, no load, no mkldnn for
PaddleOCR) — a shared/production host, or one where the mkldnn crash
doesn't occur, will differ, likely favorably for PaddleOCR. Scale by
expected page count and concurrency for real capacity numbers once
volume is known.

## ASR — setup

- **Engine: `faster-whisper` 1.2.1, not `openai-whisper`.** Both run the same
  OpenAI Whisper weights locally; `faster-whisper` runs them on CTranslate2
  instead of PyTorch — faster on CPU, lower memory, and no multi-GB `torch`
  install in the image. `int8` quantization (`compute_type="int8"`), CPU, beam
  size 5 (the library default), `language="en"`.
- **Sizes: `tiny.en`, `base.en`, `small.en`** — three, not two, for the same
  reason as the OCR configs (two points can't show whether accuracy keeps
  improving or flattens out). English-only `.en` variants: MIND is
  English-only, and the multilingual model of the same size costs the same.
- **Data: AMI `Mix-Headset` audio** for IS1008b (29.5 min) and IB4003
  (33.7 min) — the two meetings already in the real-data slice — 63 min in
  all. Reference: AMI's human word-level transcript (`words/` layer of
  `ami_public_manual_1.6.2`), the four speakers interleaved by word start
  time. Pointers and checksums in `eval/datasets/ami-audio.manifest.json`;
  CC BY 4.0. The wav files are gitignored under `local-data/`.
- **Weights placed by hand, like PaddleOCR's.** NetFree blocks HuggingFace's
  CDN on this dev network (`418 Blocked by NetFree`, with and without Xet), so
  `model.bin` for each size was downloaded on an unfiltered network from
  `https://huggingface.co/Systran/faster-whisper-<size>/tree/main` and placed
  in `models/whisper/<size>/` with `config.json`, `tokenizer.json` and
  `vocabulary.txt` (the small files download fine). Each `model.bin` was
  checked against the sha256 HuggingFace publishes for it — all three match.
  The benchmark loads from that folder and never touches the network.
- **`av<19` pinned.** faster-whisper 1.2.1 passes a keyword to `av.open()`
  that PyAV 19 no longer accepts — every `transcribe()` raised a `TypeError`.
  PyAV 18.1.0 works.
- **`cpu_threads=4` set explicitly.** With it left at 0, faster-whisper
  honors `OMP_NUM_THREADS` — and constructing a `PaddleOCR()` sets
  `OMP_NUM_THREADS=1` on the process (verified: it is set by the constructor,
  not the import). In `python -m benchmarks.ocr_asr_benchmark` the OCR half
  runs first, the ASR workers inherited that, and Whisper ran on one thread at
  ~4x the latency (tiny.en RTF 0.221 instead of ~0.05, CPU time ≈ wall time).
  4 is CTranslate2's default, so the ASR-only numbers below are unaffected.
  Anything that later runs PaddleOCR and Whisper in one process tree needs the
  same care.
- **How it's measured.** Each size runs in a fresh process, so its peak RSS is
  that model alone (interpreter + model + audio decode, sampled every 50ms),
  not leftovers from the previous size. It's sampled per meeting, from after
  the model loads, so each row is that meeting's own peak. Latency is wall time per meeting,
  reported as a real-time factor (RTF = processing time / audio length; 0.1 is
  10x faster than real time). CPU time is user+system across CTranslate2's
  threads, so it exceeds wall time. Accuracy is a **rough word diff**:
  `difflib` alignment of the lowercased, punctuation-stripped word sequences,
  counted as substitutions/deletions/insertions — rough WER = (S+D+I)/N, plus
  word recall and precision. Deliberately not a real WER (no `jiwer`, no
  number/spelling normalization): that belongs to the ASR validation work.

Reproduce: `python -m benchmarks.ocr_asr_benchmark` (both halves) or
`python -m benchmarks.asr_benchmark` (ASR only, ~25 min on a laptop CPU).

## ASR — results

Two full runs on 2026-10-11, same dev laptop, CPU only, 4 threads:

- **Run 1:** `python -m benchmarks.asr_benchmark`, nothing else running.
- **Run 2:** `python -m benchmarks.ocr_asr_benchmark` (OCR first, then ASR), with
  other desktop apps open (Zoom, Chrome) — **its latency is under CPU
  contention**, so it shows what latency looks like on a busy host, not a clean
  measurement. Its accuracy numbers are unaffected by load.

Run 1, per meeting:

| Size | Meeting | RTF | Wall time | Peak RSS | CPU time | Rough WER | Recall | Precision | S / D / I |
|---|---|---|---|---|---|---|---|---|---|
| tiny.en | IS1008b | 0.047 | 84s | 1,657MB | 325s | 0.226 | 0.803 | 0.898 | 270 / 582 / 125 |
| tiny.en | IB4003 | 0.069 | 140s | 1,915MB | 524s | 0.308 | 0.720 | 0.902 | 329 / 1,522 / 186 |
| base.en | IS1008b | 0.098 | 174s | 1,718MB | 684s | 0.226 | 0.803 | 0.907 | 231 / 619 / 124 |
| base.en | IB4003 | 0.102 | 207s | 2,006MB | 811s | 0.288 | 0.734 | 0.927 | 237 / 1,523 / 145 |
| small.en | IS1008b | 0.425 | 752s | 1,894MB | 2,360s | 0.219 | 0.813 | 0.916 | 181 / 625 / 140 |
| small.en | IB4003 | 0.672 | 1,359s | 2,152MB | 3,798s | 0.277 | 0.743 | 0.934 | 219 / 1,484 / 130 |

Run 2, per meeting (with `fallback_segments`, added after run 1):

| Size | Meeting | RTF | Wall time | Peak RSS | CPU time | Fallback segs | Rough WER | Recall | Precision |
|---|---|---|---|---|---|---|---|---|---|
| tiny.en | IS1008b | 0.049 | 86s | 1,649MB | 335s | 2 | 0.219 | 0.803 | 0.912 |
| tiny.en | IB4003 | 0.224 | 453s | 1,887MB | 1,086s | 23 | 0.301 | 0.721 | 0.908 |
| base.en | IS1008b | 0.307 | 543s | 1,741MB | 1,436s | 30 | 0.224 | 0.803 | 0.913 |
| base.en | IB4003 | 0.156 | 315s | 1,953MB | 1,239s | 14 | 0.291 | 0.726 | 0.930 |
| small.en | IS1008b | 0.738 | 1,305s | 1,935MB | 3,736s | 12 | 0.209 | 0.817 | 0.923 |
| small.en | IB4003 | 1.395 | 2,823s | 2,118MB | 5,668s | 12 | 0.275 | 0.747 | 0.933 |

**Note on the Peak RSS column (review on #652):** both runs above were
measured before the sampler was reset per meeting. It ran once per size, so
each IB4003 row (the second meeting) was the running max over both meetings.
The values still hold as per-meeting peaks:
- After IS1008b, the running max is exactly IS1008b's peak.
- Every IB4003 value is higher than its IS1008b value, in all six rows.
- So each new maximum was set during IB4003, and the number is IB4003's own
  peak.

The benchmark now samples each meeting separately, so later runs don't rely on
that argument.

Both meetings pooled (diff counts and time summed, so per minute of audio):

| Size | Rough WER (run 1 / run 2) | Precision (run 1 / run 2) | RTF run 1 (clean) | RTF run 2 (under load) | ~Time per 30-min meeting, run 1 | Peak RSS |
|---|---|---|---|---|---|---|
| tiny.en | 0.276 / 0.269 | 0.900 / 0.910 | 0.059 | 0.142 | ~2 min | 1.9GB |
| **base.en** | **0.263 / 0.265** | **0.919 / 0.923** | **0.100** | **0.226** | **~3 min** | **2.0GB** |
| small.en | 0.254 / 0.249 | 0.926 / 0.929 | 0.557 | 1.089 | ~17 min | 2.1–2.2GB |

What the numbers say:

1. **The accuracy ranking held in both runs:** small < base < tiny on rough
   WER, small > base > tiny on precision. The gaps are what moved:
   - tiny→base: −1.3 points in run 1, −0.4 in run 2;
   - base→small: −0.9 in run 1, −1.6 in run 2.

   All of these are within about 1–1.5 points, about the run-to-run noise
   (see known risks).
2. **Latency grows much faster than accuracy.** small is 5.6x base in run 1
   and 4.8x in run 2. base is 1.7x tiny in run 1, 1.6x in run 2.
3. **Latency varies a lot between runs on the same audio** — base.en on
   IS1008b: RTF 0.098 in run 1, 0.307 in run 2. Two things push it up, and
   run 2 had both:
   - **Fallback re-decodes:** 30 fallback segments in that run (each one
     re-decodes and samples several candidates — CPU time doubled, 684s →
     1,436s).
   - **CPU contention:** CPU time / wall time fell from ~4 to ~2–2.6 with
     other apps open.

   The ranking between sizes didn't change.
4. **Memory barely moves with size** (1.6–2.2GB, same in both runs). The model
   weights are 75–485MB on disk, so most of the peak is something else —
   likely decoding a whole 30-min meeting into memory. **Not verified**; it's
   the reading that fits these numbers, not a measured breakdown.
5. **Precision is where base beats tiny in both runs:**
   - Pooled, 0.919 vs. 0.900 and 0.923 vs. 0.910.
   - Most clearly on IB4003: 0.927 vs. 0.902 and 0.930 vs. 0.908.

   On IS1008b alone they are close: tied on rough WER in run 1, and tiny
   slightly ahead in run 2 (0.219 vs. 0.224).
6. **Deletions dominate, and they barely depend on size** (~1,500 on IB4003
   for every size, both runs). A measured part of them is filler words:
   - AMI's reference has 300 "uh"/"um" in IB4003 and 239 in IS1008b, and no
     Whisper size emits a single one — Whisper drops disfluencies by design.
   - "yeah" is 158 in IB4003's reference vs. 58–83 in the transcripts.

   The rest is plausibly overlapping speech in the mixed channel — unverified.

## ASR — final decision

**Use Whisper `base.en`** (faster-whisper, int8, CPU) — `WHISPER_MODEL=base.en`
in `.env.example`, `CHOSEN_WHISPER_SIZE` in `benchmarks/asr_benchmark.py`.

- **Over `small.en`:** small is the most accurate in both runs, but by 0.9–1.6
  points of rough WER, at ~5x the latency:
  - ~17 min vs. ~3 min per 30-min meeting on a quiet machine.
  - Slower than real time (RTF 1.1) on a busy one.

  Not worth it on this evidence. small is the upgrade path if accuracy
  becomes the binding constraint, and confirmed on more data.
- **Over `tiny.en`:** base's rough WER advantage is small and inconsistent
  (1.3 points in run 1, 0.4 in run 2) — **don't read it as a real gap**. What
  held in both runs is precision: base puts fewer wrong words in the
  transcript. That's the error that matters most downstream — a wrong word can
  become a wrong task in extraction, while a dropped "uh" costs nothing. The
  price is ~1.6x tiny's latency, about a minute more per meeting on a quiet
  machine.

  This is the closer of the two calls. If latency becomes the binding
  constraint, tiny is a defensible fallback.

**Resource budget for capacity planning (Checkpoint 1):**
- **Typical:** RTF ~0.1 (~3 min per 30-min meeting), ~750s CPU time per 30-min
  meeting.
- **Plan for up to RTF ~0.3:** measured on a busy host with many fallback
  re-decodes (~9 min per 30-min meeting, ~1,400s CPU).
- **Memory:** ~2GB peak.

All on a dev laptop CPU (4 threads, no GPU). Scale by meeting volume and
concurrency once known.

**Output for extraction (R2):**
- Plain text per recording (segments joined).
- No speaker labels — the `Mix-Headset` channel has every speaker in one
  stream.
- No filler words ("uh"/"um").
- Phrases can repeat in short loops on hard audio (e.g. "I'm not sure."
  repeated ~10 times in a row in one IS1008b transcript).

## ASR — chunking (#478)

**Decision: `transcribe()` runs Whisper in chunks of at most 60 s, each cut in a
silence** (`CHUNK_S`, `MIN_CHUNK_S` in `src/mind/multimodal/asr.py`). Chunking
cuts peak memory by ~300 MiB with no measurable accuracy cost; cutting at fixed
times instead costs accuracy.

**Why chunk:** faster-whisper computes its features over the whole input at
once, so peak memory grows with the audio — measured on IS1008b with base.en:
~395 MiB for 60 s, ~530 MiB for 300 s, ~790 MiB for 600 s (the `MAX_DURATION_S`
cap), about 0.9 MiB per second on top of ~240 MiB fixed (Python, libraries,
model). MIND's container limit in the shared Helm chart is 512Mi. Turning the
VAD off saved only ~30 MiB, so the VAD is not the cause.

**How chunks are cut:** each 60 s window is cut in the middle of its latest
silence past 30 s, else hard-cut at 60 s. Silences come from Whisper's own VAD
(Silero), run per window — it too scores its whole input in one pass, so running
it on the whole recording would bring the memory growth back. A window with no
speech is skipped and never reaches Whisper. Segment timestamps are offset by
the chunk start, so they stay relative to the whole recording.

**Measurement:** `python -m benchmarks.asr_chunking`, 2026-10-12, same dev laptop
as above (CPU, 4 threads). The first 600 s of both #135 meetings — what
production transcribes under its cap — scored against AMI's human words that
start in those 600 s, with the same rough WER as above. Each (variant, meeting,
run) in its own process; peak RSS covers decoding plus transcription, what one
call costs. Two runs:

| Variant | IS1008b WER | IB4003 WER | Mean WER | Mean precision | Peak RSS |
|---|---|---|---|---|---|
| `full` — one pass over the 600 s (before #478) | 0.242 / 0.242 | 0.274 / 0.275 | 0.258 | 0.913 | 783–836 MiB |
| `fixed60` — cut every 60 s | 0.251 / 0.251 | 0.287 / 0.278 | 0.267 | 0.910 | 429–556 MiB |
| `production` — `transcribe()`, cut in silences | 0.251 / 0.251 | 0.271 / 0.271 | **0.261** | 0.911 | **440–549 MiB** |

- **Silence cuts ≈ unchunked.** 0.3 points apart on the mean, inside the
  run-to-run noise documented above: 0.9 points worse on IS1008b, 0.3–0.4 better
  on IB4003, the same in both runs. An earlier, pre-implementation run of the
  same comparison (cut points from one VAD pass over the whole 600 s) gave 0.260
  for both.
- **Fixed cuts are worse:** 0.9 points on the mean and worse on both meetings in
  both runs. In the pre-implementation run, one IB4003 run reached 0.317 (+4
  points). A cut inside a word or sentence loses it on both sides.
- **Peak memory still exceeds 512Mi** on IS1008b (549 MiB), before the
  embedding model that shares the process is counted. Peaks vary by ~±60 MiB
  between variants at the same chunk size (Windows allocator), so read them as
  ranges. Wall times varied 2–3x between identical runs here (other load on the
  laptop) and are not reported.

**Limits of this evidence:** the same as "ASR — known risks" below (n=2
meetings, one corpus, rough WER), plus: 600 s of each meeting, not the whole of
it. In production most AMI-like meetings will not reach ASR at all: Bridge pairs
a recording with its transcript sidecar (`transcript_object_key`), and R2's
consumer skips ASR when one exists — ASR is the fallback path.

## ASR — known risks

The decision is the best reading of thin evidence, not a measured certainty:

- **n=2 meetings, one corpus.** Both are AMI scenario meetings, mostly
  non-native English speakers, recorded on headsets. The gaps between sizes
  are small (0.4–1.6 points across two runs), so two more meetings could
  reorder base and tiny in particular. Other
  audio (phone calls, a single far-field microphone, native speakers) is
  untested.
- **The accuracy number is a rough proxy.** No normalization: filler words
  Whisper never emits, numbers vs. digits, spelling variants and the order of
  overlapping speech all count as errors, so the absolute rough WER (~0.26)
  is pessimistic and should not be quoted as Whisper's WER. These errors hit
  every size alike, which is why the *comparison* still holds. A real WER
  (`jiwer` + normalization) is the ASR validation work.
- **Run-to-run variance, about the size of the gaps.** The same model on the
  same audio does not always produce the same transcript. Examples across
  three runs:
  - `base.en` on IS1008b: 0.219, 0.226 and 0.224.
  - `tiny.en` on IS1008b: 0.226, then 0.219.
  - `small.en` on IS1008b: 0.219, then 0.209.

  **Cause — verified
  for `base.en` on IS1008b only:** when a beam-search segment fails
  faster-whisper's quality checks (compression ratio, average log-prob), it
  is re-decoded at a higher temperature, which *samples*. In a default run,
  40 of 392 segments fell back to temperature 0.2, and the transcript
  differed from the previous run's. With fallback off (`temperature=0`), two
  runs were identical word for word — so the randomness is the fallback
  alone. **But fallback can't simply be turned off:** without it the model
  got stuck in a loop ("it was supposed to change seats." 102 times) and
  rough WER jumped to 0.458. The variance is the price of the loop guard.
  For the other sizes this is still a hypothesis — though run 2 shows every
  size falling back (2–30 segments per meeting), so all of them sample
  sometimes.
- **Latency varies even more than accuracy.** Same model, same audio:
  `base.en` on IS1008b ran at RTF 0.098 in one run and 0.307 in another.
  Fallback re-decodes cost extra CPU (that run had 30 fallback segments and
  twice the CPU time), and other load on the host costs wall time. Plan
  capacity on the worst case measured (RTF ~0.3), not the typical one.
- **One machine, CPU only.** Latency and memory are from one dev laptop with
  no GPU. A GPU, or a different CPU/thread count, changes the absolute
  numbers; the ranking between sizes is unlikely to flip, since compute grows
  with model size either way.
- **Unexplained memory.** ~2GB peak for all three sizes; the breakdown
  (model vs. audio decode) was not measured.

What would firm this up: more meetings (AMI has ~100 hours under the same
licence and layout), several runs per size to put error bars on the rough
WER, and a real WER once the validation harness exists.
