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
                "effort_estimate": 15,
                "tier": "operational",
                "blocked": False,
                "parent_task_id": None,
            },
            {
                "task_id": "task_B",
                "title": "Write design doc for Q3 feature",
                "description": "Draft architecture doc for a feature planned for next quarter.",
                "effort_estimate": 120,
                "tier": "tactical",
                "blocked": False,
                "parent_task_id": None,
            },
        ],
        "expected_top_task_id": "task_A",
        "expected_reason": (
            "task_A is an operational fix that is unblocking every PR right now; "
            "task_B is next-quarter design work with no deadline."
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
                "effort_estimate": 10,
                "tier": "tactical",
                "blocked": False,
                "parent_task_id": None,
            },
            {
                "task_id": "task_D",
                "title": "Long-term roadmap planning",
                "description": "Quarterly roadmap planning session prep.",
                "effort_estimate": 180,
                "tier": "strategic",
                "blocked": False,
                "parent_task_id": None,
            },
        ],
        "expected_top_task_id": "task_C",
        "expected_reason": (
            "task_C is directly blocking a teammate from merging right now; "
            "task_D is roadmap prep with no deadline and nothing waiting on it."
        ),
        "source": {"type": "synthetic"},
        "mentor_approved": False,
    },
]

PLACEHOLDER_SCENARIOS: list[PersonaEvaluationScenario] = [
    PersonaEvaluationScenario.model_validate(raw) for raw in _RAW_SCENARIOS
]
