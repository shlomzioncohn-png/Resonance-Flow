"""ASR half of the OCR/ASR benchmark: which local Whisper size (#135).

Run as part of the whole benchmark (python -m benchmarks.ocr_asr_benchmark)
or on its own (python -m benchmarks.asr_benchmark), from teams/mind/.

Three English-only sizes (WHISPER_SIZES) on the two AMI meetings in
eval/datasets/ami-audio.manifest.json (Mix-Headset audio). Three, not two,
for the same reason as the OCR configs: two points can't show whether
accuracy keeps improving with size or flattens out.

Engine: faster-whisper (Whisper on CTranslate2), int8 on CPU -- see
ocr-asr-benchmark.md for why not openai-whisper. Weights are loaded from
models/whisper/<size>/, never downloaded at run time: HuggingFace's CDN is
blocked on this dev network (NetFree, same as PaddleOCR's weights), so they
were placed there by hand -- see "ASR -- setup" in ocr-asr-benchmark.md.

Each size runs in its own fresh process, so its peak RSS is that model
alone, not that model plus whatever the previous size left in the
allocator. Peak RSS is the whole worker process (interpreter + model +
audio decode), sampled every 50ms per meeting from after the model loads --
the number a host needs, rough like the OCR one. CPU time is user+system across all of CTranslate2's threads,
so it can exceed wall time.

Accuracy is a rough word-level diff against AMI's human transcript (the
words/ layer: one file per speaker, interleaved by start time), deliberately
not a real WER: no number/spelling normalization, and on overlapping speech
the reference order is a guess. Those errors hit every size alike, so the
comparison between sizes holds even where the absolute numbers are
pessimistic. A proper WER harness (jiwer + normalization) is the ASR
validation work, not this size decision.
"""

from __future__ import annotations

import concurrent.futures
import difflib
import json
import multiprocessing
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from pydantic import BaseModel

from benchmarks.aligned_metrics import normalize as _normalize
from benchmarks.common import load_checksums, sample_current_process, verify_checksum

_PROJECT_ROOT = Path(__file__).parent.parent
_DATASETS_DIR = _PROJECT_ROOT / "eval" / "datasets"
_MANIFEST = _DATASETS_DIR / "ami-audio.manifest.json"
_CHECKSUMS = _DATASETS_DIR / "ami-audio.sha256"
_MODELS_DIR = _PROJECT_ROOT / "models" / "whisper"
# Raw transcripts, kept for reading the errors by eye -- under gitignored
# local-data/ next to the audio they came from, never committed.
_TRANSCRIPTS_DIR = _PROJECT_ROOT / "local-data" / "ami" / "asr-output"

# The .en variants: MIND is English-only, and the multilingual model of the
# same size costs the same and is typically worse on English.
WHISPER_SIZES = ["tiny.en", "base.en", "small.en"]
COMPUTE_TYPE = "int8"  # CTranslate2's CPU quantization -- what a CPU host would run
BEAM_SIZE = 5  # faster-whisper's default, stated so the runs are reproducible
# CTranslate2's own default, set explicitly so the environment can't change it:
# with cpu_threads left at 0, faster-whisper honors OMP_NUM_THREADS -- and
# constructing a PaddleOCR() (the OCR half of ocr_asr_benchmark, run first)
# sets OMP_NUM_THREADS=1 on the process, which the spawned ASR workers inherit.
# That silently ran Whisper single-threaded at ~4x the latency.
CPU_THREADS = 4

# The size the team picked (#135) -- see "ASR -- final decision" in
# ocr-asr-benchmark.md. The single place that says so: format_asr_conclusion()
# prints it, and WHISPER_MODEL in .env.example must match it.
CHOSEN_WHISPER_SIZE = "base.en"


class Meeting(BaseModel):
    """One AMI meeting from the manifest: its audio and per-speaker words files."""

    id: str
    audio: Path
    duration_s: float
    words_files: list[Path]


class WordDiff(BaseModel):
    """Word-level diff of a transcript against the reference.

    matched / substituted / deleted / inserted come from difflib's alignment
    of the two normalized word sequences. rough_wer is (S+D+I)/N, the WER
    formula over that alignment -- "rough" because difflib's alignment is not
    a guaranteed minimum edit distance and nothing is normalized beyond case
    and punctuation."""

    ref_words: int
    hyp_words: int
    matched: int
    substituted: int
    deleted: int
    inserted: int

    @property
    def rough_wer(self) -> float:
        return (self.substituted + self.deleted + self.inserted) / self.ref_words

    @property
    def word_recall(self) -> float:
        return self.matched / self.ref_words

    @property
    def word_precision(self) -> float:
        return self.matched / self.hyp_words if self.hyp_words else 0.0


