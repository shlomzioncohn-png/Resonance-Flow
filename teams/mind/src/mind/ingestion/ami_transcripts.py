"""AMI's human meeting transcripts as readable text, for the extraction eval (#784).

One line per speaker turn, `<speaker>: <text>`, built from the manual
annotations' words/ layer (one `<meeting>.<letter>.words.xml` per speaker):

- Every speaker's words are merged by `starttime`, as reference_words() in
  benchmarks/asr_benchmark.py does.
- Punctuation is kept: a `punc="true"` token attaches to the same speaker's
  previous word, with no space. It is attached before the speakers are merged
  -- its starttime equals the end of that word, so another speaker's word can
  share it and would otherwise land in between.
- The non-word elements (`<vocalsound>`, `<disfmarker>`, `<gap>`) are dropped.
- A new turn starts whenever the speaker changes. The speaker is named by role
  ("Project Manager") when corpusResources/meetings.xml gives one -- AMI's gold
  action items name people that way -- and by letter ("A") when it doesn't
  (the non-scenario meetings, e.g. IB4003).

This is AMI's human transcript, not ASR output, so the extraction eval
measures extraction alone; ASR is measured on its own in #480.

Files are read from eval/datasets/ami.manifest.json and checksum-verified
against ami.sha256. The corpus itself is gitignored (local-data/).
"""

from __future__ import annotations

import hashlib
import json
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

from pydantic import BaseModel, ConfigDict

# teams/mind/ -- manifest paths are relative to here.
_PROJECT_ROOT = Path(__file__).resolve().parents[3]
MANIFEST_PATH = _PROJECT_ROOT / "eval" / "datasets" / "ami.manifest.json"
SHA256_PATH = _PROJECT_ROOT / "eval" / "datasets" / "ami.sha256"

# meetings.xml's role codes, spelled the way the abssumm action items spell them.
ROLE_NAMES = {
    "PM": "Project Manager",
    "ID": "Industrial Designer",
    "UI": "User Interface Designer",
    "ME": "Marketing Expert",
}

_WORDS_SUFFIX = ".words.xml"
_ABSSUMM_SUFFIX = ".abssumm.xml"


class AMITranscript(BaseModel):
    """One meeting's transcript. meeting_id is the abssumm filename stem, same
    case (`IS1008b`): the extraction eval joins on it."""

    model_config = ConfigDict(frozen=True)

    meeting_id: str
    text: str


def load_transcript(
    meeting_id: str,
    manifest_path: Path = MANIFEST_PATH,
    sha256_path: Path = SHA256_PATH,
    root: Path = _PROJECT_ROOT,
) -> AMITranscript:
    """The transcript of one meeting listed in the manifest."""
    manifest = _read_manifest(manifest_path)
    for record in manifest["records"]:
        if record["id"] == meeting_id:
            return _transcript_for_record(record, manifest, manifest_path, sha256_path, root)
    raise KeyError(f"meeting {meeting_id!r} is not in {manifest_path.name}")


def load_all_transcripts(
    manifest_path: Path = MANIFEST_PATH,
    sha256_path: Path = SHA256_PATH,
    root: Path = _PROJECT_ROOT,
) -> list[AMITranscript]:
    """The transcript of every meeting in the manifest, in manifest order."""
    manifest = _read_manifest(manifest_path)
    return [
        _transcript_for_record(record, manifest, manifest_path, sha256_path, root)
        for record in manifest["records"]
    ]


def speaker_labels(meetings_xml: Path, meeting_id: str) -> dict[str, str]:
    """Speaker letter -> label for one meeting: the role's name when
    meetings.xml gives a known role, else the letter itself."""
    labels: dict[str, str] = {}
    for meeting in ET.parse(meetings_xml).getroot().iter("meeting"):
        if meeting.get("observation") != meeting_id:
            continue
        for speaker in meeting.iter("speaker"):
            letter = speaker.attrib["nxt_agent"]
            labels[letter] = ROLE_NAMES.get(speaker.get("role", ""), letter)
    return labels


