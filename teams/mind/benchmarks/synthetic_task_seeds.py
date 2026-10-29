"""#475: a committed corpus of realistic-looking synthetic task texts,
and a reproducible expansion of it up to any target row count.

Why not plain random word-soup (what #87's vector_search_benchmark.py
uses, and what this benchmark used before this review comment): a
vector index's query latency depends on how the data is actually
distributed in embedding space -- gibberish or near-identical strings
cluster in ways real task text never does, so a benchmark built on
either measures something that isn't representative of production. The
fix: realistic text, generated a few hundred at a time by an LLM
(Gemini, prompted per domain -- see synthetic_task_seeds_raw/'s own
files for which domains exist), then expanded past 10,000 rows by
templating -- re-texturing names, dates, ticket ids and counts on each
repeat so reusing the same seed corpus 8+ times over doesn't itself
produce near-duplicate rows.

Raw seeds live one file per domain under synthetic_task_seeds_raw/,
each a JSON array of {"title", "description"} objects written by an LLM
call, committed (see .gitignore) so the expansion is reproducible
without a new LLM call. Parsed leniently, line by line: a response that
got cut off mid-object (this happened generating the first domain file)
still yields every complete entry instead of failing the whole file.

`expand_to(n, seed)` is deterministic for a given `n`, `seed`, and the
current set of raw files -- anyone re-running it gets the same rows.
"""

from __future__ import annotations

import json
import random
import re
from pathlib import Path
from typing import TypedDict

_RAW_DIR = Path(__file__).resolve().parent / "synthetic_task_seeds_raw"


class Seed(TypedDict):
    title: str
    description: str


def load_seed_corpus() -> list[Seed]:
    """Every {"title", "description"} entry from every *.json file under
    synthetic_task_seeds_raw/, in filename order. Tolerant of a response
    that was cut off mid-entry: each line is parsed on its own, so one
    broken trailing line is dropped rather than failing the whole file."""
    seeds: list[Seed] = []
    for path in sorted(_RAW_DIR.glob("*.json")):
        for line in path.read_text(encoding="utf-8").splitlines():
            candidate = line.strip().rstrip(",")
            if candidate in ("[", "]", ""):
                continue
            try:
                entry = json.loads(candidate)
            except json.JSONDecodeError:
                continue
            if isinstance(entry, dict) and "title" in entry and "description" in entry:
                seeds.append({"title": entry["title"], "description": entry["description"]})
    if not seeds:
        raise RuntimeError(f"no seed entries found under {_RAW_DIR}")
    return seeds


_FIRST_NAMES = [
    "Priya", "Maya", "Daniel", "Noah", "Liam", "Omar", "Sofia", "Elena",
    "Hana", "Yuki", "Carlos", "Fatima", "Ravi", "Anna", "Marcus", "Ines",
    "Tomas", "Amira", "Lucas", "Nadia", "Jonas", "Chloe", "Kenji", "Leila",
]
_LAST_NAMES = [
    "Nair", "Chen", "Foster", "Patel", "Kim", "Garcia", "Novak", "Haddad",
    "Tanaka", "Silva", "Mendes", "Rossi", "Brandt", "Nguyen", "Okafor",
    "Lindqvist", "Dubois", "Ferreira", "Bauer", "Castillo", "Ahmadi",
]

_EXTRA_CLAUSES = [
    "Flagged by the on-call engineer.",
    "Needs confirmation before scheduling.",
    "Low priority -- revisit next sprint.",
    "Customer-reported, not yet reproduced internally.",
    "Blocking the next release candidate.",
    "",  # no extra clause, most of the time
]

# Most titles are short bug-report summaries with no name, date, ticket
# id or number in them at all ("Investigate transient DNS failures in
# workers") -- _retexture alone leaves these byte-identical across every
# repeat of the same base seed, which is exactly the near-duplicate
# clustering this whole module exists to avoid. Swapping the leading
# verb for a synonym and occasionally appending a short bracketed tag
# (both realistic on real ticket titles) gives titles the same kind of
# textual variety descriptions already get from entity substitution.
_VERB_SYNONYMS = {
    "Investigate": ["Investigate", "Look into", "Diagnose", "Track down"],
    "Fix": ["Fix", "Resolve", "Patch", "Repair"],
    "Correct": ["Correct", "Fix", "Adjust"],
    "Repair": ["Repair", "Fix", "Restore"],
    "Restore": ["Restore", "Bring back", "Recover"],
    "Resolve": ["Resolve", "Fix", "Close out"],
    "Handle": ["Handle", "Address"],
    "Prevent": ["Prevent", "Stop", "Block"],
}
_TITLE_TAGS = [
    "", "", "", "(regression)", "(intermittent)", "(customer-reported)",
    "(EU region)", "(mobile only)", "(high priority)",
]

