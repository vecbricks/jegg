# The rebuild against egg: counts and a profile (#134)

The run of `dev/probe.sh` by the Fuzz workflow's `probe` job,
[run 45014765355](https://github.com/vecbricks/jegg/actions/runs/45014765355), artifact
`probe-7`, dispatched from the probe branch `rebuild-probe` at e17facc (main at 0ff0fa3 plus the
counters; the probe is not merged, see "How it is measured"). A 40-term smoke run,
[45014340608](https://github.com/vecbricks/jegg/actions/runs/45014340608), agreed. The
predictions of #134 and their scores are on the issue; the fixes the counts name are #136 (the
visits), #137 (prop's extra unions), #138 (the hashcons) and #139 (the per-visit re-make).

## What it says

- **The visits.** jegg's `repair` walks the combined parent list of both sides of every merge;
  egg's `perform_union` pends the absorbed side's alone, choosing the side with fewer parents.
  On the `SAME` bucket jegg visits 6.1x the parents egg does on prop, 7.5x on math, 4.1x on
  `lambda_function_repeat`, while the forms it actually re-makes are within 1.5x of egg's
  visits. Summing the smaller side's parents over jegg's own merges, which is what egg's rule
  would visit, gives 1.60x egg's count on prop, 1.17x on math, 0.88x on lambda.
- **prop's unions.** jegg makes 36% more effective unions than egg on the 595 prop terms where
  #72's buckets say the runs are identical: classes made and merged away inside an iteration,
  invisible at the iteration boundaries (#137). On math the excess is 14%, on lambda 3%.
- **The cost per visit.** The profile charges 34 to 44% of the rebuild's self samples to
  re-making the form (`canonicalize`, `isCanonical`, `IntList.map`) and 27 to 37% to the
  hashcons (`HashMap.removeNode`, `putVal`, the record hash and equals); `sortedDistinct` is 2%.
  Each visit allocates 158 bytes on prop and 178 on math: `HashMap$Node` 25%,
  `LinkedHashMap$Entry` 19%, the per-repair `Object[]` and the re-made `int[]` 14 to 29%,
  `Integer` 5 to 7%. The rebuild is 12% of prop's bytes per run and 6% of math's.
- **Not as predicted.** egg's `rebuild_classes` walks every class's nodes on every rebuild and
  jegg re-canonicalises the stale owners alone, but the two walk nearly the same nodes (0.92x):
  the stale owners are most classes. The facts' re-make is within 1.3x on all three, and
  `analysis.make` is not among the rebuild's top frames on math.
- **A reading not believed.** The lambda profile charges 4.4 KB per visit, 96% of it
  `LinkedHashMap$Entry`; that is not one entry per visit, and is to be re-measured with
  `jdk.ObjectAllocationInNewTLAB` before use (#136's measurement does it).

## How it is measured

egg's side is a patch to the cached checkout (`dev/fuzz/probe.patch` on the probe branch,
applied by `dev/egg_harness.sh`): counters in `process_unions` (pops, memo collisions,
congruence unions, `analysis_pending` pops, `modify` calls), in `make_new_eclass` (every new
node is pended too) and `perform_union` (the absorbed side's and the kept side's parents), and
in `rebuild_classes` (classes and nodes walked, duplicates trimmed); the harness in
`dev/fuzz/common.rs` resets them per case and writes them to `$FUZZ_COUNTS`, and the printed
report carries a `Probe:` line for `lambda_function_repeat`. jegg's side is `EGraph.Probe`:
merges and the two counterfactual sums, repairs, parents visited, forms re-made, hashcons
removes and puts, pruned visits, `seen` entries, congruence merges, `analysis.make` and
`modify` calls, stale classes and nodes re-canonicalised. `RebuildProbe` (in `src/jmh`) pairs
them per term, buckets by `FuzzRun.compare` as #72 does, sums the `SAME` bucket by quintile of
egg's time, and then profiles jegg alone with JFR in process for 90 seconds per workload on the
largest `SAME` quintile, grouping the samples by the frames under `EGraph.rebuild`. Two
identities are printed so a broken counter cannot pass as a finding: egg's pops equal its
added plus absorbed; jegg's parents visited equal its hashcons puts plus pruned visits. Both
hold. The counts are exact whatever the runner's load; the profile's shares are of samples,
not time.

The probe branch is kept, not merged: a fix's measurement rebases it onto the fix and
dispatches the Fuzz workflow with `probe=true` from there (`gh workflow run fuzz.yml --ref
<branch> -f ref=<branch> -f count=1000 -f seed=2 -f probe=true`).

## Provenance

```
jegg   e17facc The probe: egg's modify counted in perform_union too,
       jegg's congruences only when effective
egg    73975c9 + dev/fuzz/probe.patch (release build)
terms 1000, seed 2, heap 2g fixed, 2026-11-22T10:57:55Z
openjdk version "25.0.5.1" 2026-11-17 LTS
cpus 4; Intel(R) Xeon(R) 6975P-C
```

## prop: 1000 terms of seed 2

Buckets: {SAME=595, NODE_LIMIT=288, BOTH_FAILED=117}. The tables are over `SAME`.

### By quintile of egg's time, smallest to largest

Columns: the quintile; its terms; egg's absorbed-side parents pended; jegg's parents visited and
the ratio; jegg's smaller-side sum (the counterfactual) and its ratio to egg's; jegg's forms
re-made; egg's pops (a memo insert each); jegg's hashcons removes and puts and the ratio.

| q | n | egg abs. | jegg vis. | ratio | jegg min | ratio | re-made | egg pops | jegg hash | ratio |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 119 | 0 | 228 | - | 0 | - | 0 | 564 | 456 | 0.81x |
| 2 | 119 | 9 | 435 | 48.33x | 9 | 1.00x | 9 | 800 | 870 | 1.09x |
| 3 | 119 | 131762 | 574074 | 4.36x | 130525 | 0.99x | 118108 | 394751 | 1155559 | 2.93x |
| 4 | 119 | 386872 | 2241210 | 5.79x | 573407 | 1.48x | 517353 | 1223689 | 4489860 | 3.67x |
| 5 | 119 | 419867 | 2925008 | 6.97x | 794864 | 1.89x | 706110 | 1228592 | 5860958 | 4.77x |

### All `SAME` terms, summed

| what | egg | jegg | jegg/egg |
|---|---|---|---|
| parents visited (egg: the absorbed side's, pended) | 938510 | 5740955 | 6.12x |
| counterfactual: jegg visiting the absorbed side only | 938510 | 1578660 | 1.68x |
| counterfactual: jegg visiting the smaller side only | 938510 | 1498805 | 1.60x |
| forms re-made (jegg) against egg's visits | 938510 | 1341580 | 1.43x |
| hash operations (egg: a memo insert per pop; jegg: removes + puts) | 2848396 | 11507703 | 4.04x |
| of which egg pays for the nodes added since the last rebuild | 1909886 |  |  |
| unions / merges | 1523697 | 2068839 | 1.36x |
| of which congruences found by the rebuild | 235610 | 442310 | 1.88x |
| facts re-made (analysis_pending pops / analysis.make calls) | 164612 | 208483 | 1.27x |
| modify calls (egg: per union and fact change; jegg: per root per pass) | 1523969 | 281356 | 0.18x |
| node-list nodes walked (rebuild_classes / re-canonicalised classes) | 7818596 | 7213029 | 0.92x |
| classes walked for that | 1732151 | 686670 | 0.40x |
| nodes re-made in the lists (egg: trimmed duplicates) | 649486 | 1307201 | 2.01x |

Per term, jegg's parents visited over egg's absorbed-side parents, terms with 10 or more on egg's
side (315): median 7.75x, p90 30.03x, max 96.46x.

Identities: egg pops minus (added + absorbed) = 0 (0 unless a run stopped between a union and its
rebuild); jegg parents visited minus (hashcons puts + pruned visits) = 0 (expected 0).

## math: 1000 terms of seed 2

Buckets: {SAME=825, NODE_LIMIT=175}. The tables are over `SAME`.

### By quintile of egg's time, smallest to largest

Columns: the quintile; its terms; egg's absorbed-side parents pended; jegg's parents visited and
the ratio; jegg's smaller-side sum (the counterfactual) and its ratio to egg's; jegg's forms
re-made; egg's pops (a memo insert each); jegg's hashcons removes and puts and the ratio.

| q | n | egg abs. | jegg vis. | ratio | jegg min | ratio | re-made | egg pops | jegg hash | ratio |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 165 | 10 | 2515 | 251.50x | 10 | 1.00x | 14 | 1587 | 1404 | 0.88x |
| 2 | 165 | 165 | 6743 | 40.87x | 155 | 0.94x | 211 | 3319 | 8174 | 2.46x |
| 3 | 165 | 1621 | 27532 | 16.98x | 1500 | 0.93x | 1592 | 11594 | 41661 | 3.59x |
| 4 | 165 | 6655 | 71989 | 10.82x | 6375 | 0.96x | 6151 | 27725 | 115491 | 4.17x |
| 5 | 165 | 189295 | 1364837 | 7.21x | 222699 | 1.18x | 212698 | 533281 | 2492099 | 4.67x |

### All `SAME` terms, summed

| what | egg | jegg | jegg/egg |
|---|---|---|---|
| parents visited (egg: the absorbed side's, pended) | 197746 | 1473616 | 7.45x |
| counterfactual: jegg visiting the absorbed side only | 197746 | 239848 | 1.21x |
| counterfactual: jegg visiting the smaller side only | 197746 | 230739 | 1.17x |
| forms re-made (jegg) against egg's visits | 197746 | 220666 | 1.12x |
| hash operations (egg: a memo insert per pop; jegg: removes + puts) | 577506 | 2658829 | 4.60x |
| of which egg pays for the nodes added since the last rebuild | 379760 |  |  |
| unions / merges | 347662 | 396207 | 1.14x |
| of which congruences found by the rebuild | 25188 | 29129 | 1.16x |
| facts re-made (analysis_pending pops / analysis.make calls) | 56869 | 61031 | 1.07x |
| modify calls (egg: per union and fact change; jegg: per root per pass) | 347719 | 74959 | 0.22x |
| node-list nodes walked (rebuild_classes / re-canonicalised classes) | 2184298 | 1998962 | 0.92x |
| classes walked for that | 306016 | 164948 | 0.54x |
| nodes re-made in the lists (egg: trimmed duplicates) | 137007 | 210215 | 1.53x |

Per term, jegg's parents visited over egg's absorbed-side parents, terms with 10 or more on egg's
side (366): median 8.84x, p90 19.95x, max 43.50x.

Identities: egg pops minus (added + absorbed) = 0 (0 unless a run stopped between a union and its
rebuild); jegg parents visited minus (hashcons puts + pruned visits) = 0 (expected 0).

## lambda_function_repeat

egg: 59 iterations, 32636 nodes; jegg: 58 iterations, 32636 nodes: the same work.

| what | egg | jegg | jegg/egg |
|---|---|---|---|
| parents visited (egg: the absorbed side's, pended) | 119466 | 483333 | 4.05x |
| counterfactual: jegg visiting the absorbed side only | 119466 | 110281 | 0.92x |
| counterfactual: jegg visiting the smaller side only | 119466 | 105422 | 0.88x |
| forms re-made (jegg) against egg's visits | 119466 | 102750 | 0.86x |
| hash operations (egg: a memo insert per pop; jegg: removes + puts) | 236267 | 967631 | 4.10x |
| of which egg pays for the nodes added since the last rebuild | 116801 |  |  |
| unions / merges | 109976 | 113629 | 1.03x |
| of which congruences found by the rebuild | 27437 | 27719 | 1.01x |
| facts re-made (analysis_pending pops / analysis.make calls) | 56277 | 53518 | 0.95x |
| modify calls (egg: per union and fact change; jegg: per root per pass) | 110026 | 10367 | 0.09x |
| node-list nodes walked (rebuild_classes / re-canonicalised classes) | 1042995 | 544720 | 0.52x |
| classes walked for that | 338824 | 55708 | 0.16x |
| nodes re-made in the lists (egg: trimmed duplicates) | 84165 | 97386 | 1.16x |

Identities: egg pops minus (added + absorbed) = 0 (0 unless a run stopped between a union and its
rebuild); jegg parents visited minus (hashcons puts + pruned visits) = 0 (expected 0).

## Profile: prop, the largest SAME quintile (119 terms)

38 runs; 8254 execution samples, 2422 under `EGraph.rebuild`. Phases by samples: {apply=467,
other=70, rebuild=2422, search=5295}.

Self frames under the rebuild:

| frame | samples | of rebuild |
|---|---|---|
| EGraph.canonicalize | 495 | 20% |
| HashMap.removeNode | 269 | 11% |
| HashMap.putVal | 214 | 9% |
| EGraph.isCanonical | 199 | 8% |
| IntList.map | 155 | 6% |
| ArraysSupport.mismatch | 137 | 6% |
| EGraph.repair | 131 | 5% |
| ArraysSupport.hashCode | 120 | 5% |
| EGraph.canonicalizeNodes | 114 | 5% |
| HashMap.put | 89 | 4% |
| HashMap.hash | 55 | 2% |
| UnionFind.find | 52 | 2% |
| Objects.equals | 50 | 2% |
| EClass.dedupNodes | 43 | 2% |
| LinkedHashMap.afterNodeInsertion | 42 | 2% |

First jegg frame under the rebuild (where the time is charged):

| frame | samples | of rebuild |
|---|---|---|
| EGraph.repair | 753 | 31% |
| EGraph.canonicalize | 495 | 20% |
| EGraph.isCanonical | 199 | 8% |
| EClass.dedupNodes | 176 | 7% |
| IntList.map | 155 | 6% |
| IntList.equals | 137 | 6% |
| IntList.hashCode | 120 | 5% |
| EGraph.canonicalizeNodes | 114 | 5% |
| EGraph.merge | 85 | 4% |
| UnionFind.find | 52 | 2% |
| IntArray.sortedDistinct | 37 | 2% |
| EClass.appendNodes | 34 | 1% |

Allocation (sampled, weighted): 149047.8 MB in all, 17604.2 MB under the rebuild (12%), 111150304
parents visited: 158 bytes per parent visited.

| class | bytes | of rebuild |
|---|---|---|
| java.util.HashMap$Node | 4391.6 MB | 25% |
| java.util.LinkedHashMap$Entry | 3315.6 MB | 19% |
| [Ljava.lang.Object; | 2840.7 MB | 16% |
| [I | 2353.1 MB | 13% |
| [Ljava.util.HashMap$Node; | 1514.3 MB | 9% |
| io.github.vecbricks.jegg.IntList | 1031.4 MB | 6% |
| java.lang.Integer | 877.7 MB | 5% |
| io.github.vecbricks.jegg.PropRulesTest$Prop$Or | 542.0 MB | 3% |
| java.util.LinkedHashMap | 289.1 MB | 2% |
| java.util.HashMap | 170.6 MB | 1% |

| first jegg frame | bytes | of rebuild |
|---|---|---|
| EGraph.repair | 9351.3 MB | 53% |
| EClass.dedupNodes | 2904.0 MB | 16% |
| IntList.map | 2778.1 MB | 16% |
| EClass.appendNodes | 675.8 MB | 4% |
| EGraph.merge | 584.3 MB | 3% |
| PropRulesTest$Prop$Or.withChildren | 542.0 MB | 3% |
| IntArray.add | 352.4 MB | 2% |
| IntArray.sortedDistinct | 254.0 MB | 1% |
| PropRulesTest$Prop$And.withChildren | 59.2 MB | 0% |
| EGraph.rebuild | 56.3 MB | 0% |

## Profile: math, the largest SAME quintile (165 terms)

62 runs; 8731 execution samples, 1801 under `EGraph.rebuild`. Phases by samples: {apply=442,
other=163, rebuild=1801, search=6325}.

Self frames under the rebuild:

| frame | samples | of rebuild |
|---|---|---|
| EGraph.canonicalize | 357 | 20% |
| HashMap.removeNode | 217 | 12% |
| EGraph.isCanonical | 194 | 11% |
| HashMap.putVal | 152 | 8% |
| IntList.map | 115 | 6% |
| EGraph.repair | 97 | 5% |
| ArraysSupport.hashCode | 94 | 5% |
| ArraysSupport.mismatch | 80 | 4% |
| HashMap.put | 63 | 3% |
| EGraph.isPruned | 62 | 3% |
| EGraph.canonicalizeNodes | 57 | 3% |
| LinkedHashMap.afterNodeInsertion | 45 | 2% |
| UnionFind.find | 40 | 2% |
| HashMap.hash | 30 | 2% |
| EGraph.rebuild | 22 | 1% |

First jegg frame under the rebuild (where the time is charged):

| frame | samples | of rebuild |
|---|---|---|
| EGraph.repair | 590 | 33% |
| EGraph.canonicalize | 357 | 20% |
| EGraph.isCanonical | 194 | 11% |
| IntList.map | 115 | 6% |
| IntList.hashCode | 94 | 5% |
| EClass.dedupNodes | 92 | 5% |
| IntList.equals | 80 | 4% |
| EGraph.isPruned | 77 | 4% |
| EGraph.canonicalizeNodes | 57 | 3% |
| UnionFind.find | 40 | 2% |
| EGraph.rebuild | 35 | 2% |
| IntArray.sortedDistinct | 34 | 2% |

Allocation (sampled, weighted): 272061.8 MB in all, 15048.4 MB under the rebuild (6%), 84619894
parents visited: 178 bytes per parent visited.

| class | bytes | of rebuild |
|---|---|---|
| java.util.HashMap$Node | 3588.0 MB | 24% |
| java.util.LinkedHashMap$Entry | 2864.7 MB | 19% |
| [Ljava.util.HashMap$Node; | 1510.8 MB | 10% |
| [Ljava.lang.Object; | 1274.9 MB | 8% |
| java.lang.Integer | 1044.4 MB | 7% |
| [I | 976.8 MB | 6% |
| io.github.vecbricks.jegg.IntList | 854.9 MB | 6% |
| io.github.vecbricks.jegg.MathTest$Math$Add | 728.5 MB | 5% |
| java.util.ArrayList$Itr | 649.5 MB | 4% |
| java.util.LinkedHashMap | 409.5 MB | 3% |

| first jegg frame | bytes | of rebuild |
|---|---|---|
| EGraph.repair | 9398.4 MB | 62% |
| IntList.map | 1690.5 MB | 11% |
| EClass.dedupNodes | 1626.3 MB | 11% |
| MathTest$Math$Add.withChildren | 728.5 MB | 5% |
| EGraph.isPruned | 649.5 MB | 4% |
| EGraph.retainNodes | 326.8 MB | 2% |
| MathTest$Math$Mul.withChildren | 326.2 MB | 2% |
| IntArray.sortedDistinct | 119.5 MB | 1% |
| EGraph.rebuild | 83.6 MB | 1% |
| EGraph.merge | 44.5 MB | 0% |

## Profile: lambda_function_repeat

136 runs; 8405 execution samples, 2340 under `EGraph.rebuild`. Phases by samples: {apply=368,
other=177, rebuild=2340, search=5520}.

Self frames under the rebuild:

| frame | samples | of rebuild |
|---|---|---|
| EGraph.canonicalize | 609 | 26% |
| HashMap.removeNode | 265 | 11% |
| IntList.map | 221 | 9% |
| EGraph.isCanonical | 205 | 9% |
| EGraph.canonicalizeNodes | 154 | 7% |
| HashMap.putVal | 152 | 6% |
| EGraph.repair | 82 | 4% |
| ArraysSupport.hashCode | 82 | 4% |
| ArraysSupport.mismatch | 66 | 3% |
| HashMap.put | 59 | 3% |
| TreeSet.addAll | 37 | 2% |
| AbstractCollection.addAll | 33 | 1% |
| TreeMap.getFirstEntry | 33 | 1% |
| LinkedHashMap.afterNodeInsertion | 32 | 1% |
| TreeMap.successor | 32 | 1% |

First jegg frame under the rebuild (where the time is charged):

| frame | samples | of rebuild |
|---|---|---|
| EGraph.canonicalize | 609 | 26% |
| EGraph.repair | 583 | 25% |
| IntList.map | 221 | 9% |
| EGraph.isCanonical | 205 | 9% |
| EGraph.canonicalizeNodes | 154 | 7% |
| LambdaTest$2.join | 102 | 4% |
| EClass.dedupNodes | 91 | 4% |
| IntList.hashCode | 82 | 4% |
| IntList.equals | 66 | 3% |
| LambdaTest$2.make | 56 | 2% |
| EGraph.merge | 38 | 2% |
| EGraph.rebuild | 33 | 1% |

Allocation (sampled, weighted): 336442.5 MB in all, 286540.2 MB under the rebuild (85%), 65733288
parents visited: 4359 bytes per parent visited.

| class | bytes | of rebuild |
|---|---|---|
| java.util.LinkedHashMap$Entry | 275085.8 MB | 96% |
| java.util.HashMap$Node | 3017.4 MB | 1% |
| java.util.TreeMap$KeyIterator | 1093.5 MB | 0% |
| [I | 799.7 MB | 0% |
| [Ljava.util.HashMap$Node; | 775.2 MB | 0% |
| java.lang.Integer | 719.8 MB | 0% |
| java.util.Collections$UnmodifiableCollection$1 | 705.8 MB | 0% |
| [Ljava.lang.Object; | 686.4 MB | 0% |
| java.util.TreeMap$Entry | 597.2 MB | 0% |
| java.util.TreeMap | 527.0 MB | 0% |

| first jegg frame | bytes | of rebuild |
|---|---|---|
| EGraph.repair | 278936.3 MB | 97% |
| LambdaTest$2.join | 2081.1 MB | 1% |
| LambdaTest$2.make | 1875.5 MB | 1% |
| EClass.dedupNodes | 1511.2 MB | 1% |
| IntList.map | 1056.0 MB | 0% |
| EGraph.rebuild | 230.6 MB | 0% |
| LambdaTest$Lambda$If.withChildren | 179.1 MB | 0% |
| LambdaTest$Lambda$Add.withChildren | 167.7 MB | 0% |
| EGraph.merge | 158.0 MB | 0% |
| IntArray.sortedDistinct | 130.8 MB | 0% |
