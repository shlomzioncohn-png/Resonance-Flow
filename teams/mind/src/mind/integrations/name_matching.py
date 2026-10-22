"""How well a name found in text matches a name in Inbox (#131).

Pure functions: strings in, a score from 0 to 1 out. No network, no state, so
the table of cases agreed for #131 can be a plain parametrized test.

The rules are ours, written down here, not learned. They compare word with
word, never the two strings as a whole: a string comparison is swayed by how
long the name is, so "sales team" came out close to "Sales" while "team" is a
word the name does not have at all.

* A name is its words, lower-cased, split on spaces, ".", "_" and "-" -- so
  "Dana  Cohen", "dana.cohen" and "dana_cohen" are the same name, and so are
  "Mary Ann" and "Mary-Ann".
* Every word of the mention needs a partner among the words of the name: the
  same word, or one with a typo (`WORD_MATCH_MIN` and up). One word with no
  partner and the name does not match at all.
* An initial ("C." in "Dana C.") is a partner for a word that starts with it,
  but only when the mention has a whole word besides: a lone "D" would
  otherwise be a partner for every name that starts with D.
* The similarity of a mention is the average similarity of its words to their
  partners, so a typo lowers it.
* If the mention covers every word of the name it is a full match and scores
  that similarity (1.0 when the words are the same, whatever their order).
  If a word of the name is left over ("Dana" for "Dana Cohen") it is a partial
  match: the similarity times `PARTIAL_SCORE`. A partial match has to be
  returned at all so that #132 can see there are two Danas and send no owner.

The score is how alike two names are. It is not how sure a model is; turning it
into the confidence Mind sends is #132's job.
"""

import re
from collections.abc import Iterable
from difflib import SequenceMatcher

# A word of the mention counts as a word of the name from this similarity up.
# Set from the 19 cases agreed for #131: the pairs that must not match top out
# at 0.67 ("dana" against "danny") and the ones that must start at 0.75 ("dena"
# against "dana"), and this is the middle of that gap. So it rests on a handful
# of pairs, not on a measured set: it is a starting point to revisit with real
# names. Inclusive on purpose.
WORD_MATCH_MIN = 0.71

# What a partial match is worth, as a multiple of its similarity. It has to stay
# above `min_score` (config.InboxDirectorySettings), or a lone first name would
# be dropped. Not measured either.
PARTIAL_SCORE = 0.7

# How well an initial stands in for the word it starts. Below 1.0 because "C"
# is less than "Cohen", above `WORD_MATCH_MIN` so that it does count as a
# partner. Not measured.
INITIAL_SIMILARITY = 0.9

_WORD_BOUNDARY = re.compile(r"[\s._-]+")


def words(text: str | None) -> tuple[str, ...]:
    """The words of a name, lower-cased. Empty for nothing."""

    if not text:
        return ()
    return tuple(w for w in _WORD_BOUNDARY.split(text.casefold()) if w)


def _word_similarity(a: str, b: str, *, initials: bool) -> float:
    similarity = SequenceMatcher(None, a, b).ratio()
    if initials and len(a) == 1 and len(b) > 1 and b.startswith(a):
        return max(similarity, INITIAL_SIMILARITY)
    return similarity


def score_words(mention: tuple[str, ...], name: tuple[str, ...]) -> float:
    """How well the words of a mention match the words of one name; 0.0 for none."""

    if not mention or not name:
        return 0.0

    # An initial counts only next to a whole word (see the module docstring).
    initials = any(len(word) > 1 for word in mention)

    similarities: list[float] = []
    covered: set[int] = set()  # which words of the name got a partner
    for word in mention:
        similarity, index = max(
            ((_word_similarity(word, other, initials=initials), i) for i, other in enumerate(name)),
            key=lambda pair: pair[0],
        )
        if similarity < WORD_MATCH_MIN:
            return 0.0
        similarities.append(similarity)
        covered.add(index)

    score = sum(similarities) / len(similarities)
    if len(covered) == len(name):
        return score
    return score * PARTIAL_SCORE


def best_score(mention_words: tuple[str, ...], names: Iterable[str | None]) -> float:
    """The best score of a mention (already split into words) against any of the
    names one candidate goes by. Split the mention once and call this for each
    candidate, rather than `score_names`, which splits it every time."""

    return max((score_words(mention_words, words(n)) for n in names), default=0.0)


def score_names(mention: str, names: Iterable[str | None]) -> float:
    """The best score of `mention` against any of the names one candidate goes by."""

    return best_score(words(mention), names)
