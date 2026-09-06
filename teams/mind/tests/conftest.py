"""
Test-session setup, shared across every test module in this package.
"""

import os

# Point every test at a dedicated table instead of the real
# `task_embeddings` table `config.py` defaults to -- tests truncate their
# table between runs (see `clean_store` in test_vector_store.py), and
# doing that against the production table would be actively destructive
# if this suite were ever pointed at a real/shared database. Must be set
# before `mind.embeddings` (and therefore `config.py`) is first imported
# by any test module, since `Settings()` reads it once at import time --
# conftest.py is guaranteed to load before test modules are collected.
os.environ.setdefault("MIND_VECTOR_STORE_TABLE", "task_embeddings_test")
