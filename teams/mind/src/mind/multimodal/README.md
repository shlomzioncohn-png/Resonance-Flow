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
- **OCR: built (`ocr.py`).** `extract_text(content: bytes, *, filename:
  str = "") -> OCRResult` -- takes raw file bytes (image or PDF; PDFs are
  rasterized page-by-page via `pypdfium2` before OCR), runs
  `tesseract --psm 11` in TSV mode, and returns an `OCRResult`:
  - `success: bool` -- `False` for a corrupted file, empty input, or a
    scan Tesseract couldn't read at all (never an exception).
  - `text: str`
  - `low_confidence_words: list[LowConfidenceWord]` (`text`, `confidence`)
    -- words Tesseract itself scored below `LOW_CONFIDENCE_THRESHOLD`
    (**60**, tuned against the confidence-by-miss-category numbers above;
    see the constant's docstring in `ocr.py` for the exact reasoning).
  - `error: str | None` -- set when `success` is `False`.

  The call is wrapped in `instrumented_call("tesseract-psm11-ocr")`
  (`src/mind/api/instrumentation.py`), so latency/cost are recorded like
  every other call in the service.

  **This signature is a working draft, not yet confirmed with R2** --
  she calls this from her `bridge.raw.landed` consumer (#349) once
  `RawObjectEvent.source_type` (`src/mind/contracts/raw_object_event.py`)
  has a value for "scanned document", which it does not yet (see #188).
  Update this section once the signature is agreed.

  Tests: `tests/test_ocr_pipeline.py` (clean scan, PDF input, corrupted
  file, corrupted PDF, unreadable/blank scan, empty input, low-confidence
  words). Tests that need the `tesseract` binary skip on a machine
  without it; the FUNSD-backed tests additionally skip if `local-data/`
  isn't populated.
- **ASR: known risk, not just "open"** — no decision exists, and it
  cannot exist yet: there is no AMI audio (or any audio) sample
  anywhere in this repo, on any branch, to benchmark Whisper against
  (one branch, `michal/real-data-slice`, has an AMI package, but it's
  the text-only manual-annotations package, not audio).
  `WHISPER_MODEL=base` in `.env.example` is an **unverified
  placeholder**, not a decision — do not use it for capacity planning.
  See `benchmarks/ocr-asr-benchmark.md` "Known risk" / "Open item".
