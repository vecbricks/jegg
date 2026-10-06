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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vecbricks.jegg.EGraphPropertyTest.Op;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Corner cases borrowed from the other implementations' suites and from their bug lists (#75):
 * hegg's reverse congruence and its pattern variable named like an index, Metatheory.jl's nested
 * congruence, egg's nonlinear patterns, ego's ground leaf reached through two nodes, egglog's
 * merge that changes nothing, its literal pattern against another value and its extraction of a
 * term that was never added. Each expected value is worked out by hand or is egg's own.
 */
class CornerCaseTest {

  private static Op leaf(String name) {
    return new Op(name, IntList.EMPTY);
  }

  private static Op node(String name, int... kids) {
    return new Op(name, IntList.of(kids));
  }

  private static EGraph<Op, Void> plain() {
    return EGraph.withoutAnalysis();
  }

  // ---- congruence

  @Test
  void aCongruenceDoesNotRunBackwards() {
    // hegg's T3, whose assertion is commented out there: f(a, c) = f(b, c) says nothing of a
    // and b.
    EGraph<Op, Void> g = plain();
    int a = g.add(leaf("x"));
    int b = g.add(leaf("y"));
    int c = g.add(leaf("z"));
    int fac = g.add(node("and", a, c));
    int fbc = g.add(node("and", b, c));
    g.merge(fac, fbc);
    g.rebuild();
    g.checkInvariants();
    assertEquals(g.find(fac), g.find(fbc));
    assertNotEquals(g.find(a), g.find(b));
    assertNotEquals(g.find(a), g.find(c));
    assertNotEquals(g.find(b), g.find(c));
  }

  private static int power(EGraph<Op, Void> g, int base, int times) {
    int id = base;
    for (int i = 0; i < times; i++) {
      id = g.add(node("not", id));
    }
    return id;
  }

  private void nestedCongruence(int m, int n, int gcd) {
    // Metatheory.jl: f^m(a) = a and f^n(a) = a give f^gcd(m,n)(a) = a, and nothing shorter:
    // cycles of two lengths in one class, found only by following congruence round the cycle.
    EGraph<Op, Void> g = plain();
    int a = g.add(leaf("x"));
    int[] chain = new int[Math.max(m, n) + 1];
    chain[0] = a;
    for (int i = 1; i < chain.length; i++) {
      chain[i] = g.add(node("not", chain[i - 1]));
    }
    g.merge(chain[m], a);
    g.merge(chain[n], a);
    g.rebuild();
    g.checkInvariants();
    for (int k = 0; k < chain.length; k++) {
      assertEquals(k % gcd == 0, g.find(chain[k]) == g.find(a), "f^" + k + " of " + m + ", " + n);
    }
    assertEquals(gcd, g.numClasses());
  }

  @Test
  void twoCyclesInOneClassGiveTheGcdOfTheirLengths() {
    nestedCongruence(6, 4, 2);
    nestedCongruence(9, 6, 3);
    nestedCongruence(7, 5, 1);
    nestedCongruence(8, 8, 8);
  }

  @Test
  void aNodeMergedWithItsOwnChildStillHashconsAndMatches() {
    // and(e, T) = e: a class holding a node over itself, the shape a repair has to take care
    // with. The node is found by lookup, matched once, and the graph stays valid.
    EGraph<Op, Void> g = plain();
    int e = g.add(leaf("x"));
    int t = g.add(leaf("T"));
    int both = g.add(node("and", e, t));
    g.merge(both, e);
    g.rebuild();
    g.checkInvariants();
    assertEquals(g.find(e), g.find(both));
    assertEquals(g.find(e), g.find(g.add(node("and", e, t))));
    assertEquals(1, Matcher.search(g, Pattern.of(node("and"), Pattern.var("a"),
        Pattern.var("b"))).size());
  }

  // ---- facts grown on one side

  private static EGraph<Op, Boolean> truthGraph() {
    return new EGraph<>(EGraphPropertyTest.truth(false));
  }

