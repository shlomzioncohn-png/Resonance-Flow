# MIND Evaluation Harness

This directory contains the evaluation engine for the **Resonance Flow MIND team**.

The harness is designed to:

- Load a fixed set of persona scenarios.
- Execute a ranking model against those scenarios through a uniform interface.
- Compute performance metrics for the evaluated model.

## Project Context

- **Immutable Scenarios:** Scores a ranking model against `eval/personas/` — a fixed scenario set, never touched mid-comparison.
- **Foundation Gate:** Must be runnable by someone outside the MIND team.
- **Lifecycle:** Used to score the baseline (week 5; R2 publishes those scores to Chain in week 6) and for the stretch-goal comparison (week 7, demo day week 9).

## The Model Interface (Contract) — closed, approved

Every ranking model must implement `RankingModel` (`interfaces.py`) — a `typing.Protocol`,
so a model satisfies the contract structurally, without inheriting from it:

```python
class RankingModel(Protocol):
    def score(self, task: Task, context: ScoreContext) -> ScoreResult:
        """Return a ScoreResult (0-1 priority score) for a single task, given the
        current time via context. Higher score = higher priority."""
        ...
```

- `Task` (`interfaces.py`): `task_id`, `title`, `description`, `deadline`,
  `effort_estimate` (minutes; the eval set's form of the canonical
  `Task.effort_estimate`), `tier`, `blocked`, `parent_task_id` (nullable; points
  at a node in `eval/hierarchy/nodes.json` — see **Hierarchy** below).
  `description`, `effort_estimate` and `tier` may be null, and `task_id` may be a
  lowercase UUID, as on real tasks (#175 — see **Real pipeline output through
  the harness** below).
- `ScoreContext`: just `current_datetime` — the model may judge how near a
  `deadline` is, and nothing else about the persona's situation (per the mentor's
  review). The harness fixes it to the scenario's `current_datetime`; it is never
  the real wall clock.
