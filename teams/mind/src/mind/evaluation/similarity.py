"""The similarity backends for the extraction-quality eval.

`evaluate_extraction` takes `similarity` and `threshold` as parameters; this
module supplies them, one pair per backend (`extraction_eval_design.md` §4, §6):

- **lexical** — `rapidfuzz`'s `token_set_ratio`, rescaled to [0, 1], with
  `LEXICAL_THRESHOLD = 0.60`. Built in #178, under #176 step 8's rule, before an
  embedding similarity existed. Needs no model, so it stays for CI and as the
  published comparison point (§4.3).
- **embedding** — `mind.embeddings.similarity`, Ayala's cosine over the
  bge-small embeddings (§4.1). Its τ is chosen on the §5 pairs only
  (`threshold_pairs.py`, `scripts/derive_embedding_threshold.py`) and frozen in
  `EMBEDDING_THRESHOLD`; until it is, the backend refuses to run, so no number
  can be produced with a τ picked by eye.

Known limits of the lexical backend, both on purpose and both pinned by tests:

- Lexical only: synonyms and paraphrases ("research" vs "find") score low,
  which pushes recall *down*. The #178 run on real meetings lost four right
  pairs this way — the reason the embedding backend exists.
- A short text whose words all appear in the other one scores 1.0
  ("send the minutes" vs "the project manager will send the minutes"),
  which can push precision *up*. Compare title and description together, not
  the title alone.
"""

from collections.abc import Callable
from typing import Literal

from rapidfuzz import fuzz
from rapidfuzz.utils import default_process

LEXICAL_THRESHOLD = 0.60

# Frozen 2026-10-28 from scripts/derive_embedding_threshold.py on the §5 pairs
# (extraction_eval_design.md §6): the middle of 0.643-0.750, the τ range where
# matching returns exactly the hand-labelled pairs. Chosen before any real
# output was scored with this backend; never moved by a run's precision/recall.
EMBEDDING_THRESHOLD: float | None = 0.70

Backend = Literal["lexical", "embedding"]


def lexical_similarity(a: str, b: str) -> float:
    """How much of the word set `a` and `b` share, from 0.0 to 1.0.

    Word order does not matter. `default_process` lowercases both strings and
    turns punctuation into spaces first, so "minutes," and "minutes" are the
    same word. A string that is empty after that scores 0.0 against anything.
    """

    return fuzz.token_set_ratio(a, b, processor=default_process) / 100.0


def embedding_similarity(a: str, b: str) -> float:
    """Cosine of the two texts' embeddings, clamped to [0, 1] (`mind.embeddings`).

    Imported on first call, not at module load: the lexical backend and the CI
    runs that use it must not need the embedding model.
    """

    from mind.embeddings.embeddings import similarity

    return similarity(a, b)


def backend(name: Backend) -> tuple[Callable[[str, str], float], float]:
    """The `(similarity, threshold)` pair a run of the eval uses for `name`.

    Raises `ValueError` for the embedding backend while its τ is not frozen.
    """

    if name == "lexical":
        return lexical_similarity, LEXICAL_THRESHOLD
    if EMBEDDING_THRESHOLD is None:
        raise ValueError(
            "the embedding backend has no frozen threshold yet: derive it on the §5 pairs "
            "with scripts/derive_embedding_threshold.py and set EMBEDDING_THRESHOLD"
        )
    return embedding_similarity, EMBEDDING_THRESHOLD
