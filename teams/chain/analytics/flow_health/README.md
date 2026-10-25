# Dependency-Flow Health Analytics (`teams/chain/analytics/health`)

Lives next to Chain's Java service but is a separate Python project: it is not part of the Maven build
or the Docker image (the Dockerfile copies only `pom.xml` and `src/`).

Dependency-flow health analysis for Chain: finds unhealthy patterns in how dependencies behave over
time (chronic blocking, recurring blockers, slow chains, growing bottlenecks, stuck subgraphs) and
reports them as a ranked, explained list. Advisory only: it never changes Chain's data.

**Status: stage 1 of 6 (synthetic input).** Only the data generator exists. The analysis reads the CSV files;
loading them into Chain's database and a bulk-read endpoint belong to a later stage.

## Run

```bash
python -m venv .venv
.venv\Scripts\activate              # Windows; on Linux/macOS: source .venv/bin/activate
pip install -e ".[dev]"
pytest
python -m flow_health.generator          # writes data/dev AND data/eval, validated, in one command
```

The generator itself uses only the standard library; the analysis code (loader, features, clustering) needs
`pip install -e ".[dev,analysis]"` (pandas, scikit-learn). Options: `--out`, `--seed` (dev), `--eval-seed`,
`--tasks`, `--days`, `--projects`, `--instances`, `--missing-tier-rate` (see `--help`).

## Two timelines: dev and eval

| | `data/dev` | `data/eval` |
|---|---|---|
| seed | 42 | 4242 (`--eval-seed`) |
| use | build and tune the model | measure precision / recall, once, at the end |
| disease types | all except the held-out `stuck_subgraph` | every type, including the held-out one |

Different seeds mean different healthy tasks, teams and graph, so the model never sees the evaluation
data in advance. On top of that the eval set contains a disease type the dev set never had. Excluding a
type does not change any other task's ids or behaviour (each disease and each task has its own seeded RNG).

## What it produces (per set)

Rows shaped exactly like Chain's two history tables (migrations `V2`, `V7`, `V9`), as CSV
(empty cell = NULL, ready for `COPY ... CSV HEADER`):

| File | Meaning |
|---|---|
| `task_state_history.csv` | task events: `STATUS_CHANGED`, and `BLOCKED` / `UNBLOCKED` when a task's blocking count crosses zero |
| `dependency_history.csv` | edge events: `ADDED` / `REMOVED` (`task_id` depends on `depends_on_task_id`), plus `REJECTED` noise |
| `ground_truth.csv` | **answer key**: per planted disease, one row per involved task and per involved edge, with `type`, `severity`, `tier`, `in_stage_a`, `window_start`, `window_end`, `ongoing_at_end` |
| `ground_truth.json` | same key with parameters and what the disease actually looked like (`observed`), including how its root compares with its peers (`peer_group`, and `peer_chains` for a slow chain) |
| `healthy_controls.csv` | **answer key**: the healthy control chains (same lengths, tiers and shape as the slow chain) with their end-to-end days |
| `tasks.csv` | analysis input: per task `task_id`, `created_at`, `project_id` |
| `task_meta.csv` | analysis input: per task `task_id` and `tier` (blank when unknown). Chain's tables have no tier column, so it lives here |
| `answer_key_tasks.csv` | **answer key**: per task `true_tier`, project `archetype`, `kind` (`normal`, `slow_legit`, `control_chain`, ...), `disease_id`, `control_id` |
| `manifest.json` | seed, config, counts, `as_of` (the "now" of the analysis), held-out types |

`ground_truth.*`, `healthy_controls.csv` and `answer_key_tasks.csv` are for evaluation only. The model's input is the two history tables plus `tasks.csv` and `task_meta.csv`.

## How it works

