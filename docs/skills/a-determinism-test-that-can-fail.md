# A determinism test that can fail

## The situation

`DeterminismTest` forks `DeterminismProbe` into fresh JVMs and compares the rendered graphs
byte for byte, to catch a hash iteration order leaking into an id or a merge. For two months it
could not have caught one: every `hashCode` of the probe's language (`Toy`) was value-based
(`long`, `String`, `Arrays.hashCode`), so every `HashMap` iterated the same way in every JVM. A
leak would have passed.

## What it taught

A test for a property must be shown able to fail on a violation, or it is a test of nothing. The
probe's language now has variable names that are interned objects without a `hashCode` of their
own, so their hash is the identity hash and differs between JVMs; with a deliberate leak added
(ids assigned in a `HashMap`'s order), the forked test fails; without it, it passes.

## How to apply it

- When writing a test for a property (determinism, an invariant, a limit), break the property on
  purpose once, locally, and watch the test fail; then revert. Say in the PR that you did.
- Any map in `src/main` whose iteration order could reach an id assignment or a merge order is
  insertion-ordered (`LinkedHashMap`, `LinkedHashSet`, `TreeMap`); a plain `HashMap` is fine only
  where its order reaches nothing.
- Fixed orders are part of jegg's promise (`PLAN.md` 3.1): ids by insertion, rules in declared
  order, matches by class id then node insertion, extraction ties by node order, the smaller root
  kept in a union.

Evidence: #14 item 7, PR #23, `DeterminismProbe.Name`.