- `ScoreResult`: `score` (float on a global **0-1** scale — each tier may be
  scored by its own rule, but results are calibrated onto one scale so tiers are
  comparable) and an optional `reasoning` string (kept in the report for later
  comparison against `expected_reason`, not used by the harness), plus optional
  `factors` (`ScoreFactors`, `None` = no breakdown) — what produced the score:
  - `contributions`: `{name: float}`, at least one entry, finite values (negative
    allowed), not required to sum to `score`. The contract names no factors:
    each model owns its own fixed vocabulary, and the test that keeps its keys
    inside it, in its own package.
  - `blocked`: whether the model applied a blocked adjustment to this score.
  - `base_score`: the score before that adjustment, when the model has one.

  Carried per candidate into the report (a copy, not the model's object), never
  used for ranking; it's there so an explanation step can be built from the
  real inputs to the score.

The report itself is Pydantic too, all in `harness.py`: `report.json` is an
`EvaluationOutput` (`model_name`, `combined`, `groups`), each report a `Report`
→ `ScenarioResult` → `CandidateScore`. So the file read back with
`EvaluationOutput.model_validate_json(...)` is validated the same way as a report
built by `run()`: unknown fields, NaN/inf and malformed factors fail on load.
`run.py` also refuses to write NaN/inf to `report.json` rather than produce
invalid JSON (it uses `json.dumps(..., allow_nan=False)`, not `model_dump_json()`,
which would silently write NaN as `null`).

`score()` still never sees `persona`, `free_time_minutes`, `constraints`, or the
sibling tasks.

The whole persona scenario schema now lives in `interfaces.py` as Pydantic models
(`PersonaEvaluationScenario` and its `Persona` / `Context` / `Task` / `Source`
parts) — that model is the binding source of truth. `eval/personas/scenario.schema.json`
is generated from it (`make schema` / `python -m mind.evaluation.schema`) and kept
honest by `tests/test_schema_snapshot.py`; `make lint` runs the staleness check.

The harness (`harness.py`) calls `score()` once per candidate task in a scenario, and
builds the ranking itself from the returned scores — a model never ranks a whole
scenario or picks a winner directly. This keeps the contract consistent with the
system-wide boundary: **MIND scores, it does not decide** (see
`MIND-01-System-Overview.md` §4) — the per-task score is the API surface, same as the
boundary with Chain.

## Running the Evaluation

You can run the evaluation harness locally from the command line.

Pass the name of the model you wish to evaluate using the `--model` argument:

```bash
python -m mind.evaluation.run --model dummy
```

Replace `dummy` with the name of the model you want to evaluate.

`--personas` (default `eval/personas`) points to the personas directory. The
scenario files live in three subdirectories — `same_tier/`, `cross_tier/` and
`hierarchy_decides/` (see `eval/personas/README.md`) — and `load_personas` recurses
into all of them; `scenario.schema.json` at the root is skipped. `make eval` scores
each group separately, breaks the result down per `persona.role`, prints the
original set (every group but `hierarchy_decides/`) as one line, and prints a
combined number too; `report.json` carries all of them (`combined`, `groups`,
`roles`, `original_set`).
The per-role numbers are split out of the combined run, not re-scored, so
they always add up to it. Pass a single group dir
(`--personas eval/personas/same_tier`) to score just that group. Loading goes through
`mind.evaluation.personas.load_personas`/`validate_personas` (the coverage /
count checks run against the combined set). `load_personas`
parses each file into a `PersonaEvaluationScenario` (Pydantic); a structurally
invalid file or a broken intra-scenario invariant (a dangling
`expected_top_task_id`, a missing `source_detail`) raises and fails the run.
`validate_personas` then adds the cross-file check (a duplicate `scenario_id`
fails the run) and the not-ready-yet checks (a role with no scenario, an
under/over count) which only print a
warning.

`placeholder_scenarios.PLACEHOLDER_SCENARIOS` still exists, but only as a
small fixed list of `PersonaEvaluationScenario` objects for unit-testing
`harness.run()` in isolation (`tests/test_harness.py`) — it is not used by this CLI.

## For mentors: running this without MIND's help

From a fresh clone, with no prior knowledge of this repo. Needs **Python 3.11 or
newer** (tested through 3.14) and nothing else.

```bash
cd teams/mind
python -m venv .venv                       # optional but recommended
source .venv/bin/activate                  # Windows: .venv\Scripts\activate
cp .env.example .env                       # no values are actually required
python -m pip install -e ".[dev]"          # installs everything the harness needs
python -m mind.evaluation.run --model dummy # runs it; prints a summary + the full JSON report; writes report.json
```

With GNU `make` installed you can use the shortcuts instead: `make dev` then
`make eval` (same commands). On Windows without `make`, use the lines above —
and run the test tools as `python -m pytest` / `python -m ruff check src tests` /
`python -m mypy src` (the bare `pytest`/`ruff` names may not be on PATH).

The run exits `0` on success. Any `[persona set warning] ...` lines (a role with
no scenario, an out-of-range count) are informational; the run still completes and
reports a score.
Only a non-zero exit code, or text under "Persona set validation FAILED", means
something is actually broken. When running the default `eval/personas` set, a
"missing role" / "expected 4-6" warning would mean the set is incomplete — there
should be none. (Those coverage warnings *are* expected when you point
`--personas` at `eval/real_slice` — see **Real data slice** below.)

## Metric

The harness evaluates models using **Top-1 Accuracy** (`metrics.top1_accuracy`).

**Decision status: closed (final).** Top-1 was chosen over MRR / nDCG with the
mentor; each scenario carries exactly one labelled correct answer
(`expected_top_task_id`), which is what top-1 needs and what MRR/nDCG-with-graded-
labels would not have. This will not change once baseline scoring starts
(week 5). See also the "Open decisions" table in `teams/mind/CLAUDE.md`.

For each scenario, the metric checks whether the task the model scored highest matches
that scenario's `expected_top_task_id`. The final number is the proportion of scenarios
where the model picked the correct top-priority task.

To keep the door open for a future switch to a different metric without re-running every
model, the harness report keeps every candidate's score and reasoning per scenario
(`ScenarioResult.candidates`), not just the winner.

## Hierarchy (`parent_task_id`)

The week-7 stretch goal propagates importance down the `parent_task_id` chain.
Until #507 every task in the set had `parent_task_id: null`, so a
hierarchy-aware model had nothing to read and would score exactly like the
baseline — and `diff_reports` would say "0 scenarios changed" whether the stretch
failed or simply never got any input. The set now carries a hierarchy, so that
answer means something.

### How it is represented

- A scenario task's `parent_task_id` names a **node** in
  [`eval/hierarchy/nodes.json`](../../../eval/hierarchy/nodes.json). The node is
  never one of the scenario's candidate tasks, so the candidate lists are
  unchanged, and a model that does not read `parent_task_id` scores exactly as
  before (see **What did not change** below).
- `null` means an **orphan**, on purpose. Orphans are part of the coverage, not
  gaps.
- A node has its own `parent_task_id` (`null` = a root), so chains can be more
  than one level deep.
- Each scenario has **its own small tree** (node ids carry a per-scenario prefix,
  e.g. `mgr_reorg_…`). The scenarios were written as independent worlds —
  different companies, different dates — and one shared organization tree would
  have forced them into one organization after the fact. It also keeps the
  week-7 question answerable: a scenario's result depends only on its own nodes.

The node shape is `HierarchyNode` / `HierarchyFixture` in
[`mind.scoring.hierarchy`](../scoring/hierarchy.py), agreed with R3 (the
propagation author) so the fixture and the propagation code use one shape. It
lives in `scoring/`, not here, so production scoring never imports from the
evaluation package.

| Field | Meaning |
|---|---|
| `task_id` | Unique across the file; same character rule as `Task.task_id` |
| `parent_task_id` | The node's own parent, or `null` for a root. Required — a root is stated, never implied |
| `importance` | 0-1, finite, **set by hand** (see below) |
| `label` | Short description for readers; no model reads it |

### Where parent importance comes from during an eval run

From the fixture, and nowhere else. `importance` is a fixed input, like a
`deadline`: it is not computed from depth, from the tasks under a node, or from
any model. Three levels are used throughout, so each value says something a
reviewer can check against the scenario text:

| Level | Value | Used for |
|---|---|---|
| low | 0.2 | Routine work, an initiative that already ended or was lost, or one still only being explored |
| medium | 0.5 | Regular planned work |
| high | 0.8 | A central initiative or goal |

0 and 1 are not used: 0 would mean "worthless" and 1 "the single most important
thing there is", and no node in the set is either. In the one two-level chain on
a correct answer, both levels are high, so the only difference between them is
depth. `tests/test_hierarchy_links.py` fails if any node uses another value.

**The nodes are constructed, not found.** No scenario names its parent
initiative; every node, its label and its chain were written for this fixture.
What differs is where each *importance* comes from, and the table below says
which for every scenario:

- **text** — the importance restates something the scenario itself says: the
  launch "happened without it", the deal is "already lost to a competitor", the
  memo is "still exploratory", the renewal protects revenue that is "actively
  slipping".
- **constructed** — the scenario does not say how important the parent is; the
  value was chosen to build a test case (a control, a stress test, a blocked task
  under an important initiative). These are R1's judgment and are open to review
  as such; changing one changes what the case tests, not whether the scenario's
  answer is right.

How a model gets the nodes: `score(task, context)` already receives
`task.parent_task_id`, and nothing new goes through `ScoreContext`. The ancestors
themselves are handed to the model when it is built — the hierarchy-aware model
lives in `scoring/` and never imports from `evaluation/`, so it cannot read
`HIERARCHY_PATH` itself. For the eval, `run.py` already loads and checks the
fixture (`load_hierarchy`, from `--hierarchy`, default `eval/hierarchy`); when
the hierarchy-aware model is added to its model list, that loaded fixture is
what it is constructed with. In production an adapter over Chain's traversal API
supplies the same `HierarchyNode` shape. The exact constructor is R3's to define
in #474.

### Why a fixture and not Chain

In production the ancestors come from Chain's traversal API. The eval does not
call it, deliberately: an eval that depends on another team's service being up,
with data that may have changed since the last run, is not reproducible — and a
reproducible comparison against the **same** fixed set is the entire point of
the baseline-vs-stretch measurement. The fixture is part of the fixed set:
`personas.sha256` covers `eval/hierarchy/` as well as `eval/personas/`, and so
does the checksum a recorded baseline stores (see **Baseline score**), so a
silent edit to a node's importance fails `personas_checksum --check`, and makes
`load_baseline_record` refuse an older baseline, just like an edit to a scenario.

### What is enforced, and where

| Rule | Where |
|---|---|
| Node fields, types and bounds | `HierarchyNode` |
| Unique node ids | `HierarchyFixture` (a bad file fails to load) |
| Every non-null node parent is in the fixture; no cycles | `mind.evaluation.hierarchy.validate_hierarchy_fixture` — run by `run.py` before scoring and by the tests. Deliberately **not** in the shared `HierarchyFixture`: what a cycle or a missing parent means is the propagation's behaviour to define (#473), and its test graphs must be able to contain them |
| Every scenario `parent_task_id` names a node; no node id is also a scenario `task_id` | `mind.evaluation.hierarchy.validate_hierarchy_links` — run by `run.py` before scoring (a failure exits 1, like a broken scenario) and by the tests |
| Every task's chain matches the table below; scenarios not in it are fully orphan; every importance is one of the three levels; every node belongs to exactly one scenario (no shared, no unused nodes) | `tests/test_hierarchy_links.py` |
| A changed node is a changed set | `personas.sha256`, and the checksum a recorded baseline stores (both via `personas_checksum`) |

### The two scenarios for the week-7 question

The mentor question is known in advance: one scenario where the stretch changed
the recommendation, one where it did not. Both were chosen before any
hierarchy-aware model exists. They are **designed** to behave this way; whether
they do depends on the propagation, and either outcome is a result.

- **Designed to change —
  `hierarchy_decides/manager_restructuring_team_signoff_vs_maintenance_team_signoff`.**
  Two sign-offs of the same tactical resourcing plan, with the same text and effort,
  for two teams; the Birchwood one is due an hour earlier. The correct answer, the
  Atlas sign-off, sits under a high Q4 restructuring initiative, itself under a high
  annual goal (the two-level chain); Birchwood's sits under low routine maintenance.
  The baseline misses it (it follows the earlier deadline); only the hierarchy points
  at the answer.
- **Designed not to change — `developer_prod_crash_vs_backlog_feature`.**
  `task_backlog_widget` sits under a high self-service roadmap node; the correct
  answer `task_crash` is an **orphan** — a production checkout crash with a
  three-hour support SLA. An orphan with a hard, near deadline must still beat a
  task under an important initiative. The baseline gets it right.

The first "designed to change" pick was `manager_status_digest_vs_reorg_proposal`.
It was replaced because `SegmentedRankingModel` already gets it right without any
hierarchy (the task text says what matters), so the stretch could not show a
change there.

### `hierarchy_decides/`: where the hierarchy decides

The 36 original scenarios were written before there was a hierarchy, and every one
of them is decided by deadline, `blocked` or the text of the task. So the hierarchy
can confirm or contradict an answer there, but almost never decide one: the stretch
would have nearly nothing to change. `eval/personas/hierarchy_decides/` adds six
scenarios, one per role, built so that it can:

- **Four where the hierarchy decides.** The candidates are near-equal on their own
  features: the same tier and effort, wording identical but for a name, deadlines
  an hour or two apart. The correct answer is slightly *weaker* on them (its
  deadline is the later one), never a full tie. What separates them is the
  initiative each one serves, and the `expected_reason` names it. Together they
  cover what the mentor asked for: an initiative that was **deprioritized**
  (`salesperson_...`, a paused vertical push), a **two-level chain**
  (`manager_...`), a **branch** (`higher_manager_...`: one important parent with one
  child against an unimportant parent with two), and a case where the direct
  parents are **equal and only the grandparents differ** (`ceo_...`), which a
  propagation that looks one level up cannot get right.
- **Two controls.** The hierarchy pulls hard toward a distractor, but a hard signal
  must win: a production payment failure with a 13:00 SLA against two migration
  steps under the year's top goal (`developer_...`, the answer is an orphan), and a
  customer site that is down inside a four-hour contractual window, whose own
  parent is *low*, against a survey for the year's priority program
  (`field_engineer_...`).

They are **new scenarios, not copies** of existing ones. The originals decide their
answer through the task text ("the biggest structural decision of the quarter",
"nobody has ever acted on it"); a copy with that text removed is no longer the same
scenario, and one with it kept would not test the hierarchy at all.

The correct answers were written before anything was run. Then the check: run
`SegmentedRankingModel` with no hierarchy input; it should miss the four decided
ones and get the two controls, and if it got a decided one right, that scenario's
features would be evened out, not its answer. Run on the baseline from #797 (which
removes its `parent_task_id` signal), it does exactly that, with no change needed:

