# multimodal

Local OCR + ASR preprocessing.

- OCR: scanned documents / PDFs (validated against RVL-CDIP / FUNSD).
- ASR: meeting audio / recordings (validated against AMI, ICSI, QMSum, MeetingBank).
- Output feeds `extraction/` through the same interface as text-native sources.
- **OCR: resolved — Tesseract, not PaddleOCR.** local Tesseract,
  `--psm 11` (sparse text: find as much text as possible, in no
  particular order). Tested against 2
  other Tesseract configs (`--psm 3` default, `--psm 6` single block)
  **and** against PaddleOCR (`mobile`/`server`, PP-OCRv5) on FUNSD's
  ground truth. `--psm 11` remains the pick: PaddleOCR's best result
  (`mobile`, 66.5% word recall) beat it by only 2.1 points (64.7%) at
  ~80x the latency and ~16x the memory — not worth it for
  general-volume processing. `paddleocr_mobile` is the documented
  fallback if a use case ever needs that extra accuracy badly enough
  to pay for it (see the benchmark doc — `paddleocr_server` is *not*
  a good fallback; it cost 22x more than `mobile` for equal-or-worse
  recall in this test, likely due to a CPU-acceleration workaround
  needed on the dev machine).
  **Real accuracy is ~91%, not 64.7%:** the headline word-recall figure
  is an exact-match proxy against FUNSD's own annotations; measured at
  the character level instead, `--psm 11` scores **91.2%**. The 27-point
  gap is mostly dataset/metric noise, not OCR quality — 59% of the
  "missed" words were cases where Tesseract's output was actually
  correct (digits merged with an adjacent field, a word-boundary
  mismatch against the annotation, or in one case an outright typo in
  FUNSD's own ground truth). Full breakdown:
  `benchmarks/word-recall-error-analysis.md`.
  **Per-word confidence, for the real residual errors:** Tesseract
  already scores each recognized word 0-100 internally (its `tsv`
  output mode) and that score predicts the errors that are real —
  correctly-read words average 80.4 confidence vs. 46-64 for every
  miss category, lowest (46.3) for the one category worth worrying
  about (OCR confidently producing a different, wrong word). The OCR
  interface should surface this per-word confidence alongside the text
  so `extraction/` (once built) can discount or flag low-confidence
  words instead of trusting every word equally — see
  `benchmarks/analyze_confidence.py` and the benchmark doc's "per-word
  confidence" section for the numbers and a starting threshold.
  Resource budget for capacity planning: ~0.5s latency, ~35MB peak
  memory, ~0.45s CPU time per page on a dev machine (no GPU, no load).
  See `benchmarks/ocr-asr-benchmark.md` for the full comparison and
  reasoning.
- **ASR: known risk, not just "open"** — no decision exists, and it
  cannot exist yet: there is no AMI audio (or any audio) sample
  anywhere in this repo, on any branch, to benchmark Whisper against
  (one branch, `michal/real-data-slice`, has an AMI package, but it's
  the text-only manual-annotations package, not audio).
  `WHISPER_MODEL=base` in `.env.example` is an **unverified
  placeholder**, not a decision — do not use it for capacity planning.
  See `benchmarks/ocr-asr-benchmark.md` "Known risk" / "Open item".
