"""Tests for mind.ingestion.ami_transcripts -- AMI's human transcript as turns (#784)."""

import hashlib
import json
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest

from mind.ingestion.ami_transcripts import (
    MANIFEST_PATH,
    AMITranscript,
    format_transcript,
    load_all_transcripts,
    load_gold_actions,
    load_transcript,
    speaker_labels,
)

MEETING = "TS3000a"

# A has a role code that isn't known. A's "Yeah" (2.0) falls inside B's first
# sentence (1.0-2.7): merged word by word it would cut that sentence in half;
# merged by segment, the sentence stays whole and "Yeah" follows it.
WORDS_A = """<?xml version="1.0" encoding="ISO-8859-1"?>
<nite:root nite:id="TS3000a.A.words" xmlns:nite="http://nite.sourceforge.net/">
   <vocalsound nite:id="TS3000a.A.words0" starttime="1.9" endtime="2.0" type="laugh"/>
   <w nite:id="TS3000a.A.words1" starttime="2.0" endtime="2.2">Yeah</w>
   <w nite:id="TS3000a.A.words2" starttime="2.2" endtime="2.2" punc="true">.</w>
   <w nite:id="TS3000a.A.words3" starttime="3.5" endtime="3.7">So</w>
   <w nite:id="TS3000a.A.words4" starttime="3.7" endtime="3.9">what</w>
   <disfmarker nite:id="TS3000a.A.words5" starttime="3.9" endtime="3.9"/>
   <w nite:id="TS3000a.A.words6" starttime="3.9" endtime="4.1">first</w>
   <w nite:id="TS3000a.A.words7" starttime="4.1" endtime="4.1" punc="true">?</w>
</nite:root>
"""
SEGMENTS_A = """<?xml version="1.0" encoding="ISO-8859-1"?>
<nite:root nite:id="TS3000a.A.segs" xmlns:nite="http://nite.sourceforge.net/">
   <segment nite:id="TS3000a.sync.2" transcriber_start="1.9" transcriber_end="2.2">
      <nite:child href="TS3000a.A.words.xml#id(TS3000a.A.words0)..id(TS3000a.A.words2)"/>
   </segment>
   <segment nite:id="TS3000a.sync.3" transcriber_start="3.5" transcriber_end="4.1">
      <nite:child href="TS3000a.A.words.xml#id(TS3000a.A.words3)..id(TS3000a.A.words7)"/>
   </segment>
</nite:root>
"""
# B is the Project Manager. Its second segment is one word, `#id(...)` with no
# `..id(...)`; its third opens with the "." that ends the second -- punctuation
# is attached across the whole words file, so that "." still follows "Great".
WORDS_B = """<?xml version="1.0" encoding="ISO-8859-1"?>
<nite:root nite:id="TS3000a.B.words" xmlns:nite="http://nite.sourceforge.net/">
   <w nite:id="TS3000a.B.words0" starttime="1.0" endtime="1.4">Okay</w>
   <w nite:id="TS3000a.B.words1" starttime="1.4" endtime="1.4" punc="true">,</w>
   <w nite:id="TS3000a.B.words2" starttime="1.4" endtime="1.6">let's</w>
   <gap nite:id="TS3000a.B.words3" starttime="1.6" endtime="1.6"/>
   <w nite:id="TS3000a.B.words4" starttime="1.6" endtime="2.4">start</w>
   <w nite:id="TS3000a.B.words5" starttime="2.4" endtime="2.6">the</w>
   <w nite:id="TS3000a.B.words6" starttime="2.6" endtime="2.7">meeting</w>
   <w nite:id="TS3000a.B.words7" starttime="2.7" endtime="2.7" punc="true">.</w>
   <w nite:id="TS3000a.B.words8" starttime="5.0" endtime="5.3">Great</w>
   <w nite:id="TS3000a.B.words9" starttime="5.3" endtime="5.3" punc="true">.</w>
   <w nite:id="TS3000a.B.words10" starttime="5.5" endtime="5.8">Thanks</w>
   <w nite:id="TS3000a.B.words11" starttime="5.8" endtime="5.8" punc="true">.</w>
</nite:root>
"""
SEGMENTS_B = """<?xml version="1.0" encoding="ISO-8859-1"?>
<nite:root nite:id="TS3000a.B.segs" xmlns:nite="http://nite.sourceforge.net/">
   <segment nite:id="TS3000a.sync.1" transcriber_start="1.0" transcriber_end="2.7">
      <nite:child href="TS3000a.B.words.xml#id(TS3000a.B.words0)..id(TS3000a.B.words7)"/>
   </segment>
   <segment nite:id="TS3000a.sync.4" transcriber_start="5.0" transcriber_end="5.3">
      <nite:child href="TS3000a.B.words.xml#id(TS3000a.B.words8)"/>
   </segment>
   <segment nite:id="TS3000a.sync.5" transcriber_start="5.5" transcriber_end="5.8">
      <nite:child href="TS3000a.B.words.xml#id(TS3000a.B.words9)..id(TS3000a.B.words11)"/>
   </segment>
</nite:root>
"""
MEETINGS_XML = """<?xml version="1.0" encoding="ISO-8859-1"?>
<nite:root xmlns:nite="http://nite.sourceforge.net/">
   <meeting nite:id="meet_1" type="scenario" observation="TS3000a">
      <speaker nite:id="TS3000a_1" nxt_agent="A" role="XX"/>
      <speaker nite:id="TS3000a_2" nxt_agent="B" role="PM"/>
   </meeting>
   <meeting nite:id="meet_2" type="nonscenario" observation="TS3000b">
      <speaker nite:id="TS3000b_1" nxt_agent="A"/>
      <speaker nite:id="TS3000b_2" nxt_agent="B" role="ID"/>
   </meeting>
</nite:root>
"""

