"""Guards eval/personas/ against silent drift after team-lead sign-off.

`load_personas`/`validate_personas` (personas.py) only check that a scenario
file is *structurally* valid — they say nothing if someone edits an already
`team_lead_approved` scenario's `expected_top_task_id`, `tier` or
`current_datetime`. That kind of edit is still a perfectly valid file, so it
passes every existing check silently, while quietly invalidating every
accuracy number already published against the old content.

`compute_personas_checksum` hashes the combined content of every scenario file
(not `scenario.schema.json` — that's a generated artifact, not part of the
approved set). `tests/test_personas_checksum.py` asserts the result against the
checksum recorded in `eval/personas/personas.sha256`, the same way `tests/test_schema_snapshot.py` pins
`scenario.schema.json` against the models it's generated from. When the set
legitimately changes, recompute and update the stored checksum in the same PR
that changes the scenarios — a diff a reviewer will see.
"""

from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

from mind.evaluation.personas import PERSONAS_DIR, SCHEMA_FILENAME

CHECKSUM_PATH = PERSONAS_DIR / "personas.sha256"


def compute_personas_checksum(personas_dir: str | Path) -> str:
    """SHA-256 over every scenario file's content under `personas_dir`.

    Recurses (same_tier/, cross_tier/, any future group), skips the generated
    `scenario.schema.json`, and hashes each file's path (relative to
    `personas_dir`, so it also catches renames/moves) together with its
    content, in a fixed sorted order so the result doesn't depend on
    filesystem iteration order.

    `read_text` already normalizes line endings to `\n`, so the checksum is the
    same on a Windows (CRLF) and a Linux (LF) checkout.
    """
    personas_path = Path(personas_dir)
    if not personas_path.is_dir():
        raise FileNotFoundError(f"Personas directory not found: {personas_path}")

    digest = hashlib.sha256()
    scenario_files = sorted(
        p for p in personas_path.rglob("*.json") if p.name != SCHEMA_FILENAME
    )
    for scenario_path in scenario_files:
        relative = scenario_path.relative_to(personas_path).as_posix()
        content = scenario_path.read_text(encoding="utf-8")
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(content.encode("utf-8"))
        digest.update(b"\0")

    return digest.hexdigest()


def render_checksum() -> str:
    """The exact text that belongs in personas.sha256 (trailing newline included)."""
    return compute_personas_checksum(PERSONAS_DIR) + "\n"


def main() -> None:
    """CLI mirroring `mind.evaluation.schema`: regenerate, or --check for CI/lint.

        python -m mind.evaluation.personas_checksum            # rewrite personas.sha256
        python -m mind.evaluation.personas_checksum --check    # exit non-zero if stale
    """
    parser = argparse.ArgumentParser(description=main.__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="Exit non-zero if the recorded checksum no longer matches eval/personas/.",
    )
    args = parser.parse_args()

    current = render_checksum()
    if args.check:
        recorded = CHECKSUM_PATH.read_text(encoding="utf-8") if CHECKSUM_PATH.is_file() else ""
        if recorded != current:
            print(
                f"{CHECKSUM_PATH} does not match the current content of {PERSONAS_DIR}.\n"
                "If this scenario-set change was reviewed and approved, regenerate it:\n"
                "  python -m mind.evaluation.personas_checksum",
                file=sys.stderr,
            )
            sys.exit(1)
        print(f"{CHECKSUM_PATH} is up to date.")
        return

    CHECKSUM_PATH.write_text(current, encoding="utf-8")
    print(f"Wrote {CHECKSUM_PATH}")


if __name__ == "__main__":
    main()
