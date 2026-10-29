# scoring

Segmented ranking: one deterministic scoring rule per tier, producing the
per-task priority score MIND publishes.

- **Boundary.** MIND publishes **the per-task score**. Everything downstream —
  feasibility, free-time matching, deciding what actually gets done — belongs to
  Chain and Inbox. The contract enforces this by not handing the model
  `free_time_minutes` or `constraints`, and nothing here reintroduces them.
- **Hierarchy is not in the baseline.** `SegmentedRankingModel` does not read
  `parent_task_id`, does not declare it on its input, and knows nothing about
  hierarchy — deliberate, see *Why hierarchy is absent* below.
  `HierarchicalRankingModel` (#474) layers propagation on top of it,
  unmodified, in production only — see *Hierarchical propagation* below.
- **Explanations (#358)** are a fixed template over the score's own values, in
  `ScoreResult.reasoning` — see *Explanations* below. No LLM, and no change to
  `task.score.proposed`: consumers keep reading `factors`.

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
that adjustment). They are *weighted* contributions, not raw feature values.
The largest is not by itself the reason for the score: a neutral impact at
strategic contributes 0.395 and says nothing. The explanation reads each
factor's distance from neutral instead — see *Explanations* below. In
`report.json` it lands as:

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

## Hierarchical propagation (#474)

`HierarchicalRankingModel` ([`hierarchical_model.py`](hierarchical_model.py))
layers on top of `SegmentedRankingModel` without changing it: it calls the
baseline for a task's own features, then adds what its ancestors contribute.
It implements `RankingModel` (and `TaskScorer`, the live path's wider
equivalent) exactly the baseline does, so R1's harness and `score_publisher.py`
both run it with no special-casing.

**The formula.** `base_score = 0.93*own_base + 0.07*mean(ancestor importance)`
when the task has at least one (known) ancestor, `own_base` unchanged
otherwise — walking every ancestor up to the root, **undiminished — no depth
decay**. The weight started at 0.5/0.5; revised down to 0.93/0.07 (review on
#474) so a task's own urgency dominates and an ancestor's importance is a
small nudge, not an equal vote. No decay rate or curve is written down
anywhere in the roadmap or the team-role documents, so inventing one now
would bake an unfounded guess into the one thing (#473's seven constructed
graphs) that is supposed to have a hand-verified, defensible answer. Revisit
this deliberately if real propagated data ever shows deep descendants
overweighted — don't drift into it. Averaging, not summing, is a deliberate,
explicit decision over an earlier additive design: own_base and every
ancestor's importance are already in [0, 1], so the weighted average never
leaves that range either — no clamping, no separate normalization step
before this is used for anything published. One consequence worth naming:
this does **not** satisfy `tests/propagation_fixtures/README.md`'s "a parent
can only add, never remove" assumption, written for the additive design it
describes — an unimportant ancestor's mean can still pull a task's own
baseline down, now only very slightly at 7%, rather than potentially all the
way below it the way a 50% weight could (#473's 02_branch's
`task_low_1`/`task_low_2` were exactly this, at the old weight). All seven
fixtures were re-checked by hand against this formula and still produce the
stated order regardless.

**Cycle: detect and refuse, not a depth limit.** A cyclic `parent_task_id`
chain raises `CycleError` rather than looping forever —
`tests/propagation_fixtures/06_cycle.json` is built to catch exactly a naive
walk-the-parent-chain implementation here. `HierarchyFixture` deliberately
does not forbid a cycle (unlike the eval's own hierarchy fixture, which
`mind.evaluation.hierarchy` checks for one), so this package's own code has
to defend itself regardless of what any one source happens to avoid today.

**Where an ancestor's importance comes from — two sources, by necessity:**

| | `FixtureAncestorSource` | `ChainAncestorSource` |
|---|---|---|
| used by | the evaluation harness, `#473`'s fixtures | the live path (`score_publisher_runner.py`) |
| walks | the whole chain, in memory | the whole chain, via `/ancestors` |
| importance | `HierarchyNode.importance` — hand-picked, already Mind's own notion of importance | each ancestor's own `base_score`, from re-scoring `ScorePublisher.cached_task(ancestor_id)` with the same baseline |

Production needs a different source, not by choice: Inbox's `task.created`/
`task.updated` never carry `parent_task_id` (`InboxTask.parent_task_id` is
always `None`), so there is no local chain to walk — and Chain's own
`/ancestors` has no `importance` field at all, only its own, unrelated
`score`. `ChainAncestorSource` asks Chain for the whole ancestor chain
(`ChainHierarchyClient.get_ancestors`, nearest-parent-first), then for each
one re-scores `ScorePublisher.cached_task(ancestor_id)` — the ancestor's own
last-known Inbox state — with the same baseline this model already uses,
to get that ancestor's own `base_score`. Reading each ancestor's own value
this way, rather than reading just the direct parent's already-propagated
score, is a necessary change from an earlier design, not a stylistic one:
summing an undiminished total composes recursively (a parent's own
propagated total already contains everything above it, so reading it alone
was equivalent to summing the whole chain), but averaging does not — a
parent's own *mean* already baked in its own ancestors at its own 93/7
weight, which is not the same number a grandchild's 93/7 blend with the
*raw* grandparent value needs. There is therefore no shortcut left that
reads fewer than every ancestor. Using Chain's own `score` instead was
considered and rejected: it is computed by a different, unrelated
algorithm, in a scale this formula was never checked against — mixing it in
would mean every fixture's hand-verified arithmetic no longer describes
what production actually does.

**Fallback.** Every failure in `ChainHierarchyClient` — timeout, connection
refused, a non-2xx, a body that isn't the expected shape — is logged and
degrades to "no ancestors" (`get_ancestors` returns `[]`), never raised.
With no ancestors, `ancestor_values` is exactly `[]`, so `base_score` is
exactly `own_base` — the same number the baseline alone would have
produced. A slow Chain fails the same way: the client's own timeout
(`MIND_CHAIN__TIMEOUT_SECONDS`, default 0.2s — round-9 review: at the old 2s
default, two such calls under `ScorePublisher`'s lock could cost up to 4s of
every other task's scoring) is short on purpose, so a slow traversal
degrades to the baseline rather than stalling the scoring loop.
An ancestor with no cached state yet (no Inbox event reached it since
start-up) is left out of the average rather than counted as 0 — see *Known
limitations* below.

**The rescore cascade.** A score can change without any Inbox event at all:
an ancestor's own value moved, so every descendant's computed base is now
stale. `ScorePublisher._score_and_send` owns this, not the model: after a
send whose `mind_score` differs from the last one published by at least
`_CASCADE_CHANGE_TOLERANCE` (or this is the task's first score ever), it
asks Chain for that task's **direct children only**
(`ChainHierarchyClient.get_direct_children`, not the full, transitive
`/descendants`) and calls `rescore(child_id)` for each. Each child, once
re-scored, makes the same decision for its own children — the cascade
reaches exactly as deep as something actually changed, one level at a time,
through the queue `rescore` already has. This correctly reaches a whole
changed subtree even though each descendant reads its own ancestors' raw
values directly rather than through its parent's result: a grandchild
recomputes its own mean (which includes the changed ancestor) when its
parent asks it to, regardless of whether the parent's *own* mind_score
happened to move too.

A fixed tolerance on the difference, not exact equality and not rounding,
is what actually stops a cycle or a settled subtree. An earlier version
compared `mind_score` exactly, with the trigger living inside the model's
own `score()` rather than behind this dedup at all, so it fired on every
call regardless of whether anything was sent. Exact equality never holds
in practice: `mind_score` bakes in time pressure, which drifts by a tiny
amount every single call just because real time passed, not because
anything about the task changed. A later version rounded `mind_score`
before comparing, which absorbed that drift but introduced its own bucket
edges: two values a hair apart could round to different buckets (a false
"changed"), and two values nearly `_CASCADE_CHANGE_TOLERANCE` apart could
round to the same one (a missed real change). Comparing `abs(new - old)`
against the tolerance directly has neither failure mode. Two tasks that
are each other's ancestor form a contracting average (each one 50% its
own value), so the *real* difference between rounds shrinks to nothing —
the tolerance just has to outlast that drift to notice the real
convergence and stop. Asking for the full subtree up front (`/descendants`)
was considered and rejected: it would re-score everything under a changed
goal at once, even descendants whose own score will not have moved,
exactly the burst of work *Mind the latency* below warns against.

**Known gap, not fixed here:** a cascade that reaches a task Mind has not
scored since start-up (see *Known limitations* below) is not retried once
that task becomes known — only once *it* is scored does its own first-score
cascade fire, and nothing proactively asks it to score sooner.

**Latency.** Production's `score()` makes one HTTP call to Chain per scored
task (`get_ancestors`, returning the whole chain in that one call — no
further network calls per ancestor; each one's own value is a local
re-score of its already-cached state), plus one more (`get_direct_children`)
on a genuine change, cascading — but, as of round-9, that second call runs
*after* `ScorePublisher`'s lock is released, not while scoring holds it (see
*Known limitations* below). Both go through one client, one short timeout,
no retry (a retry here would only make a slow Chain slower; the fallback
already handles it). **Still not measured against a live Chain** — there is
none in this dev environment to reach, and that is the number that matters
for the under-300ms p95 commitment. What *was* measured, with
`benchmarks/hierarchy_propagation_latency.py`: both calls run through the
real `ChainHierarchyClient` against an `httpx.MockTransport` serving #473's
seven constructed graphs — no socket, no real Chain. Mean 0.43ms, p95
0.59ms, max 1.24ms per call, 64 calls total. That number is this code
path's *own* overhead (request construction, parsing a fake response) and
shows Mind's side is not the bottleneck; it says nothing about real network
round-trip time to Chain, which is the actual open question and which this
cannot measure. The same script also runs `HierarchicalRankingModel.score()`
against `SegmentedRankingModel.score()` on the same leaf tasks, same mock
transport, to isolate what the whole feature adds over the baseline alone
(not just the raw Chain calls above, but also re-scoring every ancestor
through the baseline): mean difference 0.61ms, p95 1.14ms, over the 15 leaf
tasks across all seven fixtures. Re-run both numbers with a real
`ChainHierarchyClient` once this is deployed where Chain is reachable, and
report that — not this — against the commitment. The
harness path (`FixtureAncestorSource`) makes no network call at all — an
in-memory walk of a graph with at most a few dozen nodes — so the
`hierarchical` model's own harness run time is not informative about the
live path's. See *Running it* below for how to run the harness against this
model.

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

One thing Chain has to know: in `task.score.proposed`, `tier` is not optional,
so a null arrives as the protobuf default, an unspecified tier, which must read
as "unknown".

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

## Explanations

`reasoning` says why a task scored the way it did, in one sentence built from
the same values the score was computed from:

```
operational task, scores high: the deadline is 3 hours away; it mentions "sla", "customer".
tactical task, scores low: its wording plays it down ("housekeeping", "routine"); the deadline is 6 days away.
strategic task, scores in the middle: no factor stands out; no deadline given.
```

### What counts as a reason

Every rule's weights sum to 1.0, so a task with every feature at 0.5 has a base
score of exactly 0.5 under any tier. That makes each factor's **shift**,
`weight * (value - 0.5)`, its exact share of how far the score moved from
neutral: the shifts add up to `base - 0.5`. A factor is named when its shift is
more than `REASON_SHIFT` (0.05) either way.

The shift, not the raw feature value, is what decides, because the raw value
ignores the weight. An earlier draft named any feature value of 0.6 or more. On
the 24 dev scenarios that called the effort estimate a reason in 43 of 45
tasks and gave three expected-top tasks the sole explanation "it takes about
35" or "40 minutes", for a factor that had moved their score by about 0.01: a real
number explained by something that did not produce it. Strategic deadlines had
the same problem, named in all 13 tasks at a weight of 0.21.

0.05 is not fitted. It is the most `quick_win` can move any score (its largest
weight, 0.10, times 0.5), and the comparison is strict, so it reads as *a reason
is something that moved the score further than the tie-breaker can* — even a
0-minute estimate, which reaches 0.05 exactly, stops short. On the dev split 0.03 brings effort
back as a reason in 14 tasks, and 0.08 drops real impact words such as
"sign-off" at strategic; 0.05 sits between the two.

Consequences worth knowing:

- **Effort is never named as a reason**, which matches what it is for: it only
  breaks near-ties. A missing estimate is still stated, and is not mentioned at
  strategic, which does not weigh effort.
- **The tier's weights carry through.** "customer" moves an operational score
  by 0.04 and is not named; it moves a strategic one by 0.12 and is.
- **A missing deadline needs no special case**: a strong reason against at
  operational (−0.23), a weaker one at tactical (−0.08), and exactly neutral at
  strategic, where it is stated as missing rather than as a reason.

### The sentence

- **Verdict** on the base score, symmetric around 0.5: *high* at or above
  `HIGH_BASE_SCORE` (0.6), *low* below `LOW_BASE_SCORE` (0.4), otherwise *in
  the middle*. It says *scores*, never *ranks*, because it describes this
  task's own score, not its place in a list: on the 36 persona scenarios the
  model's top task scores in the middle in 8 and low in 2, because its rivals
  score lower still. "Ranks low" for the top task would read as a
  contradiction; "scores low" is simply true, and why it still comes first is
  Chain's ordering to explain, not MIND's. A blocked task says *would score*,
  since its final score sits below every startable one.
- **A high or low verdict always names something.** Several factors can each
  stay under the threshold and still add up to a high or low base; then the
  ones that leaned the verdict's way are named, never the tie-breaker.
- **Reasons** are this tier's factors only, largest shift first, each with its
  real value: the hours or days to the deadline, the words that moved impact
  (a stem such as `escalat*` is printed as the word written, "escalated").
  Impact words are quoted, as the urgency phrase is: many lexicon entries are
  not nouns ("needs your", "must reach"), and bare after "it mentions" they
  would not read as a sentence.
  Half hours round up, so 2.5 hours reads "3 hours" — not Python's `round`,
  which would send 2.5 to 2 but 3.5 to 4.
- **Time pressure names its source.** It is the max of the deadline and the
  wording, so when the wording won the sentence quotes the phrase and does not
  credit the deadline. A phrase in both lexicons ("data is at risk") is quoted
  once, not again as an impact word.
- **Missing data is stated**: "no deadline given", "no effort estimate" — the
  deadline even when the wording, not the absent date, set the time pressure.

`SegmentedRankingModel.explain(task, context)` returns the same text plus the
factors it names and every factor's shift. `tests/test_explanations.py` walks an
input grid covering every branch and checks that the named factors are the
score's own, that the text talks about exactly those factors and no others,
and that each shift is recomputed from the features themselves — which is what
keeps the text from describing a score it did not come from.

## Determinism

No LLM call, no clock read, no randomness, no dependence on dict iteration order.
The same task scores identically on every run. `reasoning` is a fixed template
over the factors for the same reason — text that changes between runs makes two
reports impossible to diff. "Hours away" is measured from
`context.current_datetime`, never from the clock.

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
- **An ancestor Mind has not scored since start-up looks like "no such
  ancestor," not "unknown."** `ChainAncestorSource` leaves an unscored
  ancestor out of the average rather than counting it as 0 (0 would be a
  real, wrong claim about its importance), but if *every* one of a task's
  ancestors is still unscored, that task reads as a root — `ChainAncestorSource`
  cannot distinguish "this task has no parent" from "it has one, but Mind
  hasn't seen it score yet." In a chain A → B → C, this does **not**
  reliably self-correct if B is the gap: A's change reaches B's cascade
  attempt, but `rescore(B)` finds B unknown and drops it there — C never
  hears about it until B itself gets scored by *its own* Inbox event (not
  one that happens to arrive, one that genuinely does), at which point B's
  first-ever score unconditionally cascades onward. Nothing proactively
  retries a gap at B; it is a real, not merely theoretical, way for a
  change to stay stuck.
- **A failed ancestor lookup is never retried once Chain recovers.**
  `ChainAncestorSource.ancestor_values` degrades a timeout, a 404 (not yet
  processed), or an outage to "no ancestors" the same way it degrades a
  genuine root — the task is scored and published without its ancestors'
  share, and nothing re-scores it later just because Chain came back. It
  only recovers if something else independently triggers a rescore (a real
  change to the task's own fields, or a cascade reaching it from one of its
  actual ancestors changing after Chain is back up).
- **`get_ancestors` still runs while `ScorePublisher`'s lock is held.**
  Both consumer threads and the rescore worker share one lock, and scoring
  a task needs its ancestors' values synchronously to compute `base_score`
  — there is no way to move that call outside the lock without changing
  what gets scored. A slow Chain here still blocks every other task's
  scoring meanwhile, bounded by `MIND_CHAIN__TIMEOUT_SECONDS` (0.2s default,
  round-9 review — down from an earlier 2s). `get_direct_children`, the
  cascade's own call, was moved outside the lock in round-9:
  `_score_and_send` now returns the cascade's target id instead of calling
  `_cascade` itself, so both `_publish_if_changed` and `_rescore_now` run it
  after releasing the lock. `tests/test_hierarchical_propagation.py`'s
  `test_a_genuinely_slow_chain_fails_fast_not_at_the_old_2s_default` proves
  the timeout itself is enforced (against a real local socket —
  `httpx.MockTransport` does not enforce a client's configured timeout at
  all), not that the lock is never held during a slow call; the one
  remaining locked call is a smaller, now-bounded version of the same
  documented risk, not a new one. Not yet measured against a live Chain to
  know whether even the 0.2s bound matters in practice — see *Hierarchical
  propagation*'s own *Latency* section above.

## Publishing the score to Chain (#357)

Runs inside MIND's API process (#920): it reads Inbox's `task.created` and
`task.updated`, scores each task with this model, and publishes
`task.score.proposed` to Chain. See "Running inside the API" below.

| File | Role |
|---|---|
| `inbox_task.py` | Inbox event → `InboxTask` (fits `ScorableTask`). Empty fields stay empty. A task or tenant id that is not a UUID raises `InvalidInboxEvent`. |
| `score_event.py` | `ScoreResult` → `TaskScoreProposed` (`contracts/`). |
| `kafka_sink.py` | `TaskScoreProposed` → shared/proto's `TaskScoreProposedEvent`, published keyed by `task_id`. |
| `score_publisher.py` | The handlers, and when to publish. |
| `score_publisher_runner.py` | Starts it from the API: one consumer thread per topic, plus the rescore worker. |

The payload, as agreed with Chain on #133: `event_id`, `tenant_id`, `task_id`,
`mind_score` (the **`base_score`**, never the blocked-adjusted `score`: blocking
is Chain's call), `scored_at`, `confidence` (`score_confidence`), `factors`, and
`tier` as Inbox sent it. An unclassified task is scored by the tactical rule, but
its tier goes out unset.

When a score is published:

- **On `task.created`, and on `task.updated` only when a field the model reads
  changes** (title, description, tier, deadline, effort). Every score makes Chain
  publish `task.scheduling.updated`, which makes Inbox emit `task.updated`;
  publishing on every update would loop forever. `urgency` is not a trigger: the
  model never reads that field.
- **Older events are dropped.** The two topics have no ordering between them, so
  each task keeps the newest Inbox `created_at` / `updated_at` seen. At an equal
  time, `task.created` counts as older.
- **Errors that would repeat are skipped, not retried.** Converting the event,
  scoring the task or building the payload fails the same way on every
  redelivery (a malformed id, a result without `base_score`, a score outside
  0..1, a model bug for one task). Propagating it would kill the process on the
  same message forever, so it is logged with the task id and skipped. Only a
  failure to send to Kafka propagates: it ends that run of the consumer with
  the message uncommitted, and the thread runs again after a short wait and
  reads it again (see "Running inside the API"). A re-score whose send fails is
  queued back the same way. Anything the
  model raises counts as permanent, so a model must not raise on a transient
  failure (#474 already requires falling back to the baseline score).
- **When propagation changes a score without an Inbox event** (#474:
  `ScorePublisher._cascade` detects the just-sent `mind_score` moved by at
  least `_CASCADE_CHANGE_TOLERANCE`), it asks Chain for that task's direct
  children and calls `ScorePublisher.rescore(child_id)` for each. That only
  queues the id, so it is safe from anywhere, including from inside the
  model's own `score()`. A worker thread scores each queued child again
  with the publisher's own model — `score_publisher_runner.py` wires
  `HierarchicalRankingModel` in as that model, replacing the baseline here
  only, in production; R1's harness still scores the unmodified baseline
  for comparison — on its newest state,
  under the same lock as the event handlers. A request for a task already
  queued is dropped, and a re-score whose score is unchanged is not sent, so a
  cycle in the hierarchy cannot flood Chain; propagation only asks a child's
  children when that child's own score actually changed. `rescore` returns
  nothing. The state is in memory, so a child no Inbox event has reached
  since start-up is logged and skipped until its next event. The other side
  of this integration, `ScorePublisher.cached_task(task_id)`, is how
  `ChainAncestorSource` re-scores an ancestor's own state — see *Hierarchical
  propagation* above.
- **The lock is held while the model scores** (#474's `ChainHierarchyClient.get_ancestors`),
  so a slow Chain holds up the other topic meanwhile, bounded by
  `MIND_CHAIN__TIMEOUT_SECONDS` -- `_cascade`'s own call does not add to this,
  since round-9 moved it outside the lock. See *Known limitations* above.

What was last published lives in memory, so a restart re-publishes each task
once on its next event. A score is not re-sent just because time passes.

## Running inside the API (#920)

The publisher runs in background threads of MIND's API, the way Chain's and
Inbox's Kafka consumers run inside their own services, so it deploys with the
API and needs no entry of its own in Relay's chart.

- **Started from the API's lifespan** (`api/main.py`) by `start_score_publisher()`,
  **only when Kafka is configured** (the chart's config file sets
  `kafka.bootstrap_servers` and `kafka.schema_registry_url`). Locally and in
  tests, with no kafka section, the API starts without it and
  `app.state.score_publisher` is `None`. Only one of the two keys set is a
  broken config (#481): start-up stops with a `ConfigFileError` naming the
  missing key, before any thread starts.
- **Stopped on shutdown**: the consumers end within a second and leave their
  group cleanly, instead of Kafka waiting for a session timeout.
- **A failed thread runs again on its own, and never takes the API down.** The
  threads run through `messaging/background.py`'s `BackgroundThreads`: when a
  run raises (a Kafka send failure), it is logged and run again after a wait
  that doubles each time, 1 s up to 60 s, as the `task.created` listener does.
  Each run of a consumer builds a new one, so the uncommitted message is read
  again: the recovery a pod restart would give, without one. There is no
  `os._exit`, and the retries do not give up.
- **`app.state.score_publisher.healthy`** is false only if a thread has died,
  which the retries should never let happen. A Kafka outage, including Kafka
  being down when the API starts, stays healthy and is retried inside the
  thread. That makes it safe for liveness (#481's `/health/live`): a broker
  that is down never restarts the API, nor loops a pod that starts without it.
  Recovery does not depend on it. It is `None` when Kafka isn't configured.
- **A thread that keeps failing is visible, not silent.** A bad message at the
  head of a topic, or a wrong setting, fails on every run, so it never
  recovers and Chain gets no scores from that topic, while `healthy` stays
  true. `app.state.score_publisher.retrying` names the threads that failed
  and have not recovered since. A thread recovers by handling a message or
  finishing a re-score, or by running 10 minutes with nothing to do and no
  new failure; not by merely running a while, since a send to a broker that
  is down blocks for up to 5 minutes before it fails. Each failure is logged
  as a WARNING; once the wait between runs reaches 60 s, every retry is
  logged at ERROR: `background: thread <name> keeps failing (<n> times in a
  row); running it again every 60.0s`. So a short outage isn't an alert, and
  a stuck thread is.
- **One consumer group per topic:** `mind-score-publisher-task-created` and
  `mind-score-publisher-task-updated`. In one shared group, every retry of
  one topic's consumer (it leaves the group and joins again) would rebalance
  the other topic's consumer too. New groups start from the beginning of
  their topic (`auto.offset.reset: earliest`).
- **The publisher's lock is held during the Kafka send.** It keeps each task's
  events in order, and a score recorded only once it was sent. So while a
  send hangs, the other two threads wait on it and don't show in `retrying`.
  A hung send fails only after about 5 minutes (librdkafka's
  `message.timeout.ms`); from then on that thread is in `retrying`. Its
  ERROR starts at the 7th failure in a row, when the wait reaches 60 s:
  about 36 minutes after the send first hung (7 x 5 min + 63 s of waits).
- **MIND must run as one replica (`replicaCount: 1`) with one uvicorn worker.**
  The publisher keeps its state in memory: a second copy would split the
  topics between two states, and the stale-event and loop checks would break.
  Raise it with Mind before scaling the API.

`BackgroundThreads` is generic: any Kafka consumer of MIND can run inside the
API the same way, with a target of the form
`lambda stop: consumer.run(Message, handle, stop=stop)`.

## Running it

```
pytest tests/test_scoring.py -v                  # the acceptance tests
pytest tests/test_scoring_missing_fields.py -v   # no tier, no estimate, no description
pytest tests/test_score_publish.py tests/test_score_event.py \
       tests/test_inbox_task.py tests/test_kafka_sink.py -v   # publishing to Chain
pytest tests/test_hierarchical_propagation.py -v              # #474: propagation, the Chain
                                                                 # adapter's fallback, the cascade
```

Evaluating a model against the persona set goes through the harness:

```
python -m mind.evaluation.run --personas eval/personas --model hierarchical
```

`"hierarchical"` is registered in `run.py`'s `models` dict, built from the
real `eval/hierarchy/nodes.json` the harness already loads and validates.
`"segmented"` (the baseline alone, for R1's own comparison) is not — that
registration is R1's file, [`../evaluation/run.py`](../evaluation/run.py),
and hers to add.
