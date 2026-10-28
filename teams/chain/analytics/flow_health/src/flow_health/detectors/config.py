"""Detector thresholds, read from config.yaml (or another file with the same shape)."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import yaml

DEFAULT_PATH = Path(__file__).with_name("config.yaml")


@dataclass(frozen=True)
class ChronicBlockerConfig:
    ratio_column: str = "active_ratio_to_tier_project"
    min_active_ratio: float = 12.0
    min_dependents: int = 3
    medium_from_ratio: float = 16.0
    high_from_ratio: float = 32.0


@dataclass(frozen=True)
class RecurringCycleConfig:
    min_transitions: int = 3
    min_median_gap_days: float = 1.0
    max_median_gap_days: float = 10.0
    medium_min_transitions: int = 5
    high_min_transitions: int = 8
    tight_cv: float = 0.3
    pair_min_return_days: float = 1.0
    min_blocker_active_ratio: float = 4.0


@dataclass(frozen=True)
class SlowChainConfig:
    min_length: int = 4
    max_length: int = 8
    min_group_size: int = 8
    min_ratio_to_median: float = 4.0
    min_robust_z: float = 3.5
    max_link_share: float = 0.5
    medium_from_ratio: float = 4.0
    high_from_ratio: float = 8.0
    max_overlap: float = 0.5
    max_paths: int = 1_000_000
    link_ratio_column: str = "active_ratio_to_tier_project"


@dataclass(frozen=True)
class DetectorConfig:
    chronic_blocker: ChronicBlockerConfig = ChronicBlockerConfig()
    recurring_cycle: RecurringCycleConfig = RecurringCycleConfig()
    slow_chain: SlowChainConfig = SlowChainConfig()


def load_config(path: Path | str | None = None) -> DetectorConfig:
    raw = yaml.safe_load(Path(path or DEFAULT_PATH).read_text(encoding="utf-8")) or {}
    return DetectorConfig(chronic_blocker=ChronicBlockerConfig(**raw.get("chronic_blocker", {})),
                          recurring_cycle=RecurringCycleConfig(**raw.get("recurring_cycle", {})),
                          slow_chain=SlowChainConfig(**raw.get("slow_chain", {})))
