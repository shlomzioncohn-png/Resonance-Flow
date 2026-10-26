# propagation_fixtures

Constructed graphs for #473, the test bed #474's hierarchical-importance
propagation algorithm will be built and checked against. Each fixture bakes in
a hand-verified correct `expected_order` as data, not as an assertion written
after the fact.

## The seven graphs

| graph | what it tests |
|---|---|
| `01_chain.json` | Smoke test: a plain parent → child → grandchild chain. Two leaves share identical own importance; the one under a strategic goal must outrank the standalone one purely from the chain beneath it. If this fails, nothing else will pass either. |
| `02_branch.json` | Written to prove propagation changes the outcome at all: children of a high-importance goal outranking children of a low-importance goal despite looking less urgent on their own features. At the current 0.93/0.07 weighting (revised down from 0.5/0.5, review on #474) own importance alone already produces the expected order, so this no longer demonstrates that claim -- see the fixture's own `notes`. |
| `03_deep_nesting.json` | Forces a correct full walk to the root, not a one-hop shortcut. A leaf 4 levels under an important goal must beat a leaf 1 level under an unimportant goal, with the intermediate nodes deliberately scored so a one-level-up read gets the wrong answer. Also the fixture for the no-decay decision (below). |
| `04_orphan.json` | A leaf with no parent at all — the shape most likely to crash naive propagation code. Must not raise just because `parent_task_id` is null, and must not receive a boost it didn't earn. |
| `05_deprioritized_initiative.json` | Isolates that the parent's importance alone, not the leaf's own, decides the order: two leaves share identical own importance and differ only in which goal (since-deprioritized vs. still-important) sits above them. |
| `06_cycle.json` | Protects the algorithm from an infinite loop on a graph it is explicitly handed. `HierarchyFixture` deliberately doesn't forbid a cycle, so a naive walk-the-parent-chain implementation must not be allowed to loop forever here. |
| `07_initiative_under_goal.json` | Written to catch a formula that reads only the top-level goal and ignores everything between it and the leaf. Two leaves share one identical goal; only an intermediate initiative differs (deprioritized vs. still-important), with a small, deliberate own-importance asymmetry (0.5 vs 0.6, not identical) so root-only and whole-chain formulas disagree rather than tie. At the current 0.93/0.07 weighting (revised down from 0.5/0.5, review on #474) that small own-importance gap outweighs the initiative-importance gap once discounted to 7%, so this fixture's expected order has flipped and it no longer distinguishes whole-chain reads from root-only reads -- see the fixture's own `notes`. |

## Design decisions

These are stated once here; the fixtures above exercise them, and several
fixtures' own `notes` fields cross-reference back to this section rather than
re-arguing it.

**No depth decay.** Importance propagates undiminished no matter how many hops
deep. No decay rate or curve is specified anywhere in the roadmap or the
team-role documents, so inventing one now would bake an unfounded guess into
fixtures that are supposed to have a defensible, hand-verified answer.
Undiminished propagation is the simplest model consistent with what's actually
known, and it is a decision that can be revisited deliberately later if real
propagated data shows deep descendants getting overweighted — but it must not
be guessed into the fixtures now. `03_deep_nesting.json` is built to catch a
regression here: its intermediate nodes are scored so that any decay strong
enough to matter would flip its expected order.

**Cycle: detect and refuse, not a depth limit.** `06_cycle.json` raises on a
cyclic `parent_task_id` chain rather than silently breaking after N hops. This
protects Mind's own propagation algorithm from an infinite loop on a graph
it's explicitly given — `HierarchyFixture` deliberately doesn't forbid a
cycle. Not realistic in the sense that any real data source produces one
today: neither Chain's traversal API (see below) nor the eval's own
`validate_hierarchy_fixture` ever lets one through. It is realistic in the
sense that the type permits constructing it, so defensive code walking an
arbitrary `HierarchyFixture` must handle it regardless of what today's actual
sources happen to avoid — the guard exists for Mind's algorithm either way.

**A parent can only add, never remove.** Named assumption, currently only
implicit in `01_chain.json`'s and `03_deep_nesting.json`'s own notes: a
parent's importance can boost a descendant above its own baseline, but can
never multiply it down below that baseline. Several fixtures' expected orders
depend on this — under a multiplicative combination formula (e.g.
`own * parent`), some of these orders would reverse, as those two files'
notes document with worked numbers. This is a stated assumption this test bed
relies on so its orders are well-defined, not a claim about what #474's real
combination formula will be; that's still #474's to decide.

## Chain's traversal API — what's actually confirmed

Derived by reading Chain's merged source directly this session —
`ChainController.java` and `TaskTraversalService.java` — not from a live
conversation with Chain's team. If a formal confirmation from Chain is still
wanted before #474 builds its adapter against this, that's a call for the
team lead; this section records the technical answer as read from the code,
it does not claim that conversation happened.

- **Endpoints returning a flat `TaskResponse` list:** `GET
  /tasks/{taskId}/dependencies` (+`/transitive`), `/dependents`
  (+`/transitive`), `/ancestors`, `/children`, `/descendants`. Each item
  carries its own `id`, `parentTaskId`, `status`, `isReady`, `blockingCount`,
  `openConstraintCount`, `estimatedDurationMin`, `score` — i.e. every node in
  the response already carries its own parent pointer, not just a bare id
  list. #474's adapter only needs `/ancestors`.
- **Endpoints with their own response shape:** `/trace` returns a
  `TaskTraceResponse` (structured dependency-graph/hierarchy sections, not a
  flat list); `/blockers` returns a `TaskAvailability`. Neither is detailed
  further here since #474 doesn't need them.
- **`/dependencies/history`:** paginated dependency-change history
  (`PageResponse<DependencyHistoryResponse>`) — not part of the
  graph-shape group above.
- **No `importance` field anywhere.** None of the flat-list fields above —
  including `score` — correspond to `importance`, the one value
  `HierarchyNode` needs. `score` is Chain's own computed value, not an input
  Mind should feed back into its own computation. This is a known gap for
  #474's adapter to solve (get importance from Mind's own baseline score or
  some other source), not something #473 solves.
- **Ordering:** `getAncestors` returns nearest parent first, root last.
  `getAllDescendants`/`getAllDependencies` do a BFS, nearest-first — not
  sorted by score.
- **No `maxDepth` parameter anywhere.** The only safety mechanism is a
  visited-set, used as both a cycle-guard and a dedup, with no depth limit.
- **Cycle / dangling-parent handling, confirmed from `ancestorIds`'s actual
  source:** on either case the walk silently `break`s and returns a normal
  200 with whatever chain it collected so far. No exception, no error field,
  nothing distinguishing a genuinely short chain from a truncated one. In
  production, Mind will never "see" a cycle through this API — it will
  receive a shorter chain that looks perfectly valid.
