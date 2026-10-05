# Working in jegg, for agents and people alike

jegg is a Java 25 port of egg (e-graphs and equality saturation) with extraction over several
roots. This file is what to know before changing anything; `docs/architecture.md` is the map of
the code, `CONTRIBUTING.md` the process in full, `PLAN.md` the record of why things are as they
are.

## The process, in one paragraph

An issue first, stating the goal, what is known and what would count as done. Then a plan in the
issue: the files it touches, the design and the alternatives set aside, the tests that accept it,
and - where it measures - the predictions, registered before the run. Then the code, on a branch,
as a pull request that fills `.github/PULL_REQUEST_TEMPLATE`, names the issue (`Closes #N`), says
where it departed from the plan, and scores every prediction. A finding outside the issue becomes
a new issue, not a paragraph in the PR. Commit messages and the PR's last section end with
`Generated-by: <tool> (<model and version>)` when AI tooling was used (Apache's convention); no
`Co-Authored-By`. Issue titles are goal-shaped; PR titles say what the change achieves.

## Build and test

- `mvn -B verify` must be clean under `-Xlint:all -Werror`; it prints the coverage of
  `src/main` at the end (98% of lines as of October 2026; do not let it fall without saying so).
- One test: `mvn -q test -Dtest=LambdaTest`; one method: `-Dtest='LambdaTest#lambdaIf'`.
- The two slow tests (`lambda_fib`, `lambda_function_repeat`) are tagged `slow`:
  `-Dsurefire.excludedGroups=slow` skips them locally; CI runs everything.
- Lines are at most 100 columns, in Java and in prose. Javadoc explains the code to a new reader;
  how it came to be this way belongs in the issue, the PR and `PLAN.md`, never in a comment.

## The oracles: run them before claiming anything

1. **The invariants.** `EGraph.checkInvariants()` (hashcons holds exactly the graph's nodes,
   every node canonical, no two congruent nodes in different classes, the live-class count) and
   `checkAnalysisInvariant()` (each fact is the join of `make` over the class's nodes). A new
   operation on the graph gets a test that calls them after it.
2. **The counts pinned to egg.** `LambdaTest` and `MathTest` assert that each ported run ends at
   egg's own iteration, node and class counts (`Egg` records). A count that differs means the
   port diverged; find where with egg's per-iteration log (`RUST_LOG=egg=info cargo test
   --release --test <suite> -- --exact <name> --nocapture` in a clone of egg 73975c9) against
   jegg's `RunReport`. Never relax a count to make a test pass: #19 and #30 each found a real
   divergence this way.
3. **Extraction against enumeration and an exact oracle.** `ExtractAllEnumerationTest` and
   `ExtractionGymTest` (branch and bound over extraction-gym's graphs). A change to the descent
   must never worsen the greedy start, never beat the oracle, and should meet it.

## Rules that cost us time

- **Commit or stage before any destructive step** - deleting, overwriting, regenerating a file,
  resetting a branch. A results file that took half an hour to produce is work.
- **Benchmarks run only when the owner says the machine is idle**, pinned, from a clean commit;
  never on a load check of your own. Ask first whether a regeneration is needed at all: a
  committed results file that names a commit in the branch's history already satisfies
  `CONTRIBUTING.md`. `benchmarks/README.md` has the command and the rules.
- **A stacked PR**: retarget the child before deleting the base branch, or GitHub closes it.
- **Determinism is a promise** (`PLAN.md` 3.1): ids by insertion order, rules in declared order,
  matches in the matcher's order, ties by node order. Any map whose iteration could reach an id
  is insertion-ordered. `DeterminismTest` forks fresh JVMs over identity-hashed names to catch a
  leak; keep it able to fail.
- **egg's behaviour is the reference, including its oddities**: the scheduler is asked before the
  size check, pruned nodes stay in the memo and the parent lists, conditions run at apply time.
  When jegg must differ (an exact hashcons, refusing unissued ids), the Javadoc says so.

## Where things are

| | |
|---|---|
| the library | `src/main/java/io/github/vecbricks/jegg`, one package, 24 files; `docs/architecture.md` maps them |
| the tests | `src/test/java/...`: egg's suites (`SimpleRulesTest`, `PropRulesTest`, `LambdaTest`, `MathTest`), the invariant and determinism tests, the extraction oracles, `Toy` and `Term` as fixtures |
| the harness | `src/jmh/java/...`: `Benchmarks` (the main), `Saturation` (the loop in two modes), the three benchmarks, `CoverageSummary` |
| the measurements | `benchmarks/*-results.txt`, each naming its commit, JDK, machine and load |
| the record | the issues (goal, plan, predictions, departures), `PLAN.md` (design and outcome), the PRs (what was built and how the predictions scored) |
| for a reader | `README.md`, `docs/concepts.md`, `docs/first-language.md`, the Javadoc at https://vecbricks.github.io/jegg/ |
| the lessons | `docs/skills/`, one page per thing learned the hard way; read the one for the area you touch |
