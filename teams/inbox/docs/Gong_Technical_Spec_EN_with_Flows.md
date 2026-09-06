# Technical Specification --- Data Flow Between Project Teams

**Focus:** INBOX team and its interfaces with BRIDGE, MIND, and CHAIN

## 1. Background and Purpose

This document consolidates all decisions made regarding how a task flows
through the system --- from the moment raw content enters the system
(via BRIDGE), through extraction/understanding (MIND), storage in the
canonical schema (INBOX), dependency management (CHAIN), and finally
presentation to the employee and task completion.

The document is intended to serve as a basis for both business
specification (for management) and technical specification (for the
development team).

The document is structured as a sequence of stages. Each stage
specifies: who performs the action, what triggers it, what content is
transferred, and who consumes the result.

## 2. INBOX Team Responsibilities

INBOX is the sole owner (**single source of authority**) of the
canonical schema and, accordingly, is the only component authorized to
issue task IDs (`task_id`).

The team is responsible for the following entities:

-   Users
-   Permissions
-   Teams
-   Tasks --- canonical schema
-   Tenants
-   Projects
-   Change history (Audit Trail) --- for every change to every field in
    a task record

## 3. Teams Involved in the Flow

  -----------------------------------------------------------------------
  Team                                Role in the Flow
  ----------------------------------- -----------------------------------
  **BRIDGE**                          Integrates with external platforms.
                                      For structured sources, performs
                                      deterministic ETL and publishes
                                      directly to Kafka
                                      (`bridge.*.tasks`) using its own
                                      IDs; for unstructured sources,
                                      lands raw content in MinIO for
                                      processing by MIND.

  **MIND**                            Reads unstructured raw content,
                                      extracts and identifies a task
                                      including proposed dependencies;
                                      asynchronously publishes a task
                                      creation request to Kafka and
                                      receives a response back from
                                      INBOX; maintains a Vector Store and
                                      internal lookup for dependency
                                      detection across sources.

  **INBOX**                           Owns the canonical schema and
                                      `task_id` issuance; creates task
                                      records, determines initial
                                      availability status, records the
                                      Audit Trail, and serves an
                                      available task to the UI.

  **CHAIN**                           Stores the actual dependency graph
                                      (dependencies proposed by MIND),
                                      manages availability, and publishes
                                      scheduling/availability updates
                                      back to INBOX.

  **RELAY**                           Owns the shared infrastructure ---
                                      Event Bus (Kafka), K8s, and CI/CD
                                      --- on which communication between
                                      teams runs.

  **LENS**                            Consumes INBOX data for the manager
                                      view; provides the shared UI
                                      component library, including the
                                      generic search component.
  -----------------------------------------------------------------------

## 4. Two Task Ingestion Paths

There is a fundamental distinction between two source types, which
affects the entire decision chain described in this document:

  ---------------------------------------------------------------------------
  Path                    Who Creates the Task        Does MIND See the
                                                      Details During
                                                      Creation?
  ----------------------- --------------------------- -----------------------
  **Structured source     BRIDGE publishes to         No --- MIND is not
  (GitHub, CRM)**         `bridge.*.tasks` in Kafka   involved in creation at
                          using its own deterministic all; it learns about
                          IDs; INBOX consumes the     the task only through
                          event and creates the       `task.created`.
                          record.                     

  **Unstructured source   MIND extracts the task and  Yes --- MIND initiated
  (Slack, Email,          publishes                   the request, but
  Meeting)**              `task.creation.requested`   receives final
                          to Kafka; INBOX creates the confirmation with the
                          task and publishes          `task_id` only when
                          `task.created` back.        `task.created` returns.
  ---------------------------------------------------------------------------

## 5. Full Flow --- What Happens at Each Stage

The flow is divided into four high-level stages:

1.  Task creation
2.  Indexing in MIND
3.  Dependency management in CHAIN
4.  UI usage and task completion

### Stage A --- Task Creation (Asynchronous)

#### Step 1 --- Full Extraction in MIND

For an unstructured source, MIND completes full extraction of the task
details:

-   `title`
-   `description`
-   `source_type`
-   `source_ref`
-   List of proposed dependencies --- the `task_id` values of the tasks
    targeted by the dependency, after they have already been resolved
    against MIND's internal lookup / Vector Store
-   Boolean field `has_dependencies` --- indicates whether any
    dependency exists, without including the dependency details
    themselves