class ASRRow(BaseModel):
    """One (size, meeting) measurement."""

    size: str
    meeting: str
    audio_s: float
    duration_s: float
    peak_rss_mb: float
    cpu_time_s: float
    # Segments faster-whisper had to re-decode at a temperature above 0 (its
    # fallback when a beam-search result fails the compression-ratio or
    # log-prob checks). Those re-decodes *sample*, so a run with any of them
    # is not guaranteed to repeat word for word.
    fallback_segments: int
    diff: WordDiff

    @property
    def real_time_factor(self) -> float:
        """Processing time / audio length: 0.1 means 10x faster than real time."""
        return self.duration_s / self.audio_s


class SizeResult(BaseModel):
    """Everything one worker process measured for one Whisper size."""

    size: str
    load_s: float
    rows: list[ASRRow]


class SizeAverages(BaseModel):
    """One size's numbers across both meetings: time and diff counts are pooled
    (summed, then divided), so the longer meeting weighs more -- per minute of
    audio, not per meeting."""

    size: str
    load_s: float
    real_time_factor: float
    duration_s: float
    peak_rss_mb: float
    cpu_time_s: float
    rough_wer: float
    word_recall: float
    word_precision: float


def load_meetings() -> list[Meeting]:
    """Meetings from ami-audio.manifest.json, every file checksum-verified.

    The audio and annotations are gitignored, so a fresh clone won't have
    them: fail with a pointer to the manifest's source page, not a traceback.
    """
    if not _MANIFEST.exists():
        raise RuntimeError(f"{_MANIFEST} not found -- see eval/datasets/README.md")
    manifest = json.loads(_MANIFEST.read_text(encoding="utf-8"))
    checksums = load_checksums(_CHECKSUMS)
    durations = {a["file"]: a["duration_s"] for a in manifest["package"]["audio"]}

    meetings: list[Meeting] = []
    for record in manifest["records"]:
        audio: Path | None = None
        words_files: list[Path] = []
        for rel_path in record["files"]:
            path = _PROJECT_ROOT / rel_path
            if not path.exists():
                raise RuntimeError(
                    f"{rel_path} not found. The ASR benchmark needs the AMI audio and "
                    "annotations downloaded locally -- they're gitignored, not committed. "
                    f"See {_MANIFEST.name} for the URLs (source page: "
                    f"{manifest['source_page']})."
                )
            if rel_path not in checksums:
                raise RuntimeError(f"{rel_path} has no entry in {_CHECKSUMS.name}")
            verify_checksum(path, checksums[rel_path])
            if path.suffix == ".wav":
                audio = path
            else:
                words_files.append(path)
        if audio is None or not words_files:
            raise RuntimeError(
                f"record {record['id']!r} in {_MANIFEST.name} needs one .wav and its words files"
            )
        meetings.append(
            Meeting(
                id=record["id"],
                audio=audio,
                duration_s=durations[audio.name],
                words_files=words_files,
            )
        )
    return meetings


def reference_words(words_files: list[Path]) -> list[str]:
    """AMI's human transcript for one meeting, as normalized words in time order.

    Each speaker's words file holds <w> elements with a starttime; the speakers
    are interleaved by sorting on it. Punctuation tokens (punc="true") and the
    non-word markers (<vocalsound>, <disfmarker>, <gap>) are left out -- Whisper
    doesn't emit them either."""
    timed: list[tuple[float, str]] = []
    for path in words_files:
        for w in ET.parse(path).getroot().iter("w"):
            if w.get("punc") == "true":
                continue
            word = _normalize(w.text or "")
            if word:
                timed.append((float(w.attrib["starttime"]), word))
    timed.sort(key=lambda t: t[0])
    return [word for _, word in timed]


