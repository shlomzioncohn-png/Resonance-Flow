# benchmarks

Comparison-run outputs: vector-store bake-offs, local-model (OCR/ASR)
comparisons, and any other "which tool" decision that needs to be
documented and reasoned about (e.g. the week-1 vector-store choice, the
managed-API-vs-local-model spike). Keep raw run outputs here; write the
resulting decision into the relevant README or an ADR.

- `ocr-asr-benchmark.md` — Tesseract PSM comparison on the FUNSD slice
  (ASR/Whisper deferred to week 3). Decision also recorded in
  `src/mind/multimodal/README.md`.
- `ocr_benchmark.py` — reproduces it. Needs `pip install ".[benchmark]"`
  plus the `tesseract-ocr` system binary (not pip-installable — e.g.
  `winget install UB-Mannheim.TesseractOCR` on Windows,
  `apt install tesseract-ocr` on Debian/Ubuntu).
