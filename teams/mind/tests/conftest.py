"""
Test-session setup, shared across every test module in this package.

Also generates the messaging module's proto classes before tests run, in case
pytest was invoked directly (not via `make test`), see `make proto`.
"""

import asyncio
import os
import subprocess
import sys
from pathlib import Path

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

_ROOT = Path(__file__).resolve().parent.parent
_MESSAGING = _ROOT / "src" / "mind" / "messaging"

if not (_MESSAGING / "mind_task_created_pb2.py").exists():
    subprocess.run(
        [
            "python", "-m", "grpc_tools.protoc",
            "--proto_path", str(_ROOT / "proto"),
            "--python_out", str(_MESSAGING),
            str(_ROOT / "proto" / "mind-task-created.proto"),
            str(_ROOT / "proto" / "mind-task-dependencies.proto"),
        ],
        check=True,
    )
