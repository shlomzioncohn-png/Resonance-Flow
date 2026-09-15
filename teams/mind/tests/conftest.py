"""
Test-session setup, shared across every test module in this package.
"""

import asyncio
import os
import sys

if sys.platform == "win32":
    # psycopg3's async mode refuses to run on Windows' default event loop
    # (ProactorEventLoop, used by asyncio.run() since Python 3.8) --
    # raises "Psycopg cannot use the 'ProactorEventLoop' to run in async
    # mode." It needs SelectorEventLoop instead. Windows-only: Linux (the
    # real deployment target) never hits this, since SelectorEventLoop is
    # already the only option there. Must be set before pytest-asyncio
    # creates its first event loop, so this runs at import time, at the
    # top of conftest.py, before anything else gets a chance to.
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

# Point every test at a dedicated table instead of the real
# `task_embeddings` table `config.py` defaults to -- tests truncate their
# table between runs (see `clean_store` in test_vector_store.py), and
# doing that against the production table would be actively destructive
# if this suite were ever pointed at a real/shared database. Must be set
# before `mind.embeddings.config.get_settings()` is first called by any
# test module -- conftest.py is guaranteed to load before test modules
# are collected, so this always wins the race.
os.environ.setdefault("MIND_VECTOR_STORE_TABLE", "task_embeddings_test")
