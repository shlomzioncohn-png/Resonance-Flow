# personas

> **The scenario `*.json` files land in their own PR** (the persona evaluation
> set). This branch/PR is the harness engine only — `eval/personas/` here holds
> just this doc and the generated `scenario.schema.json`. CI smoke-tests the
> harness against `tests/fixtures/personas/` instead.

The fixed evaluation scenario set: 4-6 scenarios per role (Developer,
Manager, Higher Manager, Field Engineer, Salesperson, CEO), ~24-36 total.
Each scenario states a free-time window and a mentor-agreed "correct" top
task. This set must be complete and mentor-approved (see `mentor_approved`
below) by the Foundation Gate (end of week 3) and must not change once
baseline/stretch comparisons start.

**Binding source of truth:** the Pydantic model `PersonaEvaluationScenario` in
[`src/mind/evaluation/interfaces.py`](../../src/mind/evaluation/interfaces.py).
[`scenario.schema.json`](./scenario.schema.json) (JSON Schema, draft 2020-12) is
**generated** from that model (`python -m mind.evaluation.schema`, or `make schema`)
so external readers still have a language-agnostic artifact; `tests/test_schema_snapshot.py`
fails if the committed file drifts from the model, and `make lint` runs the same check.
Edit the model, regenerate the file — never hand-edit the JSON.
This document explains *why* each field exists; the model is what's actually enforced in code (`make eval`).

## Core principle

**One schema, shared across all six personas** (Developer, Manager, Higher Manager, Field Engineer,
Salesperson, CEO). What changes between personas is the **values**, not the structure.
Never build a role-specific variant of this schema — every scenario, from every role,
is one and the same `PersonaEvaluationScenario` object.

## Persona ≠ Tier

This is the most important separation in the schema: **who the person is** (`persona.role`) is not
**what kind of task it is** (`task.tier`). Example: a Developer can receive both an `operational` and a
`tactical` task. A CEO will usually receive `strategic`, but the schema does not enforce that as a hard
rule — it stays scenario-dependent, so scenarios can also test edge cases (e.g. a CEO facing an urgent
operational task).

## Overall structure

```
Scenario
├── scenario_id
├── persona        { role, seniority?, department? }
├── context         { free_time_minutes, current_datetime, time_horizon, constraints[] }
├── tasks[]          { task_id, title, description, deadline?, effort_estimate,
                        tier, blocked, parent_task_id? }
├── expected_top_task_id
├── expected_reason
├── source          { type, source_detail? }
└── mentor_approved
```

## What the ranking model actually sees

The contract is `RankingModel.score(task: Task) -> float` — the model is handed
**one `Task` at a time**, nothing else. It never sees `persona`, `context`
(so not `free_time_minutes`, not `current_datetime`, not `constraints`), and not
the other candidate tasks. The harness builds the ranking from the per-task
scores; picking what actually fits the free-time window is Chain's job, not the
model's ("MIND scores, it does not decide").

Practical consequence for scenario authors: a scenario's `expected_top_task_id`
must be justifiable from **per-task fields alone** — `deadline`, `blocked`,
`tier`, and the importance/scope you can read out of `title`/`description`. If the
only thing that makes the "correct" task correct is that it fits in the available
minutes and the distractor doesn't, the scenario is grading the model on
information it structurally cannot use — redesign it. (`free_time_minutes` and
`effort_estimate` still belong in every scenario; Chain consumes them downstream.)

## Per-field documentation

### Persona