It does not fill rows randomly. It plans a world (tasks, edges, planted pathologies), then **plays it
forward in time** with Chain's own counter rules and records what Chain would have written. That is
what keeps the two tables consistent with each other: a task is `BLOCKED` exactly when its first
open dependency appears, `UNBLOCKED` exactly when the last one is done or removed, and so on.
`validate.py` replays the output from the rows alone and checks it against the tables' constraints
and the counter rules; the generator refuses to write data that fails it.

Code layout (`src/flow_health/generator/`): `config.py` (all knobs), `model.py` (tiers, task/edge specs),
`planner.py` (healthy world), `controls.py` (healthy control chains), `peers.py` (peer groups), `diseases/` (one module per disease), `simulation.py`, `validate.py`,
`export.py`, `__main__.py`.

**Healthy world**
- **Tiers:** `operational` (70%), `tactical` (22%), `strategic` (8%). Higher tiers take longer
  (work x1 / x2.5 / x6) and wait on lower ones (strategic on tactical, tactical on operational).
- **Tiers may be missing:** `--missing-tier-rate` (default 5%) hides the tier of that share of tasks in
  `task_meta.csv`; `1.0` gives a dataset with no tiers at all, like real Chain. Everything still works:
  an unknown tier runs at the average pace in the simulation, and in the peer groups the tier is simply left out.
- **Teams:** 20 projects of different pace (fast / medium / slow), lognormal durations, working
  hours and weekdays, except about 15% of events (automation, late work) at any hour or day.
- **Load bursts:** about 15% of tasks are created in 12 bursts, where one to three teams open many tasks within a day or two.
- **Graph shapes:** chains, fan-out (hub tasks that several later tasks of a project depend on),
  fan-in (integration tasks that wait on 4-7 earlier ones), diamonds. Edges also appear mid-period and are sometimes removed. No cycles.
- **Healthy control chains:** linear chains of 4 / 7 / 12 tasks (operational and tactical, per team pace) at
  normal speed. They are the "chains of the same length and shape" a slow chain is judged against.
- **Healthy lookalikes** (hard negatives, labelled in `answer_key_tasks.csv`): `slow_legit` (4-8x slower
  than its peers), `backlog` and `abandoned` (never finished, nothing depends on them).
- **`REJECTED` rows:** about 1% of tasks get one in `dependency_history` (as Chain writes for a
  cross-tenant edge). They never become edges and must be ignored by the analysis.

**Peer groups** (in `ground_truth.json`, so the detectors can be scored against the same yardstick):
a chronic blocker is compared with healthy blockers of the **same tier, similar subtree size** (bucket of
transitive dependents) and team pace; a slow chain with healthy chains of the same length, tier and team pace.
When a group has too few peers the comparison widens step by step (the `level` field says how far), and
without a tier it drops the tier.

**Diseases**, three severities each (`subtle`, `moderate`, `severe`), judged against the root's own
tier (`peer_typical_days` in the ground truth). About a third of the hold-based ones are still **ongoing** when the history ends.

| Type | What is planted | subtle / moderate / severe |
|---|---|---|
| `chronic_blocking` | one blocker stays open far longer than peers of its tier; dependents wait | 5x / 10x / 20x peers (floor 10 / 20 / 40 days), 1-3 dependents |
| `recurring_blocker` | an edge is removed and added back again and again while its target stays open; the dependent flaps BLOCKED/UNBLOCKED | 3 / 6 / 10 blocks per dependent, 1-2 dependents |
| `slow_chain` | same-tier chain where every link is several times slower than peers | length 4 / 7 / 12, 3x / 5x / 8x |
| `growing_bottleneck` | unfinished task that gains dependents over weeks | 6 / 12 / 24 dependents |
| `stuck_subgraph` (held out of dev) | multi-level subtree stalled behind one held root | 4 / 8 / 15 tasks, 6x / 12x / 24x peers (floor 14 / 28 / 50 days) |

Only `chronic_blocking`, `recurring_blocker` and `slow_chain` belong to stage A. `growing_bottleneck` and
`stuck_subgraph` are planted for later but labelled `in_stage_a=false` in the ground truth; the stage-A
detectors and evaluation ignore them.