def format_transcript(words_files: list[Path], labels: dict[str, str]) -> str:
    """The merged words of every speaker, one `<label>: <text>` line per turn.
    A speaker missing from labels is named by letter."""
    timed: list[tuple[float, str, str]] = []
    for path in sorted(words_files, key=lambda p: p.name):
        letter = _speaker_letter(path)
        timed.extend((start, letter, word) for start, word in _speaker_words(path))
    timed.sort(key=lambda t: t[0])  # stable: same-speaker order is kept on ties

    turns: list[tuple[str, list[str]]] = []
    for _, letter, word in timed:
        if turns and turns[-1][0] == letter:
            turns[-1][1].append(word)
        else:
            turns.append((letter, [word]))
    return "\n".join(f"{labels.get(letter, letter)}: {' '.join(words)}" for letter, words in turns)


def _speaker_words(path: Path) -> list[tuple[float, str]]:
    """One speaker's words with their start times, punctuation attached."""
    words: list[tuple[float, str]] = []
    for w in ET.parse(path).getroot().iter("w"):
        text = (w.text or "").strip()
        if not text:
            continue
        if w.get("punc") == "true":
            if words:
                start, word = words[-1]
                words[-1] = (start, word + text)
            continue
        words.append((float(w.attrib["starttime"]), text))
    return words


def _speaker_letter(words_file: Path) -> str:
    # <meeting>.<letter>.words.xml
    return words_file.name.removesuffix(_WORDS_SUFFIX).rsplit(".", 1)[1]


def _read_manifest(manifest_path: Path) -> dict[str, Any]:
    if not manifest_path.exists():
        raise RuntimeError(f"{manifest_path} not found -- see eval/datasets/README.md")
    manifest: dict[str, Any] = json.loads(manifest_path.read_text(encoding="utf-8"))
    return manifest


def _transcript_for_record(
    record: dict[str, Any],
    manifest: dict[str, Any],
    manifest_path: Path,
    sha256_path: Path,
    root: Path,
) -> AMITranscript:
    meeting_id: str = record["id"]
    checksums = _load_checksums(sha256_path)
    words_files: list[Path] = []
    meetings_xml: Path | None = None
    abssumm_stems: list[str] = []
    for rel_path in record["files"]:
        path = root / rel_path
        if not path.exists():
            raise RuntimeError(
                f"{rel_path} not found. The AMI annotations are gitignored, not committed -- "
                f"download {manifest['package']['file']} (see {manifest_path.name}, source "
                f"page: {manifest['source_page']}) and unzip it under local-data/ami/."
            )
        if rel_path not in checksums:
            raise RuntimeError(f"{rel_path} has no entry in {sha256_path.name}")
        _verify_checksum(path, checksums[rel_path])
        if path.name.endswith(_WORDS_SUFFIX):
            words_files.append(path)
        elif path.name.endswith(_ABSSUMM_SUFFIX):
            abssumm_stems.append(path.name.removesuffix(_ABSSUMM_SUFFIX))
        elif path.name == "meetings.xml":
            meetings_xml = path

    # The eval joins on the abssumm filename stem; a record whose id differs
    # from it, even in case only, would silently break that join.
    if abssumm_stems != [meeting_id]:
        raise RuntimeError(
            f"record {meeting_id!r} in {manifest_path.name} must list exactly one "
            f"{meeting_id}{_ABSSUMM_SUFFIX}, found {abssumm_stems}"
        )
    for path in words_files:
        if not path.name.startswith(f"{meeting_id}."):
            raise RuntimeError(
                f"record {meeting_id!r} in {manifest_path.name} lists {path.name}, "
                "another meeting's words file"
            )
    if meetings_xml is None or not words_files:
        raise RuntimeError(
            f"record {meeting_id!r} in {manifest_path.name} needs meetings.xml and its words files"
        )
    labels = speaker_labels(meetings_xml, meeting_id)
    return AMITranscript(meeting_id=meeting_id, text=format_transcript(words_files, labels))


# Same format and behaviour as load_checksums()/verify_checksum() in
# benchmarks/common.py, which src/ cannot import.
def _load_checksums(sha256_path: Path) -> dict[str, str]:
    """relative path -> expected sha256, from a `sha256sum`-format file."""
    checksums: dict[str, str] = {}
    for line in sha256_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            digest, rel_path = line.split(None, 1)
            checksums[rel_path.strip()] = digest
    return checksums


def _verify_checksum(path: Path, expected_sha256: str) -> None:
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected_sha256:
        raise RuntimeError(
            f"checksum mismatch for {path}: expected {expected_sha256}, got {actual} "
            "-- the local file doesn't match eval/datasets/ami.sha256"
        )
