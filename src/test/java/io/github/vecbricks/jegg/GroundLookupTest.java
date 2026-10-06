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
 * egg's lookup in the matcher: a nested pattern node that is ground when it is reached is looked
 * up in the hashcons rather than walked where the child's class has pruned, so it finds a node
 * {@code retainNodes} dropped, as egg's memo does, and where the run of its head in the class is
 * long. Neither changes the matches a walk finds on a graph without pruning, nor their order
 * except where a node has two nested children, which are matched smallest run first: a naive
 * matcher that walks every node, child by child from the left, is the oracle for that.
 */
class GroundLookupTest {

  /**
   * The matcher as a definition: every node of the class whose head matches, each child pattern
   * against the child's class from the left, a variable bound or checked where it stands. No
   * index, no lookup, no reordering; for keyed heads on a rebuilt graph its results are distinct,
   * so no set either.
   */
  static final class Reference<L extends Language<L>, D> {
    private final EGraph<L, D> graph;

    Reference(EGraph<L, D> graph) {
      this.graph = graph;
    }

    List<Matcher.Match> search(Pattern<L> pattern) {
      List<Matcher.Match> out = new ArrayList<>();
      for (EClass<L, D> c : graph.classes()) {
        for (Subst s : matchIn(pattern, c.id(), Subst.EMPTY)) {
          out.add(new Matcher.Match(c.id(), s));
        }
      }
      return out;
    }

    List<Subst> matchIn(Pattern<L> pattern, int id, Subst subst) {
      List<Subst> out = new ArrayList<>();
      int root = graph.find(id);
      switch (pattern) {
        case Pattern.Var<L>(var name) -> {
          var bound = subst.id(name);
          if (bound.isEmpty()) {
            out.add(subst.bind(name, root));
          } else if (graph.find(bound.getAsInt()) == root) {
            out.add(subst);
          }
        }
        case Pattern.Node<L>(var head, var children) -> {
          for (L node : graph.classOf(root).nodes()) {
            if (node.children().size() != children.size()) {
              continue;
            }
            Subst headBound = head.match(node, subst);
            if (headBound != null) {
              children(children, 0, node, headBound, out);
            }
          }
        }
      }
      return out;
    }

    private void children(List<Pattern<L>> children, int i, L node, Subst subst,
        List<Subst> out) {
      if (i == children.size()) {
        out.add(subst);
        return;
      }
      for (Subst s : matchIn(children.get(i), node.children().get(i), subst)) {
        children(children, i + 1, node, s, out);
      }
    }
  }

  private static Pattern<EGraphPropertyTest.Op> op(String name,
      List<Pattern<EGraphPropertyTest.Op>> children) {
    return new Pattern.Node<>(Pattern.head(new EGraphPropertyTest.Op(name, IntList.EMPTY)),
        children);
  }

  private static final Pattern<EGraphPropertyTest.Op> X = Pattern.var("x");
  private static final Pattern<EGraphPropertyTest.Op> Y = Pattern.var("y");
  private static final Pattern<EGraphPropertyTest.Op> T = op("T", List.of());

  /**
   * Patterns with a ground subterm after a variable (looked up), a variable after a nested node
   * that binds it (bound first), repeated variables and a leaf.
   */
  private static final List<Pattern<EGraphPropertyTest.Op>> PATTERNS = List.of(
      op("or", List.of(X, op("not", List.of(X)))),
      op("and", List.of(op("not", List.of(X)), X)),
      op("and", List.of(op("or", List.of(X, Y)), op("not", List.of(X)))),
      op("and", List.of(op("not", List.of(op("not", List.of(X)))), X)),
      op("or", List.of(op("and", List.of(X, Y)), op("and", List.of(Y, X)))),
      op("and", List.of(X, op("or", List.of(Y, X)))),
      op("or", List.of(op("not", List.of(X)), op("or", List.of(X, T)))),
      op("and", List.of(T, op("not", List.of(X)))),
      op("not", List.of(op("and", List.of(X, X)))));