#### Step 2 --- Publish `task.creation.requested`

MIND publishes an asynchronous Kafka message named
`task.creation.requested`, containing all extracted details, including
`has_dependencies`, as well as a `correlation_id`.

The `correlation_id` acts as a **request/response correlation**
mechanism: it allows MIND to later associate the request it sent with
the `task.created` event returned to it.

#### Step 3 --- Atomic Creation, `task_id` Issuance, and Publishing `task.created`

INBOX consumes `task.creation.requested` and creates a complete task
record atomically.

Based on `has_dependencies`, INBOX determines the initial availability
state:

-   `false` → the task is immediately available.
-   `true` → the task is blocked and waits for an explicit update from
    CHAIN.

INBOX issues the final `task_id`, writes the initial Audit record in the
same transaction, and publishes `task.created` to Kafka with the final
`task_id` and the original `correlation_id` --- this is the asynchronous
response to MIND.

#### Step 4 --- Structured Path, Without a Shared Endpoint

For a structured source (GitHub/CRM), BRIDGE publishes directly to Kafka
(`bridge.*.tasks`) using its own deterministic IDs.

INBOX consumes `bridge.*.tasks` directly and creates the record.

There is no `correlation_id` in this path because there is no
request/response scenario with BRIDGE.

After creation, INBOX publishes `task.created` exactly as it does for
the unstructured path --- therefore both paths converge at the same
output point.

### Stage B --- Indexing in MIND

#### Step 5 --- Indexing in MIND, Always Through `task.created`

MIND consumes `task.created` for every new task, from both source types:

-   For a task that MIND itself requested to create (the unstructured
    path), it identifies the task using `correlation_id`, thereby
    completing the request/response cycle.
-   For a task from a structured source (which MIND has not seen
    before), this is the only way MIND learns that the task exists.

MIND indexing is no longer immediate even for tasks that MIND created
itself --- it always waits for the complete Kafka round trip:

`task.creation.requested → task.created`

This is an intentional and expected trade-off of the fully asynchronous
flow.

#### Step 6 --- `task.updated`, for Every Later Update

For any later change to an existing task --- an employee edit through
the UI, a feedback-loop action, a status change, and so on ---
regardless of source, INBOX publishes `task.updated`.

MIND consumes the event and updates its index accordingly.

MIND must consume `task.updated` for all tasks, including tasks that it
originally created.

### Stage C --- Dependency Management in CHAIN

#### Step 7 --- Publish Detailed Dependencies

After Step 3 is complete, when MIND already holds a final `task_id` both
for the task itself and for the dependency targets, MIND publishes a
separate Kafka message named `task.dependencies.proposed`.

The message contains:

-   The task's `task_id`
-   A list of the `task_id` values of the tasks it depends on

This message always arrives with final IDs only --- there is no
intermediate state or temporary ID requiring later reconciliation on the
CHAIN side.

#### Step 8 --- Store the Dependency Graph

CHAIN consumes the message and stores the dependency structure in its
graph.

#### Step 9 --- Scheduling and Availability Update

CHAIN publishes `task.scheduling.updated` with three fields:

-   `task_id`
-   `is_available` --- boolean
-   `priority_score`

CHAIN publishes the update both when a dependency is resolved (the
blocking task is completed) and when the task's `priority_score`
changes.

#### Step 10 --- Update INBOX

INBOX consumes `task.scheduling.updated`, updates the task record with
the actual availability state and the `priority_score` if it is stored
on the task record, and writes an additional Audit record documenting
the change.

Change source: `CHAIN`.

### Stage D --- UI Usage and Completion

#### Step 11 --- Requesting a Task from the UI

An employee enters their personal UI (**Personal Task Inbox**, owned by
INBOX) and requests a task suitable for their available time window.

INBOX returns an available task that fits the defined time window.

#### Step 12 --- Task Completion

When the task is actually completed --- by the employee or automatically
--- INBOX publishes `task.completed` to Kafka, containing:

-   `task_id`
-   Boolean field `is_completed`

INBOX also writes a closing Audit record documenting task completion,
including who completed it.

