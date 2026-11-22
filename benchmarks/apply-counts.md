# The apply against egg: counts (#154)

The run of `dev/probe.sh` by the Fuzz workflow's `probe` job,
[run 45038970795](https://github.com/vecbricks/jegg/actions/runs/45038970795), artifact
`probe-22`, dispatched from the probe branch `rebuild-probe` at e17d236 (main at 98cdea2 plus
the counters), on an AMD EPYC 7763. A 40-term smoke run
([45036516743](https://github.com/vecbricks/jegg/actions/runs/45036516743)) and a first full
run without the right-hand-side counters
([45037353045](https://github.com/vecbricks/jegg/actions/runs/45037353045)) agreed on every
count both had. The counts are exact whatever the runner's load. The predictions of #154 and
their scores are on the issue.

## What it says

- **The right-hand sides do the same work on both sides.** For the same applications
  (11,844,772 against 11,846,128 substitutions handed to an applier on prop, 1.00x; math and
  lambda equal to the count), the right-hand sides add the same nodes: prop 18,729,210
  against 18,731,427 (1.00x), math 8,462,008 against 8,463,328, lambda 473,750 against
  473,750. The adds outside the right-hand sides differ, and they are egg's: 1,126,939 on prop
  against jegg's 15,547, from the analysis's `modify`, which egg calls on every union and
  which prop's folding answers with an add of its constant. On prop term 6 (4 nodes, 5
  applications), both sides add 6 nodes from the right-hand sides and make 2 classes of them;
  egg adds 23 in all, jegg 14.
- **jegg makes more classes of those adds.** Of the right-hand sides' adds, jegg's make a
  class 1.29x as often as egg's on prop (2,450,076 against 1,904,923; a hit rate of 86.9%
  against 89.8%), 1.13x on math (95.0% against 95.6%), 1.03x on lambda (74.6% against 75.4%);
  the effective unions follow, 1.27x, 1.14x and 1.04x, and the rebuild's congruences, 1.88x,
  1.16x and 1.01x. The graphs agree at every iteration's end, so these are classes jegg makes
  and merges away within an iteration where egg's add finds the node. This is #137's excess,
  located in the apply's adds, and present on math too. Two candidates, neither counted yet:
  the order the matches are applied in (a right-hand side added before its children are
  merged is a class of its own, merged by congruence later), and egg's `modify` on every
  union, which merges a folded class with its constant at once, so that the next add finds
  the constant's class where jegg, which runs `modify` at the rebuild, makes a class.
- **What is left is the cost per operation, and on prop and math it is not the whole of the
  gap.** The compare job times jegg's apply at 1.41x and 1.47x egg's on prop (runs 45018755381
  and 45034140264, `rebuild-counts.md`), 1.75x on math and 1.97 to 2.03x on lambda. On lambda,
  where the work agrees within 4%, that is the cost per operation. On prop and math the extra
  classes and unions are part of it, and how much is #137's count to say. The bytes below are
  what an operation costs in allocation.
- **Scored against #154's exit.** Prediction 1 (adds per application) holds on the
  right-hand sides' adds; prediction 2 (the hit rate) holds on math and lambda and fails on
  prop; prediction 3 (effective unions) fails on all three. By the issue's clause, the count
  says where the extra work is, and that is the next issue: #137.

## Bytes per add in the apply

From the probe's allocation profile (sampled and weighted), the bytes under the apply phase
over the adds of the profiled runs, the largest `SAME` quintile of prop and math for 90
seconds each:

- prop: Allocation by phase: {apply=42008043112, other=22301311568, rebuild=6635361536,
  search=36826385912}; 42008.0 MB under the apply (39%), 332394888 adds: 126 bytes per add.
- math: Allocation by phase: {apply=38210432416, other=2336727976, rebuild=4182431256,
  search=149627615800}; 38210.4 MB under the apply (20%), 369060254 adds: 104 bytes per add.

The lambda split is not read: the two full runs put the same 210 GB under the apply in one and
under the rebuild in the other, so the sampler's weights do not settle it there (the test's
analysis re-makes free-variable sets in both phases).

What an add allocates, prop and math alike: the children's `int[]` and its `IntList` from
`Matcher.instantiate` (36 to 41% and 24%), the record from `withChildren` (12 to 17%), the
`IntList` of one id the applier returns per application (`PatternApplier.apply`, 11 to 13%),
and for a new class the `EClass` with its lists, the parent entry and the hashcons's node
(`EGraph.add`, `EClass.<init>`, 7 to 11% and 4 to 7%).

prop, under the apply:

| class | bytes | of apply |
|---|---|---|
| [I | 15113.8 MB | 36% |
| io.github.vecbricks.jegg.IntList | 9885.7 MB | 24% |
| [Ljava.lang.Object; | 4360.9 MB | 10% |
| io.github.vecbricks.jegg.PropRulesTest$Prop$Or | 2150.5 MB | 5% |
| io.github.vecbricks.jegg.EClass | 1892.1 MB | 5% |
| java.util.ArrayList | 1742.4 MB | 4% |
| io.github.vecbricks.jegg.PropRulesTest$Prop$Not | 1672.1 MB | 4% |
| io.github.vecbricks.jegg.IntArray | 1234.0 MB | 3% |
| io.github.vecbricks.jegg.PropRulesTest$Prop$Implies | 1172.0 MB | 3% |
| java.util.HashMap$Node | 1044.2 MB | 2% |

| first jegg frame | bytes | of apply |
|---|---|---|
| IntList.wrap | 9885.7 MB | 24% |
| Matcher.instantiate | 7899.6 MB | 19% |
| PatternApplier.apply | 4797.3 MB | 11% |
| EGraph.add | 4608.8 MB | 11% |
| EClass.<init> | 2976.4 MB | 7% |
| PropRulesTest$Prop$Or.withChildren | 2150.5 MB | 5% |
| IntArray.<init> | 1997.0 MB | 5% |
| PropRulesTest$Prop$Not.withChildren | 1672.1 MB | 4% |
| EClass.addNode | 1464.2 MB | 3% |
| PropRulesTest$Prop$Implies.withChildren | 1172.0 MB | 3% |

math, under the apply:

| class | bytes | of apply |
|---|---|---|
| [I | 15509.5 MB | 41% |
| io.github.vecbricks.jegg.IntList | 9148.4 MB | 24% |
| io.github.vecbricks.jegg.MathTest$Math$Add | 3575.2 MB | 9% |
| io.github.vecbricks.jegg.MathTest$Math$Mul | 3093.0 MB | 8% |
| [Ljava.lang.Object; | 2765.9 MB | 7% |
| io.github.vecbricks.jegg.EClass | 843.0 MB | 2% |
| java.util.ArrayList | 829.7 MB | 2% |
| java.util.HashMap$Node | 648.3 MB | 2% |
| io.github.vecbricks.jegg.EClass$Parent | 579.6 MB | 2% |
| io.github.vecbricks.jegg.IntArray | 532.5 MB | 1% |

| first jegg frame | bytes | of apply |
|---|---|---|
| IntList.wrap | 9148.4 MB | 24% |
| Matcher.instantiate | 9141.6 MB | 24% |
| PatternApplier.apply | 5004.1 MB | 13% |
| MathTest$Math$Add.withChildren | 3575.2 MB | 9% |
| MathTest$Math$Mul.withChildren | 3093.0 MB | 8% |
| EGraph.add | 2510.6 MB | 7% |
| EClass.<init> | 1348.6 MB | 4% |
| IntArray.<init> | 1223.6 MB | 3% |
| EClass.addNode | 998.4 MB | 3% |
| EGraph.merge | 650.7 MB | 2% |

## prop: 1000 terms of seed 2, the `SAME` bucket (595 terms)

| what | egg | jegg | jegg/egg |
|---|---|---|---|
| applications: substitutions handed to the appliers (jegg: conditions held and refused) | 11844772 | 11846128 | 1.00x |
| of which a pattern applier instantiated (egg) / conditions held (jegg) | 11590772 | 11846128 | 1.02x |
| adds by the right-hand sides (egg: apply_pat; jegg: Matcher.instantiate) | 18729210 | 18731427 | 1.00x |
| of which made a class | 1904923 | 2450076 | 1.29x |
| adds, every caller (the harness's term, the analysis's modify included) | 19856149 | 18746974 | 0.94x |
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

The per-term median carries egg's `modify` adds, a constant per small term; the right-hand
sides' adds agree to the count.

## math: 1000 terms of seed 2, the `SAME` bucket (825 terms)

| what | egg | jegg | jegg/egg |
|---|---|---|---|
| applications: substitutions handed to the appliers (jegg: conditions held and refused) | 4885900 | 4885900 | 1.00x |
| of which a pattern applier instantiated (egg) / conditions held (jegg) | 4868547 | 4879108 | 1.00x |
| adds by the right-hand sides (egg: apply_pat; jegg: Matcher.instantiate) | 8462008 | 8463328 | 1.00x |
| of which made a class | 372548 | 421104 | 1.13x |
| adds, every caller (the harness's term, the analysis's modify included) | 8526770 | 8501384 | 1.00x |
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
| applications: substitutions handed to the appliers (jegg: conditions held and refused) | 422686 | 422686 | 1.00x |
| of which a pattern applier instantiated (egg) / conditions held (jegg) | 328787 | 343219 | 1.04x |
| adds by the right-hand sides (egg: apply_pat; jegg: Matcher.instantiate) | 473750 | 473750 | 1.00x |
| of which made a class | 116723 | 120376 | 1.03x |
| adds, every caller (the harness's term, the analysis's modify included) | 491290 | 474398 | 0.97x |
| of which found a node (hits) | 374489 | 353944 | 0.95x |
| of which jegg found through the pruned memory | 0 | 0 | - |
| of which made a class (news; each makes a fact) | 116801 | 120454 | 1.03x |
| modify calls from an add | 116801 | 120454 | 1.03x |
| union calls outside the rebuild (egg: one per application and per modify; jegg: the applier skips the call when the two are one class) | 360456 | 86125 | 0.24x |
| of which effective | 82539 | 85910 | 1.04x |
| conditions refused (jegg; egg applies through a conditional applier) | 0 | 79467 | - |

Apply identities: egg adds minus (hits + news) = 0 (expected 0); jegg adds minus (hits + news) = 0
(expected 0).

## How it is measured

As the rebuild's counts (`rebuild-counts.md`, "How it is measured"), with these counters
added (#154). egg's side, in `dev/fuzz/probe.patch`: `Rewrite::apply` counts the substitutions
handed to the applier (every applier, conditional ones included); `apply_pat` counts the
right-hand sides' adds and those that made a class; `add_uncanonical` counts its calls, its
hits and its new classes and the `modify` it calls; `union` counts its calls and the effective
ones; `Pattern::apply_matches` counts the substitutions a pattern applier instantiated. jegg's
side, `EGraph.Probe`: `adds`, `addHits`, `addPrunedHits`, `addNews`, `mergeCalls`,
`modifyFromAdd`, `addsInApply` and `addNewsInApply` from `Matcher.instantiate`, and in
`Rewrite.apply` `matchesApplied` and `conditionsRefused`. The union-call row is not a pair:
jegg's applier skips the call when the two ids are one class already, egg calls it and is told
so. `RebuildProbe` prints the apply table beside the rebuild's, the per-term ratio of adds
with the five farthest terms by index, the identities, and the allocation under the apply by
class and by first jegg frame, with the phase split of #146.
