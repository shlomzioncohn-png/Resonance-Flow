"""CPU time and an estimated cost for the local model calls (OCR, ASR) -- #479.

A local model has no bill per call, but it has a real cost: the CPU it holds.
This module measures that (CpuClock) and prices it (compute_cost) at the rate
in MIND_COMPUTE_COST__USD_PER_CPU_HOUR.

What the number is, and is not:

* CPU seconds, not wall-clock seconds. A call that waits on I/O costs little
  here; a call that keeps four cores busy for one second costs four. On a
  machine you rent by the hour you pay for the cores whether they are busy or
  not, so this is the *share* of the machine the call used -- a fair way to
  compare calls, not a line on an invoice.
* Process-wide. process_time() counts every thread in this process, and the
  child-process counters every child it has waited for. MIND's consumers
  handle one message at a time, so a call is all that runs during its window;
  if two calls ever overlap in one process, each is charged for both.
* Unknown stays unknown: no rate on file, or CPU that could not be measured,
  gives None -- never 0.0 (see tracking.ModelCallRecord.cost).
"""

from __future__ import annotations

import os
import sys
import time
from functools import lru_cache

from mind.config import ComputeCostSettings

_SECONDS_PER_HOUR = 3600


def _children_cpu_s() -> float | None:
    """CPU seconds used so far by child processes this process has waited for.

    None on Windows, where os.times() reports the children's fields as 0 --
    an OCR call there would otherwise look free instead of unmeasured.
    """
    if sys.platform == "win32":
        return None
    t = os.times()
    return t.children_user + t.children_system


class CpuClock:
    """CPU seconds used from construction until elapsed_s() is called.

    Args:
        include_children: Also count the CPU of child processes that finished
            in the window -- for a model that runs as a subprocess (Tesseract).
            Whisper runs in this process, so ASR leaves it off.
    """

    def __init__(self, *, include_children: bool = False) -> None:
        self._include_children = include_children
        self._own_start = time.process_time()
        self._children_start = _children_cpu_s() if include_children else None

    def elapsed_s(self) -> float | None:
        """CPU seconds so far, or None if part of it cannot be measured here."""
        own = time.process_time() - self._own_start
        if not self._include_children:
            return own
        children_now = _children_cpu_s()
        if children_now is None or self._children_start is None:
            return None
        return own + children_now - self._children_start


@lru_cache(maxsize=1)
def usd_per_cpu_hour() -> float | None:
    """The configured rate, read once per process (a config change needs a restart)."""
    return ComputeCostSettings().usd_per_cpu_hour


def compute_cost(cpu_s: float | None) -> float | None:
    """Estimated USD cost of `cpu_s` CPU seconds, or None if it cannot be priced."""
    rate = usd_per_cpu_hour()
    if cpu_s is None or rate is None:
        return None
    return cpu_s / _SECONDS_PER_HOUR * rate
