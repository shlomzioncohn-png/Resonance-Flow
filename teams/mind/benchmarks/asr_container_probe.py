"""Peak memory and CPU of one MIND process holding both the embedding model and Whisper (#478).

Meant to run **inside a Linux container** built like teams/mind's Dockerfile -- the
numbers are the kernel's own, from /proc/self/status, which a container memory
limit is enforced against (Windows RSS is not). After each stage it records VmHWM
(the peak resident set) and VmRSS (the current one); around transcribe() it also
records wall time, CPU time and the longest gap between two segments -- how far a
call can overrun TRANSCRIBE_TIMEOUT_S, which is only checked between segments.

The embedding model is a STAND-IN: bge-small-en-v1.5's own config.json (BERT, 12
layers, hidden 384) built with random weights -- the same tensors, so the same
memory, with meaningless values -- plus the sentence-transformers import the service
pays. Its real weights were not on the measuring machine (the dev network's NetFree
filter blocks Hugging Face's CDN), only its config in the Hugging Face cache.

Usage, from teams/mind/, with the image built and models/ + the HF cache mounted:

    docker run --rm [--cpus=0.5] [-e MALLOC_ARENA_MAX=2] \\
      -v "$PWD/models:/app/models:ro" -v "$HOME/.cache/huggingface:/home/mind/.cache/huggingface:ro" \\
      -v "$PWD/benchmarks:/probe:ro" -v "<dir with the audio>:/audio:ro" \\
      -e MODEL_CACHE_DIR=/app/models -e HF_HUB_OFFLINE=1 \\
      <image> python /probe/asr_container_probe.py /audio/<file>

Results: "ASR — memory and CPU in a container" in ocr-asr-benchmark.md.
"""

import glob
import json
import os
import sys
import time
from itertools import pairwise
from pathlib import Path


def status() -> dict[str, float]:
    out = {}
    with open("/proc/self/status") as f:
        for line in f:
            key, _, value = line.partition(":")
            if key in ("VmHWM", "VmRSS"):
                out[key] = int(value.split()[0]) / 1024  # kB -> MiB
    return out


def main() -> None:
    stages = {"start": status()}

    import sentence_transformers  # noqa: F401 -- the import the service pays
    import torch
    from transformers import BertConfig, BertModel

    config_path = glob.glob(
        "/home/mind/.cache/huggingface/hub/models--BAAI--bge-small-en-v1.5/snapshots/*/config.json"
    )[0]
    bert = BertModel(BertConfig.from_json_file(config_path)).eval()

    def embed() -> None:  # stands in for mind.embeddings.embed(): one 64-token forward pass
        with torch.no_grad():
            bert(input_ids=torch.randint(0, 30000, (1, 64)))

    embed()
    stages["embedding stand-in loaded + one embed"] = status()

    from mind.multimodal import asr

    assert asr.warm_up(), "ASR not ready"
    stages["whisper loaded (warm_up)"] = status()

    content = Path(sys.argv[1]).read_bytes()
    stages["audio file read"] = status()

    yielded: list[float] = []
    real_from_whisper = asr.TranscriptSegment.from_whisper  # bound to the class

    def timed_from_whisper(seg, *, offset_s=0.0):
        yielded.append(time.perf_counter())
        return real_from_whisper(seg, offset_s=offset_s)

    asr.TranscriptSegment.from_whisper = staticmethod(timed_from_whisper)  # type: ignore[method-assign,assignment]

    t, cpu0 = time.perf_counter(), time.process_time()
    result = asr.transcribe(content)
    wall, cpu = time.perf_counter() - t, time.process_time() - cpu0
    marks = [t, *yielded]
    gaps = sorted((b - a for a, b in pairwise(marks)), reverse=True)
    stages[f"after transcribe ({asr.MAX_DURATION_S} s cap)"] = status()

    embed()
    stages["embed after ASR"] = status()

    print(
        json.dumps(
            {
                "MALLOC_ARENA_MAX": os.environ.get("MALLOC_ARENA_MAX", "unset"),
                "transcribe_s": round(wall, 1),
                "transcribe_cpu_s": round(cpu, 1),
                "longest_gaps_between_segments_s": [round(g, 1) for g in gaps[:3]],
                "success": result.success,
                "truncated": result.truncated,
                "duration_s": result.duration_s,
                "segments": len(result.segments),
                "stages_mib": {k: {m: round(v) for m, v in s.items()} for k, s in stages.items()},
            },
            indent=1,
        )
    )


if __name__ == "__main__":
    main()