### Adding a disease

Create `diseases/<name>.py` that defines `DISEASE = Disease(name, plant, nominal_days=None, tiers=...)`.
`plant(planter, severity)` builds tasks and edges through the planter (`task`, `depend`, `advance`, ...)
and returns the root task and a params dict. It is discovered automatically; nothing else changes.
`tests/test_generator.py` has a toy example.

## Known simplifications

- Planted structures hang off the healthy world only through an optional upstream task of their root;
  healthy tasks never depend on diseased ones.
- Dependencies only point at earlier-created tasks, so the graph is acyclic by construction (Chain
  rejects cycles anyway). The "recurring blocker" disease is therefore an edge that is repeatedly removed and re-added, not a graph cycle. Tasks never leave DONE in this data.
- Normal-world timing is a plausible guess, not calibrated against real Chain data. Re-check the
  numbers once real history exists.

## Loading data (`flow_health.loaders`)

`CsvLoader(data_dir).load(as_of=None)` returns a `Dataset`: the tables `tasks`, `task_meta`, `dependency_history`,
`task_state_history` plus `as_of`. All later code reads data only through `Dataset`, so stage B can swap in a loader
that reads Chain. Only the contract columns are read; `REJECTED` dependency rows are dropped (logged); history is
ordered by `created_at`, then `sequence`; `as_of` defaults to the latest event, and later events are cut off when it
is set earlier. A test checks that analysis code never mentions the answer files.

## Timeline reconstruction (`flow_health.features.timeline`)

`build_timeline(dataset)` returns a `Timeline` with three tables and a list of warnings:

| Table | One row per | Columns |
|---|---|---|
| `status_segments` | PENDING / ACTIVE stretch of a task, plus a zero-length `DONE` marker at the finish time | `task_id, status, start, end, is_open` |
| `block_segments` | BLOCKED -> UNBLOCKED pair | `task_id, start, end, is_open` |
| `edge_intervals` | ADDED -> REMOVED stretch of an edge (an edge that is removed and re-added has several) | `task_id, depends_on_task_id, start, end, is_open` |

Anything still running at `as_of` is cut there and flagged `is_open = true`; it is never dropped. An event that
contradicts the sequence (UNBLOCKED without BLOCKED, a second BLOCKED, ADDED twice, REMOVED of an edge that is not
there, a status change that does not follow the previous one, a task leaving DONE) is logged as a warning, kept in
`Timeline.warnings` and skipped. The generator's data produces none.

## Features and clustering (`flow_health.features`, `flow_health.clustering`)

```bash
python -m flow_health.generator                                 # writes data/dev and data/eval
python -m flow_health.features --data data/dev --out out/dev
```

Writes to `out/dev`: `features_task.csv`, `features_edge.csv`, `features_chain.csv`, `clusters_task.csv` (task_id,
cluster, distance_to_centroid), `cluster_report.md` (size, feature medians and tier mix per cluster, silhouette) and
`feature_spec.md` (every feature: definition, unit, treatment of open items). `--as-of` overrides the end of the
observation window. Two runs on the same input give byte-identical files.

Clustering is one KMeans (random_state 42) over eight task features, standardised; k is the best silhouette in 2-6.
`tier` and `project_id` stay in `features_task.csv` but are not clustered. The code never reads the answer key; the
tests do, as a sanity check. One of them (chronic blockers in the top decile) currently fails on dev and is recorded
as an expected failure with its reason.

## Detectors (`flow_health.detectors`)

```bash
python -m flow_health.features --data data/dev --out out/dev        # once: task features and clusters
python -m flow_health.detectors --data data/dev --features out/dev --seed 42   # writes out/dev/flags.json
```

