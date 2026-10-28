# Guardrail audit (#488)

R1's week-8 audit. It checks two things:

- every ML-inference point in the pipeline attaches a confidence to its output, on every path and not only the happy path;
- on deliberately bad input, the pipeline fails gracefully instead of producing confident-looking output.

Each owner builds the confidence for her own component. This audit only checks that it is there. Nothing in another owner's component is changed here. Every gap is reported to its owner (see [Reported](#reported)).

Audited against `main` at `f22b8bc7`, 2026-10-19. Updated after mentor review on PR #866. Re-checked against `main` at `6de5b86f`, 2026-10-26, after R2's #690 (a list of tasks per text) and R3's dependency publishing (#355) landed.

## 1. Confidence per inference point

| Inference | Owner | Confidence | Where |
|---|---|---|---|
| Extraction: title, description | R2 | Yes, on every task produced. Since #690 an answer is a list of tasks, and each `ExtractedTask` carries its own confidence. An empty list and `ExtractionFailure` carry none, but no task is produced either. | `extraction/extractor.py:79-88` (`ExtractedTask`), `extraction/confidence.py:184` |
| Classification: tier, urgency | R2 (#360) | **No.** The values are nullable (the model returns `null` when unsure), but there is no per-field confidence, and the overall `confidence` ignores them. #360 is open and deferred. | `extraction/models.py:183-188`; no field in `contracts/task_creation_requested.py` |
| Owner candidates | R2 (#360) | **No.** Owner is not extracted at all: `suggested_owner_user_id`, `suggested_owner_confidence` and `candidate_assignees` exist in the contract but are always empty. #360 is open and deferred. | `contracts/task_creation_requested.py:72-86`; nothing in `extraction/` sets them |
| Effort estimate | R2 (#352) | Yes, whenever an estimate is returned: 0.9 when stated, 0.4 when inferred. Not calibrated yet. Minutes without a basis are forced to inferred, and a basis without minutes is dropped, so an estimate never leaves without a confidence. | `confidence.py:243`, `assembly.py:83`, `models.py:293-308` |
| Duplicate detection | R3 (#476) | **No.** `find_duplicate` returns the best match above a 0.761 threshold as a `SearchResult`, which carries only raw cosine `similarity`. Duplicate confidence is part of #476, and that part is in reserve. | `embeddings/duplicate_detection.py:10-22`, `embeddings/base.py:15-24`, `config.py` (`duplicate_similarity_threshold`) |
| Dependency edges | R3 (#476) | **A field, but not yet a confidence.** Since #355, each published `DependencyEdge` carries `confidence`, but it is the raw cosine `similarity` copied in as is. #476 asks for the opposite: a confidence derived from the distance to the threshold, not raw closeness. #476 is still open. | `dependencies/publisher.py:101-102`, `contracts/task_dependencies_proposed.py:17` |
| Related-task search | R3 (#476) | **Not yet.** Same as above. | `embeddings/related_tasks.py:22` |
| OCR, per word | R4 (#449) | Yes, passed through from tesseract (0-100): `mean_confidence` over every word, plus `low_confidence_words` and `low_confidence_count` below 60. This meets #449 step 2 ("at minimum a count or a list of them"). | `multimodal/ocr.py:182-183`, `330-332` |
| ASR, per segment | R4 (#478) | Yes, passed through from Whisper: `avg_logprob`, `compression_ratio` and `no_speech_prob` per segment, plus `confidence = exp(avg_logprob)` (0-1, the same number out of log space). | `multimodal/asr.py:210-229` |

**R4 and the team-lead decision.** R4 meets the requirement by passing through the native OCR and ASR scores rather than inventing a score on top of them. Both pipelines do exactly that, so neither is a gap. On failure, both return a typed reason with no text, so no text ever leaves without a confidence.

**Notes, not gaps:**

- OCR is on a 0-100 scale and ASR on 0-1. Anything that combines them has to normalise first.
- #449 records that about 20% of tesseract's misses are a different real word at full confidence. OCR confidence cannot catch that case by construction.

## 2. The adversarial set

The set lives in `eval/guardrails/cases.json`. It is separate from `eval/personas/`: the persona set tests ranking on realistic scenarios, and this set tests graceful failure on bad input. Each case's acceptable outcomes and threshold were committed before any case was run. The one change made afterwards is recorded in that case's `deviation` field.

Outcomes:

- **graceful failure**: the component's own typed failure result.
- **low confidence**: output, but below the case's threshold.
- **partial**: confidence reacted (lower than the clean reference) but stayed above the threshold.
- **silent**: output at or above the threshold. This is confident-looking output from bad input.
- **crash**: an exception instead of a result.

| Case | Component | Input | Acceptable | Observed |
|---|---|---|---|---|
| `no_action_items` | extraction | Small talk, no action item | failure, or confidence < 0.5 | **graceful** since #690: an empty task list. Before #690 it was **silent**: invented task "Catch up next week", confidence **0.855**. |
| `noise_scan` | OCR | Pixel noise, no text (seed 488) | `NO_TEXT_RECOGNIZED`, or mean < 60 | **low confidence**: 2 junk "words" (`oe`, `fe`), mean **23.39**, both flagged low |
| `crosstalk_audio` | ASR | Clean fixture mixed with itself, 1 s delayed. The reference goes through the same gain and padding without the copy, so only the overlap differs. | lower than the reference | **partial**: mean **0.742 → 0.543**, but **no** segment flagged low. The text is garbled: "...to the Thunang report by the Friday's team by F" |

The two non-English cases are run as well, but they are accepted limitations rather than findings. See [Accepted known limitations](#accepted).

OCR and ASR were run on R4's machine (tesseract and Whisper `base.en`), because R1's machine has neither. The tests are deterministic, with a fixed seed, mixing done in code and a pinned model hash. The numbers above come from the run on `3a800780` (2026-10-26), after `asr.py` changed on `main`. The first run (2026-10-20) gave cross-talk 0.747 → 0.549, and noise was the same.

Thresholds:

- **OCR and ASR** use their owner's own thresholds: 60 from #449, and `exp(-1.0) ≈ 0.368` from #478.
- **Extraction** has no such threshold. The team decided that no confidence threshold holds a task back (`extraction/README.md`). So 0.5 is an R1 audit marker ("less likely than not"). It is not a product threshold, and it was not chosen by looking at the scorer.

### What the extraction result means

The extraction confidence starts at 0.95 and deducts for weaknesses in the answer:

- repair rounds;
- title words that are absent from the source text;
- a short title;
- a missing description;
- a missing or invented source reference.

It scores the **quality of an extraction**. It does not score whether the text contains a task at all.

On the first recording (2026-10-19), the extractor returned exactly one task per text, and there was no way to answer "no task here". Small talk became "Catch up next week" at 0.855. Every word of that title appears in the source, so title grounding deducted nothing; only the missing description cost anything. This was reported as a finding for R2's #690.

#690 changed the answer to a list of tasks, and an empty list now means "no task here". On the re-recording against the same model (2026-10-26), small talk came back as an empty list. **The finding is resolved by #690.** The case now passes as an ordinary test, so a regression would fail it.

The scorer itself is unchanged. If the model does propose a task from small talk, its confidence still will not say so. The guard is now the model's ability to return an empty list, not the score.

### The contrast between R2 and R4

R4's confidence comes from the model itself (tesseract, Whisper), so it drops when the model is unsure, even when the model invents text. R2's confidence is a heuristic over the shape of the answer, so it stays high when the LLM is confident and wrong. On the first recording that showed up as the invented task. Since #690, the model's empty list is the guard instead.

### What the OCR and ASR results mean

Both cases are within their acceptable outcomes, so there is no finding against R4. One observation, written down for whoever consumes these results:

- **Cross-talk lowers confidence but does not trip the flag.** Mean confidence dropped by about a quarter, while the transcript was clearly garbled, yet no segment fell below 0.368. A consumer that only reads `low_confidence_segments` would treat this transcript as clean. Comparing `mean_confidence` against the source's usual level would catch it. Whether that is worth doing is R4's call, and it is not asserted here. The overlap is the same voice, likely easier than two real speakers.

Nothing on `main` calls OCR or ASR from the pipeline yet, and nothing outside `multimodal/` reads their confidence. So today even a flagged transcript would reach extraction as ordinary text.

## <a id="accepted"></a>3. Accepted known limitations

**Non-English content.** The program is English-only by team-lead decision, so non-English input is out of scope. It is documented here, not reported as a finding to fix. Both cases still run, so a change in this behaviour shows up:

| Case | Component | Observed | Test |
|---|---|---|---|
| `non_english_text` | extraction | A Hebrew task was silently translated to "Send the budget report to finance", at confidence **0.641**. The title grounding did react (English words absent from the Hebrew text cost 25%), but nothing flagged the language, and the prompt's "never translate the content" was not followed. | `xfail(strict=True)`, reason "accepted limitation" |
| `non_english_audio` | ASR | Whisper (`base.en`, pinned to English) writes an invented English sentence. On `3a800780` it wrote "Thank you very much for watching, and I will see you in the next video." at mean **0.370**, just above R4's 0.368, so `low_confidence_count=0` and nothing marks it as suspect. Before `asr.py` changed on `main`, the same recording gave "We will see you in the next video." at 0.309, and that one was flagged. The flag sits right at the edge for this input, and a small code change moved the result across it. Spotted by R4 on the re-run. | Asserts only that it does not crash, and prints the values. The threshold check was dropped after the first run, by mentor review (see `deviation` in `cases.json`). |

## <a id="reported"></a>4. Reported

| Finding | Reported to | Where |
|---|---|---|
| Extraction had no "no task here" outcome, so small talk became a confident task | R2, #690 | **Resolved by #690**: re-recorded 2026-10-26, small talk now returns an empty task list |

Not reported, because nothing differs from a written requirement:

- Classification and owner confidence: #360 covers it and is deferred.
- Duplicate-detection confidence: part of #476, in reserve.
- Dependency-edge confidence: since #355 the field is the raw cosine similarity copied in, which is exactly what #476 says to avoid. #476 is open and covers it, so this is pending work under R3's own issue, not a new report.
- Related-task confidence: #476 requires it and has not started. That is pending work, not a deviation.
- Non-English content: an accepted limitation (see above).
- OCR and ASR not running in CI: neither #449 nor #478 requires it.

## 5. Limitations of this audit

- The extraction results come from **one** recorded run of one live model (provider, model and date are in `eval/guardrails/recorded/*.json`). The tests replay that run deterministically, but another run or model may answer differently.
- The cross-talk case overlaps the **same voice** with itself, which is likely easier than two different speakers.
- **CI installs neither tesseract nor Whisper**, so the OCR and ASR cases skip there, as R4's own pipeline tests do. They run locally only.
- **R3 is partial.** #476 has not started, so duplicate detection and related search have no confidence to audit, and dependency edges carry raw similarity in place of one. There is no adversarial case for R3. Re-check when #476 lands.
- Out of scope: cost and latency (#484).

## 6. How to run

```
cd teams/mind
pytest tests/test_guardrails.py -v -rsx -s
```

- `-s` prints one `[guardrail]` line per case with the observed values, pass or fail.
- `non_english_text` is `xfail(strict=True)`: the accepted limitation stays visible on every run, and the test turns into a failure the day a change makes it pass, so this report gets updated. `no_action_items` passes since #690.
- The OCR cases need the tesseract binary. The ASR cases need the Whisper `base.en` weights in `models/whisper/base.en/` (see `models/README.md`).
- A failure caused by the local environment, such as a broken tesseract or weights that fail their pinned hash, skips the case rather than reporting a finding. The input is not what failed.
- To re-record the extraction answers against a live model, run `scripts/record_guardrail_responses.py`. It needs a key, so it is manual only.
