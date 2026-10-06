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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Multi-patterns: egg's six unit tests from {@code multipattern.rs}, with their match counts,
 * and what jegg adds to them: a limit, the order of matches, the refusal of a bare first
 * variable, and egg's counting of every multi-pattern match as applied.
 */
class MultiPatternTest {

  /** egg's {@code SymbolLang}: an operator name and children, nothing else. */
  record Sym(String op, IntList children) implements Language<Sym> {
    @Override
    public Sym withChildren(IntList c) {
      return new Sym(op, c);
    }
  }

  static final TreeBridge<Term, Sym> BRIDGE = new TreeBridge<>() {
    @Override
    public List<Term> childrenOf(Term t) {
      return t.kids();
    }

    @Override
    public Sym node(Term t, IntList children) {
      return new Sym(t.op(), children);
    }

    @Override
    public Term build(Sym node, List<Term> children) {
      return new Term(node.op(), children);
    }
  };

  static Pattern<Sym> pattern(String s) {
    return Term.pattern(s, BRIDGE);
  }

  /** egg's syntax: {@code ?x = p, ?y = q = r}, where {@code ?y = q = r} is two clauses. */
  static MultiPattern<Sym> multi(String s) {
    return MultiTerm.parse(s, BRIDGE);
  }

  static Rewrite<Sym, Void> rule(String name, String lhs, String rhs) {
    return Rewrite.multi(name, multi(lhs), multi(rhs));
  }

  private static int add(EGraph<Sym, Void> g, String term) {
    return g.addTree(Term.parse(term), BRIDGE);
  }

  private static EGraph<Sym, Void> graph() {
    return EGraph.withoutAnalysis();
  }

  private static int matches(EGraph<Sym, Void> g, String multipattern) {
    return Matcher.search(g, multi(multipattern), Integer.MAX_VALUE).size();
  }

