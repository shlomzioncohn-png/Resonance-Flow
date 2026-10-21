from __future__ import annotations

from dataclasses import dataclass

from .config import HOLDOUT_DISEASES, SEVERITIES, GeneratorConfig
from .diseases import DISEASE_TYPES
from .model import World
from .planner import plan_world
from .simulation import History, simulate
from .validate import validate

__all__ = ["DISEASE_TYPES", "HOLDOUT_DISEASES", "SEVERITIES", "Dataset", "GeneratorConfig", "generate", "validate"]


@dataclass
class Dataset:
    config: GeneratorConfig
    world: World
    history: History


def generate(cfg: GeneratorConfig | None = None) -> Dataset:
    cfg = cfg or GeneratorConfig()
    world = plan_world(cfg)
    return Dataset(cfg, world, simulate(cfg, world))
