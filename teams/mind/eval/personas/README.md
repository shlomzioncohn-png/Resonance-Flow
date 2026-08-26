# personas

The fixed evaluation scenario set: 4-6 scenarios per role (Developer,
Manager, Higher Manager, Field Engineer, Salesperson, CEO), ~24-36 total.
Each scenario states a free-time window and a mentor-agreed "correct" top
task. This set must be complete and mentor-approved (see `mentor_approved`
below) by the Foundation Gate (end of week 3) and must not change once
baseline/stretch comparisons start.

**Binding source of truth:** [`scenario.schema.json`](./scenario.schema.json) (JSON Schema, draft 2020-12).
This document explains *why* each field exists; the JSON Schema is what's actually enforced in code (`make eval`).

## Core principle

**One schema, shared across all six personas** (Developer, Manager, Higher Manager, Field Engineer,
Salesperson, CEO). What changes between personas is the **values**, not the structure.
Never build a role-specific variant of this schema — every scenario, from every role,
is one and the same `PersonaEvaluationScenario` object.

## Persona ≠ Tier

This is the most important separation in the schema: **who the person is** (`persona.role`) is not
**what kind of task it is** (`task.tier`). Example: a Developer can receive both an `Operational` and a
`Tactical` task. A CEO will usually receive `Strategic`, but the schema does not enforce that as a hard
rule — it stays scenario-dependent, so scenarios can also test edge cases (e.g. a CEO facing an urgent
operational task).

## Overall structure

```
Scenario
├── scenario_id
├── persona        { role, seniority?, department? }
├── context         { free_time_minutes, current_datetime, time_horizon, constraints[] }
├── tasks[]          { task_id, title, description, deadline?, estimated_duration_minutes,
                        tier, blocked }
├── expected_top_task_id
├── expected_reason
├── source          { type, source_detail? }
└── mentor_approved
```

## Per-field documentation

### Persona

