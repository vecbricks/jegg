/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PatternMatchTest {

  private static final Pattern<Toy> X = Pattern.var("x");
  private static final Pattern<Toy> Y = Pattern.var("y");

  private static Pattern<Toy> add(Pattern<Toy> a, Pattern<Toy> b) {
    return Pattern.of(new Toy.Add(IntList.EMPTY), a, b);
  }

  private static Pattern<Toy> mul(Pattern<Toy> a, Pattern<Toy> b) {
    return Pattern.of(new Toy.Mul(IntList.EMPTY), a, b);
  }

  @Test
  void aPatternMatchesEveryNodeOfItsShapeAndBindsItsVariables() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int ab = g.add(new Toy.Add(IntList.of(a, b)));
    int aa = g.add(new Toy.Add(IntList.of(a, a)));
    int prod = g.add(new Toy.Mul(IntList.of(ab, aa)));
    // ?x + ?y matches both sums, in class order, with the bindings in argument order.
    List<Matcher.Match> sums = Matcher.search(g, add(X, Y));
    assertEquals(List.of(new Matcher.Match(ab, Subst.EMPTY.bind("x", a).bind("y", b)),
        new Matcher.Match(aa, Subst.EMPTY.bind("x", a).bind("y", a))), sums);
    // ?x + ?x matches only the sum whose two children are one class.
    assertEquals(List.of(new Matcher.Match(aa, Subst.EMPTY.bind("x", a))),
        Matcher.search(g, add(X, X)));
    // A nested pattern threads the substitution: (?x + ?y) * (?x + ?x).
    assertEquals(List.of(new Matcher.Match(prod, Subst.EMPTY.bind("x", a).bind("y", b))),
        Matcher.search(g, mul(add(X, Y), add(X, X))));
    // A head with a different payload does not match: Num(1) against Num(2).
    g.add(new Toy.Num(2));
    assertTrue(Matcher.search(g, Pattern.of(new Toy.Num(1))).isEmpty());
    assertEquals(1, Matcher.search(g, Pattern.of(new Toy.Num(2))).size());
  }

  @Test
  void aPayloadVariableBindsTheOperatorsPayloadAndCarriesItToTheRightHandSide() {
    // ?x / ?y with the checked flag bound to ?c, and a rewrite that swaps the operands' roles
    // while keeping the flag: Div(c, x, y) -> Div(c, y, x) (not a true identity, a test of the
    // mechanism). Two divisions with different flags each match once, with their own flag.
    Pattern.Head<Toy> div = Pattern.binding(Toy.Div.class, "c", Toy.Div::checked,
        (c, kids) -> new Toy.Div((Boolean) c, kids));
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int checked = g.add(new Toy.Div(true, IntList.of(a, b)));
    int unchecked = g.add(new Toy.Div(false, IntList.of(a, b)));
    List<Matcher.Match> matches = Matcher.search(g, Pattern.node(div, X, Y));
    assertEquals(2, matches.size());
    assertEquals(Boolean.TRUE, matches.get(0).subst().payload("c"));
    assertEquals(Boolean.FALSE, matches.get(1).subst().payload("c"));
    Rewrite<Toy, Void> swap = Rewrite.of("swap", Pattern.node(div, X, Y), Pattern.node(div, Y, X));
    for (Matcher.Match m : swap.search(g)) {
      swap.apply(g, m);
    }
    g.rebuild();
    assertTrue(g.classOf(checked).nodes().contains(new Toy.Div(true, IntList.of(b, a))));
    assertTrue(g.classOf(unchecked).nodes().contains(new Toy.Div(false, IntList.of(b, a))));
    assertTrue(g.find(checked) != g.find(unchecked));
    g.checkInvariants();
  }

  @Test
  void aConditionReadsTheGraphAndADynamicApplierComputesItsRightHandSide() {
    // Fold ?x + ?y where both classes are known constants (ConstantFoldTest's analysis), the
    // condition reading the facts and the applier adding the sum's number.
    EGraph<Toy, Long> g = new EGraph<>(ConstantFoldTest.FOLD);
    int a = g.add(new Toy.Var("a"));
    int one = g.add(new Toy.Num(1));
    int two = g.add(new Toy.Num(2));
    int known = g.add(new Toy.Mul(IntList.of(one, two)));
    int unknown = g.add(new Toy.Mul(IntList.of(a, two)));
    g.rebuild();
    Rewrite<Toy, Long> fold = Rewrite.<Toy, Long>dynamic("fold-mul", mul(X, Y),
        (graph, eclass, s) -> IntList.of(graph.add(new Toy.Num(
            graph.data(s.idOf("x")) * graph.data(s.idOf("y"))))))
        .when((graph, eclass, s) -> graph.data(s.idOf("x")) != null
            && graph.data(s.idOf("y")) != null);
    // Both products match; the condition, read at apply time, lets only the known one through.
    List<Matcher.Match> matches = fold.search(g);
    assertEquals(2, matches.size());
    for (Matcher.Match m : matches) {
      assertEquals(m.eclass() == g.find(known), fold.apply(g, m).isPresent());
    }
    g.rebuild();
    assertEquals(g.find(known), g.find(g.add(new Toy.Num(2))));
    assertTrue(g.find(unknown) != g.find(known));
    g.checkInvariants();
    g.checkAnalysisInvariant();
  }

  @Test
  void conditionEqualInstantiatesBothPatternsAndComparesTheirClasses() {
    // x + 0 and x are merged; (?x + 0) and ?x then land in one class, and
    // (?x * 2) and ?x do not, but are added to the graph by the asking, as egg's are.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int x = g.add(new Toy.Var("x"));
    int zero = g.add(new Toy.Num(0));
    g.merge(x, g.add(new Toy.Add(IntList.of(x, zero))));
    g.rebuild();
    Subst s = Subst.EMPTY.bind("x", x);
    Condition<Toy, Void> plusZero = Condition.equal(add(X, Pattern.of(new Toy.Num(0))), X);
    Condition<Toy, Void> timesTwo = Condition.equal(mul(X, Pattern.of(new Toy.Num(2))), X);
    assertTrue(plusZero.holds(g, x, s));
    int before = g.numNodes();
    assertFalse(timesTwo.holds(g, x, s));
    g.rebuild();
    assertTrue(g.numNodes() > before, "the instantiated terms are in the graph");
    assertTrue(g.lookup(new Toy.Mul(IntList.of(x, g.add(new Toy.Num(2))))).isPresent());
  }

  @Test
  void conditionEqualReadsBothRootsAfterBothSidesAreAdded() {
    // An analysis whose modify hook, on a sum, merges the sum with both its summands: adding
    // the right side (?x + 0) merges x's class under the smaller root of 0, so x's root read
    // before that addition would be stale, and the condition would read false though x, 0 and
    // the sum are now one class.
    Analysis<Toy, Boolean> joining = new Analysis<>() {
      @Override
      public Boolean make(EGraph<Toy, Boolean> g, Toy node) {
        return node instanceof Toy.Add;
      }

      @Override
      public Boolean join(Boolean a, Boolean b) {
        return a || b;
      }

      @Override
      public void modify(EGraph<Toy, Boolean> g, int id) {
        if (g.data(id)) {
          for (Toy node : List.copyOf(g.classOf(id).nodes())) {
            if (node instanceof Toy.Add sum) {
              g.merge(id, sum.children().get(0));
              g.merge(id, sum.children().get(1));
            }
          }
        }
      }
    };
    EGraph<Toy, Boolean> g = new EGraph<>(joining);
    int zero = g.add(new Toy.Num(0));
    int x = g.add(new Toy.Var("x"));
    assertTrue(g.find(x) > g.find(zero), "x's root is the one a merge would replace");
    Condition<Toy, Boolean> equal = Condition.equal(X, add(X, Pattern.of(new Toy.Num(0))));
    assertTrue(equal.holds(g, x, Subst.EMPTY.bind("x", x)));
    assertEquals(g.find(x), g.find(zero));
  }

  @Test
  void aRightHandSideMayUseOnlyWhatTheLeftBinds() {
    // (+ ?a ?b) => (+ ?b ?c): ?c is a typo, caught when the rewrite is made, not mid-run.
    assertThrows(IllegalArgumentException.class,
        () -> Rewrite.of("typo", add(X, Y), add(Y, Pattern.var("c"))));
    // A payload variable the left does not bind, likewise.
    Pattern.Head<Toy> div = Pattern.binding(Toy.Div.class, "c", Toy.Div::checked,
        (c, kids) -> new Toy.Div((Boolean) c, kids));
    assertThrows(IllegalArgumentException.class,
        () -> Rewrite.of("payload", add(X, Y), Pattern.node(div, X, Y)));
    // Bound on both sides, fine; and a dynamic applier is not checked.
    Rewrite.of("swap", Pattern.node(div, X, Y), Pattern.node(div, Y, X));
    Rewrite.<Toy, Void>dynamic("free", add(X, Y), (_, _, s) -> IntList.of(s.idOf("x")));
    // A client's head that binds ?c without declaring it: the payload check stands aside rather
    // than refuse a right-hand side that is in fact bound.
    Pattern.Head<Toy> undeclared = new Pattern.Head<>() {
      @Override
      public Subst match(Toy node, Subst subst) {
        return node instanceof Toy.Div d ? subst.bindPayload("c", d.checked()) : null;
      }

      @Override
      public Toy build(Subst subst, IntList children) {
        return new Toy.Div((Boolean) subst.payload("c"), children);
      }
    };
    Rewrite.of("client", Pattern.node(undeclared, X, Y), Pattern.node(div, Y, X));
    assertTrue(Pattern.node(undeclared, X, Y).payloadVariables().isEmpty());
  }

  @Test
  void aPayloadVariableBoundTwiceMatchesOnlyWhenBothPayloadsAgree() {
    // (div ?c ?x (div ?c ?y ?z)): the outer and inner modes must be the same.
    Pattern.Head<Toy> div = Pattern.binding(Toy.Div.class, "c", Toy.Div::checked,
        (c, kids) -> new Toy.Div((Boolean) c, kids));
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int c = g.add(new Toy.Var("c"));
    int same = g.add(new Toy.Div(true, IntList.of(a, g.add(new Toy.Div(true, IntList.of(b, c))))));
    int mixed = g.add(new Toy.Div(true,
        IntList.of(a, g.add(new Toy.Div(false, IntList.of(b, c))))));
    Pattern<Toy> nested = Pattern.node(div, X, Pattern.node(div, Y, Pattern.var("z")));
    List<Matcher.Match> matches = Matcher.search(g, nested);
    assertEquals(1, matches.size(), matches.toString());
    assertEquals(same, matches.get(0).eclass());
    assertTrue(g.find(mixed) != same);
    assertEquals(Boolean.TRUE, matches.get(0).subst().payload("c"));
    assertEquals("Div{?c}", div.toString());
    // A head that does not declare its variables, nested under one that does, leaves the whole
    // pattern's payload variables undeclared.
    Pattern.Head<Toy> undeclared = new Pattern.Head<>() {
      @Override
      public Subst match(Toy node, Subst subst) {
        return node instanceof Toy.Div d ? subst.bindPayload("c", d.checked()) : null;
      }

      @Override
      public Toy build(Subst subst, IntList children) {
        return new Toy.Div((Boolean) subst.payload("c"), children);
      }
    };
    assertTrue(Pattern.node(div, X, Pattern.node(undeclared, Y, Pattern.var("z")))
        .payloadVariables().isEmpty());
    assertEquals("swap", Rewrite.of("swap", Pattern.node(div, X, Y), Pattern.node(div, Y, X))
        .toString());
  }

  @Test
  void aLimitedSearchReturnsAPrefixOfTheFullOneAndStopsThere() {
    // Nine sums over nine leaves, one per class: the full search finds nine matches; a search
    // limited to three finds the first three, in the same order, and asks three nodes.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int prev = g.add(new Toy.Var("a0"));
    for (int i = 1; i <= 9; i++) {
      prev = g.add(new Toy.Add(IntList.of(prev, g.add(new Toy.Var("a" + i)))));
    }
    Toy.CountingHead full = new Toy.CountingHead();
    List<Matcher.Match> all = Matcher.search(g, Pattern.node(full, X, Y));
    assertEquals(9, all.size());
    assertEquals(9, full.asked);
    Toy.CountingHead limited = new Toy.CountingHead();
    assertEquals(all.subList(0, 3), Matcher.search(g, Pattern.node(limited, X, Y), 3));
    assertEquals(3, limited.asked);
    assertEquals(all, Matcher.search(g, Pattern.node(new Toy.CountingHead(), X, Y), 9));
    assertEquals(all, Matcher.search(g, Pattern.node(new Toy.CountingHead(), X, Y), 100));
    assertThrows(IllegalArgumentException.class,
        () -> Matcher.search(g, Pattern.node(new Toy.CountingHead(), X, Y), 0));
  }

  @Test
  void aLimitedSearchStopsWithinAClassToo() {
    // The nine sums merged into one class, the shape of a rule about to be banned: the full
    // search asks all nine nodes of the class; limited to three it asks three and stops.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int first = -1;
    for (int i = 0; i < 9; i++) {
      int sum = g.add(new Toy.Add(IntList.of(g.add(new Toy.Var("l" + i)),
          g.add(new Toy.Var("r" + i)))));
      first = first < 0 ? sum : g.merge(first, sum);
    }
    g.rebuild();
    assertEquals(9, g.classOf(first).nodes().size());
    Toy.CountingHead full = new Toy.CountingHead();
    List<Matcher.Match> all = Matcher.search(g, Pattern.node(full, X, Y));
    assertEquals(9, all.size());
    assertEquals(9, full.asked);
    Toy.CountingHead limited = new Toy.CountingHead();
    assertEquals(all.subList(0, 3), Matcher.search(g, Pattern.node(limited, X, Y), 3));
    assertEquals(3, limited.asked);
  }

  /** A head that matches like {@code inner} but names no node class: every class is looked at. */
  private static Pattern.Head<Toy> untyped(Pattern.Head<Toy> inner) {
    return new Pattern.Head<>() {
      @Override
      public Subst match(Toy node, Subst subst) {
        return inner.match(node, subst);
      }

      @Override
      public Toy build(Subst subst, IntList children) {
        return inner.build(subst, children);
      }
    };
  }

  @Test
  void aHeadThatNamesItsNodeClassFindsWhatALookAtEveryClassFinds() {
    // The index from node class to classes follows merges (a class merged away leaves it, the
    // kept one gains its operators) and prunes (a class whose last node of an operator is
    // dropped leaves that operator's set); a search through it must find exactly what a search
    // through every class finds, in the same order. checkInvariants checks the index both ways.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int c = g.add(new Toy.Var("c"));
    int ab = g.add(new Toy.Add(IntList.of(a, b)));
    int bc = g.add(new Toy.Add(IntList.of(b, c)));
    int ac = g.add(new Toy.Mul(IntList.of(a, c)));
    int top = g.add(new Toy.Add(IntList.of(ab, c)));
    g.merge(ab, bc);
    g.merge(top, g.add(new Toy.Var("d")));
    g.rebuild();
    assertEquals(1, g.retainNodes(g.find(top), node -> node instanceof Toy.Var));
    g.checkInvariants();
    Pattern.Head<Toy> addHead = Pattern.head(new Toy.Add(IntList.EMPTY));
    assertEquals(Optional.of(Toy.Add.class), addHead.type());
    assertTrue(untyped(addHead).type().isEmpty());
    Pattern<Toy> typed = Pattern.node(addHead, Pattern.var("x"), Pattern.var("y"));
    Pattern<Toy> everywhere = Pattern.node(untyped(addHead), Pattern.var("x"), Pattern.var("y"));
    List<Matcher.Match> found = Matcher.search(g, typed);
    assertEquals(Matcher.search(g, everywhere), found);
    assertEquals(2, found.size(), found.toString());
    assertEquals(g.find(ab), found.get(0).eclass(), "the merged class, once");
    assertTrue(found.stream().noneMatch(m -> m.eclass() == g.find(top)), "pruned: no sum left");
    assertEquals(List.of(new Matcher.Match(ac, Subst.EMPTY.bind("x", a).bind("y", c))),
        Matcher.search(g, Pattern.of(new Toy.Mul(IntList.EMPTY), Pattern.var("x"),
            Pattern.var("y"))));
    // A binding head over the language's interface: the union of every node class's set.
    Pattern.Head<Toy> anyBinary = Pattern.binding(Toy.class, "op", node -> node.getClass(),
        (op, kids) -> op == Toy.Mul.class ? new Toy.Mul(kids) : new Toy.Add(kids));
    assertEquals(Optional.of(Toy.class), anyBinary.type());
    Pattern<Toy> binary = Pattern.node(anyBinary, Pattern.var("x"), Pattern.var("y"));
    assertEquals(Matcher.search(g, Pattern.node(untyped(anyBinary), Pattern.var("x"),
        Pattern.var("y"))), Matcher.search(g, binary));
    assertEquals(3, Matcher.search(g, binary).size());
    // A class of a node type the graph never held: nothing, and nothing thrown.
    assertEquals(List.of(), Matcher.search(g, Pattern.of(new Toy.Num(0))));
    // The union for an interface type follows later adds, merges and prunes.
    int ad = g.add(new Toy.Mul(IntList.of(a, g.find(top))));
    assertEquals(4, Matcher.search(g, binary).size());
    g.merge(ad, ac);
    g.rebuild();
    g.checkInvariants();
    assertEquals(Matcher.search(g, Pattern.node(untyped(anyBinary), Pattern.var("x"),
        Pattern.var("y"))), Matcher.search(g, binary));
    assertEquals(4, Matcher.search(g, binary).size());
  }

  @Test
  void aPruneThatLeavesANodeOfTheOperatorKeepsTheClassIndexedUnderIt() {
    // a + b and b + a in one class with a variable: dropping b + a alone keeps the class in the
    // sums' set, since a + b is still there; dropping both takes it out.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int ab = g.add(new Toy.Add(IntList.of(a, b)));
    Toy ba = new Toy.Add(IntList.of(b, a));
    g.merge(ab, g.add(ba));
    g.merge(ab, g.add(new Toy.Var("e")));
    g.rebuild();
    Pattern<Toy> sum = Pattern.of(new Toy.Add(IntList.EMPTY), Pattern.var("x"), Pattern.var("y"));
    assertEquals(2, Matcher.search(g, sum).size());
    assertEquals(1, g.retainNodes(ab, node -> !node.equals(ba)));
    g.checkInvariants();
    assertEquals(List.of(new Matcher.Match(ab, Subst.EMPTY.bind("x", a).bind("y", b))),
        Matcher.search(g, sum));
    assertEquals(1, g.retainNodes(ab, node -> node instanceof Toy.Var));
    g.checkInvariants();
    assertEquals(List.of(), Matcher.search(g, sum));
  }

  @Test
  void aKeepPredicateThatThrowsLeavesTheClassAsItWas() {
    // The predicate is the caller's: it is asked about every node before the class changes, so
    // a throw part-way leaves the nodes, their head ordinals and the table as they were. It
    // drops the first sum, keeps the second, which would move down, and throws on the variable.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int ab = g.add(new Toy.Add(IntList.of(a, b)));
    Toy ba = new Toy.Add(IntList.of(b, a));
    g.merge(ab, g.add(ba));
    g.merge(ab, g.add(new Toy.Var("e")));
    g.rebuild();
    Pattern<Toy> sum = Pattern.of(new Toy.Add(IntList.EMPTY), Pattern.var("x"), Pattern.var("y"));
    List<Matcher.Match> before = Matcher.search(g, sum);
    assertThrows(IllegalStateException.class, () -> g.retainNodes(ab, node -> {
      if (node instanceof Toy.Var) {
        throw new IllegalStateException("the caller's predicate failed");
      }
      return node.equals(ba);
    }));
    g.checkInvariants();
    assertEquals(before, Matcher.search(g, sum));
  }

  @Test
  void aPrototypeWhoseHeadIsACheaperKeyNamesNoTypeAndStillMatches() {
    // A language whose head() is a string shared by two node classes: the head built from a
    // prototype must not start at the prototype's class alone, or the other class's nodes would
    // be missed. It names no type, and every class is looked at.
    record Plus(IntList children) implements Language<Plus> {
      @Override
      public Plus withChildren(IntList children) {
        return new Plus(children);
      }

      @Override
      public Object head() {
        return "+";
      }
    }
    Pattern.Head<Plus> plus = Pattern.head(new Plus(IntList.EMPTY));
    assertTrue(plus.type().isEmpty());
    assertEquals(Optional.of(Toy.Add.class), Pattern.head(new Toy.Add(IntList.EMPTY)).type());
    EGraph<Plus, Void> g = EGraph.withoutAnalysis();
    int leaf = g.add(new Plus(IntList.EMPTY));
    int sum = g.add(new Plus(IntList.of(leaf, leaf)));
    assertEquals(List.of(new Matcher.Match(sum,
        Subst.EMPTY.bind("x", leaf))), Matcher.search(g, Pattern.node(plus, Pattern.var("x"),
            Pattern.var("x"))));
  }

  @Test
  void aSubstitutionReachedTwiceWithinOneNodesWalkIsOneMatch() {
    // A child head that binds the payload variable p for Div(true, ..) and binds nothing for
    // Div(false, ..), over a class holding both: the first child yields {p, x, y} and {x, y},
    // the second child over the same class extends both to {p, x, y}, among others, so one
    // node's walk reaches {p, x, y} twice. It is one match.
    Pattern.Head<Toy> some = new Pattern.Head<>() {
      @Override
      public Subst match(Toy node, Subst subst) {
        if (!(node instanceof Toy.Div d)) {
          return null;
        }
        if (!d.checked()) {
          return subst;
        }
        return subst.hasPayload("p") ? subst : subst.bindPayload("p", true);
      }

      @Override
      public Toy build(Subst subst, IntList children) {
        return new Toy.Div(subst.hasPayload("p"), children);
      }
    };
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int c = g.add(new Toy.Div(true, IntList.of(a, b)));
    g.merge(c, g.add(new Toy.Div(false, IntList.of(a, b))));
    g.rebuild();
    int outer = g.add(new Toy.Add(IntList.of(c, c)));
    Pattern<Toy> both = Pattern.of(new Toy.Add(IntList.EMPTY),
        Pattern.node(some, Pattern.var("x"), Pattern.var("y")),
        Pattern.node(some, Pattern.var("x"), Pattern.var("y")));
    Subst xy = Subst.EMPTY.bind("x", a).bind("y", b);
    assertEquals(List.of(new Matcher.Match(outer, xy.bindPayload("p", true)),
        new Matcher.Match(outer, xy)), Matcher.search(g, both));
    assertEquals(1, Matcher.search(g, both, 1).size());
  }

  @Test
  void aVariableBoundAlreadyMatchesItsClassAloneWhenAskedOfOneClass() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    Subst bound = Subst.EMPTY.bind("x", a);
    assertEquals(List.of(bound), Matcher.matchIn(g, Pattern.var("x"), a, bound));
    assertEquals(List.of(), Matcher.matchIn(g, Pattern.var("x"), b, bound));
    assertEquals(List.of(Subst.EMPTY.bind("y", b)),
        Matcher.matchIn(g, Pattern.var("y"), b, Subst.EMPTY));
  }

  @Test
  void twoNodesOfAClassThatYieldOneSubstitutionAreOneMatchAndCountOnceForTheLimit() {
    // A head that reads no payload over Div(true, a, b) and Div(false, a, b) in one class: one
    // substitution, not two, and the limited search counts it once, so a limit of one stops at
    // the first class with a result and a limit of two reaches the second.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int d = g.add(new Toy.Div(true, IntList.of(a, b)));
    g.merge(d, g.add(new Toy.Div(false, IntList.of(a, b))));
    g.rebuild();
    int e = g.add(new Toy.Div(true, IntList.of(b, a)));
    assertEquals(2, g.classOf(d).nodes().size());
    Pattern.Head<Toy> anyDiv = new Pattern.Head<>() {
      @Override
      public Subst match(Toy node, Subst subst) {
        return node instanceof Toy.Div ? subst : null;
      }

      @Override
      public Toy build(Subst subst, IntList children) {
        return new Toy.Div(true, children);
      }

      @Override
      public Optional<Class<? extends Toy>> type() {
        return Optional.of(Toy.Div.class);
      }
    };
    Pattern<Toy> div = Pattern.node(anyDiv, Pattern.var("x"), Pattern.var("y"));
    List<Matcher.Match> all = Matcher.search(g, div);
    assertEquals(List.of(new Matcher.Match(d, Subst.EMPTY.bind("x", a).bind("y", b)),
        new Matcher.Match(e, Subst.EMPTY.bind("x", b).bind("y", a))), all);
    assertEquals(all.subList(0, 1), Matcher.search(g, div, 1));
    assertEquals(all, Matcher.search(g, div, 2));
    // Nested under another node, the duplicate is one substitution there too.
    int outer = g.add(new Toy.Add(IntList.of(d, e)));
    Pattern<Toy> nested = Pattern.of(new Toy.Add(IntList.EMPTY), div,
        Pattern.node(anyDiv, Pattern.var("p"), Pattern.var("q")));
    assertEquals(List.of(new Matcher.Match(outer, Subst.EMPTY.bind("x", a).bind("y", b)
        .bind("p", b).bind("q", a))), Matcher.search(g, nested));
  }
}
