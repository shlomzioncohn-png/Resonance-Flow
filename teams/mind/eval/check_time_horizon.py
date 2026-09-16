"""CLI wrapper for `make eval-ci`.

The actual check lives in `mind.evaluation.personas`
(`correct_answer_deadline_violates_time_horizon`) so it is typed, linted by
`make lint`, and covered by a real test
(`tests/test_personas.py::test_real_persona_set_correct_answer_deadline_inside_time_horizon`)
instead of being the one piece of Python in the repo nothing checks. This
file only exists so `make eval-ci` has a command to run.
"""

import sys
from pathlib import Path

from mind.evaluation.personas import (
    correct_answer_deadline_violates_time_horizon,
    load_personas,
)

PERSONAS_DIR = Path(__file__).parent / "personas"


def main() -> int:
    scenarios = load_personas(PERSONAS_DIR)
    violations = [
        v
        for s in scenarios
        if (v := correct_answer_deadline_violates_time_horizon(s)) is not None
    ]
    if violations:
        print(f"{len(violations)} scenario(s) violate the time_horizon rule:")
        for v in violations:
            print(" -", v)
        return 1
    print("OK: every scenario's correct-answer deadline sits inside its time_horizon.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
