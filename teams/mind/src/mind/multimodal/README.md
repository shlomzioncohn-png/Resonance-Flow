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
- **OCR: built (`ocr.py`).** `extract_text(content: bytes, *, filename:
  str = "") -> OCRResult` -- takes raw file bytes (image or PDF; PDFs are
  rasterized page-by-page via `pypdfium2` before OCR), runs
  `tesseract --psm 11` in TSV mode, and returns an `OCRResult`:
  - `success: bool` -- `False` for a corrupted file, empty input, or a
    scan Tesseract couldn't read at all (never an exception).
  - `text: str`
  - `low_confidence_words: list[LowConfidenceWord]` (`text`, `confidence`)
    -- words Tesseract itself scored below `LOW_CONFIDENCE_THRESHOLD`
    (**60**, a starting point rather than a tuned value: it flags 12% of
    correct words and catches ~55% of real errors, see above and the comment
    on the constant in `ocr.py`).
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
