"""
Test-session setup, shared across every test module in this package.
"""

import asyncio
import os
import sys

if sys.platform == "win32":
    # psycopg3's async mode rejects Windows' default ProactorEventLoop.
    # Must be set before pytest-asyncio creates its first loop. Linux (the
    # deployment target) never hits this.
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

# Dedicated test table, not the real `task_embeddings` default -- tests
# truncate it between runs, which would be destructive against production.
# Must be set before config.get_settings() is first called; conftest.py
# always loads before test modules are collected.
os.environ.setdefault("MIND_VECTOR_STORE_TABLE", "task_embeddings_test")
