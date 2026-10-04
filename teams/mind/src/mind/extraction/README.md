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
