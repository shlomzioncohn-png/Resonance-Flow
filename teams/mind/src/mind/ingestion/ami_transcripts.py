"""AMI's human meeting transcripts as readable text, for the extraction eval (#784).

One line per speaker turn, `<speaker>: <text>`, built from two layers of the
manual annotations, one file of each per speaker:

- words/<meeting>.<letter>.words.xml -- the words. Punctuation is kept: a
  `punc="true"` token attaches to the speaker's previous word, with no space.
  The non-word elements (`<vocalsound>`, `<disfmarker>`, `<gap>`) are dropped.
- segments/<meeting>.<letter>.segments.xml -- the transcriber's segments: each
  `<segment>` is one coherent stretch of a speaker's speech, a range of words
  in that speaker's words file, with a `transcriber_start` time.

Segments, not single words, are what get sorted by time across speakers.
Merging word by word cut a sentence in half wherever another speaker's "Yeah"
fell inside it; a segment keeps it whole. Consecutive segments of the same
speaker are one turn. Every word of the words layer lands in exactly one
segment, so no words are lost at segment boundaries.

The speaker is named by role ("Project Manager") when
corpusResources/meetings.xml gives one -- AMI's gold action items name people
that way -- and by letter ("A") when it doesn't (the non-scenario meetings,
e.g. IB4003).

This is AMI's human transcript, not ASR output, so the extraction eval
measures extraction alone; ASR is measured on its own in #480.

Files are read from eval/datasets/ami.manifest.json and checksum-verified
against ami.sha256. The corpus itself is gitignored (local-data/).
"""

from __future__ import annotations

import hashlib
import re
import xml.etree.ElementTree as ET
from pathlib import Path

from pydantic import BaseModel, ConfigDict, ValidationError

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
_SEGMENTS_SUFFIX = ".segments.xml"
_ABSSUMM_SUFFIX = ".abssumm.xml"
_NITE_ID = "{http://nite.sourceforge.net/}id"
_NITE_CHILD = "{http://nite.sourceforge.net/}child"
# A segment's words: `<words file>#id(<first>)..id(<last>)`, or `#id(<word>)`
# for a one-word segment.
_HREF = re.compile(r"(?P<file>[^#]+)#id\((?P<first>[^)]+)\)(?:\.\.id\((?P<last>[^)]+)\))?")


class AMITranscript(BaseModel):
    """One meeting's transcript. meeting_id is the abssumm filename stem, same
    case (`IS1008b`): the extraction eval joins on it."""

    model_config = ConfigDict(frozen=True)

    meeting_id: str
    text: str


class _ManifestRecord(BaseModel):
    id: str
    files: list[str]


class _ManifestPackage(BaseModel):
    file: str


class _Manifest(BaseModel):
    """The parts of ami.manifest.json the loader reads; other keys are ignored."""

    package: _ManifestPackage
    source_page: str
    records: list[_ManifestRecord]


def load_transcript(
    meeting_id: str,
    manifest_path: Path = MANIFEST_PATH,
    sha256_path: Path = SHA256_PATH,
    root: Path = _PROJECT_ROOT,
) -> AMITranscript:
    """The transcript of one meeting listed in the manifest.

    Raises:
        KeyError: meeting_id is not a record in the manifest (ids are
            case-sensitive: `IS1008b`, not `is1008b`).
        RuntimeError: the manifest or ami.sha256 is missing, or the manifest
            is malformed; a file of the meeting's record is not downloaded,
            has no checksum or doesn't match it; or the record is incomplete
            or inconsistent (see load_all_transcripts).
    """
    manifest = _read_manifest(manifest_path)
    for record in manifest.records:
        if record.id == meeting_id:
            return _transcript_for_record(record, manifest, manifest_path, sha256_path, root)
    raise KeyError(f"meeting {meeting_id!r} is not in {manifest_path.name}")


