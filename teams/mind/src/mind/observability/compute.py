"""CPU time for local work (OCR, ASR, and since #482 scoring) -- its compute cost (#479).

A local model has no bill per call, but it has a real cost: the CPU it holds.
CpuClock measures it, and MIND reports it as CPU seconds -- not dollars: MIND
does not pay for its machine by the hour, so there is no price to convert at.
Dollars apply only to LLM calls (extraction/providers/pricing.py).

What the number is, and is not:

* CPU seconds, not wall-clock seconds. A call that waits on I/O costs little
  here; a call that keeps four cores busy for one second costs four -- the
  share of the machine the call used, a fair way to compare calls.
* Process-wide. process_time() counts every thread in this process, and the
  child-process counters every child it has waited for. MIND's consumers
  handle one message at a time, so a call is all that runs during its window;
  if two calls ever overlap in one process, each is charged for both. Unless
  `this_thread_only`: then only the calling thread's CPU (thread_time()),
  for work that runs in one thread next to others, such as scoring in the API.
* Unknown stays unknown: CPU that could not be measured is None, never 0.0.
"""

from __future__ import annotations

import os
import sys
import time


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
        this_thread_only: Count only the calling thread's CPU (#482) -- for
            work done in one thread of a process that runs others at the same
            time: scoring, in the API next to uvicorn and the publisher's
            other threads, where process-wide CPU would charge it for theirs.
            Not for a model that spreads its work over a thread pool (Whisper,
            the embedding model): that pool's CPU would be left out.
    """

    def __init__(self, *, include_children: bool = False, this_thread_only: bool = False) -> None:
        if include_children and this_thread_only:
            raise ValueError("a child process's CPU is not this thread's")
        self._include_children = include_children
        self._now = time.thread_time if this_thread_only else time.process_time
        self._own_start = self._now()
        self._children_start = _children_cpu_s() if include_children else None

    def elapsed_s(self) -> float | None:
        """CPU seconds so far, or None if part of it cannot be measured here."""
        own = self._now() - self._own_start
        if not self._include_children:
            return own
        children_now = _children_cpu_s()
        if children_now is None or self._children_start is None:
            return None
        return own + children_now - self._children_start
