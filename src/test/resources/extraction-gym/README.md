# extraction-gym graphs

62 e-graphs from https://github.com/egraphs-good/extraction-gym (commit 903ba0f, 1 February
2026), under its MIT licence (`LICENSE`), in its `egraph-serialize` JSON: `nodes` keyed by node id
with `op`, `children` (node ids), `eclass` and `cost`, and `root_eclasses`. Chosen from the gym's
graphs of at most 150 nodes (45) and of 151 to 300 nodes (17, issue #36: every graph of that size
that meets the rule) that have roots naming classes the file holds and a class with more than
one node, across its suites (egg's own tests, babble, lean-egg, tensat, diospyros, eggcc-bril,
set_covering and the dummy examples); a file is named `<suite>__<file>` with spaces and `#` dropped.

`ExtractionGymTest` reads them, builds the e-graph, and compares `extractAll` with the greedy
start it begins from and with an exact branch-and-bound oracle where that finishes: the data
behind issue #12's predictions.

The 17 larger graphs, by suite: babble (4), eggcc-bril (4), lean-egg (6), egg's own (2: lambda
`compose_many` and math `simplify_root`) and tensat (1, `resnet50_acyclic`). The oracle's deadline
is 3 s per graph of at most 150 nodes and 10 s per larger graph (`-Dgym.oracle.seconds=N` raises
the latter); the test prints which graphs it finished and which timed out.
