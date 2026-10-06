# The ideas, in jegg's terms

For a reader who has not met an e-graph. Each idea names the jegg type that
embodies it and the section of the egg paper (Willsey et al., "egg: Fast and
Extensible Equality Saturation", POPL 2021) that gives the algorithm. The types
are in `io.github.vecbricks.jegg`; the Javadoc is at
https://vecbricks.github.io/jegg/.

## A term is an e-node; equal terms share an e-class

An **e-node** is an operator with its children. In jegg a client's language is a
sealed set of records implementing `Language<L>`: each record is an operator,
whatever fields it carries beyond the children are its **payload** (a constant's
value, a variable's name, an overflow mode), and its children are an `IntList`
of e-class ids. Two e-nodes are equal when operator, payload and children are
equal, which is what a record gives for free - the one trap is a record with an
`int[]` field, which compares by reference; `IntList` exists so that children
never do.

An **e-class** (`EClass`) is a set of e-nodes that stand for equal terms. The
class has an id, and an e-node's children are ids, so one node stands for every
combination of its children's terms: an e-graph with a hundred classes can hold
more terms than there are atoms. The paper's section 2 draws it.

## The hashcons: every e-node once

`EGraph.add(node)` first canonicalises the node's children (each id replaced by
its class's root) and looks it up in the **hashcons**, a map from e-node to
class. A node already there returns its class; a new one gets a new class. So an
equal term added twice is one node in one class. `EGraph.lookup` asks the same
question without adding. (Paper, section 2.1 and figure 4, lines 1-11.)

## Merge, then rebuild: congruence restored in a batch

`EGraph.merge(a, b)` unions two classes in the **union-find** (`UnionFind`,
`int` ids, the smaller id kept as the root) and does nothing else but note the
class on a worklist. After a merge the graph is out of order in two ways: the
hashcons still keys nodes by their old children, and two nodes that became equal
by the merge (`f(a)` and `f(b)` once `a = b`, the **congruence**) sit in
different classes. `EGraph.rebuild()` repairs both, class by class, until no
merge is pending - the paper's contribution (section 3, figure 4 lines 27-53):
rebuilding once after a batch of merges instead of after each is what makes
equality saturation fast, and `benchmarks/RebuildBenchmark-jdk25-results.txt`
measures the factor on this port.

Between a merge and the rebuild, `lookup` may miss a node whose children were
merged; the runner always rebuilds before searching. `EGraph.checkInvariants()`
throws if the hashcons or congruence invariant is broken; the tests call it.

## An analysis: a fact per class, kept through merges

An `Analysis<L, D>` attaches a fact `D` to every class: `make` computes a
node's fact from its children's facts, `join` combines the facts of two classes
being merged (it must be a semilattice join: commutative, idempotent), and
`modify` may act on a class whose fact changed - constant folding adds the
constant as a node and merges it in. The graph keeps the facts current through
rebuilds (paper, section 4.1, figure 9). `ConstantFoldTest` is the smallest
example; `MathTest`'s analysis is egg's, with the pruning of a folded class to
its constant (`EGraph.retainNodes`).

## Patterns, matching and rewrites

A `Pattern<L>` is a tree of pattern nodes and variables (`Pattern.var("x")`).
A pattern node has a `Pattern.Head`: `Pattern.of(prototype, children...)`
matches the prototype's operator and payload exactly; `Pattern.binding(...)`
matches any payload and binds it to a **payload variable**, so a rule can match
"an add in any overflow mode" and carry the mode to its right-hand side. A
`Subst` holds the bindings, subterm variables to class ids and payload variables
to values.

`Matcher.search(graph, pattern)` finds every match in the graph, class by class
in id order, nodes in insertion order; where the root head names its node class
(`Pattern.Head.type`, which jegg's own heads do), only the classes holding such
a node are visited; a pattern node whose head has a key (`Pattern.Head.key`)
reads the class's table, its nodes sorted by head with their children laid
flat, so it touches only the run of its head and no node object until a match
is complete; a nested pattern node
whose variables are all bound by the time it is reached is looked up in the
hashcons, as egg's compiled matcher looks it up, where the child's class has
pruned nodes (which only the hashcons still knows) or where its run of the head
is long; a node's nested children are matched the one with more free variables
first and the smaller among equals, as egg's compiler orders them, so the later
ones are ground or narrower. The order is fixed by the graph and the pattern,
which is part of the determinism jegg promises. A `Rewrite` is a name, a
left-hand `Searcher` (a pattern, or a `MultiPattern`: clauses `?var = pattern` joined on shared variables, egg's
multi-pattern), a right-hand `Applier` (a pattern to instantiate, a
multi-pattern's clauses, or a function of the graph and the substitution:
`Rewrite.dynamic`) and a `Condition` read at apply time.
(Paper, section 2.2; egg's compiled matcher, `machine.rs`, is not ported as such, two of
its devices are: the lookup and its order of the nested nodes.)

## The runner: equality saturation

`Runner.run()` repeats: search every rule on the graph as it stands, apply every
match found (adding right-hand sides and merging them with the matched classes),
rebuild once; and stops when an iteration changes nothing and counts nothing as
applied (**saturation**: a rule whose right-hand side is a multi-pattern counts
every match, as egg's does, so a run in which one matches does not saturate), a
`RunLimits` limit (iterations, nodes, classes) is hit, or a `Runner.Hook` asks.
A `Scheduler` decides which matches are applied: `BackoffScheduler`, egg's
default, bans a rule whose matches pass a threshold for a few iterations and
doubles both on each ban, so associativity and commutativity do not swamp the
rest. The `RunReport` says what every iteration did. (Paper, section 2.3,
figure 5b.)

## Extraction: the best term back out

An `Extractor` prices every class under a `CostFunction` (`astSize`,
`astDepth`, or the client's table) by a fixed point from the leaves up, and
`extract(root)` returns the cheapest term of a class as an `Extracted` tree -
egg's extraction (paper, section 4.3).

`extractAll(roots)` is jegg's own: one node per class for every class the roots
reach, chosen so that the sum of the chosen nodes' costs is small with a shared
subterm paid once, returned as a `Selection` whose terms share objects where
they share classes. A compiler with many outputs over shared prefixes needs
this; egg's per-root extraction can give two outputs two forms of one prefix.
The exact problem is NP-hard; the heuristic is a greedy start and an
incremental descent, checked against an exact oracle on extraction-gym's graphs
of up to 300 nodes (`ExtractionGymTest`). A `score` hook lets the client price a whole
selection its own way.

## Threads

An `EGraph` is confined to one thread at a time: nothing in it is synchronized, so another thread
may use it only after a handover that makes its writes visible (a `Future`, an executor's
`submit`, a lock, a volatile write and read), as for any object that is not thread-safe. The same
holds for a `Runner` and an `Extractor` built over it and for a `BackoffScheduler`, which holds
one run's bans. Separate e-graphs share no state (the only statics are immutable constants), so
one graph per thread needs no coordination. The values that cross the API are immutable and safe
to share: language nodes (they are hashcons keys, so a language must make them immutable),
`IntList`, `Subst`, `Pattern`, `MultiPattern`, `RunLimits`, `Extracted`, `Selection`,
`RunReport` and `StopReason`; a `Rewrite` is as safe as the `Applier`, `Condition` and `Searcher`
it was given. A client's `Analysis`, `Condition`, `Applier` and `CostFunction` are called from
the thread that calls the graph, and need to be thread-safe only if one instance is given to
graphs that run at the same time.

## Determinism, and the three places it is fixed

egg does not promise that two runs give the same ids. jegg does: ids are
assigned in insertion order, rules are searched and applied in the order given
and their matches in the matcher's order, and the extractor breaks ties by node
order. Every map whose iteration order could reach an id is insertion-ordered,
and `DeterminismTest` forks fresh JVMs over a language with identity-hashed
names to check that no hash order leaks.
