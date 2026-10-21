from __future__ import annotations

import logging
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd

from .dataset import Dataset

log = logging.getLogger(__name__)

# Only these columns are read from each file, so nothing else (an answer key included) can reach the analysis.
_COLUMNS = {
    "tasks": ["task_id", "created_at", "project_id"],
    "task_meta": ["task_id", "tier"],
    "dependency_history": ["id", "task_id", "depends_on_task_id", "action", "source", "source_ref",
                           "created_at", "sequence"],
    "task_state_history": ["id", "task_id", "event_type", "from_status", "to_status", "blocking_count_before",
                           "blocking_count_after", "created_at", "sequence"],
}
_EDGE_ACTIONS = {"ADDED", "REMOVED"}


class CsvLoader:
    """Reads the generator's CSV files from one directory."""

    def __init__(self, data_dir: Path | str):
        self.data_dir = Path(data_dir)

    def load(self, as_of: datetime | None = None) -> Dataset:
        tables = {name: self._read(name) for name in _COLUMNS}
        tasks, meta = tables["tasks"], tables["task_meta"]
        deps, states = tables["dependency_history"], tables["task_state_history"]

        rejected = deps["action"] == "REJECTED"  # an attempt Chain refused: never an edge
        if rejected.any():
            log.info("ignoring %d REJECTED dependency_history rows", int(rejected.sum()))
        deps = deps[deps["action"].isin(_EDGE_ACTIONS)]

        latest = max(deps["created_at"].max(), states["created_at"].max())
        as_of = latest.to_pydatetime() if as_of is None else _utc(as_of)

        deps = self._until(deps, as_of, "dependency_history")
        states = self._until(states, as_of, "task_state_history")
        return Dataset(tasks=tasks, task_meta=meta,
                       dependency_history=self._ordered(deps), task_state_history=self._ordered(states),
                       as_of=as_of)

    def _read(self, name: str) -> pd.DataFrame:
        path = self.data_dir / f"{name}.csv"
        if not path.is_file():
            raise FileNotFoundError(f"{path} is missing")
        df = pd.read_csv(path, usecols=_COLUMNS[name], dtype=str, keep_default_na=False)
        df = df[_COLUMNS[name]].replace("", pd.NA)  # blank = NULL
        if "created_at" in df:
            df["created_at"] = pd.to_datetime(df["created_at"], utc=True)
        if "sequence" in df:
            df["sequence"] = df["sequence"].astype("int64")
        if "project_id" in df:
            df["project_id"] = df["project_id"].astype("int64")
        for col in ("blocking_count_before", "blocking_count_after"):
            if col in df:
                df[col] = df[col].astype("Int64")
        return df.reset_index(drop=True)

    @staticmethod
    def _until(df: pd.DataFrame, as_of: datetime, name: str) -> pd.DataFrame:
        late = df["created_at"] > as_of
        if late.any():
            log.info("dropping %d %s rows after as_of %s", int(late.sum()), name, as_of.isoformat())
        return df[~late]

    @staticmethod
    def _ordered(df: pd.DataFrame) -> pd.DataFrame:
        return df.sort_values(["created_at", "sequence"], kind="stable").reset_index(drop=True)


def _utc(value: datetime) -> datetime:
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)
