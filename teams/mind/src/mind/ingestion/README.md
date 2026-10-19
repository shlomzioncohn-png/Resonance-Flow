# ingestion

Bridge intake + corpus loading.

- Reads raw content that Bridge lands in object storage, following its pointer events.
- Loads the public research corpora used for training/eval (AMI, RVL-CDIP, FUNSD, Enron, SAMSum, ICSI/QMSum/MeetingBank).
- Does not process or interpret content — that's `extraction/` and `multimodal/`.

See `MIND-01-System-Overview.md` (§2, §7) for the source list and the real-vs-synthetic data policy.

## AMI transcripts (`ami_transcripts.py`)

AMI meetings as readable text, for the extraction eval (#178) and prompt tuning (#690).

- **Reads** the meetings in `eval/datasets/ami.manifest.json`, each file checksum-verified
  against `ami.sha256`. From AMI's manual annotations, for every speaker:
  `words/<meeting>.<letter>.words.xml` (the words) and
  `segments/<meeting>.<letter>.segments.xml` (the transcriber's segments, each a coherent
  stretch of that speaker's words with a start time); and `corpusResources/meetings.xml`
  for the speakers' roles. The corpus is gitignored — unzip the manifest's package under
  `local-data/ami/`.
- **Output:** `AMITranscript(meeting_id, text)`, from `load_transcript(meeting_id)` or
  `load_all_transcripts()`. `meeting_id` is the `abssumm.xml` filename stem, same case
  (`IS1008b`) — the eval joins on it. `text` has one line per speaker turn (from IS1008d):

  ```
  Industrial Designer: I'm sorry to be late.
  Project Manager: Welcome back. Welcome back everybody.
  ```

  The segments of all speakers are sorted by start time, and consecutive segments of the
  same speaker are one line. Punctuation is kept; the non-word markers (`<vocalsound>`,
  `<disfmarker>`, `<gap>`) are dropped; fillers ("uh", "um") and truncated words stay, as
  transcribed. Speakers are named by role (Project Manager, Industrial Designer, User
  Interface Designer, Marketing Expert) when `meetings.xml` gives one — the AMI gold action
  items name people that way — and by letter (`A`–`D`) when it doesn't, as in the
  non-scenario meeting IB4003.

  **Overlapping speech:** the order is by segment, not by word, so a sentence stays whole
  when someone else speaks during it — a "Yeah" said mid-sentence comes on its own line
  after that sentence, not inside it. (Merging word by word instead cut about half the
  turns of a meeting into fragments: "I'm / Welcome / sorry / back.") No words are lost:
  every word of the words layer is in exactly one segment.
- **Why the human transcript, not ASR:** so the extraction eval measures extraction only —
  ASR errors don't show up as extraction errors. ASR quality is measured on its own (#480,
  `benchmarks/asr-ami-validation.md`).
