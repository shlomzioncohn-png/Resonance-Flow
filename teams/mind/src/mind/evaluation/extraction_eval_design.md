# Extraction-Quality Eval — Design Note

> **Status: design locked except one external confirmation (Ayala); core implemented
> (#177).** Design for the *second* harness: "did the pipeline extract the right
> action items from a meeting at all?" — precision / recall against AMI's
> human-tagged action items. The matching core is `extraction_eval.py`
> (`evaluate_extraction`), with a fixture and a CLI; per-meeting grouping and
> micro/macro aggregation are `evaluate_meetings` (#178); the owner-match
> diagnostic (§3.7) is not built yet — see §9.
>
> **First real run, 2026-10-25 (#178):** the v1 slice through main's extraction,
> micro P 31.6% / R 37.5% / F1 34.3% at τ = 0.60 — results, hand review and
> findings in the README, "Real-data validation (#178)". The lexical backend
> missed four right pairs on real output; that is the case for the embedding
> backend (§4.3), not for changing τ after the fact.
>
> Lives next to the code in `src/mind/evaluation/`; the README's "Extraction-quality
> eval" section is the short version of this note.
>
> **The only open item** (everything else is decided):
> - **`TODO(Ayala)`** — does she expose a reusable `similarity(a,b)->float` over
>   her embeddings? If not within a few days, the lexical baseline (§4.2, fully
>   specified) ships instead. Either way the harness is unblocked.
>   **2026-10-25:** the lexical baseline is built (`similarity.py`, §4.2).
>
> Settled with Yonit (2026-10-14, #233): `title` + `description` is the
> comparison text, and the meeting id travels as the extractor's `item_key`, not
> `source_ref` (§"Meeting IDs", revised 2026-10-12). The contract's status is
> kept in the README, "The extracted-item contract".
>
> This matching is **inherently approximate** — see §7. Not an exact comparison.
>
> **Issue-step coverage:** step 1–2 → §1 · step 4 (matching rule) → §2–§4 ·
> step 5 (hand sketch) → §5 · step 6 (document it, say it's approximate) → this
> file + §7 · step 7 (meeting IDs w/ Yonit) → §"Meeting IDs" · step 8 (baseline
> if Ayala is slow) → §4.2 · step 9 (threshold before results) → §6.

---

## 1. Where the ground truth lives (issue steps 1–2, done)

- **Corpus:** AMI manual annotations, package `ami_public_manual_1.6.2` (text
  only, no audio), CC BY 4.0. Already downloaded (issue #230) under
  `local-data/ami/` and pointed at by `eval/datasets/ami.manifest.json` +
  `eval/datasets/ami.sha256` (added on `michal/real-data-slice`; merges to
  `main` with that PR). The corpus itself is gitignored; only the manifest +
  checksums are in git.
- **The action items:** `abstractive/<MEETING>.abssumm.xml` → the single
  `<actions>` element → **each child `<sentence>` is one human-tagged action
  item.** The same file also has `<abstract>`, `<decisions>`, `<problems>` —
  not ours.
- **Meeting id:** the filename stem (`IB4003.abssumm.xml` → `IB4003`); it is
  also the prefix of every `nite:id` inside the file
  (`IB4003.rdhillon.s.7`).
- **Encoding:** the XML header declares `encoding="ISO-8859-1"`, but that header is
  wrong for this corpus. Read every file as UTF-8. All 5,080 XML files in the
  package decode as UTF-8; the 63 that contain non-ASCII bytes hold
  `\xc2\xa0` (a UTF-8-encoded non-breaking space), apart from two
  `participantSummaries` files with an `ö`. Honoring the declared encoding would
  decode `\xc2\xa0` as `Â` + a non-breaking space — corruption. (An earlier
  version of this note said the opposite; README "Real data slice", item 3, has
  the evidence.) None of this touches the action items themselves: the 306
  `<actions>` sentences contain no non-ASCII character at all.

### Count — locked at 306

**306 action-item sentences, across 133 meetings** (142 `abssumm.xml` files;
9 have an empty `<actions>` element).

- By meeting type: ES 134 · TS 82 · IS 76 · IB 14.
- Per meeting: min 1, max 8; most meetings have 1–4.

How it was counted: parse each `*.abssumm.xml` with an XML parser, count every
`<sentence>` that is a descendant of `<actions>`, sum across files. A
line-based count (`grep`/`awk`) gives a lower number (271) only because some
files put several `<sentence>` elements on one line — the XML parse is
authoritative.

Internal docs (`MIND-01`, `MIND-02`, `MIND-03`, `teams/mind/CLAUDE.md`) all
repeat "100h audio, 101 meetings, 381 annotated action items". **381 could not
be reproduced** from any slice tried (306 all meetings; 292 scenario meetings
ES/IS/TS only; neither with `<decisions>` folded in). The "101 meetings" figure
is also off (142 abssumm files). That line appears to trace to an unverified
external source. **We use our verified 306.**

---

## 2. The matching problem (issue step 4)

An extracted task and the annotated sentence are almost never byte-identical.
Real gold sentences vs. a plausible extractor output:

| AMI gold sentence | plausible extracted task |
|---|---|
| "The User Interface Designer will make a presentation on the user interface concept." | "UI Designer to present the interface concept" |
| "The Project Manager will research companies that can provide the Industrial Designer with help on integrating voice recognition into her design." | "PM to find companies to help with voice recognition" |
| "The team will meet next Tuesday at two o'clock" | "Next team meeting Tuesday 2pm" |

Same commitments, very different strings. We need a rule for **"these two count
as the same item."**

---

## 3. Matching rule — LOCKED (only the similarity *backend* is open, §4)

Everything in §3 is decided. The one thing still open is *which implementation*
sits behind `similarity()` (Ayala's embeddings vs. the lexical baseline) — that
is §4, and it does not change any of the mechanics below.

### 3.1 The seam

One function, and the harness only ever calls this:

```python
def similarity(a: str, b: str) -> float:
    """1.0 = same meaning, 0.0 = unrelated. Implementation is swappable."""
```

The contract is enforced, not just stated: `evaluate_extraction` raises
`ValueError` for any score (and any `threshold`) that is not a real number in
`[0, 1]`. A NaN would otherwise slip past the "stop below `τ`" step of §3.4
(`nan < τ` is `False`) and score as a perfect match.

### 3.2 Text normalization, before `similarity` is called — LOCKED

Applied to every string on both sides, so the score never depends on
formatting: lowercase; strip leading/trailing whitespace; collapse internal
runs of whitespace to one space; strip a single trailing `.`. "Whitespace" is
whatever `str.split()` treats as such — that includes the non-breaking space
`\xa0` (so no separate replace is needed) but not the zero-width space
`\u200b`, which is absent from this corpus. **Nothing else** —
internal punctuation stays, stopword removal / stemming (if any) belong to the
`similarity` implementation, not here.

### 3.3 Which text is compared — LOCKED (confirmed with R2, 2026-10-14)

- **Gold side:** the `<sentence>` text verbatim (then §3.2 normalization).
- **Predicted side:** `title + ". " + description` when `description` is set,
  else `title`. Rationale: an AMI gold sentence carries roughly a
  title-plus-detail worth of content; matching title-only would under-credit an
  extractor that puts specifics in `description`. `TaskCreationRequested` has
  `title` (required) and `description` (optional). Pinned in code as
  `ExtractedItem.to_comparison_text()` (`extraction_contract.py`, #233).

### 3.4 Matching procedure, per meeting — LOCKED

- `P` = extracted tasks, `G` = gold action items.
- Compute `similarity(p, g)` for every `(p, g)` pair → a `|P| × |G|` matrix.
- **Global greedy one-to-one match:** list all pairs, sort by similarity
  descending; walk the list and take a pair iff neither endpoint is already
  used **and** `similarity >= τ`. Stop when no pair qualifies.
- **Tie-break** (equal similarity in the sorted list): by gold index ascending,
  then predicted index ascending. Fully deterministic → reproducible runs.
- `TP` = matched pairs · `FP` = `len(P) − TP` · `FN` = `len(G) − TP`.
- Greedy, not optimal (Hungarian): the matrix is tiny (meetings have 1–8 gold
  items), and greedy differs from the optimal assignment only when two
  predicted items both clear `τ` against two gold items with crossing
  preferences — rare, and worth at most one pair. If §5-with-code shows greedy
  losing pairs, `scipy.optimize.linear_sum_assignment` is a drop-in. Not worth
  the dependency now.

### 3.5 Precision / recall / F1 — LOCKED

- Per meeting: `precision = TP / (TP + FP)`, `recall = TP / (TP + FN)`,
  `f1 = 2PR / (P + R)`. When a denominator is 0 the metric is **undefined** for
  that meeting → reported as `None`, not 0.0 and not 1.0.
- **Headline = micro-average** over the whole eval slice:
  `Σ TP / Σ (TP + FP)`, `Σ TP / Σ (TP + FN)`, and F1 of those. Every action
  item weighted equally; robust to 1-item meetings. **The CI regression check
  keys off micro-F1.**
- **Also report macro-average** (mean of the defined per-meeting values) as a
  secondary number — a gap between micro and macro means the extractor behaves
  differently on small vs. large meetings.
- Empty cases fall out of the formulas: `|P| = 0, |G| > 0` → 0 TP, recall 0.0,
  precision `None`; `|G| = 0, |P| > 0` → precision 0.0, recall `None`;
  both empty → meeting contributes nothing. **This differs from the current
  `metrics.extraction_precision_recall`** (which returns 1.0/0.0 constants) —
  see §9.

### 3.6 Why one-to-one — LOCKED

If extraction merges two gold items into one task, one-to-one scores it as
1 TP + 1 FN. If it splits one gold item into two, 1 TP + 1 FP. A many-to-many
rule would hide both. §5.2 (IB4003) confirms the behaviour.

### 3.7 Owner / role — LOCKED for v1

`similarity` compares the **whole sentence, role phrase included**. In §5 the
role words *help* — they disambiguate near-identical actions ("the industrial
designer will work on the X concept" vs. "the user interface designer will…").

Separately, as a **secondary diagnostic** (not folded into P/R for v1): among
TP pairs, an *owner-match rate* — parse the leading `"The <Role> will…"` from
the gold sentence (simple regex; `None` when it doesn't match) and check it
against the predicted task's `role_context` / suggested owner. Wrong-owner
extraction is a real error; v1 surfaces it without letting it distort the
headline number.

---

## 4. Similarity backend — baseline LOCKED, embeddings preferred (pending Ayala)

`similarity()` has **one module** — `src/mind/evaluation/similarity.py` — with a
selectable backend. The harness imports `similarity` only.

### 4.1 Preferred backend: embeddings (`TODO(Ayala)`)

Cosine of Ayala's sentence embeddings, rescaled to `[0, 1]`
(`(cos + 1) / 2` if her range is `[-1, 1]`, passthrough if already `[0, 1]`).
Semantic — robust to the paraphrase / abbreviation failures §5 shows wrecking
the lexical backend. `TODO(Ayala)`: reusable `similarity(a,b)->float`? which
model? score range? version-pinned (so eval numbers reproduce)? *(question sent)*

### 4.2 Baseline backend: lexical — LOCKED, ready to build

`rapidfuzz.fuzz.token_set_ratio(a, b) / 100.0`.

- **Why `token_set_ratio`** and not plain ratio or Jaccard: it tokenises both
  strings into sets and scores the best of {intersection} vs.
  {intersection + a-remainder} vs. {intersection + b-remainder}. Order-independent
  and tolerant of one side being much longer — which is the common
  gold-vs-extraction shape ("The User Interface Designer will make a
  presentation on the user interface concept" vs. "UI designer: present
  interface concept").
- **Dependency:** `rapidfuzz` — small, fast (C++), widely used. Add to a
  `[project.optional-dependencies].eval` group in `pyproject.toml`. If the dep
  is refused: token-set Jaccard + a Porter stemmer, pure-Python — but §5.1 and
  §5.5 show unstemmed Jaccard is not good enough, so `rapidfuzz` is the
  recommendation.
- **Do not build it until Ayala answers** (issue step 8: "ask her first — do
  not assume either way"). The design here is so it can be built in an hour once
  she does.
- **Built 2026-10-25 (#178)** as `similarity.lexical_similarity` +
  `LEXICAL_THRESHOLD`, under issue step 8: as of that date `mind.embeddings`
  exposes `embed` / `embed_batch` but no reusable `similarity(a, b)`. Questions
  on an embedding backend were sent to Ayala the same week; they do not block
  this baseline. Two details beyond the lines above: `processor=default_process`
  (lowercase, punctuation to spaces — `_normalize` deliberately leaves internal
  punctuation to the backend), and no stemming yet (Finding 1 in §5.5 asks for
  it; whether τ = 0.60 needs it is checked on the §5 pairs before any real
  run). `rapidfuzz` is in the `dev` extra, not a separate `eval` group, because
  CI installs `.[dev]` only.

### 4.3 Swap plan

When Ayala's function exists: the `embedding` backend calls it, the default
flips to `embedding`, and `lexical` stays available — for CI runs without the
model loaded, and as a published comparison point. No harness change; only the
default backend constant in `similarity.py`.

**Revised 2026-10-28 (mentor's decision): the default does not flip.** On the
#178 run neither backend was better (README, "Embedding backend"): embedding
found more of the right pairs (7 of 16 against 5) and added more wrong ones
(4 against 1). So the default stays `lexical`, and every report shows both
backends side by side, each with its right and wrong matches, next to the hand
review as the ground truth. `scripts/run_ami_extraction_eval.py --backend both`
prints them together.

---

## 5. Hand sketch — TP / FP / FN on real meetings (issue step 5)

**Rule used for this sketch** (deliberately the weakest plausible option, to
stress-test it): `similarity` = content-word Jaccard, lowercase, stopwords
dropped, **no stemming**; `τ = 0.5`; one-to-one greedy.

> The "Extracted (hypothetical)" rows are **invented** — a plausible LLM
> extraction output, not a real pipeline run (there is no pipeline yet). The
> Jaccard values are computed **by hand and are approximate**; verify with code
> once the harness exists. The purpose is to find an unworkable threshold now.

### 5.1 IS1008b — 4 gold items (paraphrase stress test)

Gold:
- G1 "The Industrial Designer will make a presentation on the components concept for the product."
- G2 "The User Interface Designer will make a presentation on the user interface concept."
- G3 "The Marketing Expert will present the marketing concept."
- G4 "The Project Manager will research companies that can provide the Industrial Designer with help on integrating voice recognition into her design."

Extracted (hypothetical):
- E1 "Industrial Designer to present the components concept"
- E2 "UI Designer to present the interface concept"
- E3 "Marketing Expert to present the marketing concept"
- E4 "PM to find companies to help with voice recognition"
- E5 "Team to decide on the remote's functions"  *(from discussion, not an action)*

Matching (by hand):

| pair | shared content words | Jaccard ≈ | ≥ τ? | verdict |
|---|---|---|---|---|
| G1–E1 | industrial, designer, components, concept | ~0.50 | borderline yes | **TP** (with stemming: comfortable) |
| G3–E3 | marketing, expert, present, concept | ~0.70 | yes | **TP** |
| G2–E2 | designer, interface, concept | ~0.43 | **no** | G2 → **FN**, E2 → **FP** — "user interface" vs "UI" |
| G4–E4 | companies, help, voice, recognition | ~0.30 | **no** | G4 → **FN**, E4 → **FP** — heavy paraphrase |
| E5 | — | — | — | **FP** (correct — it is a hallucination) |

Result: **TP 2, FP 3, FN 2 → precision 0.40, recall 0.50.**
A human says: extraction got 4/4 real items and hallucinated 1 → true
precision ≈ 0.80, recall = 1.0.

**Finding 1:** lexical Jaccard badly under-credits paraphrase and abbreviation.
This is the strongest argument for embeddings. If we must ship lexical, it has
to be `token_set_ratio` + stemming, not raw Jaccard.

### 5.2 IB4003 — 8 gold items (merge / split test)

Gold (abbreviated): G1 walls-and-doors feasibility · G2 Gisella-with-school-admin
· G3 members form their own seating preferences · G4 members poll others for
preferences · G5 build a seating proposal · G6 merge proposal with another
team's · G7 ask Maggie & Pierrette about preferences · G8 meet next Tuesday 2pm.

Extracted (hypothetical "merging" extractor): E1 walls feasibility · E2 Gisella
question · **E3 "team members to form and collect seating preferences"
(merges G3 + G4)** · E4 build seating proposal · E5 merge with other team ·
E6 ask Maggie & Pierrette · E7 next meeting Tuesday 2pm.

Matching: E1–G1, E2–G2, E4–G5, E5–G6, E6–G7, E7–G8 all match cleanly (~0.6–0.9).
E3 competes with both G3 and G4; one-to-one gives E3 to whichever Jaccard is
higher (say G3), leaving **G4 unmatched**.

`len(G) = 8`, `len(P) = 7`. Matched pairs = 7 → **TP 7, FP 0, FN 1 (G4) →
precision 7/7 = 1.00, recall 7/8 = 0.875.**

**Finding 2:** the merge shows up as a **recall miss**, not hidden. One-to-one
matching works as intended. Keep it.

### 5.3 ES2002a — 3 gold items (boilerplate / cross-match test)

Gold: G1 "the industrial designer will work on the working design of the
remote" · G2 "the user interface designer will work on the technical functions
of the remote" · G3 "the marketing executive will work on what requirements the
remote has to fulfill".

These share heavy boilerplate ("… will work on … the remote"). Extracted:
E1 "industrial designer: work on the working design" · E2 "UI designer: work on
technical functions of the remote" · E3 "marketing exec: define product
requirements".

- G1–E1 ≈ 0.83 → TP. G2–E2 ≈ 0.6 → TP. G3–E3 ≈ 0.35 → **FN + FP**.
- **Cross-pair check:** G1–E2 ≈ 0.33 (shared: designer, work, remote). At
  `τ = 0.5` no wrong match. **At `τ = 0.35` G1 would pair with E2** — the wrong
  owner, the wrong task.

**Finding 3:** boilerplate-heavy meetings (ES/TS "work on the X concept") make
low thresholds dangerous — the rule starts pairing the wrong sentences. **τ
must not drop below ~0.45 for a lexical rule.**

### 5.4 IS1008d & TS3003b — pronoun / context note

IS1008d G2 is "**He** will also present the new promotion plan to management" —
the subject is an unresolved pronoun (it means the Project Manager, from G1).
TS3003b items are two-clause ("… user interface concept **and trend-watching**").

**Finding 4:** this is a *harness input* problem, not a matching problem — the
gold sentence itself is not self-contained. How AMI sentences get fed in
(pronoun resolution, splitting compound items) is tracked separately from the
matching rule. Flag it; don't try to fix it in `similarity`.

### 5.5 Summary of the sketch

| # | finding | consequence for the design |
|---|---|---|
| 1 | lexical Jaccard under-credits paraphrase/abbreviation (0.40/0.50 measured vs 0.80/1.0 real) | prefer embeddings; lexical fallback must use `token_set_ratio` + stemming |
| 2 | one-to-one surfaces merges as a recall miss (IB4003: recall 0.875, no false FP) | keep one-to-one matching |
| 3 | boilerplate meetings cross-match at low τ | `τ >= ~0.45` for lexical; never tune τ downward to raise recall |
| 4 | some gold sentences aren't self-contained (pronouns, compound items) | separate concern — harness input, not `similarity` |

---

## 6. Threshold — reasoning before results (issue step 9)

**"Choose before results" means:** before seeing any harness precision/recall
number. Sanity-checking `τ` against the §5 hand-labelled pairs is a *design
check* on a ~20-pair labelled dev set — not tuning against the metric. Tuning
`τ` after seeing the harness output is the failure this rule exists to prevent.

### Lexical backend — `τ = 0.60`, LOCKED

`similarity = token_set_ratio(a, b) / 100`.

Reasoning, entirely from the §5 sketch and the mechanics — no harness run:

1. `token_set_ratio` already normalises word order and dedupes tokens, so the
   score is "share of the token *set* in common", not raw string overlap.
2. In §5.3, boilerplate cross-pairs that are **not** the same item (G1×E2 —
   different owner, different task) sit at ~0.33–0.40. `τ` must clear that band
   with margin.
3. In §5.1, `τ = 0.50` put a real same-item pair (G1–E1) exactly on the line —
   too fragile.
4. Genuine same-item pairs that a human accepts land ≳ 0.60 once `token_set`'s
   length-tolerance is doing its job (it rescues the "UI" vs "user interface"
   and long-vs-short cases that killed raw Jaccard in §5.1).
5. `0.60` sits above the noise band (2) with room, and below the accept band (4).

If the §5 numbers redone with code (an Open TODO) contradict this, `τ` is
re-derived **from those pairs**, still before any harness run, and the reasoning
here is rewritten.

**Redone with code, 2026-10-25 (#178) — `τ = 0.60` kept, reasoning point 2
corrected.** Before any real extraction went through the eval, every gold ×
extracted pair of §5.1 (IS1008b) and §5.3 (ES2002a) was scored with
`similarity.lexical_similarity` (29 pairs: 7 the same task, 22 not, labels as
written in §5):

- **Point 2 was wrong.** Different-task pairs from the same meeting do not sit at
  ~0.33–0.40; with `token_set_ratio` they reach **0.72** (ES2002a G1–E2), and 8
  of the 22 score ≥ 0.60. The meetings repeat the same words ("designer",
  "present", "concept", "work on … the remote"), and `token_set_ratio` credits
  them. The hand numbers were Jaccard and approximate.
- Same-task pairs score **0.69** and up (lowest ES2002a G3–E3; the paraphrase
  G4–E4 scores 0.87). So no single `τ` separates the two groups pair by pair:
  0.70 drops G3–E3 and still lets G1–E2 through.
- **Why 0.60 holds anyway:** the eval matches one-to-one, highest score first,
  and in both meetings each true pair outscores every wrong pair that shares a
  side with it. At 0.60 the matching returns exactly the hand-labelled pairs —
  also in each of the 7 runs with one true extracted item removed (0 wrong
  matches, 0 true pairs lost). Pinned in `tests/test_similarity_threshold.py`.
- **Stemming (Finding 1) is not needed** on these pairs: every same-task pair
  already clears 0.60 without it.
- **Remaining risk:** a wrong match needs the true partner to be missing *and* an
  unmatched extracted item that happens to score ≥ 0.60 against the orphaned
  gold sentence — most likely in a meeting with repeated wording. The real run
  therefore reviews every matched pair scoring **0.60–0.75** by hand (#178 step
  4).
- **Caveat:** 29 pairs, 7 positive, and the extracted side is invented. This
  shows 0.60 is reasonable; the hand review on real output is the real test.

### Embedding backend — `τ = 0.70`, LOCKED 2026-10-28

The method: take the §5 accept/reject pairs as a labelled dev set, pick the `τ`
that best separates accept from reject for her model, freeze it. Never adjusted
against precision/recall output.

**Set 2026-10-28**, with `mind.embeddings.similarity` (Ayala; cosine over
bge-small-en-v1.5, clamped to [0, 1]) as `similarity.embedding_similarity`, by
`scripts/derive_embedding_threshold.py` on the 29 pairs in
`threshold_pairs.py`, before any real output was scored with this backend:

- Same-task pairs score **0.751–0.958**, different-task pairs **0.507–0.793**.
  As with the lexical backend, no `τ` separates them pair by pair: two wrong
  pairs (ES2002a G1–E2 0.793, IS1008b G2–E1 0.789) outscore three true ones
  (0.774, 0.762, 0.751).
- **"Best separates" is read through the matching, as for lexical:** every `τ`
  from **0.643 to 0.750** makes one-to-one matching return exactly the
  hand-labelled pairs, also with each true extracted item left out. `τ` is the
  middle, **0.70**.
- **Deviation, recorded:** the script's first rule chose `τ` pair by pair (the
  most pairs on the right side). It gave 0.83, which drops the three weak true
  pairs and fails 8 of the 9 matching checks. The rule was changed to the
  matching criterion the lexical `τ` was already held to, on the §5 pairs only;
  no real-run number was seen with this backend before the change.
- Pinned in `tests/test_similarity_threshold.py` (skipped where the model is not
  downloaded).

### Lock discipline

Once set, `τ` (per backend) does not move without a dated deviation note in this
file — same rule as the persona set and the ranking metric.

---

## 7. Why this is approximate — say it plainly (issue step 6)

This eval compares free-text against free-text. Every number it produces
depends on a `similarity` function and a threshold that are judgement calls,
not ground truth:

- A "true positive" means *our rule* decided two sentences are the same
  commitment. A human might disagree at the margin.
- Precision/recall here are **not** exact. A 0.82 and a 0.85 from two extraction
  models are only comparable because they were scored with the *same frozen*
  `similarity` + `τ`; the absolute value carries an error bar we do not
  quantify.
- AMI's own annotation is one annotator's reading of the meeting. It is a
  strong reference, not an oracle.

The eval's job is to catch *regressions and large differences* between
extraction approaches on a fixed reference — not to certify an absolute
extraction quality.

---

## Meeting IDs — agree with Yonit (issue step 7)

The eval is a **join between two lists** for each meeting:

| left — ground truth (this eval owns) | right — predictions (pipeline produces) |
|---|---|
| action items loaded straight from `abstractive/IS1008b.abssumm.xml` | the items extracted from that meeting's transcript |
| key: `IS1008b` — the `.abssumm.xml` filename stem | key: the `item_key` the eval passed in with that transcript |

The join only works if the same string appears on both sides.

> **Revised 2026-10-12** (#233 / PR #338, agreed with the mentor). An earlier
> version of this section planned the right-hand key as `source_ref`, set by the
> corpus loader and "only copied through" by extraction. That does not hold for
> the pipeline as built, see below. **Answer to #233 step 1 ("which field carries
> the meeting id"): none of the pipeline's output fields — the eval holds it.**

### The gold key (left side)

The AMI meeting code, **exactly as the `abssumm.xml` filename stem** — mixed
case, not uppercased: `IB4003`, `ES2002a`, `IS1008b`, `TS3003d`. (The trailing
letter on scenario meetings is lowercase — `IS1008b`, never `IS1008B`.) This
eval controls this side; no coordination needed for it.

### Why not `source_ref`

`source_ref` (on `ExtractedFields` and `TaskCreationRequested`) is a pointer back
to the original item, and neither value it can hold is the AMI meeting code:

- **Today** extraction reads it from *inside the content* — the LLM is asked for
  a message id, URL or ticket key that appears in the text — and
  `extraction/grounding.py` drops it when the text does not contain it. A
  meeting transcript carries no such pointer, so for a meeting it is `None`.
- **Once the Bridge consumer passes `RawObjectEvent.source_ref` through** (#31),
  it becomes the source system's own identifier: a Zoom meeting uuid, a Slack
  `channel_id:ts`, an email Message-ID. Still not an AMI code — and the eval does
  not go through Bridge at all.

Asking extraction to overwrite it with the AMI code on the corpus path was
rejected: it would give the field a second meaning only the eval needs, against
the grounding check and against what Inbox uses it for (a link back to the
original).

### The prediction key (right side) — `item_key`, held by the eval

The eval is what sends each meeting's transcript to the extractor, so it already
knows which meeting a result belongs to. It passes the meeting code as the
extractor's `item_key` (`ExtractionItem(text, source_type, item_key="IS1008b")`)
and reads it back from the outcome:

- `ExtractionSuccess.item_key` and `ExtractionFailure.item_key` both carry it
  unchanged — documented on the pipeline's side (`extraction/README.md`, "A
  failed item can be found").
- **A failure counts as a meeting with zero extracted items** (all its gold items
  are missed), never as a skipped meeting — skipping would inflate the score
  silently. The run report shows failures on a separate line, with their
  `reason` (`provider_error` vs `invalid_output`), so a provider outage is not
  read as a quality drop (mentor's requirement; to be built with the per-meeting
  layer, §9 / #178).
- `item_key` is not on `TaskCreationRequested` (`assembly.py` does not copy it),
  so the eval reads the extractor's outcomes, before assembly. That is the right
  layer anyway: this eval measures extraction, not what is sent to Inbox.

The eval-side contract for one extracted item is `ExtractedItem`
(`extraction_contract.py`): `title` + optional `description`, nothing else.

### Several tasks per text (#690, was a known limitation)

`BasicExtractor.extract` used to return exactly one task per text, capping
recall per meeting at `1/n`. Since #690 it returns `ExtractionSuccess.tasks`, a
list of up to 15 tasks. The join is unchanged: the outcome keeps the `item_key`
of the text it came from, and the eval groups by it. Content past
`MAX_CONTENT_CHARS` (8000) is still truncated, which a long meeting can hit.

### Still open

- ~~**Who loads AMI transcripts**~~ — resolved by #784:
  `src/mind/ingestion/ami_transcripts.py` loads AMI's human transcripts as
  speaker turns. They are the manual transcripts, not ASR output, while #178
  step 1 asks for meetings "that went through ayala's ASR" — which of the two
  the real run uses is still to be decided.

---

## Scope — which meetings are in the eval slice — LOCKED (staged)

- **v1 slice:** the 3 meetings already in `ami.manifest.json` —
  **IB4003, IS1008b, IS1008d** (16 gold action items). Same meetings the persona
  real-slice uses, so one download / one manifest covers both harnesses, and
  §5's hand sketch already covers 2 of the 3. Enough to stand the harness up and
  get a real micro-F1.
- **Full slice:** all 133 non-empty meetings / 306 items — a later expansion,
  once v1 runs green and R2's extractor is producing output worth scoring at
  scale. Needs the manifest expanded and every meeting's transcript wired in.
- The gold loader reads whatever the manifest lists — going from v1 to full is a
  manifest edit, not a code change.

---

## 9. Reconciliation with the existing `extraction_precision_recall` — LOCKED

`metrics.extraction_precision_recall(predicted: list[str], ground_truth:
list[str])` already exists — exact set-equality (`len(pred_set & truth_set)`), a
placeholder from `michal/metrics-research`, 8 tests. §5 shows exact equality
scores ~0 TP on real AMI text, so it cannot be the extraction eval.

**Decision:**

- **New module `src/mind/evaluation/extraction_eval.py`** holds the real logic
  (§3): per-meeting one-to-one fuzzy matching, micro/macro P/R/F1, owner-match
  diagnostic. `metrics.py` stays the *ranking* harness's file (`top1_accuracy`).
- Entry point roughly:
  ```python
  def evaluate_extraction(
      predicted_by_meeting: dict[str, list[PredItem]],
      gold_by_meeting: dict[str, list[str]],
      *, similarity: Callable[[str, str], float], threshold: float,
  ) -> ExtractionReport   # per-meeting TP/FP/FN + micro & macro P/R/F1 + owner rate
  ```
- **`extraction_precision_recall` stays** as-is (don't break its tests / the
  `metrics-research.md` reference) but gets a one-line docstring note: *"exact
  string match only — a placeholder; the real extraction eval is
  `extraction_eval.evaluate_extraction`."* It is the `τ = 1.0`, identity-`similarity`
  special case of the new code; not deleted, not built on.
- **F1** is added by the new module (the old one returns only P/R). Micro-F1 is
  the CI headline.

**Implementation status (#177).** What landed is the matching core:
`evaluate_extraction(extracted, annotated, *, similarity, threshold) ->
ExtractionEvalReport` over one pair of string lists, with `placeholder_similarity`
(Jaccard) and `PLACEHOLDER_THRESHOLD = 0.5` standing in until a real backend
exists. The per-meeting `dict` signature above, micro/macro aggregation and the
owner-match diagnostic are still to build on top of it.

---

## Open items

**Blocking (external):**
- [x] `TODO(Ayala)` — reusable `similarity(a,b)->float`: `mind.embeddings.similarity`
      (on main 2026-10-28), cosine over bge-small-en-v1.5, [0, 1]. Backend wired,
      `τ = 0.70` (§6).
      If no within a few days → build the §4.2 lexical baseline.
- [x] ~~`TODO(Yonit)` — confirm corpus-loader `source_ref = records[].id`~~ —
      superseded: the meeting id travels as the extractor's `item_key`, held by
      the eval (§"Meeting IDs", revised 2026-10-12, #233 / PR #338).
- [x] `TODO(Yonit)` — confirm `title` + `description` (§3.3) is the right
      comparison text. Confirmed 2026-10-14 (#233 step 4).
- [x] An AMI transcript loader — `ingestion/ami_transcripts.py` (#784), human
      transcripts. Open: human transcript or ASR output for the #178 run.
- [x] #690 — extraction returns a list of tasks per text; per-meeting recall is
      no longer capped at `1/n`. Long meetings can still be truncated.

**Build-time (not blocking the design):**
- [ ] Redo the §5 Jaccard numbers with code once `similarity.py` exists — they
      are hand estimates; if they move `τ`, re-derive §6 before any harness run.
- [x] Implement `extraction_eval.py` per §3/§9 (core: #177) and add the docstring
      note to `metrics.extraction_precision_recall`. Still open: per-meeting
      grouping, micro/macro F1, the owner-match diagnostic.
- [x] The eval-side contract for an extracted item (`ExtractedItem`) and the
      entry point that enforces it (`evaluate_extracted_items`) — #233 / PR #338.
- [x] Per-meeting grouping by `item_key`, micro-F1, and a separate failures line
      (count + `reason`) in the run report — #178: `evaluate_meetings` →
      `ExtractionSliceReport` (micro + macro, `None` for undefined per §3.5), and
      `run_extraction_eval.slice_summary_lines`. The CLI still reads a single
      pair; reading a slice from a file comes with the real run.
- [ ] Wire the CI regression check to micro-F1 — #175.

**Housekeeping:**
- [x] Fold this note into `src/mind/evaluation/README.md` or keep standalone —
      kept standalone, with a summary section in the README (PR #258).
