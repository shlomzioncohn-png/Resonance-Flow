"""ASR validation against AMI reference transcripts, with a real WER (#480).

This module holds the two halves every WER number depends on, so they are
tested directly rather than trusted from a printed table:

- **The reference:** AMI's human transcript (the manual annotations' words/
  layer), parsed with each word's start time and speaker, so a fixed window
  of a meeting can be cut out of it to match the audio that was transcribed.
- **The normalization:** one function, applied identically to the reference
  and to Whisper's output, so the WER counts recognition errors rather than
  formatting differences between AMI's transcription conventions and
  Whisper's.

Unlike the rough diff in asr_benchmark.py (#135), which deleted apostrophes
("we're" became "were") and compared digits to spelled-out numbers, nothing
here is "rough": every rule below is deliberate, and the list is the documented
normalization. Changing a rule changes every WER measured with it, so a
re-measurement is only comparable if this list is unchanged.

Normalization rules, applied in this order (see normalize()):

1. Unicode NFKC, and curly apostrophes become a straight one.
2. Lowercase.
3. Underscores are deleted: AMI spells letter-by-letter acronyms as
   "T_V_" / "E_P_F_L_", Whisper writes "TV" / "EPFL" -- both become "tv".
4. "%" becomes the word "percent".
5. Numbers written in digits become words, the way AMI writes them: "25" ->
   "twenty five", "6,000" -> "six thousand", "12.5" -> "twelve point five"
   (digits after the point are read one by one), "21st" -> "twenty first".
   AMI never writes a digit, Whisper usually does. No "and" is inserted
   ("150" -> "one hundred fifty").
6. Every character other than a-z, 0-9 and an apostrophe becomes a space:
   punctuation, hyphens ("twenty-five" -> "twenty five", "part-time" -> "part
   time") and Whisper's ellipses, which join words ("you...one").
7. Spelling variants map to one form: "ok" -> "okay", "alright" -> "all
   right", "'cause" -> "because", "'kay" -> "okay". Then apostrophes at
   either end of a word are stripped; apostrophes inside a word stay ("we're"
   and "were" are different words).
8. Filler words and backchannels are removed: uh, um, er, erm, ah, eh, hm,
   hmm, mm, mmm, mhm, huh (so "mm-hmm" and "uh-huh", split by rule 6, go
   too). Whisper drops these by design, and none carries task content.

What is removed from the reference only, because Whisper cannot produce it:

- Punctuation tokens (punc="true") and the non-word elements (<vocalsound>,
  <disfmarker>, <gap>) -- they are not <w> words.
- Truncated words (trunc="true"): a word cut off mid-way, e.g. "recognit".

Known residual mismatches, deliberately not normalized:

- Years and spoken number forms: "1995" -> "one thousand nine hundred ninety
  five", where a speaker says "nineteen ninety five"; "100" vs. "a hundred";
  "150" vs. "a hundred and fifty".
- Times written as decimals: Whisper's "9.30" -> "nine point three zero".
- Digits glued to letters stay as they are: "1990s", "mp3".
- Currency symbols (none seen in the transcripts so far) and dotted acronyms
  ("T.V." -> "t v").
"""

from __future__ import annotations

import re
import unicodedata
import xml.etree.ElementTree as ET
from pathlib import Path

from pydantic import BaseModel

# Rule 8. Single words only: rule 6 has already split "mm-hmm" into "mm hmm".
FILLER_WORDS = frozenset(
    {"uh", "um", "er", "erm", "ah", "eh", "hm", "hmm", "mm", "mmm", "mhm", "huh"}
)

# Rule 7, looked up before the edge apostrophes are stripped ("'cause" is
# "because", while a bare "cause" may be the noun).
SPELLING_VARIANTS = {
    "ok": ["okay"],
    "'kay": ["okay"],
    "alright": ["all", "right"],
    "'cause": ["because"],
}

_ONES = [
    "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
    "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
    "seventeen", "eighteen", "nineteen",
]  # fmt: skip
_TENS = ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"]
_SCALES = [(10**9, "billion"), (10**6, "million"), (1000, "thousand")]
_IRREGULAR_ORDINALS = {
    "one": "first",
    "two": "second",
    "three": "third",
    "five": "fifth",
    "eight": "eighth",
    "nine": "ninth",
    "twelve": "twelfth",
}

# Rule 5: an integer with optional thousands commas ("6,000"), an optional
# decimal part, an optional ordinal suffix. Not inside a longer token: the
# lookarounds keep "1p2p3p", "mp3" and "1990s" as they are. A preceding "."
# is allowed, for Whisper's ellipses ("so...25").
_NUMBER = re.compile(r"(?<!\w)(\d{1,3}(?:,\d{3})+|\d+)(\.\d+)?(st|nd|rd|th)?(?!\w)")


