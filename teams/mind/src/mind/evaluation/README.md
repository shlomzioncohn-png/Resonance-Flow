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

### Recording the stretch run and the comparison (#487)

The stretch is scored against the recorded baseline in one run:

```bash
python -m mind.evaluation.run --model <stretch-model> \
    --compare-to eval/baselines/baseline_score_<YYYY-MM-DD>.json \
    --record-stretch eval/baselines/stretch_score_<YYYY-MM-DD>.json \
    --comparison-out eval/baselines/stretch_comparison_<YYYY-MM-DD>.json \
    --model-commit <model-sha> --persona-set-commit <persona-set-sha>
```

- **`--compare-to`** loads the baseline with `load_baseline_record` before
  scoring and never re-scores it. A record from a different persona set
  (checksum mismatch) fails the run before scoring. After scoring, the run
  prints #464's `diff_reports` summary: accuracy before → after, how many
  scenarios changed in each direction, and every changed scenario.
- **`--record-stretch`** writes the stretch run in the same `BaselineRecord`
  format, with the same rules as `--record-baseline`: write-once, both commits
  required, and written only after the accuracy gates. It cannot be combined
  with `--record-baseline` in the same run.
- **`--comparison-out`** (needs `--compare-to`) writes the `ComparisonReport`
  as JSON, with `baseline_record` and `stretch_record` naming the two files it
  compares (team-relative paths, like the records). It is write-once too: an
  existing file fails the run before scoring.

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

> **Superseded by the 2026-10-27 entry below**, which has the same scores on
> the same scenarios. Only the approval flags (and so the checksum) changed.
> The file is kept unchanged, because recorded baselines are never edited.

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

### 2026-10-27: baseline re-recorded after the last 12 approvals

- **Record:** [`eval/baselines/baseline_score_2026-10-27.json`](../../../eval/baselines/baseline_score_2026-10-27.json).
  This is the current baseline, and the one #487 compares against.
- **Why a new record:** the team lead approved the 12 scenarios that were still
  `team_lead_approved: false` (#535's 6 hard scenarios and the 6 in
  `hierarchy_decides/`). Flipping the flags changes the files, so the
  persona-set checksum changes (`f5639b84…` → `64509cdc…`), and
  `load_baseline_record` refuses the 2026-10-25 record against the current
  set. Recorded baselines are never edited, so the run went to a new file.
- **Model:** `SegmentedRankingModel`, `--model segmented`. Model commit and
  persona-set commit are both `d585d3b0`, the commit that flips the flags.
- **Scores are identical to 2026-10-25.** Field by field, the two records differ
  only in `recorded_on`, `model_commit`, `persona_set.commit`,
  `persona_set.checksum`, and `persona_set.team_lead_approved` (30 → 42).
  The combined / per-group / per-role numbers, every prediction, and every
  candidate score are equal. The table, the misses and the two examples above
  all apply unchanged.
