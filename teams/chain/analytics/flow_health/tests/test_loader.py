import logging
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd
import pytest

from flow_health.loaders import CsvLoader

T = "2026-01-0{d}T10:00:00.000000+00:00"


def _write(path: Path, header: str, *rows: str) -> None:
    path.write_text("\n".join([header, *rows]) + "\n", encoding="utf-8")


@pytest.fixture
def data_dir(tmp_path):
    _write(tmp_path / "tasks.csv", "task_id,created_at,project_id,true_tier",  # extra column must not be read
           f"a,{T.format(d=1)},3,strategic", f"b,{T.format(d=1)},3,strategic")
    _write(tmp_path / "task_meta.csv", "task_id,tier", "a,tactical", "b,")
    _write(tmp_path / "dependency_history.csv",
           "id,task_id,depends_on_task_id,action,source,source_ref,created_at,sequence",
           f"2,b,a,REMOVED,USER,,{T.format(d=4)},3",
           f"1,b,a,ADDED,MIND,m1,{T.format(d=2)},1",
           f"9,b,a,REJECTED,MIND,m2,{T.format(d=3)},2")
    _write(tmp_path / "task_state_history.csv",
           "id,task_id,event_type,from_status,to_status,blocking_count_before,blocking_count_after,created_at,sequence",
           f"s2,a,STATUS_CHANGED,ACTIVE,DONE,,,{T.format(d=5)},4",
           f"s1,a,STATUS_CHANGED,PENDING,ACTIVE,,,{T.format(d=5)},3")
    return tmp_path


def test_tables_are_typed_and_ordered_by_created_at_then_sequence(data_dir):
    ds = CsvLoader(data_dir).load()
    assert list(ds.tasks.columns) == ["task_id", "created_at", "project_id"]  # nothing outside the contract
    assert str(ds.tasks["created_at"].dt.tz) == "UTC"
    assert list(ds.dependency_history["sequence"]) == [1, 3]
    assert list(ds.task_state_history["sequence"]) == [3, 4]


def test_unknown_tier_stays_missing(data_dir):
    meta = CsvLoader(data_dir).load().task_meta.set_index("task_id")["tier"]
    assert meta["a"] == "tactical" and pd.isna(meta["b"])


def test_rejected_rows_are_dropped_and_counted_in_the_log(data_dir, caplog):
    with caplog.at_level(logging.INFO):
        ds = CsvLoader(data_dir).load()
    assert set(ds.dependency_history["action"]) == {"ADDED", "REMOVED"}
    assert "ignoring 1 REJECTED" in caplog.text


def test_as_of_defaults_to_the_latest_event(data_dir):
    assert CsvLoader(data_dir).load().as_of == datetime(2026, 1, 5, 10, tzinfo=timezone.utc)


def test_an_earlier_as_of_hides_later_events(data_dir):
    ds = CsvLoader(data_dir).load(as_of=datetime(2026, 1, 3, tzinfo=timezone.utc))
    assert list(ds.dependency_history["sequence"]) == [1]
    assert ds.task_state_history.empty


def test_a_naive_as_of_is_read_as_utc(data_dir):
    assert CsvLoader(data_dir).load(as_of=datetime(2026, 1, 3)).as_of.tzinfo is not None


def test_a_missing_file_is_reported(data_dir):
    (data_dir / "task_meta.csv").unlink()
    with pytest.raises(FileNotFoundError, match="task_meta.csv"):
        CsvLoader(data_dir).load()
