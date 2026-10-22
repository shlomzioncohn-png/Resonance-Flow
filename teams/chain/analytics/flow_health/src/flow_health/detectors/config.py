"""Detector thresholds, read from config.yaml (or another file with the same shape)."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import yaml

DEFAULT_PATH = Path(__file__).with_name("config.yaml")


@dataclass(frozen=True)
class ChronicBlockerConfig:
    min_ratio_to_tier: float = 3.0
    min_dependents: int = 3
    late_age_ratio_to_tier: float = 3.0
    medium_from_ratio: float = 4.0
    high_from_ratio: float = 6.0


@dataclass(frozen=True)
class DetectorConfig:
    chronic_blocker: ChronicBlockerConfig = ChronicBlockerConfig()


def load_config(path: Path | str | None = None) -> DetectorConfig:
    raw = yaml.safe_load(Path(path or DEFAULT_PATH).read_text(encoding="utf-8")) or {}
    return DetectorConfig(chronic_blocker=ChronicBlockerConfig(**raw.get("chronic_blocker", {})))
