# multimodal

Local OCR + ASR preprocessing.

- OCR: scanned documents / PDFs (validated against RVL-CDIP / FUNSD).
- ASR: meeting audio / recordings (validated against AMI, ICSI, QMSum, MeetingBank).
- Output feeds `extraction/` through the same interface as text-native sources.
- **OCR: resolved** — local Tesseract, `--psm 11` (sparse text: find
  as much text as possible, in no particular order). Tested against 2
  other configs (`--psm 3` default, `--psm 6` single block) on FUNSD's
  ground truth; `--psm 11` won on every axis measured (64.7% word
  recall vs. 56.0%/48.2%, and also the fastest + lightest of the
  three) — matches FUNSD's scattered, disconnected-field layout better
  than either alternative. Resource budget for capacity planning:
  ~0.5s latency, ~33MB peak memory, ~0.45s CPU time per page on a dev
  machine (no GPU, no load). See `benchmarks/ocr-asr-benchmark.md` for
  the full comparison and reasoning.
- **ASR: known risk, not just "open"** — no decision exists, and it
  cannot exist yet: there is no AMI (or any audio) sample anywhere in
  this repo to benchmark Whisper against. `WHISPER_MODEL=base` in
  `.env.example` is an **unverified placeholder**, not a decision — do
  not use it for capacity planning. See `benchmarks/ocr-asr-benchmark.md`
  "Known risk" / "Open item".
