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

## Purpose

Not "which config looks better" — a final, number-backed decision for
each modality, so extraction (week 4) knows exactly what to build
against, and Checkpoint 1 has real latency/resource numbers for
capacity planning.

## Scope

**OCR: done, 5 configs (3 Tesseract + 2 PaddleOCR); Tesseract `--psm 11` chosen — see "OCR — final decision".**
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
evidence, not a validated detector, and for Tesseract only (PaddleOCR's per-line score hasn't been evaluated):

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
