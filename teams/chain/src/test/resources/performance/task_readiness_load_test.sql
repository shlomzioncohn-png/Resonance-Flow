BEGIN;

-- Create 50,000 tasks.
INSERT INTO task (
    id,
    tenant_id,
    status,
    is_ready,
    blocking_count,
    open_constraint_count,
    created_at,
    updated_at
)
SELECT
    (
        '10000000-0000-0000-0000-' ||
        lpad(i::text, 12, '0')
    )::uuid,
    '11111111-1111-1111-1111-111111111111'::uuid,
    CASE
        WHEN i % 10 = 0 THEN 'DONE'
        ELSE 'ACTIVE'
    END,
    true,
    0,
    0,
    now(),
    now()
FROM generate_series(1, 50000) AS i;


-- Add dependencies for 20,000 tasks.
INSERT INTO task_dependency (
    task_id,
    depends_on_task_id
)
SELECT
    (
        '10000000-0000-0000-0000-' ||
        lpad(i::text, 12, '0')
    )::uuid,
    (
        '10000000-0000-0000-0000-' ||
        lpad((i + 25000)::text, 12, '0')
    )::uuid
FROM generate_series(1, 20000) AS i;


-- Add 10,000 PENDING constraints.
INSERT INTO task_constraint (
    constraint_id,
    task_id,
    type,
    status,
    created_at,
    updated_at
)
SELECT
    'load-pending-' || i,
    (
        '10000000-0000-0000-0000-' ||
        lpad(i::text, 12, '0')
    )::uuid,
    'APPROVAL_NEEDED',
    'PENDING',
    now(),
    now()
FROM generate_series(1, 10000) AS i;


-- Add 5,000 SATISFIED constraints.
INSERT INTO task_constraint (
    constraint_id,
    task_id,
    type,
    status,
    created_at,
    updated_at
)
SELECT
    'load-satisfied-' || i,
    (
        '10000000-0000-0000-0000-' ||
        lpad((i + 10000)::text, 12, '0')
    )::uuid,
    'APPROVAL_NEEDED',
    'SATISFIED',
    now(),
    now()
FROM generate_series(1, 5000) AS i;


-- Refresh planner statistics after the bulk inserts.
ANALYZE task;
ANALYZE task_dependency;
ANALYZE task_constraint;


-- Test 1:
-- Find all ready tasks directly from the source tables.
EXPLAIN ANALYZE
SELECT
    r.id
FROM task_readiness r
JOIN task t ON t.id = r.id
WHERE r.is_ready = true
  AND t.status <> 'DONE';


-- Test 2:
-- Read readiness data for a specific list of task IDs through the view.
EXPLAIN ANALYZE
SELECT
    tr.id,
    tr.tenant_id,
    tr.blocking_count,
    tr.open_constraint_count,
    tr.is_ready
FROM task_readiness tr
WHERE tr.id IN (
    '10000000-0000-0000-0000-000000000001'::uuid,
    '10000000-0000-0000-0000-000000000002'::uuid,
    '10000000-0000-0000-0000-000000000003'::uuid,
    '10000000-0000-0000-0000-000000000004'::uuid,
    '10000000-0000-0000-0000-000000000005'::uuid
);


-- Remove all load-test data.
ROLLBACK;