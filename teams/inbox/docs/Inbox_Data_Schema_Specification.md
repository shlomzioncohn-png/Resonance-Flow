# Intelligent Work Prioritization Platform

# Data Schema Specification

**Canonical relational data model owned by the Inbox team — tenants, users, teams, projects, tasks, assignments and audit trail.**

| Item | Value |
|---|---|
| Owning team | Inbox |
| Database | PostgreSQL |
| Migration tool | Flyway |
| Document type | Technical specification (backend / data layer) |
| Status | Draft — pending approval on open items (see final section) |
| Entities | 11 |
| Audit trail | Included |
| Open items | 3 |

---

## 1. Purpose & Scope

This document specifies the canonical relational data schema owned by the Inbox team, as defined in the Intelligent Work Prioritization Platform. It covers tenants, users, authentication, teams, projects, tasks, worker assignments, and the audit trail. Every other team in the program (Bridge, Mind, Chain, Lens, Relay) builds against this schema as the single source of truth for task data.

Fields are listed with their type, whether they are required, their foreign-key / constraint definition, and a description. Where a field's ownership or semantics is not yet settled between teams, this is noted inline and consolidated in the final section of this document.

---

## 2. Key Design Decisions

| Topic | Decision |
|---|---|
| Team lead | One team lead per team, stored in `teams.team_lead_id`. |
| Permissions | No granular RBAC/permissions schema. `users.role` is a fixed enum (`system_admin` / `tenant_admin` / `team_lead` / `employee`) — it says which of these four kinds of user someone is, not a permissions model; extend later if a real RBAC schema is required. |
| OTP storage | Do not assume Redis. Store an OTP hash and expiration in PostgreSQL unless a different architecture is approved. |
| Task dependencies | Not stored in Inbox. Chain owns the dependency graph. |
| Availability | `tasks.is_available` is materialized in Inbox and updated from Chain. |
| Completion | Do not store `is_completed`; use `status = 'done'`. Keep `completed_at`. |
| Tier | Keep `tier` nullable — it is part of the canonical task model and used by Mind for classification/ranking. |
| Correlation ID | Technical event-correlation value, used by Mind on the unstructured creation path. Stored on `tasks.correlation_id` (nullable) and may also appear in `audit_log`. |

---

## 3. Entity Definitions

### 3.1 TENANTS

*Top-level tenant. Every user, team, project and task belongs to exactly one tenant.*

| Field | Type | Required | FK / Constraints | Description |
|---|---|---|---|---|
| `id` | UUID | Yes | PK | Unique tenant identifier. |
| `name` | VARCHAR(255) | Yes | — | Tenant name. |
| `slug` | VARCHAR(100) | Yes | UNIQUE | Unique textual identifier suitable for URLs/configuration. |
| `status` | VARCHAR(30) | Yes | `active / suspended / archived` | Tenant lifecycle status. |
| `timezone` | VARCHAR(100) | No | — | Default tenant timezone. |
| `default_locale` | VARCHAR(20) | No | — | Default language/locale. |
| `created_at` | TIMESTAMPTZ | Yes | DEFAULT `now()` | Record creation timestamp. |
| `updated_at` | TIMESTAMPTZ | Yes | DEFAULT `now()` | Last update timestamp. |

### 3.2 USERS

*Every person in the system — developer, manager, higher-manager, field engineer, salesperson, or CEO.*

| Field | Type | Required | FK / Constraints | Description |
|---|---|---|---|---|
| `id` | UUID | Yes | PK | Unique user identifier. |
| `tenant_id` | UUID | Yes | FK → `tenants.id` | Tenant the user belongs to. |
| `email` | VARCHAR(255) | Cond. | Recommended UNIQUE when used for login | User email; may also be used as a login identifier. |
| `phone` | VARCHAR(30) | Cond. | Recommended UNIQUE when used for login | User phone number; may be used for OTP login. |
| `username` | VARCHAR(100) | Cond. | UNIQUE | Username when username/password authentication is supported. |
| `first_name` | VARCHAR(100) | No | — | User first name. |
| `last_name` | VARCHAR(100) | No | — | User last name. |
| `display_name` | VARCHAR(255) | Yes | — | Name displayed in the application. |
| `role` | VARCHAR / ENUM | Yes | `system_admin / tenant_admin / team_lead / employee` | Basic organizational/application role. No granular permissions model at this stage. |
| `status` | VARCHAR(30) | Yes | `pending / active / suspended / disabled` | User lifecycle status. |
| `created_at` | TIMESTAMPTZ | Yes | DEFAULT `now()` | Creation timestamp. |
| `updated_at` | TIMESTAMPTZ | Yes | DEFAULT `now()` | Last update timestamp. |
| `last_login_at` | TIMESTAMPTZ | No | — | Last successful login timestamp. |