| Scenario | Kind | Baseline picks | Scores (picked vs correct) |
|---|---|---|---|
| `manager_restructuring_…` | decided | Birchwood — **miss** | 0.7172 vs 0.7160 |
| `salesperson_core_target_…` | decided | Pinecrest — **miss** | 0.7280 vs 0.7259 |
| `higher_manager_migration_…` | decided | Ledger staffing — **miss** | 0.7459 vs 0.7453 |
| `ceo_expansion_partnership_…` | decided | Quayle — **miss** | 0.7035 vs 0.7031 |
| `developer_payment_incident_…` | control | the payment fix — **right** | 0.8526 vs 0.7101 next |
| `field_engineer_site_outage_…` | control | the site restore — **right** | 0.7884 vs 0.7422 next |

The margins in the decided four are tiny on purpose: the features are near-equal
and only an earlier deadline tips the baseline the wrong way, so any real push from
the hierarchy is enough to change the answer. The two cheap deadline baselines
behave the same way (2/6, exactly the controls); `DummyModel` gets the two whose
answer is listed first and `LongestDescriptionModel` gets none (see
`eval/personas/README.md`). `tests/test_baseline_ignores_hierarchy.py` pins both
halves of this: the baseline's results are identical with and without parents, and
it misses the "designed to change" scenario. `tests/test_personas.py` pins the shape: one per role,
at least three decided and one or two controls, near-equal rivals (same tier and
effort, the answer due 0-2 hours later), no two candidates fitting the free time
together, and the answer never the longest description.

`make eval` prints three lines for this: `[original set: all but
hierarchy_decides]` (36), `[hierarchy_decides]` (6) and `[combined]` (42); `report.json`
carries the first as `original_set`.

### Every scenario with a hierarchy, and what it checks

27 of the 42 scenarios have at least one parent (21 of the original 36, and all six
in `hierarchy_decides/`); the other 15 are fully orphan — mostly live incidents and
deadline-decided pairs, where the `expected_reason` gives no initiative for the
tasks to serve. Every node was checked against its scenario's task descriptions and
`expected_reason`, and nothing else. `tests/test_hierarchy_links.py` holds this
table as data and fails if the fixture drifts from it.

| Scenario | Hierarchy (✱ = correct answer) | Importance from | What it checks |
|---|---|---|---|
| `manager_status_digest_vs_reorg_proposal` | reorg✱ high → high (2 levels); status low; survey orphan | text | Hierarchy agrees with the answer (the first "designed to change" pick, replaced — see above) |
| `developer_prod_crash_vs_backlog_feature` | widget high; crash✱ and wiki orphans | constructed | Designed not to change (above) |
| `developer_owns_arch_decision_vs_dep_bump` | arch✱ high; bump orphan | text | Hierarchy agrees with the answer — no-harm check |
| `developer_blocked_oauth_vs_pr_review` | oauth (blocked) high; pr✱ orphan | constructed | Blocked beats an important parent |
| `manager_sprint_plan_vs_1on1_notes` | sprint✱ medium; notes and expenses orphans | text | No-harm check, medium level |
| `higher_manager_stalled_renewal_vs_market_expansion` | renewal✱ high; expansion medium; filler orphan | text | The distractor is an initiative too, just a less important one |
| `salesperson_expense_report_vs_departed_champion` | champion✱ high; expense and CRM orphans | text | Undated task under an important deal vs. dated routine admin |
| `salesperson_contract_redlines_vs_cold_prospecting` | redlines✱ medium; prospecting high; CRM orphan | constructed | A hard deadline the signature depends on beats a task under a *more* important parent |
| `higher_manager_regulatory_filing_vs_strategy_section` | strategy high; filing✱ orphan | text | Same tier: a statutory deadline today beats an important parent |
| `ceo_regulator_call_vs_board_prep` | board high → high (2 levels); regulator✱ orphan | constructed | A deep, important chain on the *losing* task |
| `manager_blocked_hire_vs_budget_review` | hire (blocked) high; budget✱ medium | constructed | Blocked beats a *more* important parent |
| `higher_manager_blocked_budget_vs_headcount_plan` | both under one shared high node; budget blocked | text | Identical hierarchy — only `blocked` separates them |
| `ceo_overdue_launch_statement_vs_earnings_prep` | statement low (launch already happened); earnings✱ high | text | A low-importance initiative, from the reason itself |
| `higher_manager_board_deck_vs_reorg_memo` | deck✱ high; memo low (still exploratory) | text | A low-importance initiative of a different kind |
| `ceo_investor_update_vs_allhands_prep` | two different parents, both high | constructed | Control: equal hierarchy, the deadline decides |
| `manager_escalation_call_vs_design_review` | escalation✱ medium (1 level); review medium → medium (2 levels) | constructed | Control: equal importance, different depth only |
| `salesperson_processing_cutoff_vs_call_summary` | both under one shared high node | constructed | Control: the same parent — hierarchy cannot separate them |
| `field_engineer_customer_degradation_vs_internal_tooling` | script medium; degradation✱ and inventory orphans | text | An orphan with no deadline wins on customer impact alone |
| `salesperson_dead_rfp_vs_live_signing_round` | rfp low (deal already lost); signing✱ high; QBR orphan (a routine review) | text | A dead initiative — even though the text calls it the second-largest opportunity |
| `ceo_own_investor_commitment_vs_unaccepted_keynote_request` | investor✱ high; acquisitions low (exploratory); keynote orphan (never accepted) | text | Three levels in one scenario |
| `higher_manager_approved_vision_memo_vs_expiring_capacity_offer` | vision high; capacity✱ high; travel exceptions orphan | text | An important parent over a now-trivial task (only formatting left) |
| `hierarchy_decides/manager_restructuring_team_signoff_vs_maintenance_team_signoff` | atlas✱ high → high (2 levels); birchwood low; filler orphan | constructed, named in the reason | **Decides:** two-level chain — the week-7 "designed to change" scenario |
| `hierarchy_decides/salesperson_core_target_proposal_vs_paused_vertical_proposal` | northgate✱ high; pinecrest low (paused); filler orphan | constructed, named in the reason | **Decides:** deprioritized initiative |
| `hierarchy_decides/higher_manager_migration_budget_vs_reporting_requests` | orion✱ high; both ledger requests under one low node | constructed, named in the reason | **Decides:** branch — one child of an important parent vs two of an unimportant one |
| `hierarchy_decides/ceo_expansion_partnership_vs_wound_down_line_partnership` | halvorsen✱ medium → high; quayle medium → low; filler orphan | constructed, named in the reason | **Decides:** equal parents, only the grandparents differ |
| `hierarchy_decides/developer_payment_incident_vs_migration_steps` | both migration steps high → high; payment✱ orphan | constructed, named in the reason | **Control:** a live payment failure with an SLA beats a deep, important chain |
| `hierarchy_decides/field_engineer_site_outage_vs_rollout_survey` | survey high; site restore✱ low; filler orphan | constructed, named in the reason | **Control:** a hard signal wins although its own parent is low |

