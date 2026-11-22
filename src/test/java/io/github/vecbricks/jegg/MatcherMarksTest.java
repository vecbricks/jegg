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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The walk binds a variable's first occurrence without asking the substitution (#149): the
 * matches are the ones the asking walk finds, in its order, with the bindings in its order.
 */
class MatcherMarksTest {

  /** Both walks over every rule's left-hand side on the graph, compared match by match. */
  private static <L extends Language<L>, D> int compareAll(EGraph<L, D> g,
      List<Rewrite<L, D>> rules) {
    int compared = 0;
    for (Rewrite<L, D> rule : rules) {
      if (!(rule.lhs() instanceof Pattern<L> pattern)) {
        continue;
      }
      List<Matcher.Match> marked = Matcher.search(g, pattern);
      List<Matcher.Match> asked = Matcher.searchAsking(g, pattern);
      assertEquals(asked.size(), marked.size(), rule.name());
      for (int i = 0; i < asked.size(); i++) {
        assertEquals(asked.get(i).eclass(), marked.get(i).eclass(), rule.name() + " #" + i);
        assertEquals(asked.get(i).subst(), marked.get(i).subst(), rule.name() + " #" + i);
        // Subst.equals is blind to the order of the bindings; ids() is not.
        assertEquals(new ArrayList<>(asked.get(i).subst().ids().keySet()),
            new ArrayList<>(marked.get(i).subst().ids().keySet()), rule.name() + " #" + i);
        compared++;
      }
    }
    return compared;
  }

  @Test
  void theMarkedWalkFindsTheAskingWalksMatchesOnMathAndProp() {
    int compared = 0;
    for (String term : new TermGenerator("math", 2).terms(12)) {
      EGraph<MathTest.Math, Double> g = new EGraph<>(MathTest.CONSTANT_FOLD);
      g.addTree(Term.parse(term), MathTest.BRIDGE);
      new Runner<>(g, MathTest.rules(), RunLimits.DEFAULT.withIterations(4).withNodes(2000),
          new BackoffScheduler<>()).run();
      compared += compareAll(g, MathTest.rules());
    }
    for (String term : new TermGenerator("prop", 2).terms(12)) {
      EGraph<PropRulesTest.Prop, Boolean> g = new EGraph<>(PropRulesTest.CONSTANT_FOLD);
      g.addTree(Term.parse(term), PropRulesTest.BRIDGE);
      new Runner<>(g, PropRulesTest.CONTRAPOSITIVE_CASE.rules(),
          RunLimits.DEFAULT.withIterations(4).withNodes(2000), new BackoffScheduler<>()).run();
      compared += compareAll(g, PropRulesTest.CONTRAPOSITIVE_CASE.rules());
    }
    assertTrue(compared > 1000, "matches compared: " + compared);
  }

  @Test
  void aVariableFirstMetInsideANestedNodeIsAskedForAtTheSiblingAfter() {
    // (+ (* a b) a): a is first in the nested product, and the second child must agree with it,
    // not bind anew. Both walks give the one match per class with the two forms.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int x = g.add(new Toy.Var("x"));
    int y = g.add(new Toy.Var("y"));
    int xy = g.add(new Toy.Mul(IntList.of(x, y)));
    int sum = g.add(new Toy.Add(IntList.of(xy, x)));
    g.add(new Toy.Add(IntList.of(xy, y)));
    Pattern<Toy> a = Pattern.var("a");
    Pattern<Toy> pattern = Pattern.of(new Toy.Add(IntList.EMPTY),
        Pattern.of(new Toy.Mul(IntList.EMPTY), a, Pattern.var("b")), a);
    List<Matcher.Match> marked = Matcher.search(g, pattern);
    assertEquals(Matcher.searchAsking(g, pattern), marked);
    assertEquals(1, marked.size());
    assertEquals(sum, marked.get(0).eclass());
    assertEquals(x, marked.get(0).subst().idOf("a"));
    assertEquals(y, marked.get(0).subst().idOf("b"));
  }

  @Test
  void aPatternNodeUsedTwiceFallsBackToAsking() {
    // The same node object at two positions: (+ (* ?a ?b) (* ?a ?b)) built from one product.
    // "First" is per position, so the marks are not used, and the result is the asking walk's.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int x = g.add(new Toy.Var("x"));
    int y = g.add(new Toy.Var("y"));
    int xy = g.add(new Toy.Mul(IntList.of(x, y)));
    int yx = g.add(new Toy.Mul(IntList.of(y, x)));
    int same = g.add(new Toy.Add(IntList.of(xy, xy)));
    g.add(new Toy.Add(IntList.of(xy, yx)));
    Pattern<Toy> product = Pattern.of(new Toy.Mul(IntList.EMPTY), Pattern.var("a"),
        Pattern.var("b"));
    Pattern<Toy> pattern = Pattern.of(new Toy.Add(IntList.EMPTY), product, product);
    List<Matcher.Match> marked = Matcher.search(g, pattern);
    assertEquals(Matcher.searchAsking(g, pattern), marked);
    assertEquals(1, marked.size());
    assertEquals(same, marked.get(0).eclass());
  }
}
