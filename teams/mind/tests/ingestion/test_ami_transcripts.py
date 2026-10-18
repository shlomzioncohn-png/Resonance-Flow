"""Tests for mind.ingestion.ami_transcripts -- AMI's human transcript as turns (#784)."""

import hashlib
import json
from pathlib import Path

import pytest

from mind.ingestion.ami_transcripts import (
    MANIFEST_PATH,
    AMITranscript,
    format_transcript,
    load_all_transcripts,
    load_transcript,
    speaker_labels,
)

MEETING = "TS3000a"

# B is the Project Manager and punctuates; A has a role code that isn't known.
# B's "." starts at 2.0, the same time as A's "So": attached only after the
# merge, it would sort after "So" (A's file comes first) and land in A's turn.
WORDS_A = """<?xml version="1.0" encoding="ISO-8859-1"?>
<nite:root nite:id="TS3000a.A.words" xmlns:nite="http://nite.sourceforge.net/">
   <vocalsound nite:id="TS3000a.A.words0" starttime="1.8" endtime="1.9" type="laugh"/>
   <w nite:id="TS3000a.A.words1" starttime="2.0" endtime="2.2">So</w>
   <w nite:id="TS3000a.A.words2" starttime="2.2" endtime="2.4">what</w>
   <disfmarker nite:id="TS3000a.A.words3" starttime="2.4" endtime="2.4"/>
   <w nite:id="TS3000a.A.words4" starttime="2.4" endtime="2.6">first</w>
   <w nite:id="TS3000a.A.words5" starttime="2.6" endtime="2.6" punc="true">?</w>
</nite:root>
"""
WORDS_B = """<?xml version="1.0" encoding="ISO-8859-1"?>
<nite:root nite:id="TS3000a.B.words" xmlns:nite="http://nite.sourceforge.net/">
   <w nite:id="TS3000a.B.words0" starttime="1.0" endtime="1.4">Okay</w>
   <w nite:id="TS3000a.B.words1" starttime="1.4" endtime="1.4" punc="true">,</w>
   <w nite:id="TS3000a.B.words2" starttime="1.4" endtime="1.6">let's</w>
   <gap nite:id="TS3000a.B.words3" starttime="1.6" endtime="1.6"/>
   <w nite:id="TS3000a.B.words4" starttime="1.6" endtime="2.0">start</w>
   <w nite:id="TS3000a.B.words5" starttime="2.0" endtime="2.0" punc="true">.</w>
   <w nite:id="TS3000a.B.words6" starttime="5.0" endtime="5.3">Great</w>
   <w nite:id="TS3000a.B.words7" starttime="5.3" endtime="5.3" punc="true">.</w>
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

EXPECTED_TEXT = "Project Manager: Okay, let's start.\nA: So what first?\nProject Manager: Great."


def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


@pytest.fixture
def corpus(tmp_path: Path) -> Path:
    """A one-meeting manifest + sha256 file over the XML above, rooted at tmp_path."""
    files = {
        f"ami/abstractive/{MEETING}.abssumm.xml": "<nite:root/>",
        "ami/corpusResources/meetings.xml": MEETINGS_XML,
        f"ami/words/{MEETING}.A.words.xml": WORDS_A,
        f"ami/words/{MEETING}.B.words.xml": WORDS_B,
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
    (tmp_path / "ami.sha256").write_text(
        "".join(f"{_sha(tmp_path / p)}  {p}\n" for p in files), encoding="utf-8"
    )
    return tmp_path


def _load_all(root: Path) -> list[AMITranscript]:
    return load_all_transcripts(root / "ami.manifest.json", root / "ami.sha256", root)


def test_merges_speakers_by_time_keeps_punctuation_and_drops_markers(corpus: Path):
    assert _load_all(corpus) == [AMITranscript(meeting_id=MEETING, text=EXPECTED_TEXT)]


def test_load_transcript_loads_one_meeting(corpus: Path):
    transcript = load_transcript(
        MEETING, corpus / "ami.manifest.json", corpus / "ami.sha256", corpus
    )
    assert transcript == AMITranscript(meeting_id=MEETING, text=EXPECTED_TEXT)


def test_load_transcript_of_a_meeting_not_in_the_manifest(corpus: Path):
    with pytest.raises(KeyError, match="TS3000z"):
        load_transcript("TS3000z", corpus / "ami.manifest.json", corpus / "ami.sha256", corpus)


def test_speaker_change_starts_a_line_and_same_speaker_stays_on_it(corpus: Path):
    lines = _load_all(corpus)[0].text.splitlines()
    assert [line.split(":")[0] for line in lines] == ["Project Manager", "A", "Project Manager"]
    # B's three words before A speaks are one line, not three
    assert lines[0] == "Project Manager: Okay, let's start."


def test_unknown_or_missing_role_falls_back_to_the_letter(corpus: Path):
    meetings_xml = corpus / "ami/corpusResources/meetings.xml"
    assert speaker_labels(meetings_xml, "TS3000a") == {"A": "A", "B": "Project Manager"}
    assert speaker_labels(meetings_xml, "TS3000b") == {"A": "A", "B": "Industrial Designer"}


def test_speaker_missing_from_meetings_xml_is_named_by_letter(corpus: Path):
    words = sorted((corpus / "ami/words").glob("*.words.xml"))
    assert format_transcript(words, {}) == "B: Okay, let's start.\nA: So what first?\nB: Great."


def test_record_id_must_match_the_abssumm_stem_exactly(corpus: Path):
    manifest_path = corpus / "ami.manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["records"][0]["id"] = MEETING.upper()
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(RuntimeError, match="TS3000A.abssumm.xml"):
        _load_all(corpus)


def test_another_meetings_words_file_in_a_record_is_an_error(corpus: Path):
    other = corpus / "ami/words/TS3000b.A.words.xml"
    other.write_text(WORDS_A, encoding="utf-8")
    manifest_path = corpus / "ami.manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["records"][0]["files"].append("ami/words/TS3000b.A.words.xml")
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    sha_path = corpus / "ami.sha256"
    sha_path.write_text(
        sha_path.read_text(encoding="utf-8") + f"{_sha(other)}  ami/words/TS3000b.A.words.xml\n",
        encoding="utf-8",
    )
    with pytest.raises(RuntimeError, match="TS3000b.A.words.xml, another meeting's"):
        _load_all(corpus)


def test_checksum_mismatch_is_an_error(corpus: Path):
    (corpus / f"ami/words/{MEETING}.A.words.xml").write_text(WORDS_A + " ", encoding="utf-8")
    with pytest.raises(RuntimeError, match="checksum mismatch"):
        _load_all(corpus)


def test_file_missing_from_sha256_is_an_error(corpus: Path):
    sha_path = corpus / "ami.sha256"
    lines = sha_path.read_text(encoding="utf-8").splitlines()
    sha_path.write_text(
        "\n".join(line for line in lines if ".B.words" not in line), encoding="utf-8"
    )
    with pytest.raises(RuntimeError, match=r"B\.words\.xml has no entry"):
        _load_all(corpus)


_AMI_DIR = MANIFEST_PATH.parents[2] / "local-data" / "ami" / "ami_public_manual_1.6.2"


@pytest.mark.skipif(
    not (_AMI_DIR / "words").exists(),
    reason="AMI annotations not present locally (see eval/datasets/ami.manifest.json)",
)
def test_real_slice_loads_all_three_meetings():
    transcripts = {t.meeting_id: t for t in load_all_transcripts()}
    assert list(transcripts) == ["IB4003", "IS1008b", "IS1008d"]
    assert all(t.text for t in transcripts.values())
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
