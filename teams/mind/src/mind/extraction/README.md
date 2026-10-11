# extraction

LLM-based extraction + classification.

- Infers tasks that were never logged anywhere (the ML path, as opposed to Bridge's deterministic ETL path).
- Produces output conforming to the canonical `Task` schema (owned by Inbox — see MIND-01-System-Overview.md §2 for the schema sketch).
- Classification: urgency/importance, duration estimation.
- Every inferred task carries `confidence` + `extraction_provenance`.

## Basic field extraction (#350)

Turns the plain text handed over by intake (#349) into the basic fields of a
proposed task. The output is a *proposal*: Inbox is the only team that creates
a task record and issues a real `task_id`.

| Field | Where it comes from |
|---|---|
| `title`, `description`, `source_ref`, `has_dependencies` | the LLM, validated against `ExtractedFields` |
| `source_type` | carried forward from the intake event — never asked of the LLM |
| `confidence` | computed locally by `confidence.py` — never asked of the LLM |

English only: the pipeline assumes English content (confirmed by Kayvan), and
the prompts and the stopword list are written on that assumption.

### How `confidence` is computed

Confidence starts at `BASE_CONFIDENCE` (0.95 — an ML-inferred proposal is
never a certainty) and every weakness observed takes a share of what is left:

    confidence = BASE * (1 - w1) * (1 - w2) * ...

The combination is multiplicative, not a subtraction of points, because the
signals are correlated: a thin source fires `short_title`,
`missing_description` and `missing_source_ref` at once, and those three are
one underlying cause, not three independent ones. Multiplying gives each
additional signal a smaller cost in points, so severity drives the score
rather than sheer count, while adding a signal still always lowers it.

| Signal | Weight (share of remaining confidence) | Why it is evidence |
|---|---|---|
| `repair_attempts` | 0.20 per round, compounded | The model needed another round to honour the schema |
| `title_not_grounded` | up to 0.25, scaled by the share of title words missing from the source | A title built from words that never appear in the text is the shape a hallucination takes |
| `short_title` | 0.10 | Fewer than two content words is not an informative title |
| `missing_description` | 0.10 | Required evidence the model did not find |
| `missing_source_ref` | 0.10 | Only for sources that normally carry a pointer (`REFERENCE_BEARING_SOURCES`); for `MANUAL` or `SCANNED_DOC` an absent ref is simply how the source works |

The result is clamped to `[0.05, 0.99]` so it always satisfies the contract's
range, though in practice the product bottoms out around 0.33 — the floor is a
guard rail, not a working value.

`score_confidence` returns the number *and* the named deductions behind it.
Each one records both its `weight` and the `effective` points it cost in
context, and the effective costs sum to the total drop, so a reviewer can
always ask where a score came from. `compute_confidence` is the thin wrapper
for callers that only need the float.

The weights are a starting heuristic, not a calibrated model. They were chosen
so that a first-attempt, fully-grounded, fully-populated extraction scores at
the base, and so that no combination of weak signals can drive an otherwise
usable item down to the floor.

### Providers

The extractor talks to one thing only: the `LLMProvider` protocol in
`providers/base.py`. Swapping DeepSeek for Gemini, or for the scripted fake
the tests use, changes configuration rather than code. A *third* provider
needs Shmuel's sign-off first.

`providers/deepseek.py` is the real client — DeepSeek is the primary provider.
It asks for `response_format: {"type": "json_object"}`, which removes most
malformed answers without making the repair loop optional: a provider can drop
JSON mode under load, and valid JSON can still fail our schema.

Credentials and endpoint come from the two files the Helm chart mounts, the
same way `mind.messaging.config` reads Kafka's:

```yaml
# config.yaml  -> components.mind.config
llm:
  deepseek:
    base_url: https://api.deepseek.com
    model: deepseek-chat
    timeout_seconds: 60
    max_output_tokens: 1024
# secrets.yaml -> components.mind.secret
llm:
  deepseek:
    api_key: sk-...
```

Pointing `model` at `deepseek-reasoner` is not a config change alone: that
model rejects `response_format` and ignores `temperature`.

#### What the client does with a failure

Translating failures happens at the edge, in the client, so the extractor's
retry policy stays in one place and never sees an HTTP status.

| What happened | Raised as | Retried |
|---|---|---|
| read/connect timeout | `LLMTimeout` | yes |
| connection refused, DNS, TLS | `LLMUnavailable` | yes |
| `429` | `LLMRateLimited`, carrying `Retry-After` when it is a plain number | yes |
| `5xx` | `LLMUnavailable` | yes |
| a body that is not the envelope we asked for | `LLMUnavailable` | yes |
| `401`, `403` (key), `402` (out of credit) | `LLMRejected` | no |
| `400`, `404`, `422`, any other `4xx` | `LLMRejected` | no |

Two of those are worth the words:

- an unreadable `2xx` body is a *provider* failure, not a bad answer. A proxy
  or a content filter in front of the API answers with its own HTML page, and
  handing that to the repair loop would spend every attempt asking a model to
  fix something it never wrote.
- `402` sits with the refusals. Retrying an empty account cannot help, and
  burning the attempt budget on it hides the real problem.

#### Cost

`providers/pricing.py` holds a copy of the published price list and turns the
token counts DeepSeek reports into dollars. `estimate_run_cost` is there to
size a large run *before* launching it, as #350 asks.

Two caveats are built in. An unknown model or a missing usage block returns
`None`, never `0.0` — unpriced is not free. And the table ignores cache-hit
input pricing and the off-peak discount, so every number is an upper bound.
The list goes stale: re-check it before quoting a figure anyone will act on.

#### Testing it

The suite drives the client over `httpx.MockTransport`, in-process. That is
not a shortcut: a live API will not produce a `402` or a rate limit on demand,
and CI should need no key and send no content to a provider.

A live smoke test is therefore a manual step — mount a key, point
`CONFIG_FILE_PATH`/`SECRETS_FILE_PATH` at local files, and run one item
through. Note that a filtered network may intercept `api.deepseek.com` and
answer with its own block page; an HTML body or a `418` is the filter, not
DeepSeek.

#### Gemini

`providers/gemini.py` is the comparison provider #350 names, and on a network
that filters `api.deepseek.com` it is the only one that can actually be run. It
is not `deepseek.py` with the URL swapped — Gemini takes the system prompt as
its own `system_instruction` field, returns the answer as a list of parts, and
puts its retry hint in the error body instead of a header.

```yaml
llm:
  gemini:
    model: gemini-3.6-flash
    thinking_budget: 0
```

Two settings carry a measurement behind them.

`thinking_budget: 0` is the difference between a usable client and an unusable
one: against the live API the same trivial prompt took ~1.4s with thinking off
and ~101s with it on. Extraction asks for four short fields and has nothing to
reason about. It is `int | None` because not every model accepts the field —
one returned 400 for a body the others took — and `None` omits it entirely.

The model is **pinned, not "latest"**. A baseline is comparable across weeks
only if the model behind it holds still. Note also that `ListModels` is not an
entitlement: a key issued today lists the previous generation but gets a 404
asking for it, so verify a model by calling it.

On the free tier `503 "experiencing high demand"` is an everyday event rather
than an edge case, and the newest model is the most congested. That is handled,
not avoided: a 503 is retryable and spends the transient-retry budget, never a
repair round.

Blocked answers are the one case this client has that DeepSeek's does not. A
`finishReason` of `SAFETY`/`BLOCKLIST`/`PROHIBITED_CONTENT`, or a `promptFeedback`
block with no candidates at all, is a refusal: the same prompt will be blocked
again, so it is not retried.

#### The live smoke test

`scripts/smoke_gemini.py` runs one item through the whole path — prompts,
provider, parsing, repair loop, confidence, contract — against the real API. It
is manual on purpose: it needs a key, it spends quota, and it must never run in
CI.

```bash
CONFIG_FILE_PATH=config.local.yaml SECRETS_FILE_PATH=secrets.local.yaml \
    python scripts/smoke_gemini.py
```

On a network that intercepts TLS, add `--os-trust-store` (needs
`pip install truststore`). Python verifies against certifi and will reject an
interception certificate that the browser and curl accept; the flag hands
verification to the OS, which already trusts it, rather than weakening it.

## Review fixes

Seven findings from the review of this PR, and what each one changed.

### Choosing a provider

`llm.provider` names one, and `providers/factory.build_provider` returns it:

```yaml
llm:
  provider: gemini
```

There is deliberately no default. DeepSeek is the primary provider #350 names
but is unreachable from some networks; defaulting to Gemini would be the code
inventing a policy nobody set. Either silent default hands somebody a provider
they did not pick, and the mistake surfaces much later as a timeout.

`build_provider` returns a `ManagedLLMProvider` — the protocol plus `close()`
and the context-manager methods. It is separate from `LLMProvider` on purpose:
the extractor needs `complete` and nothing else, which is what lets a scripted
fake with no socket satisfy it, while a caller that *built* a client has to
release it. The extractor is handed a provider; it never owns its lifetime.

### A config value that is present but empty

`api_key:` with nothing after it reads as None in YAML, and `str(None)` is the
string `"None"` — truthy, so the provider would start and 401 on every call.
Every read now goes through one helper that treats None, and whitespace, as
missing. `thinking_budget` is the one field read directly, because there `null`
is a real setting meaning "leave the field out of the request".

### A placeholder is not a title

`description` and `source_ref` may be nothing, so a placeholder there becomes
None. `title` is required, so it has nowhere to fall back to: `"N/A"` now fails
validation and goes back for repair. The validator raises its own message
rather than letting pydantic say "Input should be a valid string", which is
useless to a model that wrote a string. A repair round only pays for itself if
the model is told what was wrong.

### A truncated answer is refused, not repaired

Hitting the output cap used to log a warning and return the cut-off text. The
loop then opened a repair round under the same cap and got the same fragment —
three paid calls, and the item dropped at the end. Both clients now raise a
non-retryable error naming the setting to change. In Gemini the check runs
before the empty-answer check, because a cap tight enough to cut before the
first character would otherwise read as an empty completion, which is retryable.

### Every call is recorded, including the failures

`record_call` sat after the `try`, so a provider that raised was never
recorded — and the failures are the slowest calls we make, since a timeout
waits out the whole deadline. They are recorded now, under the provider's name
(no answer to read a model id from) and at cost 0.0 (no usage reported).

`except LLMError` rather than `finally`, so that a bug in a client or a
`KeyboardInterrupt` propagates without being counted as a model call.

Known gap, unchanged: `estimate_cost` returns None for an unpriced model, but
`record_call` only takes a float, so unpriced and free both land as 0.0. Fixing
that means widening `ModelCallRecord`, which is shared surface.

### A failed item can be found

Both outcomes carry `item_key`, and the log names it:

```
giving up on tenant-7/email/4471-body.eml (EMAIL) after 3 attempt(s): ...
```

It is the intake event's `object_key`, not its `correlation_id` — a source
body shares a correlation id with its attachments, so it cannot say *which* of
them failed. `extract_many` takes `ExtractionItem` values for the same reason:
a three-tuple of `(str, SourceType, str | None)` reads as a puzzle.

### A pointer the content never contained

`grounding.ground_source_ref` keeps `source_ref` only when the content really
carries it: quoted in the text, at least four characters, and shaped like an
identifier rather than like a word. `"Friday"` appears in plenty of emails and
is not a pointer.

It runs between parsing and scoring, the one place holding both the answer and
the text it was supposed to come from. The scorer is told what happened and
only reads — a module that scores should not also be changing what it measures.

The scoring inversion this fixes, on one answer and one source text:

| what the model returned | before | after |
|---|---|---|
| a pointer quoted from the content | 0.8708 | 0.8708 |
| an honest `null` | 0.7837 | 0.7837 |
| an invented URL | **0.8708** | **0.627**, and the value does not travel |

An invented pointer used to outscore an honest null, which is the opposite of
what the system prompt asks the model for.

**What this does not do.** It closes fabrication, not injection. The content is
written by whoever sent it, so a sender can put a link in their own message and
tell the model to use it; the value genuinely is in the content and passes
every check here. `test_a_link_the_content_itself_carries_is_still_kept` pins
that limit so nobody mistakes the check for more than it is. The real answer is
for `source_ref` to arrive from Bridge as event metadata — `RawObjectEvent`
carries no such field today, and asking for one is open with them. Until then,
this field says what the content claimed, not what is true.

## Classification at extraction time (#351)

Three more fields, asked for in the same call: `tier`, `urgency` and
`role_context`. All three are nullable, and that is the stance rather than a
convenience — the prompt tells the model that `null` is the correct answer
when the content does not make the value clear, because Chain's selection and
the segmented ranking both act on `tier`, and a wrong value there is worse
than a missing one.

`role_context` is read from the content alone, never from who sent the message
or who might be assigned it. A task can carry a role before it has an owner,
which is most obvious for the CEO persona: it has no task tool of its own and
reaches Mind only through unstructured content.

### The switch

`ExtractionConfig.classify` decides whether the three fields are asked for at
all. It is read in exactly two places inside `extract()` — which prompt is
built, and which schema the answer is validated against — because those are
one decision: a prompt asking for seven fields and a schema accepting four
would fail every answer it received.

| | `classify=False` | `classify=True` |
|---|---|---|
| system prompt | `system.md` | `system.md` + `classification.md` |
| schema | `ExtractedFields` | `ExtractedFieldsWithClassification` |

With the switch off the prompt never mentions `tier` at all. That is
deliberate: a model that has heard the word sometimes volunteers the field,
and `extra="forbid"` would then reject an otherwise good answer.

The combined model inherits from both halves, so it *is* an `ExtractedFields`.
That is what lets `confidence.py`, `extractor.py` and every other caller stay
untouched by the switch, and it is pinned by a test.

The switch exists so that both configurations can be run from one build. The
question it is there to answer — does asking for seven fields degrade the four
basic ones? — needs the two run over the same inputs, and without it that
comparison would mean checking out an old commit mid-run.

### An unusable classification becomes null, not a repair round

A value outside the permitted vocabulary (`"critical"` for a tier), or an
urgency outside 1-5, is recorded as `None` and logged — it does not fail
validation.

This is the one place that departs from the "forgive formatting, never
content" line, and the asymmetry behind it is the point. `title` is required
by the contract, so a bad one is a real failure and still earns a repair
round. These three are optional by design, and a model that reached outside
the vocabulary has demonstrated exactly the uncertainty that `null` exists to
express. Measured on the same answer, before and after:

| | invented `tier`, repaired | invented `tier`, nulled |
|---|---|---|
| outcome | `ExtractionFailure` | `ExtractionSuccess` |
| provider calls | 3 | 1 |
| title, description, source_ref | discarded | kept |
| the other two classification fields | discarded | kept |

Losing a good title, description and source_ref — and paying three calls for
it — because of one word in an optional field was the wrong trade.

Urgency is **not** clamped into range. A `7` might mean "past the top of your
scale", or it might mean the model scored on 1 to 10, where 7 is middling.
Those are indistinguishable from here, and the costs are not symmetric: a
missing urgency is visibly missing, while a clamped one is a plausible-looking
number that quietly inflates every ranking downstream.

Every dropped value is logged at WARNING with the text the model actually
wrote. Without that, a model inventing values all day would be
indistinguishable from an honest one declining to guess — both end in `null`.

### Why `confidence` does not change

`confidence` is the confidence in the *extraction*, and it is left exactly as
#350 built it. An empty classification field costs nothing: the signals in
`confidence.py` name `description` and `source_ref` explicitly, and the same
answer scores identically whether the three fields are absent or present and
empty.

The contract itself argues for this. It carries `effort_estimate_confidence`
as a separate field, so when it wants confidence about one field it says so.
There is no `tier_confidence`, and inventing a meaning for the shared one
would misread it for Inbox.

One second-order effect is worth knowing rather than engineering around: if a
classification problem does cause a repair round, the `repair_attempts`
penalty fires and lowers the score for the extraction as a whole. After the
change above that is now rare — an unusable value no longer causes a repair —
but it is not impossible, and a low score with no obvious cause in the four
basic fields may have come from here.

### What was measured, and what was not

Run against the live API on 2026-10-07 (`gemini-3.6-flash`), over six tasks
from the **dev** half of the persona split — one per role, never the holdout,
and never used as prompt examples.

**Does asking for seven fields degrade the four?** No difference found. The
same six tasks, run with the switch off and then on:

| | title | `source_ref` | `has_dependencies` | mean confidence |
|---|---|---|---|---|
| off vs on | 6/6 identical | 6/6 | 6/6 | 0.855 vs 0.855 |

All six titles came back byte-identical. Neither configuration needed a repair
round; the two extra provider calls in the classified run were 503 retries.

Three caveats, all of them load-bearing:

- the noise floor — the same configuration run twice — rests on **two** items,
  not six: the free tier's daily quota ran out part-way through the third run.
  So "identical" is not fully separable from "deterministic at temperature 0".
- n=6, one model, one run per configuration. This is a signal, not a number to
  quote.
- it does not test **one call for seven fields against two separate calls**,
  which is the comparison #351 actually names. That one cannot be run at all
  yet: no split path exists. The pieces for it do — `classification.md` is
  already its own prompt and `ClassificationFields` its own model — so
  building it is wiring rather than rewriting, but until it is built the
  combined prompt is the issue's recommended starting point rather than a
  measured winner.

**Is the classification any good?** Unknown, and not claimed. A six-task spot
check against the labelled dev scenarios agreed on `tier` three times out of
six, with every disagreement a borderline judgement (a departmental newsletter
labelled strategic, read as operational) rather than a nonsense answer. That
points at the tier definitions in `classification.md` differing from the ones
the scenario authors used — a prompt-calibration task to do against
`MIND-05-Task-Flow-Explained.md` §3, not a code defect.

`role_context` came back null in three of six, each time on a task whose text
genuinely names no role. Worth knowing before anyone treats the persona labels
as ground truth for it: the label is the persona whose inbox the task sits in,
while this field is required to come from the content alone. The two are not
the same quantity, and a null may be the honest answer where a label exists.
