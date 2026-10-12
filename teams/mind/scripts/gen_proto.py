#!/usr/bin/env python3
"""Single source of truth for proto codegen -- called from Makefile,
Dockerfile, and tests/conftest.py. One copy means a schema edit can't
leave any of the three stale (see PR #555 review).

Usage:
    python scripts/gen_proto.py
"""

import re
import subprocess
import sys
from pathlib import Path

# protoc writes a cross-referenced proto's import by bare module name
# (e.g. task_updated_event_pb2.py does `import task_created_event_pb2`,
# mirroring the literal `import "task-created-event.proto";` in the .proto
# file with no package prefix). That only resolves at runtime via a
# sys.path hack, and doesn't resolve for mypy/pyi at all. Rewrite it to a
# package-relative import instead, in both the generated .py and .pyi.
_BARE_PB2_IMPORT = re.compile(r"^import (\w+_pb2) as ", re.MULTILINE)

_ROOT = Path(__file__).resolve().parent.parent
_MESSAGING = _ROOT / "src" / "mind" / "messaging"

# Host checkout: teams/mind/../../shared/proto. Docker build: the `shared`
# build context is copied to ./shared/proto inside WORKDIR (_ROOT) instead --
# see the Dockerfile's `COPY --from=shared`.
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

    _protoc(
        _ROOT / "proto",
        _ROOT / "proto" / "mind-task-created.proto",
        _ROOT / "proto" / "mind-task-dependencies.proto",
    )
    # task-{created,updated,completed}-event.proto come from shared/proto/
    # (the single registered copy, owned by Relay -- see
    # shared/proto/README.md), never vendored locally.
    _protoc(
        _SHARED_PROTO,
        _SHARED_PROTO / "task-created-event.proto",
        _SHARED_PROTO / "task-updated-event.proto",
        _SHARED_PROTO / "task-completed-event.proto",
    )


if __name__ == "__main__":
    main()
