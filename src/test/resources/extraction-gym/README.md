# extraction-gym graphs

60 e-graphs from https://github.com/egraphs-good/extraction-gym (commit 903ba0f, 1 February
2026), under its MIT licence (`LICENSE`), in its `egraph-serialize` JSON: `nodes` keyed by node id
with `op`, `children` (node ids), `eclass` and `cost`, and `root_eclasses`. Chosen from the gym's
graphs of at most 150 nodes (45) and of 151 to 300 nodes (15, issue #36: every graph of that size
that meets the rule) that have roots naming classes the file holds and a class with more than
one node, across its suites (egg's own tests, babble, lean-egg, tensat, diospyros, eggcc-bril,
set_covering and the dummy examples); a file is named `<suite>__<file>` with spaces and `#` dropped.

`ExtractionGymTest` reads them, builds the e-graph, and compares `extractAll` with the greedy
start it begins from and with an exact branch-and-bound oracle where that finishes: the data
behind issue #12's predictions.

The 15 larger graphs, by suite: babble (4), eggcc-bril (3), lean-egg (5), egg's own (2: lambda
`compose_many` and math `simplify_root`) and tensat (1, `resnet50_acyclic`). The gym holds two
byte-identical copies in that range (`ShapesRerun2` of `Shapes2`, `add_block_indirection.bril` of
`add.bril`), left out; among the 45 smaller ones `lean-egg__Star_1` is a copy of
`lean-egg__Basic_1`, kept as the gym has it. The oracle's budget is 10 million search
steps per graph (`-Dgym.oracle.steps=N` sets it), the same on every machine. The most a graph it
finishes takes is 2,839,044 steps; the one it does not, `egg__lambda_compose_many`, had taken 221
million at 20 s, so the finished set is 59 of 60 by construction. A wall-clock deadline of 30 s
(`-Dgym.oracle.seconds=N`) remains as a safety net, never reached today. The test prints the steps
each graph took and which graphs timed out.