  @Test
  void theParentsOfTheSideWhoseFactGrewAreRemadeWhicheverSideThatIs() {
    // Two classes, one known true: whichever way they are merged, the parents of the one that
    // was not known are re-made, and the parents of the one that was are left as they were
    // (ego reports the change per side). Outside a repair.
    for (boolean trueFirst : new boolean[] {true, false}) {
      EGraph<Op, Boolean> g = truthGraph();
      int known = g.add(leaf("T"));
      int unknown = g.add(leaf("x"));
      int parentOfUnknown = g.add(node("or", unknown));
      int parentOfKnown = g.add(node("not", known));
      assertEquals(false, g.data(parentOfUnknown));
      if (trueFirst) {
        g.merge(known, unknown);
      } else {
        g.merge(unknown, known);
      }
      g.rebuild();
      g.checkInvariants();
      g.checkAnalysisInvariant();
      assertEquals(true, g.data(parentOfUnknown), "trueFirst " + trueFirst);
      assertEquals(false, g.data(parentOfKnown));
      assertEquals(g.find(g.add(leaf("T"))), g.find(parentOfUnknown), "modify merged it with T");
    }
  }

  @Test
  void aFactGrownInsideARepairReachesTheParentsWhicheverOrderTheMergeIsMadeIn() {
    // EGraphRebuildTest's case with the analysis of this file: two nodes and(e, T) and
    // and(e2, T) become congruent when e and e2 merge; one is in e's class, the other in T's.
    for (boolean eFirst : new boolean[] {true, false}) {
      EGraph<Op, Boolean> g = truthGraph();
      int t = g.add(leaf("T"));
      int e = g.add(leaf("x"));
      int e2 = g.add(leaf("y"));
      g.merge(g.add(node("and", e, t)), e);
      g.merge(g.add(node("and", e2, t)), t);
      int q = g.add(node("or", e, e2));
      g.rebuild();
      g.checkAnalysisInvariant();
      assertEquals(false, g.data(q));
      if (eFirst) {
        g.merge(e, e2);
      } else {
        g.merge(e2, e);
      }
      g.rebuild();
      g.checkInvariants();
      g.checkAnalysisInvariant();
      assertEquals(true, g.data(q), "eFirst " + eFirst);
    }
  }

  // ---- a merge that changes nothing

  @Test
  void mergingWhatIsOneClassAlreadyChangesNothing() {
    EGraph<Op, Void> g = plain();
    int a = g.add(leaf("x"));
    int b = g.add(leaf("y"));
    g.merge(a, b);
    g.rebuild();
    long changes = g.changes();
    int root = g.merge(a, b);
    assertEquals(g.find(a), root);
    assertEquals(changes, g.changes());
    assertEquals(root, g.merge(b, b));
    assertFalse(g.isDirty());
    assertEquals(0, g.rebuild());
    assertEquals(changes, g.changes());
  }

  @Test
  void aRuleThatOnlyRestatesWhatIsKnownSaturatesAtTheFirstIteration() {
    // egglog's merge-saturates.egg: a rule whose union changes nothing is no progress.
    EGraph<Op, Void> g = plain();
    int a = g.add(leaf("x"));
    g.add(node("not", a));
    Rewrite<Op, Void> same = Rewrite.of("same", Pattern.<Op>var("p"), Pattern.<Op>var("p"));
    RunReport report = Runner.of(g, List.of(same)).run();
    assertEquals(new StopReason.Saturated(), report.stop(), report.toString());
    assertEquals(1, report.size());
    assertEquals(0, report.iterations().get(0).unions());
  }

  // ---- patterns

  @Test
  void eggsNonlinearPatternsCountWhatEggCounts() {
    // egg's nonlinear_patterns, with its counts.
    EGraph<MultiPatternTest.Sym, Void> g = EGraph.withoutAnalysis();
    for (String t : List.of("(f a a)", "(f a (g a))", "(f a (g b))", "(h (foo a b) 0 1)",
        "(h (foo a b) 1 0)", "(h (foo a b) 0 0)")) {
      g.addTree(Term.parse(t), MultiPatternTest.BRIDGE);
    }
    g.rebuild();
    java.util.function.ToIntFunction<String> matches = p -> Matcher.search(g,
        MultiPatternTest.pattern(p)).size();
    assertEquals(3, matches.applyAsInt("(f ?x ?y)"));
    assertEquals(1, matches.applyAsInt("(f ?x ?x)"));
    assertEquals(2, matches.applyAsInt("(f ?x (g ?y))"));
    assertEquals(1, matches.applyAsInt("(f ?x (g ?x))"));
    assertEquals(1, matches.applyAsInt("(h ?x 0 0)"));
  }