def word_diff(ref: list[str], hyp: list[str]) -> WordDiff:
    """Align two word sequences with difflib and count the edit operations.

    autojunk=False matters: with it on (the default), difflib treats any word
    making up over 1% of a 200+ word sequence -- "the", "and", "uh" -- as junk
    and refuses to align on it, which wrecks a transcript-length alignment.
    A replace block of unequal length counts the shorter side as
    substitutions and the rest as deletions or insertions."""
    matcher = difflib.SequenceMatcher(a=ref, b=hyp, autojunk=False)
    matched = substituted = deleted = inserted = 0
    for op, i1, i2, j1, j2 in matcher.get_opcodes():
        n_ref, n_hyp = i2 - i1, j2 - j1
        if op == "equal":
            matched += n_ref
        elif op == "delete":
            deleted += n_ref
        elif op == "insert":
            inserted += n_hyp
        else:  # replace
            substituted += min(n_ref, n_hyp)
            deleted += max(0, n_ref - n_hyp)
            inserted += max(0, n_hyp - n_ref)
    return WordDiff(
        ref_words=len(ref),
        hyp_words=len(hyp),
        matched=matched,
        substituted=substituted,
        deleted=deleted,
        inserted=inserted,
    )


def _model_dir(size: str) -> Path:
    path = _MODELS_DIR / size
    if not (path / "model.bin").exists():
        raise RuntimeError(
            f"{path / 'model.bin'} not found. Whisper weights are placed by hand (the "
            "HuggingFace CDN is blocked on this network): download config.json, model.bin, "
            f"tokenizer.json and vocabulary.txt from "
            f"https://huggingface.co/Systran/faster-whisper-{size}/tree/main into {path} -- "
            "see 'ASR -- setup' in benchmarks/ocr-asr-benchmark.md."
        )
    return path


def benchmark_size(size: str, meetings: list[Meeting]) -> SizeResult:
    """Load one Whisper size and transcribe every meeting with it.

    Meant to run in a fresh worker process (see run_asr_benchmark). Peak RSS is
    sampled per meeting: a new sampler starts after the model has loaded, from
    the current RSS -- so each row's peak includes the resident model and is
    that meeting's alone, not a running max over the meetings before it
    (review on #652). Same pattern as run_paddle_ocr.
    """
    import psutil  # optional [benchmark] extra
    from faster_whisper import WhisperModel  # optional [benchmark] extra

    proc = psutil.Process()
    load_start = time.perf_counter()
    model = WhisperModel(
        str(_model_dir(size)),
        device="cpu",
        compute_type=COMPUTE_TYPE,
        cpu_threads=CPU_THREADS,
    )
    load_s = time.perf_counter() - load_start

    rows: list[ASRRow] = []
    for meeting in meetings:
        stop_event = threading.Event()
        peak = [proc.memory_info().rss]
        sampler = threading.Thread(target=sample_current_process, args=(stop_event, proc, peak))
        cpu_before = proc.cpu_times()
        sampler.start()
        try:
            start = time.perf_counter()
            segments, _info = model.transcribe(
                str(meeting.audio), language="en", beam_size=BEAM_SIZE
            )
            # transcribe() is lazy: the decoding happens while iterating.
            decoded = list(segments)
            duration = time.perf_counter() - start
        finally:
            stop_event.set()
            sampler.join()
        cpu_after = proc.cpu_times()
        text = " ".join(segment.text.strip() for segment in decoded)

        out = _TRANSCRIPTS_DIR / size / f"{meeting.id}.txt"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(text, encoding="utf-8")

        hyp = [w for w in (_normalize(t) for t in text.split()) if w]
        rows.append(
            ASRRow(
                size=size,
                meeting=meeting.id,
                audio_s=meeting.duration_s,
                duration_s=round(duration, 1),
                peak_rss_mb=round(peak[0] / (1024 * 1024), 1),
                cpu_time_s=round(
                    (cpu_after.user - cpu_before.user) + (cpu_after.system - cpu_before.system),
                    1,
                ),
                fallback_segments=sum(1 for segment in decoded if segment.temperature > 0),
                diff=word_diff(reference_words(meeting.words_files), hyp),
            )
        )
    return SizeResult(size=size, load_s=round(load_s, 1), rows=rows)


