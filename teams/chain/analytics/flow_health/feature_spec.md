# Feature specification

Every feature is computed from the rebuilt timeline (see "Timeline reconstruction" in the README). `as_of` is the end
of the observation window. A task, block or edge still running at `as_of` is **cut (censored) at `as_of`** and flagged
open; open items are never dropped. Days are 24-hour days.

## Task level (`features_task.csv`, one row per task)

| Feature | Definition | Unit | If the task is open (`is_open`) |
|---|---|---|---|
| `task_id` | the task | uuid | - |
| `project_id` | project of the task, kept in the table, not used for clustering | int | - |
| `tier` | tier from `task_meta`; `UNKNOWN` when missing; kept in the table, not used for clustering | text | - |
| `is_open` | the task never reached DONE | bool | true |
| `age_days` | from creation to its DONE, or to `as_of` when it is not done | days | up to `as_of` |
| `days_pending` | total time in PENDING (including time spent blocked) | days | a running PENDING segment counts up to `as_of` |
| `days_active` | total time in ACTIVE | days | a running ACTIVE segment counts up to `as_of` |
| `days_blocked_total` | total time inside BLOCKED -> UNBLOCKED segments; 0 if never blocked | days | a block still open counts up to `as_of` |
| `n_block_events` | number of block segments (one per BLOCKED event) | count | an open block counts |
| `blocked_p50`, `blocked_p90`, `blocked_max` | median, 90th percentile (linear interpolation) and maximum of the block-segment durations | days | an open block is included with its length up to `as_of`; empty when the task was never blocked |
| `n_dependents_now` | tasks that depend on this one through an edge active at `as_of` | count | - |
| `n_dependents_max` | the most dependents this task ever had at the same moment | count | - |
| `dependents_per_30d` | `n_dependents_max / max(age_days, 1) * 30` (a task younger than a day counts as one day old) | dependents per 30 days | age is measured up to `as_of` |
| `age_ratio_to_tier` | `age_days` divided by the median `age_days` of all tasks of the same tier, open ones included (cut at `as_of`); `UNKNOWN` is a tier of its own | ratio | uses the cut age |
| `blocked_ratio_to_tier` | `days_blocked_total` divided by the median `days_blocked_total` of the tasks of the same tier that were blocked at least once (never-blocked tasks are left out of the median, because on dev more than half of every tier is never blocked and the median over all tasks is 0); a never-blocked task gets 0; a tier with no block at all uses the median of every blocked task | ratio | uses the cut block time |

Not-happened versus unknown: `blocked_p50`, `blocked_p90` and `blocked_max` are empty (not 0) when a task was never
blocked; they become 0 only when the clustering matrix is built.
