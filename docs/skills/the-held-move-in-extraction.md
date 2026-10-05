# The held move in extraction

## The situation

`extractAll` chooses one node per class over several roots so that a shared subterm is paid
once. The greedy start (each class takes the node whose DAG costs least, the gym's
`faster-greedy-dag`) is not enough: on the smoke test each field alone prefers the direct form
(21) over the shared decomposition (30 + 1 + 1), so greedy gives 42 where 33 is possible. A
descent that keeps a single change only when the total falls cannot leave 41 either: switching
one field to the decomposition costs 11 more until the other follows.

## What it taught

- Sharing needs a move that accepts a worse state for one step. A change that scores worse alone
  is **held** while the selected parents of the classes it brought in are offered the nodes that
  use them, and kept if the whole scored lower. The plan's own walkthrough passed through the
  worse state; the plan's algorithm could not.
- **Random small graphs do not exercise this.** Enumeration on 100 random graphs of up to six
  classes gave 100 of 100 for the descent with and without the held move, and greedy alone
  missed only three in a thousand. The shape that needs it (two roots that each gain only if both
  switch) is rare at random. The smoke test checks the move; extraction-gym's real graphs
  (`ExtractionGymTest`) and an exact branch-and-bound oracle check the result.
- **The cost is per candidate.** The first descent rebuilt the selection per candidate and ran a
  nested descent per held move: 42 ms on 278 nodes. Incremental bookkeeping (reference counts,
  an undo log) and holding only for classes newly brought in took it to 0.13 ms with the same
  selections. A held move must be a few candidates, not a pass.

## How to apply it

- Judge a change to `extractAll` against three things: the smoke test at 33, the enumeration at
  100 of 100, and the gym oracle (59 of 59 it finishes, of 60 graphs up to 300 nodes; 8 of them
  improved over the greedy start); name every miss.
- A candidate must never close a cycle (the start's check is a gray/black walk; a form-based
  check missed cycles of three), and a parent entry must be canonicalised before it is offered
  (a merge leaves the other child's list stale).
- Beam search over the greedy start (the gym's PR #48) is the next move if the oracle ever shows
  a miss the held move cannot reach.

Evidence: #4's plan and departure, PR #11, #12's plan and research comment, PR #24 and its review.
