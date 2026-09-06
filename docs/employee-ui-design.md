# Resonance Flow --- Employee UI Design

## 1. Purpose

This document defines the proposed minimal Employee experience for
**Resonance Flow**.

The central product question is:

> **"I have some free time now --- what is the most important thing I
> should work on?"**

The Employee UI should therefore be focused on: - available time →
recommended task - clear explanation of why the task was selected -
ability to start the task - ability to provide feedback through chat -
ability to inspect and manually update task/dependency/blocker
information

This is a **UX concept example**. It does not redefine team
ownership or replace the backend contracts.

------------------------------------------------------------------------

# 2. Navigation

Keep the Employee navigation minimal.

``` text
Resonance Flow

⌂ My Workspace
✓ My Tasks
```

### My Workspace

Answers:

> "I have 45 minutes. What should I do now?"

### My Tasks

Shows and manages the employee's complete task list.

Do not duplicate the full task list inside My Workspace.

------------------------------------------------------------------------

# 3. Screen 1 --- My Workspace

## Minimal skeleton

``` text
┌──────────────────────────────────────────────────────────────┐
│ Resonance Flow                                                │
│                                                              │
│ What can I accomplish right now?                             │
│                                                              │
│  I have [ 45 min ]                    [ Find my best task ]  │
│                                                              │
├──────────────────────────────────────────────────────────────┤
│ ✨ BEST TASK FOR YOU                                         │
│                                                              │
│ Review API changes                              High         │
│ Platform Service                                             │
│                                                              │
│ ✓ Fits in your 45 minutes                                   │
│ ✓ High importance                                           │
│ ✓ No blocking dependency                                    │
│                                                              │
│ [ Start task ]   [ Chat about it ]   [ View details ]       │
└──────────────────────────────────────────────────────────────┘
```

### Primary flow

``` text
Employee
   │
   │ "I have 45 minutes"
   ▼
Chain
   │
   ├── Mind task scores
   ├── effort estimate
   ├── dependencies
   ├── blockers / constraints
   └── available time
   │
   ▼
Best feasible task
   │
   ▼
Employee Workspace
```

The plan defines Mind as responsible for detecting/scoring tasks and
Chain as responsible for selecting the feasible best-fit task given the
free-time window, scores, dependencies and constraints.

------------------------------------------------------------------------

# 4. Actions on the recommended task

## A. Start task

Starts work on the recommended task.

This is the primary action.

------------------------------------------------------------------------

## B. Chat about it

Allows the employee to interact with Resonance Flow about the
recommendation.

Examples:

``` text
"Why did you choose this task?"

"I need manager approval before I can start."

"This task is already completed."

"I'm waiting for the customer."
```

The chat should not be treated as a disconnected conversation.

If the employee provides information that changes the task state, the
relevant task data should be updated.

Example:

``` text
Employee:
"I need manager approval before I can do this."

          ↓

Resonance Flow

"I'll record that this task is blocked
by the required approval."

          ↓

Task

Status: Blocked
Constraint: approval_needed
```

The plan explicitly includes an **employee feedback loop** where
employee responses refine stored task data over time.

------------------------------------------------------------------------

## C. View details

Opens the complete task details.

This is the explicit place for task/dependency/blocker management.

``` text
┌──────────────────────────────────────────────────────┐
│ Task Details                              [ Edit ]   │
│                                                      │
│ Review API changes                                   │
│                                                      │
│ Status       In Progress                             │
│ Owner        Rachel                                  │
│ Due          Tomorrow                                │
│ Effort       35 min                                 │
│                                                      │
│ Dependencies                                         │
│ ───────────────────────────────────────────────      │
│ → Update API design                                  │
│ → Authentication fix                                 │
│                                                      │
│ Constraints / Blockers                              │
│ ───────────────────────────────────────────────      │
│ None                                                 │
│                                                      │
│ [ Edit task ]   [ Edit dependencies ]               │
└──────────────────────────────────────────────────────┘
```

------------------------------------------------------------------------

# 5. Editing dependencies and blockers

The user must be able to correct task/dependency information when the
system's information is wrong or incomplete.

Example:

``` text
Edit dependencies

This task depends on:

☑ Update API design
☐ Customer approval
☐ Authentication fix

[ + Add dependency ]

                 [ Cancel ] [ Save ]
```

The exact final dependency values should follow the canonical task model
/ API contract.

The plan states that Chain owns the dependency graph, including human
correction of dependencies inferred incorrectly by Mind.

------------------------------------------------------------------------

