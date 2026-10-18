# ingestion

Bridge intake + corpus loading.

- Reads raw content that Bridge lands in object storage, following its pointer events.
- Loads the public research corpora used for training/eval (AMI, RVL-CDIP, FUNSD, Enron, SAMSum, ICSI/QMSum/MeetingBank).
- Does not process or interpret content — that's `extraction/` and `multimodal/`.

See `MIND-01-System-Overview.md` (§2, §7) for the source list and the real-vs-synthetic data policy.

## AMI transcripts (`ami_transcripts.py`)

AMI meetings as readable text, for the extraction eval (#178) and prompt tuning (#690).

- **Reads** the meetings in `eval/datasets/ami.manifest.json`, each file checksum-verified
  against `ami.sha256`: every speaker's `words/<meeting>.<letter>.words.xml` from AMI's
  manual annotations, and `corpusResources/meetings.xml` for the speakers' roles. The corpus
  is gitignored — unzip the manifest's package under `local-data/ami/`.
- **Output:** `AMITranscript(meeting_id, text)`, from `load_transcript(meeting_id)` or
  `load_all_transcripts()`. `meeting_id` is the `abssumm.xml` filename stem, same case
  (`IS1008b`) — the eval joins on it. `text` has one line per speaker turn (from IS1008d):

  ```
  Project Manager: That's your uh prototype model?
  User Interface Designer: Yeah.
  ```

  The words of all speakers are merged by start time and a new line starts whenever the
  speaker changes. Punctuation is kept; the non-word markers (`<vocalsound>`,
  `<disfmarker>`, `<gap>`) are dropped; fillers ("uh", "um") and truncated words stay, as
  transcribed. Speakers are named by role (Project Manager, Industrial Designer, User
  Interface Designer, Marketing Expert) when `meetings.xml` gives one — the AMI gold action
  items name people that way — and by letter (`A`–`D`) when it doesn't, as in the
  non-scenario meeting IB4003. Where two people talk over each other, merging by word time
  splits their speech into short alternating turns.
- **Why the human transcript, not ASR:** so the extraction eval measures extraction only —
  ASR errors don't show up as extraction errors. ASR quality is measured on its own (#480,
  `benchmarks/asr-ami-validation.md`).
