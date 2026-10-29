# MIND — ASR Validation Against AMI, with a Measured WER (#480)

**Status: measured on the proposed final set; the set awaits R2's
confirmation (D1).** The numbers are from the final run on 2026-10-28 — nine
windows from the three meetings of the real-data slice, on the pipeline after
#481. Neither the model nor the pipeline's time limits were changed on them.
An earlier run (2026-10-15) measured `small.en` only partially — see F1.

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
directly; it keeps to its two meetings, `SIZE_BENCHMARK_MEETINGS`, so its
numbers still reproduce.)

**The fixed set (proposed — see D1).** Three 240 s windows per meeting, at
1/4, 1/2 and 3/4 of its length. Windows rather than whole meetings because
`transcribe()` decodes at most `MAX_DURATION_S` (600 s) and gives up after
`TRANSCRIBE_TIMEOUT_S` (240 s) — a 30-minute meeting hits both. The set is
pinned by `test_the_fixed_set_is_pinned`.

| Meeting | Audio (`ami-audio.manifest.json`) | Windows (s) |
|---|---|---|
| IS1008b | `IS1008b.Mix-Headset.wav`, 1,768.5 s | 442–682, 884–1124, 1326–1566 |
| IB4003 | `IB4003.Mix-Headset.wav`, 2,023.3 s | 506–746, 1012–1252, 1517–1757 |
| IS1008d | `IS1008d.Mix-Headset.wav`, 1,480.8 s | 370–610, 740–980, 1111–1351 |

36 minutes of audio, 6,100 reference words after normalization. Reference:
AMI's human transcript (`words/` layer of `ami_public_manual_1.6.2`), all four
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
7. Word forms, each from a fixed list (D4):
   - `ok` → `okay`, `alright` → `all right`, `'cause` → `because`, `'kay` →
     `okay`;
   - British spellings become American, as Whisper writes them: every one in
     the reference of IS1008b, IB4003 and IS1008d (17 words: `organise` →
     `organize`, `colour` → `color`, `labelled` → `labeled`, …). A list, not
     an *-ise* → *-ize* rule: `advertise`, `otherwise` and `precise` are in
     the same reference;
   - AMI's spoken forms become the words Whisper writes: `wanna` → `want to`,
     `gonna` → `going to`, `gotta` → `got to`, `dunno` → `don't know`,
     `kinda` → `kind of`.

   Then apostrophes at a word's edges are stripped, apostrophes inside a word
   kept (`we're` ≠ `were` — #135's diff conflated them).
8. Fillers and backchannels removed: uh, um, er, erm, ah, eh, hm, hmm, mm,
   mmm, mhm, huh (so `mm-hmm`, `uh-huh` go too).

Removed from the reference only, since Whisper cannot produce them: punctuation
tokens, non-word elements (`<vocalsound>`, `<disfmarker>`, `<gap>`), and
truncated words (`trunc="true"`, e.g. "recognit").

Not normalized, on purpose, and listed so the residue is known: years and
spoken number forms ("nineteen ninety five", "a hundred and fifty"), times as
decimals ("9.30"), and `etcetera` against Whisper's `etc` (2 substitutions,
small.en only).

**WER** is jiwer 4.0.0's word-level Levenshtein. Per-meeting and overall
figures are **pooled** (errors summed over reference words), not averaged
rates. Only the WER is unique: when two alignments cost the same, how errors
split into S/D/I is jiwer's choice.

**Engine and host:** faster-whisper int8, CPU, 4 threads, beam 5; one dev
laptop, runs on 2026-10-15 and twice on 2026-10-28. `tiny.en`/`small.en` load
unpinned weights (no sha256 check — `PINNED_WHISPER_MODELS` pins only
`base.en`); they were hash-checked when placed by hand in #135.

**Reproduce** (from `teams/mind/`, with the AMI audio and annotations placed as
in the manifest):

```
python -m benchmarks.asr_validation --sizes tiny.en base.en small.en
python -m benchmarks.asr_error_analysis
pytest tests/test_asr_validation.py tests/test_asr_error_analysis.py -v
```

