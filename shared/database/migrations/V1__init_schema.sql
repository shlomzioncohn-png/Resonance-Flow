CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TYPE task_status AS ENUM (
    'NEW',
    'IN_PROGRESS',
    'DONE',
    'DISMISSED',
    'DELETED'
);

CREATE TYPE task_source_type AS ENUM (
    'SLACK',
    'EMAIL',
    'GITHUB',
    'CRM',
    'MEETING',
    'MANUAL'
);

CREATE TYPE task_event_type AS ENUM (
    'STATUS_CHANGE',
    'FEEDBACK',
    'CORRECTION',
    'DELETION'
);

CREATE TYPE task_assignee_role AS ENUM (
    'OWNER',
    'COLLABORATOR'
);

CREATE TYPE team_member_role AS ENUM (
    'LEAD',
    'MEMBER'
);

CREATE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE organizations (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    display_name TEXT NOT NULL,
    email        TEXT NOT NULL,
    CONSTRAINT uq_users_org_email UNIQUE (org_id, email)
);

CREATE INDEX idx_users_org_id ON users(org_id);

CREATE TABLE teams (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_teams_org_id ON teams(org_id);

CREATE TABLE team_members (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id   UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    user_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role      team_member_role NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_team_members_team_user UNIQUE (team_id, user_id)
);

CREATE INDEX idx_team_members_team_id ON team_members(team_id);
CREATE INDEX idx_team_members_user_id ON team_members(user_id);

CREATE TABLE tasks (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                      UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    title                       TEXT NOT NULL,
    description                 TEXT,
    status                      task_status NOT NULL DEFAULT 'NEW',
    priority_score              NUMERIC,
    confidence_score            NUMERIC,
    source_type                 task_source_type NOT NULL,
    source_ref_id               TEXT,
    external_id                 TEXT,
    estimated_duration_minutes  INTEGER CHECK (estimated_duration_minutes IS NULL OR estimated_duration_minutes > 0),
    due_date                    TIMESTAMPTZ,
    metadata                    JSONB NOT NULL DEFAULT '{}'::jsonb,
    version                     INTEGER NOT NULL DEFAULT 1,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at                  TIMESTAMPTZ,
    completed_at                TIMESTAMPTZ,
    dismissed_at                TIMESTAMPTZ,
    deleted_at                  TIMESTAMPTZ,

    owner_id                    UUID REFERENCES users(id) ON DELETE SET NULL,

    dependency_task_id          UUID REFERENCES tasks(id) ON DELETE SET NULL,

    CONSTRAINT chk_tasks_no_self_dependency CHECK (id IS DISTINCT FROM dependency_task_id)
);

CREATE INDEX idx_tasks_org_id ON tasks(org_id);
CREATE INDEX idx_tasks_status ON tasks(status);
CREATE INDEX idx_tasks_source_type ON tasks(source_type);
CREATE INDEX idx_tasks_due_date ON tasks(due_date);
CREATE INDEX idx_tasks_owner_id ON tasks(owner_id);
CREATE INDEX idx_tasks_dependency_task_id ON tasks(dependency_task_id);
CREATE INDEX idx_tasks_deleted_at ON tasks(deleted_at) WHERE deleted_at IS NULL;

CREATE TRIGGER trg_tasks_set_updated_at
    BEFORE UPDATE ON tasks
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TABLE task_assignees (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id     UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
    team_id     UUID REFERENCES teams(id) ON DELETE SET NULL,
    role        task_assignee_role NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_task_assignees_target CHECK (user_id IS NOT NULL OR team_id IS NOT NULL)
);

CREATE INDEX idx_task_assignees_task_id ON task_assignees(task_id);
CREATE INDEX idx_task_assignees_user_id ON task_assignees(user_id);
CREATE INDEX idx_task_assignees_team_id ON task_assignees(team_id);

CREATE TABLE task_events (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id    UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    event_type task_event_type NOT NULL,
    payload    JSONB NOT NULL DEFAULT '{}'::jsonb,
    actor      TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_task_events_task_id ON task_events(task_id);
CREATE INDEX idx_task_events_created_at ON task_events(created_at);
