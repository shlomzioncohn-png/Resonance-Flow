"""A hand-built fixture for the extraction-quality eval, with results computable by hand.

Same role as `placeholder_scenarios.py` for the ranking harness: a small fixed
input that both `tests/test_extraction_eval.py` and the `run_extraction_eval` CLI
use, so the harness can be exercised end-to-end before any real pipeline output
exists ("dummy model before real model").

The fixture is deliberately built so its precision/recall can be worked out on
paper and asserted against — it validates the *matching and counting machinery*,
not the quality of any similarity function.

## What it contains

Four annotated action items and four extracted items. Three of the extracted
items are near-paraphrases of an annotation (a true positive each); one is a
hallucination with no annotation (a false positive); one annotation has no
extracted counterpart (a false negative).

## The arithmetic (with `default_similarity` = Jaccard token overlap, `threshold = 0.5`)

After `_normalize` (lowercase, collapse whitespace, drop trailing "."), token-set
Jaccard = |A ∩ B| / |A ∪ B| for each intended pair:

| pair | shared / union tokens | Jaccard | ≥ 0.5? |
|---|---|---|---|
| "industrial designer to present the components concept" ↔ annotation 1 | 6 / 8 | 0.75 | yes → TP |
| "marketing expert to present the marketing concept" ↔ annotation 2 | 5 / 7 | ≈0.71 | yes → TP |
| "project manager to book a meeting room for tuesday" ↔ annotation 3 | 8 / 11 | ≈0.73 | yes → TP |
| annotation 4 ("user interface designer…") ↔ its closest extracted item | 4 / 10 | 0.40 | no → FN |
| "team to decide on the tv remote button layout" ↔ anything | ≤ 1 / 15 | ≈0.07 | no → FP |

So `TP = 3`, `FP = 1`, `FN = 1`:

- precision = 3 / (3 + 1) = **0.75**
- recall    = 3 / (3 + 1) = **0.75**
- f1        = 2·0.75·0.75 / (0.75 + 0.75) = **0.75**
"""

from __future__ import annotations

FIXTURE_THRESHOLD = 0.5

FIXTURE_ANNOTATED: list[str] = [
    "The Industrial Designer will present the components concept.",
    "The Marketing Expert will present the marketing concept.",
    "The Project Manager will book a meeting room for Tuesday.",
    # No extracted item covers this one -> false negative.
    "The User Interface Designer will present the user interface concept.",
]

FIXTURE_EXTRACTED: list[str] = [
    "Industrial Designer to present the components concept",
    "Marketing Expert to present the marketing concept",
    "Project Manager to book a meeting room for Tuesday",
    # Nothing in the annotations matches this -> false positive.
    "Team to decide on the TV remote button layout",
]

# Worked out by hand above; the tests assert the harness reproduces these.
FIXTURE_EXPECTED_PRECISION = 0.75
FIXTURE_EXPECTED_RECALL = 0.75
FIXTURE_EXPECTED_F1 = 0.75
FIXTURE_EXPECTED_TRUE_POSITIVES = 3
FIXTURE_EXPECTED_FALSE_POSITIVES = 1
FIXTURE_EXPECTED_MISSED = 1
