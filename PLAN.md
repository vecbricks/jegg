# Plan: jegg, an e-graph library for Java 25, ported from egg

<!-- Written in vecbricks/varka on 5 September 2026 as sql/varka/plans/PLAN_EGRAPH_PORT.md,
     the record of the owner's decision to port egg as a product of its own; moved here on
     3 October 2026 when this repository was created and named. Varka keeps a pointer. The
     sections follow Varka's task template where they apply; 3.4 registers size and effort
     rather than op counts, and 6 measures the library rather than a kernel. -->

*3 October 2026: the owner named the library `jegg` and the repository was created. Group id
`io.github.vecbricks`, which the GitHub organisation verifies for Maven Central (no vecbricks
domain exists to verify `io.vecbricks`); package `io.github.vecbricks.jegg`. Licence
Apache-2.0, with egg's MIT notice in NOTICE. The name is shared with JEgg, a dormant 2004
active-object framework whose package is `jegg`; the package here avoids it.*

## 1. Where this came from

`sql/varka/plans/m8/SCOPE.md` item 11, "Physical representation as a compiler
decision", concluded that the engine Varka is meant to become - every Spark
type and expression, several physical forms per logical value - needs an
extractor over equivalence classes: conversion nodes as ordinary nodes,
semilattice analyses carrying type, encoding, nullability and range, and a
cost-driven choice of representation over a whole projection. The owner's
question of 5 September 2026: there is no e-graph library in Java, so port
egg (Willsey et al., POPL 2021; `github.com/egraphs-good/egg`, MIT) to Java
25+ for Varka's future use - as a product of its own, in a dedicated
repository under `github.com/vecbricks`, not as a module of the fork. The
library has no Spark dependency and is usable outside Varka; Varka consumes
it the way it consumes Arrow, as a versioned dependency, when item 11 needs
it.

The ecosystem check behind the premise: no maintained pure-Java e-graph
library was found. The JVM has Risegg, the Scala engine of sketch-guided
equality saturation for RISE (a research artifact, not a library); Julia has
Metatheory.jl; everything else is egg and its successor egglog, both Rust,
both MIT, both active (egg last updated December 2025). egglog is the more
powerful system - Datalog-based, with a query planner, incremental execution
and composable analyses - and the wrong size for this: its strengths are
multi-pattern joins over large fact sets and re-saturation of a changing
database, neither of which a 64-node IR with a rule set in the dozens needs.
egg's model is the one item 11 asked for, and its paper gives the pseudocode
for the parts that matter.

## 2. The admission check, done

**egg's four mechanisms are item 11's four needs.** Read against the paper
(sections 2-5) and the item: a conversion between physical forms is an e-node
in the class of the logical value; `dayRange` and `inputBounds` are e-class
analyses (make, join = hull, modify); representation selection is extraction
under a cost function, which the paper shows is itself an analysis when the
cost is local (section 4.3); and the Runner's node and iteration limits give
the deterministic bound item 11 requires instead of a timeout. Nothing item
11 lists needs a mechanism egg lacks.

**The size is a port, not a project.** egg is about 5000 lines of Rust
including tests and documentation (paper, footnote 8). Its core - union-find,
hashcons, e-class map with parent lists, `add`/`merge`/`find`, deferred
`rebuild`/`repair`, the analysis hooks, e-matching, the Runner with its
backoff scheduler, the extractor - is the part this plan ports; proof
production (`Explain`), s-expression pattern parsing and the ILP extractor
are not needed by a client that builds patterns from an IR and measures its
costs.

