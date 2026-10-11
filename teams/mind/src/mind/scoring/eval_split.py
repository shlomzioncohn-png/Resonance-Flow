"""Frozen dev/holdout split of the persona evaluation set, for developing the
segmented-ranking baseline without tuning on what measures it.

**This is a development discipline, not a change to the eval set.** The set
stays 30 fixed scenarios and R1's reported baseline is still measured over all
30 (`make eval`). The split exists so that the weights behind that number were
not fitted to the very scenarios it is computed on — the first thing a mentor
will challenge about a hand-weighted model scored on 30 labelled examples.

Rules this split is only worth something if we actually follow:

1. Every weight, threshold and impact keyword is chosen against DEV only.
2. HOLDOUT is never calibrated against. Whoever measures this model — R1,
   through the harness — reads its aggregate; its per-scenario failures do not
   come back here, because reading them and then adjusting a weight silently
   turns the holdout into more dev data.
3. A dev/holdout gap gets reported, not fixed.

How the split was derived (reproducible, not hand-picked)
---------------------------------------------------------
Scenarios were bucketed into (role x group) strata from their *filenames only*
— role from the `<role>_<slug>` prefix, group from `same_tier/` vs
`cross_tier/`. No scenario file was opened to build this split. 10 holdout
places were handed to the strata proportionally by largest remainder, which
lands on 4 cross-tier / 6 same-tier (the set's own 13:17 ratio) and 1-2 per
role. Within a stratum, selection is by ascending `selection_hash`.

Three scenarios were pinned to DEV on top of that:

- `developer_prod_crash_vs_backlog_feature` and
  `higher_manager_blocked_budget_vs_headcount_plan` were read while scoping the
  issue, before the split was agreed. They can never be holdout again.
- At least one of the two "deadline already passed" scenarios, and at least two
  of the `blocked` ones, had to stay in DEV so those rules could be calibrated
  at all. Both conditions came out satisfied by the hash order itself, so no
  scenario was actually displaced.

Known weakness, stated rather than buried: CEO and Field Engineer contribute
one holdout scenario each, both same-tier. The holdout therefore says close to
nothing about those two roles in cross-tier scenarios. That is the price of a
10-scenario holdout; 12 (two per role) would have balanced it.

What leaked anyway: filenames are descriptive, so filename-level signal (which
scenarios involve a blocked task, which involve an overdue deadline) was
visible while building this. `eval/personas/README.md` also documents set-wide
patterns. Content-level knowledge of a holdout scenario is what this file
protects; that is the line.
"""

from __future__ import annotations

import hashlib
from collections.abc import Iterable

from mind.evaluation.interfaces import PersonaEvaluationScenario

#: Fixed salt for the selection hash. Changing it reshuffles the split and
#: invalidates every measurement taken under the old one — don't.
SELECTION_SALT = "mind-scoring-354"

DEV_SCENARIO_IDS: frozenset[str] = frozenset(
    {
        "ceo_payment_processor_cutoff_vs_strategy_deck",
        "ceo_blocked_ma_review_vs_vp_offer",
        "ceo_investor_update_vs_allhands_prep",
        "ceo_overdue_launch_statement_vs_earnings_prep",
        "developer_owns_arch_decision_vs_dep_bump",
        "developer_prod_crash_vs_backlog_feature",
        "developer_cosmetic_typo_vs_data_corruption",
        "field_engineer_noisy_alert_vs_silent_backup_gap",
        "field_engineer_blocked_rma_vs_runbook_update",
        "field_engineer_cutover_inspection_vs_backlog_patch",
        "field_engineer_prod_incident_vs_scheduled_maintenance",
        "higher_manager_top_customer_outage_vs_okr_review",
        "higher_manager_blocked_budget_vs_headcount_plan",
        "higher_manager_board_deck_vs_reorg_memo",
        "manager_blocked_hire_vs_budget_review",
        "manager_sprint_plan_vs_1on1_notes",
        "manager_escalation_call_vs_design_review",
        "salesperson_contract_redlines_vs_cold_prospecting",
        "salesperson_expense_report_vs_departed_champion",
        "salesperson_at_risk_renewal_vs_owned_outage",
    }
)

HOLDOUT_SCENARIO_IDS: frozenset[str] = frozenset(
    {
        "ceo_regulator_call_vs_board_prep",
        "developer_blocked_oauth_vs_pr_review",
        "developer_login_hotfix_vs_migration",
        "field_engineer_customer_degradation_vs_internal_tooling",
        "higher_manager_stalled_renewal_vs_market_expansion",
        "higher_manager_regulatory_filing_vs_strategy_section",
        "manager_status_digest_vs_reorg_proposal",
        "manager_overdue_preread_vs_tomorrow_review",
        "salesperson_blocked_discount_vs_warm_followup",
        "salesperson_processing_cutoff_vs_call_summary",
    }
)

ALL_SPLIT_SCENARIO_IDS: frozenset[str] = DEV_SCENARIO_IDS | HOLDOUT_SCENARIO_IDS


class ScenarioSetDriftError(ValueError):
    """A loaded scenario is not in the frozen split (or one of the split's is missing).

    The persona set is supposed to be fixed once baseline-vs-stretch comparisons
    start (eval/personas/README.md). If it moved, this split — and any number
    measured under it — needs redoing deliberately, not silently.
    """


def selection_hash(scenario_id: str) -> str:
    """The value scenarios were ordered by within their stratum. Kept so the
    derivation above can be re-run and checked, not used at runtime."""
    return hashlib.sha256(f"{SELECTION_SALT}|{scenario_id}".encode()).hexdigest()


def partition(
    scenarios: Iterable[PersonaEvaluationScenario],
) -> tuple[list[PersonaEvaluationScenario], list[PersonaEvaluationScenario]]:
    """Split loaded scenarios into `(dev, holdout)`, preserving input order.

    Raises `ScenarioSetDriftError` if the input contains a `scenario_id` this
    split does not know, so a changed eval set surfaces immediately instead of
    quietly dropping scenarios out of the dev run.
    """
    scenarios = list(scenarios)
    unknown = sorted({s.scenario_id for s in scenarios} - ALL_SPLIT_SCENARIO_IDS)
    if unknown:
        raise ScenarioSetDriftError(
            f"scenario_id(s) not in the frozen split: {unknown}. The persona set changed "
            f"since the split was frozen; redo the split deliberately (see this module's "
            f"docstring) rather than extending it ad hoc."
        )
    dev = [s for s in scenarios if s.scenario_id in DEV_SCENARIO_IDS]
    holdout = [s for s in scenarios if s.scenario_id in HOLDOUT_SCENARIO_IDS]
    return dev, holdout
