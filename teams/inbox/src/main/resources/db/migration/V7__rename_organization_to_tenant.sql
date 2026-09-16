-- Renames the top-level tenant concept from "organization" to "tenant" across the whole schema.
--
-- The spec already described this entity as the top-level tenant; the name "organization" was the
-- odd one out. This migration is purely a rename: no column is added, dropped or retyped, and no
-- row is rewritten. RENAME is a catalog-only operation in PostgreSQL, so it takes an ACCESS
-- EXCLUSIVE lock briefly but does not scan or copy any table.
--
-- Renaming a table or column does NOT rename the indexes, constraints or triggers hanging off it -
-- they keep working, but keep their old names. Every one of them is renamed explicitly below so
-- the catalog does not end up half-migrated and confusing to read.

-- 1. The enum type behind organizations.status / tenants.status.
ALTER TYPE org_status RENAME TO tenant_status;

-- 2. The table itself, plus the constraints and trigger that carry its name.
ALTER TABLE organizations RENAME TO tenants;
ALTER TABLE tenants RENAME CONSTRAINT organizations_pkey TO tenants_pkey;
ALTER TABLE tenants RENAME CONSTRAINT uq_organizations_slug TO uq_tenants_slug;
ALTER TRIGGER trg_organizations_set_updated_at ON tenants RENAME TO trg_tenants_set_updated_at;

-- 3. The foreign key columns on every table that points at a tenant.
ALTER TABLE users     RENAME COLUMN organization_id TO tenant_id;
ALTER TABLE teams     RENAME COLUMN organization_id TO tenant_id;
ALTER TABLE projects  RENAME COLUMN organization_id TO tenant_id;
ALTER TABLE tasks     RENAME COLUMN organization_id TO tenant_id;
ALTER TABLE audit_log RENAME COLUMN organization_id TO tenant_id;

-- 4. Those foreign keys were declared inline in V1, so PostgreSQL named them
--    <table>_<column>_fkey after the old column name.
ALTER TABLE users     RENAME CONSTRAINT users_organization_id_fkey     TO users_tenant_id_fkey;
ALTER TABLE teams     RENAME CONSTRAINT teams_organization_id_fkey     TO teams_tenant_id_fkey;
ALTER TABLE projects  RENAME CONSTRAINT projects_organization_id_fkey  TO projects_tenant_id_fkey;
ALTER TABLE tasks     RENAME CONSTRAINT tasks_organization_id_fkey     TO tasks_tenant_id_fkey;
ALTER TABLE audit_log RENAME CONSTRAINT audit_log_organization_id_fkey TO audit_log_tenant_id_fkey;

-- 5. Indexes. The definitions follow the renamed column automatically; only the names are stale.
ALTER INDEX idx_users_organization_id     RENAME TO idx_users_tenant_id;
ALTER INDEX idx_teams_organization_id     RENAME TO idx_teams_tenant_id;
ALTER INDEX idx_projects_organization_id  RENAME TO idx_projects_tenant_id;
ALTER INDEX idx_tasks_organization_id     RENAME TO idx_tasks_tenant_id;
ALTER INDEX idx_audit_log_organization_id RENAME TO idx_audit_log_tenant_id;

-- The Bridge/Mind idempotency key from V2: (tenant_id, source_type, source_ref).
ALTER INDEX uq_tasks_org_source_type_source_ref RENAME TO uq_tasks_tenant_source_type_source_ref;
