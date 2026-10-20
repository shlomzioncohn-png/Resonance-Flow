"""Build the demo's two binary artifacts from their text sources.

    python -m demo_flow.build_artifacts            # both
    python -m demo_flow.build_artifacts --only form

- artifacts/supplier_form.jpg, from scenario/supplier_form.txt: rendered with
  Pillow at 200 DPI (US Letter) and made to look scanned (a slight skew, blur
  and speckle, saved as JPEG). Seeded, so a rebuild gives the same image.
- artifacts/meeting.m4a, from scenario/meeting_script.txt: read by the Windows
  speech synthesizer (System.Speech, offline -- the same way
  tests/fixtures/asr/short_meeting_note.wav was made), then encoded to AAC with
  PyAV. **Windows only**: elsewhere, use the file someone built, or record the
  script yourself (any format FFmpeg reads; keep the .m4a name or adjust
  common.MEETING_AUDIO). Only one English voice ships with System.Speech
  (Zira), so speakers differ by speaking rate only; ASR has no diarization
  anyway, which is why the script names the owner of every commitment.
"""

import argparse
import base64
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

import av
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

from demo_flow import common

# US Letter at 200 DPI, the page size multimodal/README.md measures OCR on.
_PAGE_SIZE = (1700, 2200)
_MARGIN = 150
_SEED = 359

# Speaking rate per speaker (System.Speech: -10..10), and the pause after each line.
_SPEAKER_RATE = {"Lior": 0, "Dana": 1, "Omer": -1}
_PAUSE_MS = 700

# Passed as -EncodedCommand, so no script file is run and the execution policy
# does not apply. Paths come in through the environment.
_TTS_POWERSHELL = r"""
$ProgressPreference = "SilentlyContinue"
Add-Type -AssemblyName System.Speech
$lines = Get-Content -Raw -Encoding UTF8 $env:DEMO_TTS_LINES | ConvertFrom-Json
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
$synth.SetOutputToWaveFile($env:DEMO_TTS_OUT, $format)
foreach ($line in $lines) {
    $synth.Rate = $line.rate
    $prompt = New-Object System.Speech.Synthesis.PromptBuilder
    $prompt.AppendText($line.text)
    $prompt.AppendBreak([TimeSpan]::FromMilliseconds($line.pause_ms))
    $synth.Speak($prompt)
}
$synth.Dispose()
"""


def build_form(out: Path) -> None:
    page = Image.new("L", _PAGE_SIZE, color=255)
    draw = ImageDraw.Draw(page)
    # (point size, font): the first two lines are the form's title.
    title = (52, ImageFont.load_default(size=52))
    body = (38, ImageFont.load_default(size=38))

    y = _MARGIN
    lines = common.FORM_TEXT.read_text(encoding="utf-8").splitlines()
    for index, line in enumerate(lines):
        size, font = title if index < 2 else body
        if line.strip():
            draw.text((_MARGIN, y), line, fill=20, font=font)
        y += int(size * 1.7)

    # A scanner's artifacts: a slight skew, a little blur, faint speckle.
    rng = np.random.default_rng(_SEED)
    scanned = page.rotate(0.4, resample=Image.Resampling.BICUBIC, fillcolor=255)
    scanned = scanned.filter(ImageFilter.GaussianBlur(radius=0.7))
    pixels = np.asarray(scanned, dtype=np.int16)
    pixels = pixels - 8 + rng.normal(0, 6, size=pixels.shape).astype(np.int16)
    speckle = rng.random(pixels.shape) < 0.0008
    pixels[speckle] = 60
    result = Image.fromarray(np.clip(pixels, 0, 255).astype(np.uint8), mode="L")

    out.parent.mkdir(parents=True, exist_ok=True)
    result.save(out, format="JPEG", quality=80, dpi=(200, 200))


def _synthesize_wav(wav_out: Path) -> None:
    if sys.platform != "win32":
        raise SystemExit(
            "meeting audio is synthesized with the Windows speech synthesizer; on this OS, "
            "use an existing artifacts/meeting.m4a or record scenario/meeting_script.txt yourself"
        )
    lines = [
        {"text": text, "rate": _SPEAKER_RATE.get(speaker, 0), "pause_ms": _PAUSE_MS}
        for speaker, text in common.read_meeting_script()
    ]
    with tempfile.TemporaryDirectory() as tmp:
        lines_file = Path(tmp) / "lines.json"
        lines_file.write_text(json.dumps(lines), encoding="utf-8")
        env = {**os.environ, "DEMO_TTS_LINES": str(lines_file), "DEMO_TTS_OUT": str(wav_out)}
        encoded = base64.b64encode(_TTS_POWERSHELL.encode("utf-16-le")).decode("ascii")
        subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded],
            env=env,
            check=True,
        )
    if not wav_out.exists() or wav_out.stat().st_size == 0:
        raise SystemExit(f"the speech synthesizer wrote no audio to {wav_out}")


def _encode_m4a(wav_in: Path, out: Path) -> None:
    resampler = av.AudioResampler(format="fltp", layout="mono", rate=16000)
    with av.open(str(wav_in)) as source, av.open(str(out), "w", format="ipod") as sink:
        stream = sink.add_stream("aac", rate=16000)
        stream.bit_rate = 48_000
        stream.layout = "mono"
        for frame in source.decode(audio=0):
            for resampled in resampler.resample(frame):
                sink.mux(stream.encode(resampled))
        for resampled in resampler.resample(None):
            sink.mux(stream.encode(resampled))
        sink.mux(stream.encode(None))


def build_meeting(out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        wav = Path(tmp) / "meeting.wav"
        _synthesize_wav(wav)
        _encode_m4a(wav, out)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--only", choices=["form", "meeting"])
    args = parser.parse_args(argv)

    if args.only in (None, "form"):
        build_form(common.FORM_IMAGE)
        print(f"built {common.FORM_IMAGE} ({common.FORM_IMAGE.stat().st_size:,} bytes)")
    if args.only in (None, "meeting"):
        build_meeting(common.MEETING_AUDIO)
        print(f"built {common.MEETING_AUDIO} ({common.MEETING_AUDIO.stat().st_size:,} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
