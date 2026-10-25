"""The only way the rest of the analysis sees data: four tables and the end of the observation window."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Protocol

import pandas as pd


@dataclass(frozen=True)
class Dataset:
    """Four pandas tables plus `as_of`, the end of the observation window.

    tasks:                task_id, created_at, project_id
    task_meta:            task_id, tier (NaN when unknown)
    dependency_history:   ADDED / REMOVED rows only, ordered by created_at then sequence
    task_state_history:   ordered by created_at then sequence, nothing after as_of

    Every timestamp is a timezone-aware UTC datetime.
    """

    tasks: pd.DataFrame
    task_meta: pd.DataFrame
    dependency_history: pd.DataFrame
    task_state_history: pd.DataFrame
    as_of: datetime


class Loader(Protocol):
    """Stage B swaps the CSV loader for one that reads Chain; nothing downstream changes."""

    def load(self, as_of: datetime | None = None) -> Dataset: ...