| Field | Represents | Why it's needed | Allowed values | Required/Optional | Who uses it downstream | Example |
|---|---|---|---|---|---|---|
| `role` | Who the user is, for the purpose of ranking their tasks | Determines the user's "shape of life" — pace, time horizon, kind of relevant tasks (see role table in MIND-01) | `Developer` / `Manager` / `Higher Manager` / `Field Engineer` / `Salesperson` / `CEO` | Required | The harness (ensures every role is covered in the set); R2/R3 when building role-specific features | `"Developer"` |
| `seniority` | Seniority level within the role | Sometimes changes the correct answer (a Junior wouldn't be expected to pick up an architecture-review task a Senior would); sometimes doesn't — hence optional | `Junior` / `Mid` / `Senior` / `Executive` / `null` | Optional | The mentor / R1 when judging the labelled answer (not visible to `score(task)`) | `"Junior"` |
| `department` | Organizational department | Distinguishes, e.g., a Field Engineer in Operations from a Sales Engineer | `Engineering` / `Sales` / `Operations` / `null` | Optional (some roles, like CEO, aren't scoped to a single department) | Lens (future department-level aggregation) | `"Engineering"` |

### Context

| Field | Represents | Why it's needed | Allowed values | Required/Optional | Who uses it downstream | Example |
|---|---|---|---|---|---|---|
| `free_time_minutes` | How much free time the persona has right now | Context for the scenario and a direct input to Chain's constraint-satisfaction step. **Not** visible to the ranking model (it lives on `context`, not `Task`), so the scenario's correct answer must not depend on it — see "What the ranking model actually sees" above | Positive integer | Required | Chain (actual selection); scenario authors as context | `30` |
| `current_datetime` | "Now" for this scenario | Without it there's no way to judge whether a `deadline` is near, far, or already passed | ISO 8601 datetime | Required | Urgency calculation against deadline | `"2026-08-23T14:00:00"` |
| `time_horizon` | Planning window (today / this week / this month) | Matches each role's different pace of work (Developer = hours-days, CEO = quarters); frames what "urgent" means for the labelled answer | `today` / `this_week` / `this_month` | Required | The mentor / R1 when judging the labelled answer (not visible to `score(task)`) | `"today"` |
| `constraints` | Additional constraints the system must know about | Captures cases not covered by deadline/blocked alone (e.g. "no meetings can be scheduled") | Free-text string array, empty by default | Required (the array itself, even if empty) | Chain (feasibility); the mentor / R1 when judging the labelled answer (not visible to `score(task)`) | `["no meetings can be scheduled"]` |

### Task

| Field | Represents | Why it's needed | Allowed values | Required/Optional | Who uses it downstream | Example |
|---|---|---|---|---|---|---|
| `task_id` | Unique identifier within the scenario | Needed so `expected_top_task_id` can reference it | Unique string within the scenario | Required | expected_top_task_id | `"task_B"` |
| `title` | Short title | Human readability + occasionally used as a textual feature | String | Required | Lens (display), extraction pipeline (later) | `"Review PR #482"` |
| `description` | Task detail | Gives the model (and a human reviewer) enough context to judge duration/urgency/relevance without outside knowledge | String | Required | The ranking model | `"Code review for the auth refactor PR"` |
| `deadline` | Due date/time, if any | Establishes urgency relative to `current_datetime` | ISO 8601 datetime or `null` | Optional (nullable) | Urgency calculation | `"2026-08-23T17:00:00"` |
| `effort_estimate` | How long the task takes, **in minutes** | The eval set's concrete, minutes-valued form of the canonical `Task.effort_estimate` (owned by Inbox). Recorded so Chain can do constraint satisfaction downstream | Positive integer (minutes) | Required | Chain (constraint satisfaction); the ranking model may use it as a weak per-task feature, but the correct answer must not hinge on comparing it to `free_time_minutes` | `20` |
| `tier` | Kind of task (not the role!) | Separates "who the person is" from "what kind of work this is" — see Persona ≠ Tier above | `operational` / `tactical` / `strategic` (lowercase — matches the canonical Task schema and `src/mind/scoring/`) | Required | R2 (segmented ranking — a separate scoring rule per tier) | `"operational"` |
| `blocked` | Whether the task can currently be worked on | A blocked task should generally not be the expected correct answer | Boolean | Required | The ranking model; Chain (actual task selection) | `false` |
| `parent_task_id` | The `task_id` of this task's parent in the **cross-tier** org hierarchy (a dev task under a manager task), matching the canonical `Task.parent_task_id` | Reserved for the week-7 hierarchical value-propagation evaluation, where importance flows down the parent chain via Chain's traversal API. Added now so scenarios don't need a schema change mid-scoring. **Not** the old same-tier `dependencies` array — that was removed deliberately | String or `null`. Points *outside* the scenario, so it is not cross-referenced | Optional (nullable), `null` in every current scenario | R1/R3 (week-7 stretch) | `null` |

### Scenario-level fields

| Field | Represents | Why it's needed | Allowed values | Required/Optional | Who uses it downstream | Example |
|---|---|---|---|---|---|---|
| `expected_top_task_id` | The known-correct answer | Enables comparing Model prediction vs. Expected answer and computing a metric — the entire point of the harness | Must match one `task_id` from `tasks` (enforced by a `@model_validator` on `PersonaEvaluationScenario`, so a bad file fails to load — not expressible in the JSON Schema itself) | Required | The harness (top-1 accuracy / MRR calculation) | `"task_B"` |
| `expected_reason` | Short, data-grounded explanation for the correct answer | The difference between a "guess" and a checkable judgment; also used later to compare baseline vs. stretch (weeks 6–7) | String, at least 20 characters, grounded in **per-task** fields (`deadline`, `blocked`, `tier`, description) — never in `free_time_minutes` or a duration comparison, which are Chain's concern | Required | The mentor (plausibility check); R1 (evidence for baseline vs. stretch) | `"Task B has a same-day deadline and Task A is blocked, so Task B is the one to do now."` |
| `source.type` | The scenario's origin | Transparency: lets us know later whether a model was tested on a fabricated example or on real data | `synthetic` / `real` / `AMI` / `FUNSD` / `other` | Required | R1 (performance analysis by source type); data-policy documentation | `"synthetic"` |
| `source.source_detail` | The exact source details | Enables tracing back to the actual origin (e.g. a specific AMI meeting ID) | Free-text string or `null` | Optional (required in practice when `type` is AMI/FUNSD/other) | R1 (traceability) | `"AMI meeting ES2002a"` |
| `mentor_approved` | Whether the mentor has signed off on this specific scenario as correct ground truth | Without a per-scenario flag there is no way to distinguish "drafted" from "mentor-approved" — the set must be fully approved by the Foundation Gate, but gets built up scenario-by-scenario before that | `true` / `false` | Required (must be set explicitly, no implicit default) | The harness/CI (warns, doesn't crash, on any `false`); the mentor review process | `false` |

## Constraints not expressible as plain structure

**Intra-scenario invariants** — enforced by `@model_validator` on
`PersonaEvaluationScenario` (`src/mind/evaluation/interfaces.py`), so a violating
file **fails to load** (`load_personas` raises `PersonaSchemaError`):
- `expected_top_task_id` must equal the `task_id` of one of the entries in `tasks`.
- When `source.type` is `AMI`, `FUNSD`, or `other`, `source_detail` must be filled in.

**Cross-file hard error** — enforced in `validate_personas` (`personas.py`), fails `make eval` / CI:
- No duplicate scenarios (`scenario_id` unique across all of `eval/personas/`).

**Warnings** — also in `validate_personas`; printed, don't fail the build (the set
is built up incrementally by multiple people before the Foundation Gate, so a
mid-week PR shouldn't go red just because the set isn't complete yet). Pydantic
can only raise, not warn, which is why these stay in Python rather than in the model:
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
   genuinely different kind of choice (a hard deadline vs. no deadline, a
   `blocked` distractor, a `constraints` entry that rules an option out, a
   seniority-scoped judgment call, a tier/scope mismatch...) — not six
   variations on the same idea.
2. **Name your `scenario_id`s `<role>_<yourname>_<short_slug>`** (e.g.
   `ceo_dana_quarterly_board_prep`) — the extra name segment exists purely to
   avoid two people accidentally picking the same `scenario_id` when nobody
   coordinated in advance. `scenario_id` must still match `^[a-z0-9_]+$`.
3. One scenario per file, filename `<scenario_id>.json`, dropped directly into
   this directory. Follow `scenario.schema.json` (generated from the
   `PersonaEvaluationScenario` model) — see the field tables above for what each
   field means and why.
4. Set `mentor_approved: false` — that flag only ever gets flipped to `true` by
   R1 after an actual mentor review, never by the scenario's author.
5. Before pushing, validate locally from `teams/mind/`:
   ```bash
   python -c "from mind.evaluation.personas import load_personas, validate_personas as v; s = load_personas('eval/personas'); r = v(s); print('errors:', r.errors); print('warnings:', r.warnings)"
   ```
   If your file is structurally wrong or breaks an intra-scenario invariant
   (dangling `expected_top_task_id`, missing `source_detail`), `load_personas`
   raises `PersonaSchemaError` naming the file and field. Otherwise `r.errors`
   must be empty. Warnings about other roles/scenarios not being ready yet are
   expected and fine — that's everyone else's in-progress work, not yours.

## Scenario-writing conventions

None of these are enforced by the schema or by `validate_personas` — they're
judgment calls that four different people can easily make inconsistently.
Follow them so the mentor review doesn't come back with "these two scenarios
contradict each other."

**Deadline proximity ("urgent" vs. not):**
- A deadline within the same working day as `current_datetime`, or a few hours
  out, reads as genuinely urgent — a real pressure signal you can lean on in
  `expected_reason`.
- A deadline further out than the scenario's `time_horizon` (e.g. a `this_week`
  scenario with a deadline next month) is not urgency — don't write
  `expected_reason` as if it were the deciding factor.
- `deadline: null` = no urgency signal at all. Don't imply urgency for it.

**Duration and free time are Chain's concern, not the ranking model's:**
- The model being evaluated only ever sees one `Task` at a time (`score(task)`);
  it never sees `free_time_minutes` or the other candidates (see "What the
  ranking model actually sees" above). A scenario whose correct answer depends
  on "task A fits the window and task B doesn't" grades the model on information
  it structurally cannot use.
- `effort_estimate` and `free_time_minutes` still belong in every scenario —
  Chain needs them downstream — but the `expected_reason` must rest on per-task
  signals: a hard deadline, `blocked`, `tier`, or an importance/scope judgment
  from the description ("production-impacting", "outside a Junior's decision
  scope", "unblocks a teammate").
- Never phrase `expected_reason` as "fits the N-minute window" or "needs Nx the
  available time". If duration is the *only* thing separating the correct task
  from a distractor, the scenario is testing the wrong layer — redesign it so a
  per-task signal decides it.

**`blocked` as a distractor:**
- Only mark a task `blocked` when its `description` states a concrete
  external blocker (waiting on a specific person/team/approval). A reader
  should be able to point at the sentence that causes it — never "just because."

**`tier` assignment** (independent of role — see "Persona ≠ Tier" above):
- `operational` — routine, bounded, no scope beyond the task itself (a
  bugfix, a status update).
- `tactical` — some coordination/scope beyond one person, days-to-weeks
  horizon (a sprint plan, a review that unblocks someone else).
- `strategic` — organization-level scope or multi-month horizon (roadmap
  planning, architecture sign-off, a budget review).
- Don't default every Developer task to `operational` or every Manager task
  to `strategic` — assign by what the task actually is, per scenario.

**`seniority`, when set:**
- Only set it when it's the actual deciding factor named in `expected_reason`
  (see `developer_junior_architecture_review_vs_bugfix.json` for the pattern).
  If seniority wouldn't change the answer, leave it `null` — don't set it
  decoratively.

**One dimension per scenario, no repeats within your role:**
- Each of your 4-6 scenarios should test a genuinely different tradeoff
  (hard deadline vs. none, a `blocked` distractor, a `constraints` entry that
  rules an option out, a seniority-scoped call, a tier-vs-role mismatch...).
  If two of your scenarios are the same test with different flavor text, cut one.

**Pattern-match against the existing 8 before inventing a new style** —
`developer_junior_urgent_bugfix_vs_new_feature.json`,
`developer_junior_architecture_review_vs_bugfix.json`,
`developer_senior_blocked_oauth_vs_pr_review.json`, and
`manager_senior_async_update_vs_meeting_constraint.json` each demonstrate one
of the patterns above end-to-end.

## Mentor review status

*(Filled in by R1 once an actual review happens — not a placeholder for anyone
to hand-wave. "Partially approved with a documented plan to close" is the
honest state to report if 100% isn't reached by the Foundation Gate.)*

- **Review date:** _TBD_
- **Reviewed by:** _TBD_
- **Approved:** _TBD_ / _TBD_ scenarios (`mentor_approved: true`)
- **Notes / pending changes:** _TBD_
