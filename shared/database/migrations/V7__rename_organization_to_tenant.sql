ALTER TYPE org_status RENAME TO tenant_status;

ALTER TABLE organizations RENAME TO tenants;
ALTER TABLE tenants RENAME CONSTRAINT organizations_pkey TO tenants_pkey;
ALTER TABLE tenants RENAME CONSTRAINT uq_organizations_slug TO uq_tenants_slug;
ALTER TRIGGER trg_organizations_set_updated_at ON tenants RENAME TO trg_tenants_set_updated_at;

ALTER TABLE users     RENAME COLUMN organization_id TO tenant_id;
ALTER TABLE teams     RENAME COLUMN organization_id TO tenant_id;
ALTER TABLE projects  RENAME COLUMN organization_id TO tenant_id;
ALTER TABLE tasks     RENAME COLUMN organization_id TO tenant_id;
ALTER TABLE audit_log RENAME COLUMN organization_id TO tenant_id;

ALTER TABLE users     RENAME CONSTRAINT users_organization_id_fkey     TO users_tenant_id_fkey;
ALTER TABLE teams     RENAME CONSTRAINT teams_organization_id_fkey     TO teams_tenant_id_fkey;
ALTER TABLE projects  RENAME CONSTRAINT projects_organization_id_fkey  TO projects_tenant_id_fkey;
ALTER TABLE tasks     RENAME CONSTRAINT tasks_organization_id_fkey     TO tasks_tenant_id_fkey;
ALTER TABLE audit_log RENAME CONSTRAINT audit_log_organization_id_fkey TO audit_log_tenant_id_fkey;

ALTER INDEX idx_users_organization_id     RENAME TO idx_users_tenant_id;
ALTER INDEX idx_teams_organization_id     RENAME TO idx_teams_tenant_id;
ALTER INDEX idx_projects_organization_id  RENAME TO idx_projects_tenant_id;
ALTER INDEX idx_tasks_organization_id     RENAME TO idx_tasks_tenant_id;
ALTER INDEX idx_audit_log_organization_id RENAME TO idx_audit_log_tenant_id;

ALTER INDEX uq_tasks_org_source_type_source_ref RENAME TO uq_tasks_tenant_source_type_source_ref;
