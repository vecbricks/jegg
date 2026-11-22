# jegg against egg, by phase (#72)

The run of `dev/compare.sh` by the Fuzz workflow's `compare` job,
[run 45009265760](https://github.com/vecbricks/jegg/actions/runs/45009265760), artifact
`compare-5`, dispatched on `main` after #133. One GitHub-hosted runner; egg's times are its
runner's own report, jegg's `Runner.phases()`; per term and side each phase is the median over
the five rounds and the total the median of the per-round sums; the two sides alternate per
round. Only the `SAME` bucket (identical runs by `FuzzRun.compare`) enters the ratios; the
`NODE_LIMIT` bucket, where egg's memo-based limit stops it in another iteration, is different
work and is summed apart. How to read the rest: `README.md` here, "jegg against egg, by phase".

A first run of the same on PR #133's branch at 4f208d9
([45007298058](https://github.com/vecbricks/jegg/actions/runs/45007298058), an AMD EPYC 9V74)
read prop `SAME` 1.05x (search 0.56x, apply 1.35x, rebuild 3.86x), math 1.68x (1.30x, 1.65x,
4.61x) and `lambda_function_repeat` 1.54x (1.09x, 2.15x, 3.91x): the same picture on the other
CPU type.

## Provenance

```
jegg   b729153 jegg against egg by phase: Runner.phases() and the compare job (#133)
egg    73975c9 (release build, cargo 1.100.0 (3acc79c0c 2026-11-05))
rounds 5, terms 1000, seed 2, heap 2g fixed, 2026-11-22T09:51:46Z
openjdk version "25.0.5.1" 2026-11-17 LTS
cpus 4; Intel(R) Xeon(R) 6975P-C
load before 1.58 0.47 0.17
load after 1.14 0.72 0.31
```

## prop: 1000 terms of seed 2, 5 rounds

| bucket | terms | egg ms | jegg ms | jegg/egg |
|---|---|---|---|---|
| SAME | 595 | 3605.7 | 3722.2 | 1.03x |
| NODE_LIMIT | 288 | 1897.4 | 4605.8 | 2.43x |

Excluded: BOTH_FAILED 117, INCONCLUSIVE 0, DIVERGE 0.

Phases over SAME:

| phase | egg ms | jegg ms | jegg/egg | share of the gap |
|---|---|---|---|---|
| search | 2353.5 | 1188.8 | 0.51x | -1000% |
| apply | 944.7 | 1343.9 | 1.42x | 343% |
| rebuild | 307.5 | 1189.5 | 3.87x | 757% |
| three phases | 3605.7 | 3722.2 | 1.03x | 100% |
| egg's total with hooks | 3606.0 | | | |
| jegg's wall beyond the phases | | 8.4 | | |

Per-term jegg/egg over SAME, terms where egg took 10 us or more (528): median 1.36x, p90 1.75x, max
3.89x.

Per-round sums of the three phases, ms, all terms: egg 5546.2 5509.0 5511.9 5508.9 5504.3; jegg
9181.5 8434.6 8411.9 8470.7 8474.5.

jegg's slowest ten:

| term | bucket | jegg ms | egg ms | jegg/egg | iterations | nodes | most matched rule |
|---|---|---|---|---|---|---|---|
| 837 | NODE_LIMIT | 75.0 | 1.9 | 40.19x | 18 | 13109 | dist_and_or |
| 449 | SAME | 73.1 | 18.8 | 3.89x | 16 | 16584 | assoc_or |
| 475 | SAME | 72.1 | 24.1 | 2.99x | 16 | 27108 | assoc_or |
| 70 | SAME | 62.1 | 17.9 | 3.47x | 14 | 14721 | assoc_or |
| 173 | SAME | 61.2 | 19.9 | 3.07x | 15 | 23450 | assoc_or |
| 200 | SAME | 59.8 | 19.7 | 3.03x | 17 | 13684 | assoc_or |
| 911 | NODE_LIMIT | 58.6 | 5.9 | 9.90x | 17 | 41474 | dist_or_and |
| 429 | SAME | 53.0 | 16.0 | 3.31x | 12 | 24032 | assoc_or |
| 392 | NODE_LIMIT | 50.5 | 3.9 | 12.94x | 16 | 7169 | dist_or_and |
| 854 | NODE_LIMIT | 50.1 | 2.6 | 19.06x | 20 | 4691 | comm_or |

## math: 1000 terms of seed 2, 5 rounds

| bucket | terms | egg ms | jegg ms | jegg/egg |
|---|---|---|---|---|
| SAME | 825 | 899.4 | 1507.0 | 1.68x |
| NODE_LIMIT | 175 | 1635.4 | 6294.7 | 3.85x |

Excluded: BOTH_FAILED 0, INCONCLUSIVE 0, DIVERGE 0.

Phases over SAME:

| phase | egg ms | jegg ms | jegg/egg | share of the gap |
|---|---|---|---|---|
| search | 540.3 | 653.4 | 1.21x | 19% |
| apply | 293.5 | 543.0 | 1.85x | 41% |
| rebuild | 65.7 | 310.7 | 4.73x | 40% |
| three phases | 899.4 | 1507.0 | 1.68x | 100% |
| egg's total with hooks | 900.1 | | | |
| jegg's wall beyond the phases | | 7.8 | | |

Per-term jegg/egg over SAME, terms where egg took 10 us or more (825): median 1.41x, p90 1.62x, max
2.47x.

Per-round sums of the three phases, ms, all terms: egg 2576.9 2531.6 2533.3 2536.6 2542.8; jegg
8133.0 7837.0 7835.7 7834.2 7792.9.

jegg's slowest ten:

| term | bucket | jegg ms | egg ms | jegg/egg | iterations | nodes | most matched rule |
|---|---|---|---|---|---|---|---|
| 499 | NODE_LIMIT | 79.6 | 15.1 | 5.26x | 28 | 11158 | factor |
| 329 | NODE_LIMIT | 71.0 | 7.7 | 9.22x | 14 | 8909 | add-zero |
| 578 | SAME | 67.2 | 27.7 | 2.43x | 29 | 6527 | assoc-mul |
| 175 | NODE_LIMIT | 64.7 | 33.1 | 1.96x | 30 | 6557 | comm-mul |
| 877 | NODE_LIMIT | 63.9 | 7.0 | 9.16x | 14 | 11189 | d-mul |
| 984 | NODE_LIMIT | 63.3 | 7.6 | 8.33x | 14 | 10151 | d-mul |
| 788 | NODE_LIMIT | 63.0 | 21.6 | 2.92x | 30 | 9962 | comm-mul |
| 639 | NODE_LIMIT | 62.2 | 9.1 | 6.85x | 14 | 11082 | assoc-add |
| 62 | NODE_LIMIT | 59.9 | 7.1 | 8.38x | 14 | 12295 | d-mul |
| 457 | NODE_LIMIT | 58.7 | 15.1 | 3.89x | 26 | 10207 | comm-add |

## lambda_function_repeat, 5 rounds

egg: 59 iterations, 32636 nodes; jegg: 58 iterations, 32636 nodes (jegg stopped by Proved all
goals): the same work (egg counts the iteration a hook stops).

| phase | egg ms | jegg ms | jegg/egg |
|---|---|---|---|
| search | 308.7 | 324.2 | 1.05x |
| apply | 51.9 | 109.8 | 2.12x |
| rebuild | 46.0 | 159.8 | 3.48x |
| three phases | 406.5 | 593.8 | 1.46x |

Per-round, ms: egg 414.6 402.8 406.7 407.3 404.7; jegg 587.4 608.0 598.1 607.1 606.8.