EXPECTED_TEXT = (
    "Project Manager: Okay, let's start the meeting.\n"
    "A: Yeah. So what first?\n"
    "Project Manager: Great. Thanks."
)

ABSSUMM = f"ami/abstractive/{MEETING}.abssumm.xml"
MEETINGS = "ami/corpusResources/meetings.xml"
WORDS = [f"ami/words/{MEETING}.A.words.xml", f"ami/words/{MEETING}.B.words.xml"]
SEGMENTS = [f"ami/segments/{MEETING}.A.segments.xml", f"ami/segments/{MEETING}.B.segments.xml"]


def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _write_sha256(root: Path, rel_paths: list[str]) -> None:
    (root / "ami.sha256").write_text(
        "".join(f"{_sha(root / p)}  {p}\n" for p in rel_paths), encoding="utf-8"
    )


@pytest.fixture
def corpus(tmp_path: Path) -> Path:
    """A one-meeting manifest + sha256 file over the XML above, rooted at tmp_path."""
    files = {
        ABSSUMM: "<nite:root/>",
        MEETINGS: MEETINGS_XML,
        WORDS[0]: WORDS_A,
        WORDS[1]: WORDS_B,
        SEGMENTS[0]: SEGMENTS_A,
        SEGMENTS[1]: SEGMENTS_B,
    }
    for rel_path, content in files.items():
        (tmp_path / rel_path).parent.mkdir(parents=True, exist_ok=True)
        (tmp_path / rel_path).write_text(content, encoding="utf-8")
    manifest = {
        "package": {"file": "ami_public_manual_1.6.2.zip"},
        "source_page": "https://groups.inf.ed.ac.uk/ami/download/",
        "records": [{"id": MEETING, "files": list(files)}],
    }
    (tmp_path / "ami.manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    _write_sha256(tmp_path, list(files))
    return tmp_path


def _edit_manifest(root: Path, edit: Callable[[dict[str, Any]], None]) -> None:
    manifest_path = root / "ami.manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    edit(manifest)
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")


def _load_all(root: Path) -> list[AMITranscript]:
    return load_all_transcripts(root / "ami.manifest.json", root / "ami.sha256", root)


def test_merges_segments_by_time_keeps_punctuation_and_drops_markers(corpus: Path):
    assert _load_all(corpus) == [AMITranscript(meeting_id=MEETING, text=EXPECTED_TEXT)]


def test_load_transcript_loads_one_meeting(corpus: Path):
    transcript = load_transcript(
        MEETING, corpus / "ami.manifest.json", corpus / "ami.sha256", corpus
    )
    assert transcript == AMITranscript(meeting_id=MEETING, text=EXPECTED_TEXT)


def test_load_transcript_of_a_meeting_not_in_the_manifest(corpus: Path):
    with pytest.raises(KeyError, match="TS3000z"):
        load_transcript("TS3000z", corpus / "ami.manifest.json", corpus / "ami.sha256", corpus)


def test_sentence_interrupted_by_another_speaker_stays_on_one_line(corpus: Path):
    lines = _load_all(corpus)[0].text.splitlines()
    # A's "Yeah" is spoken while B's sentence is still going on
    assert lines[0] == "Project Manager: Okay, let's start the meeting."
    assert "A: Yeah." not in lines


def test_speaker_change_starts_a_line_and_same_speaker_stays_on_it(corpus: Path):
    lines = _load_all(corpus)[0].text.splitlines()
    assert [line.split(":")[0] for line in lines] == ["Project Manager", "A", "Project Manager"]
    # two segments of the same speaker in a row are one line
    assert lines[1] == "A: Yeah. So what first?"
    assert lines[2] == "Project Manager: Great. Thanks."


def test_unknown_or_missing_role_falls_back_to_the_letter(corpus: Path):
    meetings_xml = corpus / MEETINGS
    assert speaker_labels(meetings_xml, "TS3000a") == {"A": "A", "B": "Project Manager"}
    assert speaker_labels(meetings_xml, "TS3000b") == {"A": "A", "B": "Industrial Designer"}


def test_speaker_missing_from_meetings_xml_is_named_by_letter(corpus: Path):
    text = format_transcript([corpus / p for p in WORDS], [corpus / p for p in SEGMENTS], {})
    assert text == "B: Okay, let's start the meeting.\nA: Yeah. So what first?\nB: Great. Thanks."


def test_file_not_downloaded_points_at_the_package(corpus: Path):
    (corpus / WORDS[0]).unlink()
    with pytest.raises(RuntimeError, match=r"TS3000a\.A\.words\.xml not found.*ami_public_manual"):
        _load_all(corpus)


@pytest.mark.parametrize(
    "dropped",
    [[MEETINGS], WORDS, SEGMENTS],
    ids=["meetings.xml", "words", "segments"],
)
def test_record_missing_meetings_xml_words_or_segments_is_an_error(
    corpus: Path, dropped: list[str]
):
    def drop(manifest: dict[str, Any]) -> None:
        manifest["records"][0]["files"] = [
            f for f in manifest["records"][0]["files"] if f not in dropped
        ]

    _edit_manifest(corpus, drop)
    with pytest.raises(RuntimeError, match="needs meetings.xml and its words and segments"):
        _load_all(corpus)


def test_speaker_with_words_but_no_segments_is_an_error(corpus: Path):
    _edit_manifest(corpus, lambda m: m["records"][0]["files"].remove(SEGMENTS[1]))
    with pytest.raises(RuntimeError, match="one words and one segments file per speaker"):
        _load_all(corpus)


def test_segment_pointing_outside_its_words_file_is_an_error(corpus: Path):
    segments_b = corpus / SEGMENTS[1]
    segments_b.write_text(SEGMENTS_B.replace("B.words11", "B.words99"), encoding="utf-8")
    _write_sha256(corpus, [ABSSUMM, MEETINGS, *WORDS, *SEGMENTS])
    with pytest.raises(RuntimeError, match="ids not in TS3000a.B.words.xml"):
        _load_all(corpus)


def test_malformed_manifest_is_a_runtime_error_not_a_keyerror(corpus: Path):
    _edit_manifest(corpus, lambda m: m.pop("package"))
    with pytest.raises(RuntimeError, match=r"(?s)ami\.manifest\.json is malformed.*package"):
        _load_all(corpus)


def test_record_id_must_match_the_abssumm_stem_exactly(corpus: Path):
    _edit_manifest(corpus, lambda m: m["records"][0].update(id=MEETING.upper()))
    with pytest.raises(RuntimeError, match="TS3000A.abssumm.xml"):
        _load_all(corpus)


def test_another_meetings_file_in_a_record_is_an_error(corpus: Path):
    other = "ami/words/TS3000b.A.words.xml"
    (corpus / other).write_text(WORDS_A, encoding="utf-8")
    _edit_manifest(corpus, lambda m: m["records"][0]["files"].append(other))
    _write_sha256(corpus, [ABSSUMM, MEETINGS, *WORDS, *SEGMENTS, other])
    with pytest.raises(RuntimeError, match="TS3000b.A.words.xml, another meeting's"):
        _load_all(corpus)


def test_checksum_mismatch_is_an_error(corpus: Path):
    (corpus / WORDS[0]).write_text(WORDS_A + " ", encoding="utf-8")
    with pytest.raises(RuntimeError, match="checksum mismatch"):
        _load_all(corpus)


def test_file_missing_from_sha256_is_an_error(corpus: Path):
    _write_sha256(corpus, [ABSSUMM, MEETINGS, WORDS[0], *SEGMENTS])
    with pytest.raises(RuntimeError, match=r"B\.words\.xml has no entry"):
        _load_all(corpus)


# Abstract and decisions sentences sit next to <actions>; only the actions are gold.
ABSSUMM_WITH_ACTIONS = """<?xml version="1.0" encoding="ISO-8859-1"?>
<nite:root xmlns:nite="http://nite.sourceforge.net/">
<abstract nite:id="TS3000a.x.abstract.1">
<sentence nite:id="TS3000a.x.s.1">The team met to start the project.</sentence>
</abstract>
<actions nite:id="TS3000a.x.actions.1">
<sentence nite:id="TS3000a.x.s.2">The Project Manager will book a room.</sentence>
<sentence nite:id="TS3000a.x.s.3">The   team will meet
   next Tuesday</sentence>
</actions>
<decisions nite:id="TS3000a.x.decisions.1">
<sentence nite:id="TS3000a.x.s.4">The remote will be yellow.</sentence>
</decisions>
</nite:root>
"""


def _load_gold(root: Path, meeting_id: str = MEETING) -> list[str]:
    return load_gold_actions(meeting_id, root / "ami.manifest.json", root / "ami.sha256", root)


def test_gold_actions_are_the_actions_sentences_only_whitespace_collapsed(corpus: Path):
    (corpus / ABSSUMM).write_text(ABSSUMM_WITH_ACTIONS, encoding="utf-8")
    _write_sha256(corpus, [ABSSUMM, MEETINGS, *WORDS, *SEGMENTS])
    assert _load_gold(corpus) == [
        "The Project Manager will book a room.",
        "The team will meet next Tuesday",
    ]


def test_gold_actions_of_a_meeting_without_actions_is_empty(corpus: Path):
    (corpus / ABSSUMM).write_text(
        '<nite:root xmlns:nite="http://nite.sourceforge.net/"/>', encoding="utf-8"
    )
    _write_sha256(corpus, [ABSSUMM, MEETINGS, *WORDS, *SEGMENTS])
    assert _load_gold(corpus) == []


def test_gold_actions_of_a_meeting_not_in_the_manifest(corpus: Path):
    with pytest.raises(KeyError, match="TS9999a"):
        _load_gold(corpus, "TS9999a")


def test_gold_actions_need_the_abssumm_file_in_the_record(corpus: Path):
    _edit_manifest(corpus, lambda m: m["records"][0]["files"].remove(ABSSUMM))
    with pytest.raises(RuntimeError, match="lists no TS3000a.abssumm.xml"):
        _load_gold(corpus)


def test_gold_actions_checksum_mismatch_is_an_error(corpus: Path):
    (corpus / ABSSUMM).write_text(ABSSUMM_WITH_ACTIONS, encoding="utf-8")
    with pytest.raises(RuntimeError, match="checksum mismatch"):
        _load_gold(corpus)


_AMI_DIR = MANIFEST_PATH.parents[2] / "local-data" / "ami" / "ami_public_manual_1.6.2"


@pytest.mark.skipif(
    not (_AMI_DIR / "segments").exists(),
    reason="AMI annotations not present locally (see eval/datasets/ami.manifest.json)",
)
def test_real_slice_loads_all_three_meetings():
    transcripts = {t.meeting_id: t for t in load_all_transcripts()}
    assert list(transcripts) == ["IB4003", "IS1008b", "IS1008d"]
    # Every word of the words layer, none lost at segment boundaries: the
    # counts the word-by-word merge gave (punctuation is attached, so a token
    # is one word).
    word_counts = {
        meeting_id: sum(len(line.split(": ", 1)[1].split()) for line in t.text.splitlines())
        for meeting_id, t in transcripts.items()
    }
    assert word_counts == {"IB4003": 6614, "IS1008b": 4314, "IS1008d": 4123}
    # IS1008* are scenario meetings with roles in meetings.xml; IB4003 has none
    is_speakers = {line.split(":")[0] for line in transcripts["IS1008b"].text.splitlines()}
    assert is_speakers == {
        "Project Manager",
        "Industrial Designer",
        "User Interface Designer",
        "Marketing Expert",
    }
    ib_speakers = {line.split(":")[0] for line in transcripts["IB4003"].text.splitlines()}
    assert ib_speakers == {"A", "B", "C", "D"}
    # IS1008d opens with a sentence the word-by-word merge cut in four
    assert transcripts["IS1008d"].text.startswith("Industrial Designer: I'm sorry to be late.\n")


@pytest.mark.skipif(
    not (_AMI_DIR / "abstractive").exists(),
    reason="AMI annotations not present locally (see eval/datasets/ami.manifest.json)",
)
def test_real_slice_gold_is_sixteen_action_items():
    gold = {m: load_gold_actions(m) for m in ["IB4003", "IS1008b", "IS1008d"]}
    assert {m: len(items) for m, items in gold.items()} == {"IB4003": 8, "IS1008b": 4, "IS1008d": 4}
    assert gold["IS1008b"][2] == "The Marketing Expert will present the marketing concept."
