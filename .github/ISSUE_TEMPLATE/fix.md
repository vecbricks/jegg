---
name: A defect
about: Behaviour that is wrong, with how to see it
title: ''
labels: bug
---

**Goal.** The behaviour that is wrong, in one sentence, and the behaviour wanted.

**What is known.** How to reproduce it (a test, a probe, a graph); which invariant or promise it
breaks (`checkInvariants`, determinism, egg's behaviour); where it came from if known (a PR, a
review).

**Plan.** The fix, the files, and the test that fails before and passes after. If the fix
changes a public signature or a run's results, say so.

**Done when** the test passes, the existing suites are unchanged (the egg-pinned counts included),
and `mvn -B verify` is clean.
