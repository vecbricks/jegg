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
