# multimodal

Local OCR + ASR preprocessing.

- OCR: scanned documents / PDFs (validated against RVL-CDIP / FUNSD).
- ASR: meeting audio / recordings (validated against AMI so far -- two meetings;
  ICSI, QMSum, MeetingBank not yet).
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
  - `reason: OCRFailureReason | None`, `error: str | None`,
    `failure_category: FailureCategory | None`, `retryable: bool` -- set when
    `success` is `False` (`reason` exactly then). `error` is the message for
    logs; `reason` is what to branch on finely, `failure_category` (`DATA` /
    `ENVIRONMENT` / `BUG`, shared with ASR -- see "Shared by OCR and ASR" below)
    how to treat the document, and `retryable` is `failure_category ==
    ENVIRONMENT`. The split that matters is *data* versus *environment* (and a
    third, a bug of ours):
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

  **Worst case, which is what a consumer must be sized against:** one
  Tesseract run (one page) is killed after `TESSERACT_TIMEOUT_S = 20` s and the
  call fails with `OCR_FAILED`, so with the default `max_pages` the OCR can take
  at most 10 x 20 = **200 s**, under librdkafka's default `max.poll.interval.ms`
  of 300 s (`teams/mind` sets none). A consumer that runs OCR inside its Kafka
  handler for longer than that is dropped from the group mid-handler, its
  commit fails after the rebalance and the message is reprocessed -- duplicate
  OCR work on the very document that was too slow. The 200 s bound covers the
  Tesseract runs only: rasterizing a PDF page (~0.5 s each, not capped) comes
  on top, and a caller that raises `max_pages` should keep
  `max_pages x 20 s` under its own poll interval.

  Every Tesseract run is recorded through `observability.record_call()`
  (`tesseract-psm11-ocr`, `kind=ocr`, `status=error` if it failed), like
  every other model call in the service, with the CPU seconds Tesseract's
  child processes used as its compute cost -- a local model has no dollar
  price -- and `cpu_s` `None` on Windows, which reports no child-process CPU
  (#479, `observability/compute.py`).

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
- **ASR: resolved — Whisper `base.en`**, run locally with `faster-whisper`
  (int8, CPU), `WHISPER_MODEL=base.en` in `.env.example`. Chosen over
  `tiny.en` and `small.en` on two AMI meetings (IS1008b, IB4003; 63 min of
  `Mix-Headset` audio), two runs, on accuracy per unit of latency:
  - `base.en`: rough WER 0.263–0.265, word precision 0.919–0.923, ~10x faster
    than real time on a quiet machine (RTF 0.10, ~3 min per 30-min meeting;
    up to RTF ~0.3 measured under load), ~2GB peak memory *in the benchmark*
    (a whole 30-min meeting in one pass -- `asr.py` transcribes in chunks and
    peaks at ~440–550 MiB, see "ASR: built" below).
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

  Benchmark numbers (whole meetings, one pass): RTF ~0.1 typical / ~0.3 worst
  measured, ~2GB peak memory, ~750–1,400s CPU time per ~30-min meeting on a
  dev machine (4 threads, no GPU). See `benchmarks/ocr-asr-benchmark.md` "ASR
  — final decision" and "ASR — known risks". For what the service itself
  costs, see the resource budget under "ASR: built".
- **ASR: built (`asr.py`, #478).** `transcribe(content: bytes, *, filename: str =
  "", max_duration_s: float = 600) -> ASRResult` -- the raw bytes of any audio
  FFmpeg reads (wav, mp3, m4a, ogg, webm, mp4 audio; detected from the bytes),
  decoded with PyAV to 16 kHz mono and transcribed with Whisper `base.en`. It
  never raises. Fields:
  - `success: bool`, `text: str` -- every segment's text, one line per segment.
  - `segments: list[TranscriptSegment]`, in order, each with:
    - `text`, and `start` / `end` in seconds **from the start of the recording**
      (not of a chunk) -- the audio counterpart of OCR's page index, for R2's
      `extraction_provenance` ("from 14:32 to 14:40"). Not for `source_ref`,
      which Bridge now sends as event metadata.
    - Whisper's own scores, passed through unchanged (#478: no score invented on
      top of them): `avg_logprob` -- mean token log-probability, **natural log,
      <= 0** (not Tesseract's 0–100: OCR's cut-off of 60 has no counterpart);
      `compression_ratio` -- catches Whisper's characteristic failure, a confident
      loop on a hard stretch, that `avg_logprob` misses (Whisper treats > 2.4 as a
      failed decode); `no_speech_prob` (0–1).
    - `confidence = exp(avg_logprob)`, the same number out of log space (0–1),
      derived rather than stored.
    - **Granularity:** faster-whisper computes those three scores once per ~30 s
      decoding window and gives every segment of the window the same values --
      they are not per segment. Aggregate by duration, not by segment count.
  - `mean_confidence: float | None` -- the **duration-weighted** mean of the
    segments' `confidence` (R2 consumes this rather than computing her own);
    `low_confidence_segments` / `low_confidence_count` -- the segments below
    `LOW_CONFIDENCE_THRESHOLD = exp(-1.0) ~= 0.37`, which is Whisper's own
    `log_prob_threshold` (the point at which it re-decodes a segment), not a
    cut-off of ours; a starting point to calibrate against extraction, like OCR's.
  - How much of the recording the transcript covers -- **two independent,
    stored booleans** (not derived from the durations, unlike OCR's
    `truncated`; both can be true at once):
    - `truncated` -- **we** stopped before the end: the `max_duration_s` cap, or
      `TRANSCRIBE_TIMEOUT_S`.
    - `incomplete_file` -- the **file** is shorter than it says: a decode error
      after valid audio, or a header declaring more audio than the file holds (a
      WAV's RIFF data size, read without decoding; the container's length for
      MP3/M4A), beyond max(0.5 s, 5%). What decoded is transcribed, minus the
      segments ending within `BREAK_MARGIN_S = 1` s of the break (a sentence cut
      there is where Whisper invents or loops; R2 preferred dropping them to
      flagging them). Before this, a WAV or MP3 cut off mid-way decoded cleanly
      and passed as a complete recording: FFmpeg recomputes a WAV's length from
      the file size. **Caveat:** decoding stops at the cap, so for MP3/M4A a
      break *past* the cap cannot be seen -- `False` means "no break found in
      what was read". For WAV the header check covers the whole file.
  - `duration_s` -- how much was transcribed; `duration_available_s` -- the
    recording's length as far as it is known; `duration_available_exact` --
    `True` when the file was decoded to its end (then `duration_available_s` is
    exactly the audio it holds, for a broken file what survived), `False` when
    decoding stopped at the cap (then it is the header's declared length --
    exact for M4A, close for MP3, from the data size for WAV -- or, without one,
    "at least" what was read). **`False` means not verified by decoding, not
    necessarily wrong.**
  - `reason: ASRFailureReason | None`, `error`, `failure_category`, `retryable`
    -- the same envelope and categories as OCR (see "Shared by OCR and ASR"):
    - `DATA`: `EMPTY_INPUT`; `UNREADABLE_FORMAT` (not decodable audio, decided by
      PyAV *before* Whisper runs -- the same name as OCR's, so one branch; a
      corrupt header lands here, not a crash); `NO_SPEECH_DETECTED`
      (silence, noise, or a broken file with no complete segment before its
      break -- never an empty success or a made-up transcript).
    - `ENVIRONMENT`: `ASR_UNAVAILABLE` (weights missing, failing their sha256
      check, or faster-whisper not installed), `ASR_FAILED` (Whisper failing on
      audio already proven decodable, e.g. out of memory).
    - `BUG`: `INTERNAL_ERROR` (an unexpected exception, logged with its traceback).

  **How a call runs.** Decoding stops at `max_duration_s`: nothing past the cap
  is decoded, not even to count it (34 min of MP3: 0.6 s instead of 6.1 s
  decoding the whole file; a 3 h one would have taken ~35 s). The decoded audio
  is transcribed in **chunks of at most `CHUNK_S = 60` s, each cut in the
  middle of a silence** past `MIN_CHUNK_S = 30` s (Whisper's own Silero VAD, run
  per window), else hard-cut at 60 s. Why: faster-whisper computes its features
  over the whole input, so peak memory grew ~0.9 MiB per second of audio
  (~790 MiB at the 600 s cap); chunked it is ~440–550 MiB whatever the length,
  with rough WER against AMI's human transcript within noise of unchunked
  (0.261 vs 0.258; fixed 60 s cuts were worse, 0.267) -- `benchmarks/ocr-asr-benchmark.md`
  "ASR — chunking", reproducible with `python -m benchmarks.asr_chunking`. A
  chunk with no speech never reaches Whisper, and `vad_filter=True` drops the
  silences inside each chunk: without it Whisper "hears" stock phrases in
  silence. Whisper runs as #135 measured it: int8, CPU, beam 5, `language="en"`,
  `cpu_threads=4` set explicitly (`tests/test_asr_settings_sync.py` keeps these
  equal to the benchmark's) -- explicit, so `OMP_NUM_THREADS=1` (which
  PaddleOCR sets on its process) does not make it single-threaded; measured.

  **Worst case, which is what a consumer must be sized against.** RTF is not
  stable: ~0.1 typical and ~0.3 on whole meetings under load, but **~2 on one
  hard minute of IB4003** (temperature-fallback re-decodes and a repetition
  loop -- the run-to-run variance in "ASR — known risks", triggered here by a
  tiny numerical difference in the environment). So the bound is not the cap
  times an RTF but `TRANSCRIBE_TIMEOUT_S = 240` s, checked between segments and
  chunks: past it `transcribe()` returns what it has, `truncated=True` (or
  `ASR_FAILED` if nothing was decoded yet). On top of it: decoding up to the
  cap (~1 s) and, on a first call without `warm_up()`, loading the model
  (~1.5 s). R2 sets `max.poll.interval.ms` explicitly in her consumer; 360 s or
  more leaves room for both.

  **The model** is a per-process singleton, loaded on first use or by
  `warm_up()`, which R2's consumer calls once at start-up (it never raises, and
  returns whether ASR is ready). The weights are pinned (`PINNED_WHISPER_MODELS`:
  repo, revision, sha256 of `model.bin`) and checked against that sha256 on
  every load, whatever their source:
  1. baked into the image at build time (`WHISPER_MODELS_DIR=/opt/models/whisper`,
     see the Dockerfile -- the team-lead decision on #478: fast pod start, no
     egress to Hugging Face from the cluster, one model in every pod);
  2. `MODEL_CACHE_DIR/whisper/<size>/` for local dev, placed by hand (see
     `models/README.md`);
  3. else, as a local-dev fallback only, downloaded at that pinned revision
     into (2). An unpinned size is never downloaded.

  Every call whose audio decodes is recorded through
  `observability.record_call()` (`whisper-base.en-int8-local`, `kind=asr`,
  with its CPU seconds as its compute cost -- a local model has no dollar
  price, #479), as #478 asks. OCR is recorded the same way -- `record_call()` is
  the one mechanism for model calls.

  **Resource budget -- measured in a Linux container, one process holding the
  embedding model and Whisper as the service does** (`benchmarks/asr_container_probe.py`;
  `benchmarks/ocr-asr-benchmark.md` "ASR — memory and CPU in a container"):
  - **Memory: ~1,000 MiB peak** (952–1,003 over 7 runs) on a capped 10-min call:
    ~560 for the embedding model with torch, ~290 more for Whisper, ~150 more for
    a call. **The shared Helm chart gives MIND 512Mi** -- it needs raising (Relay
    owns the chart). `MALLOC_ARENA_MAX=2` was measured and not adopted (within
    the spread between runs). Decoding no longer copies the audio: its own peak
    went from 112 MiB to 37 MiB for 600 s.
  - **CPU: at the chart's 500m, ~6.7 min of a 10-min recording fit in
    `TRANSCRIBE_TIMEOUT_S`** (RTF ~0.6; the rest comes back as `truncated`);
    unlimited, the whole 10 min took 41 s (RTF 0.07). Whether 500m is enough is
    a trade-off for the cluster, not a bug: the timeout held either way, its
    overrun at most one decoding window (23 s at half a CPU).

  **Interface: agreed with R2** (#478, on the issue thread). She calls
  `transcribe()` from her `bridge.raw.landed` consumer (#349) when `modality`
  is `AUDIO`, the same seam as OCR: bytes in, no exceptions, the three failure
  categories, a cap that truncates rather than refuses, passing her own
  `max_duration_s` per call as she does `max_pages`. **ASR is the fallback
  path**: for a `MEETING` recording Bridge lands a same-basename transcript
  sidecar (`.vtt` / `.srt` / `.txt`) and links it from the event's
  `transcript_object_key`; when there is one, her consumer reads it and skips
  ASR -- AMI-style meetings with a human transcript will mostly never reach
  `asr.py`. No shared `to_text(event, content)` wrapper: routing on `modality`
  and `transcript_object_key` is hers, and would tie `multimodal/` to
  `contracts.RawObjectEvent` and to object storage, which belong to ingestion.
  No speaker diarization: `ExtractedFields` has no speaker field, assignment is
  Inbox's, and `participants` already comes on the event.

  Tests: `tests/test_asr_result.py` covers the result shape, scores, the
  duration-weighted mean, the failure categories (shared with OCR) and the two
  flags, without the model. `tests/test_asr_pipeline.py` covers decoding (stops
  at the cap; nothing past it decoded), chunking (cuts in silences, timestamps
  from the start of the recording, silent chunks skipped), the timeout, model
  loading (baked first, sha256 on every load, download fallback, `warm_up()`),
  `record_call()`, and files cut off mid-way (WAV, MP3, garbage in the middle,
  both flags at once, the segment at the break dropped) with Whisper replaced,
  so it runs in CI; and, with the real `base.en` (skipped without
  `models/whisper/base.en/`), a clean short recording, an unsupported format,
  empty and silent audio, the cap, and three sentences cut in the third. The
  speech fixture `tests/fixtures/asr/short_meeting_note.wav` was generated with
  the Windows speech synthesizer (offline TTS).
- **Shared by OCR and ASR** (`result.py`, `failures.py`). Both results derive
  from `MediaTextResult`: `success`, `text`, `reason` (each pipeline's own enum),
  `error`, `failure_category`, `retryable`, with `reason` set exactly on
  failure. Every reason belongs to one `FailureCategory` (a test fails if a new
  reason has none), which tells the caller what to do with the raw object:

  | Category | Meaning | What the caller does |
  |---|---|---|
  | `DATA` | the input itself is the problem | drop it, commit past it |
  | `ENVIRONMENT` | the input is fine; a model, binary or host is not | do not commit past it; retry later, with a bound |
  | `BUG` | an unexpected exception in our code | park it (dead-letter), do not drop it silently |

  `retryable` is `failure_category == ENVIRONMENT`; on its own it could not
  tell `DATA` (drop) from `BUG` (park). Reason names with the same meaning are
  the same in both (`EMPTY_INPUT`, `UNREADABLE_FORMAT`, `INTERNAL_ERROR`).

  **Reserved for OCR, not built yet: `incomplete_file`**, with the same name and
  meaning as in ASR (the file holds less than it declares; what survived is
  returned, and the flag is independent of `truncated`). Not now, by R2's
  call (#478): in Bridge's path a file cut in transfer is already caught by
  her `content_hash` check before OCR runs, so what is left is a file broken at
  its source, not yet seen in practice; and a broken PDF needs a decision first
  on what `pages_available` means, since `pypdfium2` may report a page count
  from the xref while only some of the pages render. Today such a PDF fails
  loudly as `UNREADABLE_FORMAT`, not silently.
