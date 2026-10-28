"""What breaks in the ASR validation's transcripts, and where (#480).

Run from teams/mind/, after benchmarks.asr_validation has saved its results:
python -m benchmarks.asr_error_analysis [--sizes ...] [--worst N]

Reads the ASRResults asr_validation saved under local-data/ami/asr-validation/
-- nothing is transcribed again, so the analysis is of exactly the run whose
WER was reported. Every number here uses the same normalization and the same
jiwer alignment as that WER.

Three measurements, each over every window that was not truncated (a
truncated window's untranscribed tail would count as deletions):

- **Overlap.** A reference word is "overlapped" when a word of another
  speaker is being spoken at the same time (their [start, end) intervals
  intersect, from AMI's word timings, over the whole meeting so a window's
  edges do not hide an overlap). Deletion and substitution rates are compared
  between overlapped and non-overlapped words -- also with short response
  words (RESPONSE_WORDS) left out, since those are both often overlapped and
  often dropped, which could produce a difference on their own.
- **Deletion runs.** Consecutive deleted reference words: an isolated
  deletion is a missed word, a run of LONG_RUN or more is a stretch of speech
  the transcript does not contain at all.
- **Worst stretches.** Each window cut into BIN_S bins, by each reference
  word's start time; the bins with the highest error rate are printed with
  the reference (deleted words in [brackets], substitutions as {ref>hyp}) and
  the hypothesis, to be read by eye.

AMI's word timings come from forced alignment, so "overlapped" is accurate to
a fraction of a second, not exact. The comparison is an association: it says
whether deletions concentrate in overlapped speech, not why.
"""

from __future__ import annotations

import argparse
import json
from collections import Counter

import jiwer
from pydantic import BaseModel

from benchmarks.asr_benchmark import WHISPER_SIZES, load_meetings
from benchmarks.asr_validation import (
    _OUTPUT_DIR,
    ReferenceWord,
    Window,
    load_reference,
    normalize,
    reference_text,
    reference_window,
    validation_windows,
)

BIN_S = 30.0
LONG_RUN = 5
# Short replies and acknowledgements: typically overlapped (said over the
# current speaker) and short enough to be dropped. Left out of the second
# overlap comparison so they cannot produce the overlap effect on their own.
RESPONSE_WORDS = frozenset({"yeah", "yes", "yep", "okay", "right", "oh", "no", "sure"})


class RefToken(BaseModel):
    """One normalized reference word, with the timing of the AMI word it came from."""

    text: str
    start_s: float
    speaker: str
    overlapped: bool


class Outcome(BaseModel):
    """What the alignment did with one reference token."""

    kind: str  # "hit", "sub" or "del"
    hyp: str | None = None  # the hypothesis word, for a hit or a substitution


def overlap_flags(words: list[ReferenceWord]) -> list[bool]:
    """For each word: does a word of another speaker intersect its [start, end)?

    `words` must be sorted by start time (as load_reference returns them).
    Zero-length words overlap nothing."""
    flags = [False] * len(words)
    for i, w in enumerate(words):
        for j in range(i + 1, len(words)):
            other = words[j]
            if other.start_s >= w.end_s:
                break  # sorted by start: nothing later can intersect w
            # Here w.start_s <= other.start_s < w.end_s, so they intersect
            # unless `other` is zero-length.
            if other.speaker != w.speaker and other.start_s < other.end_s:
                flags[i] = flags[j] = True
    return flags


def reference_tokens(words: list[ReferenceWord], flags: list[bool]) -> list[RefToken]:
    """normalize() applied word by word, each resulting token keeping its word's
    timing and overlap flag ("twenty-five" -> two tokens, a filler -> none).

    Gives the same token list as normalize(reference_text(words)) -- every
    normalization rule acts within one AMI word -- and run() checks that it does."""
    return [
        RefToken(text=token, start_s=w.start_s, speaker=w.speaker, overlapped=flag)
        for w, flag in zip(words, flags, strict=True)
        for token in normalize(w.text)
    ]


