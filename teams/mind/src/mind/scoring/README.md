# scoring

Segmented ranking: one deterministic scoring rule per tier, producing the
per-task priority score MIND publishes.

- **Boundary.** MIND publishes **the per-task score**. Everything downstream —
  feasibility, free-time matching, deciding what actually gets done — belongs to
  Chain and Inbox. The contract enforces this by not handing the model
  `free_time_minutes` or `constraints`, and nothing here reintroduces them.
- **Stretch goal (week 7, R3):** propagate importance down `parent_task_id`.
  This baseline reads that field as a weak signal but never walks the chain.
- **Explanations (#358, week 6)** are built on top of the raw factors this model
  returns; only a fixed template lives here.

## The contract

`SegmentedRankingModel.score(task, context) -> ScoreResult`, matching the
`RankingModel` protocol in [`../evaluation/interfaces.py`](../evaluation/interfaces.py).
It is a `Protocol`, so the class inherits from nothing. The model sees one
`Task` at a time plus `context.current_datetime`, never the other candidates and
never the persona. The harness builds the ranking from the per-task scores.

## How a score is computed

Four steps:

1. `task.tier` selects a rule from `TIER_RULES`.
2. That rule's features are computed, each normalised to 0-1 on its own.
3. Each feature is multiplied by its weight and the results are summed — the
   **base score**, already inside 0-1 because the weights sum to 1.
4. The base score is mapped into one of two bands, depending on `task.blocked`.

Worked example — the checkout-crash task from
`developer_prod_crash_vs_backlog_feature` (operational, deadline three hours out):

```
feature values        time_pressure = 0.7273   impact = 1.0   quick_win = 0.75
operational weights            0.65             0.25            0.10
contributions                0.4727           0.2500          0.0750
                             ─────────────────────────────────────────
base                         0.4727 + 0.2500 + 0.0750       = 0.7977
not blocked        final =   0.30 + 0.70 x 0.7977           = 0.8584
```

The three contributions are what is returned in `ScoreResult.factors`, a
`ScoreFactors` carrying `contributions` (the weighted values above), `blocked`
(whether a blocked adjustment was applied) and `base_score` (the score before
that adjustment). They are *weighted* contributions, not raw feature values, so
the largest one is genuinely the dominant reason for the score — which is what
#358's explanations will need. In `report.json` it lands as:

```json
"factors": {"contributions": {"time_pressure": 0.4727, "impact": 0.25, "quick_win": 0.075},
            "blocked": false, "base_score": 0.7977}
```

`contributions` is a free mapping in the shared contract, deliberately: it
serves every ranking model, and one model's vocabulary should not be frozen into
a file all of them depend on. The vocabulary is pinned on the producing side
instead, in [`factors.py`](./factors.py), and a test asserts that every key this
package emits is a member of it — without that, #358's "the explanation's factors
match the score's factors" check would be comparing two free-form strings.

## The three rules

`tier` is **not** a base score and not an urgency level. The schema defines it as
what *kind* of task this is, and a CEO can hold an operational task just as a
developer can hold a strategic one. Here it acts as a switch selecting which
yardstick measures the task. All three yardsticks report onto the same 0-1
scale, so tasks of different tiers remain comparable inside one scenario.

| tier | `time_pressure` | `impact` | `quick_win` | `initiative` | half-pressing at | no deadline reads as |
|---|---|---|---|---|---|---|
| `operational` | 0.65 | 0.25 | 0.10 | — | 8 hours | 0.15 |
| `tactical` | 0.40 | 0.55 | 0.05 | — | 72 hours | 0.30 |
| `strategic` | 0.20 | 0.75 | — | 0.05 | 336 hours | 0.50 |

## The features

All live in [`features.py`](./features.py) and are deliberately **tier-agnostic**:
a tier's rule is expressed entirely by which features it uses and how it weighs
them, never by branching inside a feature. That separation is what keeps the
three rules genuinely distinct rather than three names for one formula.

| feature | what it reads | how |
|---|---|---|
| `deadline_proximity` | `deadline` vs `context.current_datetime` | `1 / (1 + hours_remaining / scale_hours)`. One curve, one parameter per tier: hours for operational work, days for tactical, weeks for strategic |
| `stated_urgency` | `title` + `description` | Time pressure asserted in words. **Max** over matched phrases — several ways of saying a system is on fire still describe one fire |
| `time_pressure` | the two above | Their **max**: either source can establish urgency, neither is paid twice |
| `impact` | `title` + `description` | **Sum** of matched phrase weights onto a neutral base of 0.5, clamped. Impact genuinely accumulates; a customer-facing outage with an SLA attached is worse than either signal alone |
| `quick_win` | `effort_estimate` | `1 - min(effort, 120) / 120` |
| `belongs_to_initiative` | `parent_task_id` | 1.0 if a parent exists, 0.0 otherwise. No traversal |

### Why urgency can be stated in words

The persona set contains correct answers with **no deadline at all** that are
nevertheless the most time-critical thing on the list: a top customer's
production that has been down for thirty minutes, an export job corrupting more
rows every run. Read through the `deadline` field alone these score as "not
urgent" and lose to routine items that happen to carry a date — the exact
inversion this system exists to prevent. Taking the max of the dated and the
stated reading fixes it without letting either double-count.

This was not in the original feature plan; it was added after the dev scenarios
showed the failure, and it is the main thing separating this model from a
nearest-deadline heuristic.

## Normalisation

Every feature is already 0-1, and each rule's weights sum to 1.0, so a rule's
weighted sum is inside 0-1 by construction — no clamping to hide a range error.
**The tier changes the weights, not the range.**

Per-tier output bands were considered and rejected: giving, say, strategic tasks
the range [0.6, 1.0] and operational tasks [0.0, 0.6] would decide in advance
that one kind of task always beats another, which is precisely what the
`cross_tier/` scenarios exist to test rather than assume.

The final clamp in `score()` is a contract guard, not part of the method.

## `blocked` is a band, not a zero

A blocked task's base score maps into `[0, 0.25]`; a startable one's into
`[0.30, 1]`. Because the ceiling is strictly below the floor, **a blocked task
can never outrank a startable one** — at any tier, with any deadline. This is
arithmetic, not a tendency, and
[`../../../tests/test_scoring.py`](../../../tests/test_scoring.py) pins it down
across 180 combinations of tier, deadline, wording, effort and blocked state.

Zeroing blocked tasks outright was the obvious alternative and is what
`SkipBlockedNearestDeadlineModel` does. It was rejected for three reasons:

1. "Blocked" means *not startable right now*, not *worthless*. Declaring a task
   undoable is a feasibility judgment, and feasibility is Chain's call.
2. A single 0.0 makes every blocked task identical, so a consumer cannot tell a
   critical blocked task from a forgotten one.
3. If every candidate in a scenario were blocked, all scores would tie and the
   harness's `max` would return whichever task appears first in the file — the
   answer decided by line order. The band still ranks them sensibly.

On the persona set the two behave identically: in the 20 dev scenarios the
expected answer is never a blocked task and no scenario is entirely blocked, so
this choice costs nothing measurable and keeps information downstream needs.

## The two nullable fields

**`deadline is None`** returns that tier's stated fallback, never 0.0, and the
fallbacks differ on purpose: `0.15` operational (undated operational work is
usually genuinely not pressing), `0.30` tactical, `0.50` strategic. At strategic
tier most real work carries no date, so the absence of one is **neutral
evidence**, not evidence of unimportance. `stated_urgency` can still override any
of these.

An **already-passed** deadline returns the maximum, 1.0. Overdue is not by itself
a reason to deprioritise: the set deliberately contains overdue tasks pulling in
both directions — one where the moment has passed and the task is now near
worthless, one where it is still the most urgent thing on the list. Which is
which is decided by `impact`, not by the timestamp.

**`parent_task_id is None`** contributes nothing and cannot crash. In the current
persona set this field is `null` in every task, so this feature **never fires and
changes no number today**. It exists so the behaviour is defined rather than
accidental, and so the rule is already written when week 7 fills the field in.

## Why these weights

Three strengths of justification, and it is worth keeping them apart — a number
with none of them has no defence.

**Derived from the issue's tier table.** The *ordering*. Deadline weight falls
across the tiers (.65 > .40 > .20) because deadline proximity is "the dominant
signal" operationally, "matters but less sharply" tactically and "matters least"
strategically. Impact rises as its mirror (.25 < .55 < .75). The two series cross
exactly once, between operational and tactical — that crossing *is* the
segmentation. At operational, .65 > .35 satisfies the strong reading of
"dominant": the deadline outweighs everything else combined.

**Bounded by argument.** `quick_win` is capped at .10 and drops to .05 because
`effort_estimate` is Chain's input and `eval/personas/README.md` forbids a
scenario's correct answer from hinging on it; at these weights it can only break
a near-tie, never decide an ordering. It is dropped entirely at strategic, where
a 40-minute item is not a "quick win" in any useful sense. `initiative` takes the
smallest non-zero weight in the model, matching the issue's "weak signal".

The blocked ceiling is bounded the same way: a startable but utterly bland task
scores around 0.30, so the ceiling has to sit below that or the guarantee fails.
0.25 clears it; 0.30 would not.

**Calibrated on the dev split only.** The exact decimals, the `scale_hours`
values and the missing-deadline fallbacks. The ordering above constrains them;
the dev scenarios chose among the options the ordering allows.

The honest summary, and the answer to "why 0.65 and not 0.62": the *ordering* is
derived, the *bounds* are argued, and the decimals were fitted to 20 scenarios
and then measured on 10 that were never read.

## Determinism

No LLM call, no clock read, no randomness, no dependence on dict iteration order.
The same task scores identically on every run. `reasoning` is a fixed template
over the factors for the same reason — text that changes between runs makes two
reports impossible to diff. Phrasing explanations properly is #358's job in
week 6, and it will be built from `factors`, not from this string.

A baseline that is not reproducible cannot be compared to the week-7 stretch,
which is the entire purpose of measuring it.

## How this was developed and measured

The 30 persona scenarios were split 20 / 10 **before any scoring code was
written** — see [`eval_split.py`](./eval_split.py) for the derivation and the
rules. Every weight, threshold and keyword was chosen against the 20 dev
scenarios; the 10 holdout scenarios were run **once**, at the end, and only
their aggregate accuracy was read.

| set | top-1 accuracy |
|---|---|
| dev (20) | 100% (20/20) |
| holdout (10) | 90% (9/10) |
| combined (30) | 96.7% (29/30) |
| `SkipBlockedNearestDeadlineModel`, the best cheap baseline | 66.7% (20/30) |

**Read the holdout number, not the dev number.** The lexicon was written from the
dev scenarios' own wording, so 100% there is close to meaningless as evidence of
generalisation. 90% on scenarios that were never read is the claim this model can
actually make.

The split is a development discipline, not a change to the eval set: R1's
reported baseline is still `make eval`, over all 30.

## Known limitations

- **The lexicon will not transfer to real extracted tasks.** It was written from
  30 hand-authored scenarios; tasks extracted from meetings and email will be
  worded differently. What survives is the *interface* — `impact(task) -> float`
  — so its body can be replaced (embeddings, a learned classifier) without
  touching the rules, the weights or the tests. A frozen lexicon is, however, the
  honest way to meet the reproducibility requirement for a baseline.
- **29/30 is close to the set's ceiling.** A set without headroom cannot show
  improvement, which is the problem the September rebuild fixed. Whether this
  number leaves R3's week-7 stretch room to demonstrate anything is R1's call to
  interpret, not this package's.
- **`initiative` never fires** on the current set, as noted above.

## Running it

```
pytest tests/test_scoring.py -v                       # the acceptance tests
python -m mind.scoring.dev_eval                       # dev split, full detail
python -m mind.scoring.dev_eval --split holdout       # holdout, aggregate only
python -m mind.evaluation.run --personas eval/personas --model segmented
```

The last one is R1's official path and needs `"segmented"` registered in the
`models` dict in [`../evaluation/run.py`](../evaluation/run.py), which is R1's
file.