Comparisons *within* one scenario (the controls, the shared-parent pairs) are the
clean ones: same time, same persona, only the hierarchy differs. Comparisons
*across* scenarios (e.g. `oauth` vs. `arch`, both high parents, one blocked) also
differ in deadlines and wording, so they are suggestive, not controlled.

### What did not change

In the original 36, no candidate list, `expected_top_task_id` or `expected_reason`
changed — only `parent_task_id` values, plus the new `eval/hierarchy/` and the
regenerated `personas.sha256` and `scenario.schema.json`. A model that ignores
`parent_task_id` scores exactly as before on them: `skip-blocked-nearest-deadline`
is **58.3% (21/36)** on the original set before and after (the number
`eval/personas/README.md` records for the set this change was made on). The six
`hierarchy_decides/` scenarios are new, so the combined number (42) is new too.

That holds for any model that does not read `parent_task_id`, and R2's
`SegmentedRankingModel` is now one of them. The mentor decided that the baseline
must not read `parent_task_id` at all: the hierarchy is the stretch's input, and a
baseline that already uses it leaves nothing for the stretch to be measured
against. #797 removed the baseline's `belongs_to_initiative` feature, and
`tests/test_baseline_ignores_hierarchy.py` runs `SegmentedRankingModel` on the set
with the parents and with every `parent_task_id` set to null and asserts the same
pick and the same scores in every scenario, so the baseline stays free of hierarchy
from now on.

`team_lead_approved` was left as it was on every touched scenario. The change is
additive — a new field value pointing outside the scenario — and leaves
everything the approval was about (the candidates, the correct answer, the
reason) exactly as approved. The delta itself goes to review as one PR, so the
approval covers it explicitly rather than by silence.

## Baseline score

The number the week-7 stretch is measured against (#486). Once recorded, an
entry here is not overwritten; a re-run gets its own dated entry.

### How a baseline is recorded

`report.json` is not the record: it is gitignored and rewritten on every run.
The baseline is written to its own committed file:

```bash
git rev-parse HEAD                                 # -> <model-sha>
git log -1 --format=%H -- eval/personas eval/hierarchy  # -> <persona-set-sha>
python -m mind.evaluation.run --model <model> \
    --record-baseline eval/baselines/baseline_score_<YYYY-MM-DD>.json \
    --model-commit <model-sha> --persona-set-commit <persona-set-sha>
```

The file carries a `format_version`, the date, the model name, `model_commit`, the metric, the
persona set (path, `checksum`, `commit`, scenario count, how many are
`team_lead_approved`), the combined / per-group / per-role
results, and every scenario's prediction with all candidate scores (for the
manual spot-check and the week-7 comparison).

- **It is never overwritten.** If the file exists, the run fails before
  scoring; a re-run goes to a new, dated file.
- **A failed run writes nothing.** The record is written last, after the
  `--min-accuracy` / `--max-accuracy` gates; a run that exits non-zero on a gate
  leaves no file behind (the file is write-once, so a leftover would block the
  real run that follows).
- **The persona set is identified by a checksum, computed automatically.** The
  record stores `personas_checksum.compute_personas_checksum` of the persona
  directory that was scored **and of the hierarchy given with `--hierarchy`**
  (default `eval/hierarchy`; its path is stored as `persona_set.hierarchy_path`).
  Both `persona_set.path` and `persona_set.hierarchy_path` are stored relative to
  `teams/mind` (`eval/personas`, `eval/hierarchy`), however they were passed, so a
  committed record never carries the recorder's own directory layout and reads
  the same on every machine. For the real set that is the same checksum `make lint` checks against
  `eval/personas/personas.sha256`. It is computed from the files themselves, so
  it cannot be typed wrong, and it describes exactly what was scored, including
  uncommitted edits to the scenarios or to a node's importance. `load_baseline_record` refuses to load a
  baseline when the current set's checksum differs from the recorded one: that is
  the "same fixed set" guarantee for the baseline-vs-stretch comparison.
- **Both commits are required and must look like a git sha** (7-40 lowercase
  hex), so a forgotten argument, a branch name or a stray paste fails instead
  of being recorded. That is a shape check only: it cannot tell a swapped pair
  or a wrong sha, so take each one from the git command above. For the persona
  set the commit is only an extra label; the checksum is what identifies it.
- **Run the model from a clean working tree.** `model_commit` only describes
  what was scored if nothing under `src/` is uncommitted; the tool does not
  check this. (The persona set no longer depends on this: its checksum covers
  uncommitted scenario edits.)

