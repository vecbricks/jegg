# The code, mapped

One package, `io.github.vecbricks.jegg`, 24 files. The Javadoc of each is at
https://vecbricks.github.io/jegg/; this page says what each is for, how an iteration flows
through them, and where the invariants hold and where they may not.

## The files

| file | responsibility |
|---|---|
| `Language` | what a client's e-node is: `children()` as an `IntList` of class ids, `withChildren`, `head()` (operator and payload without children). A client's language is a sealed set of records. |
| `IntList` | an immutable `int[]` with value equality; children live here so a record never compares an array by reference. |
| `IntArray` | package-private: a growable `int[]`, the worklists of `EGraph` and the extractor's newly selected classes, ids kept unboxed. |
| `UnionFind` | `int` ids, path compression, the smaller id kept as root (one of the fixed orders); refuses an id it never made. |
| `EClass` | a class: id, nodes in insertion order, parent entries `(node, classId)`, the analysis fact, a flag if it ever pruned. Its views are live. |
| `EGraph` | the e-graph: hashcons, classes, `add`, `merge`, `rebuild` and `repair`, `addTree`/`lookupTree` through a `TreeBridge`, the analysis's maintenance, `retainNodes` (pruning), the change counter, the invariant checkers. |
| `TreeBridge` | a client's tree to e-nodes and back: `childrenOf`, `node`, `build`. |
| `Analysis` | `make`, `join` (a semilattice join), `modify`; `Analysis.none()`. |
| `Pattern`, `Pattern.Head` | a pattern tree of nodes and variables; a head matches an operator and payload exactly (`Pattern.of`) or binds the payload to a variable (`Pattern.binding`) and declares what it binds. |
| `Subst` | the bindings of a match: subterm variables to class ids, payload variables to values; immutable. |
| `Matcher` | the naive backtracking matcher: `search` over the whole graph (optionally to a limit), `matchIn` one class, `instantiate` a right-hand side. Order fixed: classes by id, nodes by insertion. |
| `Rewrite`, `Applier`, `Condition` | a named rule: left pattern, right-hand side (a pattern or a function), condition read at apply time; `Rewrite` refuses a right-hand variable the left does not bind. |
| `Scheduler`, `BackoffScheduler` | which matches are applied each iteration; the backoff bans a rule past its threshold and searches it only one match past it. |
| `RunLimits`, `StopReason`, `RunReport` | the bounds of a run, why it stopped, what each iteration did. |
| `Runner` | equality saturation: the loop below, with hooks. |
| `CostFunction`, `Extractor`, `Extracted`, `Selection` | extraction: tree costs by fixed point, `extract(root)` a tree, `extractAll(roots)` one node per class with sharing (greedy start, incremental descent with targeted holding), a `Selection` whose terms share objects. |

## One iteration of the runner

```
rebuild                          the graph is clean before anything reads it
changesBefore = graph.changes()  read before the hooks, so a hook's edits count as change
hooks                            each may stop the run (StopReason.Other) or change the graph
rebuild                          after the hooks
for each rule: scheduler.search  all matches read before any is applied (a banned rule: none)
for each rule, each match:       condition read now; right-hand side added; unioned with the match
   after each rule: limits       a passed limit skips the rules after it (report.skipped)
rebuild                          congruence restored once, repairs counted
report the iteration
stop if: a limit passed | unions == 0 && scheduler.canStop && changes unchanged (saturated)
       | the iteration limit
```

Two orders matter and are egg's: the scheduler is asked whenever no rule merged, even if nodes
were added (its bans are released there); and the limits are checked between rules, not only
after the iteration.

## Where the invariants hold, and where they do not

- **Hashcons and congruence** (`checkInvariants`): hold after `add`, after `rebuild`, and are
  not guaranteed between a `merge` and the next `rebuild` - the worklist holds the debt. `lookup`
  and `lookupTree` may miss a node whose children were merged until then; the runner always
  rebuilds before searching. The hashcons holds exactly the graph's nodes (egg's may hold
  garbage); nodes pruned by `retainNodes` are remembered in a separate map that `add` and
  `lookup` consult, and they stay in their children's parent lists for congruence.
- **The analysis invariant** (`checkAnalysisInvariant`): holds after `rebuild`. A `join` that
  throws (two constants in one class) leaves a direct `merge` untouched; thrown inside a rebuild's
  repair it leaves the rebuild half done, as egg's panic would.
- **Ids**: dense, never reused, never negative; `find` refuses one the graph never issued. A
  merged class's old id resolves to its root.
- **Live views**: `EClass.nodes()` and `parents()` change under a caller that changes the graph
  while iterating; copy first (an applier, a condition).
- **The extractor is a snapshot**: built on a rebuilt graph, it refuses a call once the graph's
  node or class count has changed; a change that keeps both counts goes unseen.
- **Determinism**: ids by insertion, rules in declared order, matches in the matcher's order,
  extraction ties by node order, every id-reaching map insertion-ordered. Checked by
  `DeterminismTest` across fresh JVMs.

## Where to look when

| you want to | start at |
|---|---|
| add an operation on the graph | `EGraph`, then a test with `checkInvariants` after it |
| change what a rule can match | `Pattern.Head`, `Matcher.matchIn`; `PatternMatchTest` |
| change when a run stops or what it reports | `Runner.run`, `RunReport`; `RunnerTest`, and the egg-pinned suites must still end at egg's counts |
| change extraction | `Extractor.Descent`; `ExtractorTest`, `ExtractAllEnumerationTest`, `ExtractionGymTest` |
| port another egg test | `LambdaTest` or `MathTest` as the shape; `Term` for s-expressions; egg's own run for the counts |
| measure | `src/jmh/java`, `benchmarks/README.md`; only on an idle machine the owner has offered |
