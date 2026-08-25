# Small fixed toy set used to unit-test harness.run() in isolation from the real,
# evolving eval/personas/ set. `mind.evaluation.run` (the CLI/CI entry point) uses
# the real set via load_personas() instead — see tests/test_harness.py for usage here.
PLACEHOLDER_SCENARIOS: list[dict] = [
    {
        "scenario_id": "developer_junior_placeholder_1",
        "expected_top_task_id": "task_A",
        "tasks": [
            {
                "task_id": "task_A",
                "title": "Fix failing CI test",
                "description": "A unit test regressed on main and is blocking every PR.",
                "estimated_duration_minutes": 15,
                "tier": "Operational",
                "blocked": False,
            },
            {
                "task_id": "task_B",
                "title": "Write design doc for Q3 feature",
                "description": "Draft architecture doc for a feature planned for next quarter.",
                "estimated_duration_minutes": 120,
                "tier": "Tactical",
                "blocked": False,
            },
        ],
    },
    {
        "scenario_id": "manager_placeholder_1",
        "expected_top_task_id": "task_C",
        "tasks": [
            {
                "task_id": "task_C",
                "title": "Unblock teammate waiting on approval",
                "description": "A PR is stuck waiting for the manager's review, blocking a teammate.",
                "estimated_duration_minutes": 10,
                "tier": "Tactical",
                "blocked": False,
            },
            {
                "task_id": "task_D",
                "title": "Long-term roadmap planning",
                "description": "Quarterly roadmap planning session prep.",
                "estimated_duration_minutes": 180,
                "tier": "Strategic",
                "blocked": False,
            },
        ],
    },
]
