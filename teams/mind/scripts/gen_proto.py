#!/usr/bin/env python3
"""Single source of truth for proto codegen -- called from Makefile,
Dockerfile, and tests/conftest.py, so none of them goes stale.

Usage:
    python scripts/gen_proto.py
"""

import re
import subprocess
import sys
from pathlib import Path

# protoc emits cross-file imports as bare module names -- rewrite to
# package-relative so it resolves without a sys.path hack, and mypy/pyi see it.
_BARE_PB2_IMPORT = re.compile(r"^import (\w+_pb2) as ", re.MULTILINE)

_ROOT = Path(__file__).resolve().parent.parent
_MESSAGING = _ROOT / "src" / "mind" / "messaging"

# Host checkout vs. Docker's `COPY --from=shared` into ./shared/proto.
_SHARED_PROTO = (
    _ROOT / "shared" / "proto"
    if (_ROOT / "shared" / "proto").is_dir()
    else _ROOT.parent.parent / "shared" / "proto"
)


def _protoc(proto_path: Path, *proto_files: Path) -> None:
    subprocess.run(
        [
            sys.executable, "-m", "grpc_tools.protoc",
            f"--proto_path={proto_path}",
            f"--python_out={_MESSAGING}",
            f"--pyi_out={_MESSAGING}",
            *(str(f) for f in proto_files),
        ],
        check=True,
    )
    for proto_file in proto_files:
        stem = proto_file.stem.replace("-", "_") + "_pb2"
        for suffix in (".py", ".pyi"):
            generated = _MESSAGING / f"{stem}{suffix}"
            patched = _BARE_PB2_IMPORT.sub(r"from . import \1 as ", generated.read_text())
            generated.write_text(patched)


def main() -> None:
    _MESSAGING.mkdir(parents=True, exist_ok=True)
    (_ROOT / "src" / "mind" / "__init__.py").touch()

    # Relay's single registered copy -- never vendored locally.
    _protoc(
        _SHARED_PROTO,
        _SHARED_PROTO / "task-created-event.proto",
        _SHARED_PROTO / "task-updated-event.proto",
        _SHARED_PROTO / "task-completed-event.proto",
        _SHARED_PROTO / "task-creation-requested-event.proto",
        _SHARED_PROTO / "task-dependencies-proposed-event.proto",
        _SHARED_PROTO / "task-score-proposed-event.proto",
        _SHARED_PROTO / "raw-object-event.proto",
    )


if __name__ == "__main__":
    main()
