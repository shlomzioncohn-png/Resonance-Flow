# ingestion

Bridge intake + corpus loading.

- Reads raw content that Bridge lands in object storage, following its pointer events.
- Loads the public research corpora used for training/eval (AMI, RVL-CDIP, FUNSD, Enron, SAMSum, ICSI/QMSum/MeetingBank).
- Does not process or interpret content — that's `extraction/` and `multimodal/`.

See `MIND-01-System-Overview.md` (§2, §7) for the source list and the real-vs-synthetic data policy.