  @Test
  void theMatcherAgreesWithAWalkOfEveryNodeListForListOnRandomRebuiltGraphs() {
    int matches = 0;
    for (long seed = 1; seed <= 3000; seed++) {
      long which = seed;
      List<EGraphPropertyTest.Step> steps = EGraphPropertyTest.program(seed);
      List<EGraph<EGraphPropertyTest.Op, ?>> graphs = List.of(
          EGraphPropertyTest.run(steps, EGraphPropertyTest.Mode.AT_THE_END,
              EGraph::<EGraphPropertyTest.Op>withoutAnalysis, false).graph(),
          EGraphPropertyTest.run(steps, EGraphPropertyTest.Mode.WHERE_THE_PROGRAM_SAYS,
              () -> new EGraph<>(EGraphPropertyTest.truth(false)), true).graph());
      for (EGraph<EGraphPropertyTest.Op, ?> g : graphs) {
        Reference<EGraphPropertyTest.Op, ?> reference = new Reference<>(g);
        for (Pattern<EGraphPropertyTest.Op> pattern : PATTERNS) {
          List<Matcher.Match> found = Matcher.search(g, pattern);
          List<Matcher.Match> walked = reference.search(pattern);
          if (reorders(pattern)) {
            // A node with two nested children matches them smallest run first, so within a
            // class the matches may come in another order than the walk's: the same set.
            assertEquals(walked.size(), found.size(), () -> "seed " + which + ", " + pattern);
            assertEquals(new java.util.HashSet<>(walked), new java.util.HashSet<>(found),
                () -> "seed " + which + ", " + pattern);
          } else {
            assertEquals(walked, found, () -> "seed " + which + ", " + pattern);
          }
          matches += found.size();
        }
      }
    }
    assertTrue(matches > 20_000, matches + " matches");
  }

  /** Whether some node of the pattern has two or more nested children, which the matcher may reorder. */
  private static boolean reorders(Pattern<?> pattern) {
    if (pattern instanceof Pattern.Node<?> node) {
      int nested = 0;
      for (Pattern<?> child : node.children()) {
        if (child instanceof Pattern.Node) {
          nested++;
        }
        if (reorders(child)) {
          return true;
        }
      }
      return nested >= 2;
    }
    return false;
  }

