# INBOX Query Optimization Report

Measured on 100,000 tasks, 100 tenants, 1,378 users and 118,400 assignments (PostgreSQL 14),
through the Inbox REST API. Durations are `duration_ms` from the SQL query log; a statement is
reported as a slow query above 200 ms.

| Query / Scenario | Before (ms) | After (ms) | Improvement (%) | Identified Bottleneck | Optimization Applied |
|---|---:|---:|---:|---|---|
| Tasks of one user, inside a tenant of 17,085 tasks (count query) | 190 | 8 | 95.8% | JIT compilation triggered by an inflated cost estimate: three `EXISTS` subqueries that depend on the outer task, joined by `OR` | Rewrote the filter with `IN` subqueries that do not depend on the outer task |
| Tasks of one user, over all 100,000 tasks (count query) | 199 | 24 | 87.9% | Same as above | Same as above |
| Task list, a page of 20 tasks from 13 tenants | 16 statements | 3 statements | 81.3% | N+1: the tenant of each task was loaded by a `SELECT` of its own | The tenant is joined into the list query |
| Team list, a page of 20 teams | 23 statements | 3 statements | 87.0% | N+1: every related entity was loaded eagerly, each by its own `SELECT` | All related entities are loaded lazily; the tenant is joined only where the response shows it |
| Members of a team | 9 statements | 3 statements | 66.7% | Same as above | Same as above |
| User list, a page of 100 users | 8 statements | 3 statements | 62.5% | Same as above | Same as above |
| Task list in its default order (newest first), first page inside a tenant | 8.7 * | 0.2 * | 97.7% | Every task of the tenant was sorted on each request | Composite index on `tenant_id, created_at, id` |
| All tasks sorted by title, page 4,000 | 90 | not allowed | - | Sort of 100,000 rows that did not fit in memory and spilled to disk; no consumer sorts by title | Sorting is limited to `deadline`, `priorityScore` and `createdAt`; any other column is rejected |

\* Measured with `EXPLAIN ANALYZE`; all other figures are the median of five warm requests.

## Example: `EXISTS` to `IN` (first two rows)

The query counts the tasks of one user: tasks the user owns, tasks assigned to the user, or tasks
assigned to one of the user's teams while nobody holds them personally. Both versions return the
same 3,295 tasks. Shown without the filters that were not sent, and with shortened ids.

**Query before**

```sql
select count(t.id) from tasks t
where t.tenant_id = '77f971ce-...'
  and (
        t.owner_id = '1bcae2ed-...'
     or exists (select 1 from assignments a
                where a.task_id = t.id
                  and a.user_id = '1bcae2ed-...')
     or (t.owner_id is null
         and exists (select 1 from assignments a2
                     where a2.task_id = t.id
                       and a2.team_id in (select tm.team_id from team_members tm
                                          where tm.user_id = '1bcae2ed-...'
                                            and tm.left_at is null))
         and not exists (select 1 from assignments a3
                         where a3.task_id = t.id and a3.user_id is not null))
  );
```

**Query after**

```sql
select count(t.id) from tasks t
where t.tenant_id = '77f971ce-...'
  and (
        t.owner_id = '1bcae2ed-...'
     or t.id in (select a.task_id from assignments a
                 where a.user_id = '1bcae2ed-...')
     or (t.owner_id is null
         and t.id in (select a2.task_id from assignments a2
                      where a2.team_id in (select tm.team_id from team_members tm
                                           where tm.user_id = '1bcae2ed-...'
                                             and tm.left_at is null)
                        and not exists (select 1 from assignments a3
                                        where a3.task_id = a2.task_id and a3.user_id is not null)))
  );
```

Each subquery used to refer to the outer task (`a.task_id = t.id`); now none of them does.

**`EXPLAIN ANALYZE` before** (index and recheck conditions omitted)

