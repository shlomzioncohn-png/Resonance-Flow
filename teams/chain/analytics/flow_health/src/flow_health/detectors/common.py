"""What every detector shares: the flag record, its id, the percentile score, Hebrew wording and the output file."""
from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass, field
from pathlib import Path

PATTERNS = ("CHRONIC_BLOCKER", "RECURRING_CYCLE", "SLOW_CHAIN")
SEVERITIES = ("low", "medium", "high")

TIER_NAMES = {"operational", "tactical", "strategic"}


@dataclass(frozen=True)
class Flag:
    flag_id: str
    pattern: str
    entity_type: str
    entity_ids: list[str]
    score: float
    severity: str
    explanation: str
    evidence: dict = field(default_factory=dict)


def make_flag_id(pattern: str, entity_ids: list[str]) -> str:
    """Stable across runs: the same pattern on the same entities always gets the same id."""
    digest = hashlib.sha1("|".join(sorted(entity_ids)).encode("utf-8")).hexdigest()[:10]
    return f"{pattern}:{digest}"


def percentile_scores(values: list[float], flag_ids: list[str]) -> list[float]:
    """rank / n within one detector: the strongest flag gets 1.0. Ties are broken by flag_id, so runs agree."""
    n = len(values)
    order = sorted(range(n), key=lambda i: (values[i], flag_ids[i]))
    scores = [0.0] * n
    for rank, i in enumerate(order, start=1):
        scores[i] = round(rank / n, 4)
    return scores


def tier_phrase(tier: str) -> str:
    """'tactical tasks'; a task with no known tier is compared with all tasks."""
    return f"{tier} tasks" if tier in TIER_NAMES else "all tasks"


def write_flags(flags: list[Flag], path: Path | str, meta: dict | None = None) -> None:
    """One flags.json for every detector. `meta` (seed, as_of, ...) is written beside the flags."""
    payload = {"meta": meta or {}, "flags": [asdict(f) for f in flags]}
    out = Path(path)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=False) + "\n",
                   encoding="utf-8", newline="\n")
