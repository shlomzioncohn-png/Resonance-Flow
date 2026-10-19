"""Planted pathologies, one module each. Every module exposes `DISEASE`; adding one needs no other edit."""
from __future__ import annotations

import importlib
import pkgutil

from ..config import SEVERITIES, GeneratorConfig
from ..model import DiseaseInstance, EdgeBatch, Project, TaskSpec
from .base import Disease, Planter


def _discover() -> dict[str, Disease]:
    found = {}
    for module in pkgutil.iter_modules(__path__):
        if module.name == "base":
            continue
        disease = importlib.import_module(f"{__name__}.{module.name}").DISEASE
        found[disease.name] = disease
    return dict(sorted(found.items()))


REGISTRY: dict[str, Disease] = _discover()
DISEASE_TYPES = tuple(REGISTRY)


def plant_diseases(cfg: GeneratorConfig, projects: list[Project], normal: list[TaskSpec],
                   tasks: list[TaskSpec], edges: list[EdgeBatch]) -> list[DiseaseInstance]:
    """Plants `instances_per_severity` of every non-excluded disease at every severity.

    Each disease draws from its own RNG (seeded by its id), so excluding a type or changing the
    instance count never changes the data of the others.
    """
    out = []
    for name, disease in REGISTRY.items():
        if name in cfg.excluded_diseases:
            continue
        for severity in SEVERITIES:
            for k in range(cfg.instances_per_severity):
                planter = Planter(cfg, projects, disease, severity, k + 1, normal, tasks, edges)
                root, params = disease.plant(planter, severity)
                params = {**params, "peer_typical_days": round(planter.typical_days(), 1)}
                out.append(DiseaseInstance(
                    id=planter.disease_id, type=name, severity=severity, tier=planter.tier,
                    root_task_id=root.id, task_ids=[m.id for m in planter.members], start=planter.t0,
                    params=params))
    return out
