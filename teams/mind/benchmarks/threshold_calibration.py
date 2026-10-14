"""Empirical calibration for dependency_match_threshold (#244) and
duplicate_similarity_threshold (#471) -- against threshold_calibration_tasks.json,
a hand-authored pool kept separate from eval/real_slice and eval/datasets on
purpose (see that file's _comment -- avoids overlapping with R2/Michal's own
evaluation data).

Prints a score table for each calibration, suggests a threshold (the value
that best separates true matches from everything else in this sample), and
writes benchmarks/threshold_calibration_results.json so the numbers can be
re-checked against real data later (week 8) without re-deriving the method.

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
    # Pure negatives: unrelated to every task in the pool. Needs enough of
    # them to see where the noise floor actually sits (see README
    # "Threshold calibration") -- 2 wasn't enough: "Write a guide for new
    # hires on our DB setup" shares domain vocabulary with task-db-upgrade
    # without being a duplicate of it, and scored high enough to flip an
    # existing regression test once the threshold moved below its score.
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
    # True negatives -- nothing in the task pool should match these. Needs
    # enough of them to see where the noise floor actually sits, not just
    # 1-2 examples (see README "Threshold calibration").
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


def _suggest_threshold(results: list[CaseResult]) -> float:
    """Separates real matches from real non-matches only. A close sibling
    that a matched case outranks (e.g. login-bug beating logout-bug into
    second place) is a ranking question, not a threshold question -- it's
    still returned, still correct, just also above the bar -- so it's
    excluded here on purpose; penalizing it for that pushed the threshold
    up past several genuine matches in an earlier version of this script
    (see README). Picks the midpoint of whichever gap between adjacent
    observed values misclassifies the fewest cases, not a value snapped
    onto one example -- ties broken toward the higher (stricter) gap."""
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
    finally:
        await store.clear()
        await store.close()

    _print_table("Duplicate detection (#471, is_query=False)", dup_results)
    dup_threshold = _suggest_threshold(dup_results)
    print(f"\nSuggested duplicate_similarity_threshold: {dup_threshold:.3f}")

    _print_table("Dependency resolution (#244, is_query=True)", dep_results)
    dep_threshold = _suggest_threshold(dep_results)
    print(f"\nSuggested dependency_match_threshold: {dep_threshold:.3f}")

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
            },
            indent=2,
        )
    )
    print(f"\nFull results written to {_RESULTS_PATH}")


if __name__ == "__main__":
    # loop_factory=SelectorEventLoop: psycopg's async mode rejects Windows'
    # default ProactorEventLoop; SelectorEventLoop is already the default
    # everywhere else, so this is a no-op off Windows.
    asyncio.run(main(), loop_factory=asyncio.SelectorEventLoop)
