import pytest

pytest.importorskip("psycopg")
from health import loader  # noqa: E402


def test_chain_migrations_the_loader_applies_exist():
    directory = loader._migrations_dir()
    for name in loader.MIGRATIONS:
        assert (directory / name).is_file(), f"{name} moved or renamed in Chain's migrations"


def test_loader_columns_match_the_generated_csv_headers(tmp_path):
    from dataclasses import replace
    from health.generator import GeneratorConfig, generate
    from health.generator.export import write_dataset

    cfg = replace(GeneratorConfig(), n_normal_tasks=50, n_projects=3, instances_per_severity=1)
    data = generate(cfg)
    write_dataset(tmp_path, cfg, data.world, data.history)
    for table, columns in loader.TABLES.items():
        header = (tmp_path / f"{table}.csv").read_text(encoding="utf-8").splitlines()[0].split(",")
        assert tuple(header) == columns