# What actually varies between two otherwise-identical real tickets:
# who reported it, when, which numbered ticket/count, by how much.
# Matching and re-rolling these -- not full NLP -- is deliberately the
# whole trick: it leaves sentence structure and vocabulary alone, so a
# retextured copy reads as naturally as the original, while genuinely
# changing what the embedding sees. Two capitalized words also
# occasionally matches a product name instead of a person (e.g. "Atlas
# Billing"), which gets swapped to a person name too -- a known,
# accepted imperfection of a regex heuristic, not NER; it costs a
# little realism on a minority of rows, not correctness of the benchmark.
_NAME_RE = re.compile(r"\b[A-Z][a-z]+ [A-Z][a-z]+\b")
_DATE_RE = re.compile(r"\bOctober \d{1,2}\b")
_BUILD_DATE_RE = re.compile(r"\b202\d\.\d{2}\.\d{2}\b")
_TICKET_RE = re.compile(r"\b([A-Z]{2,5})-\d{3,5}\b")
_NUMBER_RE = re.compile(r"\b\d{1,3}(?:,\d{3})*\b")


def _random_name(rng: random.Random) -> str:
    return f"{rng.choice(_FIRST_NAMES)} {rng.choice(_LAST_NAMES)}"


def _retexture(text: str, rng: random.Random) -> str:
    """A different name, date, ticket id and counts -- the concrete
    specifics two real, unrelated tickets about the same kind of bug
    would genuinely differ on. Sentence structure and vocabulary are
    untouched."""
    text = _NAME_RE.sub(lambda _: _random_name(rng), text)
    text = _DATE_RE.sub(lambda _: f"October {rng.randint(1, 28)}", text)
    text = _BUILD_DATE_RE.sub(
        lambda _: f"2026.{rng.randint(1, 12):02d}.{rng.randint(1, 28):02d}", text
    )
    text = _TICKET_RE.sub(lambda m: f"{m.group(1)}-{rng.randint(1000, 9999)}", text)
    return _NUMBER_RE.sub(lambda m: str(rng.randint(2, 50_000)), text)


def _retexture_title(title: str, rng: random.Random) -> str:
    """_retexture(), plus a leading-verb synonym swap and an occasional
    bracketed tag -- most titles have no name/date/ticket/number in them
    at all, so _retexture alone would leave them byte-identical on
    every repeat."""
    first_word, _, rest = title.partition(" ")
    synonyms = _VERB_SYNONYMS.get(first_word)
    if synonyms:
        title = f"{rng.choice(synonyms)} {rest}"
    title = _retexture(title, rng)
    tag = rng.choice(_TITLE_TAGS)
    return f"{title} {tag}" if tag else title


def expand_to(n: int, *, seed: int = 475) -> list[Seed]:
    """`n` rows, cycling through a fixed shuffle of the raw seed corpus
    (so repeats of the same base seed land spread across the set, not
    adjacent) and retexturing every repeat past the first pass, so
    reusing a seed several times over doesn't produce near-duplicate
    rows. The first time each seed is used, it is exactly what the LLM
    wrote, so the corpus's own realism is never diluted. Deterministic:
    the same `n` and `seed` always produce the same rows."""
    rng = random.Random(seed)
    base = load_seed_corpus()
    order = list(range(len(base)))
    rng.shuffle(order)

    rows: list[Seed] = []
    for i in range(n):
        base_seed = base[order[i % len(order)]]
        if i < len(order):
            rows.append(base_seed)
            continue
        description = _retexture(base_seed["description"], rng)
        extra = rng.choice(_EXTRA_CLAUSES)
        if extra:
            description = f"{description} {extra}".strip()
        retextured: Seed = {
            "title": _retexture_title(base_seed["title"], rng),
            "description": description,
        }
        rows.append(retextured)
    return rows
