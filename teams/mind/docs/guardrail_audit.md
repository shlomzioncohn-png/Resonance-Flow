# Guardrail audit (#488)

R1's week-8 audit. It checks two things:

- every ML-inference point in the pipeline attaches a confidence to its output, on every path and not only the happy path;
- on deliberately bad input, the pipeline fails gracefully instead of producing confident-looking output.

Each owner builds the confidence for her own component. This audit only checks that it is there. Nothing in another owner's component is changed here. Every gap is reported to its owner (see [Reported](#reported)).

Audited against `main` at `f22b8bc7`, 2026-10-19.

## 1. Confidence per inference point

| Inference | Owner | Confidence | Where |
|---|---|---|---|
| Extraction: title, description | R2 | Yes, on every task produced. `ExtractionFailure` carries none, but no task is produced either. | `extraction/extractor.py:189`, `extraction/confidence.py:181` |
| Effort estimate | R2 (#352) | Yes, whenever an estimate is returned: 0.9 when stated, 0.4 when inferred. Not calibrated yet. Minutes without a basis are forced to inferred, and a basis without minutes is dropped, so an estimate never leaves without a confidence. | `confidence.py:243`, `assembly.py:83`, `models.py:293-308` |
| Dependency edges | R3 (#476) | **Not yet.** Results carry only raw cosine `similarity`. #476 (required) has not started. | `embeddings/dependency_resolution.py:45` |
| Related-task search | R3 (#476) | **Not yet.** Same as above. | `embeddings/related_tasks.py:22` |
| OCR, per word | R4 (#449) | Yes, passed through from tesseract (0-100): `mean_confidence` over every word, plus `low_confidence_words` and `low_confidence_count` below 60. This meets #449 step 2 ("at minimum a count or a list of them"). | `multimodal/ocr.py:182-183`, `330-332` |
| ASR, per segment | R4 (#478) | Yes, passed through from Whisper: `avg_logprob`, `compression_ratio` and `no_speech_prob` per segment, plus `confidence = exp(avg_logprob)` (0-1, the same number out of log space). | `multimodal/asr.py:210-229` |

**R4 and the team-lead decision.** R4 meets the requirement by passing through the native OCR and ASR scores rather than inventing a score on top of them. Both pipelines do exactly that, so neither is a gap. On failure, both return a typed reason with no text, so no text ever leaves without a confidence.

**Notes, not gaps:**

- OCR is on a 0-100 scale and ASR on 0-1. Anything that combines them has to normalise first.
- #449 records that about 20% of tesseract's misses are a different real word at full confidence. OCR confidence cannot catch that case by construction.

## 2. The adversarial set

The set lives in `eval/guardrails/cases.json`. It is separate from `eval/personas/`: the persona set tests ranking on realistic scenarios, and this set tests graceful failure on bad input. Each case's acceptable outcomes and threshold were committed before any case was run.

Outcomes:

- **graceful failure**: the component's own typed failure result.
- **low confidence**: output, but below the case's threshold.
- **partial**: confidence reacted (lower than the clean reference) but stayed above the threshold.
- **silent**: output at or above the threshold. This is confident-looking output from bad input.
- **crash**: an exception instead of a result.

| Case | Component | Input | Acceptable | Observed |
|---|---|---|---|---|
| `no_action_items` | extraction | Small talk, no action item | failure, or confidence < 0.5 | **silent**: invented task "Catch up next week", confidence **0.855** |
| `non_english_text` | extraction | A real task in Hebrew | failure, or confidence < 0.5 | **silent**: translated to "Send the budget report to finance", confidence **0.641** |
| `noise_scan` | OCR | Pixel noise, no text (seed 488) | `NO_TEXT_RECOGNIZED`, or mean < 60 | **low confidence**: 2 junk "words" (`oe`, `fe`), mean **23.39**, both flagged low |
| `non_english_audio` | ASR | The same Hebrew sentence, spoken | failure, or mean < 0.368 | **low confidence**: mean **0.309**, its one segment flagged low. The text is an invented English sentence, "We will see you in the next video." |
| `crosstalk_audio` | ASR | Clean fixture mixed with itself, 1 s delayed. The reference goes through the same gain and padding without the copy, so only the overlap differs. | lower than the reference | **partial**: mean **0.747 → 0.549**, but **no** segment flagged low. The text is garbled: "...to the Phrenae report by the Friday's team by F" |

OCR and ASR were run on R4's machine (tesseract and Whisper `base.en`), because R1's machine has neither. The tests are deterministic, with a fixed seed, mixing done in code and a pinned model hash.

Thresholds:

- **OCR and ASR** use their owner's own thresholds: 60 from #449, and `exp(-1.0) ≈ 0.368` from #478.
- **Extraction** has no such threshold. The team decided that no confidence threshold holds a task back (`extraction/README.md`). So 0.5 is an R1 audit marker ("less likely than not"). It is not a product threshold, and it was not chosen by looking at the scorer.

### What the extraction results mean

The extraction confidence starts at 0.95 and deducts for weaknesses in the answer:

- repair rounds;
- title words that are absent from the source text;
- a short title;
- a missing description;
- a missing or invented source reference.

It scores the **quality of an extraction**. It does not score whether the text contains a task at all, or whether the text is in the language the pipeline assumes. Both cases show this:

- **No action items.** Every word of "Catch up next week" appears in the source text, so title grounding deducts nothing. Only the missing description cost anything.
- **Hebrew.** The title grounding did react: the English title words are absent from the Hebrew text, which cost 25%. That was not enough to go below 0.5, and nothing flagged that the content was not English.

There is also no field for "no task here". `title` is required, and a placeholder title is sent back for repair (`models.py:366-371`). So a model that correctly declines ends as `ExtractionFailure(invalid_output)`, which looks the same as malformed JSON.

**Finding:** nothing in the pipeline signals "no task in this content" or "this content is not English". A task invented from small talk, or silently translated, reaches the contract's `confidence` field looking trustworthy.

### The contrast between R2 and R4

R4's confidence comes from the model itself (tesseract, Whisper), so it drops when the model is unsure, even when the model invents text. R2's confidence is a heuristic over the shape of the answer, so it stays high when the LLM is confident and wrong. Hence the split in this set: every R4 case lowered its confidence on bad input, and both extraction cases did not.

### What the OCR and ASR results mean

All three cases are within their acceptable outcomes, so there is no finding against R4. Two observations, written down for whoever consumes these results:

- **ASR can produce fluent invented text from out-of-scope audio.** On Hebrew speech, Whisper wrote a plausible English sentence that was never said. Its confidence correctly came out low (0.309 < 0.368) and the segment is flagged, so the signal is there. But the signal only helps if the consumer reads it. Nothing on `main` passes OCR or ASR confidence into extraction yet (the #349 consumer is not on `main`), so today a flagged transcript would reach extraction as ordinary text.
- **Cross-talk lowers confidence but does not trip the flag.** Mean confidence dropped by about a quarter, while the transcript was clearly garbled, yet no segment fell below 0.368. A consumer that only reads `low_confidence_segments` would treat this transcript as clean. Comparing `mean_confidence` against the source's usual level would catch it. Whether that is worth doing is R4's call, and it is not asserted here. The overlap is the same voice, likely easier than two real speakers.

## <a id="reported"></a>3. Reported

| Finding | Reported to | Where |
|---|---|---|
| No "no task" / "not English" signal in extraction. The fix would sit in R2's `extraction/`, but #360 is closed, so the decision goes to the mentor. | Mentor, for an owner decision (R2's component) | [#866 comment](https://github.com/KamaTechOrg/Resonance-Flow/pull/866#issuecomment-6268940893) |

Not reported, because nothing differs from a written requirement:

- R3's dependency-edge and related-task confidence: #476 requires it and has not started. That is pending work, not a deviation.
- OCR and ASR not running in CI: neither #449 nor #478 requires it. It is listed under limitations.

## 4. Limitations

- The extraction results come from **one** recorded run of one live model (provider, model and date are in `eval/guardrails/recorded/*.json`). The tests replay that run deterministically, but another run or model may answer differently.
- The cross-talk case overlaps the **same voice** with itself, which is likely easier than two different speakers.
- **CI installs neither tesseract nor Whisper**, so the OCR and ASR cases skip there, as R4's own pipeline tests do. They run locally only.
- **R3 is partial.** #476 had not started at the time of this audit, so dependency edges and related search have no confidence to audit and no adversarial case. Re-check when #476 lands.
- Out of scope: classification (tier, urgency) and owner confidence (#360 closed), duplicate detection (moved to reserve), and cost and latency (#484).

## 5. How to run

```
cd teams/mind
pytest tests/test_guardrails.py -v -rsx -s
```

- `-s` prints one `[guardrail]` line per case with the observed values, pass or fail.
- The extraction cases are `xfail(strict=True)`: the finding stays visible on every run, and the test turns into a failure the day a change makes it pass, so this report gets updated.
- The OCR cases need the tesseract binary. The ASR cases need the Whisper `base.en` weights in `models/whisper/base.en/` (see `models/README.md`).
- A failure caused by the local environment, such as a broken tesseract or weights that fail their pinned hash, skips the case rather than reporting a finding. The input is not what failed.
- To re-record the extraction answers against a live model, run `scripts/record_guardrail_responses.py`. It needs a key, so it is manual only.
