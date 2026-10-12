"""Local OCR + ASR.

OCR pipeline for scanned documents/PDFs (see ocr.py); ASR (transcription)
pipeline for audio/meeting recordings, not yet built -- the model is decided
(Whisper base.en, see benchmarks/ocr-asr-benchmark.md "ASR -- final
decision"), the pipeline itself is the next step. Feeds the same
downstream extraction pipeline as text-native sources.
"""
