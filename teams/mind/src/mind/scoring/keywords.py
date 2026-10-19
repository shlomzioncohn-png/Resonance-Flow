"""The frozen lexicons behind the text-derived features.

Two separate readings of a task's `title` + `description`, because they answer
two different questions:

- `URGENCY_PHRASES` — *when*. Phrases that say the work is time-critical right
  now even though no `deadline` is set ("production has been down", "corrupts
  more rows every run"). Without this, a live incident with `deadline = None`
  reads as "not urgent" and loses to a routine item that happens to carry a
  date, which is the opposite of the truth. Scored by **max**, not sum: two
  ways of saying "this is on fire" is still one fire.
- `IMPACT_PHRASES` — *how much it matters*. Who is affected and how badly.
  Scored by **sum** onto a neutral base, because impact genuinely accumulates
  (a customer-facing outage with an SLA attached is worse than either alone).

Both are matched as **whole words** over lowercased text, with an explicit `*`
marking the few entries that are meant as prefixes (`"escalat*"` covers escalate,
escalated and escalation). Plain substring matching fired inside unrelated words
— "board" inside "dashboard" and "onboarding", "sla" inside "slack" and
"translate" — which handed impact credit to exactly the low-stakes tasks the
scoring is supposed to rank down.

Beyond the word boundary there is still no stemming, no embeddings and no LLM:
the same task must score identically on every run, or the week-5 baseline cannot
be compared to the week-7 stretch.

**Provenance:** every phrase here was taken from the wording of the original
20 *dev* scenarios (see `eval_split`). No holdout scenario was read while building this,
and no phrase was ever added to fix a single failing scenario — they are grouped
into coarse strength bands precisely so that per-word tuning is not possible.
"""

from __future__ import annotations

import re

#: A phrase ending in this marker is a **stem**: it matches from a word boundary
#: onwards and may run into a longer word, so `"regulator*"` also reads
#: "regulators". Every phrase without it must match as a whole word.
STEM_MARKER = "*"

# --- urgency: "this is happening now", scored by max -------------------------
URGENCY_PHRASES: dict[str, float] = {
    # a system is actively broken right now
    "has been down": 1.0,
    "have been down": 1.0,
    "is down": 1.0,
    "are failing": 1.0,
    "is failing": 1.0,
    "it is live now": 1.0,
    "until it is sorted": 1.0,
    "cannot process orders": 1.0,
    # active damage that accumulates while you wait
    "corrupting": 1.0,
    "corrupts more": 1.0,
    "no clean rollback": 1.0,
    "worsens each": 1.0,
    "keeps causing": 0.5,
    # silent risk already in progress
    "data is at risk": 0.7,
    "at risk until": 0.7,
    "silently skipped": 0.7,
}

# --- impact: "how much does this matter", summed onto a neutral base ---------
IMPACT_PHRASES: dict[str, float] = {
    # severe: customer-visible failure, data loss, contractual/regulatory exposure
    "outage*": 0.25,
    "corrupt*": 0.25,
    "malformed records": 0.25,
    "data is at risk": 0.25,
    "sla": 0.25,
    "churn risk": 0.25,
    "compliance flag": 0.25,
    "production": 0.20,
    "competitor*": 0.20,
    # who is affected
    "customer*": 0.15,
    "escalat*": 0.15,
    "board": 0.15,
    "investor*": 0.15,
    "analysts": 0.15,
    "regulator*": 0.15,
    # other people are waiting on this specific person
    "blocked from starting": 0.10,
    "depends on it": 0.10,
    "sign-off": 0.10,
    "needs your": 0.10,
    "must reach": 0.10,
    "so they can prepare": 0.10,
    "so it can be circulated": 0.10,
    # explicitly nobody is waiting / the moment has passed
    "nobody is waiting": -0.30,
    "nobody waiting": -0.30,
    "nothing waiting on it": -0.30,
    "no action is needed from you": -0.30,
    "not requested by anyone": -0.30,
    "no customer impact": -0.30,
    "purely cosmetic": -0.30,
    "already happened": -0.30,
    "self-resolved": -0.30,
    "was noise": -0.30,
    # low-stakes framing
    "housekeeping": -0.15,
    "routine": -0.15,
    "low stakes": -0.15,
    "backlog": -0.15,
    "exploratory": -0.15,
    "flexible": -0.15,
    "no rush": -0.15,
    "stale": -0.15,
}

#: Impact starts here, so a task nobody wrote keywords about reads as neutral
#: rather than worthless.
IMPACT_BASE = 0.5


def _matcher(phrase: str) -> re.Pattern[str]:
    """One phrase as a compiled pattern, honouring the `*` stem marker."""
    if phrase.endswith(STEM_MARKER):
        return re.compile(rf"\b{re.escape(phrase[: -len(STEM_MARKER)])}")
    return re.compile(rf"\b{re.escape(phrase)}\b")


def compile_lexicon(phrases: dict[str, float]) -> tuple[tuple[re.Pattern[str], float], ...]:
    """A lexicon as (pattern, weight) pairs, compiled once at import.

    Returned as a tuple so a lexicon cannot be edited after the fact: the
    phrases are frozen, and so is what they match.
    """
    return tuple((_matcher(phrase), weight) for phrase, weight in phrases.items())


#: What `features` actually reads. The dicts above stay the readable source.
URGENCY_MATCHERS = compile_lexicon(URGENCY_PHRASES)
IMPACT_MATCHERS = compile_lexicon(IMPACT_PHRASES)
