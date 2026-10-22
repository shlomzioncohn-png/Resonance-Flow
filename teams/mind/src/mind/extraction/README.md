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
| `source_ref` **sent to Inbox** | Bridge's, never the LLM's — see below and `task_creation/README.md` (#353) |
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

`providers/pricing.py` holds copies of DeepSeek's and Google's published price
lists and turns the token counts a provider reports into dollars.
`estimate_run_cost` is there to size a large run *before* launching it, as #350
asks.

Gemini is priced since the local key moved to the paid tier (2026-10-13); on the
free tier its calls cost nothing and the limit was a daily quota. A model maps to
a price *history*, not one price: Google has announced that `gemini-3.6-flash`
doubles on 2027-01-01, and entering that now beats remembering it on the day. A
call is priced on its UTC date — Google's page names no timezone, so a call
within hours of a change may be priced a day off. `estimate_run_cost(..., on=)`
prices a planned run on the day it will happen.

Three caveats are built in. An unknown model or a missing usage block returns
`None`, never `0.0` — unpriced is not free. `gemini-3.5-flash` and
`gemini-3.7-flash` answer calls but have no price on Google's page, so they stay
unpriced. And the table ignores cached-input pricing and every discount, so each
number is an upper bound for the standard tier. The list goes stale: re-check it
before quoting a figure anyone will act on.

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
Not every model that accepts it obeys it either: `gemini-3.8-flash` still
thought 237 tokens for a one-word answer. Those tokens are billed as output, so
the client counts them into `completion_tokens`.

The model is **pinned, not "latest"**. A baseline is comparable across weeks
only if the model behind it holds still. Note also that `ListModels` is not an
entitlement: a key issued today lists the previous generation but gets a 404
asking for it, so verify a model by calling it.

On the free tier `503 "experiencing high demand"` was an everyday event rather
than an edge case, and the newest model the most congested. On the paid tier the
same evening, eighteen calls across six models came back 200 in under 2.5s, so
the 503s were the quota, not the models. It is handled either way, not avoided:
a 503 is retryable and spends the transient-retry budget, never a repair round.

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
waits out the whole deadline. They are recorded now, under the model id the
provider sends -- the same key as a success, so a model's failures and successes
share one row of the summary (#479) -- with `status=error` and a cost of `None`:
no usage was reported, so the cost is unknown, not zero. A success is recorded
under that same id but priced by the id the answer reports, the model billed.

`except LLMError` rather than `finally`, so that a bug in a client or a
`KeyboardInterrupt` propagates without being counted as a model call.

The gap this section used to list is closed (#479): `record_call` now takes
`cost=None`, so an unpriced model, a provider that reported no usage and a failed
call are all recorded as unpriced rather than as 0.0. Every call is also
recorded with `kind=llm` and its token counts, so the model-call summary
(`GET /debug/model-calls`, see `observability/README.md`) totals tokens and US
dollars per model and holds LLM calls to their duration and cost limits.

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
for `source_ref` to arrive from Bridge as event metadata — #31 added it as
`RawObjectEvent.source_ref`. Since #353 the `source_ref` Inbox receives is
Bridge's (or `bridge:<item id>`), passed to assembly by whoever holds the
event — the #349 consumer, once it exists — never this extracted value: a
pointer the content mentions is not the item's own id, and Inbox's UI builds a
link from it. The extracted field still feeds the confidence penalties above;
whether to stop asking the model for it at all is open.

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

### Where the tier definitions come from

Tier is a **time horizon and a reach of consequence**, not a rank or a kind of
activity. Two things in this repo fix that, and the prompt follows both:

- `scoring/segmented_model.TIER_RULES` separates the tiers by `scale_hours` —
  8, 72, 336 — and shifts the weight from time pressure to impact as the
  horizon lengthens. A task labelled one tier at extraction and scored under
  another tier's rule is worse than an unlabelled one.
- `eval/personas/README.md` says it from the other side, under "Persona !=
  Tier": a developer can hold a strategic task and a chief executive an
  operational one. The prompt says so outright, because a model asked to sort
  tasks by seniority will quietly do exactly that.

An earlier version of this prompt sorted tasks by the kind of work — doing it,
organising it, deciding it. That reads plausibly and is wrong: it has no
horizon in it at all, so nothing tied extraction to how the task would later
be scored.

### What was measured, and what was not

Run against the live API (`gemini-3.6-flash`), over six tasks from the **dev**
half of the persona split — one per role, never the holdout, and never used as
prompt examples.

**Does asking for seven fields degrade the four?** No difference found. The
same six tasks with the switch off and then on: 6/6 identical titles, 6/6
`source_ref`, 6/6 `has_dependencies`, mean confidence 0.855 either way. All
six titles came back byte-identical, and neither configuration needed a repair
round.

**Is the classification any good?** Partly, and the honest numbers are:

| | first version | with horizons, role descriptions and examples |
|---|---|---|
| `tier` | 3/6 | 3/6 |
| `role_context` | 3/6 | 4/6 |

Describing the roles worked: two tasks that came back `null` — the CEO one and
the field engineer one — are now right. One moved the wrong way, a quarterly
budget review read as `HIGHER_MANAGER` instead of `MANAGER`, which is a
defensible reading of a budget.

Rewriting the tier definitions around time horizons did **not** move that
number on this sample. The three disagreements are of three different kinds:

| task | label | model |
|---|---|---|
| why last night's backup skipped two sites | `TACTICAL` | `OPERATIONAL` |
| review the quarterly team budget before sign-off | `STRATEGIC` | `TACTICAL` |
| write the monthly department newsletter | `STRATEGIC` | `OPERATIONAL` |

The second looks like the model's mistake — a quarter is a strategic horizon.
The first is a genuine judgement call. **The third is worth asking about**: by
the definition this prompt now follows, and that `TIER_RULES` encodes, a
monthly newsletter is not a weeks-or-longer commitment whose effect outlives
the task. The label may be the thing that is off. Raised as a question with
R1 rather than treated as a result.

Caveats, all load-bearing:

- n = 6, one model, one run per configuration. A signal, not a number to
  quote, and six examples is far too few to tune a prompt against without
  simply fitting them.
- the noise floor — the same configuration run twice — rested on **two** items,
  because the free tier's daily quota ran out part-way through that run. The
  paid-key re-run below replaces it.
- **it does not test one call against two separate calls**, which is the
  comparison #351 actually names. That cannot be run yet: no split path
  exists. Until it is built, the combined prompt is the issue's recommended
  starting point rather than a measured winner.

#### Re-measured on the paid tier, over all of DEV

2026-10-13, `gemini-3.6-flash`, all 50 tasks of the 20 DEV scenarios, three
configurations (A without classification, B with it, A' = A again). 150 calls,
every one succeeded first time, one provider call per item, about $0.17.

| | titles | `source_ref` | `has_dependencies` | mean confidence |
|---|---|---|---|---|
| A vs A' (noise floor) | 48/50 | 50/50 | 50/50 | 0.854 / 0.853 |
| A vs B (off vs on) | 45/50 | 50/50 | 49/50 | 0.854 / 0.854 |

Asking for the extra fields does not measurably degrade the four. Two of the
five changed titles are tasks whose title also moves between A and A' (one of
them came back more abstract under B). The other three are wording: a dropped
article, a more specific noun, and one dropped time ("10:00").

From run B: `tier` agrees with the labels on 36/50. The miss is one-sided —
`STRATEGIC` comes back right on 6 of 15, otherwise `TACTICAL` (6) or
`OPERATIONAL` (3). `role_context` is 27/50, but 27 of the 31 it answers; the
other 19 are `null`, consistent with text that names no role. No prompt was
tuned against these numbers.

At the time of this run `eval_split.partition` refused the persona set: six
scenarios had been added after the split was frozen. The run took
`DEV_SCENARIO_IDS` as it then stood (the original 20), so the new six were in
neither half and were not read. They have since been placed in the split
(`yonit/mind-eval-split-535`), four to dev and two to holdout.

### Review fixes, closed in #352

Three findings from an automated review of #351 arrived after the PR was
approved, and were fixed on the #352 branch instead.

- **Urgency was read by its spelling, not its value.** `"--3"` and `"²"`
  passed an `isdigit()` check and then broke `int()`, which sent the answer to
  repair and, after three identical answers, discarded the item — title and
  all. The opposite way, `3.0`, ordinary JSON for three, became `null`. A
  quoted number is now read only when it is written plainly (`-?[0-9]+(\.[0-9]+)?`,
  ASCII digits only), and what decides is whether the value is whole. A test
  through `BasicExtractor` pins the cost: one provider call, title kept.
- **The examples in `classification.md` were not JSON.** They read
  `tier: "OPERATIONAL"   urgency: 5`, while the prompt demands a single JSON
  object, so a model imitating them would have written something the parser
  rejects. They are now whole answer objects, and a test parses every one
  against the response model and requires exactly its keys — which is what
  forced them to grow when #352 added two.

## Effort estimate (#352)

`effort_estimate_minutes` — how long the work takes — is asked for in the same
call and under the same `classify` switch as the three fields above. The two
new keys sit on `ClassificationFields`, and the instructions in
`classification.md`, under their own rules.

### What was deliberately not built

No clarification round, no waiting, no confidence threshold that holds a task
back. Kayvan settled this before the issue was written, and SPEC-R2 says the
same: a task is created either way, and a human can fill the estimate in later
with a normal edit in Inbox. A task with no estimate is a normal task; it only
drops out of "does this fit my free time" recommendations.

That makes the empty case the common one. Every one of the 91 tasks in the
persona set carries a labelled estimate, and **only one says anything about
duration in its text** — "the upload takes a few minutes", a unit of assumed
length and so `INFERRED`, on a task labelled 40 minutes. Most of what Mind sees
will reach Inbox with no estimate.

### Three answers, not two

| the content | minutes | basis | confidence |
|---|---|---|---|
| fixes how long, in units of fixed length: "should take about an hour" | 60 | `STATED` | high |
| fixes no duration, but the work is small and specific: "fix the typo on the login page" | a few | `INFERRED` | low |
| names a unit whose length must be assumed: "about two sprints" | the model's reading | `INFERRED` | low |
| gives no sense of size: "look into the billing issue" | `null` | `null` | `null` |

The prompt's definitions of the two bases are the one place this is decided;
an earlier separate rule about units contradicted them, and was folded in.

The issue says "never an invented number" and also, quoting Kayvan, "or mark
it low-confidence". The two `INFERRED` rows are how the two are read together: an
inference from what the work is, labelled as one, is not an invention. An
estimate with no grip on the size of the work is, and stays `null`.

### The model names the basis; the number is ours

The model is not asked how confident it is. Self-reported confidence clusters
near the top regardless of content and shifts between models, while "did the
text say this, or did you work it out?" is a question about the text, which a
model answers far better. `confidence.py` turns the basis into the contract's
`effort_estimate_confidence` (`EFFORT_CONFIDENCE`), the same way the overall
`confidence` comes from signals we observe rather than from the model.

The overall `confidence` is not touched. The contract gives effort a field of
its own precisely so that it does not have to be folded in.

`effort_estimate_basis` is internal. It never reaches Inbox; only the number
derived from it does.

### Toward a finer confidence

Two values is a coarse scale, and that is a known limitation rather than a
finished design. The two values live in `EFFORT_CONFIDENCE` and neither is
calibrated. The ordering between them is what the tests pin; the values are
expected to move, which is why they are not repeated here.

Two ways to make it finer, both measurable, neither built here because the
issue asks for nothing beyond the estimate itself:

- **Calibrate against the persona labels.** Since none of them fixes a
  duration, every one is effectively `INFERRED`, so they can say how often an inferred estimate lands near the
  label, and set that number from data. They say nothing about `STATED`. This
  is R1's harness; on the paid tier 91 calls cost well under a dollar.
- **Check a `STATED` claim against the text.** A stated estimate should have a
  duration phrase in the content to point at. Where there is none, the basis
  could be downgraded to `INFERRED` and logged, the way `grounding.py` drops a
  `source_ref` the content never contained. It would catch an invented
  "stated" basis, but not a misread one: "down for 40 minutes" has a duration
  phrase and still is not an estimate.

### An unusable estimate becomes null, never a repair round

The same rule as the classification fields, because both keys are optional:

- minutes must be a whole positive number. A fraction is **rounded**, unlike
  urgency: half a minute is precision nobody asked for, while 3.5 urgency
  sits between two answers. Halves go up, so `0.5` is 1; `0.4` rounds to
  zero and is rejected. The ceiling is `MAX_EFFORT_MINUTES` = 9600, a month of
  working time: the product works in short free windows, so a longer estimate is a
  model error or a project, not a task. Above it the value becomes null and is
  logged. (Pydantic alone sets no bound, but the wire does: the field is `int32`
  in Inbox's `task-created-event.proto` and `task-creation-requested-event.proto`.)
- what counts as a whole number is decided once, in `_whole_number`, shared
  with urgency so the two cannot drift apart.
- the two keys travel together, checked after each has been read on its own.
  A basis without usable minutes is dropped: nothing was estimated. Minutes
  without a usable basis are kept as `INFERRED`, the weaker reading — a repair
  round would risk the whole item over an optional field, and a `null` would
  discard an answer the model did give.

### The traps

The phrases that look like a duration and are not one are the real risk, more
than the plain case. In the persona set, every duration-shaped phrase but one
is one of them (the exception, "a few minutes", is covered above):

- **a deadline** — "before this afternoon" says when, not how long, and
  "redundancy must be restored within 72 hours" is a deadline counted in hours.
- **time already passed** — "has been down for 30 minutes" describes the
  fault, not the work.

The prompt names both, and two of its six examples carry one. Whether a live
model actually avoids them is a question for a live run; a scripted provider
returns what the test wrote.

### What was measured, and what was not

One run against the live API (`gemini-3.6-flash`), over six texts written for
this check — none from the persona set, so it stays uncontaminated.

| the text | expected | minutes | basis |
|---|---|---|---|
| "Should take about 20 minutes." | stated | 20 | `STATED` |
| "it's roughly a two-day job" | stated | 960 | `STATED` |
| rename a button on the signup form | inferred, small | 10 | `INFERRED` |
| figure out why churn went up | none | `null` | `null` |
| a questionnaire "due in three days" | not 3 days | `null` | `null` |
| no Wi-Fi "for 45 minutes" | not 45 | `null` | `null` |

Six of six, one provider call each, and the nine-key JSON parsed first time —
the first live check of the rewritten examples too. "Two days" became 960, so
the model read a day as eight working hours. At the time the prompt only
implied that ("half a day is 240") and said nothing about a week, which could
have come back as five working days or seven calendar ones — four times apart,
both at `STATED` confidence. It now states working time outright, and sends
units of assumed length such as sprints to `INFERRED`.

A second run, on that wording, reached five of eight texts before the free
tier's daily quota ran out (429; one 503). All five came back as expected:
the four plain cases unchanged, and "about two sprints of work" as 9600
minutes, `INFERRED` — the model assumed two-week sprints and labelled the
number as its own reading. **Not yet run on the new wording:** the two traps,
and "about a week of work", which should give 2400 as `STATED`.

Caveats, all load-bearing:

- n = 6, one run, and the texts were written by the same hand as the prompt.
  The two traps in particular sit close to the prompt's own wording, so this
  shows the instructions are understood, not that they hold on text written
  by someone else.
- it says nothing about the confidence values. Whether `INFERRED` estimates
  land near the truth is the persona calibration above, and that is R1's run.

## Several tasks per text (#690)

Until #690 the model was asked for one object, so every text yielded exactly
one task and a meeting with eight action items lost seven. The answer is now
one object holding a list:

```json
{"tasks": [{"title": "...", "has_dependencies": false, "dependency_mentions": []}]}
```

| Decision | Where |
|---|---|
| An empty list is a valid answer: a text with no task is the normal case | `ExtractedTasks`, `system.md` |
| At most `MAX_TASKS_PER_TEXT` (15) tasks; more is rejected and repaired, never cut short | `models.py`; the prompt's number is filled in from the constant, not written twice |
| One invalid task fails the whole answer; the message names it by place (`tasks.1.title`) | `parse_response` |
| A bare list without the object is formatting, like a code fence: wrapped, not repaired | `parsing._read_payload` |
| Grounding and confidence run per task; `item_key`, model and attempts stay on the answer | `ExtractedTask`, `ExtractionSuccess.tasks` |
| One `TaskCreationRequested` per task, in order; none for an empty list | `assembly.to_task_creation_requested` |

`system.md` now also says what a task is: work someone is asked to do, offers
to do, or agrees to do — not a topic only discussed, not work already done.
Until #690 that never needed saying. It opens with that, before the format:
most content holds no task, and a model that first reads "return a list of
tasks" goes looking for something to put in it. Live, a newsletter, a deploy
status and small talk each came back as `{"tasks": []}`.

### Which tasks pay for a repair round

A repair round re-asks for the whole list, so a shared attempt count charged
every task for one sibling's `"N/A"`: in a meeting of eight, seven valid tasks
lost 20% each. Instead, `read_rejected_answer` reads each task of a rejected
answer on its own, and a final task **equal in every field** to one of those is
charged only the rounds up to that one (`extractor._rounds_needed`). Matching is
by content, not by place, because the model may reorder, merge or drop tasks
between rounds. The rejected tasks are used for this comparison only; nothing
from a rejected answer reaches the output.

One rejection earns no credit at all: **more than 15 tasks**. A model listing
past the cap is listing non-tasks, so none of what it listed counts as right
the first time. Its rejection says so in its own words ("keep only the real
tasks ... and leave out the rest"), and `repair_user.md` allows removing tasks
when the rejection asks: told only to "return every task again, unchanged", a
model resent the same over-long list until the item failed. An extra key beside `tasks` is formatting and keeps the credit.

This narrows the problem; it does not close it. A valid task the model
*rewords* during repair cannot be told apart from a new one, and is charged.
`repair_user.md` asks for the untouched tasks back unchanged, and every success
after a repair logs the comparison with the **last** rejected answer only —
`the rejected answer had N task(s), V of them valid; the repaired answer has R,
and U of the valid ones came back unchanged` — so how often this happens is
measured, not guessed.

**A dropped task is a warning.** The model can break #690's third decision by
answering the repair with fewer tasks, and nothing raises. When the repaired
answer is shorter *and* a valid task did not come back unchanged, it logs `a
valid task may have been dropped`. Dropping only the invalid one stays quiet:
the title message tells the model to leave a non-task out of the list.

### Proposed: partial repair

The exact fix is to send back only the failed tasks and freeze the valid ones,
so nothing has to be matched. Envelope failures — broken JSON, more than 15
tasks — would still re-ask for everything, and there every task is charged. Not built here: it changes the repair flow, needs a guard
against the model resending the frozen tasks as duplicates, and departs from
#690's third decision as worded ("one invalid task sends the whole answer to
repair"), so it is that decision's owner's call. Worth building if the logged
rewording count turns out to be high.

### `dependency_mentions` (moved here from #842)

**One direction.** A mention, and `has_dependencies`, mean "this task waits
for that work" — never "that work waits for this task". #834 sends each
resolved mention to Chain as this task's `depends_on_task_id`, so a mention of
the work a task *blocks* would arrive as an edge pointing backwards, and two
tasks naming each other as a cycle. The relation is not lost: the waiting task
names its blocker. This narrows `has_dependencies` from #350's "depends on, is
blocked by, or blocks": a task others wait for is now `false`. It is Inbox's
field, so the change is called out in the PR.

Each task names the work it depends on or is blocked by, in the content's own words
("the DB upgrade"), so that #834's resolver can look up the real `task_id`.
It is defined in `system.md`, beside `has_dependencies`, because it is a field
of every task, with the switch on or off.

Three steps, in this order, and the order is the point:

1. **Cleaned, in the model** (`only_usable_mentions_survive`): blanks,
   placeholders and repeats (case-blind) are dropped, non-strings and phrases
   longer than a title (200) are dropped and logged, at most
   `MAX_MENTIONS_PER_TASK` (10) are kept, and a bare string becomes a
   one-item list. One empty mention used to stall #834's listener; none
   leaves here.
2. **Grounded, against the content** (`grounding.ground_dependency_mentions`):
   a mention stays only if every one of its content words is in the text the
   model was sent, except that one may be missing from a mention of three
   words or more (`MIN_WORDS_TO_MISS_ONE`). Not a share: in a long meeting
   "review" or "budget" is said somewhere, and half of a two-word mention
   would ground almost anything. "the security review" in a text that never
   says "security" is dropped. Closeness is not checked: the two words may
   be far apart. A mention equal to another task's title in the same answer
   is grounded by that task — the prompt asks for exactly that, and a title
   is the model's own summary, whose words need not all have been said.
3. **The rule:** a non-empty list after grounding means `has_dependencies` is
   true. A `false` beside a grounded mention is corrected and logged, not
   repaired: the mention is the evidence, the `false` the slip. `true` with an
   empty list is allowed — the content may show other work without naming it.

The rule lives at grounding, not in the model, because the model never sees
the content: applied there, an invented mention had already turned a `false`
into a `true` before anything could tell it was invented, and
`has_dependencies` is a contract field. So the guarantee holds on
`ExtractionSuccess`, what `extract` returns, and not on a bare
`ExtractedFields` parsed or built anywhere else.

**What a mention says.** Enough to be found: the work as the content names
it, with whose it is or what it is for when the content says so, and, for
another task in the same list, that task's title. More words do not make a
mention easier to keep — each one must be in the content — so made-up
context costs the whole mention. One gap is #834's, not this stage's: when
the other task comes from the same text, it is created at the same moment,
and may not be in the index yet when its mention is resolved.
- **Not in the contract.** `TaskCreationRequested` carries only
  `has_dependencies`. The mentions stay on `ExtractedTask.fields` and travel
  separately, through `flow.send(..., dependency_mentions=...)` in #359.
- **`repair_user.md` names no keys.** It refers to "the keys the instructions
  list". Naming fields there once dropped three of them from every repaired
  answer (#587), so a new field is never added to it.

### Known gaps, left for follow-ups

- **Long content is truncated, meetings far later.** `MAX_CONTENT_CHARS`
  (8000) still cuts every other source, where the tail is mostly quoted
  history. A meeting gets `MAX_MEETING_CONTENT_CHARS` (60,000), because its
  action items cluster in the closing recap: on AMI, cut at 8000 the two
  meetings found 0/4 and 0/8 gold items, sent whole 4/4 and 4/8. Any cut is
  logged as a warning with how much was not sent, and grounding reads only
  the text that was sent. A repair round shows the model its own rejected
  answer under the same cap, so a long list of tasks is not cut either.
- **AMI was measured by hand, once.** The transcripts (from #784's loader)
  are not in the repository, as the corpus is gitignored; the run and its
  numbers are in the PR. A repeatable AMI eval belongs to R1's extraction
  eval (#178), which can now read `ExtractionSuccess.tasks`.
- **Urgency has no reference date.** `source_timestamp` is not in the prompt,
  so "by Friday" cannot be placed in time.
- **An email body and its attachment are extracted separately**, and never
  seen together.
- **With classification switched off the model sees no examples at all**,
  including the multi-task and dependency ones. The switch defaults to on.
