# A results file names its commit

## The situation

`CONTRIBUTING.md`: measurements are committed under `benchmarks/` with a provenance header, and
every number a document quotes traces to one of those files. The harness writes the header
(JVM, OS, processor, pinned cores, governor, commit, date, load before and after, JMH settings).
Three things went wrong anyway while #22 and #24 were measured.

## What it taught

- **The machine is the owner's.** It is shared with Varka's test runs and the owner's own work;
  a load-average check of your own both missed idle windows and started a run over the owner's.
  A benchmark runs only when the owner says the machine is idle, each time, and is killed at
  once if the window is gone.
- **Ask whether a re-run is needed before planning one.** A file that names a commit in the
  branch's history satisfies the rule; a re-run for exactness alone costs the owner's idle time.
  Relabelling a file by hand (as Varka did for 62 cases) is a legitimate alternative when only
  names changed - but only if the file still exists.
- **Commit or stage before anything destructive.** A 27-minute results file was deleted before a
  rename, meaning to regenerate it; when relabelling turned out to be preferred, it was gone.
- **The harness's own output can trip it.** A results file just written is an untracked file, so
  the next file in the same run was stamped `+dirty`; and a run's own load lifted the average
  above the threshold for the next run. The harness now reads the commit once at startup and
  takes a list of benchmarks in one process.

## How to apply it

- `benchmarks/README.md` has the command and the rules; `-Dbench.quick=true` checks the wiring
  with numbers not for committing.
- A PR that changes performance registers its predictions in the issue, lands as code with
  tests, and leaves the regeneration to an idle window the owner offers; the prediction is scored
  when the file lands, and the PR says which commit the file names.
- Several changes can share one window: regenerate both files once and score every prediction.

Evidence: #6's comments, #22, #24's comments, the `Benchmarks` Javadoc.
