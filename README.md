# jegg

E-graphs and equality saturation for Java 25, a port of
[egg](https://github.com/egraphs-good/egg) (Willsey et al., POPL 2021).

**Status: pre-alpha.** The repository, build, licence and plan exist; the
library is being built component by component in the order `PLAN.md` 8
gives, each with the tests that accept it. Nothing is published yet.

## What it is

An e-graph holds many equivalent terms at once: a union-find over e-class ids,
a hashcons from e-nodes to classes, deferred rebuilding that restores
congruence once per iteration rather than after every merge, e-class analyses
that carry facts (constants, ranges, types) up the graph as a semilattice,
rewrites matched against the whole graph and applied in a batch, a runner with
node and iteration limits, and an extractor that picks the cheapest term under
a cost function. egg's paper gives the algorithms; this library ports them to
plain Java 25 - records, sealed hierarchies, `int` ids never boxed - and adds
one property egg does not promise: the extracted term is a function of the
input, with iteration order fixed wherever it could reach an id.

It was planned for [Varka](https://github.com/vecbricks/varka), which will use
it to choose physical representations over a whole projection, but it has no
Spark dependency and nothing in it is specific to Varka.

## Public surface

`EGraph<L, A>`, `Language<L>`, `TreeBridge<T, L>`, `Analysis<L, D>`,
`Pattern`, `Subst`, `Rewrite`, `Condition`, `Applier`, `Runner`, `RunLimits`,
`RunReport`, `Scheduler`, `Extractor`, `Selection`, `CostFunction`. Three
things egg's API does not have and a compiler client needs are in from the
start: e-nodes with payloads and a bridge from a client's own tree type,
patterns that bind payloads as well as subterms, and extraction over several
roots that keeps a shared subterm in one form (`PLAN.md` 3.2). Proof
production, s-expression parsing and ILP extraction are deliberately out;
`PLAN.md` 3.1 maps each egg component to its Java form.

## Building

Java 25 and Maven:

    mvn -B verify

Group id `io.github.vecbricks`, artifact `jegg`, package `io.github.vecbricks.jegg`.

## Licence

Apache License 2.0. egg is MIT-licensed; its notice is in `NOTICE`.