**Java 25 fits the data structures.** E-nodes are records over int e-class
ids (structural equality, the property Varka's IR records already rely on);
the language is a sealed hierarchy with exhaustive `switch`; the union-find is
an `int[]`; the read-only match phase parallelises over rules or classes
without locks. Nothing in Rust's ownership model is load-bearing in egg.

**One property egg does not give and Varka needs: determinism.** egg accepts
that iteration order over hash maps varies run to run; its results are
set-valued (the e-class a term lands in), so that is harmless there. Varka's
pinned renderings, shape hashes and CI reproducibility require the extracted
term to be a function of the input. The port therefore fixes iteration order
at the three points where it reaches the output (section 3.1) - a deliberate
departure from egg, recorded here so it is not "fixed" back.

**What the check would have rejected:** a need for egglog's relational
engine (it is not there at Varka's graph sizes); a language-specific port
tied to today's `VarkaVectorIR` (the library must outlive the Java-Catalyst
rewrite, so its language is generic and the IR mapping is a client); or a
port whose cost of use is boxing every id (the ids are `int` throughout, or
the point of the flat design is lost).

## 3. The design

### 3.1 The library

A pure-Java library in its own repository: Maven, Java 25, JUnit 5, JMH, no
Spark and no Arrow dependency, its own GitHub Actions and release cadence, so
it builds, tests, benchmarks and ships alone. `sql/varka/engine` is the
precedent for the build shape (Java 25 release level, surefire and JMH
wiring), not for the location: that module is Varka's own kernel code, this
is a library Varka merely uses.

| | |
|---|---|
| repository | `github.com/vecbricks/jegg` (named 3 October 2026) |
| artifact | `io.github.vecbricks:jegg`, versioned and published like any dependency |
| package | `io.github.vecbricks.jegg`, not `org.apache.spark` |
| public surface | `EGraph<L, A>`, `Language<L>`, `TreeBridge<T, L>`, `Analysis<L, D>`, `Pattern`, `Subst`, `Rewrite`, `Condition`, `Applier`, `Runner`, `RunLimits`, `RunReport`, `Scheduler`, `Extractor`, `Selection`, `CostFunction` (3.2 added the bridge, the substitution's payloads, the report and the selection) |
| Varka's side | nothing until item 11: then one dependency on a pinned version, and the client mapping |

The egg-to-Java mapping, component by component:

| egg | Java 25 | note |
|---|---|---|
| `Id`, `UnionFind` | `int` ids; `int[] parent` with path compression | ids are never boxed on any hot path |
| `Language` trait, `define_language!` | `interface Language<L>`: an e-node is an operator, a payload and its children as an immutable int list, `equals`/`hashCode` over all three, `mapChildren`; a client's language is a sealed hierarchy of records implementing it, and `TreeBridge<T, L>` adds a client's tree through a destructure function and builds one back through a construct function (3.2) | the one real trap: a record with an `int[]` component compares the array by reference, so children live in an immutable `IntList` (or the record overrides `equals`/`hashCode`), or hashconsing silently fails |
| hashcons `H` | `HashMap<L, Integer>` first; an open-addressing map on the record hash later if measured | egg's canonicalise-then-lookup ports verbatim |
| `EClass`: nodes, parents, data | node list kept sorted by operator (egg does, for binary search in matching), parent list of `(node, class)` pairs, analysis data | plus a classes-by-operator index for the matcher |
| `add`, `merge`, `find`, `canonicalize` | paper Fig. 4, lines 1-27, line for line | `merge` only unions and enqueues; nothing is repaired in it |
| `rebuild`, `repair` | paper Fig. 4, lines 27-53; analysis maintenance per Fig. 9 | the worklist dedup is the whole speedup; the phase split (read every match, then write, then rebuild once) is the equality-saturation loop of Fig. 5b |
| `Analysis` trait | `interface Analysis<L, D> { D make(EGraph, L); D join(D, D); void modify(EGraph, int) }` | `join` must be a semilattice join and `modify` idempotent, or `rebuild` may not terminate (paper 4.1.1) |
| `Pattern`, `Subst`, `ematch` | a pattern tree of operator nodes and variables, where a node's payload is matched by a predicate or bound to a payload variable and `Subst` holds payload bindings beside class ids (3.2); a naive recursive matcher first, egg's compiled backtracking machine (`machine.rs`) behind a measurement later | the largest piece; at 64-node graphs the naive matcher is likely enough (prediction 5) |
| `Rewrite`, `Applier`, `Condition` | a name, a left pattern, and a right-hand side that is a pattern or an `Applier` function; conditions read analysis data and the graph, at apply time, and may add nodes (egg's `ConditionEqual`) | dynamic rewrites are functions of `(EGraph, matched class, Subst)` |
| `Runner`, `BackoffScheduler`, `StopReason` | an iteration loop with `RunLimits` (nodes, classes, iterations; no wall-clock limit by default) and per-rule backoff | the limit that makes extraction a function of the input |
| `Extractor`, `CostFunction` | bottom-up fixed point over local costs, per paper 4.3, for one root; and `extractAll` over several roots, one node chosen per e-class across all of them with a shared node paid once, returning a `Selection` as a DAG (3.2): a greedy start, then a descent over the union of the roots that tries each class's other nodes and keeps a change when the selection scores lower - a change that brings new classes in is held while their selected parents are offered the nodes that use them, and kept if the whole scores lower; evaluated incrementally, so a candidate costs what it changes; a heuristic, since the ILP is out, checked against an exact oracle on extraction-gym's small graphs | the client's cost table is Varka's measured register; a hook scores a whole selection so the client can run its own prediction over the candidate |
| `Explain`, `RecExpr` parsing, `LpExtractor`, `dot` | out | proofs are Herbie's need; Varka builds patterns from IR; no ILP dependency |

**Determinism, fixed at three points.** Ids are assigned by insertion order;
rewrites are searched in declared order and their matches applied in the
matcher's order (class id, then node insertion) before the write phase; the extractor
breaks cost ties by the lowest node id. Every map whose iteration order can
reach an id assignment or a merge order is insertion-ordered
(`LinkedHashMap`/`ArrayList`), and section 5 has a test that runs the same
saturation in fresh JVMs and compares the serialised graphs.

### 3.2 What Varka's client asks of the API, 3 October 2026

Read against `sql/varka/plans/m8/SCOPE.md` item 11, the IR and the cost model, on the owner's question of
what API Varka would need. egg's shape covers most of it; four things are not in egg's API as it
stands, and the tables above carry them.

1. **A bridge between trees and e-nodes, with payloads.** Varka's IR records hold child nodes
   where e-nodes hold child ids, and most nodes carry data beyond children: `IntArith` an op and
   an overflow mode, `ConstDivide` a divisor and a bound, `GuardedRange` its bounds, `TruncDate`
   a level, every leaf a lane. So an e-node is operator, payload and children, equal over all
   three, and `TreeBridge` adds a client tree by a destructure function and builds one back by a
   construct function. Without it every client writes a mirrored node type by hand.
2. **Patterns that bind payloads, not only subterms.** egg's variables stand for subterms;
   Varka's rules match `IntArith(ADD, ?mode, ?x, ?y)` for any mode and carry the mode to the
   right-hand side, or match a trunc level or a divisor. A pattern node takes a payload
   predicate or a payload variable, and `Subst` holds payload bindings beside class ids.
3. **Extraction over several roots sharing a DAG, with a cost the client measures.** A
   projection has many outputs sharing prefixes, and Varka's cost model prices groups of
   outputs with that sharing (VARKA-58). egg's extractor costs each root as a tree, so two
   outputs sharing a prefix can be given different forms and the sharing lost. `extractAll`
   chooses one node per e-class across all roots, pays a shared node once and returns a
   `Selection` as a DAG, never re-expanded; it is greedy, since the ILP is out, and a hook
   scores a whole selection so the client can run its byte and call-site prediction over the
   candidate, because item 11 says costs stay measured and never modelled.
4. **Analyses fit a semilattice; Varka's range analysis today does not.** `dayRange` as item 11
   describes it is make, join and modify. `VarkaRangeAnalysis` as implemented is top-down: the
   parent passes the kind, days or int, and a guard policy to the child, where an e-class
   analysis is bottom-up. The way out is item 11's first design input, the physical form
   explicit on the value, so a day and an int are different classes and the kind stops being
   context; the guard policy becomes a condition read at the consumer's rule, which sees the
   graph and the substitution. Nullability and encoding ride in the same data record. jegg
   changes nothing for this; the client does.

Two smaller points. Varka's literals are slots bound at run time, so there is no folding with
values: the analysis Varka wants is batch invariance, and a folding rule produces a scalar
expression over slots the emitter hoists, so the `math` suite's constant folding is a port test
and not the Varka case. And the run must be cheap or cached: if saturation runs before the shape
key is computed it runs on every compile, so either prediction 4's five milliseconds holds or
Varka keys the shape by the input IR and caches the extracted form with it - the client's
decision, under item 11. A `RunReport` (iterations, matches per rule, nodes and classes, the stop
reason) is returned by the runner so a client's plan can record what a run did, as Varka's
emit trace does.

### 3.3 What is deliberately unchanged

* Varka's IR, compiler and emitter: this plan builds a library and touches
  none of them. The client - `VarkaVectorIR` mirrored as a `Language` with
  e-class ids for children, the conversion nodes, the analyses, the cost
  table - is item 11's work, scheduled there (milestone 7, or earlier if
  item 1 lands a second physical representation).
* egg's algorithms: `add`, `merge`, `rebuild`, `repair` and the analysis
  invariant are ported as the paper states them; the departures are
  determinism (3.1) and omissions (`Explain`, parsing, ILP), each named.
* Java's standard collections for the first version; specialised maps only
  where the JMH numbers in section 6 say so.

### 3.4 Size and effort, registered by component

| component | lines (estimate) | acceptance |
|---|---|---|
| ids, union-find | 100 | unit tests; path compression keeps `find` idempotent |
| `Language`, `IntList`, e-node equality, `TreeBridge` | 300 | equality and hashing over operator, payload and children; the array trap covered by a test; a tree added and built back is equal to itself |
| hashcons, e-classes, `add`/`find`/`canonicalize` | 300 | hashcons invariant checked after every `add` (paper Def. 2.7) |
| `merge`, worklist, `rebuild`, `repair` | 150 | congruence invariant after `rebuild` (Theorem 3.1) |
| `Analysis` and its maintenance | 150 | analysis invariant after `rebuild` (paper 4.1) |
| patterns, substitution with payload variables, naive matcher | 300 | ported `simple` and `prop` tests; a payload bound and carried to the right-hand side |
| `Rewrite`, `Condition`, `Applier` | 150 | conditional and dynamic rewrites from the `lambda` suite |
| `Runner`, `RunLimits`, backoff scheduler | 250 | saturation detection; limits hit deterministically |
| `Extractor`, `CostFunction`, `extractAll` and `Selection` | 300 | brute-force comparison on small graphs; the shared prefix kept in one form over two roots (prediction 6) |
| ported test suites (`math`, `lambda`, `prop`, `simple`) | 800-1200 | same right-hand sides land in the same classes as in egg |
| JMH harness and results | 150 | section 6 |
| **total** | **2800-3300** | |

Weeks of one careful engineer, or several cheaper agents by component with
the ported suites as the oracle; the components above are ordered so each
can be built and accepted before the next (section 8).

## 4. Files

In the library's repository:

| file | what |
|---|---|
| `pom.xml` | the build, on `sql/varka/engine/pom.xml`'s shape minus Arrow; publishing configured from the start |
| `.github/workflows/` | build, test at the Java 25 release level, JMH on demand |
| `EGraph.java`, `UnionFind.java`, `EClass.java`, `IntList.java` | the graph |
| `Language.java`, `Analysis.java` | the two client interfaces |
| `Pattern.java`, `Subst.java`, `Matcher.java` | matching |
| `Rewrite.java`, `Condition.java`, `Applier.java` | rules |
| `Runner.java`, `RunLimits.java`, `Scheduler.java`, `BackoffScheduler.java`, `StopReason.java` | the loop |
| `Extractor.java`, `CostFunction.java` | extraction |
| `src/test/java/` | invariant tests, determinism test, the four ported suites |
| `src/jmh/java/` and `benchmarks/` | section 6, with the provenance header Varka's result files carry |
| `PLAN.md` (this file, moved) and `README.md` | the record and the front door |

In Varka, now: `sql/varka/plans/m8/SCOPE.md` item 11 pointing here, and
`PLAN_EGRAPH_PORT.md` reduced to a pointer. In Varka, at
item 11's time: the dependency on a pinned version and the client mapping,
planned there.

## 5. Tests, and what each is for

* **Invariants, as debug checks and as tests.** The hashcons invariant after
  every `add` and `merge`+`rebuild`; the congruence invariant after
  `rebuild` (no two congruent nodes in different classes); the analysis
  invariant (each class's data equals the join of `make` over its nodes).
  The failure they catch: a `repair` that forgets to re-canonicalise a
  parent, the paper's whole subject.
* **Deferred against eager rebuilding.** The same saturation run with
  `rebuild` after every `merge` and once per iteration must yield identical
  graphs (paper 3.4 ran this over its suite); the failure is a merge that
  reads a stale hashcons.
* **The ported suites.** egg's `tests/simple.rs`, `prop.rs`, `math.rs` (with
  its constant-folding analysis) and `lambda.rs` (the paper's Figures 10-11:
  an analysis carrying free variables and constants, conditional rewrites,
  and a dynamic capture-avoiding substitution). Each test adds a term, runs
  the rules, and asserts the right-hand side is in the term's class - the
  same assertions egg makes, so a divergence names the component.
* **Determinism.** One saturation serialised (nodes, classes, data,
  extracted term) from several fresh JVM invocations and from repeated
  in-process runs must be byte-identical; the failure it catches is hash
  iteration order reaching an id or a merge.
* **The array trap.** Two structurally equal e-nodes built from separate
  child lists must hashcons to one class; the failure is reference equality
  on children.
* **Extraction.** Against brute-force enumeration of all represented terms
  up to a depth on small graphs; ties broken by lowest node id, asserted.
* **Limits and scheduling.** Associativity plus commutativity over a small
  term: the Runner must stop at the node limit deterministically, and the
  backoff scheduler must ban and re-admit the expansive rule the way egg's
  does (its iteration log is the fixture).
* **A Varka-shaped smoke test**, no Varka dependency: a toy date language
  with `AddDays`, `Slot`, `Col`, `Year` and `Month` over a civil decomposition
  node, one folding rule for slot offsets whose right-hand side is a scalar
  expression over the slots, a cost function that reads a table, and two
  roots, `year(date_add(d, s1))` and `month(date_add(d, s1))`, extracted
  together: the decomposition is chosen once and shared, where extracting
  each root alone could choose two forms. The failure it catches is an API
  that cannot express item 11's first use, through the bridge, a payload
  variable and `extractAll` at once.

## 6. The measurement

A library's measurement is its own harness, committed under the repository's
`benchmarks/` with the provenance header Varka's result files carry, on an
idle machine. Cross-language comparison with egg's Rust
numbers is not attempted; the ratios the paper established are.

* **Deferred against eager rebuilding**, on the ported `math` and `lambda`
  suites: time to saturation with `rebuild` once per iteration against after
  every `merge`, and the count of `repair` calls in each mode, per test.
* **Absolute cost at Varka's size**: a 64-node graph over the toy date
  language with 20 rules, time to saturation or limit, and extraction time.
* **Naive matcher against the compiled machine**, if and when the machine is
  built: the same suites, both matchers.

### 6.1 Predictions, registered before the run

1. Deferred rebuilding is at least 5x faster than eager on the largest
   ported tests and near 1x on the smallest - the paper's Fig. 7 shape, a
   speedup that grows with rewrites applied - and the `repair` count tracks
   the time (its Fig. 8).
2. Every ported egg test passes with the right-hand side in the expected
   class; none needs a semantic change to pass.
3. The determinism test passes across ten fresh JVM runs with no seed
   control.
4. A 64-node graph with 20 rules saturates or hits its limit in under 5 ms
   and extracts in under 1 ms, so per-shape compilation cost stays
   negligible beside emission.
5. The naive matcher is within 3x of the compiled machine at that size, so
   the machine can wait for a client that needs it.
6. On the smoke test's two roots, `extractAll` keeps the shared decomposition
   in one form and its selection costs less than the two single-root
   extractions summed with the shared node paid twice; the greedy choice
   matches a brute-force enumeration on every graph small enough to
   enumerate.

## 7. Risks

1. **Reference equality on children** in a record-based language - the
   hashcons never finds anything and the graph is a tree. The array-trap
   test, and `IntList` as the only child container.
2. **Nondeterminism leaking through iteration order** - correct results,
   different ids, different extraction. The multi-JVM determinism test; the
   three fixed points in 3.1.
3. **Expansive rules** (associativity, commutativity, distributivity)
   growing the graph past any bound - the note's own open question. Node
   and iteration limits plus backoff, tested in section 5; the client's rule
   sets are small by design.
4. **An analysis whose `join` is not a semilattice join or whose `modify` is
   not idempotent** - `rebuild` cycles. The analysis-invariant test, and a
   debug-mode check that `join(a, b) == join(b, a)` and `join(a, a) == a`
   over the data seen.
5. **Matching cost at larger graphs** if a client grows past Varka's sizes.
   Prediction 5 says when the compiled machine is due; it is a contained
   component.
6. **Scope creep toward egglog** (multi-patterns, incremental runs, proofs).
   Section 3.2 names what is out; a need for any of it is a new plan.
7. **A second repository's overhead**: its own CI, publishing and
   versioning, and a dependency Varka's build must resolve without a snapshot
   repository in the way. Configure publishing in the first commit, release
   from tags, and pin the version on Varka's side when item 11 adds it;
   never a snapshot dependency in the fork.

## 8. Sequencing

Each commit green alone, with its tests:

1. The repository: build, CI, publishing configuration, licence (Apache-2.0)
   and attribution to egg (MIT), this plan moved in as `PLAN.md` - done
   3 October 2026; then ids,
   union-find, `IntList`, `Language` with payloads, `TreeBridge`, hashcons,
   `add`/`find`/`canonicalize`; the array-trap, round-trip and
   hashcons-invariant tests.
2. `merge`, the worklist, `rebuild`/`repair`; the congruence-invariant and
   deferred-against-eager tests.
3. `Analysis` and its maintenance; the constant-folding analysis and the
   `math` suite.
4. Patterns, substitution with payload variables, the naive matcher;
   `Rewrite`, `Condition`, `Applier`; the `simple` and `prop` suites.
5. `Runner`, `RunLimits`, `RunReport`, the backoff scheduler, the
   determinism fixes and test; the limits-and-scheduling test.
6. `Extractor` and `CostFunction`, then `extractAll` and `Selection`; the
   brute-force and tie-break tests; the Varka-shaped smoke test.
7. The `lambda` suite (dynamic and conditional rewrites end to end).
8. The JMH harness, one regeneration on an idle machine, section 9.
9. Optional, by prediction 5: the compiled matching machine behind a
   measurement.

The client - Varka's IR as a `Language`, conversion nodes, the analyses and
the cost table - is not in this sequence; it starts under item 11 when a
second physical representation exists to choose between.

## 9. Outcome

Measured on 4 October 2026, with the harness of section 6 (`src/jmh/java`, run under
the `bench` profile, see `benchmarks/README.md`): JDK 25.0.4 on an AMD Ryzen AI 9
HX PRO 370, the runs pinned to its four Zen 5 cores, an idle machine. Every
number below is in a file under `benchmarks/`, which names the commit it was
measured from. Cross-language comparison with egg was not attempted, as section
6 says; egg's own times for two of the lambda runs, observed while porting the
suite (#19), are quoted at the end as context only.

### 9.1 The numbers

**Deferred against eager rebuilding** (`RebuildBenchmark-jdk25-results.txt`,
commit 198a02b). One saturation of each ported prop and lambda test, with
`rebuild` once per iteration (deferred, the runner's way) against after every
merge (eager, the paper's comparison). On the twelve runs below 300 nodes the
two modes are within each other's error, except `lambda_compose_many` (284
nodes), where deferred is 1.3x faster (12.0 against 15.8 ms). On the three runs
past ten thousand nodes the paper's shape appears: `prove_fold` (31,059 nodes)
takes 0.6 s deferred and 260.7 s eager, 430x, repairing 4,536 classes against
25,706; `lambda_fib` (14,582 nodes) takes 27.4 s deferred and did not finish
eager within a ten-minute cap, over 22x; `lambda_function_repeat` (32,636
nodes) takes 11.1 s deferred and did not finish eager either, over 54x. The
repaired-class count moves the same way as the time but by far less (5.7x on
`prove_fold` against 430x), because every `rebuild` also re-canonicalises every
class and sweeps the whole hashcons, so an eager run pays a whole-graph pass
per merge (#15).

**The absolute cost at Varka's size** (`ProjectionBenchmark-jdk25-results.txt`,
commit 70e7016). A projection of 64 e-nodes over the toy date language
with integer arithmetic, 20 outputs, 20 rules (`Projection`, asserted by
`ProjectionTest`), run to a node limit a compiler might set. The limits
overshoot, since the runner checks them between iterations (#14): a limit of
200 ends after 2 iterations at 278 nodes and 107 classes, a limit of 1,000
after 4 at 1,909 nodes and 805 classes. egg's default of 10,000 is not
measured: under these rules the run to it takes 30 s, 23
iterations to 10,197 nodes.

| operation, warm (average, 5 forks x 10 s) | limit 200 | limit 1,000 |
|---|---|---|
| `saturate`: add the term, run to the limit | 0.29 ms | 12.3 ms |
| `extractAll` over the 20 roots, the DAG with sharing | 41.8 ms | 85.6 ms |
| `extract`, the cheapest tree of each of the 20 roots | 24 us | 211 us |
| bytes allocated by `saturate` | 1.5 MB | 27.2 MB |
| bytes allocated by `extractAll` | 58.8 MB | 122.9 MB |

Cold, the first call in a fresh JVM (single shot, 5 forks): `saturate`
5.0 ms at the 200 limit and 38.3 ms at 1,000; `extractAll`
89 and 191 ms; the trees 2.0 and 6.3 ms. With
`-XX:+UseCompactObjectHeaders` the bytes allocated fall by 13 to 14% on
`saturate` and 16 to 18% on `extractAll`, and the times move by under
4%, within the error bars.

**Determinism** (`DeterminismRun-jdk25-results.txt`, commit 74d5f8a):
`DeterminismProbe` in ten fresh JVMs rendered byte-identical graphs, 47 classes, sha-256 41b54b4d62eb3396, 46 ms per JVM including its start.

### 9.2 The predictions of 6.1, scored

1. **Deferred at least 5x faster than eager on the largest ported tests, near
   1x on the smallest, the repair count tracking the time: holds.** 430x and
   over 22x and 54x on the three large runs; within error on the small ones.
   The repair count tracks the direction, not the size, of the gap, for the
   reason above.
2. **Every ported egg test passes with the right-hand side in the expected
   class, none needing a semantic change: holds for the suites ported.**
   `simple`, `prop` (3 of 3) and `lambda` (12 of 12, each run ending at egg's
   own iteration, node and class counts) pass with egg's rules unchanged. The
   one library change the port forced went toward egg, not away from it: the
   runner asks its scheduler whether it can stop in every iteration that
   merged nothing, as egg's does (#19). The `math` suite is not ported (#18),
   so the prediction is scored on three suites of the four.
3. **The determinism test passes across ten fresh JVMs: holds.**
4. **A 64-node graph with 20 rules saturates or hits its limit in under 5 ms
   and extracts in under 1 ms: half holds, half fails.** Saturation to a
   200-node limit takes 0.29 ms warm, and to a 1,000-node limit
   12.3 ms, so the 5 ms holds for a tight budget and fails for a
   loose one; egg's default limit is out of the question at these rules.
   Extracting the cheapest tree per root takes 24 to 211 us,
   under 1 ms; `extractAll`, the DAG over all roots with sharing, takes
   41.8 to 85.6 ms, 40 to 85 times over, and dominates the whole
   compile. The cost is the descent's: every candidate rebuilds the selection
   from scratch and a held move runs a nested descent (#12). Cold, in a fresh
   JVM, everything is two to eighty times slower still, the JIT's share.
5. **The naive matcher within 3x of the compiled machine: not measurable.**
   There is no machine (#7). A JFR profile of the deferred `lambda_function_repeat` run (`RepeatProfile-jdk25-results.txt`) puts at least 32% of its samples in the matcher and at least 26% in `rebuild`, with 39% in node equality whose callers the stack depth cut off; the matcher is the number #7 reads, and it says the naive matcher is a third of the run or more, not the whole of it.
6. **`extractAll` keeps the shared decomposition and costs less than the two
   single-root extractions summed; the greedy choice matches enumeration on
   every graph small enough to enumerate: holds, with a change.** On the
   smoke test the selection costs 33 against 42 (#11). The greedy choice
   alone does not reach it: it stops at 41, and the descent that follows it
   does, so the "greedy choice" of the prediction is a greedy start and a
   descent. Against enumeration, 100 of 100 random graphs of up to six
   classes, though graphs that small rarely have the shape the descent exists
   for (#12).

### 9.3 What moved that the plan did not list

- **Extraction with sharing needed more than a greedy choice.** A class's
  best DAG for itself is not the best for the union of the roots; the
  coordinate descent with a held move (#4, #11) is the heuristic, and the
  literature's exact methods, beam search and real benchmark graphs are
  #12's plan.
- **The runner differed from egg in when it asks the scheduler**, found only
  by comparing ban logs on `lambda_function_repeat` (#19); the hook API and
  `ConditionEqual` came with that suite.
- **The rebuild sweeps the whole graph every time**, which the deferred-eager
  numbers above show and #15 plans away; the node limits overshoot by an
  iteration (#14).
- **`extractAll` is the compile-time cost**, not saturation, at a compiler's
  budget; prediction 4 was written with egg's tree extraction in mind.
- **The process**: an issue, then a plan in it, then a pull request
  (`CONTRIBUTING.md`, #3), set after the first steps had gone code-first; two
  code reviews per step found what the plan's tests did not.

### 9.4 What the port leaves for later

The open issues: the compiled matcher if a measurement asks (#7); the backoff
search stopping at its threshold (#9); a stronger and cheaper descent (#12);
unboxed ids and value-class readiness (#13); the correctness findings of the
whole-repository review (#14); a rebuild proportional to its work (#15); the
`math` suite (#18). For Varka, item 11's work as section 3.2 left it: the
dependency on a pinned version and the client mapping.

As context only, not a measurement: egg's own release build runs `lambda_fib`
in 0.18 s and `lambda_function_repeat` in 0.36 s where jegg takes 27 and 11 s;
the review of #19 put about half of the latter in `rebuild` and half in the
matcher.