- **Compared against:** the stretch model (#474) was scored against this record
  on the same set: 76.2% (32/42), with 9 scenarios changed (4 wrong → right,
  5 right → wrong). Records, diff and explanations are in
  [`docs/stretch_evidence.md`](../../../docs/stretch_evidence.md).

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
python -m mind.evaluation.run_extraction_eval --threshold 0.7   # default: LEXICAL_THRESHOLD (0.60)
```

`pair.json` is `{"extracted": [...], "annotated": [...]}` — exactly those two keys,
lists of strings only (validated by the `ExtractionEvalInput` pydantic model; an
unknown key or a non-string item is rejected with the offending location, e.g.
`extracted.1`). A bad input file or an invalid `--threshold` prints the error to
stderr and exits with code 1. The full report is also written to
`extraction_report.json` in the working directory (gitignored). With no `--input`, the
fixture in `extraction_fixture.py` runs — three true positives, one false
positive, one false negative, precision = recall = 0.75, worked out by hand in
that file's docstring. The similarity the CLI uses is the lexical backend
(`similarity.lexical_similarity`, `rapidfuzz`'s `token_set_ratio`, with the locked
`LEXICAL_THRESHOLD = 0.60`, `extraction_eval_design.md` §4.2/§6); the fixture scores
the same with it. The Jaccard `placeholder_similarity` stays for the unit tests,
whose expected counts are worked out for it. The embedding backend
(`similarity.embedding_similarity`, `EMBEDDING_THRESHOLD = 0.70`) is chosen with
`scripts/run_ami_extraction_eval.py --backend embedding`, and `--backend both`
scores with both, as every report shows them; see "Embedding backend" below.
`metrics.extraction_precision_recall` is a stricter placeholder still (exact
string match); it is not this eval.

### Current status

**Run once on real AMI meetings through main's real extraction (#178, 2026-10-25);
re-scored with the embedding backend, and run on ASR transcripts of two of the
meetings (2026-10-28).**
Tracking issues: #176 design, #177 implementation, #178 real-data validation.
Not yet in CI (#175).

### Real-data validation (#178)

**What was run.** The v1 slice — IB4003, IS1008b, IS1008d, 16 gold action items
(8 + 4 + 4, `load_gold_actions`) — on AMI's **human** transcripts
(`ingestion/ami_transcripts.py`, #784), each meeting sent whole as one MEETING
item to `BasicExtractor` (main at the #968 merge) on `gemini-3.6-flash`, scored
with `evaluate_meetings` at the locked lexical τ = 0.60. Human transcripts, not
ASR, so this measures extraction alone (mentor's call); ASR is in "ASR transcripts" below.

```bash
# needs a key; spends quota (see the script's docstring)
CONFIG_FILE_PATH=config.local.yaml SECRETS_FILE_PATH=secrets.local.yaml \
    python scripts/run_ami_extraction_eval.py
# re-score the recorded run: no key, no model call
python scripts/run_ami_extraction_eval.py \
    --from-outcomes eval/extraction_runs/2026-10-25_gemini-3.6-flash/outcomes.json
```

The model's answers (`outcomes.json`) and the scored report (`scores.json`) of
this run are in `eval/extraction_runs/2026-10-25_gemini-3.6-flash/`. `scores.json`
quotes AMI's action items verbatim (CC BY 4.0, AMI Meeting Corpus, University of
Edinburgh; see `eval/datasets/ami.manifest.json`).

**The number, as the eval computes it** — the reported result, not adjusted:

| | TP | FP | FN | precision | recall | F1 |
|---|---|---|---|---|---|---|
| IB4003 | 2 | 3 | 6 | 40.0% | 25.0% | 30.8% |
| IS1008b | 3 | 4 | 1 | 42.9% | 75.0% | 54.5% |
| IS1008d | 1 | 6 | 3 | 14.3% | 25.0% | 18.2% |
| **micro** | 6 | 13 | 10 | **31.6%** | **37.5%** | **34.3%** |
| macro | | | | 32.4% | 41.7% | 34.5% |

No extraction failed.

**Hand review (#178 step 4).** Every gold item and every extracted item was
checked against the transcript (line numbers below are in the text
`load_transcript` returns). This is a judgement, recorded to explain the number,
not to replace it:

| | gold found by the model | eval's TP | matcher missed a right pair | matcher took a wrong pair |
|---|---|---|---|---|
| IB4003 | 4 / 8 | 2 | g1 split offices (0.55, l. 76); g2 Gisella (0.59) | — |
| IS1008b | 3 / 4 | 3 | g4 Intel/IBM, speech vs voice recognition (0.60, rounds below) | g3 "present the marketing concept" ← "keep watching market trends" (0.66) |
| IS1008d | 2 / 4 | 1 | g2 promotion plan to management ← "convince management to align the launch with a sports event" (l. 235) | — |
| **total** | **9 / 16 (56%)** | 6 | 4 | 1 |

IB4003's g5 ("build a proposal") and g6 ("merge it with the other team's") are one
sentence of the meeting (l. 243) and one extracted item; one-to-one matching
gives one TP and one FN there, as design §3.4 intends.

**Findings.**

1. **The lexical backend is not enough on real output.** It rejected four right
   pairs and accepted one wrong one, so the official recall is 19 points under
   what the model found. Two causes: synonyms (speech/voice, split/separate,
   convince/present) and long descriptions — the model writes a sentence of
   detail per task, and `token_set_ratio` scores the longer text lower
   (Gisella: 0.73 on the title, 0.59 on title + description). τ and the
   comparison text were fixed before the run and are **not** changed after it.
   This is the evidence for an embedding backend (Ayala); the recorded
   `outcomes.json` lets it be scored on the very same answers.
2. **AMI's `<actions>` is a partial answer key.** Of the 19 extracted items, 10
   match no gold item yet were said in the meeting (send the minutes, the
   sharing folder, four people in a three-person room, market tests, launch…).
   Some sit in the same file's `<decisions>`; one contradicts the transcript
   (IS1008d `<decisions>`: evaluate real units "rather than evaluating the
   prototype"; l. 172–185: the team agrees on a prototype evaluation). So low
   precision here does not mean invented tasks.
3. **The model invents little.** No extracted task is absent from its meeting;
   one adds a detail that was not said (IS1008d, "…or movie tie-in": the sports
   event is l. 212–235, "movie" only appears at l. 151, about a 3D view).
4. **What the model misses** (reported to Yonit, not fixed here): the next
   meeting (IB4003 g8, "next Tuesday… about two o'clock", l. 276–293), loose
   group tasks (IB4003 g3, g4), and tasks set for another speaker (IS1008b g3,
   IS1008d g3).
5. **One run is not a stable number.** IS1008b was run twice: the same seven
   tasks, other descriptions, and TP went from 2 to 3 — through the wrong
   pair above. Repeated runs would give a range.

**Limitations.** Three meetings, 16 gold items, one run of one model; human
transcripts, not ASR; the hand review is one reviewer's reading.

### Embedding backend (2026-10-28)

Finding 1 above is why it exists. Ayala's `mind.embeddings.similarity` (cosine
over bge-small-en-v1.5, [0, 1]) is wired in as `similarity.embedding_similarity`;
the lexical backend stays, for CI without the model and as the comparison
point (`extraction_eval_design.md` §4.3).

**τ = 0.70, chosen on the §5 pairs only**, before the real run was scored with
it (`scripts/derive_embedding_threshold.py`, the pairs in `threshold_pairs.py`;
§6 has the detail). Same-task pairs score 0.751–0.958, different-task pairs
0.507–0.793, so no τ separates them pair by pair; every τ from 0.643 to 0.750
makes the one-to-one matching return exactly the hand-labelled pairs, also with
each true item left out, and 0.70 is the middle. The script's first rule
(pair by pair) gave 0.83 and failed 8 of those 9 checks; the rule was changed to
the matching criterion the lexical τ was held to, recorded in §6. Until a τ is
frozen, the backend refuses to run.

**The same recorded answers, re-scored** (no model call; `--from-outcomes ...
--backend both`; `scores.json` and `scores_embedding.json`).

**How every report shows the result** (mentor's decision, 2026-10-28): both
backends side by side, each with its right and wrong matches split by the hand
review, next to the hand review itself as the ground truth. The default backend
stays lexical (design §4.3).

| gold items found (of 16) | lexical, τ 0.60 | embedding, τ 0.70 | hand review (ground truth) |
|---|---|---|---|
| **right matches** | **5 (31%)** | **7 (44%)** | **9 (56%)** |
| wrong matches | 1 | 4 | — |
| the backend's own recall (micro) | 37.5% | 68.8% | — |
| the backend's own precision / F1 (micro) | 31.6% / 34.3% | 57.9% / 62.9% | — |

31–44% is the range the two backends give; both sit **below** the hand review,
because both miss right pairs the model did find (IS1008d's "convince
management…" for the promotion plan, which neither backend matches). A
backend's own recall counts its wrong matches as found, so it is not the
extraction's quality: embedding's 68.8% includes four wrong ones.

**Hand review of the 11 embedding matches**, against the review above:

- **Recovered:** the three right pairs the lexical backend lost to synonyms —
  IB4003 split offices (0.80) and Gisella (0.74), IS1008b Intel/IBM with
  "speech" for "voice recognition" (0.74).
- **Wrong, and new:** within one meeting, different tasks on the same topic now
  clear τ. IB4003 "Prepare a concrete proposal…" → "the group members will come
  up with their own preferences…" (0.76; the item is g5/g6's); IS1008d "Start
  real production" → "the group will evaluate the product after real units have
  been produced" (0.76); IS1008d "Launch product in the market" → "the Marketing
  Expert will begin his marketing plan after production has begun" (0.71).
- **Wrong, as with lexical:** IS1008b "Keep watching market trends" → "the
  Marketing Expert will present the marketing concept" (0.76).
- **Still missed:** IS1008d g2 (the promotion plan to management, which the
  model extracted as "convince management to align the launch…"), and IB4003
  g5/g6, whose item went to g3.

All four wrong matches score 0.70–0.85, inside the band the run script flags
for review by hand.

**What it shows.** The embedding backend fixes the lexical one's misses on
synonyms, but it over-matches on topic. Neither backend alone is the extraction
quality, which is why both are reported with the hand review: lexical 31%,
embedding 44%, the hand review 56%. On 16 items neither backend can be called
better. τ is not moved for this (§6): it was set on the §5 pairs before this
run, and the review band is where these errors are caught.

### ASR transcripts (2026-10-28)

**The question.** The real pipeline transcribes a meeting recording; it never
sees AMI's human transcript. How much does extraction lose when it reads ASR
output instead? Expected: a clear drop, from word errors and from the missing
speakers (the human transcript opens every line with a role, and the gold is
phrased by role, "The Project Manager will…").

**What was run.** IS1008b and IB4003 (IS1008d has no audio here), Mix-Headset,
transcribed by Michal Brecher through the pipeline's `asr.transcribe()`
(Whisper base.en, chunks of up to 60 s cut in silence, `vad_filter=True`); the
only change was lifting the 600 s decode cap and 240 s timeout so a whole
meeting is transcribed (`truncated=False`). No speaker labels; IS1008b's text is
23% shorter than the human transcript (20,111 against 26,071 characters). The
transcripts (CC BY 4.0, AMI Meeting Corpus) are in
`eval/extraction_runs/asr_transcripts_2026-10-28/`; the run, the same model
and gold, in `eval/extraction_runs/2026-10-28_asr_gemini-3.6-flash/`:

```bash
python scripts/run_ami_extraction_eval.py \
    --transcripts-dir eval/extraction_runs/asr_transcripts_2026-10-28 --backend both
```

**Result, on the same two meetings (12 gold items)**, in the reporting format
above, against the human-transcript run of 2026-10-25:

| gold items found (of 12) | lexical | embedding | hand review |
|---|---|---|---|
| human transcript | 4 right, 1 wrong | 6 right, 2 wrong | **7 (58%)** |
| ASR transcript | 4 right, 0 wrong | 5 right, 0 wrong | **5 (42%)** |

The backends' own recall on ASR: lexical 33.3%, embedding 41.7%.

**What the hand review found.**

- **A small drop, and only part of it is ASR.** Two gold items were found from
  the human transcript and not from ASR. IB4003's Gisella task is one, but the
  ASR text has it ("putting Gisela with the other admin of the school is not
  an option"): the model did not extract it this run, the run-to-run variation
  seen before, not ASR. IS1008b's "research companies … voice recognition" is
  the other: ASR wrote "companies like **Inter** or IBM", and the model extracted
  speech-recognition tasks but none about finding companies (borderline;
  counted as missed).
- **Garbled names do not always cost an item.** "Pierrette" came out "Perret",
  and "Ask Perret and Maggie about their preferences" was still extracted and
  matched.
- **The missing speakers did not hurt what this eval measures.** It compares a
  task's content, not who owns it. Tasks from ASR come without an owner ("Ask
  Perret and Maggie…" rather than "The Project Manager will ask…"): that affects
  owner assignment, which this eval does not score.
- **No wrong matches this time**, with either backend; "keep watching market
  trends" was not matched to the marketing concept, its description now worded
  differently.
- **Extra items are still real tasks** absent from the gold (the minutes, the
  shared folder, four places in the three-person room), as on the human
  transcript.

**Limitations.** Two meetings, 12 gold items, one run per transcript; a
difference of two items cannot be put on ASR with confidence. The decode cap
was lifted for these transcripts; in production a whole meeting would be cut
at 600 s.

### Several tasks per text (#690, was a known limitation)

Until [#690](https://github.com/KamaTechOrg/Resonance-Flow/issues/690) the
extraction stage returned exactly one task per input text, which capped recall on
a whole meeting at `1/n`. It now returns a list: `ExtractionSuccess.tasks` holds
up to 15 `ExtractedTask`s, each with its own `fields` and `confidence`, and an
empty list is a valid answer. `item_key` stays on the outcome, so the eval still
groups results by meeting through it (see "The extracted-item contract"); each
`tasks[i].fields` is what it reads.

Since #968, `extraction.prompts.MAX_CONTENT_CHARS` is 100,000 for every source,
a safety net against a runaway item; the v1 slice's transcripts (26–35k
characters) go through whole.

## Guardrail eval

A separate, deliberately adversarial set (`eval/guardrails/`, `tests/test_guardrails.py`) that feeds bad input to extraction, OCR and ASR and checks for graceful failure instead of confident output. It is not part of the persona set or its score. Findings and the per-component confidence audit: [`docs/guardrail_audit.md`](../../../docs/guardrail_audit.md) (#488).
