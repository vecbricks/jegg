# The code, mapped

One package, `io.github.vecbricks.jegg`, 27 files. The Javadoc of each is at
https://vecbricks.github.io/jegg/; this page says what each is for, how an iteration flows
through them, and where the invariants hold and where they may not.

## The files

| file | responsibility |
|---|---|
| `Language` | what a client's e-node is: `children()` as an `IntList` of class ids, `withChildren`, `head()` (operator and payload without children). A client's language is a sealed set of records. |
| `IntList` | an immutable `int[]` with value equality; children live here so a record never compares an array by reference. |
| `IntArray` | package-private: a growable `int[]`, the worklists of `EGraph` and the extractor's newly selected classes, ids kept unboxed. |
| `UnionFind` | `int` ids, path compression, the smaller id kept as root (one of the fixed orders); refuses an id it never made. |
| `EClass` | a class: id, nodes in insertion order, parent entries `(node, classId)`, the analysis fact, a flag if it ever pruned, and each node's head ordinal (`EGraph.headOrdinal`, numbered as heads are first met); and, built on first use and dropped when the node list changes, the matcher's two views of the list: the table (`EClass.Table`: the positions sorted by head ordinal, insertion order kept within a head, with every node's arity and children in one `int[]`) and a type index from a node class to the positions of its nodes. Its views are live. |
| `EGraph` | the e-graph: hashcons, classes, `add`, `merge`, `rebuild` and `repair`, `addTree`/`lookupTree` through a `TreeBridge`, the analysis's maintenance, `retainNodes` (pruning), the change counter, the invariant checkers. |
| `TreeBridge` | a client's tree to e-nodes and back: `childrenOf`, `node`, `build`. |
| `Analysis` | `make`, `join` (a semilattice join), `modify`; `Analysis.none()`. |
| `Pattern`, `Pattern.Head` | a pattern tree of nodes and variables; a head matches an operator and payload exactly (`Pattern.of`) or binds the payload to a variable (`Pattern.binding`) and declares what it binds. |
| `Subst` | the bindings of a match: subterm variables to class ids, payload variables to values; immutable. |
| `Matcher` | the backtracking matcher: `search` over the graph (optionally to a limit; for a `MultiPattern`, a depth-first join of its clauses), starting at the classes holding the root head's node class when the head names it (`Pattern.Head.type`, an index `EGraph` keeps per node class), `matchIn` one class in one depth-first walk, where a pattern node whose head has a key (`Pattern.Head.key`) reads the class's table (the run of its head found by binary search, arities and children read as consecutive ints, no node object touched until a match is complete), one whose head names a node class reads the type index of a class of 8 nodes or more and walks a smaller one, and one with neither walks every node, a nested pattern node that is ground by the time it is reached looked up in the hashcons rather than walked where the child's class has pruned or its run of the head is long (egg's `Lookup`, which finds pruned nodes too), `instantiate` a right-hand side. Order fixed: classes by id, nodes by insertion, children depth-first; the indexes keep insertion order, so they change nothing but the time. |
| `Searcher`, `MultiPattern` | what a rule searches with: a `Pattern`, or several clauses `?var = pattern` joined on shared variables (egg's multi-pattern, which also serves as a right-hand side through `Applier.multi`). |
| `Rewrite`, `Applier`, `Applied`, `Condition` | a named rule: left searcher, right-hand side (a pattern, a multi-pattern or a function), condition read at apply time; `applyTo` applies one match and returns `Applied` (unions made, and what egg counts as applied); `Rewrite` refuses a right-hand variable the left does not bind. |
| `Scheduler`, `BackoffScheduler` | which matches are applied each iteration; the backoff bans a rule past its threshold and searches it only one match past it. |
| `RunLimits`, `StopReason`, `RunReport` | the bounds of a run, why it stopped, what each iteration did. |
| `Runner` | equality saturation: the loop below, with hooks. |
| `CostFunction`, `Extractor`, `Extracted`, `Selection` | extraction: tree costs by fixed point, `extract(root)` a tree, `extractAll(roots)` one node per class with sharing (greedy start, incremental descent with targeted holding), a `Selection` whose terms share objects. |

## The public surface

One package, 26 public top-level types and the nested types below. A type is public because a
client names it, implements it, or meets it in a signature it uses; everything else is
package-private (the tests and the measurement harness share the package, so they still see it).
Cut for 0.1.0 (#47): `UnionFind` (no signature mentions it), `EClass.Parent` and
`EClass.parents()` (the repair's entries, which no client reads), and `PatternApplier` and
`MultiApplier` (what `Applier.pattern` and `Applier.multi` return, now package-private classes
instead of public nested records). `IntList` stays public although it is an implementation of
sorts: `Language.children()` returns it.

| group | types | public because |
|---|---|---|
| the graph | `EGraph`, `EClass`, `Language`, `IntList`, `TreeBridge`, `Analysis` | a client builds a graph, implements a language, a bridge and an analysis, and reads classes; `children()` returns an `IntList` |
| patterns and rules | `Pattern` (`Var`, `Node`, `Head`), `MultiPattern` (`Clause`), `Subst`, `Matcher` (`Match`), `Searcher`, `Rewrite`, `Applier`, `Applied`, `Condition` | a client writes rules, and implements heads, conditions and appliers; `Rewrite.search` and `Scheduler.search` return `Matcher.Match`, `Applier.applyTo` returns `Applied` |
| running | `Runner` (`Hook`), `RunLimits`, `RunReport` (`Iteration`), `StopReason`, `Scheduler`, `BackoffScheduler` | a client runs, bounds, observes and may replace the scheduler |
| extraction | `CostFunction`, `Extractor` (`Best`), `Extracted`, `Selection` | a client prices and extracts |

Closed by `sealed`: `Pattern` (`Var`, `Node`) and `StopReason`, which a client matches
exhaustively. Open on purpose: `Language`, `Analysis`, `Searcher`, `Applier`, `Condition`,
`Scheduler`, `CostFunction`, `TreeBridge`, `Pattern.Head` and `Runner.Hook`, which clients
implement. Threads: `docs/concepts.md`.

Nullness: the package is `@NullMarked` (JSpecify 1.0.0), so a reference is non-null unless marked
`@Nullable`. The analysis fact type is `D extends @Nullable Object`: `Analysis.none()` has
`D = Void` and its facts are null, an analysis over `Integer` never sees a null. The marked
places are `Pattern.Head.match` (null for no match), `Subst.payload` and `bindPayload` (a payload
may be null) and `Extractor.extractAll`'s score (null for the node-cost sum). NullAway was run
once over `src/main`, not added to the build; it reports five internal places (`EGraph`'s
fact slot before the first `make`, `Extractor`'s choice arrays), which are not annotated.

Module: `module-info.java` exports the one package and has `requires static transitive
org.jspecify`, so the annotations compile for a client on the module path and are not needed at
run time. The tests run on the class path (`useModulePath` is false: the determinism test forks
JVMs from `java.class.path`) and compile inside the module with `java.management` added.

## One iteration of the runner

```
rebuild                          the graph is clean before anything reads it
changesBefore = graph.changes()  read before the hooks, so a hook's edits count as change
hooks                            each may stop the run (StopReason.Other) or change the graph
rebuild                          after the hooks
for each rule: scheduler.search  all matches read before any is applied (a banned rule: none)
for each rule, each match:       condition read now; right-hand side added; unioned with the match
                                 (a multi-pattern: with the classes its clause variables name)
   after each rule: limits       a passed limit skips the rules after it (report.skipped)
rebuild                          congruence restored once, repairs counted
report the iteration
stop if: a limit passed | counted == 0 && scheduler.canStop && changes unchanged (saturated)
       | the iteration limit
```

Two orders matter and are egg's: the scheduler is asked whenever nothing was counted as applied,
even if nodes were added (its bans are released there); and the limits are checked between rules,
not only after the iteration. `counted` is the unions, plus one for every match of a rule whose
right-hand side is a multi-pattern, as egg counts them (`Applied`), so a run in which such a rule
matches never saturates.

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
