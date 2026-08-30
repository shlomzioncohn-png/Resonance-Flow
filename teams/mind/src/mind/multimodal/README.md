# multimodal

Local OCR + ASR preprocessing.

- OCR: scanned documents / PDFs (validated against RVL-CDIP / FUNSD).
- ASR: meeting audio / recordings (validated against AMI, ICSI, QMSum, MeetingBank).
- Output feeds `extraction/` through the same interface as text-native sources.
- **OCR: resolved** — local Tesseract, `--psm 3` (Tesseract's own
  default page segmentation) — beat `--psm 6` (single text block) on
  word-recall against FUNSD's ground truth, with no cost in
  latency/memory. See `benchmarks/ocr-asr-benchmark.md` for the
  comparison and reasoning.
- **ASR: still open** — managed API vs. local model (e.g. local
  Whisper), and which size. Benchmark deferred to week 3 (no AMI
  sample downloaded locally yet) — see `benchmarks/ocr-asr-benchmark.md`
  "Open item".