  @Test
  void anUnboundRightHandVariableIsRefused() {
    // egg: multi_rewrite!("foo"; "?x = (foo ?y)" => "?x = ?z") panics with "unbound var ?z".
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> rule("foo", "?x = (foo ?y)", "?x = ?z"));
    assertTrue(e.getMessage().contains("?z"), e.getMessage());
  }

  @Test
  void aRightHandClauseMayBindAVariableForTheNext() {
    // egg's ok_unbound_var: ?z is new in the first clause and used in the second.
    rule("foo", "?x = (foo ?y)", "?z = (baz ?y), ?x = ?z");
    assertThrows(IllegalArgumentException.class,
        () -> rule("foo", "?x = (foo ?y)", "?x = ?z, ?z = (baz ?y)"));
  }

  @Test
  void multiPatternsJoinOnSharedVariablesAndClassVariables() {
    // egg's multi_patterns, with its counts: (f a b) and (f a c) are one class.
    EGraph<Sym, Void> g = graph();
    add(g, "(f a a)");
    int ab = add(g, "(f a b)");
    int ac = add(g, "(f a c)");
    g.merge(ab, ac);
    g.rebuild();

    assertEquals(1, matches(g, "?x = (f a a),   ?y = (f ?c b)"));
    assertEquals(1, matches(g, "?x = (f a a),   ?y = (f a b)"));
    assertEquals(1, matches(g, "?x = (f a a),   ?y = (f a a)"));
    assertEquals(9, matches(g, "?x = (f ?a ?b), ?y = (f ?c ?d)"));
    assertEquals(1, matches(g, "?x = (f ?a a),  ?y = (f ?a b)"));

    assertEquals(0, matches(g, "?x = (f a a), ?x = (f a c)"));
    assertEquals(1, matches(g, "?x = (f a b), ?x = (f a c)"));
  }

  @Test
  void aRightHandSideMayCreateWhatTheLeftNeedsNext() {
    // egg's unbound_rhs: rule1 creates y and z and unions them; rule2 needs both to exist.
    EGraph<Sym, Void> g = graph();
    add(g, "(x)");
    List<Rewrite<Sym, Void>> rules = List.of(
        rule("rule1", "?x = (x)", "?y = (y), ?y = (z)"),
        rule("rule2", "?x = (x), ?y = (y), ?z = (z)", "?y = (y), ?y = (z)"));
    RunReport report = Runner.of(g, rules).run();
    int y = add(g, "(y)");
    int z = add(g, "(z)");
    assertEquals(g.find(y), g.find(z));
    // rule2 fired too, once rule1 had made (y) and (z).
    assertTrue(report.iterations().stream().anyMatch(it -> it.matches().get("rule2") > 0),
        report.toString());
  }

  @Test
  void contextTransfer() {
    // egg's ctx_transfer: a tag known equal in one context is equal in a later one.
    EGraph<Sym, Void> g = graph();
    add(g, "(lte ctx1 ctx2)");
    add(g, "(lte ctx2 ctx2)");
    add(g, "(lte ctx1 ctx1)");
    int x2 = add(g, "(tag x ctx2)");
    int y2 = add(g, "(tag y ctx2)");
    int z2 = add(g, "(tag z ctx2)");
    int x1 = add(g, "(tag x ctx1)");
    int y1 = add(g, "(tag y ctx1)");
    int z1 = add(g, "(tag z ctx2)");
    g.merge(x1, y1);
    g.merge(y2, z2);
    g.rebuild();
    List<Rewrite<Sym, Void>> rules = List.of(rule("context-transfer",
        """
        ?x = (tag ?a ?ctx1) = (tag ?b ?ctx1),
        ?t = (lte ?ctx1 ?ctx2),
        ?a1 = (tag ?a ?ctx2),
        ?b1 = (tag ?b ?ctx2)""",
        "?a1 = ?b1"));
    Runner.of(g, rules).run();
    assertEquals(g.find(x1), g.find(y1));
    assertEquals(g.find(y2), g.find(z2));
    assertEquals(g.find(x2), g.find(y2));
    assertEquals(g.find(x2), g.find(z2));
    assertNotEquals(g.find(y1), g.find(z1));
    assertNotEquals(g.find(x1), g.find(z1));
    g.checkInvariants();
  }

  @Test
  void aBareVariableClauseRangesOverEveryClass() {
    // egg's bare_var: "?y = ?y" is a clause whose pattern is a variable; searching and applying
    // it does not fail.
    EGraph<Sym, Void> g = graph();
    add(g, "(f a)");
    g.rebuild();
    Rewrite<Sym, Void> r = rule("r", "?a = (f ?x), ?y = ?y", "?a = (g ?x ?y)");
    List<Matcher.Match> found = r.search(g);
    assertEquals(g.numClasses(), found.size());
    for (Matcher.Match m : found) {
      r.apply(g, m);
    }
    g.rebuild();
    g.checkInvariants();
  }

  @Test
  void aFirstClauseThatIsABareVariableIsRefusedAsALeftHandSide() {
    MultiPattern<Sym> bare = multi("?y = ?z, ?x = (f ?z)");
    EGraph<Sym, Void> g = graph();
    assertThrows(IllegalArgumentException.class,
        () -> Matcher.search(g, bare, Integer.MAX_VALUE));
    assertThrows(IllegalArgumentException.class,
        () -> Rewrite.multi("bare", bare, multi("?x = (g ?z)")));
    assertThrows(IllegalArgumentException.class, () -> new MultiPattern<Sym>(List.of()));
  }

  @Test
  void aLimitedSearchIsAPrefixOfTheFullOneInClassOrder() {
    EGraph<Sym, Void> g = graph();
    add(g, "(f a a)");
    int ab = add(g, "(f a b)");
    int ac = add(g, "(f a c)");
    g.merge(ab, ac);
    g.rebuild();
    MultiPattern<Sym> all = multi("?x = (f ?a ?b), ?y = (f ?c ?d)");
    List<Matcher.Match> full = Matcher.search(g, all, Integer.MAX_VALUE);
    for (int limit = 1; limit <= full.size(); limit++) {
      assertEquals(full.subList(0, limit), Matcher.search(g, all, limit), "limit " + limit);
    }
    assertThrows(IllegalArgumentException.class, () -> Matcher.search(g, all, 0));
    // The matches come in the class order of the first clause, each carrying that class.
    int previous = -1;
    for (Matcher.Match m : full) {
      assertTrue(m.eclass() >= previous);
      assertEquals(m.eclass(), m.subst().idOf("x"));
      previous = m.eclass();
    }
    assertEquals(9, new java.util.HashSet<>(full).size(), "the substitutions are distinct");
  }

  @Test
  void theVariablesAreTheClauseVariablesThenThePatternsInOrder() {
    MultiPattern<Sym> m = multi("?x = (tag ?a ?c), ?t = (lte ?c ?d)");
    assertEquals(List.of("x", "a", "c", "t", "d"), List.copyOf(m.subtermVariables()));
    assertTrue(m.payloadVariables().isPresent());
    assertEquals(Set.of(), m.payloadVariables().get());
  }

  @Test
  void aMultiPatternRuleCountsEveryMatchAsAppliedSoARunNeverSaturates() {
    // egg's apply_matches returns an id per substitution whether or not a union changed
    // anything, so its runner never calls such a run saturated; a pattern rule that changes
    // nothing does let the run saturate. The two rules do the same: nothing.
    EGraph<Sym, Void> multi = graph();
    add(multi, "(x)");
    RunReport viaMulti = Runner.of(multi, List.of(rule("noop", "?x = (x)", "?x = (x)"))).run();
    assertEquals(new StopReason.IterationLimit(30), viaMulti.stop());

    EGraph<Sym, Void> plain = graph();
    add(plain, "(x)");
    RunReport viaPattern = Runner.of(plain,
        List.of(Rewrite.<Sym, Void>of("noop", pattern("(x)"), pattern("(x)")))).run();
    assertEquals(new StopReason.Saturated(), viaPattern.stop());
    assertEquals(multi.numNodes(), plain.numNodes());
    assertEquals(multi.numClasses(), plain.numClasses());
  }

  @Test
  void aMultiPatternApplierRefusesApplyAndCountsEachMatchThroughApplyTo() {
    // apply would hide its unions and its count from whoever called it, so it is refused, as
    // egg's apply_one panics; applyTo reports the unions and counts the match once.
    EGraph<Sym, Void> g = graph();
    int x = add(g, "(x)");
    g.rebuild();
    Applier<Sym, Void> applier = Applier.multi(multi("?y = (y), ?y = (z)"));
    assertThrows(UnsupportedOperationException.class, () -> applier.apply(g, x, Subst.EMPTY));
    Matcher.Match match = new Matcher.Match(x, Subst.EMPTY.bind("x", x));
    assertEquals(new Applied(1, 1), applier.applyTo(g, match));
    assertEquals(new Applied(0, 1), applier.applyTo(g, match));
    // A lambda that forwards to it through apply fails loudly, not with a wrong count.
    Rewrite<Sym, Void> forwarding = Rewrite.dynamic("forwarding", multi("?x = (x)"),
        (graph, eclass, subst) -> applier.apply(graph, eclass, subst));
    assertThrows(UnsupportedOperationException.class,
        () -> forwarding.apply(g, forwarding.search(g).get(0)));
  }

  @Test
  void aPatternVariableNamedLikeTheClauseVariableIsAJoin() {
    // ?x = (f ?x) matches a class only if the class holds a node over itself: the clause
    // variable must not overwrite the pattern's binding of ?x.
    EGraph<Sym, Void> g = graph();
    int fa = add(g, "(f a)");
    g.rebuild();
    MultiPattern<Sym> self = multi("?x = (f ?x)");
    assertEquals(0, Matcher.search(g, self, Integer.MAX_VALUE).size());
    g.merge(fa, add(g, "a"));
    g.rebuild();
    List<Matcher.Match> found = Matcher.search(g, self, Integer.MAX_VALUE);
    assertEquals(1, found.size());
    assertEquals(g.find(fa), found.get(0).subst().idOf("x"));
  }

  @Test
  void twoPartialMatchesThatMeetInOneSubstitutionAreOneMatch() {
    // A head that binds the payload variable p for Div(true, ..) and nothing for Div(false, ..),
    // over a class holding both: the first clause yields {u, x, y, p} and {u, x, y}, and the
    // second extends both to {u, v, x, y, p}. It is one match, so there are two, not three.
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
    MultiPattern<Toy> twice = MultiPattern.of(
        MultiPattern.clause("u", Pattern.node(some, Pattern.var("x"), Pattern.var("y"))),
        MultiPattern.clause("v", Pattern.node(some, Pattern.var("x"), Pattern.var("y"))));
    List<Matcher.Match> found = Matcher.search(g, twice, Integer.MAX_VALUE);
    assertEquals(2, found.size(), found.toString());
    assertEquals(2, new java.util.HashSet<>(found).size());
    assertEquals(found.subList(0, 2), Matcher.search(g, twice, 2));
  }

  @Test
  void aGroundClauseIsLookedUpSoAPrunedNodeStillMatches() {
    // egg compiles a ground clause to a lookup in its memo, which keeps a node pruning dropped:
    // (g a) is merged into b's class and pruned from it, and still matches, as in egg 73975c9.
    EGraph<Sym, Void> g = graph();
    add(g, "a");
    int ga = add(g, "(g a)");
    int b = add(g, "b");
    add(g, "(f a)");
    g.merge(ga, b);
    g.rebuild();
    g.retainNodes(g.find(b), node -> node.children().isEmpty());
    g.rebuild();
    assertTrue(g.lookupTree(Term.parse("(g a)"), BRIDGE).isPresent());
    assertEquals(1, matches(g, "?y = (g a)"));
    assertEquals(1, matches(g, "?x = (f ?a), ?y = (g ?a)"));
    assertEquals(0, matches(g, "?x = (f ?a), ?y = (g ?a), ?y = (f ?a)"));
  }

  @Test
  void aClauseWhosePatternIsABoundVariableNamesOneClass() {
    EGraph<Sym, Void> g = graph();
    for (int i = 0; i < 20; i++) {
      add(g, "(p a" + i + ")");
    }
    g.rebuild();
    List<Matcher.Match> byPattern = Matcher.search(g, multi("?x = (p ?a), ?y = ?a"),
        Integer.MAX_VALUE);
    List<Matcher.Match> byVariable = Matcher.search(g, multi("?x = (p ?a), ?a = ?y"),
        Integer.MAX_VALUE);
    assertEquals(20, byPattern.size());
    assertEquals(byVariable.size(), byPattern.size());
    for (Matcher.Match m : byPattern) {
      assertEquals(m.subst().idOf("a"), m.subst().idOf("y"));
    }
  }

  @Test
  void theReportShowsWhatIsCountedWhereItIsNotTheUnions() {
    EGraph<Sym, Void> g = graph();
    add(g, "(x)");
    RunReport report = Runner.of(g, List.of(rule("noop", "?x = (x)", "?x = (x)"))).run();
    RunReport.Iteration first = report.iterations().get(0);
    assertEquals(0, first.unions());
    assertEquals(1, first.counted());
    assertTrue(report.toString().contains("0 unions, 1 counted"), report.toString());
  }

  @Test
  void theMeasurementHarnessStopsAsTheRunnerDoes() {
    // Saturation's copy of the loop must read the same count: a no-op multi-pattern rule runs to
    // the iteration limit in both, where a no-op pattern rule saturates in both.
    EGraph<Sym, Void> viaHarness = graph();
    add(viaHarness, "(x)");
    Saturation.Outcome multi = Saturation.run(Saturation.Mode.DEFERRED, viaHarness,
        List.of(rule("noop", "?x = (x)", "?x = (x)")), RunLimits.DEFAULT,
        new BackoffScheduler<>(), graph -> false, Long.MAX_VALUE);
    assertEquals("IterationLimit", multi.stop());
    assertEquals(30, multi.iterations());
    EGraph<Sym, Void> plain = graph();
    add(plain, "(x)");
    Saturation.Outcome pattern = Saturation.run(Saturation.Mode.DEFERRED, plain,
        List.of(Rewrite.<Sym, Void>of("noop", pattern("(x)"), pattern("(x)"))),
        RunLimits.DEFAULT, new BackoffScheduler<>(), graph -> false, Long.MAX_VALUE);
    assertEquals("Saturated", pattern.stop());
  }
}