def align(
    reference: list[str], hypothesis: list[str]
) -> tuple[list[Outcome], list[tuple[int, str]]]:
    """jiwer's alignment, per reference token, plus the inserted words.

    Returns (one Outcome per reference token, [(index of the reference token
    the insertion follows, or -1 before the first, inserted word)])."""
    out = jiwer.process_words(" ".join(reference), " ".join(hypothesis))
    outcomes: list[Outcome] = [Outcome(kind="del") for _ in reference]
    insertions: list[tuple[int, str]] = []
    for chunk in out.alignments[0]:
        ref = range(chunk.ref_start_idx, chunk.ref_end_idx)
        hyp = range(chunk.hyp_start_idx, chunk.hyp_end_idx)
        if chunk.type in ("equal", "substitute"):
            kind = "hit" if chunk.type == "equal" else "sub"
            for r, h in zip(ref, hyp, strict=True):
                outcomes[r] = Outcome(kind=kind, hyp=hypothesis[h])
        elif chunk.type == "insert":
            insertions += [(chunk.ref_start_idx - 1, hypothesis[h]) for h in hyp]
        # "delete": already Outcome(kind="del")
    return outcomes, insertions


class Rates(BaseModel):
    words: int = 0
    deleted: int = 0
    substituted: int = 0

    def add(self, outcome: Outcome) -> None:
        self.words += 1
        self.deleted += outcome.kind == "del"
        self.substituted += outcome.kind == "sub"

    def cell(self) -> str:
        if not self.words:
            return "-- | --"
        return f"{self.deleted / self.words:.3f} | {self.substituted / self.words:.3f}"


def deletion_runs(outcomes: list[Outcome]) -> list[int]:
    """Lengths of every maximal run of consecutive deletions."""
    runs, current = [], 0
    for o in outcomes:
        if o.kind == "del":
            current += 1
        elif current:
            runs.append(current)
            current = 0
    if current:
        runs.append(current)
    return runs


class Bin(BaseModel):
    window: str
    start_s: float
    end_s: float
    ref_words: int
    errors: int
    marked_reference: str
    hypothesis: str

    @property
    def error_rate(self) -> float:
        return self.errors / self.ref_words


def bins(
    window: Window,
    tokens: list[RefToken],
    outcomes: list[Outcome],
    insertions: list[tuple[int, str]],
) -> list[Bin]:
    """The window cut into BIN_S bins by each reference token's start time; an
    insertion counts in the bin of the reference token it follows."""
    inserted_after: dict[int, list[str]] = {}
    for after, word in insertions:
        inserted_after.setdefault(after, []).append(word)

    result: list[Bin] = []
    start = window.start_s
    while start < window.end_s:
        end = min(start + BIN_S, window.end_s)
        idx = [i for i, t in enumerate(tokens) if start <= t.start_s < end]
        if idx:
            marked, hyp, errors = [], [], 0
            if idx[0] == 0:
                hyp += inserted_after.get(-1, [])
                errors += len(inserted_after.get(-1, []))
            for i in idx:
                o = outcomes[i]
                if o.kind == "hit":
                    marked.append(tokens[i].text)
                    hyp.append(o.hyp or "")
                elif o.kind == "sub":
                    marked.append(f"{{{tokens[i].text}>{o.hyp}}}")
                    hyp.append(o.hyp or "")
                    errors += 1
                else:
                    marked.append(f"[{tokens[i].text}]")
                    errors += 1
                extra = inserted_after.get(i, [])
                marked += [f"+{w}" for w in extra]
                hyp += extra
                errors += len(extra)
            result.append(
                Bin(
                    window=window.label,
                    start_s=start,
                    end_s=end,
                    ref_words=len(idx),
                    errors=errors,
                    marked_reference=" ".join(marked),
                    hypothesis=" ".join(hyp),
                )
            )
        start = end
    return result


def _transcript(size: str, window: Window) -> dict:
    path = _OUTPUT_DIR / size / f"{window.label}.json"
    if not path.exists():
        raise RuntimeError(f"{path} not found -- run python -m benchmarks.asr_validation first")
    return json.loads(path.read_text(encoding="utf-8"))


