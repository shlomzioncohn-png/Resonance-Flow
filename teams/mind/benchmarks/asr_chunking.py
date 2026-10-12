"""Does transcribing in chunks hurt ASR accuracy? (#478)

Run from teams/mind/: python -m benchmarks.asr_chunking  (~20-30 min on a laptop CPU)

Why chunk at all: faster-whisper computes its features over the whole input at
once, so transcribe()'s peak memory grew ~0.9 MiB per second of audio (~790 MiB
for the 600 s cap), over the 512Mi the shared Helm chart gives MIND. Chunks bound
it by the chunk length instead. The risk is accuracy at the cuts, so this compares,
on the first MAX_DURATION_S seconds of the two #135 AMI meetings (what production
transcribes under its cap), against AMI's human transcript:

  full        one model.transcribe() over the whole capped audio -- the old way
  fixed60     60 s chunks cut at fixed times, wherever a word happens to be
  production  asr.transcribe() itself: chunks of at most CHUNK_S cut in silences

Same rough WER as asr_benchmark.py (word_diff, normalize; see its docstring for
why "rough"), Whisper base.en int8 on CPU as asr.py runs it. Each (variant,
meeting, run) runs in its own fresh process, so its peak RSS is its own. Every
configuration runs RUNS times, since the same model on the same audio does not
always give the same transcript ("ASR -- known risks" in ocr-asr-benchmark.md).
Results: "ASR -- chunking" in ocr-asr-benchmark.md.
"""

from __future__ import annotations

import concurrent.futures
import multiprocessing
import threading
import time
import xml.etree.ElementTree as ET

from pydantic import BaseModel

from benchmarks.aligned_metrics import normalize as _normalize
from benchmarks.asr_benchmark import BEAM_SIZE, Meeting, load_meetings, word_diff
from benchmarks.common import sample_current_process

VARIANTS = ["full", "fixed60", "production"]
RUNS = 2
_FIXED_CHUNK_S = 60


class ChunkingRow(BaseModel):
    variant: str
    meeting: str
    run: int
    rough_wer: float
    word_recall: float
    word_precision: float
    peak_rss_mb: float
    duration_s: float


def capped_reference(meeting: Meeting, cap_s: float) -> list[str]:
    """AMI's human words that start inside the first `cap_s` seconds, in time order.

    reference_words() in asr_benchmark.py, limited to the audio production reads."""
    timed: list[tuple[float, str]] = []
    for path in meeting.words_files:
        for w in ET.parse(path).getroot().iter("w"):
            start = float(w.attrib["starttime"])
            if w.get("punc") == "true" or start >= cap_s:
                continue
            word = _normalize(w.text or "")
            if word:
                timed.append((start, word))
    timed.sort(key=lambda t: t[0])
    return [word for _, word in timed]


def run_one(variant: str, meeting: Meeting, run: int) -> ChunkingRow:
    """Transcribe one meeting one way. Meant to run in a fresh worker process."""
    import psutil  # optional [benchmark] extra

    from mind.multimodal import asr

    content = meeting.audio.read_bytes()
    _, model = asr._load_model()

    # The window covers what one call costs in production: decoding the bytes and
    # transcribing them, for every variant alike. (A first version decoded before
    # the window and then let transcribe() decode again inside it, so "production"
    # held the audio twice and paid for a decode the others did not.)
    proc = psutil.Process()
    stop_event = threading.Event()
    peak = [proc.memory_info().rss]
    sampler = threading.Thread(target=sample_current_process, args=(stop_event, proc, peak))
    sampler.start()
    start = time.perf_counter()
    texts: list[str] = []
    try:
        if variant == "production":
            result = asr.transcribe(content)
            if not result.success:
                raise RuntimeError(f"transcribe() failed: {result.reason}: {result.error}")
            texts = [s.text for s in result.segments]
        else:
            audio, _ = asr._decode(content, asr.MAX_DURATION_S)
            step = len(audio) if variant == "full" else _FIXED_CHUNK_S * asr.SAMPLE_RATE
            for i in range(0, len(audio), step):
                found, _ = model.transcribe(
                    audio[i : i + step], language="en", beam_size=BEAM_SIZE, vad_filter=True
                )
                texts += [seg.text for seg in found]
    finally:
        stop_event.set()
        sampler.join()
    duration_s = time.perf_counter() - start

    hyp = [w for w in (_normalize(t) for text in texts for t in text.split()) if w]
    diff = word_diff(capped_reference(meeting, asr.MAX_DURATION_S), hyp)
    return ChunkingRow(
        variant=variant,
        meeting=meeting.id,
        run=run,
        rough_wer=round(diff.rough_wer, 4),
        word_recall=round(diff.word_recall, 4),
        word_precision=round(diff.word_precision, 4),
        peak_rss_mb=round(peak[0] / 2**20),
        duration_s=round(duration_s, 1),
    )


def run_chunking_benchmark() -> list[ChunkingRow]:
    meetings = load_meetings()
    spawn = multiprocessing.get_context("spawn")
    rows: list[ChunkingRow] = []
    for run in range(1, RUNS + 1):
        for meeting in meetings:
            for variant in VARIANTS:
                with concurrent.futures.ProcessPoolExecutor(
                    max_workers=1, mp_context=spawn
                ) as pool:
                    row = pool.submit(run_one, variant, meeting, run).result()
                print(
                    f"{row.variant:10} {row.meeting:8} run {row.run}  rough WER "
                    f"{row.rough_wer:.3f}  recall {row.word_recall:.3f}  precision "
                    f"{row.word_precision:.3f}  peak {row.peak_rss_mb:.0f} MiB  "
                    f"{row.duration_s:.0f}s",
                    flush=True,
                )
                rows.append(row)
    print("\n--- per variant, both meetings and all runs pooled ---")
    for variant in VARIANTS:
        mine = [r for r in rows if r.variant == variant]
        n = len(mine)
        print(
            f"{variant:10} rough WER {sum(r.rough_wer for r in mine) / n:.3f}  "
            f"precision {sum(r.word_precision for r in mine) / n:.3f}  "
            f"peak {min(r.peak_rss_mb for r in mine):.0f}-"
            f"{max(r.peak_rss_mb for r in mine):.0f} MiB"
        )
    return rows


if __name__ == "__main__":
    run_chunking_benchmark()