> **Note:** `users.manager_id` is intentionally not included — see Open Questions.

### 3.3 USER_AUTH_IDENTITIES

*Supports multiple authentication methods per user (password, phone OTP, email OTP).*

| Field | Type | Required | FK / Constraints | Description |
|---|---|---|---|---|
| `id` | UUID | Yes | PK | Authentication identity identifier. |
| `user_id` | UUID | Yes | FK → `users.id` | User owning this authentication identity. |
| `auth_type` | VARCHAR(30) | Yes | `password / phone_otp / email_otp` | Authentication method. |
| `identifier` | VARCHAR(255) | Yes | Recommended UNIQUE with `auth_type` | Username, email, or phone according to `auth_type`. |
| `password_hash` | TEXT | Cond. | Required only when `auth_type = 'password'` | Secure password hash; plaintext passwords are never stored. |
| `is_verified` | BOOLEAN | Yes | DEFAULT `false` | Whether the authentication identifier has been verified. |
| `created_at` | TIMESTAMPTZ | Yes | DEFAULT `now()` | Creation timestamp. |
| `updated_at` | TIMESTAMPTZ | Yes | DEFAULT `now()` | Last update timestamp. |

`is_primary` is intentionally omitted — no current business requirement requires a primary authentication method.

### 3.4 OTP_REQUESTS

*One-time-password requests for login and verification flows. Stored in PostgreSQL, not Redis (see decisions table).*

| Field | Type | Required | FK / Constraints | Description |
|---|---|---|---|---|
| `id` | UUID | Yes | PK | Unique OTP request identifier. |
| `user_id` | UUID | No | FK → `users.id` | User identifier when already known; may be NULL early in the login flow. |
| `identifier` | VARCHAR(255) | Yes | — | Phone number or email to which the OTP was sent. |
| `code_hash` | TEXT | Yes | — | Hash of the OTP code; plaintext OTP is not stored. |
| `purpose` | VARCHAR(30) | Yes | e.g. `login / verify_phone / reset_password` | Purpose of the OTP request. |
| `channel` | VARCHAR(20) | Yes | `sms / email` | OTP delivery channel. |
| `status` | VARCHAR(30) | Yes | `pending / verified / expired / blocked` | OTP lifecycle status. |
| `attempt_count` | SMALLINT | Yes | DEFAULT `0` | Number of verification attempts. |
| `expires_at` | TIMESTAMPTZ | Yes | — | OTP expiration timestamp. |
| `created_at` | TIMESTAMPTZ | Yes | DEFAULT `now()` | Request creation timestamp. |
| `verified_at` | TIMESTAMPTZ | No | — | Successful verification timestamp. |

### 3.5 TEAMS

*One of the six functional/project teams (or any org sub-team); has exactly one team lead.*

| Field | Type | Required | FK / Constraints | Description |
|---|---|---|---|---|
| `id` | UUID | Yes | PK | Unique team identifier. |
| `tenant_id` | UUID | Yes | FK → `tenants.id` | Tenant the team belongs to. |
| `name` | VARCHAR(255) | Yes | — | Team name. |
| `team_lead_id` | UUID | Yes | FK → `users.id` | The team's single team lead. |
| `status` | VARCHAR(30) | Yes | `active / archived` | Team lifecycle status. |
| `created_at` | TIMESTAMPTZ | Yes | DEFAULT `now()` | Creation timestamp. |
| `updated_at` | TIMESTAMPTZ | Yes | DEFAULT `now()` | Last update timestamp. |

### 3.6 TEAM_MEMBERS

*Many-to-many join between users and teams, with membership history.*

| Field | Type | Required | FK / Constraints | Description |
|---|---|---|---|---|
| `team_id` | UUID | Yes | FK → `teams.id`; composite PK with `user_id` | Team identifier. |
| `user_id` | UUID | Yes | FK → `users.id`; composite PK with `team_id` | User belonging to the team. |
| `joined_at` | TIMESTAMPTZ | Yes | DEFAULT `now()` | Membership start timestamp. |
| `left_at` | TIMESTAMPTZ | No | — | Membership end timestamp, if applicable. |

