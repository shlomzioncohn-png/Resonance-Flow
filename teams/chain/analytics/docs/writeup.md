# Dependency-flow health analytics: stage A write-up

Status: **draft. The Lens paragraph is waiting for Rachel's approval** (marked below). The mentor review is not filled in yet.

## What was built

- **Data.** A generator produces synthetic Chain history (task states and dependency edges over about 180 days, rows shaped like Chain's history tables) and a separate answer key of planted diseases and healthy look-alikes (decoys: `slow_legit`, `backlog`, `abandoned`). `data/dev` (seed 42) is for building and tuning. `data/heldout` (seed 7) was generated in the same way, with a different graph, teams and tasks, and includes the two disease types stage A does not look for. It was opened only for the single final run.
- **Features.** A timeline rebuilt from the two history tables gives per-task, per-edge and per-chain features. The one the detectors rely on is the task's work time relative to the usual for its tier inside its project (`active_ratio_to_tier_project`).
- **Clustering.** One KMeans (random_state 42) over eight standardised task features, k chosen by silhouette in 2-6. It is used as context in the flags' evidence, not as a decision rule.
- **Three detectors**, all deterministic and driven by `config.yaml`: a *chronic blocker* (a task worked on far longer than the usual for its tier while at least 3 tasks wait on it), a *recurring cycle* (an edge that is removed and re-added at least 3 times with a 1-10 day median gap, or a near-cycle pair) and a *slow chain* (a window of 4-8 finished tasks whose links are all slower than same-length windows).
- **Evaluation.** A flag matches a planted disease by task (chronic blocker), by edge (recurring cycle, either edge of a pair) or by Jaccard of at least 0.5 on the task sets (slow chain). Several flags on one disease count as one TP; a flag on no disease is an FP, and one that touches a decoy is also reported as having fallen for it; a disease with no flag is an FN. Flags that match only a disease type outside stage A are listed separately and counted neither way.
- **Process.** Thresholds were tuned on dev only, in one bounded round that picked the best F1 per detector (chronic blocker: ratio 8 to 12, dependents 1 to 3; slow chain: ratio to group median 2 to 4). `config.yaml` was then frozen (SHA-256 `56b380101f40f2c17c3b14f1a07412ebf702edca0cdb697f318ca2532fc7728d`; `run_all` refuses a different config) and the heldout set was run once, with no tuning afterwards.

## Results

| Detector | dev P | dev R | heldout P | heldout R |
|---|---|---|---|---|
| Chronic blocker | 0.56 (5 TP, 4 FP, 10 FN) | 0.33 | 0.62 (5 TP, 3 FP, 10 FN) | 0.33 |
| Recurring cycle | 1.00 (15 / 0 / 0) | 1.00 | 1.00 (15 / 0 / 0) | 1.00 |
| Slow chain | 0.35 (7 TP, 13 FP, 8 FN) | 0.47 | 0.32 (7 TP, 15 FP, 8 FN) | 0.47 |
| **Overall** | **0.61** (27 / 17 / 18) | **0.60** | **0.60** (27 / 18 / 18) | **0.60** |

- Heldout is not worse than dev: overall precision and recall are the same to two decimals, which suggests the frozen thresholds were not overfitted to dev's particular graph. The per-detector numbers are small counts (15 planted diseases each), so one disease moves recall by 0.07.
- **Borderline (`subtle`) diseases:** overall recall 0.33 on dev and 0.47 on heldout. The chronic blocker finds none of its five subtle diseases on either set, and the slow chain finds none on dev and two of five on heldout. Recurring cycles are found at every severity. Recall by severity on heldout: subtle 0.47, moderate 0.47, severe 0.87.
- **Decoys.** 7 of dev's 17 false positives and 3 of heldout's 18 touch a decoy task (see the reports for the flag ids). Most false positives are therefore ordinary tasks and chains that merely look slow, not the planted look-alikes.
- **Outside stage A.** 10 dev flags and 14 heldout flags hit `growing_bottleneck` or `stuck_subgraph` diseases. They are not scored; they show these detectors react to the neighbouring patterns, which stage B has to separate.
- Tuning trade-off: before tuning, dev had 222 flags with precision 0.19 and recall 0.87. The single round traded recall for precision; whether that is the right balance is a product decision.
- **Mentor review (face validity):** pending. `mentor_review.csv` (15 flags, 5 per detector, seed 7) has been produced but not yet sent to the mentor; the count of "reasonable" and the comments will be added here.

## Limitations

- **Synthetic data only.** The thresholds and the numbers above say how the detectors behave on this generator, not on real Chain data.
- **No deterioration over time windows.** Detectors look at the whole history at once; a pattern that is getting worse inside a window is not detected.
- **Two patterns were not evaluated:** subgraphs that get stuck, and bottlenecks that emerge over time. Both are stage B.
- The score is a percentile inside each detector, so ranking across detectors inside one severity is only indicative.
- Explanations show the median in whole days, so a median under half a day reads "0 days".

## Boundary with Lens

**[Pending Rachel's approval]** Lens shows what is wrong right now: aggregates of the current state of the tasks. This tool finds patterns that only exist over time, such as an edge that keeps flapping or a task that stays a blocker far longer than its peers. Its output is a recommendation only: it never writes to Chain's tables and never changes a task or a dependency.
