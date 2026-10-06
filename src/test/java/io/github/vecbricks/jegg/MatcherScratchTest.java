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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The lists a graph lends its searches ({@code Matcher.Scratch}) and the flag that spares the
 * matcher asking each class whether it has pruned.
 */
class MatcherScratchTest {

  private static Pattern<Toy> v(String name) {
    return Pattern.var(name);
  }

  private static Pattern<Toy> add(Pattern<Toy> left, Pattern<Toy> right) {
    return Pattern.of(new Toy.Add(IntList.EMPTY), left, right);
  }

  /** a + b and b + a in one class, and (a + b) + 1 over it. */
  private static EGraph<Toy, Void> graph() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int ab = g.add(new Toy.Add(IntList.of(a, b)));
    g.merge(ab, g.add(new Toy.Add(IntList.of(b, a))));
    g.add(new Toy.Add(IntList.of(ab, g.add(new Toy.Num(1)))));
    g.rebuild();
    return g;
  }

  @Test
  void aSearchGivesTheListsBackEmptyAndTheNextSearchReusesThem() {
    EGraph<Toy, Void> g = graph();
    Pattern<Toy> nested = add(add(v("x"), v("y")), v("z"));
    List<Matcher.Match> first = Matcher.search(g, nested);
    assertEquals(2, first.size(), first.toString());
    Matcher.Scratch lent = g.borrowScratch();
    assertTrue(lent.found.isEmpty(), "no substitution is kept between searches");
    assertFalse(lent.free.isEmpty(), "the nested node's list went back to the pool");
    lent.free.forEach(list -> assertTrue(list.isEmpty()));
    g.returnScratch(lent);
    assertEquals(first, Matcher.search(g, nested), "the reused lists give the same matches");
  }

  @Test
  void aSearchStartedInsideAnotherGetsListsOfItsOwn() {
    // A head that runs a whole search of the same graph for each node it is asked about, as a
    // nested node of the outer pattern. The outer root's class holds (a + b) + (c + d) and
    // (c + d) + (a + b), so when the head runs for the second node, the outer search already
    // holds the first node's matches in its list: an inner search given the same lists would
    // empty them.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int ab = g.add(new Toy.Add(IntList.of(g.add(new Toy.Var("a")), g.add(new Toy.Var("b")))));
    int cd = g.add(new Toy.Add(IntList.of(g.add(new Toy.Var("c")), g.add(new Toy.Var("d")))));
    int top = g.add(new Toy.Add(IntList.of(ab, cd)));
    g.merge(top, g.add(new Toy.Add(IntList.of(cd, ab))));
    g.rebuild();
    Pattern<Toy> inner = add(v("p"), v("q"));
    int[] innerSearches = {0};
    Pattern.Head<Toy> searching = new Pattern.Head<>() {
      @Override
      public @Nullable Subst match(Toy node, Subst subst) {
        if (!(node instanceof Toy.Add)) {
          return null;
        }
        assertFalse(Matcher.search(g, inner).isEmpty());
        innerSearches[0]++;
        return subst;
      }

      @Override
      public Toy build(Subst subst, IntList children) {
        return new Toy.Add(children);
      }
    };
    Pattern<Toy> outer = add(v("w"), Pattern.node(searching, v("x"), v("y")));
    Pattern<Toy> plain = add(v("w"), add(v("x"), v("y")));
    List<Matcher.Match> expected = Matcher.search(g, plain);
    assertEquals(2, expected.size(), expected.toString());
    assertEquals(expected, Matcher.search(g, outer));
    assertTrue(innerSearches[0] >= 2, "the inner searches ran for both nodes");
    Matcher.Scratch lent = g.borrowScratch();
    assertTrue(lent.found.isEmpty());
    lent.free.forEach(list -> assertTrue(list.isEmpty()));
  }

  @Test
  void theGraphRemembersWhetherAnyClassHasPruned() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int ab = g.add(new Toy.Add(IntList.of(a, b)));
    Toy ba = new Toy.Add(IntList.of(b, a));
    g.merge(ab, g.add(ba));
    int one = g.add(new Toy.Num(1));
    int top = g.add(new Toy.Add(IntList.of(ab, one)));
    g.rebuild();
    assertFalse(g.anyPruned());
    assertEquals(0, g.retainNodes(ab, node -> true));
    assertFalse(g.anyPruned(), "keeping every node prunes nothing");
    assertEquals(1, g.retainNodes(ab, node -> !node.equals(ba)));
    assertTrue(g.anyPruned());
    // The matcher, now asking each class, still finds the dropped node, nested and ground, by
    // its lookup (a search's root is walked, as egg's is, so the node is asked for as a child).
    Pattern<Toy> dropped = add(Pattern.of(new Toy.Var("b")), Pattern.of(new Toy.Var("a")));
    assertEquals(List.of(new Matcher.Match(g.find(top), Subst.EMPTY.bind("z", g.find(one)))),
        Matcher.search(g, add(dropped, v("z"))));
  }
}
