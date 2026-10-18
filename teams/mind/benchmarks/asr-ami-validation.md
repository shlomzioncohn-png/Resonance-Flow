# MIND — ASR Validation Against AMI, with a Measured WER (#480)

**Status: measurement infrastructure complete; results provisional.** The file
set is not final (it waits on R2, who owns the AMI slice), and neither the model
nor the pipeline's time limits were changed on these numbers.

This document keeps three kinds of statement apart, and each section says which
one it holds:

1. **Measured facts** — numbers the scripts print, reproducible from the pinned
   file set and normalization.
2. **Hypotheses that need testing** — readings of those numbers that fit them
   but were not tested directly. Each says what would test it.
3. **Decisions pending approval** — what the numbers would support, not what
   has been decided.

## Purpose

#135 picked Whisper `base.en` on a *rough* word diff with no normalization
(rough WER ~0.26, "not to be quoted as Whisper's WER"). This answers how good
the transcripts reaching R2's extraction actually are:
- a real WER (jiwer, documented normalization) on real meeting audio;
- per recording, not just overall;
- what breaks;
- and whether the model-size choice holds up.

## Setup

**What is measured: the ASR pipeline, not Whisper alone.** Every window goes
through `mind.multimodal.asr.transcribe()` as WAV bytes — the same chunking,
VAD, caps and timeout as in production. The run fails unless each call leaves
an ASR `record_call()` record under the size being measured; latency and CPU
time come from that record. (#135's `asr_benchmark.py` called faster-whisper
directly; it is unchanged, so its numbers still reproduce.)

**The fixed set (provisional — see D1).** Three 240 s windows per meeting, at
1/4, 1/2 and 3/4 of its length. Windows rather than whole meetings because
`transcribe()` decodes at most `MAX_DURATION_S` (600 s) and gives up after
`TRANSCRIBE_TIMEOUT_S` (240 s) — a 30-minute meeting hits both. The set is
pinned by `test_the_fixed_set_is_pinned`.

| Meeting | Audio (`ami-audio.manifest.json`) | Windows (s) |
|---|---|---|
| IS1008b | `IS1008b.Mix-Headset.wav`, 1,768.5 s | 442–682, 884–1124, 1326–1566 |
| IB4003 | `IB4003.Mix-Headset.wav`, 2,023.3 s | 506–746, 1012–1252, 1517–1757 |

24 minutes of audio, 3,999 reference words after normalization. Reference: AMI's
human transcript (`words/` layer of `ami_public_manual_1.6.2`), all four
speakers interleaved by word start time. A word belongs to the window it
*starts* in. CC BY 4.0, as in the manifest.

**Normalization** — one function (`normalize()` in `benchmarks/asr_validation.py`)
applied identically to reference and transcript, in this order:

1. Unicode NFKC; curly apostrophes become straight.
2. Lowercase.
3. Underscores deleted — AMI writes acronyms letter by letter (`T_V_`), Whisper
   writes `TV`.
4. `%` becomes `percent`.
5. Digits become words the way AMI writes them (AMI never writes a digit):
   `25` → `twenty five`, `6,000` → `six thousand`, `12.5` → `twelve point
   five`, `21st` → `twenty first`. No "and" inserted.
6. Every character except a–z, 0–9 and `'` becomes a space — punctuation,
   hyphens (`twenty-five`, `part-time`), and Whisper's ellipses that join
   words (`you...one`).
7. `ok` → `okay`, `alright` → `all right`, `'cause` → `because`, `'kay` →
   `okay`; then apostrophes at a word's edges are stripped, apostrophes inside
   a word kept (`we're` ≠ `were` — #135's diff conflated them).
8. Fillers and backchannels removed: uh, um, er, erm, ah, eh, hm, hmm, mm,
   mmm, mhm, huh (so `mm-hmm`, `uh-huh` go too).

Removed from the reference only, since Whisper cannot produce them: punctuation
tokens, non-word elements (`<vocalsound>`, `<disfmarker>`, `<gap>`), and
truncated words (`trunc="true"`, e.g. "recognit").

Not normalized, on purpose, and listed so the residue is known: years and
spoken number forms ("nineteen ninety five", "a hundred and fifty"), times as
decimals ("9.30"), British/American spelling, AMI's spoken forms ("wanna",
"dunno", "kinda") against Whisper's "want to"/"don't know"/"kind of". The last
two measurably cost ~0.2 WER points — see F3.

