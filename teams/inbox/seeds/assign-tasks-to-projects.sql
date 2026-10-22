-- Gives every existing task a project. Run it after load-seeds.sql (or on any database that
-- already has tenants, teams and tasks), from anywhere:
--
--   psql -h localhost -p ${DB_PORT:-5432} -U ${DB_USER:-postgres} -d ${DB_NAME:-resonance_flow} \
--        -f teams/inbox/seeds/assign-tasks-to-projects.sql
--
-- 1. Every team gets two projects, named like Lens's Manager Dashboard mock projects
--    (teams/lens/bff/.../client/ManagerClient.getProjects()), owned by the team's lead.
--    A tenant with more than six teams wraps around the list: "Checkout Revamp 2", ...
-- 2. Every task with no project yet is placed in one of its team's two projects. The task's team
--    is taken from its assignments, in this order:
--      a team assignment -> the team itself
--      a user assignment -> the team that user is a member of
--    preferring the 'owner' assignment, then the earliest one. Without either, the team of the
--    task's owner_id, then of created_by.
-- 3. A task with no team at all goes to its tenant's "General Backlog" project (no team, no owner),
--    created only for tenants that actually have such tasks.
--
-- Tasks that already have a project_id are left alone, and every project id is derived from the
-- team / tenant id, so running this twice changes nothing.

\set ON_ERROR_STOP on
BEGIN;

-- Lens's 12 mock projects, as names/descriptions to hand out to teams.
CREATE TEMP TABLE lens_project_names (n INT PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL) ON COMMIT DROP;
INSERT INTO lens_project_names (n, name, description) VALUES
    (0,  'Checkout Revamp',        'Modernizing the checkout flow with improved UX and faster processing.'),
    (1,  'Fraud Detection API',    'Implementing ML-based fraud detection with real-time alerting.'),
    (2,  'Mobile Onboarding',      'New mobile app onboarding flow for the next launch.'),
    (3,  'Search Relevance v2',    'Second iteration of search ranking: better signals, faster index and offline evaluation.'),
    (4,  'Billing Reconciliation', 'Automated daily reconciliation between the billing ledger and payment provider settlements.'),
    (5,  'Design System Rollout',  'Rolling out the shared component library and design tokens across all product surfaces.'),
    (6,  'CRM Sync Pipeline',      'Two-way sync of deals and contacts between the CRM and the platform.'),
    (7,  'Notification Center',    'In-app notification inbox with user preferences and digest emails.'),
    (8,  'Vendor Risk Scoring',    'Scoring third-party vendors by security posture, financial health and data access.'),
    (9,  'Internal Analytics Hub', 'Self-serve internal analytics: shared metrics layer and curated dashboards.'),
    (10, 'GitHub App Migration',   'Moving the GitHub integration from OAuth tokens to a GitHub App with fine-grained permissions.'),
    (11, 'Legacy Contract Import', 'Importing scanned legacy contracts into the platform.');

-- 1. Two projects per team -------------------------------------------------------------------
-- slot k = the k-th project of the tenant (teams in natural name order, "Team 2" before
-- "Team 10"), so two teams of the same tenant never get the same name.
INSERT INTO projects (id, tenant_id, name, description, owner_id, team_id, status, start_date, target_date)
SELECT md5(t.id::text || ':project:' || s.i)::uuid,
       t.tenant_id,
       l.name || CASE WHEN slot >= 12 THEN ' ' || (slot / 12 + 1) ELSE '' END,
       l.description,
       t.team_lead_id,
       t.id,
       CASE WHEN t.status = 'archived' THEN 'archived' ELSE 'active' END::project_status,
       current_date - (30 + (slot % 5) * 15),
       current_date + (45 + (slot % 4) * 30)
FROM (SELECT tm.*, row_number() OVER (PARTITION BY tm.tenant_id ORDER BY length(tm.name), tm.name, tm.id) - 1 AS team_no
      FROM teams tm) t
CROSS JOIN generate_series(0, 1) AS s(i)
CROSS JOIN LATERAL (SELECT (t.team_no * 2 + s.i)::int AS slot) k
JOIN lens_project_names l ON l.n = k.slot % 12
ON CONFLICT DO NOTHING;

-- 2. Each project-less task's team -------------------------------------------------------------
CREATE TEMP TABLE task_team ON COMMIT DROP AS
SELECT t.id AS task_id,
       t.tenant_id,
       COALESCE(
           (SELECT COALESCE(a.team_id, um.team_id)
            FROM assignments a
            LEFT JOIN LATERAL (SELECT m.team_id FROM team_members m
                               WHERE m.user_id = a.user_id AND m.left_at IS NULL
                               ORDER BY m.joined_at, m.team_id LIMIT 1) um ON a.user_id IS NOT NULL
            WHERE a.task_id = t.id AND COALESCE(a.team_id, um.team_id) IS NOT NULL
            ORDER BY (a.assignment_role = 'owner') DESC, a.assigned_at, a.id
            LIMIT 1),
           (SELECT m.team_id FROM team_members m
            WHERE m.user_id = t.owner_id AND m.left_at IS NULL ORDER BY m.joined_at, m.team_id LIMIT 1),
           (SELECT m.team_id FROM team_members m
            WHERE m.user_id = t.created_by AND m.left_at IS NULL ORDER BY m.joined_at, m.team_id LIMIT 1)
       ) AS team_id
FROM tasks t
WHERE t.project_id IS NULL;

-- 3. "General Backlog" for tenants with tasks that have no team ---------------------------------
INSERT INTO projects (id, tenant_id, name, description, status, start_date)
SELECT DISTINCT md5(tt.tenant_id::text || ':general-backlog')::uuid,
       tt.tenant_id,
       'General Backlog',
       'Tasks not tied to any team yet.',
       'active'::project_status,
       current_date - 30
FROM task_team tt
WHERE tt.team_id IS NULL
ON CONFLICT DO NOTHING;

-- Assigning a project is not activity on the task, so it must not bump tasks.updated_at.
ALTER TABLE tasks DISABLE TRIGGER trg_tasks_set_updated_at;

-- Which of the team's two projects is fixed by the task id, so roughly half go to each.
UPDATE tasks t
SET project_id = CASE
        WHEN tt.team_id IS NULL THEN md5(tt.tenant_id::text || ':general-backlog')::uuid
        ELSE md5(tt.team_id::text || ':project:' || (abs(hashtext(t.id::text)) % 2))::uuid
    END
FROM task_team tt
WHERE tt.task_id = t.id;

ALTER TABLE tasks ENABLE TRIGGER trg_tasks_set_updated_at;

COMMIT;

-- Sanity check - no task should be left without a project.
SELECT count(*)                                    AS tasks,
       count(*) FILTER (WHERE project_id IS NULL)  AS without_project,
       count(DISTINCT project_id)                  AS projects_used
FROM tasks;

SELECT tn.name AS tenant, p.name AS project, tm.name AS team, count(t.id) AS tasks
FROM projects p
JOIN tenants tn ON tn.id = p.tenant_id
LEFT JOIN teams tm ON tm.id = p.team_id
LEFT JOIN tasks t ON t.project_id = p.id
GROUP BY tn.name, p.id, p.name, tm.name
ORDER BY tn.name, tm.name NULLS LAST, p.name
LIMIT 30;