### 3.7 PROJECTS

*Optional grouping of tasks under a named initiative, owned by a user and/or team.*

| Field | Type | Required | FK / Constraints | Description |
|---|---|---|---|---|
| `id` | UUID | Yes | PK | Unique project identifier. |
| `tenant_id` | UUID | Yes | FK → `tenants.id` | Tenant owning the project. |
| `name` | VARCHAR(255) | Yes | — | Project name. |
| `description` | TEXT | No | — | Project description. |
| `owner_id` | UUID | No | FK → `users.id` | Project owner, if required. |
| `team_id` | UUID | No | FK → `teams.id` | Primary team responsible for the project, if applicable. |
| `status` | VARCHAR(30) | Yes | `planned / active / completed / archived` | Project lifecycle status. |
| `start_date` | DATE | No | — | Project start date. |
| `target_date` | DATE | No | — | Target completion date. |
| `created_at` | TIMESTAMPTZ | Yes | DEFAULT `now()` | Creation timestamp. |
| `updated_at` | TIMESTAMPTZ | Yes | DEFAULT `now()` | Last update timestamp. |

### 3.8 TASKS — canonical schema, sole authority: Inbox

*The single canonical task representation every source (GitHub, CRM, email, Slack, WhatsApp, meetings, scanned docs, manual entry) converges on.*

| Field | Type | Required | FK / Constraints | Description |
|---|---|---|---|---|
| `id` | UUID | Yes | PK | Final canonical task ID issued by Inbox. |
| `tenant_id` | UUID | Yes | FK → `tenants.id` | Tenant the task belongs to. |
| `project_id` | UUID | No | FK → `projects.id` | Project associated with the task. |
| `title` | TEXT | Yes | — | Short task title. |
| `description` | TEXT | No | — | Detailed task description. |
| `source_type` | VARCHAR / ENUM | Yes | `github_issue / github_pr / crm_deal / email / slack / whatsapp / meeting / doc / scanned_doc / calendar / manual / jira_issue / zoom / bigbluebutton` | Original source of the task. |
| `source_ref` | TEXT | No | — | Reference to the original source, such as URL, message ID, or issue ID. |
| `correlation_id` | UUID | No | — | Request/response correlation value used by Mind on the unstructured creation path (`task.creation.requested` → `task.created`). NULL for tasks created via the structured path (Bridge) or manually. Technical field, not a business attribute. |
| `tier` | VARCHAR / ENUM | No | `operational / tactical / strategic` | Task level used by Mind for tier-specific classification/ranking. May be NULL before classification. |
| `role_context` | VARCHAR / ENUM | No | `developer / manager / higher_manager / field_engineer / salesperson / ceo` | Which of the six product personas this task belongs to. Not derived from assignment; set at creation/extraction time. May be NULL before classification. |
| `urgency` | SMALLINT | No | CHECK `1..5` | Task urgency level. |
| `effort_estimate_minutes` | INTEGER | No | CHECK `> 0` | Estimated execution time in minutes. |
| `deadline` | TIMESTAMPTZ | No | — | Task deadline. |
| `status` | VARCHAR / ENUM | Yes | `new / in_review / approved / in_progress / snoozed / done / rejected / cancelled` | Task lifecycle state. |
| `is_available` | BOOLEAN | Yes | DEFAULT `false` or agreed default | Whether the task is currently available for execution. Calculated by Chain and materialized in Inbox. |
| `priority_score` | REAL | No | CHECK `1..10` | Latest prioritization/scheduling score used by the task experience. Produced by Chain and materialized in Inbox, like `is_available`. Fractional values allowed; higher means more important. NULL until Chain has scored the task. |
| `snoozed_until` | TIMESTAMPTZ | No | Required when `status = 'snoozed'` | Time until which the task is deferred. |
| `extraction_provenance` | VARCHAR / ENUM | Yes | `etl / ml_inferred / manual` | How the task was created. |
| `confidence` | REAL | Cond. | CHECK `0..1`; required for ML-inferred tasks | Confidence score for ML-inferred task data. |
| `owner_id` | UUID | No | FK → `users.id` | The single user who manages/follows the task — the accountable owner, distinct from whoever the task is assigned to via ASSIGNMENTS. May be NULL before triage/claim. |
| `effort_estimate_confidence` | REAL | No | CHECK `0..1` | Confidence score for the ML-inferred `effort_estimate_minutes` value. |
| `suggested_owner_user_id` | UUID | No | FK → `users.id` | ML-suggested owner proposed by Mind. Distinct from `owner_id`, which is the confirmed accountable owner — this is a proposal, not a decision. |
| `suggested_owner_confidence` | REAL | No | CHECK `0..1` | Confidence score for `suggested_owner_user_id`. |
| `created_by` | UUID | No | FK → `users.id` | User who created the task. Sent by the originating system on every ingestion path — Bridge (`bridge.*.tasks`) and Mind (`task.creation.requested`) each include the acting user's id, same as manual creation. NULL only when the source has no identifiable user (e.g. a fully automated integration with no acting user). |
| `created_at` | TIMESTAMPTZ | Yes | DEFAULT `now()` | Creation timestamp. |
| `updated_at` | TIMESTAMPTZ | Yes | DEFAULT `now()` | Last update timestamp. |
| `completed_at` | TIMESTAMPTZ | No | — | Completion timestamp. `is_completed` is intentionally not stored because `status = 'done'` is the source of truth. |
| `metadata` | JSONB | No | — | Free-form structured metadata attached to the task. No fixed schema. |

