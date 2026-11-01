# Evaluation report: data\dev

- data seed: 42, as of 2026-07-03T15:36:16.574959+00:00
- config.yaml SHA-256: `56b380101f40f2c17c3b14f1a07412ebf702edca0cdb697f318ca2532fc7728d` (frozen: matches the locked hash)
- flags: 60

## Precision and recall

n/a = undefined (no flags, or nothing planted for that detector).

| Detector | TP | FP | FN | Precision | Recall |
|---|---|---|---|---|---|
| CHRONIC_BLOCKER | 5 | 4 | 10 | 0.56 | 0.33 |
| RECURRING_CYCLE | 15 | 0 | 0 | 1.00 | 1.00 |
| SLOW_CHAIN | 7 | 13 | 8 | 0.35 | 0.47 |
| **Overall** | 27 | 17 | 18 | 0.61 | 0.60 |

## Recall on low-severity (`subtle`) diseases, the borderline cases

| Scope | Planted | Found | Recall |
|---|---|---|---|
| overall | 15 | 5 | 0.33 |
| CHRONIC_BLOCKER | 5 | 0 | 0.00 |
| RECURRING_CYCLE | 5 | 5 | 1.00 |
| SLOW_CHAIN | 5 | 0 | 0.00 |

## Recall by severity

| Severity | Planted | Found | Recall |
|---|---|---|---|
| subtle | 15 | 5 | 0.33 |
| moderate | 15 | 7 | 0.47 |
| severe | 15 | 15 | 1.00 |

## Decoys

7 false positives touch a decoy task (`slow_legit`, `backlog`, `abandoned`); they are also counted in FP above.
- `CHRONIC_BLOCKER:4b416d927d`
- `CHRONIC_BLOCKER:70c840ab14`
- `SLOW_CHAIN:27219948ab`
- `SLOW_CHAIN:99d0c6cbe2`
- `SLOW_CHAIN:a8a7688151`
- `SLOW_CHAIN:d0d612992f`
- `SLOW_CHAIN:f1ea88fe47`

## Outside stage A

10 flags hit a planted disease that stage A does not look for (`growing_bottleneck`, `stuck_subgraph`). They count neither as TP nor as FP.

## Missed diseases (FN)

- `chronic_blocking-moderate-1`
- `chronic_blocking-moderate-2`
- `chronic_blocking-moderate-3`
- `chronic_blocking-moderate-4`
- `chronic_blocking-moderate-5`
- `chronic_blocking-subtle-1`
- `chronic_blocking-subtle-2`
- `chronic_blocking-subtle-3`
- `chronic_blocking-subtle-4`
- `chronic_blocking-subtle-5`
- `slow_chain-moderate-1`
- `slow_chain-moderate-2`
- `slow_chain-moderate-5`
- `slow_chain-subtle-1`
- `slow_chain-subtle-2`
- `slow_chain-subtle-3`
- `slow_chain-subtle-4`
- `slow_chain-subtle-5`

## Ranked list

Rows are ordered by severity, then by score. The score is a percentile inside its own detector, so a score of one detector is not comparable with a score of another; between detectors the order inside a severity is therefore only indicative. The list is a recommendation: nothing is written to Chain.

## Mentor review (face validity)

Waiting for the mentor: `mentor_review.csv` has not been filled in yet.
