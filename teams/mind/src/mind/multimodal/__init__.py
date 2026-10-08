"""Local OCR + ASR.

OCR pipeline for scanned documents/PDFs (see ocr.py); ASR (transcription)
pipeline for audio/meeting recordings, not yet built -- blocked on missing
AMI audio, see benchmarks/ocr-asr-benchmark.md "Known risk". Feeds the same
downstream extraction pipeline as text-native sources.
"""