**WER** is jiwer 4.0.0's word-level Levenshtein. Per-meeting and overall
figures are **pooled** (errors summed over reference words), not averaged
rates. Only the WER is unique: when two alignments cost the same, how errors
split into S/D/I is jiwer's choice.

**Engine and host:** faster-whisper int8, CPU, 4 threads, beam 5; one dev
laptop, 2026-10-15. `tiny.en`/`small.en` load unpinned weights (no sha256
check — `PINNED_WHISPER_MODELS` pins only `base.en`); they were hash-checked
when placed by hand in #135.

**Reproduce** (from `teams/mind/`, with the AMI audio and annotations placed as
in the manifest):

```
python -m benchmarks.asr_validation --sizes tiny.en base.en small.en
python -m benchmarks.asr_error_analysis
pytest tests/test_asr_validation.py tests/test_asr_error_analysis.py -v
```

(The issue names `python -m mind.multimodal.asr_benchmark`; benchmarks live in
`benchmarks/` and run as `python -m benchmarks.<name>` — see
`benchmarks/README.md` — and are not part of the `mind` package.)

## 1. Measured facts

### F1. WER per window and overall

One run per size. Overlapped = share of the window's reference words spoken
while another speaker was speaking (F3).

| Window | Overlapped | tiny.en | base.en | small.en |
|---|---|---|---|---|
| IS1008b 442–682 | 24.9% | 0.258 | 0.272 | 0.336 **(truncated)** |
| IS1008b 884–1124 | 12.6% | 0.127 | 0.124 | 0.107 |
| IS1008b 1326–1566 | 3.7% | 0.068 | 0.059 | 0.059 |
| IB4003 506–746 | 10.9% | 0.161 | 0.152 | 0.135 |
| IB4003 1012–1252 | 32.3% | 0.253 | 0.245 | 0.220 |
| IB4003 1517–1757 | 28.7% | 0.250 | 0.244 | 0.214 |

Pooled:

| | tiny.en | base.en | small.en |
|---|---|---|---|
| IS1008b (3 windows) | 0.139 | 0.138 | 0.149 (incl. truncated) |
| IB4003 (3 windows) | 0.223 | 0.215 | 0.191 |
| **Overall, all 6 windows** | 0.188 | **0.183** | 0.174 (incl. truncated) |
| **Overall, the 5 windows all sizes completed** | 0.180 | **0.173** | **0.154** |
| Substitutions / deletions / insertions (5 windows) | 169 / 450 / 22 | 137 / 465 / 13 | 101 / 428 / 20 |

- **The truncated window.** `small.en` was cut off by the pipeline's timeout on
  IS1008b 442–682. Its 0.336 counts audio that was never transcribed, so it is
  not comparable; the 5-window row leaves that window out for every size.
- **Within one size, windows range ~4.6x** (base.en: 0.059 to 0.272). The
  overall figure hides that spread.
- **base.en's normalized WER is 0.183**, against #135's rough 0.263 on the
  same two meetings. Different spans, though: whole meetings there, windows
  here — so the gap is not the effect of normalization alone.

### F2. Runtime (from the `record_call()` records)

| | RTF per window | CPU s per 240 s window | Calls over the 120 s "slow" limit |
|---|---|---|---|
| tiny.en | 0.058–0.165 | 37–85 | 0 of 6 |
| base.en | 0.109–0.370 | 71–254 | 0 of 6 |
| small.en | 0.603–0.765 (5 windows); 1.200 truncated | 399–508 | **6 of 6** |

- **The truncated call ran 288 s**, against a `TRANSCRIBE_TIMEOUT_S` of 240 s.
- **base.en's RTF here (~0.2) is about twice #135's clean run (~0.1).** Host
  load during this run was not controlled — see H5.

### F3. What the errors are

**Deletions dominate:** 552 of base.en's 733 errors (75%), and the deletion
count moves little with model size (428–465 on the 5 windows). small.en's gain
is mostly in substitutions (F1).

**Deletions concentrate in overlapped speech.** A reference word counts as
overlapped when another speaker's word intersects it in time (AMI word
timings).

| Size | Words | Overlapped: deletion rate | Not overlapped: deletion rate | Ratio | Overlapped: subst. rate | Not: subst. rate |
|---|---|---|---|---|---|---|
| tiny.en | all | 0.398 | 0.067 | 5.9x | 0.090 | 0.039 |
| base.en | all | **0.411** | **0.072** | **5.7x** | 0.075 | 0.032 |
| base.en | without response words* | 0.366 | 0.068 | 5.4x | 0.074 | 0.031 |
| small.en (5 windows) | all | 0.390 | 0.058 | 6.7x | 0.045 | 0.025 |