def _below_thousand(n: int) -> list[str]:
    words: list[str] = []
    if n >= 100:
        words += [_ONES[n // 100], "hundred"]
        n %= 100
    if n >= 20:
        words.append(_TENS[n // 10])
        n %= 10
        if n:
            words.append(_ONES[n])
    elif n or not words:
        words.append(_ONES[n])
    return words


def number_words(n: int) -> list[str]:
    """A non-negative integer as English words: 6000 -> ["six", "thousand"]."""
    if n == 0:
        return ["zero"]
    words: list[str] = []
    for scale, name in _SCALES:
        if n >= scale:
            words += _below_thousand(n // scale) + [name]
            n %= scale
    if n:
        words += _below_thousand(n)
    return words


def _ordinal(words: list[str]) -> list[str]:
    last = words[-1]
    if last in _IRREGULAR_ORDINALS:
        last = _IRREGULAR_ORDINALS[last]
    elif last.endswith("y"):
        last = last[:-1] + "ieth"
    else:
        last += "th"
    return words[:-1] + [last]


def _spell_number(match: re.Match[str]) -> str:
    integer, decimal, ordinal_suffix = match.groups()
    words = number_words(int(integer.replace(",", "")))
    if ordinal_suffix:
        words = _ordinal(words)
    if decimal:
        words += ["point"] + [_ONES[int(d)] for d in decimal[1:]]
    return f" {' '.join(words)} "


def normalize(text: str) -> list[str]:
    """Normalize a transcript into words for WER (rules 1-8 in the module docstring).

    The same function for the reference and for Whisper's output: a rule
    applied to only one side would itself create or hide errors."""
    text = unicodedata.normalize("NFKC", text).replace("’", "'").replace("‘", "'")
    text = text.lower()
    text = text.replace("_", "")
    text = text.replace("%", " percent ")
    text = _NUMBER.sub(_spell_number, text)
    text = re.sub(r"[^a-z0-9']+", " ", text)

    words: list[str] = []
    for token in text.split():
        for word in SPELLING_VARIANTS.get(token, [token]):
            word = word.strip("'")
            if word and word not in FILLER_WORDS:
                words.append(word)
    return words


class ReferenceWord(BaseModel):
    """One word of AMI's human transcript, as written there (not normalized)."""

    start_s: float
    end_s: float
    speaker: str  # A-D: the letter in the words file name, one per participant
    text: str


def _speaker(words_file: Path) -> str:
    # IS1008b.A.words.xml -> "A"
    parts = words_file.name.split(".")
    if len(parts) < 4 or parts[-2:] != ["words", "xml"]:
        raise ValueError(f"not an AMI words file name: {words_file.name}")
    return parts[-3]


def load_reference(words_files: list[Path]) -> list[ReferenceWord]:
    """AMI's human transcript for one meeting, all speakers interleaved by start time.

    Each <w> keeps its start/end time and speaker, so a time window can be cut
    out (reference_window) and overlapping speech can be measured. Left out:
    punctuation tokens, truncated words, and the non-word elements (only <w>
    elements are read) -- see the module docstring. Ties on start time are
    broken by speaker, so the order is the same on every run."""
    words: list[ReferenceWord] = []
    for path in words_files:
        speaker = _speaker(path)
        for w in ET.parse(path).getroot().iter("w"):
            if w.get("punc") == "true" or w.get("trunc") == "true":
                continue
            text = (w.text or "").strip()
            if not text:
                continue
            words.append(
                ReferenceWord(
                    start_s=float(w.attrib["starttime"]),
                    end_s=float(w.attrib["endtime"]),
                    speaker=speaker,
                    text=text,
                )
            )
    words.sort(key=lambda w: (w.start_s, w.speaker))
    return words


def reference_window(
    words: list[ReferenceWord], start_s: float, end_s: float
) -> list[ReferenceWord]:
    """The reference words that start in [start_s, end_s).

    A word belongs to the window it starts in, so consecutive windows split a
    meeting's words without dropping or double-counting any."""
    if end_s <= start_s:
        raise ValueError(f"empty window: start {start_s} s, end {end_s} s")
    return [w for w in words if start_s <= w.start_s < end_s]


def reference_text(words: list[ReferenceWord]) -> str:
    """The words joined in time order, ready for normalize()."""
    return " ".join(w.text for w in words)
