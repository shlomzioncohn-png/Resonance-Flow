# multimodal

Local OCR + ASR preprocessing.

- OCR: scanned documents / PDFs (validated against RVL-CDIP / FUNSD).
- ASR: meeting audio / recordings (validated against AMI, ICSI, QMSum, MeetingBank).
- Output feeds `extraction/` through the same interface as text-native sources.
- **OCR: engine choice open — Tesseract vs. PaddleOCR mobile; needs a call
  from the team.** Both were benchmarked on FUNSD (n=2 documents). On
  characters (grouped CER — GT and OCR words matched by bounding-box overlap,
  each cluster of overlapping words scored as one string; lower is better, 0
  is perfect) PaddleOCR mobile scores **0.074** vs. **0.200** for Tesseract
  `--psm 11` — about 2.7x fewer character errors, confirmed by a check that
  uses no box matching (0.070 vs. 0.168). The cost: ~40–46s vs. ~0.5s per
  page and ~570–620MB vs. ~34MB (CPU, mkldnn off). The earlier decision
  "Tesseract, not PaddleOCR" rested on word recall looking tied (64.7% vs.
  66.5%); that was a measurement artifact — PaddleOCR drops the spaces inside
  a line, which exact-match word recall undercounts. Recommendation: if OCR
  runs as background ingestion, PaddleOCR mobile as the default with
  Tesseract `--psm 11` as the lightweight fallback; `paddleocr_server` is not
  worth it (CER 0.102, minutes per page). Open items before committing: re-time
  with mkldnn on, measure the image-size impact (Relay needs to know), confirm
  on more documents. Full numbers: `benchmarks/ocr-asr-benchmark.md`.
  An earlier "~91% accuracy" figure was retracted — it had no zero point and
  rewarded output volume (random gibberish scored 42–53% on it).
  **Per-word confidence:** Tesseract
  already scores each recognized word 0-100 internally (its `tsv`
  output mode), and that score differs between the miss groups in a
  heuristic classification of word-recall misses — correctly-read words
  average 80.4 confidence vs. 46-64 for every miss category, lowest
  (46.3) for OCR confidently producing a different, wrong word (directional
  evidence, not a validated error detector). The OCR
  interface should surface this per-word confidence alongside the text
  so `extraction/` (once built) can discount or flag low-confidence
  words instead of trusting every word equally — see
  `benchmarks/analyze_confidence.py` and the benchmark doc's "per-word
  confidence" section for the numbers and a starting threshold.
  Resource budget for capacity planning: ~0.5s latency, ~35MB peak
  memory, ~0.45s CPU time per page on a dev machine (no GPU, no load).
  See `benchmarks/ocr-asr-benchmark.md` for the full comparison and
  reasoning.
- **ASR: known risk, not just "open"** — no decision exists, and it
  cannot exist yet: there is no AMI audio (or any audio) sample
  anywhere in this repo, on any branch, to benchmark Whisper against
  (one branch, `michal/real-data-slice`, has an AMI package, but it's
  the text-only manual-annotations package, not audio).
  `WHISPER_MODEL=base` in `.env.example` is an **unverified
  placeholder**, not a decision — do not use it for capacity planning.
  See `benchmarks/ocr-asr-benchmark.md` "Known risk" / "Open item".
