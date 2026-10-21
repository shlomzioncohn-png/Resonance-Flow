"""The generated CSV headers must stay identical to the real Chain tables (no database needed)."""
from __future__ import annotations

import re
from pathlib import Path

import pytest

from health.generator import GeneratorConfig, generate
from health.generator.export import write_dataset
from dataclasses import replace


def _migrations_dir() -> Path:
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "src" / "main" / "resources" / "db" / "migration"
        if candidate.is_dir():
            return candidate
    pytest.skip("Chain's migrations are not next to this project")


def _columns(sql_file: str, table: str) -> list[str]:
    sql = (_migrations_dir() / sql_file).read_text(encoding="utf-8")
    body = re.search(rf"CREATE TABLE {table} \((.*?)\n\);", sql, re.S).group(1)
    cols = []
    for line in body.splitlines():
        m = re.match(r"\s{4}([a-z_]+)\s+[A-Z]", line)
        if m and m.group(1) != "constraint":
            cols.append(m.group(1))
    return cols


@pytest.mark.parametrize("table,migration", [("dependency_history", "V2__init_dependency_history.sql"),
                                             ("task_state_history", "V9__init_task_state_history.sql")])
def test_csv_columns_match_chains_real_tables(tmp_path, table, migration):
    cfg = replace(GeneratorConfig(), n_normal_tasks=60, n_projects=3, instances_per_severity=1)
    data = generate(cfg)
    write_dataset(tmp_path, cfg, data.world, data.history)
    header = (tmp_path / f"{table}.csv").read_text(encoding="utf-8").splitlines()[0].split(",")
    assert header == _columns(migration, table)
