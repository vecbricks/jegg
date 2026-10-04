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

/**
 * egg's {@code tests/prop.rs}, ported: propositional formulas over symbols with {@code &},
 * {@code |}, {@code ~} and {@code ->}, and the proofs egg's suite runs, each a start term, a
 * rule set and goals that must land in the start's class.
 */
class PropRulesTest {

  sealed interface Prop extends Language<Prop> permits Prop.Sym, Prop.And, Prop.Or, Prop.Not,
      Prop.Implies {
    record Sym(String name) implements Prop {
      @Override
      public IntList children() {
        return IntList.EMPTY;
      }

      @Override
      public Prop withChildren(IntList c) {
        return this;
      }
    }

    record And(IntList children) implements Prop {
      @Override
      public Prop withChildren(IntList c) {
        return new And(c);
      }
    }

    record Or(IntList children) implements Prop {
      @Override
      public Prop withChildren(IntList c) {
        return new Or(c);
      }
    }

    record Not(IntList children) implements Prop {
      @Override
      public Prop withChildren(IntList c) {
        return new Not(c);
      }
    }

    record Implies(IntList children) implements Prop {
      @Override
      public Prop withChildren(IntList c) {
        return new Implies(c);
      }
    }
  }

  static final TreeBridge<Term, Prop> BRIDGE = new TreeBridge<>() {
    @Override
    public List<Term> childrenOf(Term t) {
      return t.kids();
    }

    @Override
    public Prop node(Term t, IntList children) {
      return switch (t.op()) {
        case "&" -> new Prop.And(children);
        case "|" -> new Prop.Or(children);
        case "~" -> new Prop.Not(children);
        case "->" -> new Prop.Implies(children);
        default -> new Prop.Sym(t.op());
      };
    }

    @Override
    public Term build(Prop node, List<Term> children) {
      return switch (node) {
        case Prop.Sym s -> new Term(s.name(), List.of());
        case Prop.And a -> new Term("&", children);
        case Prop.Or o -> new Term("|", children);
        case Prop.Not n -> new Term("~", children);
        case Prop.Implies im -> new Term("->", children);
      };
    }
  };

  static Pattern<Prop> pattern(String s) {
    return Term.pattern(s, BRIDGE);
  }

  static Rewrite<Prop, Void> rule(String name, String lhs, String rhs) {
    return Rewrite.of(name, pattern(lhs), pattern(rhs));
  }

  // egg's rule set, by its names.
  static final Rewrite<Prop, Void> DEF_IMPLY = rule("def_imply", "(-> ?a ?b)", "(| (~ ?a) ?b)");
  static final Rewrite<Prop, Void> DEF_IMPLY_FLIP =
      rule("def_imply_flip", "(| (~ ?a) ?b)", "(-> ?a ?b)");
  static final Rewrite<Prop, Void> DOUBLE_NEG = rule("double_neg", "(~ (~ ?a))", "?a");
  static final Rewrite<Prop, Void> DOUBLE_NEG_FLIP = rule("double_neg_flip", "?a", "(~ (~ ?a))");
  static final Rewrite<Prop, Void> ASSOC_OR =
      rule("assoc_or", "(| ?a (| ?b ?c))", "(| (| ?a ?b) ?c)");
  static final Rewrite<Prop, Void> DIST_AND_OR =
      rule("dist_and_or", "(& ?a (| ?b ?c))", "(| (& ?a ?b) (& ?a ?c))");
  static final Rewrite<Prop, Void> DIST_OR_AND =
      rule("dist_or_and", "(| ?a (& ?b ?c))", "(& (| ?a ?b) (| ?a ?c))");
  static final Rewrite<Prop, Void> COMM_OR = rule("comm_or", "(| ?a ?b)", "(| ?b ?a)");
  static final Rewrite<Prop, Void> COMM_AND = rule("comm_and", "(& ?a ?b)", "(& ?b ?a)");
  static final Rewrite<Prop, Void> LEM_IMPLY =
      rule("lem_imply", "(& (| ?a ?b) (| (~ ?a) ?c))", "(| ?b ?c)");
  static final Rewrite<Prop, Void> CONTRAPOSITIVE =
      rule("contrapositive", "(-> ?a ?b)", "(-> (~ ?b) (~ ?a))");

  /** egg's limits for this suite. */
  static final RunLimits LIMITS = RunLimits.DEFAULT.withIterations(20).withNodes(5_000);

  /**
   * One of egg's `prove_something` tests: its rules, start term and goals, every goal to be in
   * the start's class after the run. The cases are data so the measurement harness (PLAN.md 6)
   * can run the same suite.
   */
  record Case(String name, List<Rewrite<Prop, Void>> rules, String start, List<String> goals) {
  }

  static final Case CONTRAPOSITIVE_CASE = new Case("prove_contrapositive",
      List.of(DEF_IMPLY, DEF_IMPLY_FLIP, DOUBLE_NEG_FLIP, COMM_OR), "(-> x y)",
      List.of("(-> x y)", "(| (~ x) y)", "(| (~ x) (~ (~ y)))", "(| (~ (~ y)) (~ x))",
          "(-> (~ y) (~ x))"));

  static final Case CHAIN_CASE = new Case("prove_chain",
      List.of(DEF_IMPLY, DEF_IMPLY_FLIP, DOUBLE_NEG_FLIP, COMM_OR, COMM_AND, LEM_IMPLY),
      "(& (-> x y) (-> y z))",
      List.of("(& (-> x y) (-> y z))", "(& (| (~ x) y) (| (~ y) z))", "(| (~ x) z)",
          "(-> x z)"));

  /** Distribution and association: (x | y) & (x | z) reaches x | (y & z). */
  static final Case FOLD_CASE = new Case("prove_fold",
      List.of(DIST_OR_AND, DIST_AND_OR, COMM_OR, COMM_AND, ASSOC_OR, DOUBLE_NEG, CONTRAPOSITIVE),
      "(& (| x y) (| x z))", List.of("(| x (& y z))"));

  static final List<Case> CASES = List.of(CONTRAPOSITIVE_CASE, CHAIN_CASE, FOLD_CASE);

  /** egg's `prove_something`: every goal must be in the start's class after the run. */
  private static void prove(Case c) {
    EGraph<Prop, Void> g = EGraph.withoutAnalysis();
    int root = g.addTree(Term.parse(c.start()), BRIDGE);
    RunReport report = new Runner<>(g, c.rules(), LIMITS, new BackoffScheduler<>()).run();
    for (String goal : c.goals()) {
      int id = g.lookupTree(Term.parse(goal), BRIDGE);
      assertTrue(id >= 0 && g.find(id) == g.find(root),
          goal + " is not in the class of " + c.start() + "\n" + report);
    }
    g.checkInvariants();
  }

  @Test
  void proveContrapositive() {
    prove(CONTRAPOSITIVE_CASE);
  }

  @Test
  void proveChain() {
    prove(CHAIN_CASE);
  }

  @Test
  void proveFoldWithTheOtherRules() {
    prove(FOLD_CASE);
  }

  @Test
  void aTermParsesAndBridgesBothWays() {
    Term t = Term.parse("(-> (~ x) (| y z))");
    assertEquals("->", t.op());
    assertEquals(2, t.kids().size());
    EGraph<Prop, Void> g = EGraph.withoutAnalysis();
    int id = g.addTree(t, BRIDGE);
    assertEquals(id, g.lookupTree(t, BRIDGE));
    assertEquals(-1, g.lookupTree(Term.parse("(| x y)"), BRIDGE));
  }
}
