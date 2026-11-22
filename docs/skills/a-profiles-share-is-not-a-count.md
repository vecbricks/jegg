# A profile's share is not a count

## The situation

Three changes in a row in November 2026 were picked from a frame's share of a JFR profile and
scored flat on the clock:

- **#138.** The `HashMap` frames of the hashcons were 22% of prop's execution samples and 18%
  of math's. A table of jegg's own (open addressing, cached hashes, no boxing) left the sum of
  the map's frame and the keys' `equals` where it was, 15.5% to 14.9%, and the Timing within
  noise (PR #144, parked).
- **#145.** The records' `equals` under the lookups were 8.6% of prop's samples, and the
  operators over one pair of children shared a hash. Salting the hash per operator cut the mean
  probe displacement from 1.06 to 0.61 and moved nothing: the samples were the hits' `equals`,
  one per successful lookup, not the collisions' (PR #148, closed).
- **#149.** `Subst.nodeOfInterned` was the top self frame of math's run, 12 to 14%. A count
  showed the walk asking it 4.7 million times on 60 prop terms and hearing "unbound" 89% of the
  time. Binding a variable's first occurrence without asking removed 85% of those calls; the
  frame's share did not move (prop 8.7% to 9.9%, math 14.3% to 13.9%) and nor did the Timing
  (PR #150). The method is inlined into the walk's hot loop, and the profiler credits it with
  samples that are the loop's.

And one misreading of the same profiles: the probe's phase split filed the apply's adds under
search because `Matcher.instantiate` has `Matcher` in its name, so the apply read as 7% of prop
where it is about 40% (#146).

## What it taught

- **A self-frame share says where samples sit, not what a change removes.** Removing the work
  a frame is charged with is a prediction to register, not a conclusion to act on. The two
  questions are different: "what is hot" and "what would go".
- **Count before building.** A throwaway counter on the branch, run locally over a few dozen
  fuzz terms, is a count and not a timing, so the owner's machine may run it. It settles in
  minutes what a workflow run settles in an hour: how many calls, how long the chains, how many
  hits against misses. #149's count was right about the calls and wrong about the cost, which
  is still better than no count: it made the miss legible.
- **Inlining moves the blame.** A small method inlined into a hot loop takes the loop's samples
  in the profile's virtual frames. Read the callers under the frame (`dev/jfr_callers.py` does
  this for the map frames) and the self frames of the loop itself before trusting a leaf.
- **The wall clock by phase is the ground truth.** The Fuzz workflow's `compare` job times the
  three phases with egg as the control on the same runner; the Timing workflow resolves about
  3% on prop and math. A profile's phase split is a reading of stacks and can be wrong (#146).
- **Register the exit.** #149 wrote into its plan which result would close it with no code,
  and that is what happened. An issue that cannot fail is not a measurement.

## How to apply it

- Before an issue names a frame as its lever: count what the change would remove, on the
  branch, with counters that are not committed, and write the count into the issue.
- Predict the gain as the frame's share times the fraction of its work the count says goes
  away, never as the share itself; name the measurement that scores it (the compare job's phase
  row, the Timing's verdict) and the count that closes the issue if the gain is not there.
- When scoring, attribute the after-profile's frames by caller and phase as the before-profile
  was, and say so when a share did not move: the next reader should not pick the same lever.

Evidence: #138 and PR #144, #145 and PR #148, #149 and PR #150, #146; `benchmarks/rebuild-counts.md`
("After #138"), `dev/jfr_callers.py`.