## 6. Unified Topics and API Table

  ----------------------------------------------------------------------------------------
  Channel                        Publisher         Consumer(s)       Main Content
  ------------------------------ ----------------- ----------------- ---------------------
  `task.creation.requested`      MIND              INBOX             Full task details,
                                                                     `has_dependencies`,
                                                                     `correlation_id` for
                                                                     request/response
                                                                     correlation

  `bridge.*.tasks`               BRIDGE            INBOX             Structured-source
                                                                     task details, BRIDGE
                                                                     deterministic ID

  `task.created`                 INBOX             MIND              Final `task_id` +
                                                                     `correlation_id` when
                                                                     present + task
                                                                     details --- for all
                                                                     tasks from both paths

  `task.updated`                 INBOX             MIND              `task_id` + updated
                                                                     details --- all
                                                                     tasks, from every
                                                                     source

  `task.dependencies.proposed`   MIND              CHAIN             `task_id` + list of
                                                                     dependency target
                                                                     `task_id` values

  `task.scheduling.updated`      CHAIN             INBOX             `task_id` +
                                                                     `is_available` +
                                                                     `priority_score`

  `task.completed`               INBOX             Whole system      `task_id` +
                                                                     `is_completed`
  ----------------------------------------------------------------------------------------

## 7. Audit Trail --- Change History

Every change to a task record --- without exception --- is recorded as a
separate row in a dedicated history table, within the same transaction
in which the update itself is performed.

This guarantees that an update cannot occur without a corresponding
Audit record, or vice versa.

  -----------------------------------------------------------------------
  Field                               Description
  ----------------------------------- -----------------------------------
  `id`                                Audit record ID

  `task_id`                           The task on which the change was
                                      made

  `changed_field`                     The field that changed (`status`,
                                      `owner`, `availability`, etc.)

  `old_value`                         Previous value

  `new_value`                         New value

  `changed_by`                        Who/what made the change ---
                                      `user_id`, or service name if the
                                      change was automatic (`MIND`,
                                      `CHAIN`, `system`)

  `changed_at`                        Timestamp

  `source_event`                      Optional, for debugging --- the
                                      topic/call that caused the change,
                                      e.g. `task.scheduling.updated`
  -----------------------------------------------------------------------

### Triggers for Writing an Audit Record

-   `task.creation.requested` / `bridge.*.tasks` --- creation: initial
    `created` record, with `old_value = null`.
-   `task.scheduling.updated` from CHAIN --- change to `status` /
    `availability` / `priority_score`, with `changed_by = CHAIN`.
-   Employee action in the UI (`approve` / `reject` / `complete` /
    `snooze` --- Phase 2) --- with `changed_by = user_id`.
-   Feedback loop (Phase 2) --- field update following employee
    feedback.
-   `task.completed` --- closing `completed` record.

------------------------------------------------------------------------

# Flow Diagrams

## 1. UNSTRUCTURED SOURCE --- Slack / Email / Meeting

``` text
Slack / Email / Meeting
          |
          v
       BRIDGE
          |
          +-- Land raw content in MinIO
          |
          v
        MIND
          |
          +-- Full extraction
          |     +-- title
          |     +-- description
          |     +-- source_type
          |     +-- source_ref
          |     +-- proposed dependencies
          |     +-- has_dependencies
          |
          +-- Resolve dependency targets
          |   against internal lookup / vector store
          |
          | Kafka:
          | task.creation.requested
          |
          | {
          |   task details,
          |   has_dependencies,
          |   correlation_id
          | }
          v
        INBOX
          |
          +-- Create task atomically
          +-- Issue final task_id
          +-- Write initial Audit
          |
          +-- Initial availability:
          |     has_dependencies = false -> available
          |     has_dependencies = true  -> blocked
          |
          | Kafka:
          | task.created
          |
          | {
          |   task_id,
          |   correlation_id,
          |   task details
          | }
          v
        MIND
          |
          +-- Match correlation_id
          +-- Receive final task_id
          +-- Index task
          |
          | Kafka:
          | task.dependencies.proposed
          |
          | {
          |   task_id,
          |   dependency_task_ids[]
          | }
          v
        CHAIN
          |
          +-- Store dependency graph
```

## 2. STRUCTURED SOURCE --- GitHub / CRM

