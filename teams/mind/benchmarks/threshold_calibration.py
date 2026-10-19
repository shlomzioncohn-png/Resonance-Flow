"""Empirical calibration for dependency_match_threshold (#244),
duplicate_similarity_threshold (#471) and related_task_threshold (#472),
against threshold_calibration_tasks.json (kept separate from
eval/real_slice to avoid overlapping R2/Michal's own eval data).

Run: python -m benchmarks.threshold_calibration
"""

import asyncio
import json
import os
from pathlib import Path
from typing import NamedTuple

from benchmarks.vector_store_test_table import create_vector_store_test_table
from mind.config import VectorStoreSettings
from mind.embeddings import embed, nearest_tasks, task_text
from mind.embeddings.pgvector_store import PgVectorStore

_TASKS_PATH = Path(__file__).resolve().parent / "threshold_calibration_tasks.json"
_RESULTS_PATH = Path(__file__).resolve().parent / "threshold_calibration_results.json"
_TABLE = "task_embeddings_threshold_calibration"

# (test text, expected_task_id or None for "shouldn't match anything well")
DUPLICATE_CASES: list[tuple[str, str | None]] = [
    ("Move the prod DB to the newer version", "task-db-upgrade"),
    ("Order food for the company offsite", "task-catering"),
    ("Set up accounts for the new engineer joining", "task-onboard-hire"),
    ("Review access logs across services this quarter", "task-security-audit"),
    ("Investigate the urgent support ticket from a customer", "task-customer-escalation"),
    ("Document the new endpoints from this sprint", "task-api-docs"),
    # Hard pairs: near-duplicate siblings that must NOT cross-match.
    ("Push the DB migration out to prod", "task-deploy-prod"),
    ("Push the DB migration out to staging", "task-deploy-staging"),
    ("Publish the 2.3 release notes", "task-release-v23"),
    ("Publish the 2.4 release notes", "task-release-v24"),
    ("App crashes when logging in on phones", "task-login-bug"),
    ("App crashes when logging out on phones", "task-logout-bug"),
    # Pure negatives -- need enough to find the real noise floor.
    ("Water the office plants", None),
    ("Book a conference room for Friday", None),
    ("Fix the coffee machine in the break room", None),
    ("File this quarter's tax paperwork", None),
    ("Replace the printer on the third floor", None),
    ("Book the dentist appointment for next week", None),
    ("Plan the company holiday party", None),
    ("Book flights for the upcoming conference", None),
    ("Review expense reports from last month's travel", None),
    ("Clean out the supply closet", None),
    ("Update the office holiday calendar", None),
    ("Renovate the parking garage entrance", None),
    # Hard negatives: share domain vocabulary with a real task without
    # being a duplicate of it.
    ("Upgrade the office wifi router firmware", None),
    ("Write a guide for new hires on our DB setup", None),
]

DEPENDENCY_CASES: list[tuple[str, str | None]] = [
    ("the DB upgrade", "task-db-upgrade"),
    ("team offsite food", "task-catering"),
    ("onboarding the new hire", "task-onboard-hire"),
    ("security audit", "task-security-audit"),
    ("customer escalation", "task-customer-escalation"),
    ("API docs", "task-api-docs"),
    ("prod deploy", "task-deploy-prod"),
    ("staging deploy", "task-deploy-staging"),
    ("v2.3 notes", "task-release-v23"),
    ("v2.4 notes", "task-release-v24"),
    ("login crash", "task-login-bug"),
    ("logout crash", "task-logout-bug"),
    # True negatives -- need enough to find the real noise floor.
    ("the weather forecast", None),
    ("lunch plans for today", None),
    ("fixing the office coffee machine", None),
    ("quarterly tax filing", None),
    ("replacing the office printer", None),
    ("scheduling the dentist appointment", None),
    ("the company holiday party", None),
    ("booking flights for the conference", None),
    ("reviewing expense reports for travel", None),
    ("organizing the supply closet", None),
    ("the new company holiday calendar", None),
    ("the parking garage renovation", None),
    # Hard negatives: share a word with a real task but are a different
    # domain entirely -- the noise floor should stay low even here.
    ("upgrading the office wifi router", None),
    ("writing the team's weekly newsletter", None),
]

# (task_id, expected_related_task_id or None) -- task-to-task, not text
# (see _run_related_cases). Related-not-duplicate pairs: #471's own hard
# siblings, plus db-upgrade/db-backup. Everything else has no designed
# relation -- its best-other score is the noise floor.
RELATED_CASES: list[tuple[str, str | None]] = [
    ("task-deploy-prod", "task-deploy-staging"),
    ("task-deploy-staging", "task-deploy-prod"),
    ("task-release-v23", "task-release-v24"),
    ("task-release-v24", "task-release-v23"),
    ("task-login-bug", "task-logout-bug"),
    ("task-logout-bug", "task-login-bug"),
    ("task-db-upgrade", "task-db-backup"),
    ("task-db-backup", "task-db-upgrade"),
    ("task-catering", None),
    ("task-standup-notes", None),
    # task-release-notes excluded: it scored 0.85+ against v23/v24 -- it's
    # genuinely related to both, not a negative.
    ("task-onboard-hire", None),
    ("task-offboard-employee", None),
    ("task-security-audit", None),
    ("task-vendor-contract", None),
    ("task-customer-escalation", None),
    ("task-perf-review", None),
    ("task-budget-forecast", None),
    ("task-ui-redesign", None),
    ("task-api-docs", None),
    ("task-load-test", None),
]


class CaseResult(NamedTuple):
    text: str
    expected_task_id: str | None
    expected_similarity: float | None  # None if expected task didn't even place
    best_other_task_id: str
    best_other_similarity: float