def average_size(result: SizeResult) -> SizeAverages:
    rows = result.rows
    audio = sum(r.audio_s for r in rows)
    duration = sum(r.duration_s for r in rows)
    ref = sum(r.diff.ref_words for r in rows)
    hyp = sum(r.diff.hyp_words for r in rows)
    matched = sum(r.diff.matched for r in rows)
    errors = sum(r.diff.substituted + r.diff.deleted + r.diff.inserted for r in rows)
    return SizeAverages(
        size=result.size,
        load_s=result.load_s,
        real_time_factor=duration / audio,
        duration_s=duration / len(rows),
        peak_rss_mb=max(r.peak_rss_mb for r in rows),
        cpu_time_s=sum(r.cpu_time_s for r in rows) / len(rows),
        rough_wer=errors / ref,
        word_recall=matched / ref,
        word_precision=matched / hyp if hyp else 0.0,
    )


def _format_row(row: ASRRow) -> str:
    d = row.diff
    return (
        f"{row.size} {row.meeting}: audio={row.audio_s / 60:.1f}min duration={row.duration_s:.1f}s "
        f"RTF={row.real_time_factor:.3f} peak_rss={row.peak_rss_mb:.0f}MB "
        f"cpu_time={row.cpu_time_s:.1f}s fallback_segments={row.fallback_segments} | rough WER={d.rough_wer:.3f} "
        f"(ref={d.ref_words} hyp={d.hyp_words} matched={d.matched} S={d.substituted} "
        f"D={d.deleted} I={d.inserted}) recall={d.word_recall:.3f} "
        f"precision={d.word_precision:.3f}"
    )


def format_averages(avg: SizeAverages) -> str:
    return (
        f"{avg.size}: RTF={avg.real_time_factor:.3f} (mean {avg.duration_s:.0f}s per "
        f"~30min meeting) peak_rss={avg.peak_rss_mb:.0f}MB cpu_time={avg.cpu_time_s:.0f}s "
        f"load={avg.load_s:.1f}s | rough WER={avg.rough_wer:.3f} "
        f"recall={avg.word_recall:.3f} precision={avg.word_precision:.3f}"
    )


def format_asr_conclusion(averages: dict[str, SizeAverages]) -> str:
    """The ASR conclusion line: the team's pick first, the measured leader second.

    Same shape as the OCR conclusion: the recommendation is CHOSEN_WHISPER_SIZE
    (picked on the accuracy-per-latency trade), not whichever size had the
    lowest rough WER -- small.en is a little more accurate and ~5x slower, and
    the printout must not recommend the opposite of the docs.
    """

    def describe(size: str) -> str:
        avg = averages[size]
        return (
            f"{size} (rough WER={avg.rough_wer:.3f}, precision={avg.word_precision:.3f}, "
            f"RTF={avg.real_time_factor:.3f}, peak_rss={avg.peak_rss_mb:.0f}MB)"
        )

    lowest_wer = min(averages, key=lambda size: averages[size].rough_wer)
    lines = [f"ASR recommendation (the team's decision): Whisper {describe(CHOSEN_WHISPER_SIZE)}"]
    if lowest_wer == CHOSEN_WHISPER_SIZE:
        lines.append("  Also the lowest rough WER measured.")
    else:
        lines.append(
            f"  Lowest rough WER measured (not the pick, see ocr-asr-benchmark.md): "
            f"{describe(lowest_wer)}"
        )
    lines.append(
        "  Rough word diff, n=2 meetings, run-to-run noise ~1 point -- see "
        "ocr-asr-benchmark.md 'ASR -- known risks'."
    )
    return "\n".join(lines)


def run_asr_benchmark() -> dict[str, SizeAverages]:
    """Benchmark every size in WHISPER_SIZES, each in its own fresh process."""
    print("\n--- ASR (Whisper, faster-whisper int8 on CPU) ---")
    meetings = load_meetings()
    for size in WHISPER_SIZES:
        _model_dir(size)  # fail before any long run if a model is missing
    averages: dict[str, SizeAverages] = {}
    spawn = multiprocessing.get_context("spawn")
    for size in WHISPER_SIZES:
        print(f"\nTranscribing with {size}...", flush=True)
        with concurrent.futures.ProcessPoolExecutor(max_workers=1, mp_context=spawn) as pool:
            result = pool.submit(benchmark_size, size, meetings).result()
        for row in result.rows:
            print(_format_row(row))
        averages[size] = average_size(result)

    print("\n--- ASR per-size results (both meetings pooled) ---")
    for avg in averages.values():
        print(format_averages(avg))
    return averages


if __name__ == "__main__":
    asr_averages = run_asr_benchmark()
    print("\n--- Conclusion ---")
    print(format_asr_conclusion(asr_averages))
