# Changelog

All notable changes to jegg are listed here, newest first, in the form of
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). jegg is at 0.x: while the API moves, a
minor version may break it (`CONTRIBUTING.md`, "Releases"). Each pull request adds its line under
`Unreleased`.

## [Unreleased]

## [0.1.0] - unreleased

### Added

- The e-graph: union-find, hashcons, deferred rebuilding, e-class analyses, `addTree` and
  `lookupTree` (#1, #2, #32).
- Patterns and rewrites, with payload variables and predicates; `Matcher`, `Rewrite`, `Subst`
  (#1).
- Multi-patterns: clauses joined on shared variables, a `Searcher` for a rule's left side, egg's
  applied count (#66).
- The runner with egg's backoff scheduler, `RunLimits`, `RunReport` and hooks; ids and
  matches that are a fixed function of the input (#2, #19, #23).
- `TreeBridge`, from a client's own tree type to e-nodes with payloads (#1, #11).
- Extraction over one root and over several roots that share a DAG (`extractAll`, `Selection`),
  with an incremental descent checked against an exact oracle with a step budget (#11, #24, #61).
- egg's five test suites ported with pinned counts: `simple`, `lambda`, `math` (with egg's pruning
  of folded classes), `prop`, `datalog` (#19, #30, #65, #69).
- Differential fuzzing against egg, including scheduler scenarios (#70, #77).
- The measurement harness, its committed results and the Varka-sized projection benchmark (#22,
  #49, #85).
- A README, a two-page guide, a map of the code, a comparison with egg module by module, Javadoc
  on Pages, and contributor and agent guides (#38, #40, #41, #42, #63).
- JSpecify nullness, a module descriptor, a stated threading rule and a listed public surface
  (#78, #79).
- Guards: line and branch coverage, Javadoc warnings as errors, Dependabot (#26, #58).

### Changed

- Rebuilding takes time proportional to its work: shared parent entries, no whole-graph sweep
  (#50).
- Unboxed ids on the hot paths: int worklists, arrays by class id, substitutions without maps
  (#51).
- Matching reads a class through a table of its nodes sorted by head, with children laid flat; it
  allocates per match, builds its deduplication set only where duplicates can arise, and looks up
  a ground nested node in the hashcons where that finds more or costs less. Saturation is 3 to 10
  times faster than before on the ported suites and the projection, with the same matches and
  counts (#52, #80, #81, #82, #83, #86, #89).
- Four types left the public surface (#78).
- The backoff scheduler stops searching one match past its threshold, as egg's does (#31).

### Fixed

- Atomic merge, checked ids and rewrites, exact saturation and limits (#23).
- A stale analysis fact after a repair-time merge, and a hashcons key left behind by a prune,
  both found by fuzzing against egg (#73).
- The Javadoc workflow builds again, so the API docs deploy (#88).

[Unreleased]: https://github.com/vecbricks/jegg/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/vecbricks/jegg/releases/tag/v0.1.0