  @Test
  void aGroundLeafInAClassReachedThroughTwoNodesGivesOneMatchPerNode() {
    // ego's `check matches after merging`: (x & z) and (y & z) merged, the pattern ?a & z.
    EGraph<MultiPatternTest.Sym, Void> g = EGraph.withoutAnalysis();
    int xz = g.addTree(Term.parse("(& x z)"), MultiPatternTest.BRIDGE);
    int yz = g.addTree(Term.parse("(& y z)"), MultiPatternTest.BRIDGE);
    g.merge(xz, yz);
    g.rebuild();
    List<Matcher.Match> found = Matcher.search(g, MultiPatternTest.pattern("(& ?a z)"));
    assertEquals(2, found.size(), found.toString());
    assertEquals(1, new HashSet<>(found.stream().map(Matcher.Match::eclass).toList()).size());
    Set<Integer> bound = new HashSet<>();
    found.forEach(m -> bound.add(m.subst().idOf("a")));
    assertEquals(2, bound.size(), "the two nodes bind two different ?a");
  }

  @Test
  void aBareVariablePatternMatchesExactlyEveryLiveClass() {
    // hegg's "singleton variable matches all", over the random graphs of the properties.
    for (long seed = 1; seed <= 100; seed++) {
      EGraphPropertyTest.Outcome<Void> o = EGraphPropertyTest.run(EGraphPropertyTest.program(seed),
          EGraphPropertyTest.Mode.AT_THE_END, EGraph::withoutAnalysis, false);
      Set<Integer> matched = new HashSet<>();
      List<Matcher.Match> found = Matcher.search(o.graph(), Pattern.var("x"));
      found.forEach(m -> matched.add(m.eclass()));
      Set<Integer> live = new HashSet<>();
      o.graph().classes().forEach(c -> live.add(c.id()));
      assertEquals(live, matched, "seed " + seed);
      assertEquals(o.graph().numClasses(), found.size(), "one match per class, seed " + seed);
    }
  }

  @Test
  void variablesMayBeNamedLikeNumbers() {
    // hegg's T32: variables "1" and "2" are names, not indices.
    EGraph<Op, Void> g = plain();
    int a = g.add(leaf("x"));
    int b = g.add(leaf("y"));
    g.add(node("and", a, b));
    List<Matcher.Match> found = Matcher.search(g, Pattern.of(node("and"), Pattern.var("1"),
        Pattern.var("2")));
    assertEquals(1, found.size());
    assertEquals(a, found.get(0).subst().idOf("1"));
    assertEquals(b, found.get(0).subst().idOf("2"));
  }

  @Test
  void aLeafPatternMatchesOnlyTheSameLeafAndAnOperatorOnlyItsArity() {
    // egglog #343: a literal argument against another value must not match; and a pattern of
    // one child does not match a node of two.
    EGraph<Op, Void> g = plain();
    int a = g.add(leaf("x"));
    g.add(node("and", a, g.add(leaf("y"))));
    assertEquals(1, Matcher.search(g, Pattern.of(leaf("x"))).size());
    assertEquals(0, Matcher.search(g, Pattern.of(leaf("z"))).size());
    assertEquals(0, Matcher.search(g, Pattern.of(node("and"), Pattern.var("a"))).size());
    assertEquals(0, Matcher.search(g, Pattern.of(node("and"), Pattern.var("a"), Pattern.var("b"),
        Pattern.var("c"))).size());
    assertEquals(1, Matcher.search(g, Pattern.of(node("and"), Pattern.var("a"),
        Pattern.var("b"))).size());
  }

  // ---- extraction

  @Test
  void anExtractorRefusesAnIdTheGraphNeverIssued() {
    // egglog #629: extracting a term that was never added panicked.
    EGraph<Op, Void> g = plain();
    g.add(leaf("x"));
    g.rebuild();
    Extractor<Op, Void> ex = new Extractor<>(g, CostFunction.astSize());
    assertThrows(IllegalArgumentException.class, () -> ex.best(99));
    assertThrows(IllegalArgumentException.class, () -> ex.extract(99));
    assertThrows(IllegalArgumentException.class, () -> ex.extract(-1));
    assertTrue(ex.best(0).cost() > 0);
  }
}
