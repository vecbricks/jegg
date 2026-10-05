# The scheduler is asked before the size check

## The situation

egg's runner decides saturation as `applied.is_empty() && scheduler.can_stop(i) && sizes
unchanged`, in that order, and `BackoffScheduler.can_stop` is not a pure question: it releases
bans. jegg's first runner asked the scheduler only when nothing had merged *and* nothing had been
added. A condition that adds nodes without merging (`if-elim`'s `ConditionEqual` instantiates two
terms) then kept jegg from asking, its bans were released an iteration later than egg's, and
`lambda_function_repeat` hit its iteration limit where egg proves the goal in 58.

## What it taught

- A call that looks like a predicate may be a mutation; the order of `&&` operands is then part
  of the algorithm, and a "simplification" that reorders them changes results. `Runner.run` now
  hoists the call into its own statement and `Scheduler.canStop`'s Javadoc states the contract:
  asked after every iteration in which no rule merged two classes, whether or not nodes were
  added.
- Sizes cannot tell an iteration that changed nothing: an add and a merge in one iteration leave
  the node and class counts as they were. `EGraph.changes()` counts both, and the runner reads it
  before the hooks run, so a hook's edits count too.
- A passed limit stops the run even when the rebuild brings the size back under it, as egg's
  does; the rules skipped on its account are named in the report.

## How to apply it

- Any change to `Runner.run`'s stop rule must keep: the scheduler asked whenever no rule merged;
  the change counter read before the hooks; a passed limit kept. `RunnerTest` has a test for each,
  and the egg-pinned suites must still end at egg's counts.
- When porting a condition from another codebase, write down which operands have effects.

Evidence: #19 and its PR description, #14's plan note and PR #23, `Scheduler.canStop`'s Javadoc.
