# The apply against egg: counts (#154)

The run of `dev/probe.sh` by the Fuzz workflow's `probe` job,
[run 45037353045](https://github.com/vecbricks/jegg/actions/runs/45037353045), artifact
`probe-21`, dispatched from the probe branch `rebuild-probe` at fe1e15b (main at 98cdea2 plus
the counters), on an AMD EPYC 7763. A 40-term smoke run,
[45036516743](https://github.com/vecbricks/jegg/actions/runs/45036516743), agreed. The counts
are exact whatever the runner's load; the rebuild's tables of the same run are the ones
`rebuild-counts.md` describes. The predictions of #154 and their scores are on the issue.

## What it says

- **The apply does egg's work.** For the same matches, jegg's apply adds the same nodes: on
  math 8,501,384 adds against egg's 8,526,770 (1.00x), 1.74 adds per application on both
  sides, 95.0% of them hits against egg's 95.5%; on lambda 0.97x the adds, 1.38 against 1.49
  per application, 74.6% hits against 76.2%; on prop 0.94x the adds, 1.58 against 1.71 per
  application, 86.9% hits against 90.4%. egg's extra adds are its `modify`'s: egg calls the
  analysis's `modify` on every union (1,523,969 calls on prop against jegg's 281,356), and
  prop's folding adds its constant from `modify`, a hit each time. Both sides' identities
  hold (adds = hits + news).
- **jegg makes more classes than egg in the apply.** New classes 1.29x egg's on prop, 1.13x
  on math, 1.03x on lambda, with effective unions 1.27x, 1.14x and 1.04x and, in the rebuild,
  congruences 1.88x, 1.16x and 1.01x. The graphs agree at every iteration's end, so these are
  classes jegg makes and merges away within an iteration where egg finds the node. The shape
  fits an order effect: a right-hand side added before its children are merged is a class of
  its own, merged by congruence later; added after, it is a hit. This is #137's excess,
  located: it is in the apply's adds and unions, and it exists on math too.
- **The cost per operation is the gap.** The compare job times jegg's apply at 1.47x egg's on
  prop, 1.75x on math and 2.03x on lambda (`rebuild-counts.md`, "After #136"), for the same
  adds, hits and unions within 6%. jegg's apply allocates 121 bytes per add on prop, 101 on
  math and 303 on lambda: a record and an `IntList` per node built, a parent entry and the
  hashcons's node per new class, a `Subst` per binding of the match. That is a layout
  question (#92, the opt-in modes of #84), not another lever inside the current one.
- **A harness constant on the smallest terms.** The five prop terms with the fewest adds read
  egg 23 against jegg 14 for the same five applications: egg's `modify` on the union of the
  start term with `true`, before the run, and on every union after. The per-term median of
  jegg's adds over egg's (0.87x on prop) carries it; the p90 is 0.99x.

## prop: 1000 terms of seed 2, the `SAME` bucket (595 terms)

| what | egg | jegg | jegg/egg |
|---|---|---|---|
| matches applied (egg: substitutions instantiated; jegg: conditions held) | 11590772 | 11846128 | 1.02x |
| adds | 19856149 | 18746974 | 0.94x |
| of which found a node (hits) | 17946263 | 16291946 | 0.91x |
| of which jegg found through the pruned memory | 0 | 0 | - |
| of which made a class (news; each makes a fact) | 1909886 | 2455028 | 1.29x |
| modify calls from an add | 1909886 | 2455028 | 1.29x |
| union calls outside the rebuild (egg: one per application and per modify; jegg: the applier skips the call when the two are one class) | 11845367 | 1633240 | 0.14x |
| of which effective | 1285234 | 1626529 | 1.27x |
| conditions refused (jegg; egg applies through a conditional applier) | 0 | 0 | - |

Per term, jegg's adds over egg's, terms with 10 or more adds on egg's side (595): median 0.87x, p90
0.99x, max 1.00x. Farthest terms (index: jegg adds / egg adds, matches applied jegg / egg): 7:
15/25, 5/5; 12: 15/25, 5/5; 15: 15/25, 5/5; 17: 15/25, 5/5; 23: 15/25, 5/5;

Apply identities: egg adds minus (hits + news) = 0 (expected 0); jegg adds minus (hits + news) = 0
(expected 0).

## math: 1000 terms of seed 2, the `SAME` bucket (825 terms)

| what | egg | jegg | jegg/egg |
|---|---|---|---|
| matches applied (egg: substitutions instantiated; jegg: conditions held) | 4868547 | 4879108 | 1.00x |
| adds | 8526770 | 8501384 | 1.00x |
| of which found a node (hits) | 8147010 | 8073068 | 0.99x |
| of which jegg found through the pruned memory | 0 | 880059 | - |
| of which made a class (news; each makes a fact) | 379760 | 428316 | 1.13x |
| modify calls from an add | 379760 | 428316 | 1.13x |
| union calls outside the rebuild (egg: one per application and per modify; jegg: the applier skips the call when the two are one class) | 4936740 | 380246 | 0.08x |
| of which effective | 322474 | 367090 | 1.14x |
| conditions refused (jegg; egg applies through a conditional applier) | 0 | 6792 | - |

Per term, jegg's adds over egg's, terms with 10 or more adds on egg's side (825): median 0.99x, p90
1.00x, max 1.04x. Farthest terms (index: jegg adds / egg adds, matches applied jegg / egg): 8:
41/44, 14/14; 15: 41/44, 14/14; 32: 41/44, 14/14; 41: 41/44, 14/14; 48: 41/44, 14/14;

Apply identities: egg adds minus (hits + news) = 0 (expected 0); jegg adds minus (hits + news) = 0
(expected 0).

## lambda_function_repeat

| what | egg | jegg | jegg/egg |
|---|---|---|---|
| matches applied (egg: substitutions instantiated; jegg: conditions held) | 328787 | 343219 | 1.04x |
| adds | 491290 | 474398 | 0.97x |
| of which found a node (hits) | 374489 | 353944 | 0.95x |
| of which jegg found through the pruned memory | 0 | 0 | - |
| of which made a class (news; each makes a fact) | 116801 | 120454 | 1.03x |
| modify calls from an add | 116801 | 120454 | 1.03x |
| union calls outside the rebuild (egg: one per application and per modify; jegg: the applier skips the call when the two are one class) | 360456 | 86125 | 0.24x |
| of which effective | 82539 | 85910 | 1.04x |
| conditions refused (jegg; egg applies through a conditional applier) | 0 | 79467 | - |

Apply identities: egg adds minus (hits + news) = 0 (expected 0); jegg adds minus (hits + news) = 0
(expected 0).

## Bytes per add

From the probe's allocation profile, the bytes sampled under the apply phase over the adds of
the profiled runs (the largest `SAME` quintile of prop and math, 90 seconds each;
`lambda_function_repeat` whole):

- prop: Allocation by phase: {apply=41613153368, other=22331923384, rebuild=7608361216,
  search=38932621600}; 41613.2 MB under the apply (38%), 343443404 adds: 121 bytes per add.
- math: Allocation by phase: {apply=37390517448, other=3153303080, rebuild=4015909544,
  search=152669499912}; 37390.5 MB under the apply (19%), 369052526 adds: 101 bytes per add.
- lambda: Allocation by phase: {apply=14971781600, other=22086800, rebuild=203393789272,
  search=25541529800}; 14971.8 MB under the apply (6%), 49337392 adds: 303 bytes per add.

The lambda rebuild's bytes are the test's analysis (free-variable sets re-made on every
fact), as `rebuild-counts.md` says.

## How it is measured

As the rebuild's counts (`rebuild-counts.md`, "How it is measured"), with these counters
added (#154). egg's side, in `dev/fuzz/probe.patch`: `add_uncanonical` counts its calls, its
hits and its new classes and the `modify` it calls; `union` counts its calls and the effective
ones; `apply_matches` counts the substitutions it instantiates. jegg's side, `EGraph.Probe`:
`adds`, `addHits`, `addPrunedHits`, `addNews`, `mergeCalls`, `modifyFromAdd`, and in
`Rewrite.apply` `matchesApplied` and `conditionsRefused`. The union-call row is not a pair:
jegg's applier skips the call when the two ids are one class already, egg calls it and is told
so. `RebuildProbe` prints the apply table beside the rebuild's, the per-term ratio of adds
with the five farthest terms by index, the identities, and the bytes per add under the apply
phase, with the phase split of #146.