#### 3.8.1 Task / Chain data ownership

| Data | Type / Structure | Stored by | Description |
|---|---|---|---|
| `is_available` | BOOLEAN | Inbox | Materialized availability state, calculated by Chain. |
| `priority_score` | REAL | Inbox | Latest prioritization/scheduling score, `1..10`, higher is more important. Calculated by Chain, materialized in Inbox. Delivered on `task.scheduling.updated`; omitting it leaves the stored value unchanged. |
| Task dependencies | Dependency graph | Chain | Blocking relationships between tasks; not stored in Inbox. |
| Blocker resolution | Chain service logic | Chain | Resolves dependency state and recalculates task availability. |
| Parent / child hierarchy | `parent_task_id UUID`, or Chain hierarchy | TBD | Structural task hierarchy. Open ownership decision. |

### 3.9 ASSIGNMENTS

*Who a task is intended for — unified table covering both user-level and team-level assignment. Exactly one of `user_id` / `team_id` is set per row.*

Distinct from `tasks.owner_id`, which is the accountable owner/manager of the task, not necessarily who it is assigned to.

| Field | Type | Required | FK / Constraints | Description |
|---|---|---|---|---|
| `id` | UUID | Yes | PK | Unique assignment record identifier. |
| `task_id` | UUID | Yes | FK → `tasks.id` | Task being assigned. |
| `user_id` | UUID | Cond. | FK → `users.id`; NULL unless user assignment | User assigned to the task. Mutually exclusive with `team_id`. |
| `team_id` | UUID | Cond. | FK → `teams.id`; NULL unless team assignment | Team assigned to the task. Mutually exclusive with `user_id`. |
| `assignment_role` | ENUM | Yes | `owner / assignee / responsible / collaborator` | The assignee's role on the task. |
| `assigned_at` | TIMESTAMPTZ | Yes | DEFAULT `now()` | Assignment timestamp. |
| `assigned_by` | UUID | No | FK → `users.id` | User who made the assignment; NULL for automatic assignment. |

Required constraints:

```sql
CHECK (
    (user_id IS NOT NULL AND team_id IS NULL)
    OR
    (user_id IS NULL AND team_id IS NOT NULL)
);
```

Exactly one of the two must be set.

Use two partial unique indexes:

```sql
UNIQUE(task_id, user_id) WHERE user_id IS NOT NULL;
UNIQUE(task_id, team_id) WHERE team_id IS NOT NULL;
```

**How assignments are written:**

- **Create** - `POST /api/v1/tasks` takes an optional `assignees` list; one row is saved per entry, together with the task. The Kafka ingestion paths (`bridge.structured.tasks`, `task.creation.requested`) do the same from their own `assignees` list.
- **Read** - every task response carries its `assignees`.
- **Update** - `PATCH /api/v1/tasks/{id}` with `assignees` replaces the task's full set; without `assignees` the set is left untouched. An assignee already on the task keeps its row (`assigned_at`, `assigned_by` unchanged); if only its role changed, the role is updated in place. Rows not in the new list are deleted; new entries are inserted. The ingestion paths still write assignments at creation only.
- **Delete** - there is no task delete yet. `task_id` is `ON DELETE CASCADE`, so any future task delete removes its assignments with it.
- **Validation** - an assigned user or team must exist (`404`) and belong to the task's tenant (`403`); the same user or team listed twice is rejected (`400`). On the REST path, `assigned_by` is the acting user.

