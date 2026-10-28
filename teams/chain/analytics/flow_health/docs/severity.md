# How severity is defined

Two different things are called "severity" in the ground truth. Do not mix them up:

| Field | What it is | Used for |
|---|---|---|
| `severity` (`subtle` / `moderate` / `severe`) | How strongly the generator **planted** the pattern (hold length, cycles, chain length and slowdown). It follows the plant's parameters, not how bad the case looks. | Asking "does the detector find faint patterns too?" |
| `severity_score` (0 to 100) and `severity_level` (`low` / `medium` / `high`) | How bad the case **actually is** in the generated data, from the four parts below. | Ranking, and judging whether the detector's ranking is sensible. |

The planted label and the score are related (stronger plants score higher on average) but a faintly planted
case with a tight deadline can outrank a strongly planted one with no deadline in sight. That is intended.

## The score

One number, a weighted sum of four parts, each between 0 and 1:

```
severity_score = 100 * (0.35 * abnormality + 0.30 * overrun + 0.25 * deadline + 0.10 * scale)
level: high from 66, medium from 33, otherwise low
```

| Part | Question | 0 means | 1 means |
|---|---|---|---|
| **abnormality** | How far is it from similar healthy cases? | at or below the median of its peers | the most extreme of its peers |
| **overrun** | How much longer than estimated did the work take? | within its estimate (or what a healthy task takes) | 100 times the estimate or more |
| **deadline** | How much pressure do the deadlines of the tasks involved put on it? | no known deadline, or all a month or more away | a deadline already missed by a month or more |
| **scale** | How much work is held up? | one task | 12 or more tasks |

The weights and the level thresholds are `GeneratorConfig` fields (`severity_weights`, `severity_high_from`,
`severity_medium_from`), so they are recorded in `manifest.json` and can be changed without touching code. The 100x
overrun limit, the 12-task scale limit and the other definitions below are constants in
`src/flow_health/generator/severity.py`. All of them are a first proposal to be reviewed, not measured values.

### Deadline pressure

For every task the pattern involves (the root and whatever it holds up) that has a `deadline`, take the slack:
the deadline minus the moment the task finished, or minus the end of the pattern's window if it is still open.

- slack of zero or less (deadline missed): `0.6 + 0.4 * min(1, days late / 30)`
- slack ahead: `0.6 * (1 - days left / 30)`, reaching 0 at a month

The part is the **highest** value among those tasks, i.e. the tightest deadline decides. Tasks without a
deadline (30% of them, as in Inbox) add nothing.

### Overrun

`active time / expected time`, scored on a log scale (1x gives 0, 100x gives 1).

- Active time runs from the task's first `ACTIVE` event to its `DONE` (or to the end of the window).
- Expected time is the task's `effort_estimate_minutes`; when it has none (40% of tasks), what a healthy task of
  its tier and team normally takes.
- A slow chain sums these over all its links; the other patterns use the root task.

## Per type

| Type | abnormality | overrun | deadline | scale |
|---|---|---|---|---|
| `chronic_blocking` | percentile of the blocker's open time among healthy blockers of the same tier, similar subtree and team pace | the blocker's active time against its estimate | tightest deadline among the blocker and the tasks it blocks | tasks it blocks, out of 12 |
| `recurring_blocker` | how many times the dependent was blocked: 3 or fewer is normal (0), 10 or more is 1 | the blocker's active time against its estimate | tightest deadline among the blocker and the tasks that keep being blocked | tasks affected, out of 12 |
| `slow_chain` | percentile of the chain's end-to-end time among healthy control chains of the same length, tier and team pace | all links' active time against their estimates | tightest deadline among the links | links, out of 12 |

`growing_bottleneck` and `stuck_subgraph` (outside stage A) use the chronic-blocker definition.

## What deadline and estimate are in the data

Both are Inbox's fields (`deadline`, `effort_estimate_minutes`), fixed per task and repeated on every row of
that task in `task_state_history.csv`. This is an assumption for the synthetic data: Chain's real history
table has neither column today, and Chain does not store a deadline at all. Nothing here changes the real tables.

- 70% of tasks have a deadline and 60% an estimate (Inbox's dataset design: 30% and 40% are empty). These shares, the
  estimate noise and step, and the deadline mix are `GeneratorConfig` fields (`has_deadline_rate`, `has_estimate_rate`,
  `estimate_noise`, `estimate_step_minutes`, `deadline_mix`).
- Estimates are multiples of 15 minutes, drawn around what a healthy task of that tier and team takes. A task
  that is slow because it was planted as a disease is estimated as a normal one, so its overrun shows. A task that
  is slow by nature (`slow_legit`) is estimated as slow, so it does not.
- Deadlines fall some time after creation: about a fifth close to the expected completion (so some are missed),
  the rest within a week, a month or up to three months.

## When a case is not yet visible

Severity says how bad a case is, not whether the data already shows it. A pattern that starts close to the end of
the history may not have had time to look abnormal. `visible_in_data` in the ground truth says whether it has:
a chronic blocker once it has been open longer than 90% of its healthy peers, a slow chain once it has taken longer
than 90% of its healthy peer chains, a recurring blocker once a task has been blocked a third time. `visible_from`
is that moment, and `not_visible_because` is `too_recent` (still going on) or `stayed_within_normal` (it ended
without ever leaving the normal range). Score detectors only on cases that are visible.

Healthy tasks are blocked at most three times (some are, when a dependency is re-planned), so a recurring blocker
planted at its faintest (3 blocks) cannot be told apart from them by counting alone.
