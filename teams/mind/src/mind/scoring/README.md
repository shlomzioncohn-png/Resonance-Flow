# scoring

Segmented ranking: one deterministic scoring rule per tier, producing the
per-task priority score MIND publishes.

- **Boundary.** MIND publishes **the per-task score**. Everything downstream —
  feasibility, free-time matching, deciding what actually gets done — belongs to
  Chain and Inbox. The contract enforces this by not handing the model
  `free_time_minutes` or `constraints`, and nothing here reintroduces them.
- **Hierarchy is not here at all.** The week-7 stretch (R3) propagates
  importance down the parent chain; this baseline does not read
  `parent_task_id`, does not declare it on its input, and knows nothing about
  hierarchy. That is deliberate — see *Why hierarchy is absent* below.
- **Explanations (#358, week 6)** are built on top of the raw factors this model
  returns; only a fixed template lives here.

## The contract

`SegmentedRankingModel.score(task, context) -> ScoreResult`, matching the
`RankingModel` protocol in [`../evaluation/interfaces.py`](../evaluation/interfaces.py).
It is a `Protocol`, so the class inherits from nothing. The model sees one
task at a time plus `context.current_datetime`, never the other candidates and
never the persona. The harness builds the ranking from the per-task scores.

The task it reads is a `ScorableTask` ([`inputs.py`](inputs.py)): the six
fields the model uses, plus `task_id`, which the score does not read but which
names the task — R3's week-7 propagation asks Chain's dependency API for a
task's ancestors by that id (SPEC-R3 §5). R1's scenario `Task` fits it unchanged,
so the harness runs as before; the live path can hand it a task built from
Inbox's, where three of those fields may be empty (see below). A mypy-only
check in `inputs.py` fails if R1's `Task` ever stops fitting.

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

| tier | `time_pressure` | `impact` | `quick_win` | half-pressing at | no deadline reads as |
|---|---|---|---|---|---|
| `operational` | 0.65 | 0.25 | 0.10 | 8 hours | 0.15 |
| `tactical` | 0.40 | 0.55 | 0.05 | 72 hours | 0.30 |
| `strategic` | 0.21 | 0.79 | — | 336 hours | 0.50 |

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

**The requirement is on the reachable range, not on the sum.** A weight parked on
a feature that can never leave 0 passes the sum check and still shortens that
rule's scale. That had actually happened: the strategic rule's `initiative`
weight was 0.05 of a sum of 1.0, but the feature returned 0 for every task in
the set, so the rule could only reach 0.95 while the other two reached 1.0 —
quietly penalising strategic tasks in exactly the `cross_tier/` scenarios where
tiers compete directly. `test_every_rule_can_reach_a_full_base_score` holds this
now, and would have failed on the old weights.

## Why hierarchy is absent

The baseline does not read `parent_task_id`, and `ScorableTask` does not declare
it. Three reasons, in the order they matter:

1. **The comparison depends on it.** Week 7's stretch propagates importance down
   the parent chain and is measured against this baseline. "Did hierarchy help?"
   is only answerable if the baseline never saw hierarchy at all.
2. **The signal is close to information-free in production.** In a real company
   almost every task sits under something — a developer task under a manager
   task under a feature goal — so "has a parent" is very nearly the constant 1.
3. **It was never measured.** `parent_task_id` is `null` in every scenario, so
   the feature always returned exactly 0 and its 0.05 was reasoned, not
   calibrated — while silently costing the strategic rule 5% of its scale (see
   *Normalisation*).

Removed at the team lead's request. Its 0.05 was redistributed between the two
remaining strategic weights **in proportion** — 0.20/0.95 and 0.75/0.95, giving
0.21 and 0.79 — so neither weight was re-chosen; only the share that had been
parked on a constant-0 feature moved. A test pins both the values and the
preserved ratio, and another asserts that a task scores identically with and
without a parent at every tier, so re-introducing the feature fails the suite.

R3 does not need the field on this input either: per SPEC-R3 §5 she obtains a
task's ancestors from Chain's dependency query API, keyed by `task_id`.

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

In the 20 dev scenarios the expected answer is never a blocked task and no
scenario is entirely blocked, so the band and a hard zero are indistinguishable
there — and the band keeps information downstream needs.

## Two fields that may be null even in the scenarios

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

**`parent_task_id`** is not read at all, and is not even declared on
`ScorableTask`, so there is no null case to define. A test asserts that the same
task scores identically with and without a parent, at every tier — see *Why
hierarchy is absent*.

## A task with fields missing

A real task can arrive without a tier, an effort estimate or a description:
extraction leaves them null on purpose when the content gives no basis (#351,
#352), and Inbox's Task allows it. The scenarios fill all three, so none of
this changes a persona score — all 91 score identically with and without it.

- **No tier: scored by the tactical rule** (`ASSUMED_TIER`). Tactical is the
  middle of both axes — 72 scale_hours between 8 and 336, weights between the
  other two — so it assumes the least. Only the choice of rule is assumed: the
  task's tier stays null, in the database and in what is sent to Chain, and the
  explanation says "task of unknown tier, scored as tactical". Logged as
  `tier_assumed` with the task's id. A task is re-scored on every update, so
  the null-tier rate is the number of **distinct ids**, never the number of
  lines. Counting null tiers in Inbox's tasks answers a slightly wider
  question — every task, not only the ones that were scored — so the two can
  differ without either being wrong.
  Deliberately not a factor: a null tier already says it was assumed, and a
  flag among the contributions would distort the explanation's leading factor.
- **No effort estimate: no quick_win bonus.** The bonus rewards a shortness we
  know about; without an estimate there is no evidence of one. That is the same
  0.0 a task past the cap gets, so a missing estimate is never a penalty.
- **No description: adds no words.** Without the guard, `f"{None}"` put the word
  "none" into the text the lexicons read.

### How far to trust such a score

`score_confidence(task)` ([`confidence.py`](confidence.py)) fills the
`confidence` field of `task.score.proposed`. It is 1.0 when nothing is missing
and drops by **the worst a missing field can do**, not the typical: Chain
orders by score, a gap of 0.05 already flips an order, and which tasks are
badly off cannot be known in advance.

| missing | penalty | where the number comes from |
|---|---|---|
| tier | `ASSUMED_TIER_SHIFT` (0.17) | **measured**: the 90th percentile of how far a persona task's base score moves when its tier is replaced by tactical (mean 0.063, max 0.352), over all 91 tasks — tactical ones included, since Chain cannot know whether the assumption was right. Assumes tasks with no tier spread across the tiers as the persona set does |
| effort estimate | quick_win's weight in the rule used | **derived**: the bonus cannot move a score further than its weight — 0.10 operational, 0.05 tactical, nothing at strategic |

Both missing compound by multiplication. It is a separate function rather than
a field on `ScoreResult`, which is R1's contract and has none.

Two things Chain has to know: in `task.score.proposed` neither `tier` nor
`effort_estimate_minutes` is optional, so a null arrives as the protobuf
default — an unspecified tier and **0 minutes**, which must read as "unknown",
not as a zero-minute task.

## Why these weights

Three strengths of justification, and it is worth keeping them apart — a number
with none of them has no defence.

**Derived from the issue's tier table.** The *ordering*. Deadline weight falls
across the tiers (.65 > .40 > .21) because deadline proximity is "the dominant
signal" operationally, "matters but less sharply" tactically and "matters least"
strategically. Impact rises as its mirror (.25 < .55 < .79). The two series cross
exactly once, between operational and tactical — that crossing *is* the
segmentation. At operational, .65 > .35 satisfies the strong reading of
"dominant": the deadline outweighs everything else combined.

**Bounded by argument.** `quick_win` is capped at .10 and drops to .05 because
`effort_estimate` is Chain's input and `eval/personas/README.md` forbids a
scenario's correct answer from hinging on it; at these weights it can only break
a near-tie, never decide an ordering. It is dropped entirely at strategic, where
a 40-minute item is not a "quick win" in any useful sense.

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

## How this was developed

The 30 persona scenarios were split 20 / 10 **before any scoring code was
written** — see [`eval_split.py`](./eval_split.py) for the derivation and the
rules. Every weight, threshold and keyword was chosen against the 20 dev
scenarios, and no holdout scenario was read while building this package.

#535 later added six scenarios. The thirty kept their halves and the six were
placed 4 / 2 by the same filename-only rules, so the split is now 24 / 12. The
weights here were chosen before those six existed, and none of them was read
for scoring. (The #352 effort-estimate work did search the text of every
persona task, these six included, for duration phrases — a narrow look, but a
look.) Where this README says "the 20 dev scenarios", it means the original
twenty, which is what those claims were checked against.

The split is a development discipline, not a change to the eval set and not a
measurement. **Scoring this model against the persona set is R1's**, through the
harness, over all 36 — so this package reports no accuracy number of its own and
there is exactly one place such a number comes from.

What the split lets this package claim without measuring anything is the part
that matters for trusting that number: the weights behind it were not fitted to
the scenarios it will be computed on, and the lexicon was written without
reading a third of the set.

## Known limitations

- **The lexicon will not transfer to real extracted tasks.** It was written from
  the wording of 20 hand-authored scenarios — the dev split, never the holdout;
  tasks extracted from meetings and email will be worded differently. What
  survives is the *interface* — `impact(task) -> float` — so its body can be
  replaced (embeddings, a learned classifier) without touching the rules, the
  weights or the tests. A frozen lexicon is, however, the honest way to meet
  the reproducibility requirement for a baseline.
- **The set may have little headroom left for the stretch.** A set a baseline
  nearly saturates cannot show improvement, which is the problem the September
  rebuild fixed. Whether that is the case here, and what it means for R3's
  week-7 stretch, is R1's call to read off the reported numbers — not this
  package's to assert.
- **`ASSUMED_TIER_SHIFT` was measured on synthetic tasks.** The spread on real
  extracted ones may differ; re-measure once #353 produces them.

## Running it

```
pytest tests/test_scoring.py -v                  # the acceptance tests
pytest tests/test_scoring_missing_fields.py -v   # no tier, no estimate, no description
```

That is all this package runs. Evaluating the model against the persona set goes
through the harness and belongs to R1; it needs `"segmented"` registered in the
`models` dict in [`../evaluation/run.py`](../evaluation/run.py), which is R1's
file.