No `CONFIG_FILE_PATH` is needed: since #481, ASR takes the model size from
config.yaml's `asr.whisper_model` before `WHISPER_MODEL`, so each size's
process writes its own `local-data/ami/asr-validation/<size>/config.yaml`
naming that size — a config.yaml on the host cannot swap the model being
measured.

(The issue names `python -m mind.multimodal.asr_benchmark`; benchmarks live in
`benchmarks/` and run as `python -m benchmarks.<name>` — see
`benchmarks/README.md` — and are not part of the `mind` package.)

## 1. Measured facts

### F1. WER per window and overall

The final run (2026-10-28), one run per size; every window completed for every
size. Overlapped = share of the window's reference words spoken while another
speaker was speaking (F3).

| Window | Overlapped | tiny.en | base.en | small.en |
|---|---|---|---|---|
| IS1008b 442–682 | 24.8% | 0.252 | 0.261 | 0.232 |
| IS1008b 884–1124 | 12.5% | 0.122 | 0.120 | 0.103 |
| IS1008b 1326–1566 | 3.8% | 0.063 | 0.054 | 0.057 |
| IB4003 506–746 | 10.9% | 0.159 | 0.151 | 0.134 |
| IB4003 1012–1252 | 32.3% | 0.251 | 0.242 | 0.218 |
| IB4003 1517–1757 | 28.8% | 0.247 | 0.246 | 0.214 |
| IS1008d 370–610 | 22.5% | 0.248 | 0.240 | 0.221 |
| IS1008d 740–980 | 23.4% | 0.191 | 0.157 | 0.157 |
| IS1008d 1111–1351 | 22.8% | 0.173 | 0.164 | 0.159 |

Pooled:

| | tiny.en | base.en | small.en |
|---|---|---|---|
| IS1008b (3 windows) | 0.134 | 0.132 | 0.119 |
| IB4003 (3 windows) | 0.221 | 0.215 | 0.190 |
| IS1008d (3 windows) | 0.202 | 0.184 | 0.176 |
| **Overall, all 9 windows** | 0.190 | **0.182** | **0.166** |
| Substitutions / deletions / insertions | 303 / 782 / 77 | 248 / 796 / 64 | 185 / 767 / 61 |

- **Within one size, windows range ~4.8x** (base.en: 0.054 to 0.261). The
  overall figure hides that spread.
- **base.en's normalized WER is 0.182** (0.180 on IS1008b and IB4003 alone),
  against #135's rough 0.263 on those two meetings. Different spans, though:
  whole meetings there, windows here — so the gap is not the effect of
  normalization alone.
- **IS1008d has most of the set's insertions:** 42 of base.en's 64 (H8).
- **The runs reproduce.** Across the three runs, every window's transcript is
  identical run to run, small.en's included — except IB4003 1517–1757 for
  tiny.en and base.en, which differs every time (base.en scores 0.246 in both
  10-28 runs, with a different S/D/I split), and the small.en window the
  10-15 run cut short.
- **The 2026-10-15 run measured `small.en` on 5 windows only.** The pipeline's
  timeout cut it off on IS1008b 442–682 (0.336, counting audio never
  transcribed), so that run reported small.en on the other 5 windows and
  called it not viable on its latency (F2, D2). On 10-28 the same window
  completed (0.232).
- **D4's word forms lowered every size's WER by 0.2–0.4 points** on the first
  two meetings' transcripts (base.en 0.184 → 0.180), almost all in
  substitutions (base.en: 160 → 146). The most frequent form is `gonna`: 28
  times in the three meetings' reference.

### F2. Runtime (from the `record_call()` records)

RTF and CPU seconds per 240 s window, per run:

| | 10-15 | 10-28, first (6 windows) | 10-28, final (9 windows) |
|---|---|---|---|
| tiny.en RTF / CPU s | 0.058–0.165 / 37–85 | 0.060–0.167 / 37–87 | 0.026–0.059 / 23–53 |
| base.en RTF / CPU s | 0.109–0.370 / 71–254 | 0.129–0.221 / 69–146 | 0.049–0.181 / 44–150 |
| small.en RTF / CPU s | 0.603–0.765 / 399–508 (5 windows); 1.200 truncated | 0.153–0.499 / 140–303 | 0.122–0.690 / 114–446 |
| Calls over the 120 s "slow" limit | small.en 6 of 6 | none | small.en 2 of 9 (166 s, 158 s) |