# 6. "Can't do this now" flow (optional)

The recommended task should have an alternative to **Start task**.

``` text
[ Start task ]   [ Can't do this now ▾ ]
```

Use this when the task cannot currently be performed because of a real
blocker or constraint.

Examples:

``` text
Can't do this now

○ Waiting for manager approval
○ Waiting for customer information
○ Blocked by another task
○ Missing required information
○ Other constraint
```

The selected reason should update the task/blocker information rather
than simply hiding the task.

------------------------------------------------------------------------

# 7. Screen 2 --- My Tasks

My Tasks is the complete task-management view.

It should not duplicate the task list inside My Workspace.

``` text
┌──────────────────────────────────────────────────────────────┐
│ My Tasks                                                     │
│                                                              │
│ [ All ] [ In Progress ] [ Blocked ] [ Waiting on me ]       │
│                                                              │
├──────────────────────────────────────────────────────────────┤
│ Task                         Status          Due             │
│ ─────────────────────────────────────────────────────────── │
│ Update customer proposal     Blocked         Today           │
│ Prepare contract draft       Blocked         Tomorrow        │
│ Review API changes            Ready           Today           │
│ Update documentation         In Progress     May 20          │
└──────────────────────────────────────────────────────────────┘
```

Selecting a task opens **Task Details**, where the user can edit the
task and its dependencies.

------------------------------------------------------------------------

# 8. End-to-end Employee flow

``` text
                    MY WORKSPACE
                         │
                         ▼
              "I have 45 minutes"
                         │
                         ▼
                Find my best task
                         │
                         ▼
              ┌────────────────────┐
              │ Best task for you  │
              └────────────────────┘
                    │    │    │
          ┌─────────┘    │    └────────────┐
          ▼              ▼                 ▼
     Start task     Chat about it     View details
                         │                 │
                         │                 ├── Edit task
                         │                 └── Edit dependencies
                         │
                         ▼
                 Task data updated
                         │
                         ▼
              Chain / task state
                  is recalculated
```

------------------------------------------------------------------------

# 9. Backend ownership / data sources

The Employee UI is a consumer. It should not calculate prioritization
itself.

### Mind

Provides: - task detection/extraction - task importance / score -
classification - explanation-related intelligence

### Chain

Provides: - dependency graph - blocker / constraint state - dependency
correction - feasibility / eligibility - best-fit task selection for the
available time window

### Inbox

Provides the canonical task data / task lifecycle.

### Relay

Provides real-time push infrastructure where required.

------------------------------------------------------------------------

# 10. Important UX principles

1.  **Minimal screen**
    -   Do not add Calendar unless explicitly required by the final
        product scope.
    -   Do not add Upcoming widgets.
    -   Do not add duplicated task summaries.
    -   Do not add Dependencies / Related sections to the main
        workspace.
2.  **One primary question**
    -   "What should I do now?"
3.  **One recommended task**
    -   Make the recommendation the visual focus.
4.  **Three clear actions**
    -   Start task
    -   Chat about it
    -   View details
5.  **Chat can change the task**
    -   Feedback is not merely conversational; relevant information
        should update stored task data.
6.  **Details are the explicit management surface**
    -   Editing task data, dependencies and blockers belongs in Task
        Details.
7.  **Blocked is actionable**
    -   When a task cannot be performed, capture the reason/dependency
        rather than silently dismissing it.

------------------------------------------------------------------------

# 11. Scope / ownership note

The uploaded LENS team specification says LENS owns the **manager
workload & process insights** experience in Phase 2 and explicitly
distinguishes this from Chain's per-task functionality.

Therefore, this Employee UI should be treated as a **product/UX
reference and cross-team handoff example**, unless the final team
ownership plan explicitly assigns its implementation to LENS.

LENS's documented manager experience is focused on: - team workload -
work in progress - blocked/delayed work - bottlenecks - employees who
may need support - aggregate workflow health

It is explicitly not a per-employee productivity score.

------------------------------------------------------------------------

# 12. Source basis

Primary project sources: -
`Intelligent-Work-Prioritization-9Week-Plan.md` - `team-lens.md`

Key source-derived points: - The product's core question is
role-relative: what is the most important work to do during available
time. - The canonical Task model includes `depends_on`, `constraints`,
`status`, `urgency`, `effort_estimate`, `deadline`, and
provenance/confidence. - Mind detects/scores; Chain stores, validates,
resolves and selects. - Employees can provide feedback that refines
stored task data. - LENS aggregates workflow health for managers rather
than resolving individual tasks.
