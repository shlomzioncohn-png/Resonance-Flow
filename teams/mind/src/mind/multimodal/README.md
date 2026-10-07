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
  memory, ~0.45s CPU time per page on a dev machine (no GPU, no load) --
  for a bare, FUNSD-sized image (0.75 MP). A scanned PDF page costs more,
  about 2s for a US-Letter page at 200 DPI: see `max_pages` below.
  See `benchmarks/ocr-asr-benchmark.md` for the full comparison and
  reasoning.
- **OCR: built (`ocr.py`).** `extract_text(content: bytes, *, filename: str =
  "", max_pages: int = 10) -> OCRResult` -- takes the raw bytes of an image or
  a PDF (PDFs are rasterized page-by-page via `pypdfium2` before OCR), runs
  `tesseract --psm 11` in TSV mode, and returns an `OCRResult`. It never
  raises. Fields:
  - `success: bool`
  - `text: str` -- all pages joined with a newline (the convenience view).
    **Line breaks are kept:** within a page, words are grouped into Tesseract's
    own lines (its `block_num` / `par_num` / `line_num`), spaces inside a line
    and a newline between lines, so a 4-line form comes back as 4 lines
    rather than one run-on string that mixes the fields of different lines.
  - `pages: list[str]` -- the text of each page (with the same line breaks);
    the index is the page number (0-based), with `""` for a page where
    nothing was recognised, so a blank page keeps its slot.
  - `pages_available: int`, `truncated: bool` -- a PDF longer than
    `max_pages` is **truncated, not refused**: `pages` holds the first
    `max_pages` pages, `pages_available` is the document's real page count and
    `truncated` is `True`. A caller should treat that text as a partial
    document (e.g. score a task inferred from it lower). A single image is one
    page.
  - `word_count: int`, `mean_confidence: float | None` -- over every
    recognised word, so the low-confidence list has a denominator ("12 weak
    words of 40" is not "12 of 4000"), and a caller can calibrate its own
    cut-off later without a new OCR run.
  - `low_confidence_words: list[LowConfidenceWord]` (`text`, `confidence`)
    and `low_confidence_count: int` -- words Tesseract itself scored below
    `LOW_CONFIDENCE_THRESHOLD` (**60**, a module constant: a starting point
    rather than a tuned value, it flags 12% of correct words and catches ~55%
    of real errors -- see above and the comment on the constant in `ocr.py`).
  - `reason: OCRFailureReason | None`, `error: str | None`, `retryable: bool`
    -- set when `success` is `False` (`reason` exactly then). `error` is the
    message for logs; `reason` is what to branch on, and the split that
    matters is *data* versus *environment* (and a third, a bug of ours):
    - data failures (retrying gives the same result, drop the document):
      `EMPTY_INPUT`, `UNREADABLE_FORMAT` (not a readable image or PDF --
      decided by PIL / `pypdfium2` *before* tesseract runs, so a corrupt or
      truncated file is never confused with a broken install),
      `NO_TEXT_RECOGNIZED` (a valid image/PDF in which no text was found:
      blank or too poor);
    - environment failures, `retryable=True` (nothing is wrong with the
      document -- do not treat it as processed, e.g. do not commit a Kafka
      offset past it): `OCR_UNAVAILABLE` (the `tesseract` binary is not
      installed), `OCR_FAILED` (tesseract exited non-zero, timed out, or
      could not be run, on an image already proven valid, so it is the
      environment -- e.g. a missing language pack, `tesseract-ocr` installed
      without `tesseract-ocr-eng`). `retryable` means "may succeed later",
      not "will": bound the retries, since a page that always times out fails
      every time;
    - a bug in our own code, `retryable=False`: `INTERNAL_ERROR` (an
      unexpected exception inside `extract_text`, logged with its traceback).
      Not retryable, because retrying the same document fails the same way
      and would block everything behind it; park the document (dead-letter)
      rather than dropping it silently. Only the expected environment errors
      above are `OCR_FAILED` -- a bug is never reported as one.

  `max_pages` (default `MAX_PAGES = 10`) bounds the cost of a call: only the
  first `max_pages` pages of a PDF are rendered and read, and the result says
  it was cut short (`truncated`, `pages_available`) -- the extraction that
  consumes this text needs title / description / source reference, almost
  always in the first pages, so a long document is not worth refusing. (A PDF
  that cannot even be opened cannot be counted either, and is
  `UNREADABLE_FORMAT`.) OCR runs synchronously, so it is time the caller's
  handler is blocked. Measured on the dev machine, a US-Letter page scanned at
  200 DPI (1700x2200 px) takes **about 2 s**: ~0.5 s to rasterize plus
  ~1.5 s of Tesseract, whose time grows with the pixel count -- which is why
  it is well above the ~0.7 s of the 0.75 MP bare image in the benchmark.
  Dense pages cost more, sparse ones less, so 10 pages is about 20 s. The
  default is a guess at a sensible limit, not a measured one; pass
  `max_pages` to change it (at least 1; below that is a `ValueError`).

  The call is wrapped in `instrumented_call("tesseract-psm11-ocr")`
  (`src/mind/api/instrumentation.py`), so latency/cost are recorded like
  every other call in the service.

  **Signature: agreed with R2** (in Slack, confirmed on PR #509; she calls this from her
  `bridge.raw.landed` consumer, #349). She asked for: raw `bytes` in (her
  consumer already reads the object to verify its content hash, so a path
  would mean reading it twice; `filename` stays as a secondary hint -- the
  `%PDF-` magic-byte check covers the real cases); a typed failure reason so a
  data failure can be told from an environment one; the per-document counts;
  `pages`; and the page cap. *Whether a document is scanned* is the caller's
  decision, not `ocr.py`'s: `RawObjectEvent.modality` already has
  `Modality.SCANNED_DOC` and `SourceType.SCANNED_DOC` exists, so no new
  `source_type` value is needed (#188 looks stale). The open question is on
  Bridge's side: Bridge sets `modality` for the media lane (a scanned-docs
  directory is always `SCANNED_DOC`) and, for unstructured attachments, derives
  it from the MIME type (`image/*` and `application/pdf` give `SCANNED_DOC`; no
  MIME type gives `TEXT`, the contract's default), so a scanned file whose MIME
  type is missing would be routed down the text path -- R2 keeps `content_type`
  plus a magic-byte check as a fallback.

  Tests: `tests/test_ocr_result.py` covers the result shape, every failure
  `reason`, `retryable`, `pages`, the counts and the page cap with the
  tesseract-facing steps replaced, so it runs without the binary, in CI too.
  `tests/test_ocr_pipeline.py` runs the real binary (clean scan, multi-page
  PDF, a blank page in the middle, corrupted file and PDF, blank scan, empty
  input, low-confidence words) and skips without it; the FUNSD-backed ones
  additionally skip if `local-data/` isn't populated.
- **ASR: known risk, not just "open"** — no decision exists, and it
  cannot exist yet: there is no AMI audio (or any audio) sample
  anywhere in this repo, on any branch, to benchmark Whisper against
  (one branch, `michal/real-data-slice`, has an AMI package, but it's
  the text-only manual-annotations package, not audio).
  `WHISPER_MODEL=base` in `.env.example` is an **unverified
  placeholder**, not a decision — do not use it for capacity planning.
  See `benchmarks/ocr-asr-benchmark.md` "Known risk" / "Open item".