- **The host, not the pipeline, sets these numbers.** The transcripts are the
  same run to run (F1), so the decoding work is the same, yet the time for it
  moves 2–3x between runs, and within the final run small.en's RTF swung
  0.12–0.69 across windows of the same length.
- **The truncated 10-15 call ran 288 s** against a `TRANSCRIBE_TIMEOUT_S` of
  240 s (H6).
- **base.en ran at 0.05–0.10 RTF on 8 of 9 windows in the final run**, at or
  below #135's clean ~0.1 (H5).
- **CPU summed over a run's windows:** final run tiny.en 385 s, base.en 725 s,
  small.en 2,584 s (3.6x base.en); first 10-28 run 2.2x. small.en costs 2–4x
  base.en's CPU, depending on the run.

### F3. What the errors are

**Deletions dominate:** 796 of base.en's 1,108 errors (72%), and the deletion
count moves little with model size (767–796). small.en's gain is mostly in
substitutions: 185 against base.en's 248 (F1).

**Deletions concentrate in overlapped speech.** A reference word counts as
overlapped when another speaker's word intersects it in time (AMI word
timings).

| Size | Words | Overlapped: deletion rate | Not overlapped: deletion rate | Ratio | Overlapped: subst. rate | Not: subst. rate |
|---|---|---|---|---|---|---|
| tiny.en | all | 0.396 | 0.059 | 6.7x | 0.086 | 0.040 |
| base.en | all | **0.404** | **0.059** | **6.8x** | 0.065 | 0.034 |
| base.en | without response words* | 0.350 | 0.055 | 6.4x | 0.066 | 0.033 |
| small.en | all | 0.407 | 0.052 | 7.8x | 0.042 | 0.027 |

\* yeah, yes, yep, okay, right, oh, no, sure — often overlapped and often
dropped, so left out to check they don't produce the effect alone. They don't:
the ratio barely changes.

- **Overlapped words are 20.6% of the reference but 63.9% of base.en's
  deletions** (509 of 796); 63.7% for tiny.en, 66.9% for small.en.
- **Window by window, the order of overlap and WER mostly agree** (F1 table):
  the 3.8%-overlap window has the lowest WER, the 24.8–32.3% windows the
  highest, IS1008d's 22.5–23.4% windows in between.
- **The exception is IB4003 506–746:** 10.9% overlap but 93 deletions (base.en),
  far more than IS1008b 884–1124 at 12.5% overlap with 39.

**Most deletions are isolated words.** For base.en, 155 of 796 deletions (19.5%)
sit in runs of 5+ consecutive words (21 runs, the longest 17 words, F4.4) —
whole utterances missing. Most deleted words (overlapped / not): yeah 62/14,
yes 22/0, okay 17/10, it's 16/11, the 16/31.

**Substitutions are a long tail:** base.en's 248 substitutions are 218 distinct
pairs, no pair more than 5 times (a → the, the → a).
- **D4 took out the normalization residue seen among them:** British vs.
  American spelling (organise ×2, recognise, authorisation) and AMI's spoken
  forms (wanna, dunno, kinda, gonna).
- **Examples from base.en** (examples, not a counted category):
  - *Names:* christine → christian, pierrette → pierre, eddie → you.
  - *Domain words:* titanium → time, feature → future (×2), steel → steal,
    file → fire, backup → background, compliments → minutes.

### F4. The worst stretches, read

The `asr_error_analysis` report ranks 30 s bins by error rate (bins with 20+
reference words). base.en's worst four in the final run, read against the
reference:

