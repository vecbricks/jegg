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
5. egg's defaults include a 5-second time limit jegg does not have; if egg's report says
   `TimeLimit`, the counts are not reproducible and the case says so instead of pinning them.
