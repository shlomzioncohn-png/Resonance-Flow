-- Replaces the free-text users.role column with a real enum.
--
-- role was deliberately VARCHAR(50) in V1 (see Inbox_Data_Schema_Specification.md §2:
-- "No granular permissions model at this stage") pending a decision on what the valid values
-- actually are. That decision has now been made: every user is exactly one of the four roles
-- below, so role gets the same treatment as status (V1) already has - a native Postgres enum,
-- not an unconstrained string.
--
-- NOTE: this is a real data migration, not a catalog-only rename like V7. Any existing row whose
-- role is not one of the four values below will make the ALTER COLUMN below fail - there is no
-- generic way to guess which of the four an arbitrary legacy value (e.g. 'developer') should
-- become, so this migration does not attempt to remap old values. On a dev database, truncate or
-- fix up users.role by hand first.

CREATE TYPE user_role AS ENUM (
    'system_admin',
    'tenant_admin',
    'team_lead',
    'employee'
);

ALTER TABLE users
    ALTER COLUMN role TYPE user_role USING role::user_role;
