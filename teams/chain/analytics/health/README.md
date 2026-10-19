# Dependency-Flow Health Analytics (`teams/chain/analytics/health`)

Lives next to Chain's Java service but is a separate Python project: it is not part of the Maven build
or the Docker image (the Dockerfile copies only `pom.xml` and `src/`).

Dependency-flow health analysis for Chain: finds unhealthy patterns in how dependencies behave over
time (chronic blocking, recurring blockers, slow chains, growing bottlenecks, stuck subgraphs) and
reports them as a ranked, explained list. Advisory only: it never changes Chain's data.

**Status: stage 1 of 6 (synthetic input).** The data generator and a Postgres loader exist. There is no model yet,
no Chain endpoint, and nothing touches the shared Chain database; those come in later stages.

## Run

```bash
python -m venv .venv
.venv\Scripts\activate              # Windows; on Linux/macOS: source .venv/bin/activate
pip install -e ".[dev]"
pytest
python -m health.generator          # writes out/dev AND out/eval, validated, in one command
```

The generator itself uses only the standard library. Options: `--out`, `--seed` (dev), `--eval-seed`,
`--tasks`, `--days`, `--projects`, `--instances` (see `--help`).

## Two timelines: dev and eval

| | `out/dev` | `out/eval` |
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
| `ground_truth.csv` | **answer key**: per planted disease, one row per involved task and per involved edge, with `type`, `severity`, `tier`, `window_start`, `window_end`, `ongoing_at_end` |
| `ground_truth.json` | same key with parameters and what the disease actually looked like (`observed`) |
| `tasks_meta.csv` | **answer key**: per task `tier`, project archetype and kind (`normal`, `slow_legit`, ...). `tier` is not a column of Chain's tables, so it lives here |
| `manifest.json` | seed, config, counts, `as_of` (the "now" of the analysis), held-out types |

`ground_truth.*` and `tasks_meta.csv` are for evaluation only. The model's input is the two history tables.

## How it works

It does not fill rows randomly. It plans a world (tasks, edges, planted pathologies), then **plays it
forward in time** with Chain's own counter rules and records what Chain would have written. That is
what keeps the two tables consistent with each other: a task is `BLOCKED` exactly when its first
open dependency appears, `UNBLOCKED` exactly when the last one is done or removed, and so on.
`validate.py` replays the output from the rows alone and checks it against the tables' constraints
and the counter rules; the generator refuses to write data that fails it.

Code layout (`src/health/generator/`): `config.py` (all knobs), `model.py` (tiers, task/edge specs),
`planner.py` (healthy world), `diseases/` (one module per disease), `simulation.py`, `validate.py`,
`export.py`, `__main__.py`.

**Healthy world**
- **Tiers:** `operational` (70%), `tactical` (22%), `strategic` (8%). Higher tiers take longer
  (work x1 / x2.5 / x6) and wait on lower ones (strategic on tactical, tactical on operational).
- **Teams:** 20 projects of different pace (fast / medium / slow), lognormal durations, working
  hours and weekdays only.
- **Load bursts:** about 15% of tasks are created in 12 bursts, where one to three teams open many tasks within a day or two.
- **Graph shapes:** chains, fan-out (hub tasks that several later tasks of a project depend on),
  fan-in (integration tasks that wait on 4-7 earlier ones), diamonds. Edges also appear mid-period and are sometimes removed. No cycles.
- **Healthy lookalikes** (hard negatives, labelled in `tasks_meta.csv`): `slow_legit` (4-8x slower
  than its peers), `reopened_legit`, `backlog` and `abandoned` (never finished, nothing depends on them).
- **`REJECTED` rows:** about 1% of tasks get one in `dependency_history` (as Chain writes for a
  cross-tenant edge). They never become edges and must be ignored by the analysis.

**Diseases**, three severities each (`subtle`, `moderate`, `severe`), judged against the root's own
tier (`peer_typical_days` in the ground truth). About a third of the hold-based ones are still **ongoing** when the history ends.

| Type | What is planted | subtle / moderate / severe |
|---|---|---|
| `chronic_blocking` | one blocker stays open far longer than peers of its tier; dependents wait | 5x / 10x / 20x peers (floor 10 / 20 / 40 days), 1-3 dependents |
| `recurring_blocker` | blocker is finished and reopened again and again; dependents flap BLOCKED/UNBLOCKED | 3 / 6 / 10 cycles |
| `slow_chain` | same-tier chain where every link is several times slower than peers | length 4 / 7 / 12, 3x / 5x / 8x |
| `growing_bottleneck` | unfinished task that gains dependents over weeks | 6 / 12 / 24 dependents |
| `stuck_subgraph` (held out of dev) | multi-level subtree stalled behind one held root | 4 / 8 / 15 tasks, 6x / 12x / 24x peers (floor 14 / 28 / 50 days) |

### Adding a disease

Create `diseases/<name>.py` that defines `DISEASE = Disease(name, plant, nominal_days=None, tiers=...)`.
`plant(planter, severity)` builds tasks and edges through the planter (`task`, `depend`, `advance`, ...)
and returns the root task and a params dict. It is discovered automatically; nothing else changes.
`tests/test_generator.py` has a toy example.

## Loading into Postgres (optional, `[db]` extra)

Loads a dataset into a throwaway Postgres that has Chain's real history-table schema. Chain's own
Flyway files (V2, V7, V9) are applied as they are, so Postgres itself enforces the CHECK constraints and
the unique index on the synthetic rows. It is never the shared Chain database.

```bash
pip install -e ".[dev,db]"
docker compose up -d --wait            # postgres on localhost:55432, data discarded on `down`
python -m health.loader                # out/dev -> chain_health; override with --data / --dsn / HEALTH_DB_DSN
docker compose down                    # reset before loading again
```

The loader refuses to load into tables that already hold rows (it never truncates or drops anything).

## Known simplifications

- Planted structures hang off the healthy world only through an optional upstream task of their root;
  healthy tasks never depend on diseased ones.
- Dependencies only point at earlier-created tasks, so the graph is acyclic by construction (Chain
  rejects cycles anyway). The "recurring blocker" disease is therefore reopen/finish flapping, not a graph cycle.
- Normal-world timing is a plausible guess, not calibrated against real Chain data. Re-check the
  numbers once real history exists.
