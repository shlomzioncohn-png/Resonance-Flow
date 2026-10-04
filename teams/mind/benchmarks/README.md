# benchmarks

Comparison-run outputs: vector-store bake-offs, local-model (OCR/ASR)
comparisons, and any other "which tool" decision that needs to be
documented and reasoned about (e.g. the week-1 vector-store choice, the
managed-API-vs-local-model spike). Keep raw run outputs here; write the
resulting decision into the relevant README or an ADR.

- `ocr-asr-benchmark.md` — OCR: 5 configs benchmarked (Tesseract psm 3/6/11,
  PaddleOCR mobile/server); Tesseract `--psm 11` chosen on latency and memory,
  with the accuracy trade (PaddleOCR is ~2.7x more accurate) documented. ASR:
  known risk, blocked on missing AMI data, not benchmarked. Status also in
  `src/mind/multimodal/README.md`.
- `ocr_asr_benchmark.py` — reproduces the OCR half (`python -m
  benchmarks.ocr_asr_benchmark` from `teams/mind/`; prints an explicit
  "BLOCKED" notice for the ASR half instead of fake numbers). Needs
  `pip install ".[benchmark]"` plus the `tesseract-ocr` system binary
  (not pip-installable — e.g. `winget install UB-Mannheim.TesseractOCR`
  on Windows, `apt install tesseract-ocr` on Debian/Ubuntu).
