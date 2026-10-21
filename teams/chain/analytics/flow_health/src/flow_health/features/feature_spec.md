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

## Edge level (`features_edge.csv`, one row per `(task_id, depends_on_task_id)` pair seen in the history)

`REJECTED` rows are not edges and never produce a row. `task_id` is the dependent, `depends_on_task_id` the task it waits on.

| Feature | Definition | Unit | If the edge is still active at `as_of` |
|---|---|---|---|
| `n_toggles` | number of ADDED plus REMOVED events of the pair | count | counts its ADDED |
| `median_gap_days` | median time between consecutive ADDED / REMOVED events of the pair; empty when there is only one event | days | the open stretch is not a gap |
| `active_fraction` | active time divided by the time since the first ADDED. The period ends at `as_of`, or earlier when the dependent task finished (an edge stops mattering once its dependent is done). An edge judged at the instant it was added is 1 if active, else 0 | 0..1 | the open stretch counts up to the end of the period |
| `n_dependent_flips` | BLOCKED / UNBLOCKED rows of the dependent caused by this edge. A BLOCKED row belongs to the edges added in the same write (within 1 second). An UNBLOCKED row belongs to the edges removed then, or whose target became DONE then. An edge added in the same write as others shares the flip with them | count | a block that has not ended yet counts only its BLOCKED |

## Chain level (`features_chain.csv`, one row per chain)

A chain is built for every task that is DONE and has no dependents (no edge pointing at it is active at `as_of`; this
keeps a chain from being counted again as the tail of a longer one). From that sink it takes the longest path, counted in
tasks, along the edges that were active **at the moment the sink finished**, down to a root (a task with no active
dependency at that moment). An edge is followed only if its target finished before its dependent did. Among paths of
the same length it takes the one that started earliest (the slower chain), then the smaller ids. A chain has at least 3
tasks; shorter ones produce no row. Because a chain ends in a DONE sink, every task in it is DONE: an open task is never
part of a chain, and chains carry no open-item cut-off.

| Feature | Definition | Unit |
|---|---|---|
| `sink_task_id`, `root_task_id` | the finished sink and the root of its longest path | uuid |
| `task_ids` | the tasks of the chain, root first, joined by `;` | text |
| `chain_length` | number of tasks | count |
| `end_to_end_days` | from the earliest first ACTIVE among the chain's tasks to the DONE of the sink | days |
| `hop_days` | `chain_length` values joined by `;`. The first is the root's own work (the chain's first ACTIVE to the root's DONE); each next one runs from the DONE of the dependency to the DONE of the task that waited on it. They add up to `end_to_end_days` | days |
| `mean_hop`, `max_hop`, `std_hop` | mean, maximum and population standard deviation of `hop_days` | days |
| `max_hop_share` | `max_hop / end_to_end_days` (0 when the total is 0). Low: the delay is spread out (a slow chain). High: it sits in one task (a chronic blocker) | 0..1 |
| `n_edge_changes` | ADDED plus REMOVED events, up to the sink's DONE, of the edges along the chain | count |
| `length_bucket` | `3`, `4`, `5`, `6`, `7` or `8+`, so a detector can compare chains of similar length | text |
