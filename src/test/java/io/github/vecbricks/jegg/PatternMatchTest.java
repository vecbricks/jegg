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
}