``` text
GitHub / CRM
      |
      v
   BRIDGE
      |
      +-- Deterministic ETL
      +-- Map to canonical Task
      +-- Generate deterministic source ID
      |
      | Kafka:
      | bridge.*.tasks
      |
      | {
      |   task details,
      |   BRIDGE deterministic id
      | }
      v
    INBOX
      |
      +-- Consume bridge.*.tasks
      +-- Create canonical task
      +-- Issue final task_id
      +-- Write initial Audit
      |
      | Kafka:
      | task.created
      |
      | {
      |   task_id,
      |   task details
      | }
      v
     MIND
      |
      +-- Index only (no extraction)
      +-- Duplicate detection
      +-- Dependency mapping
      |
      | Kafka:
      | task.dependencies.proposed
      |
      | {
      |   task_id,
      |   dependency_task_ids[]
      | }
      v
    CHAIN
      |
      +-- Store dependency graph
```

## 3. TASK CREATION --- BOTH PATHS CONVERGE

``` text
              UNSTRUCTURED                         STRUCTURED
                   |                                   |
                   v                                   v
                BRIDGE                              BRIDGE
                   |                                   |
                   v                                   |
                 MIND                                  |
                   |                                   |
                   | task.creation.requested           | bridge.*.tasks
                   | + correlation_id                  |
                   |                                   |
                   +---------------+   +---------------+
                                   v   v
                                  INBOX
                                    |
                                    +-- Create canonical Task
                                    +-- Issue task_id
                                    +-- Initial availability
                                    +-- Audit
                                    |
                                    | Kafka:
                                    | task.created
                                    v
                                   MIND
                                    |
                                    +-- Index
                                    +-- Duplicate detection
                                    +-- Dependency mapping
                                    |
                                    | task.dependencies.proposed
                                    v
                                  CHAIN
```

## 4. TASK UPDATE

``` text
Employee / UI
      |
      +-- Edit title
      +-- Edit description
      +-- Change status
      +-- Reassign
      +-- Snooze
      +-- Other task changes
      |
      v
    INBOX
      |
      +-- Update canonical Task
      +-- Write Audit
      |
      | Kafka:
      | task.updated
      |
      | {
      |   task_id,
      |   updated details
      | }
      v
     MIND
      |
      +-- Update index
```

## 5. DEPENDENCY / SCHEDULING

``` text
MIND
  |
  | Kafka:
  | task.dependencies.proposed
  |
  | {
  |   task_id,
  |   dependency_task_ids[]
  | }
  v
CHAIN
  |
  +-- Store dependency graph
  +-- Resolve dependency state
  +-- Calculate availability
  +-- Calculate / update priority
  |
  | Kafka:
  | task.scheduling.updated
  |
  | {
  |   task_id,
  |   is_available,
  |   priority_score
  | }
  v
INBOX
  |
  +-- Update is_available
  +-- Update priority_score
  +-- Write Audit
```

## 6. TASK COMPLETION

``` text
Employee / Automatic process
            |
            v
          INBOX
            |
            +-- Mark task completed
            +-- Write closing Audit
            |
            | Kafka:
            | task.completed
            |
            | {
            |   task_id,
            |   is_completed
            | }
            v
      THE WHOLE SYSTEM
            |
            +----------> MIND
            +----------> CHAIN
            +----------> Other relevant consumers
```

## 7. FULL TASK LIFECYCLE

``` text
      UNSTRUCTURED                         STRUCTURED
           |                                   |
           v                                   v
        BRIDGE                              BRIDGE
           |                                   |
           v                                   |
         MIND                                  |
           |                                   |
           | task.creation.requested           | bridge.*.tasks
           | + correlation_id                  |
           |                                   |
           +---------------+   +---------------+
                           v   v
                          INBOX
                            |
                            +-- Create Task
                            +-- task_id
                            +-- availability
                            +-- Audit
                            |
                            | task.created
                            v
                           MIND
                            |
                            +-- Index
                            +-- Duplicate detection
                            +-- Dependency mapping
                            |
                            | task.dependencies.proposed
                            v
                          CHAIN
                            |
                            +-- Dependency graph
                            +-- Availability
                            +-- Priority
                            |
                            | task.scheduling.updated
                            v
                          INBOX
                            |
                            +-- availability
                            +-- priority_score
                            +-- Audit
                            |
                            v
                    PERSONAL TASK INBOX
                            |
                            v
                          USER
                            |
                            +-- Work
                            +-- Edit
                            +-- Reassign
                            +-- Snooze
                            +-- Complete
                            |
                            v
                          INBOX
                            |
              +-------------+------------------+
              |                                |
              | task.updated                   | task.completed
              v                                v
             MIND                       THE WHOLE SYSTEM
              |
              +-- Update index
```
