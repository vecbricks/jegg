# Why not write your own e-graph: the pitfalls jegg already handles

An e-graph looks small from the outside: a union-find over ids, a hashcons from nodes to
classes, and a loop that keeps applying rules. Writing that much takes an afternoon. What takes
longer is everything that goes wrong once rules interact, facts attach to classes, and extraction
has to pick one term. jegg is a port of [egg](https://github.com/egraphs-good/egg), whose paper
and code settled those questions; this page lists what it already answers, and when a hand-rolled
version is the better call.

| Pitfall in a hand-rolled version | What goes wrong | Where jegg encodes the answer |
|---|---|---|
| Congruence: equal children must mean equal parents | Merging two classes makes every parent node equivalent too; done eagerly it costs a rebuild per merge | `EGraph.rebuild`, `EGraph.repair` (deferred once per iteration), `EGraphRebuildTest`; `docs/concepts.md` "Merge, then rebuild" |
| The hashcons and dropped nodes | A node removed from a class is still a fact about the graph: adding it again must find its class, not create a second one | `EGraph.lookup`, `EGraph.retainNodes` and the pruned memory; `GroundLookupTest` |
| Determinism | Ids, match order and extraction ties that depend on hash order make a run impossible to compare or reproduce | Insertion-ordered ids, fixed match order (`Matcher`), ties by node order (`Extractor`); `DeterminismTest`, `DeterminismProbe`; `docs/concepts.md` "Determinism" |
| Rule scheduling | A rule that explodes its match count floods every later iteration; banning it for too long hides matches | `BackoffScheduler`, `Scheduler`; `RunnerTest` (a banned rule and its return) |
| Facts per class | An analysis that is not a semilattice join, or is not re-made after a merge, silently keeps stale facts | `Analysis.make`, `join`, `modify`, checked by `EGraph.checkAnalysisInvariant`; `ConstantFoldTest`, `EGraphMergeTest` |
| Extraction | The cheapest term is not the cheapest per class when roots share a DAG; choosing each root alone may split a shared subterm | `Extractor.extractAll`, `Selection`, a client `score` over a whole selection; `ExtractAllEnumerationTest`, `ExtractionGymTest` |
| Rules with several premises | A join over shared variables, egg's multi-pattern, is not expressible as one pattern | `MultiPattern`, `Searcher`; `MultiPatternTest`, `DatalogTest`, `PropRulesTest` |

## When a hand-rolled e-graph is the right call

If all of these hold, a small e-graph or a fixed rewrite pass is less code than the dependency:

- the rule set is fixed and its rules do not interact, so their order never matters;
- no analysis fact has to be read by a rule;
- one root is extracted, with no sharing across roots;
- no run has to be compared with another, so match order and ids can float.

The moment one of them fails, the mechanisms above come back one by one, and each has a failure
mode that is quiet rather than loud: a missing congruence, a duplicate node, a stale fact, an
extraction that is not the one the cost function describes.

## What jegg does not do

Proofs, s-expression parsing, Graphviz output and ILP extraction are deliberately out;
[`docs/egg-comparison.md`](egg-comparison.md) lists every egg item jegg does not port, with the
reason; the modes beyond egg's parity are tracked in the repository's issues, #84 among them.
