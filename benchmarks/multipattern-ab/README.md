# The multi-pattern A/B (#67)

Does #66's change slow pattern-only rules? Four runs of `RebuildBenchmark` and
`ProjectionBenchmark`, before, after, before, after, on one idle night, each from a clean
worktree with `dev/bench.sh --idle rebuild,projection` (pinned to cpus 0-3 and 12-15, performance
governor, `benchmarks/README.md`'s method):

| run | code | commit | started | the files' load average before, after |
|---|---|---|---|---|
| `run1-before` | main before #66 | 68699ce | 22:31 | Rebuild 0.22, 0.68; Projection 0.68, 0.75 |
| `run2-after` | #66's head after its second review | 338ed1f | 23:15 | Rebuild 0.92, 0.76; Projection 0.76, 0.69 |
| `run3-before` | main before #66 | 68699ce | 23:59 | Rebuild 0.49, 0.81; Projection 0.81, **1.19** |
| `run4-after` | #66's head | 338ed1f | 00:43 | Rebuild 0.47, 0.70; Projection 0.70, 0.80 |

The loads are the benchmark's own thread, about 0.7 to 0.9, except the last minute of run 3's
projection half, 1.19: something else ran near its end, and its cold single-shot numbers are not
to be trusted. `driver.log` is the driver's own record of the starts and ends.

`338ed1f` is the head of #66, whose merge into `main` is a squash, so the commit will not be in
`main`'s history; its tree, `63fd62950c2504e7f0693f26c3668fa0020f00bb`, is what `main` holds
after the merge if `main` has not moved since 68699ce, whose tree is
`5fc5a20a1e05b2cbaa8c94167f250bec87832869` (`git rev-parse <commit>^{tree}`).

## Reading them

The machine drifts by several percent from one run to the next, in no fixed direction (run 3 is
5.1% above run 1 over all 62 Rebuild cases, run 4 0.4% above it), so a single before and after
cannot say. `fit.py` fits, for each case, the log of the time against the run's slot and against
whether it is the after code, and reports the after/before effect:

    python3 benchmarks/multipattern-ab/fit.py benchmarks/multipattern-ab

Its output, which the pull request quotes:

- `RebuildBenchmark`, 62 single-shot cases: the after/before effect is 0.994 over all (deferred
  1.004, eager 0.983); the plain mean of the after runs over the mean of the before runs is
  0.997. No case's fitted effect is above 1.11; 9 of 62 are above 1.05, which the same code's
  run-to-run spread (29 of 62 plain ratios above 1.05 between runs 1 and 3) accounts for.
- The three large single runs are the same in all four: `prove_fold` 0.2 or 0.3 s deferred and
  1.1 s eager; `lambda_function_repeat` 2.3 s and 3.2 to 3.3 s; `lambda_fib` 27.3 to 28.6 s
  deferred and 28.2 to 29.7 s eager, the after runs 27.6 and 28.6, the before runs 28.2 and 27.3.
- `ProjectionBenchmark`, warm `saturate`: 200 nodes 0.993 (default headers) and 1.002 (compact
  headers); 1000 nodes 1.013 and 1.008. Bytes allocated per operation fall by 1.3% (default,
  200 nodes: 480.7 and 479.7 KB before, 473.8 and 472.8 KB after) and 0.9% (compact), because the
  common `Applied` results are shared and `Rewrite.apply` returns cached `Optional`s, where main
  allocated an `OptionalInt` per applied match.
