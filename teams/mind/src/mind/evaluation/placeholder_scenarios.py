"""Small fixed toy set used to unit-test harness.run() in isolation from the real,
evolving eval/personas/ set. `mind.evaluation.run` (the CLI/CI entry point) uses
the real set via load_personas() instead — see tests/test_harness.py for usage here.

Written as raw dicts and parsed through the model (exactly what load_personas does
to the real files) so this fixture exercises the same validation path.
"""

from mind.evaluation.interfaces import PersonaEvaluationScenario

_RAW_SCENARIOS: list[dict] = [
    {
        "scenario_id": "developer_junior_placeholder_1",
        "persona": {"role": "Developer", "seniority": "Junior"},
        "context": {
            "free_time_minutes": 30,
            "current_datetime": "2026-08-23T14:00:00",
            "time_horizon": "today",
        },
        "tasks": [
            {
                "task_id": "task_A",
                "title": "Fix failing CI test",
                "description": "A unit test regressed on main and is blocking every PR.",
                "estimated_duration_minutes": 15,
                "tier": "operational",
                "blocked": False,
            },
            {
                "task_id": "task_B",
                "title": "Write design doc for Q3 feature",
                "description": "Draft architecture doc for a feature planned for next quarter.",
                "estimated_duration_minutes": 120,
                "tier": "tactical",
                "blocked": False,
            },
        ],
        "expected_top_task_id": "task_A",
        "expected_reason": (
            "task_A is a 15-minute operational fix that unblocks every PR and fits "
            "the 30-minute window; task_B needs 120 minutes and is not urgent."
        ),
        "source": {"type": "synthetic"},
        "mentor_approved": False,
    },
    {
        "scenario_id": "manager_placeholder_1",
        "persona": {"role": "Manager"},
        "context": {
            "free_time_minutes": 20,
            "current_datetime": "2026-08-23T14:00:00",
            "time_horizon": "this_week",
        },
        "tasks": [
            {
                "task_id": "task_C",
                "title": "Unblock teammate waiting on approval",
                "description": "A PR is stuck waiting for the manager's review, blocking a teammate.",
                "estimated_duration_minutes": 10,
                "tier": "tactical",
                "blocked": False,
            },
            {
                "task_id": "task_D",
                "title": "Long-term roadmap planning",
                "description": "Quarterly roadmap planning session prep.",
                "estimated_duration_minutes": 180,
                "tier": "strategic",
                "blocked": False,
            },
        ],
        "expected_top_task_id": "task_C",
        "expected_reason": (
            "task_C is a 10-minute review that fits the 20-minute window and unblocks "
            "a teammate; task_D needs 180 minutes and has no near-term deadline."
        ),
        "source": {"type": "synthetic"},
        "mentor_approved": False,
    },
]

PLACEHOLDER_SCENARIOS: list[PersonaEvaluationScenario] = [
    PersonaEvaluationScenario.model_validate(raw) for raw in _RAW_SCENARIOS
]
