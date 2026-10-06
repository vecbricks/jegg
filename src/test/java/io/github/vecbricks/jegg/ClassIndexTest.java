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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The indexes a class keeps for the matcher: they name the nodes a walk would find, in its
 * order; they are dropped by every change of the node list; and the matcher reads them in a
 * class of {@link Matcher#INDEX_FROM} nodes or more and walks a smaller one, to the same matches.
 */
class ClassIndexTest {

  private static final Pattern<Toy> X = Pattern.var("x");
  private static final Pattern<Toy> Y = Pattern.var("y");

  private static Pattern<Toy> add(Pattern<Toy> a, Pattern<Toy> b) {
    return Pattern.of(new Toy.Add(IntList.EMPTY), a, b);
  }

  private static Pattern<Toy> mul(Pattern<Toy> a, Pattern<Toy> b) {
    return Pattern.of(new Toy.Mul(IntList.EMPTY), a, b);
  }

  /**
   * A graph with one big class: leaves a, b and the numbers 0 to n-1, and a class holding, in
   * this order, (a+b), (b+a), a*b, b*a, then (a+k) and (k*a) for each k, and 0. Big enough that
   * the matcher reads it through the indexes.
   */
  private static EGraph<Toy, Void> bigClass(int n) {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int c = g.add(new Toy.Add(IntList.of(a, b)));
    g.merge(c, g.add(new Toy.Add(IntList.of(b, a))));
    g.merge(c, g.add(new Toy.Mul(IntList.of(a, b))));
    g.merge(c, g.add(new Toy.Mul(IntList.of(b, a))));
    for (int k = 0; k < n; k++) {
      int num = g.add(new Toy.Num(k));
      g.merge(c, g.add(new Toy.Add(IntList.of(a, num))));
      g.merge(c, g.add(new Toy.Mul(IntList.of(num, a))));
    }
    g.merge(c, g.add(new Toy.Num(0)));
    g.rebuild();
    g.checkInvariants();
    return g;
  }

  /** The positions a walk of the class would stop at for this head, in the walk's order. */
  private static List<Integer> walk(EClass<Toy, Void> eclass, Pattern.Head<Toy> head) {
    List<Integer> out = new ArrayList<>();
    List<Toy> nodes = eclass.nodes();
    for (int i = 0; i < nodes.size(); i++) {
      if (head.match(nodes.get(i), Subst.EMPTY) != null) {
        out.add(i);
      }
    }
    return out;
  }

  private static List<Integer> list(IntArray positions) {
    List<Integer> out = new ArrayList<>();
    for (int i = 0; i < positions.size(); i++) {
      out.add(positions.get(i));
    }
    return out;
  }

  @Test
  void theHeadIndexNamesTheNodesAWalkFindsInItsOrder() {
    EGraph<Toy, Void> g = bigClass(10);
    EClass<Toy, Void> c = g.classOf(g.find(g.lookup(new Toy.Num(0)).getAsInt()));
    assertTrue(c.nodes().size() >= Matcher.INDEX_FROM, "the class must be big enough to index");
    for (Toy prototype : List.of(new Toy.Add(IntList.EMPTY), new Toy.Mul(IntList.EMPTY),
        new Toy.Num(0), new Toy.Num(7))) {
      Pattern.Head<Toy> head = Pattern.head(prototype);
      IntArray positions = c.positionsWithHead(head.key().get());
      // Num(7) is in its own class, not this one: a walk finds nothing and the index has no entry.
      assertEquals(walk(c, head), positions == null ? List.of() : list(positions),
          head.toString());
    }
    // A head no node has: no positions, not an empty array.
    assertNull(c.positionsWithHead(new Toy.Num(99).head()));
    assertNull(c.positionsOfType(Toy.Div.class));
    // The type index: every Add and Mul node, in order, and the one Num.
    List<Integer> adds = new ArrayList<>();
    List<Integer> nums = new ArrayList<>();
    for (int i = 0; i < c.nodes().size(); i++) {
      if (c.nodes().get(i) instanceof Toy.Add) {
        adds.add(i);
      } else if (c.nodes().get(i) instanceof Toy.Num) {
        nums.add(i);
      }
    }
    assertEquals(adds, list(c.positionsOfType(Toy.Add.class)));
    assertEquals(nums, list(c.positionsOfType(Toy.Num.class)));
    g.checkInvariants();
  }

  @Test
  void theMatcherFindsTheSameMatchesThroughTheIndexAsByAWalk() {
    EGraph<Toy, Void> g = bigClass(10);
    int a = g.find(g.lookup(new Toy.Var("a")).getAsInt());
    int b = g.find(g.lookup(new Toy.Var("b")).getAsInt());
    int big = g.find(g.lookup(new Toy.Num(0)).getAsInt());
    // (?x + ?y) over the big class: one match per Add node, in insertion order.
    List<Matcher.Match> sums = Matcher.search(g, add(X, Y));
    List<Matcher.Match> expected = new ArrayList<>();
    for (Toy node : g.classOf(big).nodes()) {
      if (node instanceof Toy.Add(var kids)) {
        expected.add(new Matcher.Match(big, Subst.EMPTY.bind("x", g.find(kids.get(0)))
            .bind("y", g.find(kids.get(1)))));
      }
    }
    assertEquals(expected, sums);
    assertEquals(12, sums.size());
    // A nested pattern node into the big class: (0 * ?x), where 0's class is the big class, so
    // the leaf pattern is matched inside it: the one Mul node whose first child is that class.
    List<Matcher.Match> zeroTimes = Matcher.search(g, mul(Pattern.of(new Toy.Num(0)), X));
    assertEquals(List.of(new Matcher.Match(big, Subst.EMPTY.bind("x", a))), zeroTimes);
    // A payload-binding head reads the type index: every Num node of the class binds ?n.
    Pattern<Toy> anyNum = Pattern.node(Pattern.binding(Toy.Num.class, "n", Toy.Num::value,
        (n, kids) -> new Toy.Num((Long) n)));
    List<Matcher.Match> nums = Matcher.search(g, anyNum);
    // Nine number classes and the big class, which holds 0: ten matches, the big one's binding 0.
    assertEquals(10, nums.size());
    assertEquals(Optional.of(0L), nums.stream().filter(m -> m.eclass() == big)
        .map(m -> m.subst().payload("n")).findFirst());
    // A head with neither key nor type is asked about every node of the big class.
    int[] asked = {0};
    Pattern.Head<Toy> counting = new Pattern.Head<>() {
      @Override
      public Subst match(Toy node, Subst subst) {
        asked[0]++;
        return node instanceof Toy.Mul ? subst : null;
      }

      @Override
      public Toy build(Subst subst, IntList children) {
        return new Toy.Mul(children);
      }
    };
    List<Matcher.Match> products = Matcher.search(g, Pattern.node(counting, X, Y));
    assertEquals(12, products.size());
    // Every binary node of every class is tried; the leaves fail the arity test before the head.
    assertEquals(24, asked[0]);
    assertEquals(b, products.get(0).subst().idOf("y"));
    g.checkInvariants();
  }

  @Test
  void everyChangeOfTheNodeListDropsTheIndexes() {
    EGraph<Toy, Void> g = bigClass(10);
    int big = g.find(g.lookup(new Toy.Num(0)).getAsInt());
    int a = g.find(g.lookup(new Toy.Var("a")).getAsInt());
    EClass<Toy, Void> c = g.classOf(big);
    Pattern.Head<Toy> addHead = Pattern.head(new Toy.Add(IntList.EMPTY));
    // Built by a search, then dropped by a merge into the class.
    Matcher.search(g, add(X, Y));
    assertTrue(c.hasIndex());
    int extra = g.add(new Toy.Add(IntList.of(a, a)));
    g.merge(big, extra);
    g.rebuild();
    c = g.classOf(big);
    assertTrue(!c.hasIndex() || list(c.positionsWithHead(addHead.key().get())).size() == 13);
    assertEquals(13, Matcher.search(g, add(X, Y)).size());
    g.checkInvariants();
    // Dropped by a merge of a child class, which rebuild canonicalises the nodes for.
    assertTrue(c.hasIndex());
    int b = g.find(g.lookup(new Toy.Var("b")).getAsInt());
    g.merge(a, b);
    g.rebuild();
    g.checkInvariants();
    // (a+b), (b+a) and (a+a) are one node now, so one match fewer per duplicate dropped.
    List<Matcher.Match> sums = Matcher.search(g, add(X, Y));
    assertEquals(walk(g.classOf(big), addHead).size(), sums.size());
    g.checkInvariants();
    // Dropped by pruning.
    assertTrue(g.classOf(big).hasIndex());
    int dropped = g.retainNodes(big, node -> !(node instanceof Toy.Mul));
    assertTrue(dropped > 0);
    assertTrue(Matcher.search(g, mul(X, Y)).isEmpty());
    assertEquals(walk(g.classOf(big), addHead).size(), Matcher.search(g, add(X, Y)).size());
    g.checkInvariants();
  }

  @Test
  void aSmallClassIsWalkedAndGivesTheSameMatches() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int ab = g.add(new Toy.Add(IntList.of(a, b)));
    g.merge(ab, g.add(new Toy.Mul(IntList.of(a, b))));
    g.rebuild();
    EClass<Toy, Void> c = g.classOf(ab);
    assertTrue(c.nodes().size() < Matcher.INDEX_FROM);
    assertEquals(List.of(new Matcher.Match(g.find(ab), Subst.EMPTY.bind("x", a).bind("y", b))),
        Matcher.search(g, add(X, Y)));
    assertTrue(!c.hasIndex(), "a small class is walked, not indexed");
    g.checkInvariants();
  }

  @Test
  void theInvariantCheckRefusesAnIndexThatDisagreesWithTheNodes() {
    EGraph<Toy, Void> g = bigClass(10);
    int big = g.find(g.lookup(new Toy.Num(0)).getAsInt());
    EClass<Toy, Void> c = g.classOf(big);
    Matcher.search(g, add(X, Y));
    Matcher.search(g, Pattern.node(Pattern.binding(Toy.Num.class, "n", Toy.Num::value,
        (n, kids) -> new Toy.Num((Long) n))));
    assertTrue(c.hasIndex());
    g.checkInvariants();
    // The live list changed behind the index's back: a node of another head at an indexed
    // position, then a node the index does not list.
    List<Toy> live = c.readNodes();
    Toy first = live.get(0);
    live.set(0, new Toy.Div(true, IntList.of(big, big)));
    assertThrows(IllegalStateException.class, c::checkIndexes);
    live.set(0, first);
    c.checkIndexes();
    live.add(new Toy.Var("z"));
    assertThrows(IllegalStateException.class, c::checkIndexes);
    live.remove(live.size() - 1);
    c.checkIndexes();
    g.checkInvariants();
    // The type index alone, built by a payload-binding head: the same two disagreements.
    EGraph<Toy, Void> h = bigClass(10);
    EClass<Toy, Void> d = h.classOf(h.find(h.lookup(new Toy.Num(0)).getAsInt()));
    Matcher.search(h, Pattern.node(Pattern.binding(Toy.Num.class, "n", Toy.Num::value,
        (n, kids) -> new Toy.Num((Long) n))));
    assertTrue(d.hasIndex());
    List<Toy> nodes = d.readNodes();
    Toy head = nodes.get(0);
    nodes.set(0, new Toy.Div(true, IntList.of(d.id(), d.id())));
    assertThrows(IllegalStateException.class, d::checkIndexes);
    nodes.set(0, head);
    nodes.add(new Toy.Var("z"));
    assertThrows(IllegalStateException.class, d::checkIndexes);
    nodes.remove(nodes.size() - 1);
    h.checkInvariants();
  }
}