### 3.10 TASK_FEEDBACK

*Phase 2 — the employee feedback loop: free-text responses to a task that feed back into stored task data and future extraction/scoring, not just the current session.*

| Field | Type | Required | FK / Constraints | Description |
|---|---|---|---|---|
| `id` | UUID | Yes | PK | Unique feedback record identifier. |
| `task_id` | UUID | Yes | FK → `tasks.id` | Task the feedback relates to. |
| `user_id` | UUID | Yes | FK → `users.id` | User who gave the feedback. |
| `feedback_text` | TEXT | Yes | NOT NULL | The feedback itself, as free text. A row is written only when non-blank text was sent. |
| `created_at` | TIMESTAMPTZ | Yes | DEFAULT `now()` | Feedback timestamp. |

**Resolved:** a new `TASK_FEEDBACK` row is written by Inbox and propagates to Mind through the existing `task.updated` topic rather than a new dedicated topic. Mind consumes it there to refine future extraction/scoring.

If feedback also mutates a `TASKS` field directly, for example `status → in_review`, that change rides the same `task.updated` event — no separate signal is needed.

**Current scope:** feedback is accepted only together with a status change to `rejected` (`PATCH /api/v1/tasks/{id}/status`), and is optional there; blank text counts as no feedback. The `task.updated` event of that change carries it in two optional fields - `feedback_text`, `feedback_user_id` - which are absent from every `task.updated` event that has no feedback. The text is also written to the `audit_log.new_values` of that status change.

### 3.11 AUDIT_LOG

*Append-only change history, owned entirely by Inbox, covering every entity type in this schema.*

| Field | Type | Required | FK / Constraints | Description |
|---|---|---|---|---|
| `id` | BIGINT IDENTITY | Yes | PK | Sequential audit record identifier. |
| `tenant_id` | UUID | No | FK → `tenants.id` | Tenant in which the action occurred. |
| `actor_user_id` | UUID | No | FK → `users.id` | User who performed the action; NULL for system-driven actions. |
| `action` | VARCHAR(100) | Yes | e.g. `user.login / task.created / task.updated / task.completed / task.reassigned` | Type of action performed. |
| `entity_type` | VARCHAR(50) | Yes | e.g. `user / team / project / task` | Type of affected entity. |
| `entity_id` | UUID | No | — | Identifier of the affected entity. |
| `old_values` | JSONB | No | — | Relevant values before the change. |
| `new_values` | JSONB | No | — | Relevant values after the change. |
| `metadata` | JSONB | No | — | Additional technical context such as source service or Kafka event. |
| `correlation_id` | UUID | No | — | Correlates asynchronous request/response events for tracing/debugging. Not a business task field. |
| `ip_address` | INET | No | — | Client IP address when relevant. |
| `user_agent` | TEXT | No | — | Client/browser information when relevant. |
| `created_at` | TIMESTAMPTZ | Yes | DEFAULT `now()`; append-only | Audit event timestamp. |

> **Note:** This JSONB-based, per-action design (one row per change, covering all field diffs together) differs from an alternative per-field design (one row per individual field changed) also discussed for this schema — see Open Questions for the trade-off.

**What is written (`AuditService`):** every service builds an explicit *snapshot* of its entity - a fixed list of fields, never the whole object - and the row is written from it.

| | `old_values` | `new_values` |
|---|---|---|
| Creation / addition (`*_CREATED`, `TEAM_MEMBER_ADDED`) | NULL | Full snapshot of the new record. |
| Update (`*_UPDATED`, `*_STATUS_UPDATED`, `TASK_COMPLETED`, `TASK_SCHEDULING_UPDATED`, `TEAM_MEMBER_REMOVED`) | Only the fields whose value changed, each with the value it had **before** the change. | Full snapshot **after** the change. |

