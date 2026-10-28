"""ASR validation against AMI reference transcripts, with a real WER (#480).

Run from teams/mind/: python -m benchmarks.asr_validation [--sizes ...]
(default: the chosen size only; --sizes tiny.en base.en small.en to compare).

What it measures is the ASR pipeline itself, not Whisper on its own: every
window goes through mind.multimodal.asr.transcribe() -- its chunking, VAD,
caps and timeout -- and must leave a record_call() record, which is where
the latency and CPU time in the table come from. (The #135 size benchmark,
asr_benchmark.py, calls faster-whisper directly; it is left as it is, so its
recorded numbers still reproduce.)

The fixed set: three VALIDATION_WINDOW_S windows per meeting in
eval/datasets/ami-audio.manifest.json, at VALIDATION_WINDOW_POSITIONS of its
length (validation_windows()). Not whole meetings: transcribe() decodes at
most MAX_DURATION_S of a recording and gives up after TRANSCRIBE_TIMEOUT_S,
and a 30-minute meeting would hit both. A window the pipeline still cut short
is flagged in the table, never scored as if it were complete.

WER is jiwer's (word-level Levenshtein), on normalized words. Overall and
per-meeting WER are pooled -- every window's errors summed, over every
window's reference words -- not an average of per-window rates, which would
let a short window weigh as much as a long one.

Two halves every WER number depends on are tested directly rather than
trusted from a printed table:

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
7. Word forms map to one form, each from a fixed list:
   - spelling variants: "ok" -> "okay", "alright" -> "all right", "'cause"
     -> "because", "'kay" -> "okay";
   - British spellings become American, as Whisper writes them: "organise"
     -> "organize", "colour" -> "color" (BRITISH_SPELLINGS);
   - spoken forms AMI writes as said become the words Whisper writes:
     "wanna" -> "want to", "gonna" -> "going to", "dunno" -> "don't know"
     (SPOKEN_FORMS).
   Then apostrophes at either end of a word are stripped; apostrophes inside
   a word stay ("we're" and "were" are different words).
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

import argparse
import concurrent.futures
import importlib.metadata
import io
import multiprocessing
import os
import re
import unicodedata
import wave
import xml.etree.ElementTree as ET
from collections.abc import Callable, Iterable
from pathlib import Path

import jiwer
from pydantic import BaseModel

from benchmarks.asr_benchmark import CHOSEN_WHISPER_SIZE, WHISPER_SIZES, Meeting, load_meetings
from mind.multimodal.asr import ASRResult
from mind.observability.tracking import CallKind, get_recent_calls

_PROJECT_ROOT = Path(__file__).parent.parent
# Every window's full ASRResult (segments, timestamps, confidences), for
# reading the worst segments by eye -- gitignored, like the audio.
_OUTPUT_DIR = _PROJECT_ROOT / "local-data" / "ami" / "asr-validation"

# Under TRANSCRIBE_TIMEOUT_S (240 s of processing) at every real-time factor
# #135 measured for base.en (worst 0.31) and at small.en's quiet-host RTF
# (0.67); small.en on a busy host (RTF up to 1.4) can still time out -- which
# the table then shows, since that is the pipeline's real behavior.
VALIDATION_WINDOW_S = 240.0
# A quarter, half and three quarters into the meeting: past the opening
# small talk, and spread over the whole discussion.
VALIDATION_WINDOW_POSITIONS = (0.25, 0.5, 0.75)

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

# Rule 7, British spellings: every one in the reference of IS1008b, IB4003 and
# IS1008d (#480, D4). A list, not an -ise -> -ize rule, which would also turn
# "advertise", "otherwise" and "precise" -- in the same reference -- into
# words that don't exist. A meeting added to the set needs its reference
# checked for more.
BRITISH_SPELLINGS = {
    "authorisation": ["authorization"],
    "centre": ["center"],
    "centres": ["centers"],
    "colour": ["color"],
    "customisation": ["customization"],
    "customise": ["customize"],
    "customised": ["customized"],
    "finalise": ["finalize"],
    "labelled": ["labeled"],
    "labour": ["labor"],
    "manoeuvre": ["maneuver"],
    "minimise": ["minimize"],
    "organise": ["organize"],
    "recognise": ["recognize"],
    "recognised": ["recognized"],
    "synchronise": ["synchronize"],
    "utilise": ["utilize"],
}

# Rule 7, spoken forms AMI writes as said and Whisper writes out: every one
# in the same reference (#480, D4).
SPOKEN_FORMS = {
    "wanna": ["want", "to"],
    "gonna": ["going", "to"],
    "gotta": ["got", "to"],
    "dunno": ["don't", "know"],
    "kinda": ["kind", "of"],
}

_WORD_FORMS = SPELLING_VARIANTS | BRITISH_SPELLINGS | SPOKEN_FORMS

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
        for word in _WORD_FORMS.get(token, [token]):
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


# --- scoring ------------------------------------------------------------------


class WordErrors(BaseModel):
    """Word-level edit counts of a hypothesis against its reference (normalized words).

    Only the WER is unique: among alignments of equal cost, how the errors split
    into S / D / I is the implementation's choice (jiwer's)."""

    hits: int
    substitutions: int
    deletions: int
    insertions: int

    @property
    def ref_words(self) -> int:
        return self.hits + self.substitutions + self.deletions

    @property
    def hyp_words(self) -> int:
        return self.hits + self.substitutions + self.insertions

    @property
    def wer(self) -> float:
        return (self.substitutions + self.deletions + self.insertions) / self.ref_words


def score(reference: list[str], hypothesis: list[str]) -> WordErrors:
    """jiwer's alignment of two normalized word lists.

    An empty reference has no WER (nothing to divide by) and means the window
    was badly chosen, so it is refused rather than scored."""
    if not reference:
        raise ValueError("empty reference: no WER for a window with no reference words")
    out = jiwer.process_words(" ".join(reference), " ".join(hypothesis))
    return WordErrors(
        hits=out.hits,
        substitutions=out.substitutions,
        deletions=out.deletions,
        insertions=out.insertions,
    )


def pooled(errors: Iterable[WordErrors]) -> WordErrors:
    """Counts summed, so .wer is total errors over total reference words."""
    errors = list(errors)
    return WordErrors(
        hits=sum(e.hits for e in errors),
        substitutions=sum(e.substitutions for e in errors),
        deletions=sum(e.deletions for e in errors),
        insertions=sum(e.insertions for e in errors),
    )


# --- the fixed set --------------------------------------------------------------


class Window(BaseModel):
    """A fixed stretch of one meeting: the unit that is transcribed and scored."""

    meeting: str
    start_s: float
    end_s: float

    @property
    def label(self) -> str:
        return f"{self.meeting}@{self.start_s:.0f}-{self.end_s:.0f}s"


def validation_windows(meeting: str, duration_s: float) -> list[Window]:
    """The fixed windows of one meeting: VALIDATION_WINDOW_S long, starting at
    each of VALIDATION_WINDOW_POSITIONS of its length (whole seconds)."""
    windows = []
    for position in VALIDATION_WINDOW_POSITIONS:
        start = float(round(position * duration_s))
        end = start + VALIDATION_WINDOW_S
        if end > duration_s:
            raise ValueError(f"{meeting} ({duration_s} s) is too short for a window at {start} s")
        windows.append(Window(meeting=meeting, start_s=start, end_s=end))
    return windows


def window_wav(audio: Path, window: Window) -> bytes:
    """The window's stretch of a WAV file, as the bytes of a WAV file of its own --
    what transcribe() takes, like a recording landed by Bridge."""
    with wave.open(str(audio), "rb") as src:
        rate = src.getframerate()
        src.setpos(round(window.start_s * rate))
        frames = src.readframes(round((window.end_s - window.start_s) * rate))
        buf = io.BytesIO()
        with wave.open(buf, "wb") as dst:
            dst.setnchannels(src.getnchannels())
            dst.setsampwidth(src.getsampwidth())
            dst.setframerate(rate)
            dst.writeframes(frames)
    return buf.getvalue()


# --- running ------------------------------------------------------------------


class WindowResult(BaseModel):
    """One (size, window) measurement."""

    size: str
    window: Window
    errors: WordErrors
    audio_s: float  # how much of the window transcribe() covered
    # From the record_call() record of the call, not timed here: the pipeline's
    # own measurement, so a broken record would show up as a broken table.
    latency_s: float
    cpu_s: float | None
    # transcribe() stopped before the end of the window (its timeout): the
    # hypothesis covers less audio than the reference, so its WER is inflated.
    truncated: bool

    @property
    def real_time_factor(self) -> float:
        return self.latency_s / (self.window.end_s - self.window.start_s)


Transcribe = Callable[..., ASRResult]


def validate_window(
    transcribe: Transcribe,
    size: str,
    audio: Path,
    reference: list[ReferenceWord],
    window: Window,
) -> tuple[WindowResult, ASRResult]:
    """Transcribe one window through the pipeline and score it against the reference.

    Fails loudly rather than scoring if the transcription failed (a WER of 1.0
    would hide an environment problem behind an accuracy number), or if the
    call left no ASR record_call() record under this size (#480: week 8's
    monitoring depends on every ASR call going through it)."""
    before = get_recent_calls(1)
    result = transcribe(window_wav(audio, window), filename=f"{window.label}.wav")
    after = get_recent_calls(1)
    if not result.success:
        raise RuntimeError(f"{window.label} with {size}: {result.reason}: {result.error}")
    if not after or (before and after[-1] is before[-1]) or after[-1].kind != CallKind.ASR:
        raise RuntimeError(f"{window.label} with {size}: transcribe() left no record_call() record")
    record = after[-1]
    if size not in record.model_name:
        raise RuntimeError(f"{window.label}: recorded under {record.model_name!r}, not {size}")

    in_window = reference_window(reference, window.start_s, window.end_s)
    row = WindowResult(
        size=size,
        window=window,
        errors=score(normalize(reference_text(in_window)), normalize(result.text)),
        audio_s=result.duration_s,
        latency_s=record.duration_ms / 1000,
        cpu_s=record.cpu_s,
        truncated=result.truncated,
    )
    return row, result


def validate_size(size: str, meetings: list[Meeting]) -> list[WindowResult]:
    """Every window of every meeting with one Whisper size.

    Runs in its own fresh process (see run_validation): transcribe() loads its
    model once per process, so the size is set before the first call. ASR reads
    it from config.yaml's asr.whisper_model, which wins over WHISPER_MODEL
    (#481), so this process gets a config.yaml of its own naming the size --
    the host's config, if any, cannot pick the model being measured."""
    out_dir = _OUTPUT_DIR / size
    out_dir.mkdir(parents=True, exist_ok=True)
    config_file = out_dir / "config.yaml"
    config_file.write_text(f"asr:\n  whisper_model: {size}\n", encoding="utf-8")
    os.environ["CONFIG_FILE_PATH"] = str(config_file)
    from mind.multimodal import asr

    # Loads the model and the VAD session up front, so neither lands in the
    # first window's latency.
    if not asr.warm_up():
        raise RuntimeError(f"Whisper {size} could not be loaded -- see the log above")
    rows = []
    for meeting in meetings:
        reference = load_reference(meeting.words_files)
        for window in validation_windows(meeting.id, meeting.duration_s):
            row, result = validate_window(asr.transcribe, size, meeting.audio, reference, window)
            (out_dir / f"{window.label}.json").write_text(
                result.model_dump_json(indent=2), encoding="utf-8"
            )
            rows.append(row)
    return rows


def _format_counts(name: str, errors: WordErrors) -> str:
    return (
        f"| {name} | {errors.wer:.3f} | {errors.ref_words:,} | {errors.hyp_words:,} | "
        f"{errors.substitutions} / {errors.deletions} / {errors.insertions} |"
    )


def format_size_report(size: str, rows: list[WindowResult]) -> str:
    """Per-window, per-meeting and overall WER for one size, as markdown tables."""
    lines = [
        f"### {size}",
        "",
        "| Window | WER | Ref words | Hyp words | S / D / I | RTF | CPU s | Truncated |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for r in rows:
        cpu = f"{r.cpu_s:.0f}" if r.cpu_s is not None else "n/a"
        lines.append(
            _format_counts(r.window.label, r.errors)
            + f" {r.real_time_factor:.3f} | {cpu} | {'YES' if r.truncated else 'no'} |"
        )
    lines += [
        "",
        "| Meeting (pooled) | WER | Ref words | Hyp words | S / D / I |",
        "|---|---|---|---|---|",
    ]
    for meeting in dict.fromkeys(r.window.meeting for r in rows):
        in_meeting = (r.errors for r in rows if r.window.meeting == meeting)
        lines.append(_format_counts(meeting, pooled(in_meeting)))
    lines.append(_format_counts("**overall**", pooled(r.errors for r in rows)))
    truncated = sum(r.truncated for r in rows)
    if truncated:
        lines.append(
            f"\nWARNING: {truncated} window(s) truncated by transcribe()'s timeout -- "
            "their WER counts audio that was never transcribed."
        )
    return "\n".join(lines)


def run_validation(sizes: list[str]) -> dict[str, list[WindowResult]]:
    """Validate every size, each in its own fresh process."""
    meetings = load_meetings()
    print(
        f"jiwer {importlib.metadata.version('jiwer')}; normalization: benchmarks/asr_validation.py"
    )
    print("Fixed set:")
    for meeting in meetings:
        labels = [w.label for w in validation_windows(meeting.id, meeting.duration_s)]
        print(f"  {', '.join(labels)}")
    results: dict[str, list[WindowResult]] = {}
    spawn = multiprocessing.get_context("spawn")
    for size in sizes:
        print(f"\nTranscribing with {size}...", flush=True)
        with concurrent.futures.ProcessPoolExecutor(max_workers=1, mp_context=spawn) as pool:
            results[size] = pool.submit(validate_size, size, meetings).result()
        print(format_size_report(size, results[size]), flush=True)
    return results


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="ASR validation against AMI, with a real WER")
    parser.add_argument(
        "--sizes",
        nargs="+",
        choices=WHISPER_SIZES,
        default=[CHOSEN_WHISPER_SIZE],
        help=f"Whisper sizes to validate (default: {CHOSEN_WHISPER_SIZE}, the chosen one)",
    )
    run_validation(parser.parse_args().sizes)