def load_all_transcripts(
    manifest_path: Path = MANIFEST_PATH,
    sha256_path: Path = SHA256_PATH,
    root: Path = _PROJECT_ROOT,
) -> list[AMITranscript]:
    """The transcript of every meeting in the manifest, in manifest order.

    Raises:
        RuntimeError: the manifest or ami.sha256 is missing, or the manifest
            is malformed; a file listed in the manifest is not downloaded, has
            no checksum or doesn't match it; or a record is incomplete or
            inconsistent: no meetings.xml, no words and segments file for some
            speaker, an id that isn't its abssumm filename stem, another
            meeting's file, or a segment pointing at words not in its
            speaker's words file.
    """
    manifest = _read_manifest(manifest_path)
    return [
        _transcript_for_record(record, manifest, manifest_path, sha256_path, root)
        for record in manifest.records
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


def format_transcript(
    words_files: list[Path], segments_files: list[Path], labels: dict[str, str]
) -> str:
    """Every speaker's segments sorted by start time, one `<label>: <text>`
    line per turn. Each speaker needs exactly one words and one segments file;
    a speaker missing from labels is named by letter."""
    words_by_letter = {_speaker_letter(p, _WORDS_SUFFIX): p for p in words_files}
    segments_by_letter = {_speaker_letter(p, _SEGMENTS_SUFFIX): p for p in segments_files}
    if (
        len(words_by_letter) != len(words_files)
        or len(segments_by_letter) != len(segments_files)
        or words_by_letter.keys() != segments_by_letter.keys()
    ):
        raise RuntimeError(
            "need exactly one words and one segments file per speaker, got words for "
            f"{sorted(words_by_letter)} and segments for {sorted(segments_by_letter)}"
        )

    timed: list[tuple[float, str, list[str]]] = []
    for letter in sorted(words_by_letter):
        timed.extend(
            (start, letter, words)
            for start, words in _speaker_segments(
                words_by_letter[letter], segments_by_letter[letter]
            )
        )
    timed.sort(key=lambda t: t[0])  # stable: same-speaker order is kept on ties

    turns: list[tuple[str, list[str]]] = []
    for _, letter, words in timed:
        if turns and turns[-1][0] == letter:
            turns[-1][1].extend(words)
        else:
            turns.append((letter, list(words)))
    return "\n".join(f"{labels.get(letter, letter)}: {' '.join(words)}" for letter, words in turns)


def _speaker_segments(words_file: Path, segments_file: Path) -> list[tuple[float, list[str]]]:
    """One speaker's segments as (transcriber_start, words); segments holding
    only non-word elements are left out."""
    elements = list(ET.parse(words_file).getroot())
    position = {element.get(_NITE_ID): i for i, element in enumerate(elements)}

    # Punctuation is attached over the whole words file, before it is cut into
    # segments, so a "." that opens a segment still ends the previous one.
    words: list[tuple[int, str]] = []  # (position in the words file, word)
    for i, element in enumerate(elements):
        text = (element.text or "").strip()
        if element.tag != "w" or not text:
            continue
        if element.get("punc") == "true":
            # Punctuation with no word before it is dropped on purpose: there is
            # nothing to attach it to (doesn't occur in the manifest's meetings).
            if words:
                words[-1] = (words[-1][0], words[-1][1] + text)
            continue
        words.append((i, text))

    segments: list[tuple[float, list[str]]] = []
    for segment in ET.parse(segments_file).getroot().iter("segment"):
        first, last = _segment_range(segment, words_file, position)
        segment_words = [word for i, word in words if first <= i <= last]
        if segment_words:
            segments.append((float(segment.attrib["transcriber_start"]), segment_words))
    return segments


def _segment_range(
    segment: ET.Element, words_file: Path, position: dict[str | None, int]
) -> tuple[int, int]:
    """Positions of a segment's first and last element in its words file."""
    child = segment.find(_NITE_CHILD)
    match = _HREF.fullmatch(child.get("href", "")) if child is not None else None
    if match is None or match["file"] != words_file.name:
        raise RuntimeError(
            f"segment {segment.get(_NITE_ID)!r} does not point into {words_file.name}"
        )
    first, last = match["first"], match["last"] or match["first"]
    if first not in position or last not in position:
        raise RuntimeError(
            f"segment {segment.get(_NITE_ID)!r} points at ids not in {words_file.name}"
        )
    return position[first], position[last]


def _speaker_letter(path: Path, suffix: str) -> str:
    # <meeting>.<letter><suffix>
    return path.name.removesuffix(suffix).rsplit(".", 1)[1]


def _read_manifest(manifest_path: Path) -> _Manifest:
    if not manifest_path.exists():
        raise RuntimeError(f"{manifest_path} not found -- see eval/datasets/README.md")
    try:
        return _Manifest.model_validate_json(manifest_path.read_text(encoding="utf-8"))
    except ValidationError as e:
        raise RuntimeError(f"{manifest_path.name} is malformed: {e}") from e


def _transcript_for_record(
    record: _ManifestRecord,
    manifest: _Manifest,
    manifest_path: Path,
    sha256_path: Path,
    root: Path,
) -> AMITranscript:
    meeting_id = record.id
    checksums = _load_checksums(sha256_path)
    words_files: list[Path] = []
    segments_files: list[Path] = []
    meetings_xml: Path | None = None
    abssumm_stems: list[str] = []
    for rel_path in record.files:
        path = root / rel_path
        if not path.exists():
            raise RuntimeError(
                f"{rel_path} not found. The AMI annotations are gitignored, not committed -- "
                f"download {manifest.package.file} (see {manifest_path.name}, source "
                f"page: {manifest.source_page}) and unzip it under local-data/ami/."
            )
        if rel_path not in checksums:
            raise RuntimeError(f"{rel_path} has no entry in {sha256_path.name}")
        _verify_checksum(path, checksums[rel_path])
        if path.name.endswith(_WORDS_SUFFIX):
            words_files.append(path)
        elif path.name.endswith(_SEGMENTS_SUFFIX):
            segments_files.append(path)
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
    for path in words_files + segments_files:
        if not path.name.startswith(f"{meeting_id}."):
            raise RuntimeError(
                f"record {meeting_id!r} in {manifest_path.name} lists {path.name}, "
                "another meeting's file"
            )
    if meetings_xml is None or not words_files or not segments_files:
        raise RuntimeError(
            f"record {meeting_id!r} in {manifest_path.name} needs meetings.xml and its "
            "words and segments files"
        )
    labels = speaker_labels(meetings_xml, meeting_id)
    try:
        text = format_transcript(words_files, segments_files, labels)
    except RuntimeError as e:
        raise RuntimeError(f"record {meeting_id!r} in {manifest_path.name}: {e}") from e
    return AMITranscript(meeting_id=meeting_id, text=text)


# Same format and behaviour as load_checksums()/verify_checksum() in
# benchmarks/common.py, which src/ cannot import.
def _load_checksums(sha256_path: Path) -> dict[str, str]:
    """relative path -> expected sha256, from a `sha256sum`-format file."""
    if not sha256_path.exists():
        raise RuntimeError(f"{sha256_path} not found -- see eval/datasets/README.md")
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