Three deterministic detectors; each returns a list of flags and the command writes all of them to one `flags.json`
(`{"meta": {...}, "flags": [...]}`). Output is a recommendation only: nothing is written to Chain, and no task or
dependency is changed. A flag has `flag_id`, `pattern`, `entity_type`, `entity_ids`, `score` (0-1, percentile rank inside
its detector), `severity` (`low` / `medium` / `high`), `explanation` (a Hebrew sentence built from a template and the real
numbers) and `evidence` (the numbers themselves). Merging the three lists into one ranked report is the next task.
The detectors never read the answer files (checked by `tests/test_no_answer_leak.py`).

| Detector | Looks at | Flagged when | Severity |
|---|---|---|---|
| `CHRONIC_BLOCKER` (a task) | `features_task` | `active_ratio_to_tier_project` (days the task was ACTIVE / the usual for its tier inside its project) is at least 8 **and** it had at least 1 dependent. A long task nobody waits on is not flagged. A chronic blocker is held while it is worked on, so its own blocked time is not used. | by the ratio: 8-16 low, 16-32 medium, 32 and up high |
| `RECURRING_CYCLE` (an edge, or a pair of edges) | `dependency_history`: ADDED / REMOVED events of each `(task_id, depends_on_task_id)` | at least 3 switches (the first ADDED is not a switch) and a median gap between events of 1-10 days. An edge fixed once is not flagged. A **near-cycle** (two live edges of one task, one removed and back after at least a day) is flagged on the pair; edges already flagged alone are not repeated. | 8+ switches, or 5+ with even gaps (CV <= 0.3): high; 5+ medium; else low. A pair is always low. |
| `SLOW_CHAIN` (a path) | windows of 4-8 consecutive finished tasks of the dependency graph (`chain_paths.py`); value of a window = the median `active_ratio_to_tier_project` of its tasks | the value is at least 2x the median value of windows **of the same length**, robust z (median / MAD, IQR when MAD is 0) at least 3.5, **and** no single link above 50% of the total time (such a path is a chronic blocker). Groups smaller than 8 are not judged. Overlapping windows of one stretch give one flag (the strongest). | by the ratio to the group median: 2-4 low, 4-8 medium, 8 and up high |

**Paths.** The graph holds finished tasks; an edge counts if it was active when its dependent finished and its target
finished no later. A maximal path starts at a task with no such dependency and ends at one nothing depends on; every
window of 4-8 consecutive tasks of it is a candidate, so a slow stretch inside a longer path is seen. The time runs from
the first task's first ACTIVE to the last task's DONE; the first hop is the first task's own work, so the hops add up
to the total. Windows share tasks, so after the thresholds the weaker of two windows that share more than half of the
smaller one is dropped.

**Thresholds** live in `src/flow_health/detectors/config.yaml` (`--config` points at another file); the file's comments
say what each value means. The ones that decide most: `chronic_blocker.min_active_ratio`, `min_dependents`;
`recurring_cycle.min_transitions`, `min_median_gap_days`, `max_median_gap_days`; `slow_chain.min_ratio_to_median`,
`min_robust_z`, `max_link_share`, `min_group_size`. They were set on the dev set (seed 42) only; the eval set was not opened.
The only change from the starting values: `min_median_gap_days` is 1.0 instead of 2 (on dev every edge with 3 or more
switches has a median gap of 1.8-3.0 days).

**Result on dev:** 222 flags: 88 chronic blockers, 20 recurring-cycle edges, 114 slow chains, 0 near-cycle pairs (no
task in dev has two live edges of which one flaps).

**Limits.** The work-time features compare a task with its tier inside its project (`active_ratio_to_tier_project`; a
tier with fewer than 5 worked-on tasks in a project takes the tier-wide `active_ratio_to_tier`). Two thresholds moved
from the task's opening values to fit dev: the chronic blocker's dependents (3 to 1, with the ratio raised to 8) and the
slow chain's ratio to the group median (3 to 2). A subtle chronic blocker with a single dependent is still hard to tell
from an ordinary busy task, and a slow chain with ordinary-looking links is not visible to any of these features.
