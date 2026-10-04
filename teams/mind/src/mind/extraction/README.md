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
