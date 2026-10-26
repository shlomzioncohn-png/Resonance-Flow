# benchmarks

Comparison-run outputs: vector-store bake-offs, local-model (OCR/ASR)
comparisons, and any other "which tool" decision that needs to be
documented and reasoned about (e.g. the week-1 vector-store choice, the
managed-API-vs-local-model spike). Keep raw run outputs here; write the
resulting decision into the relevant README or an ADR.

- `pipeline-baseline-482.md` — **preliminary**, single-run latency and cost
  baseline of the full #359 demo flow (#482), per stage and per item, with its
  raw JSONL and report under `pipeline-baseline-482/<run-id>/`. Embedding and
  vector queries not measured yet, and every score degraded (no Chain): read
  its limitations before using a number from it.
- `ocr-asr-benchmark.md` — OCR: 5 configs benchmarked (Tesseract psm 3/6/11,
  PaddleOCR mobile/server); Tesseract `--psm 11` chosen on latency and memory,
  with the accuracy trade (PaddleOCR is ~2.7x more accurate) documented. ASR:
  3 Whisper sizes benchmarked (tiny.en/base.en/small.en) on two AMI meetings;
  base.en chosen, with the sample's limits written down as known risks. Status
  also in `src/mind/multimodal/README.md`.
- `ocr_asr_benchmark.py` — reproduces both halves (`python -m
  benchmarks.ocr_asr_benchmark` from `teams/mind/`; ends in an explicit
  conclusion line for OCR and for ASR). Needs `pip install ".[benchmark]"`
  plus the `tesseract-ocr` system binary (not pip-installable — e.g.
  `winget install UB-Mannheim.TesseractOCR` on Windows, `apt install
  tesseract-ocr` on Debian/Ubuntu), the AMI audio from
  `eval/datasets/ami-audio.manifest.json`, and the Whisper weights under
  `models/whisper/` (see the doc's "ASR — setup").
- `asr_benchmark.py` — the ASR half on its own (`python -m
  benchmarks.asr_benchmark`, ~25 min on a laptop CPU, most of it `small.en`).