The format is one Pydantic model, `baseline_record.BaselineRecord`, used for
both writing and reading. Its checks run on every write and every read: each
accuracy (combined, per group, per role) must equal its own
`correct_top1 / total_scenarios`, `combined` must agree with the per-scenario
results, and each scenario's `passed` must match its prediction. A record that
fails any of them is refused, with an error naming the field. The per-group and
per-role slices must also add up to `combined`.
`format_version` (currently `1`) lets a reader say "this record is an older/newer
format" if a field is ever renamed or removed, instead of a validation error.
`baseline_record.load_baseline_record(path, personas_path=..., hierarchy_dir=...)` reads a record back into the
`Report` it was written from, so the week-7 comparison (#487) diffs the stretch
run against the recorded baseline instead of a re-run.

### 2026-09-23: no week-5 baseline number exists yet

- **Checked:** `origin/main` @ `c7e6c507` (2026-09-23).
- **Finding:** `src/mind/scoring/` contains only `README.md` and `__init__.py`,
  with no ranking model. The harness has only ever scored `DummyModel` and the
  heuristics in `baseline_models.py`, none of which is the baseline.
- **Why:** the segmented-ranking model (#354) has not merged. It is open as
  PR #498, which also depends on `ScoreResult.factors` (PR #497, open).
- **Consequence:** there is no week-5 baseline number anywhere, so #428 step 3
  ("re-confirm the baseline number") has nothing to confirm yet.
- **Next:** measured over all 30 scenarios in `eval/personas/` once #354 lands
  (expected start of week 7, 2026-10-04), then recorded here with the model
  commit and the persona-set version.

### 2026-10-25: segmented baseline recorded

- **Record:** [`eval/baselines/baseline_score_2026-10-25.json`](../../../eval/baselines/baseline_score_2026-10-25.json)
- **Model:** `SegmentedRankingModel` (#354), `--model segmented`, model commit
  `b16bfecc`.
- **Persona set:** `eval/personas` + `eval/hierarchy`, commit `f07b5e71`,
  **42 scenarios**, not the 30 the 2026-09-23 entry expected: #535 added 6 hard
  scenarios and #507 added the 6 in `hierarchy_decides/`.

| Slice | Top-1 |
|---|---|
| **Combined (42)** | **78.6% (33/42)** |
| Original set, all but `hierarchy_decides` (36) | 86.1% (31/36) |
| `cross_tier` (16) | 87.5% (14/16) |
| `same_tier` (20) | 85.0% (17/20) |
| `hierarchy_decides` (6) | 33.3% (2/6) |
| CEO / Developer / Field Engineer (7 each) | 85.7% (6/7) |
| Higher Manager / Manager / Salesperson (7 each) | 71.4% (5/7) |

- **`hierarchy_decides`, as designed:** it misses all four scenarios the
  hierarchy decides (`manager_restructuring_team_signoff`,
  `salesperson_core_target_proposal`, `higher_manager_migration_budget`,
  `ceo_expansion_partnership`) and gets both controls. The baseline does not
  read `parent_task_id`, so it has nothing to decide these with.
- **Original-set misses:** all 5 are from #535's hard scenarios:
  `higher_manager_approved_vision_memo`, `manager_idle_new_hires`,
  `developer_inflated_p0`, `field_engineer_released_controller`,
  `salesperson_dead_rfp`.

**Where it was right: `developer_prod_crash_vs_backlog_feature`.** The model
picked `task_crash`, the null-pointer crash in checkout (0.858), which is the
correct answer, over the order-history widget (0.427) and the wiki refresh
(0.438). Its reasoning names what decides it: the deadline is 3 hours away (the
support SLA) and the wording says "customers" and "escalated". The other two
have no deadline and their own wording plays them down ("not requested by
anyone", "nobody is waiting").

**Where it was wrong: `developer_inflated_p0_vs_understated_data_leak`.** The
model picked `task_staging`, "[P0] Staging health dashboard is red" (0.854). It
should have picked `task_leak` (0.586): titled "Minor", but its description
shows customers seeing other accounts' invoices right now, with a
regulator-notification window closing Friday. The model weighed the nearer
deadline (3 hours vs. 28) and the words "production" and "customer" in the
staging task's text. It does not read that the [P0] tag is a template default
on a staging-only alert, or that a mislabelled leak is the real severity. This
is the case #535's hard scenario was written to catch: an inflated label
against an understated real impact.

- **Next:** #487 scores the stretch model on the same set and diffs it against
  this record (`load_baseline_record` + `diff_reports`).

## Real data slice

The Foundation Gate requires the harness to run on a small slice of a **real**
dataset, not only hand-written scenarios. That slice lives in `eval/real_slice/`,
kept separate from `eval/personas/` on purpose: the persona set is fixed and
team-lead-approved so the baseline and week-7 scores stay comparable against it, whereas
the real slice exists only to prove the harness survives real data. The two are
never mixed.

```bash
python -m mind.evaluation.run --personas eval/real_slice
```

### Corpus

**AMI Meeting Corpus**, manual-annotations package `ami_public_manual_1.6.2`
(text only — no audio downloaded), licensed **CC BY 4.0**. The reproducible
pointer is committed as `eval/datasets/ami.manifest.json` +
`eval/datasets/ami.sha256`; the corpus files themselves stay out of git (under
`local-data/`). To regenerate the slice, download the package named in the
manifest and unzip it to `local-data/ami/ami_public_manual_1.6.2/`.

Action items come from the abstractive-summary layer: each
`abstractive/<meeting>.abssumm.xml` has an `<actions>` block whose `<sentence>`
elements are the human-annotated action items. Those sentences become a
scenario's `tasks`. All three scenarios use persona role **Manager** — the AMI
Project Manager is the participant who leaves a meeting owning the action-item
list — and are `team_lead_approved: false`, which is
expected: the real slice is not part of the graded set.

### Meetings used

| Scenario | AMI meeting | Items | Chosen top task | Why (model-visible signals only) |
|---|---|---|---|---|
| `manager_ami_ib4003_office_move` | IB4003 — AMI office-move meeting | 8 | `task_walls_feasibility` | The proposal-merge task is `blocked`; the follow-up meeting is a calendar event; the walls question is the open prerequisite the seating plan depends on. |
| `manager_ami_is1008b_functional_design` | IS1008b — remote-control design project, functional-design meeting | 4 | `task_research_vr_help` | Two of the three concept-prep tasks (UI, marketing) are independent; the third (components-concept) is treated as blocked on this same research — an inferred dependency, not one AMI states outright. Completing this one resolves that blocker. |
| `manager_ami_is1008d_budget_close` | IS1008d — same project, final meeting | 4 | `task_negotiate_cost` | Two of the four tasks are `blocked` by their own text ("after production has begun"); of the rest, the budget negotiation outranks routine reporting. |

### What broke on real data, and what was fixed

Nothing in the harness *crashed*, but real data exposed several rough edges:

1. **Annotation sentences are not self-contained.** AMI action items use
   pronouns referring to earlier sentences ("*He* will also present the new
   promotion plan…"), future-tense assignments naming other roles ("The
   Industrial Designer will…"), and sometimes no terminal punctuation ("The team
   will meet next Tuesday at two o'clock"). The schema's `description` is meant
   to let a task be judged in isolation.
   *Fixed:* each `description` quotes the action sentence verbatim, then adds one
   clause of meeting context and resolves the pronoun — meaning unchanged.

2. **AMI action items carry no deadlines and no explicit priority.** The ground
   truth cannot lean on deadline proximity the way a hand-written scenario can.
   *Fixed:* every `expected_reason` is grounded in `blocked`, `tier`, and
   importance stated in the description. `current_datetime` for all three
   scenarios uses the **real** meeting date from `corpusResources/meetings.xml`
   in the package (`dateOnly` per meeting: IB4003 `02-11-2004`; IS1008b/IS1008d
   `10-12-2004`), not an invented one — there was no consistent date scheme
   elsewhere in the repo for staying synthetic to protect (checked: `eval/personas/`'s
   30 scenarios are all dated 2026, this slice was already the outlier before
   this fix). One deadline was made concrete: IB4003's "next Tuesday at two
   o'clock" resolves against the real date without inventing anything, because
   `02-11-2004` is itself a Tuesday — "next Tuesday" is `2004-11-09`.

3. **The XML declares `encoding="ISO-8859-1"`, but that header is wrong for
   this corpus.** Verified against all 5,080 XML files in the package: zero
   fail a plain UTF-8 read. 63 files contain non-ASCII bytes, and every one of
   them is `\xc2\xa0` — a non-breaking space encoded in UTF-8 — under a header
   that still claims ISO-8859-1. Honoring the declared encoding, which is what
   an earlier version of this note told #176 to do, decodes `\xc2\xa0` as two
   Latin-1 characters (`Â` + a non-breaking space) — corruption, introduced on
   purpose by following the header.
   *Fixed:* the note for #176 is the reverse of what it said before — do not
   trust the declared encoding on this corpus; read every file as UTF-8. Also
   normalize non-breaking spaces (`\xa0`) to regular spaces before any string
   comparison: an invisible non-breaking space is not equal to a normal space,
   so left unnormalized it silently turns a true positive into a false
   negative in the matching rule. Nothing needed for this slice itself (its
   three files are pure ASCII); recorded here as the corrected caveat for
   #176.

4. **`validate_personas` coverage warnings fire on the slice.** Running
   `--personas eval/real_slice` prints `no scenario yet for role(s): …`,
   `Manager: has 3 scenario(s), expected 4-6`, and `3 scenario(s) total,
   expected 24-36`. These check the *persona set's* completeness and do not
   apply here. They are warnings only — exit code is still `0` and a score is
   still printed. Not treated as failures for the slice; documented rather than
   silenced, to avoid a harness code change.

5. **The grouped report is empty for a flat folder.** `run.py` splits its report
   by subdirectory (`same_tier/`, `cross_tier/` in the persona set);
   `eval/real_slice/` is flat, so `report.json` carries `"groups": {}` and only
   the combined report is meaningful. Expected for the slice.

6. **The schema requires `effort_estimate` and `tier` on every task; AMI
   supplies neither.** Every value across all three scenarios (15-120 minutes,
   `operational`/`tactical`) is invented — the same kind of gap as the missing
   deadlines and priority in item 2, but for two structurally required fields.
   The two carry different risk. `effort_estimate` is safe: the schema
   requires a correct answer to never hinge on comparing it against
   `free_time_minutes`, so an invented value cannot move the ground truth.
   `tier` is **not** safe: the ranking model does see `tier`, and
   `expected_reason` is allowed to lean on it, so an invented `tier` can
   change which answer is correct. The tiers used here were chosen to match
   each task's real scope (presentation/reporting prep = `operational`,
   negotiation and cost decisions = `tactical`), not tuned toward a
   particular answer, but there is no way to verify that choice against AMI
   itself — it is not in the annotation.

**Note on `manager_ami_is1008b_functional_design` specifically:** after the
`blocked` fix above (see the self-contradiction fix in the scenario file),
its three unblocked tasks are identical in every structured, model-visible
field (`blocked=False`, `tier=operational`, `deadline=None`). The only field
that differs is `effort_estimate` (45 vs. 90 vs. 90) — exactly the field item
6 says a correct answer must never hinge on. The real distinguishing signal
lives only in the free-text `description`, which is allowed for a model that
reads it, but a deterministic feature-based model (e.g. the week-5
`skip-blocked-nearest-deadline` baseline) does not read description text at
all. This scenario is therefore a **description-level scenario**: unsolvable
by design for a features-only model, not a ranking bug if one misses it.

**The same gap exists in `manager_ami_is1008d_budget_close`.** Its two
unblocked candidates, `task_negotiate_cost` and `task_present_promo_plan`,
are also identical in `blocked` (`False`) and `tier` (both `tactical`) and
`deadline` (both `None`) — only `effort_estimate` (90 vs. 45) differs. Unlike
the IS1008b case, the distinguishing text here is not an inference: the LCD
contingency in `task_negotiate_cost`'s description is directly backed by
AMI's own `<decisions>` layer, and `task_present_promo_plan` self-describes
as routine reporting. So the description-level signal is honest, but it is
still description-level — a features-only model has the same "unsolvable by
design" gap here as in IS1008b, for the same reason.

### Result

`python -m mind.evaluation.run --personas eval/real_slice` loads all three
scenarios, runs the `dummy` model, prints **Top-1 accuracy 66.7% (2/3)** and
exits `0`. That number is not a model result — `dummy` scores every task equally
and the harness breaks ties toward the first-listed task — it only confirms the
harness ingests real-corpus scenarios and produces a report end to end.

With the real `skip-blocked-nearest-deadline` baseline (`--model
skip-blocked-nearest-deadline`; landed on `main` after this branch was cut,
picked up by rebasing this branch onto `main` on 2026-09-24), the slice scores
**33.3% (1/3)** — worse than dummy, and every one of the three results is
explained by the model's scoring formula (`blocked` -> `0.0`, no deadline ->
a flat `0.01`, a deadline -> always higher than `0.01`), not by the model
understanding anything about the slice:

- `manager_ami_ib4003_office_move`: **miss.** The model picks
  `task_followup_meeting` — the only task with a deadline — over the correct
  `task_walls_feasibility`, which has none. This is the scenario's intended
  trap: the deadline belongs to a fixed calendar event, not prioritizable
  work, and only the `description` says so. A model that reads `deadline`
  alone, with no text, falls for it.
- `manager_ami_is1008b_functional_design`: **miss**, exactly as the
  description-level-scenario note above predicts. All unblocked candidates
  score the same flat `0.01` (no deadline), so the harness's tie-break
  (first-listed task) decides, landing on `task_ui_concept`, not the
  correct answer.
- `manager_ami_is1008d_budget_close`: **hit** — by the same tie-break
  mechanism, not signal comprehension. `task_negotiate_cost` and
  `task_present_promo_plan` also tie at `0.01`; `task_negotiate_cost` happens
  to be listed first, so it wins. Not evidence the model understood the
  budget dependency.

So the one correct prediction is a tie-break coincidence, the same mechanism
that drove `dummy`'s 66.7%. This is the answer the rebase was meant to
establish: the real-data slice does not yet measure ranking quality for any
model that has actually been run against it — it proves only that the harness
pipes real AMI data through any `RankingModel` implementation end to end.

## Real pipeline output through the harness (#174)

The real-data slice above is still hand-written scenarios. #174 asks what happens
when the harness meets tasks the pipeline actually produced. **Diagnostic only:
nothing is fixed here; the fixes are #175.**

**What was run.** The three tasks (T1–T3) of one `demo_flow` run (slack-first,
2026-10-26), exported by Michal Brecher from the run's stack as `task.created`
and `task.score.proposed`, the event in each message's `value` with proto field
names. They are kept in `eval/real_output/2026-10-26_demo-slack-first/`.
`scripts/diagnose_real_tasks.py` replays them, no stack and no model call:

```bash
python scripts/diagnose_real_tasks.py
```

**Result: the harness rejected all three tasks when it loaded them**, before any
model ran. This records the harness as it was before #175 (run on 17cd38ee);
the same script on the current code shows the fixes below instead. Each
`value` into `Task.model_validate`, unchanged:

| field | the harness expects | real output | tasks |
|---|---|---|---|
| `task_id` | `task_id`, matching `^[a-z0-9_]+$` | `id`, a UUID with hyphens | 3/3 |
| `tier` | `operational` / `tactical` / `strategic` | `TASK_TIER_TACTICAL` (the proto enum name) | 3/3 |
| `effort_estimate` | required, by that name | `effort_estimate_minutes`; absent on T1 | 3/3; 1/3 |
| `description` | required | absent on T1 | 1/3 |
| `blocked` | required | absent; Chain's `is_available` arrives instead | 3/3 |
| everything else | `extra="forbid"` | 10–12 more fields (`tenant_id`, `urgency`, `status`, `confidence`…) | 3/3 |

With only the obvious renames done (`id` → `task_id`, the effort name, the tier
lowercased, `blocked=False`), one gap is still left on every task: **the UUID
fails `task_id`'s pattern**. That one does not show from reading the models side
by side; it took real ids.

`LongestDescriptionModel`, the length-bias probe, crashes on T1
(`len(None)`): the first thing an optional `description` would break.

**All of these are harness assumptions, none a bug in the pipeline's output.**
Empty `description`, `tier` and effort are valid states the extraction contract
allows (#351, #352).

**The live path holds.** The same events through `InboxTask` (#357) and
`SegmentedRankingModel`, scored at each event's `scored_at`, give exactly the
`mind_score` the run published (0.470893, 0.213120, 0.261094), including T1
with its empty fields. So real tasks already reach the model correctly; only the
scenario path of the harness cannot take them.

**Limitations.** Three tasks from one run of a synthetic scenario. Fields the
harness does not read are out of scope here (`role_context` empty on T2 and T3,
no suggested owner) — both already show as FAIL in the demo's own check.

### The fixes, and the check that keeps them (#175)

**The harness now takes real-shaped tasks.** In `Task` (`interfaces.py`):

- `task_id` and `expected_top_task_id` share one pattern, `TASK_ID_PATTERN`:
  snake_case as before, **or** a lowercase UUID. Not any string: an uppercase or
  braced UUID is still rejected.
- `description`, `effort_estimate` and `tier` are optional. Empty is `null` (or
  the key absent), never `""` or `0`; a non-null value is checked as before.
- `scenario.schema.json` is regenerated. `personas.sha256` does not change: the
  checksum covers the scenario files, not the schema.
- `LongestDescriptionModel` reads a missing description as length 0 instead of
  crashing. It was the only code under `evaluation/` that assumed the fields
  were filled; `scoring/` already handled empty ones (#357).
- The hand-written set in `eval/personas/` still fills all three on every task:
  `tests/test_personas.py` fails if one is left out, so the optional schema does
  not loosen the curated set.

With these, `scripts/diagnose_real_tasks.py`'s second pass accepts all three
demo tasks.

**What the model does with an empty field** — already decided in the model,
not in the harness, which passes `null` through: no tier → scored by
`ASSUMED_TIER` (`tactical`, the middle of both axes) and logged
`tier_assumed`; no effort estimate → no quick-win bonus (the absence of a bonus,
not a penalty); no description → nothing added to the text the lexicons read.
A missing tier or estimate also lowers the score's confidence.

**A scenario with empty fields, outside the frozen set.**
`eval/real_shaped/salesperson_demo_quote_blocked_vs_supplier_form.json`: Dana
(Salesperson) with two real tasks from the demo run, kept as they came (UUID
ids; the price quote has no description and no effort estimate), and one
hand-written task with no tier and no deadline, since no real task came
without a tier. `blocked` is set from the demo's expected answer (the quote
waits for Omer's cost sheet). Correct answer: the supplier registration form.
It is in its own folder so `eval/personas/`, its checksum and the frozen
baseline stay as they are; it goes through the same schema and loader.

**CI.** `make eval-real-shaped` runs the harness on `eval/real_shaped/` with the
segmented model, and `mind-ci.yaml` runs it after the smoke check. It fails if
the harness cannot load or score those tasks; it has no accuracy gate (one
scenario is not a number). Locally: exit 0 as is; with `description` made
required again on purpose, exit 1 naming `tasks/0/description`; exit 0 once
restored. The Mind CI job is skipped on draft PRs, so the same red-then-green
proof on GitHub is recorded on the PR that lands this.

That scenario is written down once, so it cannot notice a later change to what
real tasks look like. `tests/test_real_output_through_harness.py` can: it takes
the recorded `task.created` events and builds each task the way the live path
does today — the current generated proto (parsed strictly, so a field the
proto no longer knows is an error), then `to_scoring_task` — and loads it into
`Task`, checks that T1's empty fields stay empty, and that the harness's copy
scores exactly like the live `InboxTask`. A change to the proto, to
`to_scoring_task` or to `Task` that breaks this fails in the PR that makes it
(checked: `description` made required again fails 3 tests; an event field the
proto does not know fails on parsing). What it cannot catch is a change in what
the pipeline produces (say, tier no longer filled): that takes a new demo run.

**The persona set is unchanged.** All 42 scenarios scored before (49c5ab1c,
this branch's base) and after, with `segmented`, `dummy`, `nearest-deadline`,
`skip-blocked-nearest-deadline` and `longest-description`: every task's score
identical, the same top task in all 42. The frozen baseline
(`baseline_score_2026-10-25.json`) still loads, 33/42.

**What the scenario shows about the segmented model** — findings, not fixed
here, and the scenario was not changed for them:

1. It picks the hand-written task, which has no tier, over the supplier form
   (base 0.496 against 0.261; final 0.647 against 0.483), for two reasons.
   **The operational rule itself:** its scale is 8 hours, so the form's
   deadline four days out gives time pressure 0.075 — below the 0.15 an
   operational task gets with no deadline at all. The same form with no
   deadline would score 0.310, not 0.261: the distant date lowers it.
   **The weights:** the untiered task is scored by the tactical rule
   (`ASSUMED_TIER`), where no deadline is 0.30 (not neutral; 0.5 is
   strategic's) and impact weighs 0.55 against operational's 0.25, so impact
   gives it 0.330 (0.55 × 0.600) against the form's 0.125 (0.25 × 0.500).
2. The keyword lexicon misses negation: "nothing depends on it" counts in the
   task's favour as "depends on it", which is why its impact is 0.600 and not
   the 0.500 the form gets.

## Extraction-quality eval

A **second, separate** harness — not a variant of the ranking metric above, which
is why it is its own section. Top-1 accuracy asks "given a list of tasks, did the
model rank the right one first?". This asks a question one step upstream: **"did
the pipeline extract the right action items from a meeting at all?"** — measured
as precision / recall / F1 of the extracted items against a human-annotated
reference (AMI's tagged action items). It lives in `extraction_eval.py`
(`evaluate_extraction`), with a CLI at `python -m mind.evaluation.run_extraction_eval`.
The full design, including the hand-sketch on real AMI meetings, is in
`extraction_eval_design.md`.

### What it measures

Given `extracted` action items and `annotated` (reference) ones, both as strings:

- **precision** = `TP / len(extracted)` — of what was extracted, how much was real.
- **recall** = `TP / len(annotated)` — of the real action items, how many were found.
- **f1** — harmonic mean. Micro-F1 over the whole slice is the number CI will
  regress on (`extraction_eval_design.md` §3.5).
- **counts** — `n_extracted` and `n_annotated` (the denominators above) and
  `true_positives` (the numerator), so a report is readable on its own.

The report (`ExtractionEvalReport`, a frozen pydantic model — `model_dump()` gives
the JSON the CLI writes) also carries the matched pairs, the missed annotations,
and the false positives — the raw evidence, kept for "why did this run score that?" analysis in
later weeks, the same requirement `ScenarioResult.candidates` exists for above.

### The matching rule

An extracted task and an annotated sentence are almost never byte-identical
("UI Designer to present the interface concept" vs. "The User Interface Designer
will make a presentation on the user interface concept"). The rule
(`extraction_eval_design.md` §3):

1. Normalize both sides — lowercase, collapse whitespace, drop a trailing period.
   "Whitespace" is whatever `str.split()` treats as such, which includes the
   non-breaking space `\xa0` — so `\xa0` needs no separate `.replace`. It does
   **not** include `\u200b` (zero-width space), which would survive
   normalization. Neither matters for the AMI action items themselves: the 306
   `<actions>` sentences contain no non-ASCII character at all (the `\xc2\xa0`
   bytes found elsewhere in the package, see "Real data slice" above, are not in
   them).
2. Score every (extracted, annotated) pair with a `similarity(a, b) -> float` in
   `[0, 1]`.
3. Match greedily, one-to-one: highest similarity first, take a pair iff both
   endpoints are still free and the score is `>= threshold`. A merge (two
   annotations covered by one extracted item) therefore shows up as one true
   positive plus one missed annotation, not hidden.
4. `TP` = matched pairs, `FP` = unmatched extracted, `FN` = unmatched annotated.

`similarity` and `threshold` are **required parameters of `evaluate_extraction`,
never hardcoded**: Ayala's embedding-similarity function swaps in at the call site
with no change to the harness, and the locked lexical threshold
(`extraction_eval_design.md` §6 — τ = 0.60, for the `rapidfuzz` backend) can be
revised as a visible call-site change rather than a silent code edit.

**A bad score fails loudly.** `evaluate_extraction` raises `ValueError` if
`threshold`, or any score `similarity` returns, is not a real number in `[0, 1]`
(NaN, infinity, out of range, `None`, a string, a `bool`). This matters most for
NaN: `nan < threshold` is `False`, so without the check a NaN score is matched as
if it had cleared the bar and the eval reports a *perfect* run instead of
failing. It also catches an embedding backend whose cosine in `[-1, 1]` was not
rescaled to `[0, 1]`.

### The extracted-item contract

`evaluate_extraction` compares strings, but a real extracted item is a structured
object. `extraction_contract.py` pins which fields of the pipeline's output the
eval reads and what they must mean, as a Pydantic model — in code so the
pipeline's output is *checked* against it, not just described. The pipeline's own
outbound contract stays `TaskCreationRequested`.

`ExtractedItem` carries only what the eval reads:

| field | required | role |
|---|---|---|
| `title` | yes | head of the comparison string |
| `description` | no | appended to `title` (`title + ". " + description`) when present — design §3.3 |

**Which field carries the meeting id (issue #233, step 1): none.** `source_ref` is a
pointer back to the original item: today it is read from *inside the content* and
dropped when the text does not contain it (`extraction/grounding.py`), so for a
meeting transcript it is `None`; once the Bridge consumer passes
`RawObjectEvent.source_ref` through (#31) it becomes the source system's own id (a
Zoom meeting uuid, a Slack `channel_id:ts`). Neither is the AMI meeting code, and
the eval does not go through Bridge. The eval is what sends each meeting to the
extractor, so it holds the meeting id itself: it passes it in as the extractor's
`item_key` and reads it back from `ExtractionSuccess` / `ExtractionFailure` — both
outcomes carry it (`extraction/README.md`, "A failed item can be found") —
grouping items by meeting before they are scored.

`ExtractedItem.to_comparison_text()` produces the string `evaluate_extraction`
matches. Pipeline output enters the eval through
`evaluate_extracted_items(items, annotated, *, similarity, threshold)`, which takes
`ExtractedItem`s — so the extracted side has to pass the contract before it is
scored — and is otherwise a thin wrapper over `evaluate_extraction` (the
string-level version the CLI and fixture use). Field names match the pipeline's
`ExtractedFields` (and its subclass `ExtractedFieldsWithClassification`) and
`TaskCreationRequested` (`contracts/task_creation_requested.py`), and
`extra="ignore"`, so the pipeline's full payload validates as-is — `source_ref`
is simply ignored. One rule is stricter than `TaskCreationRequested`: an empty
`description` is rejected, so "no description" is spelled `None` only (the
pipeline already maps blank answers to `None`). Items are frozen, so one that
passed validation cannot be edited into one that would not have. The
classification fields (`tier`, `urgency`, `role_context`, the effort
estimate) are deliberately absent even though the pipeline can now fill them in
the extraction call itself (#351/#352, `ExtractionConfig.classify`): extraction
P/R measures "did we find the right action items", not "did we classify them
right". `role_context` joins this contract as an optional field when the
owner-match diagnostic (design §3.7) is built.

**Status: agreed with Yonit (R2), 2026-10-14** (#233 step 5): `title` +
`description` is the comparison text, and the meeting id travels as the
extractor's `item_key`, which reaches both outcomes unchanged (documented on the
pipeline's side, `extraction/README.md`). This is the one place the contract's
status is kept; the module docstring and `extraction_eval_design.md` point here.

### This eval is approximate

It compares free text to free text. A "true positive" means *our rule* — a
similarity function and a threshold that are judgement calls — decided two
sentences are the same commitment. Two models' scores are comparable only because
they were scored with the *same frozen* rule; the absolute number carries an
error bar we do not quantify. The job is to catch regressions and large
differences on a fixed reference, not to certify absolute extraction quality.

### Running it

```bash
python -m mind.evaluation.run_extraction_eval                   # the bundled hand-built fixture
python -m mind.evaluation.run_extraction_eval --input pair.json
python -m mind.evaluation.run_extraction_eval --threshold 0.6   # default: PLACEHOLDER_THRESHOLD (0.5)
```

`pair.json` is `{"extracted": [...], "annotated": [...]}` — exactly those two keys,
lists of strings only (validated by the `ExtractionEvalInput` pydantic model; an
unknown key or a non-string item is rejected with the offending location, e.g.
`extracted.1`). A bad input file or an invalid `--threshold` prints the error to
stderr and exits with code 1. The full report is also written to
`extraction_report.json` in the working directory (gitignored). With no `--input`, the
fixture in `extraction_fixture.py` runs — three true positives, one false
positive, one false negative, precision = recall = 0.75, worked out by hand in
that file's docstring. The similarity the CLI uses is a pure-Python Jaccard placeholder
(`placeholder_similarity`, paired with `PLACEHOLDER_THRESHOLD = 0.5`); the real backend (embeddings, or a `rapidfuzz` baseline)
is not built yet — blocked on Ayala's answer (`extraction_eval_design.md` §4).
`metrics.extraction_precision_recall` is a stricter placeholder still (exact
string match); it is not this eval.

### Current status

**Fixture-validated, not yet run against real pipeline output** — see
[Cross-team: should the Task model / API error format be shared contracts? #21](https://github.com/KamaTechOrg/Resonance-Flow/issues/21)
(tracking issues for this eval: #176 design, #177 implementation).
The extracted side needs Yonit's extraction pipeline and Michal's ASR to be
producing output; the annotated side (AMI action items) wires in via a manifest
edit, not a code change (`extraction_eval_design.md` §"Scope").

### Several tasks per text (#690, was a known limitation)

Until [#690](https://github.com/KamaTechOrg/Resonance-Flow/issues/690) the
extraction stage returned exactly one task per input text, which capped recall on
a whole meeting at `1/n`. It now returns a list: `ExtractionSuccess.tasks` holds
up to 15 `ExtractedTask`s, each with its own `fields` and `confidence`, and an
empty list is a valid answer. `item_key` stays on the outcome, so the eval still
groups results by meeting through it (see "The extracted-item contract"); each
`tasks[i].fields` is what it reads.

Still a gap: content longer than `extraction.prompts.MAX_CONTENT_CHARS` (8000) is
truncated, and a meeting's action items cluster in its closing recap, so a long
transcript can still lose items. Truncation is now logged.
