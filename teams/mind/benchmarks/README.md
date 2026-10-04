# benchmarks

Comparison-run outputs: vector-store bake-offs, local-model (OCR/ASR)
comparisons, and any other "which tool" decision that needs to be
documented and reasoned about (e.g. the week-1 vector-store choice, the
managed-API-vs-local-model spike). Keep raw run outputs here; write the
resulting decision into the relevant README or an ADR.

- `ocr-asr-benchmark.md` — OCR: 5 configs benchmarked (Tesseract psm 3/6/11,
  PaddleOCR mobile/server); the engine choice is open — PaddleOCR mobile is far
  more accurate, Tesseract far cheaper; the doc has the evidence and a
  recommendation. ASR: known risk, blocked on missing AMI data, not
  benchmarked. Status also in `src/mind/multimodal/README.md`.
- `aligned_metrics.py` — position-based CER used for the OCR accuracy numbers
  (grouped and strict); `analyze_*.py` — follow-up analyses of the misses and
  of Tesseract's per-word confidence.
- `ocr_asr_benchmark.py` — reproduces the OCR half (`python -m
  benchmarks.ocr_asr_benchmark` from `teams/mind/`; prints an explicit
  "BLOCKED" notice for the ASR half instead of fake numbers). Needs
  `pip install ".[benchmark]"` plus the `tesseract-ocr` system binary
  (not pip-installable — e.g. `winget install UB-Mannheim.TesseractOCR`
  on Windows, `apt install tesseract-ocr` on Debian/Ubuntu).