1. **IS1008d 370–400 s (0.42):** the team reacts to a prototype, several
   people at once.
   - Short reactions over the main speaker are gone ("okay", "I'm fine", "of
     course").
   - A claim loses its content: "egg shapes aren't the most economical" →
     "egg shapes aren't i".
   - A content word is swapped: "a couple of compliments to the artist" →
     "a couple of minutes to the artist".
   - Words appear that the reference lacks ("probably", "a couple of", "when
     it comes to the") — they read as natural speech (H8).
2. **IB4003 1162–1192 s (0.37):**
   - Two speakers trade short turns. The transcript keeps one speaker's
     sentences ("you can't prevent having visitors…") and has none of the
     other's interjections ("you want to have visitors", "it's funny that").
   - At the end, the reference's "…move researchers to another building"
     comes out as "we need to research on our building" — the content
     changes, not just the wording.
3. **IB4003 1727–1757 s (0.36):**
   - While one speaker sets out a plan, the words of a second speaker
     interleaved with it are missing ("we're", "gonna", "should", "be",
     "there", "why", "couldn't").
   - So is a stretch of the plan itself, "come up with the optimal solution"
     (a run of deletions).
   - A name is substituted: "Pierrette" → "Pierre".
4. **IS1008d 520–550 s (0.35):** the longest deletion run in the set, 17
   words. Two speakers overlap — "a bit of a difference of opinion", "a long
   way", "cost a lot of money to market it" — and the transcript joins them
   into one sentence neither said: **"we're going to have a lot of money to
   market it"**. A cost becomes money to spend.

Also read, from the earlier runs' top four (0.34 each in the final run):

5. **IS1008b 532–562 s:** cross-talk in a materials discussion. The content
   words "expensive", "the marketing comes out" and "made out of titanium"
   are missing.
6. **IB4003 1607–1637 s:** "that's not realistic either" → "that realistic
   leader" — **the negation is dropped**. One observed instance; not counted
   across the set.

## 2. Hypotheses that need testing

- **H1 — Whisper transcribes one speaker of an overlap and drops the other.**
  - *Fits:* F3's 6.8x deletion ratio, which holds without response words and
    for every size, and F4's stretches, where the transcript follows one
    coherent speaker — or, at 520–550 s, joins two into one sentence.
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
  - *Against reordering:* insertions are few (61–77 per size, 1.0–1.3% of
    reference words) — a reordered word would show up as an insertion or
    substitution.
  - *Test:* bag-of-words recall of each overlapped stretch, independent of
    order.
- **H3 — IB4003 506–746 has a cause other than overlap.**
  - *Fact:* 93 deletions at 10.9% overlap (F3), cause unknown.
  - *Candidates:* VAD dropping quiet or distant speech, or one quiet speaker.
  - *Test:* compare the ASR segments' timestamps with the reference words'
    timing, and see which speakers the deleted words belong to.
- **H4 — small.en is really ~1.6 WER points better, mostly fewer
  substitutions.**
  - *Fits:* 9 windows (0.182 → 0.166), and its transcripts repeat exactly
    run to run, although it samples on fallback (#135).
  - *Unknown:* how much of it holds on other meetings — the gain varies 3x
    between these three (IS1008b 1.3 points, IB4003 2.5, IS1008d 0.8).
  - *Test:* more meetings.
- **H5 — base.en's slower runs (~0.2 RTF, vs. ~0.1 in #135) were host load,
  not pipeline overhead.**
  - *Supports it:* in the final run base.en ran at 0.05–0.10 on 8 of 9
    windows, on the same pipeline, and the same transcripts took 2–3x
    different time between runs (F2).
  - *Unknown:* no run controlled host load, so the pipeline's own overhead
    over #135's direct call is not measured.
  - *Test:* a run on a quiet machine, then compare with #135.
- **H6 — The timeout overshoots (288 s vs. 240 s, on 10-15) because it is
  checked only between segments.**
  - *Source:* reading `_transcribe()` — the elapsed time is checked per chunk
    and per decoded segment, so one long decode (with fallback) runs past the
    limit.
  - *Test:* not tested; would need timing per segment.
- **H7 — Accents are not separable here.** AMI's speakers are mostly
  non-native English speakers, and every window is affected alike. This set
  cannot tell accent errors from others; nothing here supports a claim either
  way.
- **H8 — IS1008d's insertions are mostly words AMI's transcriber left out,
  not words Whisper invented.**
  - *Fits:* in F4.1 the inserted words ("probably", "a couple of", "when it
    comes to the") read as natural speech in context, and IS1008d holds 42 of
    base.en's 64 insertions.
  - *Why it matters:* if so, part of IS1008d's WER is the reference's, not
    the ASR's.
  - *Test:* listen to the inserted stretches.

## 3. Decisions pending approval

- **D1 — The final file set (R2).**
  - *Proposal, now measured:* the three meetings of R2's real-data slice
    (`ami.manifest.json`) — IS1008b, IB4003, IS1008d — same window rule.
    IS1008d's audio comes from the same AMI source as the other two.
  - *R2 to confirm:* the set, and whether she wants windows placed on the
    stretches her extraction reads.
  - *If she wants other windows:* rerun; the numbers here are replaced, not
    averaged in.
- **D2 — The long-term model size.**
  - *What the data supports:* keep `base.en`, as #135 decided — confirmed on
    normalized WER over the final set, not revised. The case against small.en
    is cost and the time limit.
    - Over tiny.en: 0.8 points of WER (0.190 → 0.182) and 18% fewer
      substitutions, at 1.9x the CPU.
    - small.en is 1.6 points better (0.166 vs. 0.182), mostly on
      substitutions (185 vs. 248: names and domain terms, F3), and only 0.8
      on IS1008d. It does not help where most words are lost: overlapped
      speech is deleted at the same rate (0.407 vs. 0.404).
    - It costs 2–4x base.en's CPU (F2). Over all three runs its RTF ranged
      0.12–0.77, so a recording of `MAX_DURATION_S` (600 s) would take
      ~70–460 s against a `TRANSCRIBE_TIMEOUT_S` of 240 s. base.en's
      0.05–0.37 is ~30–220 s, under the limit in every run. All on a laptop
      with 4 threads, before any pod CPU limit.
  - *Not decided:* moving to small.en means also deciding the timeout and the
    compute budget. Worth reopening only if wrong names and terms turn out to
    hurt extraction (R2/R1).
- **D3 — Diarization / per-speaker channels.** The transcript has no speaker
  labels, and H1 suggests the mixed channel loses overlapped speech — a
  single-channel model will not recover it. R2 said on 2026-10-28 that she
  would prefer to know who said what. Out of scope for #480; a separate issue
  if the team takes it up.
- **D4 — Two more normalization rules: decided, added (2026-10-28).**
  British→American spelling and AMI's spoken forms are now part of rule 7,
  before the final measurement, so the final numbers use them from the start.
  They lowered every size's WER by 0.2–0.4 points (F1).

## For R2: what to expect in the text reaching extraction

Measured on base.en, final set:

- **About 1 reference word in 5 is wrong or missing** (WER 0.182). It varies a
  lot by stretch: ~5% in the window with the least overlapping speech, ~25% in
  the most overlapped ones (F1).
- **Missing, not invented, is the main error.** About 3 of 4 errors are
  deletions (F3), and insertions are rare: if a task isn't extracted, its
  words may simply not be in the transcript.
- **Overlapped speech loses ~40% of its words** (F3). Interjections and
  objections said over someone else are the most likely to vanish — including
  content, not only "yeah" (F4).
- **Two overlapping speakers can come out as one sentence neither said**, with
  a different meaning (F4.4: a cost becomes "a lot of money to market it").
- **Names and domain terms get substituted** with plausible wrong words
  (christine → christian, titanium → time, compliments → minutes). An
  extracted owner or item name can be wrong while looking fine.
- **A dropped "not" can invert a statement** — one instance seen (F4.6); not
  counted.
- **Format** (unchanged from #478):
  - plain text, segments joined;
  - no speaker labels;
  - no "uh"/"um";
  - numbers as digits.

## Acceptance criteria (#480)

- [x] **A fixed, named set of AMI recordings, recorded:** above, pinned by a
  test. Pending R2's confirmation (D1).
- [x] **WER with a standard library against AMI's reference:** jiwer 4.0.0.
- [x] **Normalization documented explicitly:** "Setup", and the
  `asr_validation.py` docstring.
- [x] **WER per recording and overall:** F1.
- [x] **At least two worst segments examined, failure modes described:** F4,
  F3.
- [x] **Model-size choice confirmed or revised, with evidence:** the final set
  confirms base.en (D2).
- [x] **Written up in `benchmarks/`:** this document.
- [x] **R2 told what failure modes to expect:** sent on 2026-10-28; her reply
  is in D3.
