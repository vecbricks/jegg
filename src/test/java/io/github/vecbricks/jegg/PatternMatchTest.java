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
      assertEquals(m.eclass() == g.find(known), fold.apply(g, m) >= 0);
    }
    g.rebuild();
    assertEquals(g.find(known), g.find(g.add(new Toy.Num(2))));
    assertTrue(g.find(unknown) != g.find(known));
    g.checkInvariants();
    g.checkAnalysisInvariant();
  }
}
