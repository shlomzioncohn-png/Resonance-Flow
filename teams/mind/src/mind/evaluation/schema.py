"""Renders eval/personas/scenario.schema.json from the Pydantic models.

`PersonaEvaluationScenario` (interfaces.py) is the source of truth for the
persona scenario schema. This module projects it to a standalone JSON Schema
file so `make eval`, other teams and the mentor have a language-agnostic
artifact — kept honest by tests/test_schema_snapshot.py.

    python -m mind.evaluation.schema           # rewrite the committed file
    python -m mind.evaluation.schema --check    # exit non-zero if it is stale

Note: the exact byte output can shift between Pydantic versions. When it does,
re-run without --check and commit the result — the models stay the source of
truth, the file just follows.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from mind.evaluation.interfaces import PersonaEvaluationScenario

SCHEMA_PATH = (
    Path(__file__).resolve().parents[3] / "eval" / "personas" / "scenario.schema.json"
)

_SCHEMA_DIALECT = "https://json-schema.org/draft/2020-12/schema"
_SCHEMA_ID = "https://resonance-flow.internal/mind/eval/personas/scenario.schema.json"


def scenario_json_schema() -> dict:
    """The PersonaEvaluationScenario JSON Schema, with a stable $schema/$id header."""
    schema = PersonaEvaluationScenario.model_json_schema()
    return {"$schema": _SCHEMA_DIALECT, "$id": _SCHEMA_ID, **schema}


def render() -> str:
    """The exact text that belongs in scenario.schema.json (trailing newline included)."""
    return json.dumps(scenario_json_schema(), indent=2, ensure_ascii=False) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="Exit non-zero if the committed schema is out of sync with the models.",
    )
    args = parser.parse_args()

    rendered = render()
    if args.check:
        current = SCHEMA_PATH.read_text(encoding="utf-8") if SCHEMA_PATH.is_file() else ""
        if current != rendered:
            print(
                f"{SCHEMA_PATH} is out of sync with the Pydantic models.\n"
                "Regenerate it:  python -m mind.evaluation.schema",
                file=sys.stderr,
            )
            sys.exit(1)
        print(f"{SCHEMA_PATH} is up to date.")
        return

    SCHEMA_PATH.write_text(rendered, encoding="utf-8")
    print(f"Wrote {SCHEMA_PATH}")


if __name__ == "__main__":
    main()
