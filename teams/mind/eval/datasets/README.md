# datasets

Manifests + checksums only — **never the corpora themselves**.

Real corpora used: AMI Meeting Corpus, RVL-CDIP / FUNSD, Enron Email,
SAMSum, ICSI / QMSum / MeetingBank. CRM data is synthetic by construction
(no real public CRM corpus exists). See MIND-01-System-Overview.md §7 for
licensing constraints per source (e.g. Enron: eval only, never surfaced in
a demo; SAMSum: CC BY-NC-ND, research only).

## Foundation Gate real-data slice

FUNSD was selected as the first real-data slice because the current Foundation Gate focuses on validating the evaluation and data-loading infrastructure rather than end-to-end meeting-to-task quality. A small sample of two documents from the FUNSD test split is used to exercise the infrastructure against real public data while keeping the corpus local and reproducible through the committed manifest and checksums.
