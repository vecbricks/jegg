# extraction-gym graphs

45 e-graphs from https://github.com/egraphs-good/extraction-gym (commit 903ba0f, 1 February
2026), under its MIT licence (`LICENSE`), in its `egraph-serialize` JSON: `nodes` keyed by node id
with `op`, `children` (node ids), `eclass` and `cost`, and `root_eclasses`. Chosen from the gym's
graphs of at most 150 nodes that have roots naming classes the file holds and a class with more than one node, across its
suites (egg's own tests, babble, lean-egg, tensat, diospyros, eggcc-bril, set_covering and the
dummy examples); a file is named `<suite>__<file>` with spaces and `#` dropped.

`ExtractionGymTest` reads them, builds the e-graph, and compares `extractAll` with the greedy
start it begins from and with an exact branch-and-bound oracle where that finishes: the data
behind issue #12's predictions.
