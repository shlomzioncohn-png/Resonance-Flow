CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TYPE org_status AS ENUM (
    'active',
    'suspended',
    'archived'
);

CREATE TYPE user_status AS ENUM (
    'pending',
    'active',
    'suspended',
    'disabled'
);

CREATE TYPE user_auth_type AS ENUM (
    'password',
    'phone_otp',
    'email_otp'
);

CREATE TYPE otp_channel AS ENUM (
    'sms',
    'email'
);

CREATE TYPE otp_status AS ENUM (
    'pending',
    'verified',
    'expired',
    'blocked'
);

CREATE TYPE team_status AS ENUM (
    'active',
    'archived'
);

CREATE TYPE project_status AS ENUM (
    'planned',
    'active',
    'completed',
    'archived'
);

CREATE TYPE task_source_type AS ENUM (
    'github_issue',
    'github_pr',
    'crm_deal',
    'email',
    'slack',
    'whatsapp',
    'meeting',
    'doc',
    'scanned_doc',
    'calendar',
    'manual'
);

CREATE TYPE task_tier AS ENUM (
    'operational',
    'tactical',
    'strategic'
);

CREATE TYPE task_role_context AS ENUM (
    'developer',
    'manager',
    'higher_manager',
    'field_engineer',
    'salesperson',
    'ceo'
);

CREATE TYPE task_status AS ENUM (
    'new',
    'in_review',
    'approved',
    'in_progress',
    'snoozed',
    'done',
    'rejected',
    'cancelled'
);

CREATE TYPE task_extraction_provenance AS ENUM (
    'etl',
    'ml_inferred',
    'manual'
);

CREATE TYPE assignment_role AS ENUM (
    'owner',
    'assignee',
    'responsible',
    'collaborator'
);

CREATE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE organizations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(100) NOT NULL,
    status          org_status NOT NULL,
    timezone        VARCHAR(100),
    default_locale  VARCHAR(20),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_organizations_slug UNIQUE (slug)
);

CREATE TRIGGER trg_organizations_set_updated_at
    BEFORE UPDATE ON organizations
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TABLE users (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    email          VARCHAR(255),
    phone          VARCHAR(30),
    username       VARCHAR(100),
    first_name     VARCHAR(100),
    last_name      VARCHAR(100),
    display_name   VARCHAR(255) NOT NULL,
    role           VARCHAR(50) NOT NULL,
    status         user_status NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at  TIMESTAMPTZ
);

CREATE INDEX idx_users_organization_id ON users(organization_id);
CREATE UNIQUE INDEX uq_users_email ON users(email) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX uq_users_phone ON users(phone) WHERE phone IS NOT NULL;
CREATE UNIQUE INDEX uq_users_username ON users(username) WHERE username IS NOT NULL;

CREATE TRIGGER trg_users_set_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TABLE user_auth_identities (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    auth_type      user_auth_type NOT NULL,
    identifier     VARCHAR(255) NOT NULL,
    password_hash  TEXT,
    is_verified    BOOLEAN NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_user_auth_identities_type_identifier UNIQUE (auth_type, identifier),
    CONSTRAINT chk_user_auth_identities_password_hash
        CHECK (auth_type <> 'password' OR password_hash IS NOT NULL)
);

CREATE INDEX idx_user_auth_identities_user_id ON user_auth_identities(user_id);

CREATE TRIGGER trg_user_auth_identities_set_updated_at
    BEFORE UPDATE ON user_auth_identities
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TABLE otp_requests (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID REFERENCES users(id) ON DELETE SET NULL,
    identifier     VARCHAR(255) NOT NULL,
    code_hash      TEXT NOT NULL,
    purpose        VARCHAR(30) NOT NULL,
    channel        otp_channel NOT NULL,
    status         otp_status NOT NULL,
    attempt_count  SMALLINT NOT NULL DEFAULT 0,
    expires_at     TIMESTAMPTZ NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified_at    TIMESTAMPTZ
);

CREATE INDEX idx_otp_requests_user_id ON otp_requests(user_id);
CREATE INDEX idx_otp_requests_identifier ON otp_requests(identifier);