async def _load_tasks() -> list[dict[str, str]]:
    data = json.loads(_TASKS_PATH.read_text(encoding="utf-8"))
    return data["tasks"]


async def _run_cases(
    store: PgVectorStore, cases: list[tuple[str, str | None]], *, is_query: bool, n_tasks: int
) -> list[CaseResult]:
    results = []
    for text, expected_task_id in cases:
        candidates = await nearest_tasks(store, text, k=n_tasks, is_query=is_query)
        by_id = {c.task_id: c.similarity for c in candidates}
        expected_similarity = by_id.get(expected_task_id) if expected_task_id else None
        others = [c for c in candidates if c.task_id != expected_task_id]
        best_other = max(others, key=lambda c: c.similarity)
        results.append(
            CaseResult(
                text,
                expected_task_id,
                expected_similarity,
                best_other.task_id,
                best_other.similarity,
            )
        )
    return results


async def _run_related_cases(
    store: PgVectorStore, cases: list[tuple[str, str | None]], *, n_tasks: int
) -> list[CaseResult]:
    """Task-to-task, not text-to-task -- matches how find_related() queries."""
    results = []
    for task_id, expected_related_id in cases:
        vector = await store.get_vector(task_id)
        assert vector is not None, f"{task_id} must already be upserted by main()"
        candidates = await store.search(vector, k=n_tasks, exclude_task_id=task_id)
        by_id = {c.task_id: c.similarity for c in candidates}
        expected_similarity = by_id.get(expected_related_id) if expected_related_id else None
        others = [c for c in candidates if c.task_id != expected_related_id]
        best_other = max(others, key=lambda c: c.similarity)
        results.append(
            CaseResult(
                task_id,
                expected_related_id,
                expected_similarity,
                best_other.task_id,
                best_other.similarity,
            )
        )
    return results


def _suggest_threshold(results: list[CaseResult]) -> float:
    """Separates real matches from real non-matches only -- a matched
    case's own runner-up isn't a negative (see README). Picks the midpoint
    of the best-separating gap, ties toward the stricter (higher) one."""
    positives = [r.expected_similarity for r in results if r.expected_similarity is not None]
    negatives = [r.best_other_similarity for r in results if r.expected_task_id is None]
    values = sorted(set(positives) | set(negatives))
    gaps = [(values[i] + values[i + 1]) / 2 for i in range(len(values) - 1)]
    candidates = [values[-1] + 0.01, *reversed(gaps), values[0] - 0.01]
    best_threshold, best_errors = candidates[0], len(positives)
    for t in candidates:
        errors = sum(1 for p in positives if p < t) + sum(1 for n in negatives if n >= t)
        if errors < best_errors:  # strict: ties keep the higher (earlier) threshold
            best_threshold, best_errors = t, errors
    return best_threshold


def _print_table(name: str, results: list[CaseResult]) -> None:
    print(f"\n=== {name} ===")
    print(f"{'text':<48} {'expected':<22} {'exp.sim':>8}  {'best other':<22} {'other.sim':>9}")
    for r in results:
        exp_sim = f"{r.expected_similarity:.3f}" if r.expected_similarity is not None else "-"
        exp_id = r.expected_task_id or "(none)"
        print(
            f"{r.text:<48} {exp_id:<22} {exp_sim:>8}  {r.best_other_task_id:<22} {r.best_other_similarity:>9.3f}"
        )


async def main() -> None:
    tasks = await _load_tasks()
    settings = VectorStoreSettings(
        dsn=os.environ["MIND_VECTOR_STORE__DSN"],  # type: ignore[arg-type]
        table=_TABLE,
    )
    await create_vector_store_test_table(settings)
    store = PgVectorStore(settings)
    await store.open()
    try:
        await store.clear()
        for t in tasks:
            await store.upsert(t["task_id"], embed(task_text(t["title"], t["description"])))

        dup_results = await _run_cases(store, DUPLICATE_CASES, is_query=False, n_tasks=len(tasks))
        dep_results = await _run_cases(store, DEPENDENCY_CASES, is_query=True, n_tasks=len(tasks))
        rel_results = await _run_related_cases(store, RELATED_CASES, n_tasks=len(tasks))
    finally:
        await store.clear()
        await store.close()

    _print_table("Duplicate detection (#471, is_query=False)", dup_results)
    dup_threshold = _suggest_threshold(dup_results)
    print(f"\nSuggested duplicate_similarity_threshold: {dup_threshold:.3f}")

    _print_table("Dependency resolution (#244, is_query=True)", dep_results)
    dep_threshold = _suggest_threshold(dep_results)
    print(f"\nSuggested dependency_match_threshold: {dep_threshold:.3f}")

    _print_table("Related tasks (#472, task-to-task via stored vectors)", rel_results)
    rel_threshold = _suggest_threshold(rel_results)
    print(f"\nSuggested related_task_threshold: {rel_threshold:.3f}")

    _RESULTS_PATH.write_text(
        json.dumps(
            {
                "task_count": len(tasks),
                "duplicate": {
                    "suggested_threshold": dup_threshold,
                    "cases": [r._asdict() for r in dup_results],
                },
                "dependency": {
                    "suggested_threshold": dep_threshold,
                    "cases": [r._asdict() for r in dep_results],
                },
                "related": {
                    "suggested_threshold": rel_threshold,
                    "cases": [r._asdict() for r in rel_results],
                },
            },
            indent=2,
        )
    )
    print(f"\nFull results written to {_RESULTS_PATH}")


if __name__ == "__main__":
    # psycopg async rejects Windows' default ProactorEventLoop.
    asyncio.run(main(), loop_factory=asyncio.SelectorEventLoop)
