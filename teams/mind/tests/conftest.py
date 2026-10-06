"""Generates the messaging module's proto classes before tests run, in case
pytest was invoked directly (not via `make test`), see `make proto`."""

import subprocess
from pathlib import Path

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
