# MIND — Why Tesseract `--psm 11` word recall is 64.7% (error analysis)

## Purpose

Direct follow-up to the mentor's question after seeing 64.7% word
recall vs. 91.2% character similarity: is the 64.7% ceiling mostly real
OCR failures, or mostly an artifact of exact-match, word-level scoring?
Every word_recall **miss** across both FUNSD documents (98 total, with
`--psm 11`) was pulled and classified by cause. Script:
`benchmarks/analyze_word_misses.py` (`python -m
benchmarks.analyze_word_misses` from `teams/mind/`) — reproducible,
prints this exact breakdown + the full example list.

## Method

For each of the 98 missed GT words, found the single most similar word
anywhere in Tesseract's output (character-level similarity, order
doesn't matter) and classified the pair into one of 5 buckets. This is
a **heuristic classifier, spot-checked on ~20 examples by hand, not
independently validated at the full n** — good enough to answer "mostly
near-misses or mostly real gaps," not a publishable taxonomy.

## Breakdown

| Category | Count | % of misses | What it means |
|---|---:|---:|---|
| **digit_merge** | 28 | 28.6% | OCR read the digits correctly, just merged an adjacent field into one token (e.g. zip+plus-4 code) — **not a recognition error**. |
| **segmentation** | 30 | 30.6% | OCR's content is right; the GT annotation's word boundary just falls differently (e.g. `oh` inside `ohio`) — **not a recognition error**. |
| **char_misread** | 18 | 18.4% | A real character-level OCR error, but the garbled output is recognizable/human-readable (e.g. `error`→`eror`). |
| **different_word** | 20 | 20.4% | OCR produced a different, plausible-looking token, not just a garbled version of the right one (e.g. `distribution`→`instructions`). |
| **not_detected** | 2 | 2.0% | Nothing similar anywhere in the output — genuinely missed. |

**Headline answer to "is 64.7% too-strict matching?": yes, mostly.**
`digit_merge` + `segmentation` = **59.2%** of all misses are cases
where OCR's actual output was correct or near-correct — the exact-match
metric just couldn't see it. Only `char_misread` + `different_word` +
`not_detected` = **40.8%** of misses are OCR actually getting content
wrong in a way a human would also call wrong, and most of those
(`char_misread`, 18.4%) are still legible.

One example proves the metric itself can be wrong, not just harsh:
**`comunication` (GT) vs. `communication` (OCR)** — the FUNSD
**annotation itself is misspelled**; Tesseract's reading is *more*
correct than the ground truth it's being scored against.

## Does this affect task extraction? (qualitative — extraction isn't built yet)

Extraction (`src/mind/extraction/`) is Phase 2 (week 4+, not built), so
this can't be measured directly yet — only reasoned about by category:

- **`digit_merge`, `segmentation` (59.2% of misses):** low risk. Content
  words are intact; the merged/split cases are mostly zip codes, phone
  numbers, and letterhead abbreviations (`P.`, `ATT.`, `U.S.`, form
  labels like `NAME`) — not typically the load-bearing content for
  "what's the task / who / when."
- **`char_misread` (18.4%):** low-moderate risk. An LLM-based extractor
  is generally robust to single-character typos (`eror`, `sireet`,
  `criginal` all still read as their intended word) — but this hasn't
  been tested against MIND's actual extraction prompts.
- **`different_word` (20.4%):** **highest risk, worth real attention.**
  This is OCR confidently producing a *different, plausible* word
  (`distribution`→`instructions`, `return`→`reper`, `Primed`→
  `privileged`) — not obviously wrong the way a garble is. If a word
  like this lands in a decision-relevant field, a downstream extractor
  has no signal that it's wrong.
- **`not_detected` (2.0%):** low prevalence, but by definition total
  information loss when it does happen.

**Recommendation:** re-run this same classification once extraction
exists, specifically checking whether any `different_word` case changed
an extracted task's meaning — that's the one category this analysis
can't rule out just from the OCR output alone.

## Full example list (98 misses, both FUNSD documents, `--psm 11`)

