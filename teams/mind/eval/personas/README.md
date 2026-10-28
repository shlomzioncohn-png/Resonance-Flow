# personas

The fixed evaluation scenario set: 4-6 scenarios per role (Developer,
Manager, Higher Manager, Field Engineer, Salesperson, CEO), ~24-36 total, plus one
`hierarchy_decides/` scenario per role (#507) — so up to 7 per role and 42 in all.
Each scenario states a free-time window and an agreed "correct" top
task. This set must be complete and team-lead-approved (see `team_lead_approved`
below) by the Foundation Gate (end of week 3) and must not change once
baseline/stretch comparisons start.

`personas.sha256` pins the set against silent edits
(`python -m mind.evaluation.personas_checksum --check`, run by `make lint`).
Despite its name and location it covers **two** directories: the scenario files
here *and* `../hierarchy/`, the parent nodes the scenarios' `parent_task_id`
values point at — a changed node changes the set as much as a changed scenario.

## Three groups: `same_tier/`, `cross_tier/` and `hierarchy_decides/`

Per the mentor's review, the set is split into subdirectories that test
different things and are scored separately (`make eval` prints a number for each,
one for the original set, and a combined one):

| Directory | Every candidate task is… | What it checks |
|---|---|---|
| `same_tier/` | the **same** `tier` (e.g. three `operational` tasks) | the ranking model **in isolation** — given tasks of one kind, does it order them right? |
| `cross_tier/` | a **mix** of tiers (e.g. `strategic` vs `operational`) | the **end-to-end** outcome — did the system land on the right task for the user? (the raw calibrated score matters less here) |
| `hierarchy_decides/` | near-equal on their own features; what separates them is the initiative each one serves (#507) | whether the **hierarchy** changes the answer — the week-7 stretch. See **Hierarchy** in `src/mind/evaluation/README.md` |

For the first two the rule is mechanical: all task `tier` values equal →
`same_tier/`, otherwise `cross_tier/`. Those two together are the **original
set** (36): `make eval` prints its number as `[original set: all but
hierarchy_decides]`, next to `[hierarchy_decides]` and `[combined]` (42). The
per-role and total counts (4-7 per role, 24-42 total) apply to the combined set.
`load_personas("eval/personas")` recurses into every group; pass a single group
dir to load just that group.

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
└── team_lead_approved
```

## What the ranking model actually sees

The model scores **one task at a time**, plus a small context that carries only
the current time (`RankingModel.score(task, context)` where `context` holds
`current_datetime` — the mentor's decision; the contract change itself is landing
on the harness branch). That is enough to compute how near a `deadline` is. It
never sees `persona` (so not `seniority`), the rest of `context` (not
`free_time_minutes`, not `constraints`), or the other candidate tasks. The harness builds the ranking from the per-task scores; picking what
actually fits the free-time window is Chain's job, not the model's ("MIND scores,
it does not decide").

Practical consequence for scenario authors: a scenario's `expected_top_task_id`
must be justifiable from **what the model actually sees** — `deadline` (now
judgeable against `current_datetime`), `blocked`, `tier`, and the importance/scope
you can read out of `title`/`description`. It must **not** hinge on:

- **`free_time_minutes` / a duration comparison** — "task A fits the window, B
  doesn't" is Chain's call, not the model's.
- **`persona.seniority`** — "a junior wouldn't take this on" is a scheduling/user
  concern handled elsewhere; the model ranks the task, not the person.
- **`context.constraints`** — "he can't take meetings today", "he's offline" — same:
  the model never sees these. If a constraint is the *only* thing separating the
  right task from a distractor, redesign so a per-task signal (a deadline, a
  blocker, stated impact) decides it.

(`free_time_minutes`, `effort_estimate` and `constraints` still belong in every
scenario — Chain consumes them downstream — they just can't be the reason.)

## Per-field documentation

### Persona

| Field | Represents | Why it's needed | Allowed values | Required/Optional | Who uses it downstream | Example |
|---|---|---|---|---|---|---|
| `role` | Who the user is, for the purpose of ranking their tasks | Determines the user's "shape of life" — pace, time horizon, kind of relevant tasks (see role table in MIND-01) | `Developer` / `Manager` / `Higher Manager` / `Field Engineer` / `Salesperson` / `CEO` | Required | The harness (ensures every role is covered in the set); R2/R3 when building role-specific features | `"Developer"` |
| `seniority` | Seniority level within the role | Optional field, kept in the schema for future consumers (e.g. Lens) but **deliberately left unset in every scenario in this set**: the model ranks the task not the person, and the mentor ruled that no scenario may depend on seniority, so a value here would never do anything. Leave it out. | `Junior` / `Mid` / `Senior` / `Executive` / `null` | Optional (unused here) | — | `null` |
| `department` | Organizational department | Distinguishes, e.g., a Field Engineer in Operations from a Sales Engineer | `Engineering` / `Sales` / `Operations` / `null` | Optional (some roles, like CEO, aren't scoped to a single department) | Lens (future department-level aggregation) | `"Engineering"` |

### Context

| Field | Represents | Why it's needed | Allowed values | Required/Optional | Who uses it downstream | Example |
|---|---|---|---|---|---|---|
| `free_time_minutes` | How much free time the persona has right now | Context for the scenario and a direct input to Chain's constraint-satisfaction step. **Not** visible to the ranking model (it lives on `context`, not `Task`), so the scenario's correct answer must not depend on it — see "What the ranking model actually sees" above | Positive integer | Required | Chain (actual selection); scenario authors as context | `30` |
| `current_datetime` | "Now" for this scenario | Without it there's no way to judge whether a `deadline` is near, far, or already passed. Per the mentor's decision it **is** passed to the model (as `context` alongside the task), so `expected_reason` may rely on deadline proximity. | ISO 8601 datetime | Required | The model (urgency vs. `deadline`); urgency calculation | `"2026-08-23T14:00:00"` |
| `time_horizon` | Planning window (today / this week / this month) | Matches each role's different pace of work (Developer = hours-days, CEO = quarters); frames what "urgent" means for the labelled answer | `today` / `this_week` / `this_month` | Required | The mentor / R1 when judging the labelled answer (not visible to `score(task)`) | `"today"` |
| `constraints` | Situational limits the *system* must know about (e.g. "no meetings can be scheduled") | Feeds Chain's feasibility step. **Not** visible to the model, and — per the mentor — **must not** be the deciding factor in a scenario: schedule/user constraints are handled in another part of the system. Keep the array (often empty). | Free-text string array, empty by default | Required (the array itself, even if empty) | Chain (feasibility) only — never `score()` | `[]` |

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
| `parent_task_id` | The `task_id` of this task's parent in the **cross-tier** org hierarchy (a dev task under a manager task), matching the canonical `Task.parent_task_id` | Gives the week-7 hierarchical value-propagation model something to propagate. In production the ancestors come from Chain's traversal API; in the eval they come from the fixed node file [`eval/hierarchy/nodes.json`](../hierarchy/nodes.json), so the comparison stays reproducible. **Not** the old same-tier `dependencies` array — that was removed deliberately | String or `null`. Points *outside* the scenario, at a node in `eval/hierarchy/nodes.json` — never at a candidate task (checked by `mind.evaluation.hierarchy.validate_hierarchy_links`). `null` = an orphan, on purpose | Optional (nullable). Set in 21 of the 36 scenarios — which, and why, is in **Hierarchy** in `src/mind/evaluation/README.md` | R1/R3 (week-7 stretch) | `"mgr_reorg_quarterly_initiative"` |

### Scenario-level fields

| Field | Represents | Why it's needed | Allowed values | Required/Optional | Who uses it downstream | Example |
|---|---|---|---|---|---|---|
| `expected_top_task_id` | The known-correct answer | Enables comparing Model prediction vs. Expected answer and computing a metric — the entire point of the harness | Must match one `task_id` from `tasks` (enforced by a `@model_validator` on `PersonaEvaluationScenario`, so a bad file fails to load — not expressible in the JSON Schema itself) | Required | The harness (top-1 accuracy / MRR calculation) | `"task_B"` |
| `expected_reason` | Short, data-grounded explanation for the correct answer | The difference between a "guess" and a checkable judgment; also used later to compare baseline vs. stretch (weeks 6–7) | String, at least 20 characters, grounded in **per-task** fields (`deadline`, `blocked`, `tier`, description) — never in `free_time_minutes` or a duration comparison, which are Chain's concern | Required | The mentor (plausibility check); R1 (evidence for baseline vs. stretch) | `"Task B has a same-day deadline and Task A is blocked, so Task B is the one to do now."` |
| `source.type` | The scenario's origin | Transparency: lets us know later whether a model was tested on a fabricated example or on real data | `synthetic` / `real` / `AMI` / `FUNSD` / `other` | Required | R1 (performance analysis by source type); data-policy documentation | `"synthetic"` |
| `source.source_detail` | The exact source details | Enables tracing back to the actual origin (e.g. a specific AMI meeting ID) | Free-text string or `null` | Optional (required in practice when `type` is AMI/FUNSD/other) | R1 (traceability) | `"AMI meeting ES2002a"` |
| `team_lead_approved` | Whether the team lead has signed off on this scenario in a PR — the only approval gate | Makes the review visible per scenario instead of tracked in someone's head | `true` / `false` | Required (set explicitly; author starts it `false`, the team lead flips it) | The team lead / R1 as workflow state — not a Foundation Gate criterion and not checked by `validate_personas` | `false` |

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
- A role has scenarios, but not 4-7 of them.
- The full set doesn't total 24-42 scenarios.

## Status

The full set is drafted: **42 scenarios** across all six roles (**7 per role** —
the per-role and total maximum, see `MAX_SCENARIOS_PER_ROLE` / `MAX_TOTAL_SCENARIOS`),
split into `same_tier/` (20), `cross_tier/` (16) and `hierarchy_decides/` (6). The
first 30 are the 2026-09-06 rebuild; the next 6 are the "hard scenarios" round
described below; the last 6, one per role, are the hierarchy group (#507, raised
from 36 / 6 per role with the mentor's agreement). All 42 are
`team_lead_approved`; the 12 newest were approved on 2026-10-27. All load cleanly through `load_personas`;
`validate_personas` reports no errors and no role-coverage / count warnings.

Each role's first five scenarios (the 2026-09 rebuild) test five *different* tradeoffs,
no dimension repeated within a role; the sixth is one of the hard scenarios
(see "Hard-scenarios round" below):

- **A** — a hard deadline vs. a task with none (deadline is right)
- **blocked** — the *blocked* task carries the *nearest* deadline, so a model that
  ignores `blocked` and picks the soonest deadline gets it wrong
- **proximity** — both tasks have deadlines; the nearer one is right
- **nearest-deadline-wrong** — a soon but trivial deadline vs. a critical task
  with no/later deadline; only reading the description gets this right
- one of: **persona ≠ tier** (a CEO's operational firefight, a Developer's
  strategic sign-off), a **deadline that has already passed** (the thing it was
  for is over — deprioritize it), or **customer-facing vs. internal impact**

### Cheap-baseline scores (the reason this set exists)

The set is the measuring stick for baseline-vs-stretch. If a two-line heuristic
already near-ceilings, there is no room to show a real model's improvement. Run
against the original 36 and the full 42 (the 30-scenario figures were 33.3% /
46.7% / 66.7%):

| Strategy | Original set (36) | `hierarchy_decides/` (6) | All (42) |
|---|---|---|---|
| pick the first task (`DummyModel`) | 27.8% (10/36) | 2/6 | 28.6% (12/42) |
| pick the nearest deadline, ignore `blocked` | 41.7% (15/36) | 2/6 | 40.5% (17/42) |
| **skip `blocked`, then nearest deadline** | **58.3% (21/36)** | 2/6 | 54.8% (23/42) |
| pick the longest description (`LongestDescriptionModel`) | 61.1% (22/36) | 0/6 | 52.4% (22/42) |

All measured 2026-10. The original-set column did not move when the hierarchy
group was added. On `hierarchy_decides/` the two deadline rules get exactly the two
controls (the hard signal holds the nearest deadline there) and miss the four
cases the hierarchy decides, which is how that group is built. `DummyModel` gets the
two whose answer is listed first (one decided, one control) and
`LongestDescriptionModel` none.

(Measured by `mind.evaluation.baseline_models`, not hand-derived — see below.
The 30-scenario set was designed for ~30%/~43%/~63%; after the 2026-09-16 review
round's rewrites it measured 33.3%/46.7%/66.7%, a few points higher, most notably
because of `manager_overdue_preread_vs_tomorrow_review`, which the "skip `blocked`,
then nearest deadline" baseline gets right for a reason that isn't cheating —
see the `eval-ci` target in the `Makefile`. The six hard scenarios then brought
the two deadline rows down to 41.7% and 58.3%; one of them
(`manager_idle_...`) has the nearest deadline as its correct answer on purpose.)

The last row is a length-bias probe: it scores a task by the length of its
description and nothing else. The six hard scenarios originally scored 6/6 on it
(the correct answer was the longest text in every one, because it carried the
explanation of why it is correct); after the rewrite it scores 0/6 on them. Its
22/36 comes entirely from the 30 original scenarios (22/30), which is why the
`eval-ci` ceiling is 0.65 and not lower.

No trivial position rule beats ~50%. The ~20-point gap between the last two
rows is deliberate — it is the headroom a model earns by recognising
`blocked`; the ~44 points above 55.6% is the headroom it earns by reading
descriptions.

**These two baselines are real code, not just a table.**
`mind.evaluation.baseline_models.NearestDeadlineModel` and
`SkipBlockedNearestDeadlineModel` implement the `RankingModel` contract (they
only need one task plus `current_datetime`, exactly what `score(task, context)`
already gives them) and produce the 41.7%/58.3% numbers above. `make eval-ci`
runs `SkipBlockedNearestDeadlineModel` with `--max-accuracy 0.68` — that is the
real headroom guard (see the `Makefile` comment for why 0.68 and not the
originally-designed 0.63). `DummyModel`, which returns a constant score and so
never reads `deadline` or `blocked` at all, is run separately with a `min`/`max`
band (20-50%, around its 27.8%) as a *position-bias* guard only — it cannot tell you anything
about whether a deadline heuristic is near the ceiling, because it never looks
at `deadline` in the first place. See the `eval-ci` target in the `Makefile`
for both.

**Post-mentor-review cleanup (2026-08-31).** The mentor's feedback: (1) the model
now receives `current_datetime` as context, so deadline-proximity reasoning is
fair game; (2) scores are calibrated to a global 0–1 scale and cross-tier
comparisons are legitimate; (3) no scenario may hinge on `persona.seniority` or
`context.constraints` — the model ranks tasks, not people or schedules. Seven
scenarios were rewritten to remove seniority-scoped or constraint-scoped reasoning
(the "junior authority" case became a `blocked` case; the "no meetings / offline /
no VPN / phone-only" cases now turn on a plain deadline). One scenario
(`developer_junior_architecture_review_vs_bugfix`) was dropped and replaced with a
clean same-tier scenario. (4) The set was split into the `same_tier/` and
`cross_tier/` groups (see the top of this file); `make eval` now reports a score
for each plus a combined one. Split: 16 `same_tier`, 10 `cross_tier` (26 total).
Two scenarios were added while splitting so every role has at least one scenario
in each group — `manager_mid_incident_actions_vs_process_polish` (same_tier) and
`field_engineer_senior_refresh_planning_vs_climbing_errors` (cross_tier). Per-role
combined counts stay in the 4-6 range (Developer 4, Manager 5, Higher Manager 4,
Field Engineer 5, Salesperson 4, CEO 4).

**Team-lead PR review (2026-09-01).** From the review of the set:
- `Task.task_id` now uses the same pattern as `scenario_id` (`^[a-z0-9_]+$`) —
  it had allowed uppercase by accident.
- New scenario-level field **`team_lead_approved`** (bool), mirroring
  `mentor_approved` (since removed) — the team lead's sign-off.
- **`seniority` was removed from every scenario** and dropped from the
  `scenario_id` naming (`developer_senior_blocked_oauth_...` →
  `developer_blocked_oauth_...`). It was being set decoratively on all 26 while
  never being the deciding factor — which it structurally can't be (the model
  never sees it, and the mentor ruled no scenario may depend on it). The field
  stays in the schema for future consumers; this set just doesn't populate it.

**Mentor review of the set (2026-09-06).** The mentor ran trivial baselines and
found a two-line heuristic scored 85%, leaving no room to measure a real model's
improvement. The set was rebuilt to **30 scenarios (5 per role)** so that:

- the "skip `blocked`, then nearest deadline" baseline scores **~63%** (down from
  85%) — see *Cheap-baseline scores* above;
- every `blocked` scenario gives the **blocked task the nearest deadline**, so
  handling `blocked` is what separates the ~43% and ~63% baselines;
- **both tasks fit `free_time_minutes`** in every scenario, so Chain's effort
  filter can never eliminate the distractor and hand the model a free answer;
- the correct answer's **position is shuffled** (index 0 in 10/30, 1 in 14, 2 in
  6; no "pick task *k*" rule beats ~50%), and no 3-task scenario has it first;
- **persona ≠ tier** in several scenarios (a CEO doing operational work, a
  Developer doing strategic work), so "role ⇒ tier" is not a shortcut;
- two scenarios test a **deadline that has already passed** — in opposite
  directions, so "overdue" is not a shortcut for "deprioritize":
  `ceo_overdue_launch_statement_vs_earnings_prep` is the case where the thing
  the deadline was for already happened, so the overdue task really is low
  value now; `manager_overdue_preread_vs_tomorrow_review` is the case where
  the overdue task is *still* the most urgent thing on the list, because
  whatever it feeds is still live;
- every `deadline` is justified in the task's own `description`;
- no scheduled-meeting start time is used as a `deadline`.

**Hard-scenarios round (2026-10).** The mentor ran a real model on the set and
it scored too high, so six deliberately misleading scenarios were added (one per
role; set was then 36, the maximum at the time). Each one makes the surface signals — nearest
deadline, tier, urgent-sounding words, the `blocked` flag — point at the wrong
task, while the right one is still decidable from per-task text alone. Same
rules as above: nothing hinges on `free_time_minutes`, `seniority` or
`constraints`; every deadline is explained in its description; a blocked task
with a deadline holds the nearest deadline; both approvals start `false`.

| Scenario | Disguise |
|---|---|
| `developer_inflated_p0_vs_understated_data_leak` | A `[P0]` title on a staging-only alert and a "Minor" title on a live customer-data exposure (with a regulator window); a blocked task holds the nearest deadline. |
| `manager_idle_new_hires_vs_informational_steering_deck` | **The nearest deadline is the correct answer** (access approval, 11:30) against two longer, later, better-sounding tasks: a `strategic` deck nobody needs by then, and a vendor questionnaire that chased twice. Tier trap: `strategic` decoy vs `tactical` answer. |
| `higher_manager_approved_vision_memo_vs_expiring_capacity_offer` | Big-sounding `strategic` memo whose content is already approved, and a travel-exception approval due today at 17:00 (the nearest deadline) vs a binding offer that lapses Friday morning. |
| `field_engineer_released_controller_vs_blocked_fibre_fix` | The answer has the *latest* deadline (a 72-hour contractual redundancy clock); its parts blocker was cleared that morning, so `blocked: false`, though the text mentions the earlier wait. |
| `salesperson_dead_rfp_vs_live_signing_round` | A future deadline whose purpose is dead (the RFP was already awarded to a competitor) and a customer-review deck due tomorrow morning vs a live signing-round cut-off. |
| `ceo_own_investor_commitment_vs_unaccepted_keynote_request` | A deadline an outside party set for a request the CEO never accepted vs a deadline the CEO committed to in writing; plus a `strategic` filler with no deadline. |

Measured effect: `SkipBlockedNearestDeadlineModel` gets **1 of the 6** (the
`manager_idle_...` one, whose answer is the nearest deadline; 66.7% → 58.3% combined);
`DummyModel` gets 0 of 6 (none has the answer in position 0), dropping to 27.8%,
still inside its 20-50% band. Position distribution was then index 0 in 10/36, 1 in
17, 2 in 9 (no 3-task scenario has the answer first). With `hierarchy_decides/` (#507,
answer at index 0 in two, 1 in one and 2 in three, so that "pick the first task" is
not scored 0/6 there by construction) it is 12 / 18 / 12 of 42, which is
what `tests/test_personas.py` pins (`EXPECTED_REAL_SCENARIO_COUNT = 42`, the position
dict `{0: 12, 1: 18, 2: 12}`; the "answer never first in a 3-task scenario" rule now
applies to the original set only). The
`make eval-ci` headroom gate (`--max-accuracy 0.68`) is unchanged and now has
~10 points of slack instead of ~1. Known overlap, deliberate because stacking
traps is what lowers the score: the Developer and Field Engineer scenarios each
combine a blocked-nearest-deadline distractor with a second trap, so they echo
`developer_blocked_oauth_vs_pr_review` / `field_engineer_blocked_rma_vs_runbook_update`
in part.

**Review round on the hard scenarios (2026-10, PR #535).** The mentor's comments, addressed:

- Every new scenario has 3 candidates, and the third is a *second trap*, not filler:
  it has a close deadline or a convincing reason to look important, and its text shows it
  is not (`task_vendor`, `task_travel_exceptions`, `task_qbr`). With a third task the
  answer may not be first, which is why `manager_idle_...` and `salesperson_...` were
  reordered (position distribution above).
- **Length bias.** The mentor found that picking the longest description scored 6/6 on
  the new scenarios, because the correct answer was the one that explained itself. The
  traps were rewritten to be long and detailed, and the correct answer short and dry
  (it is never the longest text in a scenario). `LongestDescriptionModel` is now a
  baseline with an `eval-ci` gate (`--max-accuracy 0.65`), and
  `tests/test_personas.py` pins both the ceiling and "the correct answer is never the
  longest description in the hard scenarios".
- **The nearest deadline is not always a trap any more.** In `manager_idle_...` the
  correct answer (the access approval, 11:30) has the nearest deadline; a test pins that
  at least one hard scenario is like this.
- Descriptions give facts, not conclusions: sentences that wrote out the answer
  ("a late deck changes nothing for anyone", "no one is relying on you", "the lead has
  said the fix can wait", "a late flag is simply cleared", "no further submissions will
  be read") were removed from the new scenarios.
- `manager_idle_...` states the cost of delay (each hour the approval waits, six
  engineers sit idle). That stated fact is what makes `task_access` the answer; the
  free-time window plays no part in it, because the model never sees
  `free_time_minutes` (see "What the ranking model actually sees").

**Real-model measurement (2026-10).** `SegmentedRankingModel` (`mind.scoring`, merged
to `main` in #498), run on all 36 scenarios *after* this round's rewrite (same result before
and after the last wording and length-balancing changes, so nothing was iterated on it):

| | Correct |
|---|---|
| All 36 | **30/36 = 83.3%** |
| dev (20 old scenarios the model was calibrated on) | 20/20 |
| holdout (10 old scenarios) | 9/10 |
| the 6 hard scenarios (never seen by the model) | 1/6 |

For the record: 32/36 = 88.9% before this round (3/6 on the hard scenarios). An earlier
attempt that re-worded three scenarios after reading the model's keyword lexicon
(80.6%) was **reverted**, because a set built to fail one model at its known weak
points makes every other model look better by comparison. This round was written
from the mentor's length finding and from what is hard for a person, and measured
after the fact; the three phrases that had come from the reverted attempt ("forwarded to the
board and to investors", "shared with investors afterwards", "an audience of
customers and industry analysts") were deliberately not reused. The author had
read the model's lexicon earlier, so this is not a blind measurement.

**`mentor_approved` removed (2026-10).** The scenario-level `mentor_approved` flag was
removed at the mentor's request; `team_lead_approved` is now the only approval flag.
The model forbids unknown fields, so a scenario file that still carries
`mentor_approved` fails to load. Older notes below that mention it describe the
state at the time.

## Contributing a scenario

1. Pick one of your assigned roles. Write 4-6 scenarios for it, each testing a
   genuinely different kind of choice (a hard deadline vs. no deadline, a
   `blocked` distractor, a customer-facing vs. internal importance call, a
   far-future deadline that isn't really urgent, a tier/scope contrast...) — not
   six variations on the same idea. The decider must always be something the
   model sees: `deadline`, `blocked`, `tier`, or stated impact in the
   description — never `seniority` or `constraints`.
2. **Name your `scenario_id`s `<role>_<short_slug>`** (e.g.
   `ceo_board_resolution_vs_conference_keynote`), matching the existing set. The
   `<slug>` is usually `<option_a>_vs_<option_b>` so the tradeoff is readable from
   the filename. `scenario_id` must match `^[a-z0-9_]+$`. If two authors are
   writing the same role in parallel, agree on slugs up front to avoid a
   collision (`validate_personas` fails the build on a duplicate `scenario_id`).
3. One scenario per file, filename `<scenario_id>.json`, dropped into
   `same_tier/` if every candidate task shares a `tier`, otherwise `cross_tier/`.
   Follow `scenario.schema.json` (generated from the `PersonaEvaluationScenario`
   model) — see the field tables above for what each field means and why.
4. Set `team_lead_approved: false` — the author never sets it to `true`. The
   team lead flips it in the PR.
5. Before pushing, validate locally from `teams/mind/`:
   ```bash
   python -c "from mind.evaluation.personas import load_personas, validate_personas as v; s = load_personas('eval/personas'); r = v(s); print('errors:', r.errors); print('warnings:', r.warnings)"
   ```
   If your file is structurally wrong or breaks an intra-scenario invariant
   (dangling `expected_top_task_id`, missing `source_detail`), `load_personas`
   raises `PersonaSchemaError` naming the file and field. Otherwise `r.errors`
   must be empty. Warnings about other roles/scenarios not being ready yet are
   expected and fine — that's everyone else's in-progress work, not yours.

   Also run the time_horizon check (see "Deadline proximity" below):
   ```bash
   python -m eval.check_time_horizon
   ```
   This is the same check CI runs (`make eval-ci`) — catch it locally rather
   than in review.

## Scenario-writing conventions

None of these are enforced by the schema or by `validate_personas` — they're
judgment calls that four different people can easily make inconsistently.
Follow them so the mentor review doesn't come back with "these two scenarios
contradict each other."

**Deadline proximity ("urgent" vs. not)** — judged against `current_datetime`,
which the model does see:
- A deadline the same working day as `current_datetime`, or a few hours out,
  reads as genuinely urgent — a real pressure signal you can lean on in
  `expected_reason`.
- A deadline weeks or months past `current_datetime` is not urgency — don't write
  `expected_reason` as if it were the deciding factor (the `time_horizon` field
  frames this for a human reader, but the model only has the raw dates).
- `deadline: null` = no urgency signal at all. Don't imply urgency for it.

**A deadline past `time_horizon` on a *distractor* is deliberate, not a bug.**
A few scenarios pair a near-term correct answer with a distractor whose deadline
falls outside the scenario's `time_horizon` (`ceo_regulator_call_vs_board_prep` —
`today`, `task_board` two days out; `field_engineer_prod_incident_vs_scheduled_maintenance`
— `today`, `task_maintenance` a week out). That is the point: the far deadline is
there so it reads as *not* urgent against the live task. The correct answer's own
deadline always sits inside `time_horizon`; only the distractor's may fall beyond
it. A `this_week` scenario whose *correct* answer is due next week is a bug —
either widen `time_horizon` (as `manager_blocked_hire_vs_budget_review` does,
widening to `this_month` because its correct answer genuinely is a multi-week
Q3 budget review) or move the deadline itself if the story is really a
tighter one. `ceo_blocked_ma_review_vs_vp_offer` and
`field_engineer_cutover_inspection_vs_backlog_patch` both originally widened
the label instead of fixing the data (`this_week`→`this_month`, `today`→
`this_week`) to pass the checker; both were revisited and now move the
deadline itself instead (a vendor-contract deadline moved within the same
week; a cutover moved from "tomorrow" to "tonight") — see "Review status"
below.

This is mechanically checked, not just a convention to remember:
`tests/test_personas.py::test_real_persona_set_correct_answer_deadline_inside_time_horizon`
calls `mind.evaluation.personas.correct_answer_deadline_violates_time_horizon` against
the real set, so it runs under `make test` (linted and type-checked like any other
code, unlike a standalone script under `eval/`). `python -m eval.check_time_horizon`
is a thin CLI wrapper around the same function, kept only so `make eval-ci` has a
command to run.

Four other set-wide invariants get the same treatment, each next to this one in
`tests/test_personas.py`: every `blocked`-with-a-deadline task holds the nearest
deadline in its scenario, every task's `effort_estimate` fits `free_time_minutes`,
the correct-answer position distribution matches the numbers above, and the same
task text never carries two different `tier` values across files. All five used to
be prose only — a scenario edit could silently break one and CI would stay green.

**What the model can't see — never the decider:**
- The model sees one task plus a context carrying only `current_datetime`
  (`score(task, context)`); it never sees `free_time_minutes`, `constraints`,
  `persona` (so not `seniority`), or the other candidates. See "What the ranking
  model actually sees" above.
- `effort_estimate`, `free_time_minutes` and `constraints` still belong in every
  scenario — Chain needs them downstream — but the `expected_reason` must rest on
  per-task signals: a hard deadline (now judgeable against `current_datetime`),
  `blocked`, `tier`, or an importance judgment from the description
  ("production-impacting", "unblocks a teammate", "affects a paying customer").
- Never phrase `expected_reason` as "fits the N-minute window", "he can't take
  meetings today", or "a junior wouldn't handle this". If a
  duration/constraint/seniority fact is the *only* thing separating the correct
  task from a distractor, redesign so a per-task signal decides it.

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

**`seniority` and `constraints` — not the decider:**
- The mentor was explicit: the MIND model ranks the tasks, not the person or
  their calendar. A scenario must be correct on `deadline` / `blocked` / `tier` /
  described impact alone.
- **`seniority`: leave it out entirely** — this set doesn't use it (see the field
  table). Don't reintroduce it "for flavour".
- **`constraints`: put an entry in only if Chain would genuinely need it**, and
  never let it be what flips the answer — the model never sees `context`.

**One dimension per scenario, no repeats within your role:**
- Each of your 4-6 scenarios should test a genuinely different tradeoff
  (hard deadline vs. none, a `blocked` distractor, customer-facing vs. internal
  impact, a far-future deadline that isn't urgent, a tier contrast...).
  If two of your scenarios are the same test with different flavor text, cut one.

**Pattern-match against the existing set before inventing a new style** —
`cross_tier/developer_prod_crash_vs_backlog_feature.json` (deadline + impact),
`cross_tier/developer_blocked_oauth_vs_pr_review.json` (`blocked` distractor),
`same_tier/field_engineer_customer_degradation_vs_internal_tooling.json` (customer
vs. internal), and `same_tier/manager_status_digest_vs_reorg_proposal.json`
(a near deadline that is the *wrong* choice) each demonstrate one pattern end-to-end.

## Review status

*(Filled in by R1 as reviews happen — not a placeholder for anyone to hand-wave.
"Partially approved with a documented plan to close" is the honest state to
report if 100% isn't reached by the Foundation Gate.)*

**Team-lead review (PR #198):**
- **Update 2026-10:** the set is now 36. The 30 below were approved as described;
  the six hard scenarios added in PR #535 are still `team_lead_approved: false`.
- **Update 2026-10-27:** the set is now 42 (#507 added `hierarchy_decides/`). The
  team lead approved the 12 that were still `false` (#535's six and the six
  hierarchy scenarios), so 42 / 42 are `team_lead_approved`.
- **Reviewed by:** estherora-tem, team lead. (The mentor is a different person
  and has not reviewed this set yet — that is a separate, still-pending gate;
  `mentor_approved` stayed `false` on all 30 until it was removed in 2026-10.)
- **Approved:** 30 / 30 `team_lead_approved`. 27 from the 2026-09-15/16
  scenario-by-scenario sign-off pass (22 outright, 5 with a non-blocking note —
  see below); the remaining 3 (`manager_blocked_hire_vs_budget_review`,
  `manager_overdue_preread_vs_tomorrow_review`,
  `salesperson_at_risk_renewal_vs_owned_outage`) were rewritten per her
  requested fixes and approved on re-review (2026-09-17). The set was rebuilt
  on 2026-09-06 in response to her earlier review (baseline too high,
  blocked-distractor pattern, effort/free-time, persona↔tier, position bias,
  deadline-passed).
- **Notes / pending changes:** Addressed 2026-09-15 in response to
  estherora-tem's PR review:
  - `ceo_blocked_ma_review_vs_vp_offer`'s `expected_reason` corrected, and its
    `time_horizon` widened `this_week` → `this_month` so the correct answer's
    own deadline (next Monday) sits inside the window, matching the fix
    already applied to `manager_blocked_hire_vs_budget_review`.
  - Same bug found and fixed in `field_engineer_cutover_inspection_vs_backlog_patch`
    (`today` → `this_week`; correct answer is due the next morning).
  - `higher_manager_stalled_renewal_vs_market_expansion`'s untouched third task
    renamed `task_travel_policy` → `task_filler_travel_policy` to match the
    `task_filler_*` convention for tasks `expected_reason` doesn't need to name.
  - The time_horizon rule is now mechanically checked (`eval/check_time_horizon.py`,
    run via `make eval-ci`) instead of relying on someone re-deriving it by hand
    per file — see "Deadline proximity" below.
  - `mind-ci.yml`'s eval step (previously `make eval`, which always exits 0 for
    any load that succeeds) now runs `make eval-ci`: the time_horizon check plus
    a combined top-1 accuracy gate (`--min-accuracy 0.20 --max-accuracy 0.50`)
    around the designed ~30% dummy/pick-first baseline — see "Cheap-baseline
    scores" above.

  **Scenario-by-scenario sign-off pass (2026-09-15/16).** estherora-tem reviewed
  all 30 individually: 22 approved outright, 5 approved with a non-blocking
  note, 3 not approved. Also flagged: the `--max-accuracy 0.50` gate above did
  not guard what its comment claimed (`DummyModel` never reads `deadline`/
  `blocked`, so it cannot detect a set that has lost its headroom — proven with
  a sabotaged copy of the set that scored 100% on the real heuristic while
  still passing `make eval-ci`), `eval/check_time_horizon.py` sat outside
  every quality gate (`make lint`/`make test` don't touch `eval/`) and
  re-parsed JSON by hand instead of using `load_personas`, and 4 of the 5
  set-wide invariants this README documents were prose only. Addressed:

  - `manager_blocked_hire_vs_budget_review`: `task_hire`'s blocker changed from
    "waiting on panel feedback" (something the manager could just chase
    herself) to the two panel members being unreachable while travelling —
    genuinely outside her control, matching the pattern in
    `field_engineer_blocked_rma_vs_runbook_update` and
    `salesperson_blocked_discount_vs_warm_followup`.
  - `manager_overdue_preread_vs_tomorrow_review`: rewritten as the "overdue but
    still the most urgent thing" case (see the deadline-passed bullet above) —
    the compliance report is overdue, but the auditor is now asking for it
    directly, so `expected_top_task_id` flips to the overdue task. This was a
    structural duplicate of `ceo_overdue_launch_statement_vs_earnings_prep`
    (both "overdue item whose event already happened" vs. "prep for tomorrow");
    the rewrite removes the duplicate and adds the missing direction at once.
  - `salesperson_at_risk_renewal_vs_owned_outage`: the speculative "new logo"
    distractor (structurally identical to `higher_manager_stalled_renewal_vs_market_expansion`
    — an at-risk account vs. speculative growth, both all-null-deadline) was
    replaced with a task that reads as urgent but whose description says
    another team already owns it and is actively working it — a dimension the
    set was missing entirely, not just a reshuffle.
  - `ceo_payment_processor_cutoff_vs_strategy_deck`: `task_filler_press` tier
    corrected `strategic` → `operational` (a 15-minute trade-press quote is not
    strategic-scope work; tier describes the kind of work, not its stakes).
  - The correct-answer position distribution shifted with the
    `manager_overdue_preread_vs_tomorrow_review` rewrite: **index 0 in 10/30,
    1 in 14, 2 in 6** (was 9/15/6) — updated above and in the new position-
    distribution test.
  - The real headroom gate now runs
    `mind.evaluation.baseline_models.SkipBlockedNearestDeadlineModel`
    (`--max-accuracy 0.70`) instead of leaning on `DummyModel`, which is kept
    only as a position-bias guard — see "Cheap-baseline scores" above.
  - The time_horizon check moved into `tests/test_personas.py` (typed, run by
    `make test`); `eval/check_time_horizon.py` is now a thin wrapper so
    `make eval-ci` still has a command to run. Four more tests were added for
    the previously-prose-only invariants (blocked-holds-nearest-deadline,
    effort-fits-free-time, position distribution, no duplicate task text with
    a different tier) — see "Deadline proximity" below for the time_horizon
    one specifically.

  **Not changed (mentor flagged as non-blocking, explicitly "not this PR"):**
  the overlap between `developer_prod_crash_vs_backlog_feature` and
  `developer_login_hotfix_vs_migration` (both "production incident, same-day
  deadline wins"); the structural echo between `ceo_regulator_call_vs_board_prep`
  and `higher_manager_regulatory_filing_vs_strategy_section` (both "regulatory
  item due today with a penalty" vs. "strategic document due later").

  **`ceo_blocked_ma_review_vs_vp_offer` revisited (2026-09-16), both of her
  non-blocking notes on the same file:** (1) the stakes imbalance —
  `task_ma` was "review the acquisition term sheet"; reframed as "review the
  renewed vendor contract terms" so the blocked task's stakes are no longer
  wildly larger than the VP hire decision it loses to. (2) the `this_month`
  widening from the first review round "fixed the label, not the data" — the
  persona was still planning her week. Reverted `time_horizon` to `this_week`
  and moved `task_vp`'s deadline itself (next Monday → Friday) instead;
  `task_ma` keeps the nearer deadline that same day (17:00 vs. 20:00), so the
  blocked-holds-nearest-deadline pattern is unaffected. First pass moved it to
  Sunday 17:00 -- the last instant still inside `this_week` per the checker --
  which fixed the checker again instead of the scenario: the description
  already said "by the end of the week", and in this set "end of the week"
  is read as Friday, not Sunday afternoon (the same bug flagged earlier on
  `higher_manager_blocked_budget_vs_headcount_plan`'s Saturday deadline).
  Corrected the deadline to Friday 20:00; the description text itself didn't
  need to change -- it was right the whole time, only the date behind it was
  wrong.

  **`field_engineer_cutover_inspection_vs_backlog_patch` revisited (2026-09-16),
  same "label not data" note:** the cutover was "tomorrow's 08:00", which is
  why `today` had to be widened to `this_week` to pass the checker in the
  first review round. Moved the cutover itself to tonight (18:00, same day as
  `current_datetime`) and reverted `time_horizon` to `today` instead — the
  data now matches the label. Her other note on this file (it's the most
  generic, "deadline vs. no deadline" scenario in the Field Engineer set) was
  explicitly "fine" as-is, not something to change.

  **Three more findings (2026-09-16/17):**
  - `salesperson_at_risk_renewal_vs_owned_outage` (renamed from
    `...vs_new_logo`): the file/`scenario_id` still said "new logo" after the
    2026-09-16 re-pointing replaced that task with `task_outage_followup`, so
    the filename no longer matched `<option_a>_vs_<option_b>` or the actual
    content. Renamed both.
  - `manager_overdue_preread_vs_tomorrow_review`: making the overdue task
    correct (see above) had a side effect — `NearestDeadlineModel`'s formula,
    `0.5 - hours/10_000`, scores *any* overdue deadline above 0.5 and *any*
    future deadline below 0.5 regardless of magnitude, so an overdue deadline
    is mathematically always "nearest". "Skip `blocked`, then nearest
    deadline" now solves this scenario for the wrong reason, and combined
    accuracy moved from 63% (design target) to 66.7% (measured) — verified
    that adding a third task with a same-day future deadline does not change
    this (even a deadline 1 second overdue outscores one 1 minute away).
    Since a scenario-content fix isn't possible without changing the formula,
    lowered `make eval-ci`'s headroom gate from `--max-accuracy 0.70` to
    `0.68` instead (documented in the `Makefile`) rather than leave the gate
    silently correct-by-luck at 0.70.
  - The "Cheap-baseline scores" table above was measured against the
    now-current set (33.3% / 46.7% / 66.7%) instead of the original design
    targets (~30% / ~43% / ~63%), which had drifted out of date after the
    rewrites in this review round.

  **`current_datetime` diversity (2026-09-16).** The mentor's other set-wide
  observation: all 30 scenarios shared one `current_datetime` (Monday
  2026-09-07), so `this_week`'s Monday-start-week logic and the end-of-month
  boundary in `this_month` were never exercised near an edge. Fixed by moving
  four scenarios' `current_datetime` (and every task `deadline` in the same
  file, by the identical delta, so the relative "today"/"tomorrow"/"Friday"
  framing in each description stays true):
  - `salesperson_processing_cutoff_vs_call_summary` and
    `manager_escalation_call_vs_design_review`: `this_week`, shifted +3 days
    (Monday → **Thursday**), so the window has only 3 days left before Sunday
    instead of 6.
  - `manager_blocked_hire_vs_budget_review`: `this_month`, shifted +14 days
    (weekday-preserving, so no description text needed to change) — its
    correct answer (`task_budget`) now lands its deadline on
    **2026-09-30, the last day of the month**, directly exercising
    `calendar.monthrange`'s last-day calculation in `time_horizon_end`.
  - `ceo_blocked_ma_review_vs_vp_offer` was shifted +14 days too, but its
    `time_horizon` was later reverted from `this_month` back to `this_week`
    (see the note above) — it still keeps a `current_datetime` other than
    2026-09-07, just no longer exercises the month-end edge specifically.
  - All four re-verified against every invariant in this file (`make test`,
    `make eval-ci`) — none of the tasks' relative ordering, `blocked` status,
    or tier changed, so every existing test (including the position-
    distribution and headroom gates) still passes with identical numbers.
