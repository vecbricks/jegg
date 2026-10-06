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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The matcher deduplicates a class's substitutions with a set only where one can come up twice:
 * a pattern with a head that has no key, or a dirty graph. For a pattern whose heads all have
 * keys, on a rebuilt graph, the substitutions are distinct by construction
 * ({@code Matcher.distinctByConstruction}); these tests are that claim's check, on random
 * graphs, and the two cases that keep the set.
 */
class DistinctMatchesTest {

  private static Pattern<EGraphPropertyTest.Op> op(String name,
      List<Pattern<EGraphPropertyTest.Op>> children) {
    return new Pattern.Node<>(Pattern.head(new EGraphPropertyTest.Op(name, IntList.EMPTY)),
        children);
  }

  private static final Pattern<EGraphPropertyTest.Op> X = Pattern.var("x");
  private static final Pattern<EGraphPropertyTest.Op> Y = Pattern.var("y");
  private static final Pattern<EGraphPropertyTest.Op> T = op("T", List.of());

  /**
   * Patterns with repeated variables, nested nodes and a leaf, which are the shapes that make a
   * walk reach one substitution by two routes if anything does.
   */
  private static final List<Pattern<EGraphPropertyTest.Op>> PATTERNS = List.of(
      op("and", List.of(X, Y)),
      op("and", List.of(X, X)),
      op("or", List.of(X, op("not", List.of(X)))),
      op("and", List.of(T, X)),
      op("not", List.of(op("not", List.of(X)))),
      op("and", List.of(X, op("or", List.of(Y, X)))),
      op("or", List.of(op("and", List.of(X, Y)), op("and", List.of(Y, X)))),
      op("and", List.of(op("or", List.of(X, T)), op("not", List.of(Y)))));

  @Test
  void aKeyedPatternYieldsEachSubstitutionOnceOnRandomRebuiltGraphs() {
    int searches = 0;
    int matches = 0;
    for (long seed = 1; seed <= 3000; seed++) {
      long which = seed;
      List<EGraphPropertyTest.Step> steps = EGraphPropertyTest.program(seed);
      // Without an analysis, and with the truth analysis, whose modify merges every class
      // proved true into one, the big class a walk meets most routes in.
      List<EGraph<EGraphPropertyTest.Op, ?>> graphs = List.of(
          EGraphPropertyTest.run(steps, EGraphPropertyTest.Mode.AT_THE_END,
              EGraph::<EGraphPropertyTest.Op>withoutAnalysis, false).graph(),
          EGraphPropertyTest.run(steps, EGraphPropertyTest.Mode.WHERE_THE_PROGRAM_SAYS,
              () -> new EGraph<>(EGraphPropertyTest.truth(false)), true).graph());
      for (EGraph<EGraphPropertyTest.Op, ?> g : graphs) {
        assertTrue(!g.isDirty());
        for (Pattern<EGraphPropertyTest.Op> pattern : PATTERNS) {
          List<Matcher.Match> found = Matcher.search(g, pattern);
          Set<Matcher.Match> distinct = new HashSet<>(found);
          assertEquals(distinct.size(), found.size(),
              () -> "seed " + which + ", " + pattern + ": a substitution came up twice in "
                  + found);
          // The same through matchIn, class by class, which decides per call.
          List<Matcher.Match> byClass = new ArrayList<>();
          for (EClass<EGraphPropertyTest.Op, ?> c : g.classes()) {
            for (Subst s : Matcher.matchIn(g, pattern, c.id(), Subst.EMPTY)) {
              byClass.add(new Matcher.Match(c.id(), s));
            }
          }
          assertEquals(found, byClass);
          searches++;
          matches += found.size();
        }
      }
    }
    assertTrue(matches > 20_000, "the graphs must give the walk something to find: " + matches
        + " matches in " + searches + " searches");
  }