  @Test
  void aGroundSubtermFindsANodeThatWasPrunedAsEggsMemoDoes() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int one = g.add(new Toy.Num(1));
    int two = g.add(new Toy.Num(2));
    int sum = g.add(new Toy.Add(IntList.of(one, two)));
    int three = g.merge(sum, g.add(new Toy.Num(3)));
    g.rebuild();
    g.retainNodes(three, node -> node instanceof Toy.Num);
    int prod = g.add(new Toy.Mul(IntList.of(a, three)));
    g.rebuild();
    g.checkInvariants();
    assertEquals(List.of(new Toy.Num(3)), g.classOf(three).nodes(), "the sum was dropped");
    Pattern<Toy> onePlusTwo = Pattern.of(new Toy.Add(IntList.EMPTY), Pattern.of(new Toy.Num(1)),
        Pattern.of(new Toy.Num(2)));
    Pattern<Toy> pattern = Pattern.of(new Toy.Mul(IntList.EMPTY), Pattern.var("x"), onePlusTwo);
    // The ground subterm (+ 1 2) is looked up: the hashcons remembers the dropped node with its
    // class, so the pattern matches, as egg's would; a walk of the class's nodes finds no sum.
    assertEquals(List.of(new Matcher.Match(prod, Subst.EMPTY.bind("x", a))),
        Matcher.search(g, pattern));
    assertTrue(new Reference<>(g).search(pattern).isEmpty(), "a walk does not see the dropped node");
    // The same subterm as a whole pattern is not ground at its root: the root is walked.
    assertTrue(Matcher.search(g, onePlusTwo).isEmpty());
    // A ground subterm the graph never had matches nothing.
    Pattern<Toy> onePlusOne = Pattern.of(new Toy.Add(IntList.EMPTY), Pattern.of(new Toy.Num(1)),
        Pattern.of(new Toy.Num(1)));
    assertTrue(Matcher.search(g, Pattern.of(new Toy.Mul(IntList.EMPTY), Pattern.var("x"),
        onePlusOne)).isEmpty());
  }

  @Test
  void aVariableToTheRightOfANestedNodeAgreesWithTheWalk() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int ab = g.add(new Toy.Add(IntList.of(a, b)));
    int prodA = g.add(new Toy.Mul(IntList.of(ab, a)));
    int prodB = g.add(new Toy.Mul(IntList.of(ab, b)));
    g.rebuild();
    // (* (+ ?x ?y) ?x): (+ ?x ?y) binds ?x and ?y from (+ a b), then ?x must agree with the
    // second child: it does at (* (+ a b) a) and not at (* (+ a b) b).
    Pattern<Toy> pattern = Pattern.of(new Toy.Mul(IntList.EMPTY),
        Pattern.of(new Toy.Add(IntList.EMPTY), Pattern.var("x"), Pattern.var("y")),
        Pattern.var("x"));
    assertEquals(List.of(new Matcher.Match(prodA, Subst.EMPTY.bind("x", a).bind("y", b))),
        Matcher.search(g, pattern));
    assertEquals(new Reference<>(g).search(pattern), Matcher.search(g, pattern));
    // (* (+ ?x ?x) ?x): (+ a a) is not in the graph, so nothing matches; added, it matches at
    // the product over it, and nowhere else.
    Pattern<Toy> twice = Pattern.of(new Toy.Mul(IntList.EMPTY),
        Pattern.of(new Toy.Add(IntList.EMPTY), Pattern.var("x"), Pattern.var("x")),
        Pattern.var("x"));
    assertTrue(Matcher.search(g, twice).isEmpty());
    int aa = g.add(new Toy.Add(IntList.of(a, a)));
    int prodAA = g.add(new Toy.Mul(IntList.of(aa, a)));
    g.rebuild();
    assertEquals(List.of(new Matcher.Match(prodAA, Subst.EMPTY.bind("x", a))),
        Matcher.search(g, twice));
    assertEquals(new Reference<>(g).search(twice), Matcher.search(g, twice));
    assertTrue(prodB != prodA);
  }

  @Test
  void aGroundSubtermInALongRunIsLookedUpToTheWalksMatches() {
    // A class with more Add nodes than LOOKUP_FROM, (a + k) for k up to 11 and (a + b), under a
    // product whose other child is a: the ground (+ a 3) in that class is looked up, and so is
    // (+ a 99), which is not there, and the walk agrees on both.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int sums = g.add(new Toy.Add(IntList.of(a, b)));
    for (int k = 0; k < 12; k++) {
      g.merge(sums, g.add(new Toy.Add(IntList.of(a, g.add(new Toy.Num(k))))));
    }
    int prod = g.add(new Toy.Mul(IntList.of(sums, a)));
    g.rebuild();
    g.checkInvariants();
    assertTrue(g.classOf(sums).nodes().size() > Matcher.LOOKUP_FROM);
    Pattern<Toy> three = Pattern.of(new Toy.Mul(IntList.EMPTY),
        Pattern.of(new Toy.Add(IntList.EMPTY), Pattern.var("x"), Pattern.of(new Toy.Num(3))),
        Pattern.var("x"));
    assertEquals(List.of(new Matcher.Match(g.find(prod), Subst.EMPTY.bind("x", a))),
        Matcher.search(g, three));
    assertEquals(new Reference<>(g).search(three), Matcher.search(g, three));
    Pattern<Toy> none = Pattern.of(new Toy.Mul(IntList.EMPTY),
        Pattern.of(new Toy.Add(IntList.EMPTY), Pattern.var("x"), Pattern.of(new Toy.Num(99))),
        Pattern.var("x"));
    assertTrue(Matcher.search(g, none).isEmpty());
    // (+ ?x ?x) over the same class is ground once ?x is bound by the first child: (+ a a) is
    // not among the sums, so nothing; (+ ?x ?y) is not ground and is walked, one match per sum.
    Pattern<Toy> same = Pattern.of(new Toy.Mul(IntList.EMPTY),
        Pattern.of(new Toy.Add(IntList.EMPTY), Pattern.var("x"), Pattern.var("x")),
        Pattern.var("x"));
    assertTrue(Matcher.search(g, same).isEmpty());
    Pattern<Toy> any = Pattern.of(new Toy.Mul(IntList.EMPTY),
        Pattern.of(new Toy.Add(IntList.EMPTY), Pattern.var("x"), Pattern.var("y")),
        Pattern.var("x"));
    assertEquals(13, Matcher.search(g, any).size());
    assertEquals(new Reference<>(g).search(any), Matcher.search(g, any));
  }

  @Test
  void aPayloadBindingHeadInsideAGroundSubtermIsBuiltFromItsBinding() {
    // Div(checked, a, b) twice under one Add: the first Div binds ?c, ?x and ?y, the second is
    // then ground and looked up through build(subst, children), which reads ?c.
    Pattern.Head<Toy> anyDiv = Pattern.binding(Toy.Div.class, "c", Toy.Div::checked,
        (c, kids) -> new Toy.Div((Boolean) c, kids));
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int checked = g.add(new Toy.Div(true, IntList.of(a, b)));
    int unchecked = g.add(new Toy.Div(false, IntList.of(a, b)));
    int same = g.add(new Toy.Add(IntList.of(checked, checked)));
    int mixed = g.add(new Toy.Add(IntList.of(checked, unchecked)));
    g.rebuild();
    // The checked division's class has pruned (a Num was merged in and the Div kept), so the
    // ground second child is looked up, through the payload the first bound.
    g.merge(checked, g.add(new Toy.Num(7)));
    g.rebuild();
    g.retainNodes(g.find(checked), node -> node instanceof Toy.Div);
    g.rebuild();
    Pattern<Toy> pattern = Pattern.of(new Toy.Add(IntList.EMPTY),
        Pattern.node(anyDiv, Pattern.var("x"), Pattern.var("y")),
        Pattern.node(anyDiv, Pattern.var("x"), Pattern.var("y")));
    List<Matcher.Match> found = Matcher.search(g, pattern);
    assertEquals(List.of(new Matcher.Match(same,
        Subst.EMPTY.bind("x", a).bind("y", b).bindPayload("c", true))), found);
    assertTrue(mixed != same);
    assertEquals(new Reference<>(g).search(pattern), found);
  }

  @Test
  void aGroundClauseOfAMultiPatternAbsentFromTheGraphMatchesNothing() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    g.add(new Toy.Add(IntList.of(a, b)));
    g.rebuild();
    // ?s = (+ ?x ?y) binds ?x and ?y; ?p = (* ?x ?y) is then ground and looked up: no product
    // is in the graph, so the join yields nothing; added, it yields the one match.
    MultiPattern<Toy> multi = MultiPattern.of(
        MultiPattern.clause("s", Pattern.of(new Toy.Add(IntList.EMPTY), Pattern.var("x"),
            Pattern.var("y"))),
        MultiPattern.clause("p", Pattern.of(new Toy.Mul(IntList.EMPTY), Pattern.var("x"),
            Pattern.var("y"))));
    assertTrue(Matcher.search(g, multi, Integer.MAX_VALUE).isEmpty());
    g.add(new Toy.Mul(IntList.of(a, b)));
    g.rebuild();
    assertEquals(1, Matcher.search(g, multi, Integer.MAX_VALUE).size());
  }
}