| Field | Represents | Why it's needed | Allowed values | Required/Optional | Who uses it downstream | Example |
|---|---|---|---|---|---|---|
| `role` | Who the user is, for the purpose of ranking their tasks | Determines the user's "shape of life" — pace, time horizon, kind of relevant tasks (see role table in MIND-01) | `Developer` / `Manager` / `Higher Manager` / `Field Engineer` / `Salesperson` / `CEO` | Required | The harness (ensures every role is covered in the set); R2/R3 when building role-specific features | `"Developer"` |
| `seniority` | Seniority level within the role | Sometimes changes the correct answer (a Junior wouldn't be expected to pick up an architecture-review task a Senior would); sometimes doesn't — hence optional | `Junior` / `Mid` / `Senior` / `Executive` / `null` | Optional | The ranking model, only where a real signal depends on it | `"Junior"` |
| `department` | Organizational department | Distinguishes, e.g., a Field Engineer in Operations from a Sales Engineer | `Engineering` / `Sales` / `Operations` / `null` | Optional (some roles, like CEO, aren't scoped to a single department) | Lens (future department-level aggregation) | `"Engineering"` |

### Context

| Field | Represents | Why it's needed | Allowed values | Required/Optional | Who uses it downstream | Example |
|---|---|---|---|---|---|---|
| `free_time_minutes` | How much free time the persona has right now | The single most load-bearing field in the scenario — the same task list can have a completely different correct answer at 20 minutes vs. two hours | Positive integer | Required | Running the harness; later, Chain (actual selection) | `30` |
| `current_datetime` | "Now" for this scenario | Without it there's no way to judge whether a `deadline` is near, far, or already passed | ISO 8601 datetime | Required | Urgency calculation against deadline | `"2026-08-23T14:00:00"` |
| `time_horizon` | Planning window (today / this week / this month) | Matches each role's different pace of work (Developer = hours-days, CEO = quarters) | `today` / `this_week` / `this_month` | Required | The ranking model — the horizon affects which tasks are even relevant | `"today"` |
| `constraints` | Additional constraints the system must know about | Captures cases not covered by free_time/deadline alone (e.g. "no meetings can be scheduled") | Free-text string array, empty by default | Required (the array itself, even if empty) | The ranking model and explanation generation | `["no meetings can be scheduled"]` |

### Task

| Field | Represents | Why it's needed | Allowed values | Required/Optional | Who uses it downstream | Example |
|---|---|---|---|---|---|---|
| `task_id` | Unique identifier within the scenario | Needed so `expected_top_task_id` can reference it | Unique string within the scenario | Required | expected_top_task_id | `"task_B"` |
| `title` | Short title | Human readability + occasionally used as a textual feature | String | Required | Lens (display), extraction pipeline (later) | `"Review PR #482"` |
| `description` | Task detail | Gives the model (and a human reviewer) enough context to judge duration/urgency/relevance without outside knowledge | String | Required | The ranking model | `"Code review for the auth refactor PR"` |
| `deadline` | Due date/time, if any | Establishes urgency relative to `current_datetime` | ISO 8601 datetime or `null` | Optional (nullable) | Urgency calculation | `"2026-08-23T17:00:00"` |
| `estimated_duration_minutes` | How long the task takes | Compared directly against `free_time_minutes` — the most basic "is this even possible right now" check | Positive integer | Required | The ranking model; Chain (constraint satisfaction) | `20` |
| `tier` | Kind of task (not the role!) | Separates "who the person is" from "what kind of work this is" — see Persona ≠ Tier above | `Operational` / `Tactical` / `Strategic` | Required | R2 (segmented ranking — a separate scoring rule per tier) | `"Operational"` |
| `blocked` | Whether the task can currently be worked on | A blocked task should generally not be the expected correct answer | Boolean | Required | The ranking model; Chain (actual task selection) | `false` |

### Scenario-level fields

| Field | Represents | Why it's needed | Allowed values | Required/Optional | Who uses it downstream | Example |
|---|---|---|---|---|---|---|
| `expected_top_task_id` | The known-correct answer | Enables comparing Model prediction vs. Expected answer and computing a metric — the entire point of the harness | Must match one `task_id` from `tasks` (enforced in validation code, not by the JSON Schema itself) | Required | The harness (top-1 accuracy / MRR calculation) | `"task_B"` |
| `expected_reason` | Short, data-grounded explanation for the correct answer | The difference between a "guess" and a checkable judgment; also used later to compare baseline vs. stretch (weeks 6–7) | String, at least 20 characters, must be grounded in fields from the scenario itself | Required | The mentor (plausibility check); R1 (evidence for baseline vs. stretch) | `"Task B fits the available 30-minute window, has a near deadline, and Task A is blocked."` |
| `source.type` | The scenario's origin | Transparency: lets us know later whether a model was tested on a fabricated example or on real data | `synthetic` / `real` / `AMI` / `FUNSD` / `other` | Required | R1 (performance analysis by source type); data-policy documentation | `"synthetic"` |
| `source.source_detail` | The exact source details | Enables tracing back to the actual origin (e.g. a specific AMI meeting ID) | Free-text string or `null` | Optional (required in practice when `type` is AMI/FUNSD/other) | R1 (traceability) | `"AMI meeting ES2002a"` |
| `mentor_approved` | Whether the mentor has signed off on this specific scenario as correct ground truth | Without a per-scenario flag there is no way to distinguish "drafted" from "mentor-approved" — the set must be fully approved by the Foundation Gate, but gets built up scenario-by-scenario before that | `true` / `false` | Required (must be set explicitly, no implicit default) | The harness/CI (warns, doesn't crash, on any `false`); the mentor review process | `false` |

## Constraints not enforced by the JSON Schema itself (enforced in `validate_personas`, `personas.py`)

**Hard errors** (fail `make eval` / CI):
- `expected_top_task_id` must equal the `task_id` of one of the entries in `tasks`.
- No duplicate scenarios (`scenario_id` unique across all of `eval/personas/`).
- When `source.type` is `AMI`, `FUNSD`, or `other`, `source_detail` must be filled in.

**Warnings** (printed, don't fail the build — the set is built up incrementally by
multiple people before the Foundation Gate, so a mid-week PR shouldn't go red just
because the set isn't complete yet):
- A role has no scenario at all.
- A role has scenarios, but not 4-6 of them.
- The full set doesn't total 24-36 scenarios.
- A scenario has `mentor_approved: false`.

## Status

The schema was sanity-checked against a first batch of 8 scenarios (4 Developer, 4
Manager, written by R1). The remaining four roles (Higher Manager, Field Engineer,
Salesperson, CEO) are being written by the rest of the team now, to reach the full
24-36 across all six roles required by the Foundation Gate.

## Contributing a scenario

1. Pick one of your assigned roles. Write 4-6 scenarios for it, each testing a
   genuinely different kind of choice (a deadline vs. duration tradeoff, a
   `blocked` distractor, a `constraints` entry that rules an option out, a
   seniority-scoped judgment call...) — not six variations on the same idea.
2. **Name your `scenario_id`s `<role>_<yourname>_<short_slug>`** (e.g.
   `ceo_dana_quarterly_board_prep`) — the extra name segment exists purely to
   avoid two people accidentally picking the same `scenario_id` when nobody
   coordinated in advance. `scenario_id` must still match `^[a-z0-9_]+$`.
3. One scenario per file, filename `<scenario_id>.json`, dropped directly into
   this directory. Follow `scenario.schema.json` — see the field tables above
   for what each field means and why.
4. Set `mentor_approved: false` — that flag only ever gets flipped to `true` by
   R1 after an actual mentor review, never by the scenario's author.
5. Before pushing, validate locally from `teams/mind/`:
   ```bash
   python -c "from mind.evaluation.personas import load_personas, validate_personas as v; s = load_personas('eval/personas'); r = v(s); print('errors:', r.errors); print('warnings:', r.warnings)"
   ```
   `errors` must be empty. Warnings about other roles/scenarios not being ready
   yet are expected and fine — that's everyone else's in-progress work, not yours.

## Mentor review status

*(Filled in by R1 once an actual review happens — not a placeholder for anyone
to hand-wave. "Partially approved with a documented plan to close" is the
honest state to report if 100% isn't reached by the Foundation Gate.)*

- **Review date:** _TBD_
- **Reviewed by:** _TBD_
- **Approved:** _TBD_ / _TBD_ scenarios (`mentor_approved: true`)
- **Notes / pending changes:** _TBD_