- An update that changes nothing in the snapshot writes **no row** - the trail records changes, not requests. This is why a snapshot must list every field an update can change: a field left out of it would make an update of only that field go unaudited.
- A field that appears in `old_values` is, by definition, a field that changed in that action; its new value is the same key in `new_values`.
- Enum values are stored by name (`"IN_PROGRESS"`, `"EMPLOYEE"`), related records by id only (`ownerId`, `projectId`, `teamLeadId`).
- `TASKS` snapshot: `title`, `description`, `sourceType`, `sourceRef`, `status`, `tier`, `roleContext`, `urgency`, `effortEstimateMinutes`, `effortEstimateConfidence`, `deadline`, `snoozedUntil`, `completedAt`, `projectId`, `ownerId`, `suggestedOwnerUserId`, `suggestedOwnerConfidence`. `TASK_SCHEDULING_UPDATED` rows carry `isAvailable` and `priorityScore` instead.
- `TASK_CREATED` additionally carries `assignments` in `new_values`: one `{userId, teamId, assignmentRole, assignedById}` object per `ASSIGNMENTS` row saved with the task, an empty list when there are none. `TASK_UPDATED` carries the same `assignments` list in its snapshot, so a change of assignees alone is audited like any other field change. Assignments have no audit row of their own.
- Appointing a team lead writes up to three rows: the `TEAM_CREATED` / `TEAM_UPDATED` row itself, a `TEAM_MEMBER_ADDED` row if the lead was not already a member, and a `USER_UPDATED` row if the lead was promoted from `EMPLOYEE` to `TEAM_LEAD`.
- Rows written before this behaviour was introduced have `old_values` NULL on updates too, and a narrower `TASKS` snapshot (`title`, `sourceType`, `sourceRef`, `status`).

---

## 4. Schema Relationships

| Source | Relationship | Target | FK / Join |
|---|---|---|---|
| TENANTS | 1 → N | USERS | `users.tenant_id` |
| TENANTS | 1 → N | TEAMS | `teams.tenant_id` |
| TENANTS | 1 → N | PROJECTS | `projects.tenant_id` |
| TENANTS | 1 → N | TASKS | `tasks.tenant_id` |
| USERS | 1 → N | USER_AUTH_IDENTITIES | `user_auth_identities.user_id` |
| USERS | 1 → N | OTP_REQUESTS | `otp_requests.user_id` |
| USERS | N ↔ N | TEAMS | `TEAM_MEMBERS` |
| USERS | 1 → N | TEAMS | `teams.team_lead_id` |
| PROJECTS | 1 → N | TASKS | `tasks.project_id` |
| USERS | 1 → N | TASKS | `tasks.owner_id` (single owner) |
| TASKS | 1 → N | ASSIGNMENTS | `assignments.task_id` (exactly one of `user_id`/`team_id` per row) |
| TASKS | 1 → N | TASK_FEEDBACK | `task_feedback.task_id` |
| CHAIN | calculates | TASKS | `tasks.is_available` |
| CHAIN | owns | Dependency Graph | Chain-owned storage |
| Relevant services write | — | AUDIT_LOG | `entity_type`, `entity_id`, `correlation_id` |

---

## 5. Open Questions & Points Requiring Approval

The following items are not fully resolved by this schema and require an explicit decision from the Inbox team, mentors, or the relevant cross-team owner (Chain / Mind) before implementation proceeds.

### DESIGN TRADE-OFF — Audit trail granularity: JSONB vs. per-field rows

This schema stores `old_values` / `new_values` as JSONB per action — one row per change event, covering all fields changed together.

An alternative, per-field design — one row per individual field changed, with plain `changed_field / old_value / new_value` columns — has also been discussed and is simpler to query for single-field history but does not generalize as cleanly across entity types.

**Decision needed:** confirm JSONB is the adopted approach. If per-field query performance for a specific UI (e.g. status-change timeline) is required, consider a GIN index on `old_values` / `new_values`, or a supplementary per-field table for TASKS specifically.

*Current state, not a decision:* the code writes to the JSONB columns, and `old_values` is filled on every update (see section 3.11, "What is written"). That implements the existing design; it does not settle the choice above.

### OPEN IN SCHEMA — Parent/child task hierarchy ownership

Explicitly marked TBD in this schema: should `parent_task_id` live on TASKS (Inbox), or should the entire parent/child hierarchy move to Chain's dependency graph, alongside same-tier `depends_on` edges?

**Decision needed:** agree with Chain team lead before Chain's Phase 2 cross-tier traceability work begins, since it depends on this ownership being settled.

### OPEN IN SCHEMA — Direct manager vs. team lead

`users.manager_id` is not included. Manager is currently derivable only via:

`user → team_members → teams → team_lead_id`

**Decision needed:** is this derivation sufficient for the manager workspace (Lens) and role-relative prioritization (Mind), or are there cases where a user's direct manager differs from their team lead?

---

**Inbox Team — Data Schema Specification**  
**PostgreSQL / Flyway — Prepared for internal review**