  /**
   * The same claim on the graphs the fuzzing builds, where the sets were made most: egg's prop
   * and math rule sets, all of whose heads have keys, over random terms, every rule searched
   * before every iteration on the rebuilt graph.
   */
  @Test
  void theFuzzTermsRuleSetsYieldEachMatchOnce() {
    int[] checked = {0};
    List<Rewrite<PropRulesTest.Prop, Boolean>> prop = List.of(PropRulesTest.DEF_IMPLY,
        PropRulesTest.DEF_IMPLY_FLIP, PropRulesTest.DOUBLE_NEG, PropRulesTest.DOUBLE_NEG_FLIP,
        PropRulesTest.ASSOC_OR, PropRulesTest.DIST_AND_OR, PropRulesTest.DIST_OR_AND,
        PropRulesTest.COMM_OR, PropRulesTest.COMM_AND, PropRulesTest.LEM, PropRulesTest.OR_TRUE,
        PropRulesTest.AND_TRUE, PropRulesTest.CONTRAPOSITIVE, PropRulesTest.LEM_IMPLY);
    for (String term : new TermGenerator("prop", 2).terms(12)) {
      EGraph<PropRulesTest.Prop, Boolean> g = new EGraph<>(PropRulesTest.CONSTANT_FOLD);
      try {
        int root = g.addTree(Term.parse(term), PropRulesTest.BRIDGE);
        g.merge(root, g.add(new PropRulesTest.Prop.Bool(true)));
        g.rebuild();
        new Runner<>(g, prop, PropRulesTest.LIMITS, new BackoffScheduler<>())
            .withHook(graph -> distinctMatches(graph, prop, checked)).run();
      } catch (IllegalStateException e) {
        // A term that folds to false contradicts the assumption, as in the fuzzing.
        assertTrue(e.getMessage().startsWith("Merged non-equal constants"), e.getMessage());
      }
    }
    List<Rewrite<MathTest.Math, Double>> math = MathTest.rules();
    for (String term : new TermGenerator("math", 2).terms(8)) {
      EGraph<MathTest.Math, Double> g = new EGraph<>(MathTest.CONSTANT_FOLD);
      try {
        g.addTree(Term.parse(term), MathTest.BRIDGE);
        g.rebuild();
        new Runner<>(g, math, RunLimits.DEFAULT.withIterations(8), new BackoffScheduler<>())
            .withHook(graph -> distinctMatches(graph, math, checked)).run();
      } catch (IllegalStateException e) {
        assertTrue(e.getMessage().startsWith("Merged non-equal constants"), e.getMessage());
      }
    }
    assertTrue(checked[0] > 100_000, checked[0] + " matches checked");
  }

  private static <L extends Language<L>, D> java.util.Optional<String> distinctMatches(
      EGraph<L, D> graph, List<Rewrite<L, D>> rules, int[] checked) {
    for (Rewrite<L, D> rule : rules) {
      List<Matcher.Match> found = rule.search(graph);
      assertEquals(new HashSet<>(found).size(), found.size(),
          () -> rule + ": a match came up twice");
      checked[0] += found.size();
    }
    return java.util.Optional.empty();
  }

  @Test
  void aDirtyGraphKeepsTheSetSinceAMergedClassMayListOneNodeTwice() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int x = g.add(new Toy.Var("x"));
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int ax = g.add(new Toy.Add(IntList.of(a, x)));
    int bx = g.add(new Toy.Add(IntList.of(b, x)));
    // a = b makes the two sums congruent; merging them before the rebuild leaves the class
    // listing both forms, which are one node in canonical terms.
    g.merge(a, b);
    int sum = g.merge(ax, bx);
    assertTrue(g.isDirty());
    assertEquals(2, g.classOf(sum).nodes().size());
    Pattern<Toy> pattern = Pattern.of(new Toy.Add(IntList.EMPTY), Pattern.var("p"),
        Pattern.var("q"));
    List<Matcher.Match> found = Matcher.search(g, pattern);
    assertEquals(List.of(new Matcher.Match(g.find(sum),
        Subst.EMPTY.bind("p", g.find(a)).bind("q", x))), found);
    assertEquals(1, Matcher.matchIn(g, pattern, sum, Subst.EMPTY).size());
    // Rebuilt, the list holds the node once and the set is not needed; the matches are the same.
    g.rebuild();
    g.checkInvariants();
    assertEquals(1, g.classOf(sum).nodes().size());
    assertEquals(found, Matcher.search(g, pattern));
  }

  @Test
  void aHeadWithoutAKeyKeepsTheSetOnARebuiltGraph() {
    // A head that matches any Div and binds nothing: two Div nodes in one class differing only
    // in their flag give one substitution, which the set keeps once (PatternMatchTest has the
    // same case with a limit); here the point is that the pattern is not all keyed.
    Pattern.Head<Toy> anyDiv = new Pattern.Head<>() {
      @Override
      public Subst match(Toy node, Subst subst) {
        return node instanceof Toy.Div ? subst : null;
      }

      @Override
      public Toy build(Subst subst, IntList children) {
        return new Toy.Div(true, children);
      }
    };
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int d = g.add(new Toy.Div(true, IntList.of(a, b)));
    g.merge(d, g.add(new Toy.Div(false, IntList.of(a, b))));
    g.rebuild();
    // The keyless head at the root, and nested under a keyed one.
    Pattern<Toy> root = Pattern.node(anyDiv, Pattern.var("p"), Pattern.var("q"));
    assertEquals(List.of(new Matcher.Match(g.find(d), Subst.EMPTY.bind("p", a).bind("q", b))),
        Matcher.search(g, root));
    int outer = g.add(new Toy.Add(IntList.of(d, d)));
    Pattern<Toy> nested = Pattern.of(new Toy.Add(IntList.EMPTY), root, root);
    assertEquals(List.of(new Matcher.Match(outer, Subst.EMPTY.bind("p", a).bind("q", b))),
        Matcher.search(g, nested));
    g.checkInvariants();
  }
}
