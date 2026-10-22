-- Built-in SYSTEM_ADMIN so tenants and users can be created on an empty database.

INSERT INTO tenants (id, name, slug, status)
VALUES ('00000000-0000-0000-0000-000000000001', 'System', 'system', 'active');

INSERT INTO users (id, tenant_id, display_name, role, status)
VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
        'System Admin', 'system_admin', 'active');
