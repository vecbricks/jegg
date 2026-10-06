# Checking a port against egg

## The situation

A ported egg test that proves its goal can still be wrong: it can take a different number of
iterations, build a different graph, and reach the goal by a route egg never takes. The goal
alone cannot tell a faithful port from a lucky one.

## What it taught

Pin every ported run to egg's own iteration, node and class counts, read from egg's run of the
same test, and treat a difference as a divergence to explain, never as a number to relax. Two
real divergences were found this way and nowhere else:

- `lambda_function_repeat` proved its goal at the iteration limit where egg proves it in 58
  iterations. egg asks its scheduler whether it can stop in every iteration that merged nothing,
  even if nodes were added; jegg asked only when nothing was added either, so its bans were
  released an iteration late (#19).
- `integ_part1`, `integ_part2` and `math_diff_simple2` ended with one to three classes more than
  egg: a pruned node had been removed from its children's parent lists, so a merge that made it
  congruent with a live node was never seen (#30).

## How to apply it

1. Run egg's own test, one at a time, in a clone of egg at the pinned commit (73975c9):
   `cargo test --release --test <suite> -- --exact <name> --nocapture` prints a report with
   `Iterations`, `Egraph size: N nodes, M classes`. egg's "nodes" is the sum of the classes' node
   lists, which is jegg's `numNodes()`; egg's "memo" includes garbage and is not comparable.
2. Record them in an `Egg(iterations, nodes, classes)` beside the case (`LambdaTest.Case`,
   `MathTest.Case`). egg records the iteration a hook stops as one more than jegg's report does;
   `sameAsEgg` allows for it.
3. When a count differs, compare per iteration: `RUST_LOG=egg=info` on egg's side prints
   `Size: n=..., e=...` after each iteration (`e` is classes; `n` is the memo, not comparable),
   and `Banning` / `fast-forwarded` lines for the scheduler; jegg's `RunReport` has the same per
   iteration. The first iteration where `e` differs is where to look.
4. egg installs no goal hook for a test with a `@check`, so such a test runs to its limit;
   mirror that (`math_associate_adds`).
5. For more runs than the ports pin, `dev/fuzz.sh` (#44) runs random terms of `prop` and `math`
   through egg's own language, analysis and rules and jegg's ports and compares, per iteration,
   the nodes and classes at its start. Run `dev/fuzz.sh --known <language>` first: egg's side must
   reproduce the pinned counts, or no result of the fuzzing means anything. egg checks its node
   limit against its memo, which keeps stale entries, so a run can stop on a limit jegg's nodes
   have not reached; the comparison calls that "the same up to egg's memo-sized node limit" only
   when egg's nodes are within the limit, its memo is over it, and every iteration start egg
   reached is jegg's too. Shrunk divergences go to `dev/fuzz/found/` (not tracked).
6. egg's defaults include a 5-second time limit jegg does not have; if egg's report says
   `TimeLimit`, the counts are not reproducible and the case says so instead of pinning them.
7. egg's prop tests print no report; `dev/egg_counts.sh prop <test>` runs a copy of
   `tests/prop.rs` that does (`print_report` after each run, and the graph's size for
   `const_fold`, which has no runner).
8. A graph that matches egg's iteration by iteration can still stop at a different iteration.
   egg's multi-pattern `apply_matches` returns an id per substitution whether or not a union
   changed anything, so a run with a matching multi-pattern rule is never `Saturated`: egg's
   `prove_chain` runs 20 iterations over a graph that stops changing in the fifth, and both
   `datalog` tests run to the iteration limit. A conditional rule standing in for the multi-pattern
   built the same graph and saturated in 6 (#65); `Applier.multi` counts every match as applied
   (`Applied.counted`), and `prove_chain` and `datalog` now end at egg's counts (#34). When the
   nodes and classes agree after every iteration and only the stop differs, read egg's source for
   what it counts as applied before calling it a divergence.
