# Stretch evidence: hierarchical propagation vs. the segmented baseline (#487)

> **Validated on constructed graphs only.** Chain never sets `parentTaskId` on a
> task today (see `scoring/README.md`, PR #974), so in production `/ancestors`
> returns nothing and the stretch model scores exactly like the baseline. Every
> ancestor in this comparison comes from the eval's own constructed hierarchy,
> `eval/hierarchy/nodes.json` (#507), with hand-set importance values.

## The question

> Show me one scenario where the hierarchy stretch goal changed the
> recommendation versus the baseline, and one where it did not. Why?

## What was compared

| | Baseline | Stretch |
|---|---|---|
| Model | `SegmentedRankingModel` (#354) | `HierarchicalRankingModel` (#474, PR #974) |
| Model commit | `d585d3b0` | `d585d3b0` |
| Record | [`baseline_score_2026-10-27.json`](../eval/baselines/baseline_score_2026-10-27.json) | [`stretch_score_2026-10-27.json`](../eval/baselines/stretch_score_2026-10-27.json) |

- **Same persona set for both:** `eval/personas` + `eval/hierarchy` at commit
  `d585d3b0`, 42 scenarios, all `team_lead_approved`. The stretch run loaded the
  baseline from its record (`--compare-to`), which refuses a record whose
  persona-set checksum differs, so the baseline was not re-scored.
- **Why 2026-10-27 and not the 2026-10-25 baseline:** the team lead approved the
  last 12 scenarios, which changed the persona-set checksum, so the baseline was
  re-recorded on the approved set. Its scores are identical to 2026-10-25 (only
  the commit, checksum and approval-count labels differ), and so are the stretch
  scores and the diff. See the README's "Baseline score" section.
- **The diff:** [`stretch_comparison_2026-10-27.json`](../eval/baselines/stretch_comparison_2026-10-27.json),
  produced by #464's `diff_reports`.
- **What the stretch does:** for a task with ancestors, `score = 0.5 * own + 0.5 *
  mean(ancestor importance)`, where `own` is the baseline's own score for that
  task. A task with no parent (an orphan) keeps the baseline score exactly.

## Result

| Slice | Baseline | Stretch |
|---|---|---|
| **Combined (42)** | **78.6% (33/42)** | **76.2% (32/42)** |
| Original set, all but `hierarchy_decides` (36) | 86.1% (31/36) | 75.0% (27/36) |
| `hierarchy_decides` (6) | 33.3% (2/6) | 83.3% (5/6) |

**The stretch changed the top recommendation in 9 of 42 scenarios: 4 wrong → right
and 5 right → wrong. Net lift is negative (−1 scenario, −2.4 points).** It fixes
all four scenarios built for the hierarchy to decide, and costs five that the
baseline already had right.

| Direction | Scenario | Baseline → Stretch (expected) |
|---|---|---|
| wrong → right | `ceo_expansion_partnership_vs_wound_down_line_partnership` | quayle → **halvorsen** (halvorsen) |
| wrong → right | `higher_manager_migration_budget_vs_reporting_requests` | ledger_staffing → **orion_budget** (orion_budget) |
| wrong → right | `manager_restructuring_team_signoff_vs_maintenance_team_signoff` | birchwood_signoff → **atlas_signoff** (atlas_signoff) |
| wrong → right | `salesperson_core_target_proposal_vs_paused_vertical_proposal` | pinecrest → **northgate** (northgate) |
| right → wrong | `ceo_regulator_call_vs_board_prep` | **regulator** → board (regulator) |
| right → wrong | `field_engineer_customer_degradation_vs_internal_tooling` | **degradation** → script (degradation) |
| right → wrong | `field_engineer_site_outage_vs_rollout_survey` | **site_restore** → rollout_survey (site_restore) |
| right → wrong | `higher_manager_regulatory_filing_vs_strategy_section` | **filing** → strategy (filing) |
| right → wrong | `salesperson_contract_redlines_vs_cold_prospecting` | **redlines** → prospecting (redlines) |

## Where it changed the recommendation: `ceo_expansion_partnership`

A CEO with two partnership term sheets to sign. The task features are built to
be near-equal, and only the hierarchy separates them, two levels up:

```
task_halvorsen → enterprise_partnerships (0.5) → enterprise_expansion (0.8)
task_quayle    → consumer_partnerships   (0.5) → consumer_line        (0.2, wound down)
```

| Task | Ancestor mean | Baseline | Stretch |
|---|---|---|---|
| `task_halvorsen` ✔ | (0.5 + 0.8) / 2 = 0.65 | 0.703 | **0.729** |
| `task_quayle` | (0.5 + 0.2) / 2 = 0.35 | 0.703 | 0.624 |

The baseline cannot tell the two apart: it does not read `parent_task_id`, and
it picks Quayle on a tie it has no grounds to break. The direct parents are equal
(0.5 each), so a model that looked only one level up would also tie. The
stretch walks the whole chain: Halvorsen's grandparent is a growing line (0.8)
and Quayle's is one being wound down (0.2). That pulls the two in opposite
directions and the correct answer wins by 0.105.

## Where it made no difference: `developer_payment_incident_vs_migration_steps`

A control: a live incident against two tasks under an important two-level chain.

```
task_payment_retries  orphan (live payment-failure incident)
task_port_config   → migration_wave (0.8) → platform_goal (0.8)
task_port_tests    → migration_wave (0.8) → platform_goal (0.8)
```

| Task | Baseline | Stretch |
|---|---|---|
| `task_payment_retries` ✔ | 0.853 | 0.853 |
| `task_port_config` | 0.710 | 0.785 |
| `task_port_tests` | 0.673 | 0.767 |

Both migration tasks rise, as they should, because their whole chain is
high-importance. But the incident is an orphan, so it keeps its score, and its
lead over each migration task is larger than that task's rise: 0.143 over
`task_port_config` (which rises +0.075) and 0.180 over `task_port_tests` (which
rises +0.094). The top recommendation stays correct. Propagation moved the scores;
it did not move the answer.

## Where it made things worse: `field_engineer_site_outage_vs_rollout_survey`

The other control, built so that a hard signal (a customer's warehouse scanners
are offline) beats the hierarchy.

```
task_site_restore    → maintenance_contracts (0.2)
task_rollout_survey  → edge_rollout          (0.8)
```

| Task | Baseline | Stretch |
|---|---|---|
| `task_site_restore` ✔ | **0.788** | 0.614 |
| `task_rollout_survey` | 0.742 | **0.801** |

The baseline gets this right on the task's own urgency. In the stretch, the
outage fix sits under a low-importance parent (0.2), and with half the weight on
the ancestor mean that parent drags it down by 0.174. The survey is lifted by
its 0.8 parent. The parent signal overrides a live customer outage.

## Why the five losses happen

All five come from the same property of the formula: **the ancestor mean carries
half the weight, whatever the task's own urgency.**

- **In three, the correct answer is an orphan** (`ceo_regulator`,
  `field_engineer_customer_degradation`, `higher_manager_regulatory_filing`). In
  the persona set, time-critical tasks (live incidents, hard deadlines) are
  mostly orphans. An orphan's score never moves, so a rival whose ancestor mean
  is above its own score climbs past it, even when the baseline gap came from
  the task's own urgency. Example: in `higher_manager_regulatory_filing` the
  filing (orphan) stays at 0.804, and the strategy section climbs from 0.772 to
  0.816 under its 0.8 parent.
- **In two, the correct answer sits under a less important parent than its
  rival**, and the rival's parent is worth more than the baseline gap:
  `site_outage` (0.2 vs. 0.8, above), and `salesperson_contract_redlines`
  (0.5 vs. 0.8). In the redlines case the redlines drop from 0.675 to 0.662,
  while cold prospecting jumps from 0.502 to 0.681.

The same property is what wins the four `hierarchy_decides` cases, where the
features are near-equal by design and the hierarchy is the only signal. The
stretch cannot tell "near-equal, so the hierarchy should decide" apart from
"clearly more urgent on its own".

This is a measurement, not a proposed fix. The weighting is R3's design (#474)
and is reported to her as a finding.

## Other things worth knowing

- **Moved the right way, but not enough:** `salesperson_dead_rfp_vs_live_signing_round`.
  The baseline was wrong and the stretch is still wrong. The correct
  `task_signing` (0.8 parent) rises from 0.658 to 0.759, the dead RFP (0.2
  parent) drops from 0.758 to 0.599, but the orphan `task_qbr` (0.784) stays on
  top.
- **Averaging can lower a correct answer and still keep it on top:**
  `manager_escalation_call`, where the escalation drops from 0.946 to 0.798
  under a 0.5 parent and still wins.
- **Orphans never change.** Every task without a parent scores exactly the same
  in both runs, which confirms the stretch is the baseline plus the ancestor
  blend and nothing else.

## Reproduce

This is the command that produced the stretch record, from a clean working tree
at `d585d3b0`. From `teams/mind`:

```bash
python -m mind.evaluation.run --model hierarchical \
    --compare-to eval/baselines/baseline_score_2026-10-27.json \
    --record-stretch eval/baselines/stretch_score_<date>.json \
    --comparison-out eval/baselines/stretch_comparison_<date>.json \
    --model-commit <sha> --persona-set-commit <sha>
```

Both records are write-once. A re-run goes to new, dated files and does not
replace these.