```
Aggregate  (cost=577443.70..577443.71 rows=1 width=8) (actual time=305.077..305.081 rows=1 loops=1)
  ->  Index Scan using idx_tasks_tenant_id on tasks t  (cost=0.29..577417.12 rows=10629 width=16) (actual time=301.693..304.981 rows=3295 loops=1)
        Filter: ((owner_id = '1bcae2ed-...') OR (hashed SubPlan 2) OR ((owner_id IS NULL) AND (hashed SubPlan 4) AND (NOT (hashed SubPlan 6))))
        Rows Removed by Filter: 13790
        SubPlan 2
          ->  Bitmap Heap Scan on assignments a  (actual time=9.208..9.547 rows=2469 loops=1)
        SubPlan 4
          ->  Nested Loop  (actual time=8.991..9.222 rows=1732 loops=1)
        SubPlan 6
          ->  Seq Scan on assignments a3  (actual time=17.092..23.728 rows=91567 loops=1)
Planning Time: 3.121 ms
JIT:
  Functions: 51
  Options: Inlining true, Optimization true, Expressions true, Deforming true
  Timing: Generation 1.729 ms, Inlining 63.028 ms, Optimization 97.231 ms, Emission 69.256 ms, Total 231.244 ms
Execution Time: 322.028 ms
```

**`EXPLAIN ANALYZE` after** (index and recheck conditions omitted)

```
Aggregate  (cost=4369.96..4369.97 rows=1 width=8) (actual time=7.902..7.905 rows=1 loops=1)
  ->  Bitmap Heap Scan on tasks t  (cost=2100.93..4338.07 rows=12755 width=16) (actual time=4.257..7.802 rows=3295 loops=1)
        Filter: ((owner_id = '1bcae2ed-...') OR (hashed SubPlan 1) OR ((owner_id IS NULL) AND (hashed SubPlan 2)))
        Rows Removed by Filter: 13790
        ->  Bitmap Index Scan on idx_tasks_tenant_id  (actual time=0.231..0.232 rows=17085 loops=1)
        SubPlan 1
          ->  Bitmap Heap Scan on assignments a  (actual time=0.049..0.310 rows=2469 loops=1)
        SubPlan 2
          ->  Nested Loop Anti Join  (actual time=0.074..3.249 rows=826 loops=1)
Planning Time: 3.044 ms
Execution Time: 8.234 ms
```

What the two plans show:

- **Estimated cost: 577,443 before, 4,369 after.** The work is the same in both plans - each
  subquery runs once (`hashed SubPlan`, `loops=1`) - but before, the planner priced the
  subqueries as if they ran once per task.
- **JIT: 231 ms before, absent after.** PostgreSQL compiles a statement whose estimated cost is
  above 100,000. Of the 322 ms, 231 ms were compilation.
- **Execution time: 322 ms before, 8 ms after.** `EXPLAIN ANALYZE` adds its own timing overhead,
  so these are higher than the 190 ms and 8 ms measured through the API.

## Example: one `SELECT` per tenant to a `JOIN` (third row)

The request asks for the first page of 20 tasks, ordered by deadline, as a system admin. The 20
tasks belong to 13 tenants, and the response shows the tenant of every task. Shown without the
filters that were not sent, with column lists shortened to `*` and with shortened ids.

**Statements before** (16)

```sql
-- 1. the actor
select u.*, tn.* from users u join tenants tn on tn.id = u.tenant_id
where u.id = '00000000-...';

-- 2. the page of tasks
select t.* from tasks t
order by t.deadline fetch first 20 rows only;

-- 3-15. the tenant of each task: one statement per tenant on the page, 13 in all
select tn.* from tenants tn where tn.id = 'bed5eccc-...';
select tn.* from tenants tn where tn.id = '50e26b34-...';
select tn.* from tenants tn where tn.id = 'e7f32392-...';
-- ... 10 more statements, one per tenant

-- 16. the total number of tasks, for paging
select count(t.id) from tasks t;
```

**Statements after** (3)

```sql
-- 1. the actor
select u.*, tn.* from users u join tenants tn on tn.id = u.tenant_id
where u.id = '00000000-...';

-- 2. the page of tasks, each one together with its tenant
select t.*, tn.* from tasks t join tenants tn on tn.id = t.tenant_id
order by t.deadline fetch first 20 rows only;

-- 3. the total number of tasks, for paging
select count(t.id) from tasks t;
```

What the two lists show:

- **Statements: 16 before, 3 after.** The actor, the page and the count are the same three
  statements in both lists. The other 13 loaded the tenants, and they are gone.
- **The number of statements depended on the data.** There was one more statement for every
  different tenant on the page, so a page of 20 tasks could add up to 20. Now every list request
  issues 3 statements, whatever the page holds.
- **Each of the 13 statements took 0-1 ms.** None of them was a slow query; the cost was 13 more
  round trips to the database on every request.

## Also changed

- The task id is always the last sort key, so tasks that share a value keep a fixed order and
  paging cannot repeat or skip a task.
- Sorting by a column that does not exist returns `400` instead of `500`.
