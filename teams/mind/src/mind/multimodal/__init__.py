"""Local OCR + ASR.

OCR for scanned documents/PDFs (ocr.py: Tesseract --psm 11) and ASR for
audio/meeting recordings (asr.py: Whisper base.en via faster-whisper), both
returning the same envelope and failure categories (result.py, failures.py), so
they feed the same downstream extraction pipeline as text-native sources. See
this package's README.
"""
