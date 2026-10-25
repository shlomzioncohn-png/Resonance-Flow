"""The analysis packages must never touch the generator's answer files (only `evaluation` may, to score them)."""
import re
from pathlib import Path

SRC = Path(__file__).resolve().parents[1] / "src" / "flow_health"
FORBIDDEN = ("pathologies", "run_manifest", "manifest.json", "ground_truth", "healthy_controls", "answer_key")


def _analysis_sources():
    return [p for p in SRC.rglob("*.py") if not {"generator", "evaluation"} & set(p.relative_to(SRC).parts)]


def test_analysis_code_exists_to_scan():
    assert _analysis_sources()


def test_no_analysis_module_mentions_an_answer_file():
    hits = [(p.name, w) for p in _analysis_sources() for w in FORBIDDEN if re.search(w, p.read_text("utf-8"))]
    assert not hits


def test_no_analysis_module_imports_the_generator():
    hits = [p.name for p in _analysis_sources() if re.search(r"flow_health\.generator|from \.\.generator", p.read_text("utf-8"))]
    assert not hits


def test_no_analysis_module_imports_the_evaluation():
    hits = [p.name for p in _analysis_sources() if re.search(r"flow_health\.evaluation|from \.\.evaluation|from \.evaluation", p.read_text("utf-8"))]
    assert not hits
