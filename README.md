# jegg

E-graphs and equality saturation for Java 25, a port of
[egg](https://github.com/egraphs-good/egg) (Willsey et al., POPL 2021), with
extraction over several roots that keeps a shared subterm in one form.

```java
sealed interface Arith extends Language<Arith> permits Num, Var, Add, Mul {}
record Num(long value) implements Arith { /* no children */ }
record Var(String name) implements Arith { /* no children */ }
record Add(IntList children) implements Arith { /* withChildren(c) -> new Add(c) */ }
record Mul(IntList children) implements Arith { /* withChildren(c) -> new Mul(c) */ }

EGraph<Arith, Void> g = EGraph.withoutAnalysis();
int a = g.add(new Var("a"));
int root = g.add(new Add(IntList.of(
    g.add(new Mul(IntList.of(a, g.add(new Num(2))))), g.add(new Num(0)))));   // (a * 2) + 0

Pattern<Arith> x = Pattern.var("x");
List<Rewrite<Arith, Void>> rules = List.of(
    Rewrite.of("add-0", Pattern.of(new Add(IntList.EMPTY), x, Pattern.of(new Num(0))), x),
    Rewrite.of("mul-2", Pattern.of(new Mul(IntList.EMPTY), x, Pattern.of(new Num(2))),
        Pattern.of(new Add(IntList.EMPTY), x, x)));

RunReport report = Runner.of(g, rules).run();                       // saturates in a few steps
Extracted<Arith> best = new Extractor<>(g, CostFunction.astSize()).extract(root);  // a * 2
```

The whole example, with the records written out, is
[`ReadmeExampleTest`](src/test/java/io/github/vecbricks/jegg/ReadmeExampleTest.java),
which runs on every build.

**Status.** The library is complete to the plan's eighth step (`PLAN.md` 8):
e-graph, rebuilding, analyses, patterns and rewrites, the runner with egg's
backoff scheduler, extraction over one root and over several, and the
measurement harness. egg's five test suites (`simple`, `prop`, `lambda`,
`math`, `datalog`) are ported test for test, each run checked against egg's own
iteration, node and class counts; the library has egg's multi-patterns too. Every build prints the tests' coverage of
`src/main` (98% of lines). The API still moves between steps: 0.1.0 is not on
Maven Central yet, and `CHANGELOG.md` and `CONTRIBUTING.md` ("Releases") say how
it is cut and how versions are numbered while the API moves.

## What it is

An e-graph holds many equivalent terms at once: a union-find over e-class ids,
a hashcons from e-nodes to classes, deferred rebuilding that restores
congruence once per iteration rather than after every merge, e-class analyses
that carry facts (constants, ranges, types) up the graph as a semilattice,
rewrites matched against the whole graph and applied in a batch, a runner with
node and iteration limits, and an extractor that picks the cheapest term under
a cost function. egg's paper gives the algorithms; this library ports them to
plain Java 25 - records, sealed hierarchies, `int` ids - and adds one property
egg does not promise: the extracted term is a function of the input, with
iteration order fixed wherever it could reach an id.

Three things egg's API does not have and a compiler client needs are in from
the start: e-nodes with payloads and a `TreeBridge` from a client's own tree
type, patterns that bind payloads as well as subterms, and `extractAll` over
several roots that chooses one node per class and pays a shared subterm once.
Proof production, s-expression parsing and ILP extraction are deliberately out;
[`docs/egg-comparison.md`](docs/egg-comparison.md) lists everything else egg has that jegg does not.

It was planned for [Varka](https://github.com/vecbricks/varka), which will use
it to choose physical representations over a whole projection, but it has no
Spark dependency and nothing in it is specific to Varka.

## Reading

- [`docs/concepts.md`](docs/concepts.md): the ideas, each with the jegg type
  that embodies it and the paper's section, for a reader new to e-graphs.
- [`docs/egg-comparison.md`](docs/egg-comparison.md): for a reader who knows egg, what is and is
  not ported, module by module, and what jegg adds.
- [`docs/first-language.md`](docs/first-language.md): a language with a
  payload, a bridge from your own tree, a rule that binds a payload, and an
  extraction over several roots.
- [Javadoc](https://vecbricks.github.io/jegg/) of the public surface:
  `EGraph`, `Language`, `TreeBridge`, `Analysis`, `Pattern`, `MultiPattern`,
  `Searcher`, `Subst`, `Rewrite`, `Condition`, `Applier`, `Applied`, `Runner`,
  `RunLimits`, `RunReport`, `Scheduler`, `Extractor`, `Selection`,
  `CostFunction`.
- [`PLAN.md`](PLAN.md): where the library came from, the design, egg's
  components mapped to their Java forms, and the outcome of the measurement.
- [`docs/skills/`](docs/skills/README.md): what this repository learned the
  hard way, one page per lesson, for whoever touches that area next.

## Measured

Three files under [`benchmarks/`](benchmarks/), each naming the commit, JDK,
machine and load it was measured under (`benchmarks/README.md` says how):

- [`RebuildBenchmark`](benchmarks/RebuildBenchmark-jdk25-results.txt):
  deferred rebuilding against rebuilding after every merge, on the ported
  suites; within noise below 300 nodes, 430x on a 31,000-node run.
- [`ProjectionBenchmark`](benchmarks/ProjectionBenchmark-jdk25-results.txt):
  a 64-node projection with 20 rules, as a compiler would run it; saturation to
  a 200-node limit in 0.27 ms warm, extraction over 20 roots in 0.13 ms.
- [`DeterminismRun`](benchmarks/DeterminismRun-jdk25-results.txt): ten fresh
  JVMs render the same graph byte for byte.

## Contributing

An issue stating the problem or goal, then a plan in the issue, then the code as
a pull request: [`CONTRIBUTING.md`](CONTRIBUTING.md) has the order, the why,
and the practical half (running one test, the slow tests, the benchmarks, the
counts pinned to egg). Issues labelled
[`good first issue`](https://github.com/vecbricks/jegg/labels/good%20first%20issue)
have their plans written and are sized for a first pull request.

## Using it

Once 0.1.0 is on Maven Central:

```xml
<dependency>
  <groupId>io.github.vecbricks</groupId>
  <artifactId>jegg</artifactId>
  <version>0.1.0</version>
</dependency>
```

The artifact is a module, `io.github.vecbricks.jegg`; its only dependency, JSpecify's
annotations, is `static` and is not needed at run time. Java 25.

## Building

Java 25 and Maven:

    mvn -B verify

Group id `io.github.vecbricks`, artifact `jegg`, package `io.github.vecbricks.jegg`.

## Licence

Apache License 2.0. egg is MIT-licensed; its notice is in `NOTICE`.
