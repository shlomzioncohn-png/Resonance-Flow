"""Checks the README's time_horizon rule against every scenario in eval/personas/.

The rule (see "Deadline proximity" in eval/personas/README.md): the correct
answer's own deadline must always sit inside its scenario's `time_horizon`
window. Only a *distractor* task may have a deadline that falls outside it —
that is deliberate (it is what makes the distractor read as not urgent). A
`this_week` scenario whose correct answer is due next week is a bug; widen
`time_horizon` instead.

This script makes that claim mechanically re-checkable instead of something a
reviewer has to re-derive by hand for all 30 files. Run it from teams/mind/:

    python -m eval.check_time_horizon

Exits 1 and prints every offending file if the rule is violated, 0 otherwise.
"""

from __future__ import annotations

import calendar
import json
import sys
from datetime import datetime, time, timedelta
from pathlib import Path

PERSONAS_DIR = Path(__file__).parent / "personas"
SCHEMA_FILENAME = "scenario.schema.json"


def _horizon_end(current: datetime, horizon: str) -> datetime:
    """Last instant still considered 'inside' the given time_horizon window."""
    if horizon == "today":
        return datetime.combine(current.date(), time(23, 59, 59))
    if horizon == "this_week":
        # Monday-start week; end = the Sunday of the current week.
        days_until_sunday = 6 - current.weekday()
        end_date = current.date() + timedelta(days=days_until_sunday)
        return datetime.combine(end_date, time(23, 59, 59))
    if horizon == "this_month":
        last_day = calendar.monthrange(current.year, current.month)[1]
        return datetime(current.year, current.month, last_day, 23, 59, 59)
    raise ValueError(f"unknown time_horizon: {horizon!r}")


def check_file(path: Path) -> str | None:
    """Returns a violation message for `path`, or None if it's fine."""
    data = json.loads(path.read_text(encoding="utf-8"))
    tasks = {t["task_id"]: t for t in data["tasks"]}
    correct = tasks[data["expected_top_task_id"]]
    deadline = correct.get("deadline")
    if deadline is None:
        return None

    deadline_dt = datetime.fromisoformat(deadline)
    current_dt = datetime.fromisoformat(data["context"]["current_datetime"])
    horizon = data["context"]["time_horizon"]
    end = _horizon_end(current_dt, horizon)

    if deadline_dt > end:
        return (
            f"{path}: time_horizon={horizon!r} ends {end.isoformat()}, but the "
            f"correct answer {correct['task_id']!r} has deadline {deadline} "
            f"(after the window)"
        )
    return None


def main() -> int:
    violations = []
    for path in sorted(PERSONAS_DIR.rglob("*.json")):
        if path.name == SCHEMA_FILENAME:
            continue
        message = check_file(path)
        if message:
            violations.append(message)

    if violations:
        print(f"{len(violations)} scenario(s) violate the time_horizon rule:")
        for v in violations:
            print(" -", v)
        return 1

    print("OK: every scenario's correct-answer deadline sits inside its time_horizon.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
