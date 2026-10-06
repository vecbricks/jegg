# Pruning's leftovers carry weight

## The situation

egg's `math` suite prunes a folded class to its constant in the analysis's `modify`
(`nodes.retain(is_leaf)`), editing the class's node list and leaving the memo entry and the parent
entries behind as garbage. The plan for the port (#18) said: remove the node from the class, the
hashcons and the parent lists, and let a re-added node get a fresh class. Three ported tests
disagreed.

## What it taught

Both leftovers do work, and a port that cleans them up changes the algorithm:

- **The memo entry.** Adding a pruned node again must find its class and change nothing. Without
  it, `add-zero` re-makes `(+ 0 0)` in the class of `0` on every iteration, the graph changes
  every iteration, and `math_fail` never saturates where egg saturates in four.
- **The parent entries.** A pruned node must stay a parent of its children: a later merge of a
  child can make it congruent with a live node, and `repair` must union the two classes. Without
  it, three tests ended with more classes than egg.

And a third lesson, from the review of the port: **how a pruned entry is recognised must not
depend on the node's form.** A parent entry can carry a form older than any the prune saw (a
child's id changed between the entry's last re-key and the prune), so judging by stored or
canonical form put pruned nodes back into the hashcons. The fact is recorded where it cannot go
stale: a flag on the class that pruned, and the class's node list.

A fourth, from fuzzing math against egg (#44): **a pruned node's hashcons key is not only the two
forms `retainNodes` knows.** It removes the form the class listed and the canonical form, but an
entry keyed under an older form (a child's class merged away since, not yet repaired) kept that
key past the prune, and the hashcons then held a node no class listed: `numNodes()` counted one
more than the graph holds. `repair`'s pruned branch now also drops the entry's own key, if it
names the entry's class. `EGraphRebuildTest` has the hand-built case.

## How to apply it

- jegg keeps its hashcons exact (it holds exactly the graph's nodes, which `checkInvariants`
  asserts) and remembers pruned nodes in a separate map that `add` and `lookup` consult; `repair`
  re-keys a pruned parent there, judged by `EClass.hasPruned()` and the node list, never by form.
- When porting a behaviour of egg's that leaves state behind, ask what reads the leftover before
  deleting it; the egg-pinned counts will tell you if you guessed wrong.
- The extractor must choose only nodes a class lists, since `lookup` now finds pruned ones too.

Evidence: #18's plan comment and departure note, PR #30 and its review, the three
`EGraphMergeTest` tests on pruning.
