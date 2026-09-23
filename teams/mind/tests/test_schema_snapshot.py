"""eval/personas/scenario.schema.json is generated from the Pydantic models
(mind.evaluation.interfaces) — it must never be hand-edited or left stale.

If this fails after a deliberate model change, regenerate and commit:

    python -m mind.evaluation.schema
"""

from mind.evaluation.schema import SCHEMA_PATH, render


def test_committed_schema_matches_the_models():
    assert SCHEMA_PATH.read_text(encoding="utf-8") == render(), (
        "scenario.schema.json is out of sync with the models — run "
        "`python -m mind.evaluation.schema`"
    )
