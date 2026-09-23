"""
Test-session setup, shared across every test module in this package.

Also generates the messaging module's proto classes before tests run, in case
pytest was invoked directly (not via `make test`), see `make proto`.
"""

import asyncio
import subprocess
import sys
from pathlib import Path

from dotenv import load_dotenv

if sys.platform == "win32":
    # psycopg3's async mode rejects Windows' default ProactorEventLoop.
    # Must be set before pytest-asyncio creates its first loop. Linux (the
    # deployment target) never hits this.
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

_ROOT = Path(__file__).resolve().parent.parent

# tests/test_vector_store.py reads MIND_VECTOR_STORE__DSN straight from
# os.environ (deliberately, not via mind.config.Settings -- see its `store`
# fixture docstring), which CI satisfies by exporting it as a real workflow
# env var. Locally there's no such step -- `.env` is just a file until
# something loads it -- so load it here. Never overrides a var already set
# (CI keeps exporting its own; load_dotenv only fills in what's missing),
# and silently no-ops if `.env` doesn't exist.
load_dotenv(_ROOT / ".env")

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
