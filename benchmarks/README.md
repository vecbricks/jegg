# Measurements

The measurement harness of `PLAN.md` 6 lives under `src/jmh/java` and compiles with the tests
on every build. It runs under the `bench` profile; `-Dbench=help` prints this text, as the
harness's `--help` does:

    mvn -Pbench -q test-compile exec:exec -Dbench=<rebuild|projection|determinism|all>[,<name>]

    Benchmarks (-Dbench=, comma separated, default all):
      rebuild      deferred against eager rebuilding, one saturation of each ported test
      projection   a projection of 64 nodes over the toy date language: saturation, extraction
      determinism  DeterminismProbe in ten fresh JVMs, the renderings compared byte for byte
      all          the three above

    Properties:
      -Dbench.quick=true    a short run that checks the harness; its numbers are not for
                            committing (default false)
      -Dbench.force=true    run although the one-minute load average is above 1.0 (default
                            false)
      -Dbench.pin=<auto|none|cpu list>
                            the cores to pin to: the fast cores sharing cpu0's L3, none, or
                            a taskset list such as 0-3,12-15 (default auto)
      -Dbench.json=true     the files written, as one JSON array, instead of a line each
                            (default false)

    --help, -h, or -Dbench=help prints this text.

It writes one file per benchmark here, `<Name>-jdk<N>-results.txt`, with the header
`CONTRIBUTING.md` asks for: the JVM and OS, the processor, the commit (marked `+dirty` when the
tree had uncommitted changes, which makes the file a draft), the date, the load average before
and after the run, and the JMH settings of each section. `-Dbench.quick=true` runs a short
version that checks the harness; its numbers are not for committing.

A committed file is regenerated as a whole, on an idle machine (a load average under 1 before
the run), from the commit it names. Every number a document quotes traces to one of these
files.

| file | what it measures | `PLAN.md` 6.1 |
|---|---|---|
| `RebuildBenchmark` | one saturation of each ported test, with `rebuild` once per iteration (deferred, the runner's way) against after every merge (eager); the classes repaired in each mode | prediction 1 |
| `ProjectionBenchmark` | a projection of 64 nodes over the toy date language, 20 rules: saturation and extraction, warm and cold, bytes allocated, with and without compact object headers | prediction 4 |
| `DeterminismRun` | `DeterminismProbe` in ten fresh JVMs, the renderings compared byte for byte | prediction 3 |
| `RepeatProfile` | not a benchmark: one JFR profile of the deferred `lambda_function_repeat` run, its samples grouped by phase, for the matcher's share | prediction 5 |
| `RebuildProbe` | not a benchmark: the rebuild's work counted on both sides against egg's (patched) and a JFR profile of jegg's rebuild; on the probe branch `rebuild-probe`, not here; `rebuild-counts.md` is its committed table | #134 |

## Timings: A/B in GitHub Actions

A change whose plan predicts a time is scored by the `Timing` workflow
(`.github/workflows/timing.yml`), not on the owner's machine: dispatched by hand with a `head`
and a `base` ref, it checks out both, and `dev/timing.sh` runs them interleaved on one hosted
runner over the four workloads of #74 (prop and math at 1,000 random terms of seed 2, the
projection at a 10,000-node limit, `lambda_function_repeat`), five rounds in fresh JVMs with a
fixed 2 GB heap. The driver (`Timing`, under `src/jmh/java`) and the workloads are the test
tree of the ref the run was dispatched from; the two sides contribute only their library
classes, so both do the same work by the same code, a head older than the driver can be timed,
and each line records the work done so that can be checked.

The job summary holds the provenance (both commits, the JDK, the runner's CPU, the load) and a
table: per workload, each side's median and range across rounds, the change of the medians, and
a verdict. A verdict is `faster` or `slower` only when the medians differ by more than the larger
of the two sides' own ranges; otherwise it is `within noise`, which is not a result either way.
The rounds are in the run's artifact, `timing-<run number>`. A hosted runner has neighbours and
no pinning, so a plan's gate under the measured range resolves as "within noise" and is said so
when scored; an A/A run (`main` against `main`) measures the floor (#120).

The first runs set three bounds on reading a summary (#126). The runner's CPU differs between
jobs (an Intel Xeon 6975P-C and an AMD EPYC 7763 so far, the latter about 40% slower on prop and
math), so a number compares only with the other side of its own run, never with another run's
or with the laptop figures in #74's PRs. At 100 to 700 ms (the projection, lambda) the range
reached 44% and 18% of the median on the A/A run, so a gate under about 10% on those two
workloads is not one the workflow decides; prop and math at 1,000 terms resolve about 3%. And
the side run second in a round read slower on the projection in the first four runs, the two
A/A runs included, so `dev/timing.sh` alternates the order of the sides from round to round
and the effect cancels in the medians (#129).

These timings are not committed under `benchmarks/`: a results file here names one machine's
provenance, and a workflow run names its own. A PR quotes the run's URL and the table.

## jegg against egg, by phase

`dev/compare.sh` (#72), run by the Fuzz workflow's `compare` job (dispatch `fuzz.yml` with
`compare=true`): the fuzz term sets of a seed through egg's harness and through jegg, the two
sides alternating over the rounds, each term's phases the median over the rounds; and
`lambda_function_repeat` through egg's own test and the harness's deferred run. Work is
compared by `FuzzRun.compare`, and only the `SAME` bucket (identical runs) is summed into the
ratios; the node-limit bucket is summed apart. egg's times are its runner's own report, jegg's
are `Runner.phases()`; the comparison is of the three phases, with egg's hooks and jegg's wall
time beyond the phases printed beside them. `egg-compare.md` here is the committed table of the
run the comparison doc quotes, with its provenance.

