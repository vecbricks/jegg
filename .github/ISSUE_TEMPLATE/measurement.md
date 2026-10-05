---
name: A measurement or a performance change
about: Something to make faster or to measure, with predictions registered before the run
title: ''
labels: enhancement
---

**Goal.** What is slow or unknown, with the number that says so and the file it traces to
(`benchmarks/`).

**What is known.** Where the time goes (a profile, a review), what the baseline is and which
commit it names.

**Plan.** The change; the tests that show it changes no result (the egg-pinned counts, the
invariants); which benchmark scores it and how it is regenerated (`benchmarks/README.md`: an idle
machine the owner has offered, pinned, from a clean commit).

**Predictions**, registered before the run: numbered, with the number each will be compared to.

**Done when** the tests pass, the results file is regenerated from the branch's commit, and the
PR scores the predictions against the baseline.