def analyze_size(size: str, worst: int) -> str:
    """The three measurements for one size, as a markdown report."""
    overlap = {True: Rates(), False: Rates()}
    overlap_content = {True: Rates(), False: Rates()}
    deleted_words: Counter[tuple[str, bool]] = Counter()
    substituted: Counter[tuple[str, str]] = Counter()
    runs: list[int] = []
    all_bins: list[Bin] = []
    skipped: list[str] = []
    window_rows: list[str] = []

    for meeting in load_meetings():
        words = load_reference(meeting.words_files)
        flags = overlap_flags(words)
        for window in validation_windows(meeting.id, meeting.duration_s):
            saved = _transcript(size, window)
            if saved["truncated"]:
                skipped.append(window.label)
                continue
            in_window = [
                (w, f) for w, f in zip(words, flags, strict=True)
                if window.start_s <= w.start_s < window.end_s
            ]  # fmt: skip
            tokens = reference_tokens([w for w, _ in in_window], [f for _, f in in_window])
            expected = normalize(
                reference_text(reference_window(words, window.start_s, window.end_s))
            )
            if [t.text for t in tokens] != expected:
                raise RuntimeError(f"{window.label}: word-by-word normalization diverged")
            outcomes, insertions = align([t.text for t in tokens], normalize(saved["text"]))

            for token, outcome in zip(tokens, outcomes, strict=True):
                overlap[token.overlapped].add(outcome)
                if token.text not in RESPONSE_WORDS:
                    overlap_content[token.overlapped].add(outcome)
                if outcome.kind == "del":
                    deleted_words[(token.text, token.overlapped)] += 1
                elif outcome.kind == "sub":
                    substituted[(token.text, outcome.hyp or "")] += 1
            runs += deletion_runs(outcomes)
            all_bins += bins(window, tokens, outcomes, insertions)
            deleted = sum(o.kind == "del" for o in outcomes)
            errors = deleted + sum(o.kind == "sub" for o in outcomes) + len(insertions)
            window_rows.append(
                f"| {window.label} | {sum(t.overlapped for t in tokens) / len(tokens):.1%} | "
                f"{errors / len(tokens):.3f} | {deleted} |"
            )

    total = overlap[True].words + overlap[False].words
    deletions = sum(runs)
    long_run_words = sum(r for r in runs if r >= LONG_RUN)
    lines = [f"### {size}", ""]
    if skipped:
        lines += [f"Left out (truncated): {', '.join(skipped)}", ""]
    lines += [
        "| Window | Overlapped words | WER | Deletions |",
        "|---|---|---|---|",
        *window_rows,
        "",
        (
            f"Reference words: {total:,}, of which overlapped: {overlap[True].words:,} "
            f"({overlap[True].words / total:.1%})."
        ),
        "",
        "| Words | Overlapped? | Count | Deletion rate | Substitution rate |",
        "|---|---|---|---|---|",
    ]
    for label, table in (("all", overlap), ("without response words", overlap_content)):
        for flag in (True, False):
            lines.append(
                f"| {label} | {'yes' if flag else 'no'} | {table[flag].words:,} | "
                f"{table[flag].cell()} |"
            )
    in_overlap = sum(c for (_, o), c in deleted_words.items() if o)
    lines += [
        "",
        (
            f"Deletions: {deletions:,}; in overlapped words: {in_overlap:,} "
            f"({in_overlap / deletions:.1%}). In runs of {LONG_RUN}+ words: "
            f"{long_run_words:,} ({long_run_words / deletions:.1%}), over "
            f"{sum(r >= LONG_RUN for r in runs)} runs; longest run {max(runs, default=0)}."
        ),
        "",
        "Most deleted words (overlapped / not):",
    ]
    by_word: Counter[str] = Counter()
    for (word, _), count in deleted_words.items():
        by_word[word] += count
    lines.append(
        ", ".join(
            f"{w} {deleted_words[(w, True)]}/{deleted_words[(w, False)]}"
            for w, _ in by_word.most_common(15)
        )
    )
    lines += [
        "",
        (
            f"Substitutions: {sum(substituted.values())}, as {len(substituted)} distinct "
            "(reference > hypothesis) pairs; the most frequent:"
        ),
        ", ".join(f"{r}>{h} {c}" for (r, h), c in substituted.most_common(20)),
    ]
    lines += ["", f"Worst {worst} stretches of {BIN_S:.0f} s (at least 20 reference words):"]
    ranked = sorted((b for b in all_bins if b.ref_words >= 20), key=lambda b: -b.error_rate)
    for b in ranked[:worst]:
        lines += [
            "",
            (
                f"**{b.window} {b.start_s:.0f}-{b.end_s:.0f}s**: error rate "
                f"{b.error_rate:.2f} ({b.errors} errors / {b.ref_words} words)"
            ),
            f"- REF: {b.marked_reference}",
            f"- HYP: {b.hypothesis}",
        ]
    return "\n".join(lines)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Error analysis of the ASR validation run")
    parser.add_argument("--sizes", nargs="+", choices=WHISPER_SIZES, default=WHISPER_SIZES)
    parser.add_argument("--worst", type=int, default=4, help="worst stretches to print per size")
    args = parser.parse_args()
    for size in args.sizes:
        print(analyze_size(size, args.worst), end="\n\n", flush=True)
