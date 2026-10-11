# multimodal

Local OCR + ASR preprocessing.

- OCR: scanned documents / PDFs (validated against RVL-CDIP / FUNSD).
- ASR: meeting audio / recordings (validated against AMI, ICSI, QMSum, MeetingBank).
- Output feeds `extraction/` through the same interface as text-native sources.
- **OCR: resolved — Tesseract, `--psm 11`** (sparse text), decided with the
  mentor on latency and memory: ~0.5s and ~34MB per page vs. ~40–46s and
  ~570–620MB for PaddleOCR mobile (CPU), with no heavy dependency in the image.
  **This is an accuracy trade made knowingly:** on FUNSD (n=2 documents)
  PaddleOCR mobile has ~2.7x fewer character errors — grouped CER 0.074 vs. 0.200
  for Tesseract `--psm 11` (grouped CER: GT and OCR words matched by
  bounding-box overlap, each cluster of overlapping words scored as one string;
  lower is better, 0 is perfect; a check with no box matching agrees, 0.070 vs.
  0.168). A stricter one-to-one version gives 0.37 for Tesseract and is the
  conservative bound. PaddleOCR mobile is the documented upgrade path if
  accuracy becomes the binding constraint (not `paddleocr_server`). Full
  numbers and the open items for switching: `benchmarks/ocr-asr-benchmark.md`.
  An earlier "~91% accuracy" figure was retracted — it had no zero point and
  rewarded output volume (random gibberish scored 42–53% on it). Unaligned word
  recall (64.7%) is a secondary, recall-only number that undercounts PaddleOCR.
  **Per-word confidence:** Tesseract already scores each recognized word
  0-100 internally (its `tsv` output mode), and that score differs between
  correctly-read words (avg 80.4) and the miss groups of a heuristic
  classification of word-recall misses (avg 45-63) — but the distributions
  overlap heavily: a cut-off of 60 flags 12% of correct words and catches only
  ~55% of the real errors, and no cut-off does both (directional evidence,
  Tesseract only, not a validated detector). The OCR interface should still
  return it alongside the text, as one signal for `extraction/` (once built)
  rather than a filter — see `benchmarks/analyze_confidence.py` and the
  benchmark doc's "per-word confidence" section.
  Resource budget for capacity planning: ~0.5s latency, ~35MB peak
  memory, ~0.45s CPU time per page on a dev machine (no GPU, no load).
  See `benchmarks/ocr-asr-benchmark.md` for the full comparison and
  reasoning.
- **ASR: resolved — Whisper `base.en`**, run locally with `faster-whisper`
  (int8, CPU), `WHISPER_MODEL=base.en` in `.env.example`. Chosen over
  `tiny.en` and `small.en` on two AMI meetings (IS1008b, IB4003; 63 min of
  `Mix-Headset` audio), two runs, on accuracy per unit of latency:
  - `base.en`: rough WER 0.263–0.265, word precision 0.919–0.923, ~10x faster
    than real time on a quiet machine (RTF 0.10, ~3 min per 30-min meeting;
    up to RTF ~0.3 measured under load), ~2GB peak memory.
  - `small.en` is 0.9–1.6 points better on rough WER and ~5x slower (~17 min
    per 30-min meeting; slower than real time under load).
  - `tiny.en`'s rough WER is within noise of base's (0.4–1.3 points worse),
    but its precision is lower in both runs (0.900–0.910): more wrong words
    reaching `extraction/`. The closer call — tiny is the fallback if latency
    becomes the binding constraint.

  **Known risks, not hidden behind the pick:**
  - n=2 meetings from one corpus.
  - The accuracy number is a rough word diff against AMI's human transcript
    (no normalization), not a real WER.
  - Run-to-run variance (~1 point on the same input) is about the size of
    the gaps between sizes, and latency varies up to 3x between runs.

  Resource budget for capacity planning: RTF ~0.1 typical / ~0.3 worst
  measured, ~2GB peak memory, ~750–1,400s CPU time per ~30-min meeting on a
  dev machine (4 threads, no GPU). See `benchmarks/ocr-asr-benchmark.md` "ASR
  — final decision" and "ASR — known risks".