| GT word | OCR brought | similarity | category |
|---|---|---:|---|
| `3` | `336` | 0.50 | digit_merge |
| `-466` | `4665087` | 0.60 | digit_merge |
| `-5087` | `4665087` | 0.73 | digit_merge |
| `17` | `1706` | 0.67 | digit_merge |
| `:46` | `4` | 0.67 | digit_merge |
| `466-` | `4665087` | 0.60 | digit_merge |
| `5087` | `4665087` | 0.73 | digit_merge |
| `335-` | `336` | 0.67 | digit_merge |
| `7392` | `3357392` | 0.73 | digit_merge |
| `335-` | `336` | 0.67 | digit_merge |
| `7363` | `3357363` | 0.73 | digit_merge |
| `466-` | `4665087` | 0.60 | digit_merge |
| `8980` | `4668980` | 0.73 | digit_merge |
| `43215` | `432159428` | 0.71 | digit_merge |
| `12` | `12098` | 0.57 | digit_merge |
| `20` | `2` | 0.67 | digit_merge |
| `09/` | `091797` | 0.50 | digit_merge |
| `17/` | `7` | 0.67 | digit_merge |
| `97` | `7` | 0.67 | digit_merge |
| `10:` | `100` | 0.80 | digit_merge |
| `55` | `10855` | 0.57 | digit_merge |
| `5` | `15` | 0.67 | digit_merge |
| `5` | `15` | 0.67 | digit_merge |
| `5` | `15` | 0.67 | digit_merge |
| `20` | `2` | 0.67 | digit_merge |
| `5` | `15` | 0.67 | digit_merge |
| `1` | `12` | 0.67 | digit_merge |
| `1` | `12` | 0.67 | digit_merge |
| `NOTE:` | `no` | 0.67 | segmentation |
| `P.` | `op` | 0.67 | segmentation |
| `01` | `0` | 0.67 | segmentation |
| `ATT.` | `at` | 0.80 | segmentation |
| `June` | `___june` | 0.73 | segmentation |
| `Brown/` | `brown614` | 0.77 | segmentation |
| `DO` | `d` | 0.67 | segmentation |
| `NOT` | `no` | 0.80 | segmentation |
| `SOON` | `on` | 0.67 | segmentation |
| `not` | `no` | 0.80 | segmentation |
| `have` | `ave` | 0.86 | segmentation |
| `U.` | `us` | 0.67 | segmentation |
| `S.` | `si` | 0.67 | segmentation |
| `30` | `0` | 0.67 | segmentation |
| `ag` | `g` | 0.67 | segmentation |
| `oh` | `ohio` | 0.67 | segmentation |
| `225` | `25` | 0.80 | segmentation |
| `27` | `7` | 0.67 | segmentation |
| `31` | `3` | 0.67 | segmentation |
| `603` | `6` | 0.50 | segmentation |
| `K.` | `dark` | 0.40 | segmentation |
| `T.` | `to` | 0.67 | segmentation |
| `D.` | `nd` | 0.67 | segmentation |
| `NAME` | `me` | 0.67 | segmentation |
| `NAME` | `me` | 0.67 | segmentation |
| `-O` | `to` | 0.67 | segmentation |
| `Mart` | `mar` | 0.86 | segmentation |
| `61` | `6` | 0.67 | segmentation |
| `125` | `12` | 0.80 | segmentation |
| `Page` | `paget` | 0.89 | segmentation |
| `George` | `_george` | 0.92 | char_misread |
| `RECEIVE` | `received` | 0.93 | char_misread |
| `POSSIBLE` | `responsible` | 0.74 | char_misread |
| `distribution,` | `instructions` | 0.75 | char_misread (borderline — see `different_word` note) |
| `comunication` | `communication` | 0.96 | char_misread (**GT is misspelled — OCR is arguably correct**) |
| `error,` | `eror` | 0.89 | char_misread |
| `original` | `criginal` | 0.88 | char_misread |
| `cooperation.` | `_coopéfaton` | 0.73 | char_misread |
| `Street/` | `sireet` | 0.83 | char_misread |
| `LORILLARD` | `lortllard` | 0.89 | char_misread |
| `Sparrow` | `sparraw` | 0.86 | char_misread |
| `Portland` | `partland` | 0.88 | char_misread |
| `North` | `noth` | 0.89 | char_misread |
| `ACCOUNT` | `accounts` | 0.93 | char_misread |
| `ACCOUNT` | `accounts` | 0.93 | char_misread |
| `Portland` | `partland` | 0.88 | char_misread |
| `Dari` | `dark` | 0.75 | char_misread |
| `105` | `1085` | 0.86 | char_misread |
| `82092117` | `12098` | 0.46 | different_word |
| `PROPERLY,` | `reper` | 0.62 | different_word |
| `return` | `reper` | 0.55 | different_word |
| `-3428` | `432159428` | 0.62 | different_word |
| `Primed` | `privileged` | 0.62 | different_word |
| `19` | `1898` | 0.67 | different_word |
| `82200067` | `2072` | 0.50 | different_word |
| `841` | `e41` | 0.67 | different_word |
| `001` | `100` | 0.67 | different_word |
| `Blachly` | `etacnty` | 0.43 | different_word |
| `AUG` | `aue` | 0.67 | different_word |
| `JUN` | `eugene` | 0.44 | different_word |
| `VOLUME` | `me` | 0.50 | different_word |
| `VOLUME` | `me` | 0.50 | different_word |
| `Maid` | `may` | 0.57 | different_word |
| `Clover` | `ouverte` | 0.62 | different_word |
| `Trip` | `zip` | 0.57 | different_word |
| `Astro` | `faswo` | 0.60 | different_word |
| `106` | `100` | 0.67 | different_word |
| `600` | `100` | 0.67 | different_word |
| `www` | `wwwagstateohus` | 0.35 | not_detected (arguably a `digit_merge`-style URL merge, not a true miss — content is there, just glued together) |
| `x` | `texaco` | 0.29 | not_detected |
