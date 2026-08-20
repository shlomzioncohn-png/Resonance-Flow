MOCK_SCENARIOS = [
    {
        "id": "scenario_1",
        "role": "developer",
        "free_time_minutes": 30,
        "tasks": [
            {
                "id": "t1",  # עודכן מ-task_id
                "org_id": "org_123",
                "title": "Fix login bug",
                "status": "OPEN", 
                "source_type": "github_issue",
                "estimated_duration_minutes": 25,
                "confidence_score": None,
                "priority_score": None,  # לכאן ייכנס הציון של המודל שלך!
                "expected_top": True     # נשאר השדה הפנימי שלך לבדיקה
            },
            {
                "id": "t2",
                "org_id": "org_123",
                "title": "Update README",
                "status": "OPEN",
                "source_type": "slack",
                "estimated_duration_minutes": 15,
                "confidence_score": None,
                "priority_score": None,
                "expected_top": False
            }
        ]
    }
]