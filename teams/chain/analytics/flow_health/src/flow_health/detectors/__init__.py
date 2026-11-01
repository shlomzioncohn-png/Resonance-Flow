"""Three detectors that flag unhealthy dependency-flow patterns. Advisory only: nothing here writes to Chain."""
from .common import Flag, write_flags

__all__ = ["Flag", "write_flags"]
