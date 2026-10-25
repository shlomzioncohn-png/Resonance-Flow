# Evaluation report: data\heldout

- data seed: 7, as of 2026-07-03T15:11:31.283974+00:00
- config.yaml SHA-256: `56b380101f40f2c17c3b14f1a07412ebf702edca0cdb697f318ca2532fc7728d` (frozen: matches the locked hash)
- flags: 65

## Precision and recall

n/a = undefined (no flags, or nothing planted for that detector).

| Detector | TP | FP | FN | Precision | Recall |
|---|---|---|---|---|---|
| CHRONIC_BLOCKER | 5 | 3 | 10 | 0.62 | 0.33 |
| RECURRING_CYCLE | 15 | 0 | 0 | 1.00 | 1.00 |
| SLOW_CHAIN | 7 | 15 | 8 | 0.32 | 0.47 |
| **Overall** | 27 | 18 | 18 | 0.60 | 0.60 |

## Recall on low-severity (`subtle`) diseases, the borderline cases

| Scope | Planted | Found | Recall |
|---|---|---|---|
| overall | 15 | 7 | 0.47 |
| CHRONIC_BLOCKER | 5 | 0 | 0.00 |
| RECURRING_CYCLE | 5 | 5 | 1.00 |
| SLOW_CHAIN | 5 | 2 | 0.40 |

## Recall by severity

| Severity | Planted | Found | Recall |
|---|---|---|---|
| subtle | 15 | 7 | 0.47 |
| moderate | 15 | 7 | 0.47 |
| severe | 15 | 13 | 0.87 |

## Decoys

3 false positives touch a decoy task (`slow_legit`, `backlog`, `abandoned`); they are also counted in FP above.
- `CHRONIC_BLOCKER:b9a0756f1b`
- `SLOW_CHAIN:210e83a156`
- `SLOW_CHAIN:bf1dcf13fa`

## Outside stage A

14 flags hit a planted disease that stage A does not look for (`growing_bottleneck`, `stuck_subgraph`). They count neither as TP nor as FP.

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
- `slow_chain-moderate-3`
- `slow_chain-moderate-5`
- `slow_chain-severe-1`
- `slow_chain-severe-4`
- `slow_chain-subtle-1`
- `slow_chain-subtle-2`
- `slow_chain-subtle-4`

## Ranked list

Rows are ordered by severity, then by score. The score is a percentile inside its own detector, so a score of one detector is not comparable with a score of another; between detectors the order inside a severity is therefore only indicative. The list is a recommendation: nothing is written to Chain.

## Mentor review (face validity)

Waiting for the mentor: `mentor_review.csv` has not been filled in yet.
