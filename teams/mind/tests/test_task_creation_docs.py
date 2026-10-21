"""The 'why' of task_creation lives in its README alone; the code points to it.

A pointer names a section by its heading, and nothing else would notice a
renamed heading leaving the pointer aimed at nothing.
"""

import re
from pathlib import Path

PACKAGE = Path(__file__).parents[1] / "src" / "mind" / "task_creation"
POINTER = re.compile(r'README\.md,\s*"([^"]+)"')


def _readme_headings() -> set[str]:
    lines = (PACKAGE / "README.md").read_text(encoding="utf-8").splitlines()
    return {line.lstrip("#").strip() for line in lines if line.startswith("#")}


def _pointers() -> list[tuple[str, str]]:
    found = []
    for source in sorted(PACKAGE.glob("*.py")):
        # Collapse line breaks, so a pointer wrapped across lines still matches.
        text = " ".join(source.read_text(encoding="utf-8").split())
        found += [(source.name, heading) for heading in POINTER.findall(text)]
    return found


def test_every_readme_section_the_code_points_to_exists() -> None:
    pointers = _pointers()
    headings = _readme_headings()

    # Guards the guard: were the pointer format to change, finding none would pass.
    assert pointers

    missing = [(name, heading) for name, heading in pointers if heading not in headings]
    assert missing == []