\* yeah, yes, yep, okay, right, oh, no, sure — often overlapped and often
dropped, so left out to check they don't produce the effect alone. They don't:
the ratio barely changes.

- **Overlapped words are 19.4% of the reference but 57.8% of base.en's
  deletions** (319 of 552); 59.0% for tiny.en, 61.0% for small.en.
- **Window by window, the order of overlap and WER mostly agree** (F1 table):
  the 3.7%-overlap window has the lowest WER, the 24.9–32.3% windows the
  highest.
- **The exception is IB4003 506–746:** 10.9% overlap but 93 deletions (base.en),
  far more than IS1008b 884–1124 at 12.6% overlap with 37.

**Most deletions are isolated words.** For base.en, 109 of 552 deletions (19.7%)
sit in runs of 5+ consecutive words (15 runs, the longest 12 words) — whole
utterances missing. Most deleted words (overlapped / not): yeah 42/11, the 11/25,
it's 8/11, okay 10/4, no 8/1.

**Substitutions are a long tail:** base.en's 160 substitutions are 150 distinct
pairs, no pair more than 3 times.
- **Normalization residue, not recognition errors:** 8 of them are British vs.
  American spelling (organise ×2, recognise, authorisation) or AMI's spoken
  forms (wanna, dunno, kinda, gonna). That is ~0.2 WER points.
- **Examples from base.en** (examples, not a counted category):
  - *Names:* christine → christian, pierrette → pierre, eddie → you.
  - *Domain words:* titanium → time, feature → future (×2), steel → steal,
    file → fire, backup → background.

### F4. The worst stretches, read

The `asr_error_analysis` report ranks 30 s bins by error rate (bins with 20+
reference words). base.en's worst four, read against the reference:

1. **IB4003 1162–1192 s (0.37):**
   - Two speakers trade short turns. The transcript keeps one speaker's
     sentences ("you can't prevent having visitors…") and has none of the
     other's interjections ("you want to have visitors", "it's funny that").
   - At the end, the reference's "…move researchers to another building"
     comes out as "we need to research on our building" — the content
     changes, not just the wording.
2. **IB4003 1727–1757 s (0.36):**
   - While one speaker sets out a plan, the words of a second speaker
     interleaved with it are missing ("we're", "gonna", "should", "be",
     "there", "why", "couldn't").
   - So is a stretch of the plan itself, "come up with the optimal solution"
     (a run of deletions).
   - A name is substituted: "Pierrette" → "Pierre".
3. **IS1008b 532–562 s (0.34):** cross-talk in a materials discussion. The
   content words "expensive", "the marketing comes out" and "made out of
   titanium" are missing.
4. **IB4003 1607–1637 s (0.34):** "that's not realistic either" → "that
   realistic leader" — **the negation is dropped**. One observed instance;
   not counted across the set.

## 2. Hypotheses that need testing

- **H1 — Whisper transcribes one speaker of an overlap and drops the other.**
  - *Fits:* F3's 5.7x deletion ratio, which holds without response words and
    for every size, and F4's stretches, where the transcript follows one
    coherent speaker.
  - *Not shown:*
    - why — the dominant (louder/closer) voice is the obvious candidate,
      untested;
    - that a bigger Whisper fixes it — small.en's ratio is no better.
  - *Test:* in overlapped stretches, which speaker's words survive, against
    each speaker's level in the individual headset channels (AMI publishes
    them).
- **H2 — The missing overlapped words are really absent, not reordered.**
  - *Why it matters:* interleaving speakers by start time is a guess at word
    order. A transcript holding both speakers in another order would also
    score deletions.
  - *Against reordering:* insertions are few (13–22 on the 5 windows) — a
    reordered word would show up as an insertion or substitution.
  - *Test:* bag-of-words recall of each overlapped stretch, independent of
    order.
- **H3 — IB4003 506–746 has a cause other than overlap.**
  - *Fact:* 93 deletions at 10.9% overlap (F3), cause unknown.
  - *Candidates:* VAD dropping quiet or distant speech, or one quiet speaker.
  - *Test:* compare the ASR segments' timestamps with the reference words'
    timing, and see which speakers the deleted words belong to.
