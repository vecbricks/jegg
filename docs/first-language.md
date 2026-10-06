# Your first language: payloads, a bridge, a binding rule, several roots

A walk through the three things jegg has that egg does not, on the toy date
language of `DateSmokeTest` (`src/test/java/.../DateSmokeTest.java` has the
whole thing, and `ProjectionBenchmark`'s `Projection` a larger one). The
scenario is a compiler's: two fields of one shifted date, `year(d + s1 + s2)` and
`month(d + s1 + s2)`, where each field can be read straight from the day count
(dear) or off a civil decomposition (dear once, cheap per field), and the two
should share the decomposition.

Add jegg to a Maven build with the coordinates of the README's "Using it"
(`io.github.vecbricks:jegg:0.1.0`); the code below runs against that artifact.

## 1. The language: records with payloads

```java
sealed interface Date extends Language<Date> permits Col, Slot, SlotSum, AddDays, Civil, Field, FieldOf {
  record Col() implements Date { ... }                      // the input column
  record Slot(int index) implements Date { ... }            // a literal bound at run time
  record SlotSum(IntList children) implements Date { ... }  // a scalar over slots, free per row
  record AddDays(IntList children) implements Date { ... }
  record Civil(IntList children) implements Date { ... }    // the decomposition: 30
  record Field(String name, IntList children) implements Date { ... }    // straight: 20
  record FieldOf(String name, IntList children) implements Date { ... } // off a Civil: 1
}
```

`Slot(int index)` and `Field(String name, ...)` carry payloads: the record's
fields beyond `children`. Two `Field` nodes with different names are different
operators to the hashcons, which is what you want. A leaf's `children()` is
`IntList.EMPTY` and its `withChildren` returns `this`; a node's `withChildren(c)`
returns a copy over `c`, which is how the graph canonicalises nodes.

## 2. The bridge from your own tree

A compiler already has an IR with child *nodes*; an e-node has child *ids*.
`TreeBridge<T, L>` converts: `childrenOf(tree)` lists a tree's children,
`node(tree, childIds)` makes the e-node for a tree over its children's classes,
and `build(node, childTrees)` goes back. `EGraph.addTree(tree, bridge)` adds a
whole tree bottom-up (a subtree object reached twice is added once),
`lookupTree` asks whether the graph holds one, and `Extracted.toTree(bridge)`
turns an extraction back into your type. The tests' `Term` with its bridge in
`DateSmokeTest` is the smallest example; `LambdaTest.BRIDGE` shows a bridge that
refuses a misspelt operator.

## 3. A rule that binds a payload

`Pattern.of(prototype, children...)` matches a node with exactly the prototype's
operator and payload. To match a `Field` of *any* name and carry the name to the
right-hand side, bind it:

```java
Pattern.Head<Date> field = Pattern.binding(Date.Field.class, "f", Date.Field::name,
    (name, kids) -> new Date.Field((String) name, kids));
Pattern.Head<Date> fieldOf = Pattern.binding(Date.FieldOf.class, "f", Date.FieldOf::name,
    (name, kids) -> new Date.FieldOf((String) name, kids));

Rewrite.of("field-through-civil",
    Pattern.node(field, v("d")),                                   // (Field ?f ?d)
    Pattern.node(fieldOf, Pattern.of(new Date.Civil(IntList.EMPTY), v("d"))));  // (FieldOf ?f (Civil ?d))
```

The head binds the payload variable `f`; the `Subst` of a match holds it beside
the subterm variable `d`, and the right-hand side's head reads it back. A
`Rewrite` is refused at construction if its right-hand side uses a variable,
subterm or payload, that the left does not bind - as egg refuses it - which
catches a typo before the run. The third rule of the smoke test folds two
offsets into one slot expression: `(AddDays (AddDays ?d ?a) ?b) =>
(AddDays ?d (SlotSum ?a ?b))`, which is how a folding rule works when the
literals are run-time slots and there are no values to fold.

## 4. Run, then extract over several roots

```java
EGraph<Date, Void> g = EGraph.withoutAnalysis();
int year = g.add(...);   // Field("year", shifted)
int month = g.add(...);  // Field("month", shifted)
RunReport report = Runner.of(g, rules()).run();      // Saturated

Extractor<Date, Void> ex = new Extractor<>(g, TABLE);
ex.best(year).cost();                                // 21: alone, straight from the day count
Selection<Date> both = ex.extractAll(IntList.of(year, month));
both.cost();                                         // 33: Civil once (30), two FieldOf (1 + 1), AddDays (1)
both.terms().get(0).children().get(0) == both.terms().get(1).children().get(0)  // one Civil object
```

Alone, each field is cheaper read directly (20 + 1 against 30 + 1 + 1), and egg's
per-root extraction would give both the direct form, 42 in all. `extractAll`
chooses one node per class over the union of the roots: the decomposition is
chosen once and both fields read off it, 33. The choice is a greedy start and a
descent that holds a change which pays off only once another root follows it
(`Extractor`'s Javadoc has the algorithm); `extractAll(roots, score)` lets you
price a whole selection yourself, which is how a compiler runs its own cost
model over a candidate.

## What to read next

`docs/concepts.md` for the ideas; the Javadoc for the surface; `PLAN.md` 3.2 for
why these three things are in the API; `CONTRIBUTING.md` to change something.
