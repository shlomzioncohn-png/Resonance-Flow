# Dependency-Flow Health Analytics (`teams/chain/analytics/health`)

Lives next to Chain's Java service but is a separate Python project: it is not part of the Maven build
or the Docker image (the Dockerfile copies only `pom.xml` and `src/`).

Dependency-flow health analysis for Chain: finds unhealthy patterns in how dependencies behave over
time (chronic blocking, recurring blockers, slow chains, growing bottlenecks, stuck subgraphs) and
reports them as a ranked, explained list. Advisory only: it never changes Chain's data.

**Status: stage 1 of 6 (synthetic input).** Only the data generator exists. There is no model yet,
no Chain endpoint, and nothing is written to Postgres; those come in later stages.

## Run

```bash
python -m venv .venv
.venv\Scripts\activate              # Windows; on Linux/macOS: source .venv/bin/activate
pip install -e ".[dev]"
pytest
python -m health.generator                    # dev set  -> out/dev   (holdout disease excluded)
python -m health.generator --include-holdout --out out/final   # final evaluation set
```

The generator itself uses only the standard library. Options: `--seed`, `--tasks`, `--days`,
`--projects`, `--instances` (see `--help`).

## What it produces

Rows shaped exactly like Chain's two history tables (migrations `V2` and `V9`), as CSV
(empty cell = NULL, ready for `COPY ... CSV HEADER`):

| File | Meaning |
|---|---|
| `dependency_history.csv` | `ADDED` / `REMOVED` edges (`task_id` depends on `depends_on_task_id`) |
| `task_state_history.csv` | `STATUS_CHANGED`, and `BLOCKED` / `UNBLOCKED` when a task's blocking count crosses zero |
| `manifest.json` | seed, config, counts, `as_of` (the "now" of the analysis), held-out types |
| `ground_truth.json` | **answer key**: every planted disease, its root task, members, parameters, and what it actually looked like (`observed`: ongoing at the end, longest block, ...) |
| `ground_truth.csv` | same key flattened: one row per sick task (`task_id`, `disease_id`, `type`, `severity`, `role`, `ongoing_at_end`) |
| `tasks_meta.csv` | **answer key**: per task project archetype and kind (`normal`, `slow_legit`, ...) |

`ground_truth.*` and `tasks_meta.csv` are for evaluation only. The model's input is the two
history tables.

## How it works

It does not fill rows randomly. It plans a world (tasks, edges, planted pathologies), then **plays it
forward in time** with Chain's own counter rules and records what Chain would have written. That is
what keeps the two tables consistent with each other: a task is `BLOCKED` exactly when its first
open dependency appears, `UNBLOCKED` exactly when the last one is done or removed, and so on.
`validate.py` replays the output from the rows alone and checks it against the tables' constraints
and the counter rules; the generator refuses to write data that fails it.

- **Scale (defaults):** about 5,400 tasks over 180 days, 20 projects, 5 planted instances per disease
  type and severity (60 diseases in dev, 75 in final; roughly 7% of tasks are sick).
- **Normal world:** 20 projects of different pace (fast / medium / slow), lognormal durations,
  working-hours-only activity, dependencies mostly inside a project and on recent work, late-added
  and removed edges, a mix of `MIND` / `USER` / `SYSTEM` sources.
- **Healthy lookalikes** (hard negatives, labelled in `tasks_meta.csv`): `slow_legit` (4-8x slower
  than its team), `reopened_legit`, `backlog` and `abandoned` (never finished, nothing depends on them).
- **`REJECTED` rows:** about 1% of tasks get a `REJECTED` entry in `dependency_history` (as Chain writes for
  a cross-tenant edge, migration `V7`). They never become edges and must be ignored by the analysis.
- **Diseases**, each at three severities (`subtle`, `moderate`, `severe`). About a third of the hold-based
  ones (chronic blocking, bottleneck, stuck subgraph) are still **ongoing** when the history ends:

| Type | What is planted | subtle / moderate / severe |
|---|---|---|
| `chronic_blocking` | one blocker held open, its dependents wait | 14 / 30 / 60 days, 1-3 dependents |
| `recurring_blocker` | blocker is finished and reopened again and again; dependents flap BLOCKED/UNBLOCKED | 3 / 6 / 10 cycles |
| `slow_chain` | chain where every link is several times slower than its team | length 4 / 7 / 12, 3x / 5x / 8x |
| `growing_bottleneck` | unfinished task that gains dependents over weeks | 6 / 12 / 24 dependents |
| `stuck_subgraph` | multi-level subtree stalled behind one held root | 4 / 8 / 15 tasks, 20 / 40 / 70 days |

## Evaluation protocol (hold-out)

`stuck_subgraph` is held out: the dev set never contains it, so nothing gets tuned against it. Build
and tune the model on `out/dev`; run `out/final` (`--include-holdout`) once at the end to see whether
it catches a pattern type it never saw. Excluding a type does not change any other task's ids or
behaviour (each disease and each task has its own seeded RNG), so dev results carry over to final.

## Known simplifications

- Planted structures hang off the normal world only through an optional upstream task of their root;
  normal tasks never depend on diseased ones.
- Dependencies only point at earlier-created tasks, so the graph is acyclic by construction (Chain
  rejects cycles anyway). The "recurring blocker" disease is therefore reopen/finish flapping, not a graph cycle.
- Normal-world timing is a plausible guess, not calibrated against real Chain data. Re-check the
  numbers once real history exists.