- **H4 — small.en is really ~2 WER points better, mostly fewer
  substitutions.**
  - *Fits:* 5 windows, one run of each size.
  - *Unknown:* run-to-run noise for small.en, which samples on fallback
    (#135). base.en repeated almost exactly (0.184 and 0.183 overall; 5 of 6
    windows identical) — that says nothing about small.en.
  - *Test:* repeat the run, more windows.
- **H5 — base.en's latency (~0.2 RTF, vs. ~0.1 in #135) is host load, not
  pipeline overhead.**
  - *Unknown:* the run did not control host load; the chunked
    VAD + `transcribe()` path could also cost more than #135's direct call.
  - *Test:* a run on a quiet machine, then compare with #135.
- **H6 — The timeout overshoots (288 s vs. 240 s) because it is checked only
  between segments.**
  - *Source:* reading `_transcribe()` — the elapsed time is checked per chunk
    and per decoded segment, so one long decode (with fallback) runs past the
    limit.
  - *Test:* not tested; would need timing per segment.
- **H7 — Accents are not separable here.** AMI's speakers are mostly
  non-native English speakers, and every window is affected alike. This set
  cannot tell accent errors from others; nothing here supports a claim either
  way.

## 3. Decisions pending approval

- **D1 — The final file set (R2).**
  - *Proposal:* the meetings of R2's real-data slice (`ami.manifest.json`):
    IS1008b, IB4003, and IS1008d (its words files are local, its audio is
    not yet), same window rule.
  - *R2 to confirm:* the set, and whether she wants windows placed on the
    stretches her extraction reads.
  - *After that:* rerun everything above; the numbers here are replaced, not
    averaged in.
- **D2 — The long-term model size.**
  - *What the data supports:* keep `base.en`, as #135 decided — confirmed on
    normalized WER, not revised.
    - Over tiny.en: 0.7 points of WER and 19% fewer substitutions at
      1.4–2.3x the latency per window.
    - small.en: more accurate (H4), but at its measured RTF (0.6–0.77) it
      exceeds the monitoring "slow" limit on every 4-minute call. A recording
      of `MAX_DURATION_S` (600 s) would need ~360–460 s, past
      `TRANSCRIBE_TIMEOUT_S` — so small.en is not viable with the pipeline's
      current limits.
  - *Not decided:* moving to small.en means also deciding the timeout and the
    compute budget. Nothing was changed on provisional numbers.
- **D3 — Diarization / per-speaker channels.** H1 suggests the mixed channel
  loses overlapped speech, so a single-channel model will not recover it.
  Whether that matters is R2's call (out of scope for #480 unless she says
  extraction needs it).
- **D4 — Two more normalization rules?** British→American spelling and AMI's
  spoken forms (F3: ~0.2 WER points). Adding them changes every number, so
  decide before the final measurement, not after.

## For R2: what to expect in the text reaching extraction

Measured on base.en, provisional set:

- **About 1 reference word in 5 is wrong or missing** (WER 0.183). It varies a
  lot by stretch: ~6% in the window with the least overlapping speech, ~25% in
  the most overlapped ones (F1).
- **Missing, not invented, is the main error.** 3 of 4 errors are deletions
  (F3), and insertions are rare: if a task isn't extracted, its words may
  simply not be in the transcript.
- **Overlapped speech loses ~40% of its words** (F3). Interjections and
  objections said over someone else are the most likely to vanish — including
  content, not only "yeah" (F4).
- **Names and domain terms get substituted** with plausible wrong words
  (christine → christian, titanium → time). An extracted owner or item name
  can be wrong while looking fine.
- **A dropped "not" can invert a statement** — one instance seen (F4.4); not
  counted.
- **Format** (unchanged from #478):
  - plain text, segments joined;
  - no speaker labels;
  - no "uh"/"um";
  - numbers as digits.

## Acceptance criteria (#480)

- [x] **A fixed, named set of AMI recordings, recorded:** above, pinned by a
  test. Provisional pending R2 (D1).
- [x] **WER with a standard library against AMI's reference:** jiwer 4.0.0.
- [x] **Normalization documented explicitly:** "Setup", and the
  `asr_validation.py` docstring.
- [x] **WER per recording and overall:** F1.
- [x] **At least two worst segments examined, failure modes described:** F4,
  F3.
- [ ] **Model-size choice confirmed or revised, with evidence:** the evidence
  confirms base.en (D2). Awaiting approval, and the rerun on the final set.
- [x] **Written up in `benchmarks/`:** this document.
- [ ] **R2 told what failure modes to expect:** the section above is ready to
  send; not sent yet.
