# egg and jegg, side by side

For a reader who knows [egg](https://github.com/egraphs-good/egg) (commit 73975c9, the one jegg's
tests are pinned to) and asks what is and is not ported. One table per egg module: the egg item,
what jegg has in its place, and a note. An egg item that jegg lacks is marked with one of four
statuses, so the list of absences can be read by cause:

| status | meaning |
|---|---|
| out by design | a decision of the plan (`PLAN.md` 3.1) or of the design's promises, with its reason: proofs, parsing, Graphviz, an ILP extractor, a wall-clock limit, mutable access to a class |
| replaced | another jegg mechanism does the job, so the egg item has no counterpart of its own |
| not needed yet | no client has asked, so it is left out, not ruled out; a reader who needs one should open an issue |
| not ported yet | the issue that will port it is named |

"Added" rows are what egg lacks. `PLAN.md` 3.1 is the design record behind these tables and
`docs/architecture.md` maps the jegg code.

Of the 25 rows so marked, 13 are out by design (ten of them proofs, parsing or text output; the
other three are `classes_mut`, the time limit and the ILP extractor), 6 replaced, 5 not needed
yet and 1 not ported yet (`datalog.rs`, #34).

The shape of the port in four lines:

- Ids are `int`, never boxed on a hot path; a client's language is a sealed hierarchy of records
  (`Language`), not a macro-defined enum, and its trees reach the e-graph through a `TreeBridge`,
  not a `RecExpr`.
- The same algorithms in the same order (the paper's Figures 4, 5 and 9), so a run's iteration,
  node and class counts agree with egg's. Four of egg's test suites are ported and pinned to those
  counts (see the last section).
- One property egg does not promise: the result is a function of the input. Ids are assigned by
  insertion, every map whose order could reach an id is insertion-ordered, and `extractAll` breaks
  ties by node order (`docs/concepts.md`, `docs/skills/a-determinism-test-that-can-fail.md`).
- Proofs, parsing, ILP extraction and Graphviz output are out, by design
  (`PLAN.md` 3.1); `Explain`'s absence is the largest.

## `egraph` (`EGraph`)

| egg | jegg | note |
|---|---|---|
| `EGraph::new(analysis)`, `default()` | `new EGraph<>(analysis)`, `EGraph.withoutAnalysis()` | ported |
| `add(enode)` | `add(L)` | ported; returns an `int` class id |
| `add_uncanonical` | out by design | it exists for explanations; `add` canonicalises |
| `add_expr(&RecExpr)` | `addTree(root, bridge)` | through `TreeBridge`; a subtree object reached twice is added once |
| `lookup`, `lookup_expr` | `lookup(L)`, `lookupTree(root, bridge)` | ported; both also find a node `retainNodes` dropped |
| `union(a, b)` | `merge(a, b)` | returns the surviving root; only unions and queues, nothing is repaired |
| `union_trusted`, `union_instantiations` | out by design | explanation justifications; a rewrite's apply does the instantiation (`Rewrite.apply`) |
| `find` | `find(int)` | ported |
| `rebuild` | `rebuild()` | ported (paper Fig. 4); returns the number of classes repaired |
| `classes()` | `classes()` | a read-only snapshot in id order |
| `classes_mut()` | out by design | a mutable class could break the invariants; `EClass`'s mutators are package-private |
| `classes_for_op` | an internal index | the matcher uses it; `Pattern.Head.type()` names the operator class it searches |
| `total_size()` | `numNodes()` | distinct canonical e-nodes; named for what it counts, not for egg's `memo` |
| `number_of_classes()` | `numClasses()` | ported |
| `total_number_of_nodes()`, `is_empty()`, `nodes()` | replaced | derivable from `classes()` and `numNodes()` |
| `id_to_node`, `id_to_expr`, `id_to_pattern` | `classOf(id).nodes()`; `Extractor.extract` | the term of a class is an extraction, not an accessor |
| `egraph_union`, `egraph_intersect`, `copy_without_unions`, `get_union_equalities`, `equivs` | not needed yet | operations on whole graphs for tools like Herbie; no client of jegg asks for them |
| `LanguageMapper`, `SimpleLanguageMapper` | not needed yet | the same |
| `with_explanations_enabled`, `explain_equivalence`, `explain_matches`, `get_num_congr` | out by design | proofs (`explain` below) |
| `dot()` | out by design | see `dot` below |
| (none) | `changes()` | added: a counter of adds, merges and prunes, so two readings that agree prove nothing changed, which sizes cannot |
| (none) | `isDirty()` | added: whether a rebuild is pending |
| (none) | `retainNodes(eclass, keep)` | added: prune a class's nodes, remembering the dropped ones so `lookup` still resolves (`docs/skills/prunings-leftovers-carry-weight.md`) |
| (none) | `checkInvariants()`, `checkAnalysisInvariant()` | added: the structure's invariants as a call, run by the tests after saturations |
| (none) | `data(id)`, `analysis()` | the fact of a class and the analysis; egg reads `egraph[id].data` |

## `eclass` (`EClass`)

| egg | jegg | note |
|---|---|---|
| `EClass { id, nodes, data, parents }` | `EClass`: `id()`, `nodes()`, `data()`, `parents()` | nodes in insertion order, not sorted by operator; the views are live |
| `parents()` as ids | `parents()` as `Parent(node, classId)` | an entry per parent node, shared by every list that names it, re-keyed in place |
| `iter`, `len`, `is_empty`, `leaves`, `assert_unique_leaves` | `nodes()` | use the list |
| `for_each_matching_node` | replaced | egg's matcher uses it; jegg's matcher walks `nodes()` |

## `unionfind` (`UnionFind`)

| egg | jegg | note |
|---|---|---|
| `make_set`, `size`, `find`, `union` | `makeSet`, `size`, `find`, `union` | an `int[]` with path compression; `union` keeps the smaller id as the root |
| `find_mut` | replaced | `find` compresses paths on its own |
| (none) | refuses an id it never made | added |

## `language` (`Language`, `Analysis`)

| egg | jegg | note |
|---|---|---|
| `Language` trait, `define_language!` | `Language<L>`: `children()`, `withChildren(IntList)`, `head()` | records implement it; a node has an operator, a payload and children |
| `discriminant`, `matches` | `head()` | the operator and payload without the children; two nodes match up to children when their heads are equal |
| `children_mut`, `map_children`, `update_children`, `for_each`, `fold`, `all`, `any` | replaced | `withChildren` builds the replacement; `IntList.map` maps the ids |
| `FromOp`, `LanguageChildren` | out by design | parsing; children are an `IntList` |
| `RecExpr` | replaced | `Extracted` is the extracted tree; `TreeBridge` reads and builds a client's own tree |
| `Analysis::make(egraph, enode, id)` | `Analysis.make(graph, node)` | the class id is not passed |
| `Analysis::merge(a, b) -> DidMerge` | `Analysis.join(a, b) -> D` | a semilattice join returning the fact, not a flag |
| `Analysis::modify(egraph, id)` | `Analysis.modify(graph, id)` | ported; may add nodes or merge, must be idempotent |
| `Analysis::remake`, `pre_union`, `allow_ematching_cycles` | not needed yet | `pre_union` serves explanations; the others no client has needed |
| `()` analysis | `Analysis.none()`, `EGraph.withoutAnalysis()` | ported |
| (none) | payloads on e-nodes | added: an operator may carry a value (a mode, a constant); equality and hashing cover it |
| (none) | `TreeBridge<T, L>` | added: a client's tree type in and out of the graph |

## `rewrite` (`Rewrite`, `Applier`, `Condition`)

| egg | jegg | note |
|---|---|---|
| `Rewrite::new(name, searcher, applier)` | `new Rewrite<>(name, lhs, rhs, condition)`; `Rewrite.of(...)` | checks at construction that a pattern right-hand side uses only what the left binds, as egg's |
| `Rewrite::search`, `search_with_limit`, `apply` | `Rewrite.search(graph)`, `search(graph, limit)`, `apply(graph, match)` | ported |
| `Searcher` trait | `Searcher` | the interface a `Rewrite`'s left-hand side is; `Pattern` and `MultiPattern` implement it: `search(graph, limit)` and the variables a match binds |
| `Applier` trait, dynamic rewrites | `Applier`: `apply(graph, eclass, subst) -> IntList` | a function of the graph, the matched class and the substitution; returns the classes to union with the match |
| `ConditionalApplier`, `Condition` trait | `Rewrite.when(condition)`, `Condition` | read at apply time, in the write phase, and may add nodes, as egg's `check` may through its `&mut EGraph`; the method is `holds` |
| `ConditionEqual` | `Condition.equal(a, b)` | ported, with both patterns instantiated before either root is read |
| (none) | `Condition.always()`, `Condition.and(other)` | added |
| `apply_matches`, `apply_one`, `vars` | `Applier.apply`, `Applier.applyTo`; `Pattern.subtermVariables()` | `applyTo` is `apply_one`: it builds, unions with the matched class, and says how many unions changed the graph and how many egg counts as applied (`Applied`) |
| `multi_rewrite!` | `Rewrite.multi(name, lhs, rhs)` | clauses `?var = pattern` built with `MultiPattern.of`; no text syntax in the library; a multi-pattern counts every match as applied, as egg's `apply_matches` does, so a run in which one matches never saturates (`prop`'s `lem_imply`) |

## `pattern`, `subst`, `machine`, `multipattern`

| egg | jegg | note |
|---|---|---|
| `Pattern`, `ENodeOrVar` | `Pattern` (`Node`, `Var`) | built from a prototype node, `Pattern.of(prototype, children...)`; not parsed from text |
| `Pattern::from_str`, `pretty`, `alpha_rename` | out by design | patterns are built from the client's IR, so there is no text to parse |
| `Var` (`?x`) | `String` names | a variable is a name |
| `Subst` | `Subst` | immutable, parallel arrays, an order-free hash; binding returns a new one |
| (none) | payload variables | added: a pattern binds an operator's payload as well as subterms (`Subst.payload`), and a head may match a payload by predicate |
| `Searcher` over `SearchMatches` | `Matcher.search` returning `Match(eclass, subst)` | one record per substitution; a class's matches in node order |
| the compiled machine (`machine.rs`: `Program`, `compile_from_pat`) | a backtracking matcher (`Matcher.matchIn`) | the machine is deferred behind a measurement: #7 |
| `MultiPattern` | `MultiPattern`, `Matcher.search(graph, multi, limit)` | a depth-first join in the class order of the first clause, without egg's compilation; a bare variable is refused as a first clause, as egg's |
| `search_eclass_with_limit`, `search_with_limit` | `Matcher.search(graph, pattern, limit)` | a limit on matches, as the backoff scheduler uses it |

## `run` (`Runner`, schedulers, limits, reports)

| egg | jegg | note |
|---|---|---|
| `Runner::default().with_egraph(g).run(&rules)` | `Runner.of(g, rules).run()` | defaults: 30 iterations, 10,000 nodes, the backoff scheduler |
| `with_iter_limit`, `with_node_limit` | `RunLimits.withIterations`, `withNodes` | ported |
| `with_time_limit` | out by design | no wall-clock limit: a limit that depends on the machine breaks determinism; `RunLimits` bounds nodes, classes and iterations |
| (none) | `RunLimits.withClasses` | added |
| `with_scheduler`, `RewriteScheduler`, `SimpleScheduler` | `new Runner<>(graph, rules, limits, scheduler)`, `Scheduler`, `Scheduler.simple()` | ported; `canStop`, `isBanned`, `reset` |
| `BackoffScheduler` (`with_initial_match_limit`, `with_ban_length`) | `new BackoffScheduler<>(matchLimit, banLength)` | the same algorithm and defaults (1000 matches, 5 iterations): a banned rule's limit and ban both double; `canStop` shortens every ban by the shortest |
| `do_not_ban`, `rule_match_limit`, `rule_ban_length` | not needed yet | per-rule overrides; one threshold for all rules so far |
| `with_hook` | `Runner.withHook(Hook)` | a hook returns `Optional<String>`; a present value stops the run as `Other` |
| `with_expr` | replaced | `addTree` adds a term to the graph |
| `IterationData` | not needed yet | per-iteration client data; the hook sees the graph |
| `StopReason` | `StopReason` (`Saturated`, `IterationLimit`, `NodeLimit`, `ClassLimit`, `Other`) | a sealed interface; no `TimeLimit` |
| `Report`, `Iteration` (counts and times) | `RunReport`, `RunReport.Iteration` | counts only: classes, nodes, matches per rule, banned and skipped rules, applied, unions, repaired; no times, which would differ between runs |
| `print_report` | `RunReport.toString()` | |
| `explain_equivalence`, `explain_matches` on the runner | out by design | proofs |

## `extract` and `lp_extract` (`Extractor`)

| egg | jegg | note |
|---|---|---|
| `Extractor::new(egraph, cost_function)` | `new Extractor<>(graph, costs)` | prices every class once; a snapshot, so a graph that changed after is refused |
| `find_best(eclass)` | `Extractor.extract(eclass)`, `best(eclass)` | a tree with shared subterms as one object; `best` gives the cheapest node and its cost |
| `find_best_node`, `find_best_cost` | `best(eclass).node()`, `.cost()` | |
| `CostFunction::cost(enode, costs)` | `CostFunction.nodeCost(node)`, `cost(node, childCost)` | `nodeCost` is the node's own price; `cost` combines the children's, a sum by default |
| `AstSize`, `AstDepth` | `CostFunction.astSize()`, `astDepth()` | ported; `extractAll` sums `nodeCost`, so under `astDepth` it is not a depth |
| ties | the lowest node order | added: deterministic |
| (none) | `extractAll(roots)`, `extractAll(roots, score)` | added: one node per class across several roots, a shared subterm paid once, a client's whole-selection `score` hook; a heuristic checked against an exact oracle on extraction-gym's graphs (`docs/skills/the-held-move-in-extraction.md`) |
| `LpExtractor`, `LpCostFunction` | out by design | an ILP solver is a dependency a client of this size does not want; `extractAll` is the multi-root answer |

## `explain`, `dot`, `util`, `sexp`, `macros`

| egg | jegg | note |
|---|---|---|
| `Explain`, `Explanation`, `FlatTerm`, `TreeTerm`, `Justification`, `check_proof` | out by design | proofs are Herbie's need, and they change `add` and `union`; a client that builds patterns from an IR has no use for them |
| `Dot` (`to_dot`, `to_png`, `to_svg`, `to_pdf`) | out by design | `PLAN.md` 3.1; the planned text dump of classes after each iteration is #48's `Explore` CLI |
| `util` (hash maps, `UniqueQueue`, `Instant`, `pretty_print`) | `IntArray`, `IntList`, `LinkedHashMap` | internal; the maps that could reach an id are insertion-ordered |
| `sexp`, `symbolic_expressions` | out by design | no parsing in the library; the tests have a `Term` parser for egg's s-expressions |
| `define_language!`, `rewrite!`, `multi_rewrite!` macros | records and constructors | Java has no macros; a rule is `Rewrite.of(name, lhs, rhs)` |
| `Symbol`, `SymbolLang` | `String` | |

## What the ports show

egg's four ported test suites each run against egg's own counts (`Egg(iterations, nodes,
classes)` records, read by running egg with `RUST_LOG=egg=info`; see
`docs/skills/checking-a-port-against-egg.md`):

| egg's test file | jegg | state |
|---|---|---|
| `simple.rs` | `SimpleRulesTest` | ported, counts pinned |
| `prop.rs` | `PropRulesTest` | ported in full (`Bool`, `ConstantFold`, egg's rules and three tests), counts pinned; `prove_chain` ends at egg's 31 nodes and 12 classes in 6 iterations where egg runs 20 (the multi-pattern quirk above) |
| `lambda.rs` | `LambdaTest` | ported, counts pinned |
| `math.rs` | `MathTest` | ported, counts pinned |
| `datalog.rs` | not ported yet | multi-patterns are in the library; the port itself is the second pull request of #34 |

jegg's own tests add what egg lacks: `EGraphRebuildTest` (deferred against eager rebuilding on
random graphs), `DeterminismTest` (the same run in fresh JVMs, byte for byte), the extraction
oracles (`ExtractorTest`, `ExtractAllEnumerationTest`, `ExtractionGymTest` against
extraction-gym's graphs) and the matcher tests (`PatternMatchTest`).