CREATE TABLE teams (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name            VARCHAR(255) NOT NULL,
    team_lead_id    UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    status          team_status NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_teams_organization_id ON teams(organization_id);
CREATE INDEX idx_teams_team_lead_id ON teams(team_lead_id);

CREATE TRIGGER trg_teams_set_updated_at
    BEFORE UPDATE ON teams
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TABLE team_members (
    team_id   UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    user_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at   TIMESTAMPTZ,

    PRIMARY KEY (team_id, user_id)
);

CREATE INDEX idx_team_members_user_id ON team_members(user_id);

CREATE TABLE projects (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    owner_id        UUID REFERENCES users(id) ON DELETE SET NULL,
    team_id         UUID REFERENCES teams(id) ON DELETE SET NULL,
    status          project_status NOT NULL,
    start_date      DATE,
    target_date     DATE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_projects_organization_id ON projects(organization_id);
CREATE INDEX idx_projects_owner_id ON projects(owner_id);
CREATE INDEX idx_projects_team_id ON projects(team_id);

CREATE TRIGGER trg_projects_set_updated_at
    BEFORE UPDATE ON projects
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TABLE tasks (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id          UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    project_id               UUID REFERENCES projects(id) ON DELETE SET NULL,
    title                    TEXT NOT NULL,
    description              TEXT,
    source_type              task_source_type NOT NULL,
    source_ref               TEXT,
    tier                     task_tier,
    role_context             task_role_context,
    urgency                  SMALLINT,
    effort_estimate_minutes  INTEGER,
    deadline                 TIMESTAMPTZ,
    status                   task_status NOT NULL,
    is_available             BOOLEAN NOT NULL DEFAULT false,
    priority_score           REAL,
    snoozed_until            TIMESTAMPTZ,
    extraction_provenance    task_extraction_provenance NOT NULL,
    confidence               REAL,
    created_by               UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at             TIMESTAMPTZ,

    CONSTRAINT chk_tasks_urgency CHECK (urgency IS NULL OR urgency BETWEEN 1 AND 5),
    CONSTRAINT chk_tasks_effort_estimate_minutes CHECK (effort_estimate_minutes IS NULL OR effort_estimate_minutes > 0),
    CONSTRAINT chk_tasks_snoozed_until CHECK (status <> 'snoozed' OR snoozed_until IS NOT NULL),
    CONSTRAINT chk_tasks_confidence_range CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    CONSTRAINT chk_tasks_confidence_required CHECK (extraction_provenance <> 'ml_inferred' OR confidence IS NOT NULL)
);

CREATE INDEX idx_tasks_organization_id ON tasks(organization_id);
CREATE INDEX idx_tasks_project_id ON tasks(project_id);
CREATE INDEX idx_tasks_status ON tasks(status);
CREATE INDEX idx_tasks_source_type ON tasks(source_type);
CREATE INDEX idx_tasks_deadline ON tasks(deadline);
CREATE INDEX idx_tasks_created_by ON tasks(created_by);
CREATE INDEX idx_tasks_is_available ON tasks(is_available);

CREATE TRIGGER trg_tasks_set_updated_at
    BEFORE UPDATE ON tasks
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TABLE assignments (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id          UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    user_id          UUID REFERENCES users(id) ON DELETE CASCADE,
    team_id          UUID REFERENCES teams(id) ON DELETE CASCADE,
    assignment_role  assignment_role NOT NULL,
    assigned_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_by      UUID REFERENCES users(id) ON DELETE SET NULL,

    CONSTRAINT chk_assignments_target CHECK (
        (user_id IS NOT NULL AND team_id IS NULL) OR (user_id IS NULL AND team_id IS NOT NULL)
    )
);

CREATE INDEX idx_assignments_task_id ON assignments(task_id);
CREATE INDEX idx_assignments_user_id ON assignments(user_id);
CREATE INDEX idx_assignments_team_id ON assignments(team_id);
CREATE UNIQUE INDEX uq_assignments_task_user ON assignments(task_id, user_id) WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX uq_assignments_task_team ON assignments(task_id, team_id) WHERE team_id IS NOT NULL;

CREATE TABLE task_feedback (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id        UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    feedback_type  VARCHAR(30) NOT NULL,
    feedback_text  TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_task_feedback_task_id ON task_feedback(task_id);
CREATE INDEX idx_task_feedback_user_id ON task_feedback(user_id);

CREATE TABLE audit_log (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id  UUID REFERENCES organizations(id) ON DELETE SET NULL,
    actor_user_id    UUID REFERENCES users(id) ON DELETE SET NULL,
    action           VARCHAR(100) NOT NULL,
    entity_type      VARCHAR(50) NOT NULL,
    entity_id        UUID,
    old_values       JSONB,
    new_values       JSONB,
    metadata         JSONB,
    correlation_id   UUID,
    ip_address       INET,
    user_agent       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_organization_id ON audit_log(organization_id);
CREATE INDEX idx_audit_log_actor_user_id ON audit_log(actor_user_id);
CREATE INDEX idx_audit_log_entity ON audit_log(entity_type, entity_id);
CREATE INDEX idx_audit_log_correlation_id ON audit_log(correlation_id);
CREATE INDEX idx_audit_log_created_at ON audit_log(created_at);
