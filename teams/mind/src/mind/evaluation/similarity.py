"""The lexical similarity backend for the extraction-quality eval.

`evaluate_extraction` takes `similarity` and `threshold` as parameters; this
module supplies the real lexical pair, locked in `extraction_eval_design.md`
§4.2 and §6: `rapidfuzz`'s `token_set_ratio`, rescaled to [0, 1], with
`LEXICAL_THRESHOLD = 0.60`.

Built in #178, under #176 step 8's rule: `mind.embeddings` exposes `embed` / `embed_batch`
but no reusable `similarity(a, b)`, so the lexical baseline comes first. An
embedding backend can be added next to it later without touching the harness.

Known limits, both on purpose and both pinned by tests:

- Lexical only: synonyms and paraphrases ("research" vs "find") score low,
  which pushes recall *down*.
- A short text whose words all appear in the other one scores 1.0
  ("send the minutes" vs "the project manager will send the minutes"),
  which can push precision *up*. Compare title and description together, not
  the title alone.
"""

from rapidfuzz import fuzz
from rapidfuzz.utils import default_process

LEXICAL_THRESHOLD = 0.60


def lexical_similarity(a: str, b: str) -> float:
    """How much of the word set `a` and `b` share, from 0.0 to 1.0.

    Word order does not matter. `default_process` lowercases both strings and
    turns punctuation into spaces first, so "minutes," and "minutes" are the
    same word. A string that is empty after that scores 0.0 against anything.
    """

    return fuzz.token_set_ratio(a, b, processor=default_process) / 100.0
