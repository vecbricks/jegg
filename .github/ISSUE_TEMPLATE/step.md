---
name: A step or a feature
about: Something the library should do that it does not yet
title: ''
labels: enhancement
---

**Goal.** What the library should do when this is done, and for whom. If it ports a part of egg,
name the egg source and version.

**What is known.** What exists already, what has been tried, what constraint the design must
respect (`PLAN.md` section, an issue, a measurement).

**Plan.** The files it touches; the design, and the alternatives set aside and why; the tests
that accept it; for a port, the counts to pin to egg's own run.

**Predictions**, to score in the pull request: numbered, each checkable.

**Done when** the tests above pass, `mvn -B verify` is clean, and the PR scores the predictions.
