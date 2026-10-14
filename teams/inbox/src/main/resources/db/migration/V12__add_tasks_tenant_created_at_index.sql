-- Serves the Inbox task list's default order: newest first inside one tenant
-- (WHERE tenant_id = ? ORDER BY created_at DESC, id DESC). With it the first page is read
-- straight off the index instead of sorting every task of the tenant on each request.
-- id is part of the key because it is the list's tie-breaker for tasks created at the same instant.
CREATE INDEX idx_tasks_tenant_created_at_id ON tasks (tenant_id, created_at DESC, id DESC);
