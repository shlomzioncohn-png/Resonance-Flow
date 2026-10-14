# Decision document: choosing a ranking metric for the Evaluation Harness

## 1. Background and goal
This document decides which metric the system will use to measure the performance of our ranking models. The evaluation harness we're building will run automatically on every Pull Request in CI to catch ranking regressions before merge. Our goal is to establish a measurement standard that can demonstrate model improvements to the mentor ahead of the week-7 stretch goal.

## 2. Alternatives considered
* **Top-1 Accuracy:** a binary check of whether the task the model returns in first place (highest score) is in fact the single task defined as "correct" for the scenario.
* **MRR (Mean Reciprocal Rank) / nDCG:** more advanced metrics that evaluate the quality of a full ranked list, accounting for the position of relevant results throughout the list (not just first place). nDCG also supports graded relevance (different importance levels for different tasks).

## 3. Technical comparison against system constraints

| Criterion | Top-1 Accuracy | MRR / nDCG |
| :--- | :--- | :--- |
| **Fit with the defined data structure** | Exact match to the current system requirements and to the persona set of 24-36 scenarios, where each scenario has one mentor-approved "correct" answer. | Requires substantial investment in producing ground truth labels for multiple tasks per scenario, plus ranking their relative relevance. |
| **Ranking sensitivity** | Strict. The system is looking for "the single most important thing to do right now." If it's not in first place, the model fails (score 0). | Sensitive. Awards partial credit even if the relevant task lands in second or third place. |
| **Implementation complexity in the runner** | Very simple to implement in fast Python code, and easy to explain. Produces a transparent, unambiguous result. | Requires more complex computation and handling of edge cases in partial lists. |

## 4. Final recommendation
The recommendation is to adopt **Top-1 Accuracy** as the primary metric for the evaluation harness at this stage.

Our product exists to answer the focused question "what's the single most important thing I can do right now?" Given that, and given the constraint that our scenario set contains exactly one correct answer (Top Task) per given free-time window, measuring the ranking model by its first-place accuracy provides the most precise and simplest indicator of system success. Implementing MRR or nDCG at this stage would be over-engineering and would require labeling resources that don't serve the week-7 proof-of-concept.

In parallel, we've prepared a placeholder extraction function (`extraction_precision_recall(predicted, ground_truth)`) to measure entity-level Precision and Recall against the 381 labeled AMI tasks.

> **Note:** the "381" above could not be reproduced; the verified count is **306** action items across 133 meetings. See `extraction_eval_design.md` ("Count — locked at 306").
