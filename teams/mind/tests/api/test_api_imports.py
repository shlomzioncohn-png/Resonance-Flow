"""#482: importing Mind's API does not import torch.

The API is deployed with `memory: 512Mi` (Relay's chart). It loads no
embedding model -- /related reads stored vectors -- yet torch and
sentence-transformers used to come in with `mind.embeddings`, ~380 MB of it
(benchmarks/serving-reliability-482.md). embeddings.py now imports them when a
model is first loaded; this keeps it that way. In a fresh interpreter, since
this test session has long since imported both.
"""

import subprocess
import sys

_CHECK = (
    "import sys\n"
    "import mind.api.main\n"
    "heavy = sorted(m for m in ('torch', 'sentence_transformers', 'transformers') "
    "if m in sys.modules)\n"
    "print(','.join(heavy))\n"
)


def test_importing_the_api_imports_no_torch():
    result = subprocess.run(
        [sys.executable, "-c", _CHECK], capture_output=True, text=True, timeout=120, check=False
    )
    assert result.returncode == 0, result.stderr
    assert result.stdout.strip() == ""


def test_the_model_class_is_still_reachable_from_the_module():
    # embeddings.SentenceTransformer resolves on access (tests patch it there).
    from mind.embeddings import embeddings

    assert embeddings.SentenceTransformer.__name__ == "SentenceTransformer"


def test_warm_up_loads_the_model_and_says_so(monkeypatch):
    from mind.embeddings import embeddings

    loaded = []
    monkeypatch.setattr(embeddings, "_get_model", lambda: loaded.append(True))

    assert embeddings.warm_up() is True
    assert loaded == [True]


def test_warm_up_never_raises_a_failed_load(monkeypatch, caplog):
    from mind.embeddings import embeddings

    def broken() -> None:
        raise OSError("model files missing")

    monkeypatch.setattr(embeddings, "_get_model", broken)

    assert embeddings.warm_up() is False
    assert "embedding model not ready at start-up" in caplog.text
