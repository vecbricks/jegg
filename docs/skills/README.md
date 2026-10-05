# Skills: what this repository learned the hard way

One page per lesson, in Varka's pattern: the situation, what it taught, how to apply it, and the
issues and pull requests that hold the evidence. Read the one for the area you are about to
touch; `docs/architecture.md` says which area a file belongs to.

| skill | when to read it |
|---|---|
| [Checking a port against egg](checking-a-port-against-egg.md) | porting an egg test or changing anything a ported run goes through |
| [Pruning's leftovers carry weight](prunings-leftovers-carry-weight.md) | touching `retainNodes`, the hashcons, `repair`, or anything egg leaves as garbage |
| [A determinism test that can fail](a-determinism-test-that-can-fail.md) | writing a test for a property, or a map that could reach an id |
| [A results file names its commit](a-results-file-names-its-commit.md) | measuring anything, or regenerating a file under `benchmarks/` |
| [The held move in extraction](the-held-move-in-extraction.md) | changing `Extractor.extractAll` or judging its results |
| [The scheduler is asked before the size check](the-scheduler-is-asked-before-the-size-check.md) | touching `Runner.run`'s stop rule or a `Scheduler` |
