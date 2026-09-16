-- Loads the CSV files SeedGenerator produced (teams/inbox/docs/Task_Dataset_Design.md) into an
-- already-migrated Postgres — run Flyway (via docker compose up on the inbox service) first, so
-- these tables already exist.
--
-- \copy is a psql *client-side* command: relative file paths below resolve against wherever psql
-- itself is run from, not against this script's location. Run from this directory:
--
--   cd teams/inbox/seeds
--   psql -h localhost -p ${DB_PORT:-5432} -U ${DB_USER:-postgres} -d ${DB_NAME:-resonance_flow} -f load-seeds.sql
--
-- Order matters — later tables reference earlier ones by foreign key:
--   tenants -> users -> teams (team_lead_id references users!) -> team_members -> tasks -> assignments

\copy tenants (id, name, slug, status) FROM 'tenants.csv' WITH (FORMAT csv, HEADER true)
\copy users (id, tenant_id, email, username, display_name, role, status) FROM 'users.csv' WITH (FORMAT csv, HEADER true)
\copy teams (id, tenant_id, name, team_lead_id, status) FROM 'teams.csv' WITH (FORMAT csv, HEADER true)
\copy team_members (team_id, user_id) FROM 'team_members.csv' WITH (FORMAT csv, HEADER true)
\copy tasks (id, tenant_id, title, source_type, tier, role_context, urgency, effort_estimate_minutes, deadline, status, is_available, priority_score, snoozed_until, extraction_provenance, confidence, effort_estimate_confidence) FROM 'tasks.csv' WITH (FORMAT csv, HEADER true)
\copy assignments (task_id, user_id, team_id, assignment_role) FROM 'assignments.csv' WITH (FORMAT csv, HEADER true)

-- Sanity check — row counts per table, should match SeedGenerator's own printed summary.
SELECT 'tenants' AS table_name, count(*) FROM tenants
UNION ALL SELECT 'users', count(*) FROM users
UNION ALL SELECT 'teams', count(*) FROM teams
UNION ALL SELECT 'team_members', count(*) FROM team_members
UNION ALL SELECT 'tasks', count(*) FROM tasks
UNION ALL SELECT 'assignments', count(*) FROM assignments;
