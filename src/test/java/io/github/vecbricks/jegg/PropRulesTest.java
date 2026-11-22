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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * egg's {@code tests/prop.rs}, ported: propositional formulas over symbols and the constants
 * {@code true} and {@code false} with {@code &}, {@code |}, {@code ~} and {@code ->}, a
 * constant-folding analysis, and egg's three tests: {@code prove_contrapositive},
 * {@code prove_chain} and {@code const_fold}. Each run is pinned to egg's own iteration, node and
 * class counts. {@code prove_fold} is not egg's: a larger case of our own that the measurement
 * harness runs, with no egg run to pin to.
 */
class PropRulesTest {

  sealed interface Prop extends Language<Prop> permits Prop.Bool, Prop.Sym, Prop.And, Prop.Or,
      Prop.Not, Prop.Implies {
    /** A constant, {@code true} or {@code false}: egg's {@code Bool}. */
    record Bool(boolean value) implements Prop {
      @Override
      public IntList children() {
        return IntList.EMPTY;
      }

      @Override
      public Prop withChildren(IntList c) {
        return this;
      }
    }

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
        case "true" -> new Prop.Bool(true);
        case "false" -> new Prop.Bool(false);
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
        case Prop.Bool b -> new Term(String.valueOf(b.value()), List.of());
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

  /**
   * egg's {@code ConstantFold}: the fact is the class's constant, if it has one. {@code make}
   * folds a connective whose children all have one; {@code join} refuses two constants that
   * differ, as egg's {@code assert_eq} does; {@code modify} adds the constant and merges it in,
   * pruning nothing. egg's fact also carries a pattern for explanations, which jegg does not
   * port.
   */
  static final Analysis<Prop, Boolean> CONSTANT_FOLD = new Analysis<>() {
    @Override
    public Boolean make(EGraph<Prop, Boolean> g, Prop node) {
      return switch (node) {
        case Prop.Bool b -> b.value();
        case Prop.Sym s -> null;
        case Prop.And a -> both(g, a, (x, y) -> x && y);
        case Prop.Or o -> both(g, o, (x, y) -> x || y);
        case Prop.Implies im -> both(g, im, (x, y) -> !x || y);
        case Prop.Not n -> {
          Boolean x = g.data(n.children().get(0));
          yield x == null ? null : !x;
        }
      };
    }

    private Boolean both(EGraph<Prop, Boolean> g, Prop node,
        java.util.function.BinaryOperator<Boolean> f) {
      Boolean x = g.data(node.children().get(0));
      Boolean y = g.data(node.children().get(1));
      return x == null || y == null ? null : f.apply(x, y);
    }

    @Override
    public Boolean join(Boolean a, Boolean b) {
      if (a != null && b != null && !a.equals(b)) {
        throw new IllegalStateException("Merged non-equal constants: " + a + " and " + b);
      }
      return a != null ? a : b;
    }

    @Override
    public void modify(EGraph<Prop, Boolean> g, int id) {
      Boolean c = g.data(id);
      if (c != null) {
        g.merge(id, g.add(new Prop.Bool(c)));
      }
    }
  };

  static Rewrite<Prop, Boolean> rule(String name, String lhs, String rhs) {
    return Rewrite.of(name, pattern(lhs), pattern(rhs));
  }

  // egg's rule set, by its names.
  static final Rewrite<Prop, Boolean> DEF_IMPLY =
      rule("def_imply", "(-> ?a ?b)", "(| (~ ?a) ?b)");
  static final Rewrite<Prop, Boolean> DEF_IMPLY_FLIP =
      rule("def_imply_flip", "(| (~ ?a) ?b)", "(-> ?a ?b)");
  static final Rewrite<Prop, Boolean> DOUBLE_NEG = rule("double_neg", "(~ (~ ?a))", "?a");
  static final Rewrite<Prop, Boolean> DOUBLE_NEG_FLIP =
      rule("double_neg_flip", "?a", "(~ (~ ?a))");
  static final Rewrite<Prop, Boolean> ASSOC_OR =
      rule("assoc_or", "(| ?a (| ?b ?c))", "(| (| ?a ?b) ?c)");
  static final Rewrite<Prop, Boolean> DIST_AND_OR =
      rule("dist_and_or", "(& ?a (| ?b ?c))", "(| (& ?a ?b) (& ?a ?c))");
  static final Rewrite<Prop, Boolean> DIST_OR_AND =
      rule("dist_or_and", "(| ?a (& ?b ?c))", "(& (| ?a ?b) (| ?a ?c))");
  static final Rewrite<Prop, Boolean> COMM_OR = rule("comm_or", "(| ?a ?b)", "(| ?b ?a)");
  static final Rewrite<Prop, Boolean> COMM_AND = rule("comm_and", "(& ?a ?b)", "(& ?b ?a)");
  static final Rewrite<Prop, Boolean> LEM = rule("lem", "(| ?a (~ ?a))", "true");
  static final Rewrite<Prop, Boolean> OR_TRUE = rule("or_true", "(| ?a true)", "true");
  static final Rewrite<Prop, Boolean> AND_TRUE = rule("and_true", "(& ?a true)", "?a");
  static final Rewrite<Prop, Boolean> CONTRAPOSITIVE =
      rule("contrapositive", "(-> ?a ?b)", "(-> (~ ?b) (~ ?a))");

  /**
   * egg's {@code lem_imply}, a multi-pattern: {@code ?value = true = (& (-> ?a ?b) (-> (~ ?a)
   * ?c)) => ?value = (| ?b ?c)}, which must be one because the conjunction is not equal to the
   * disjunction in general. Its right-hand side counts every match as applied, as egg's does, so
   * a run in which it matches never saturates: {@link #CHAIN_CASE} ends at egg's iteration limit.
   */
  static final Rewrite<Prop, Boolean> LEM_IMPLY = Rewrite.multi("lem_imply",
      MultiTerm.parse("?value = true = (& (-> ?a ?b) (-> (~ ?a) ?c))", BRIDGE),
      MultiTerm.parse("?value = (| ?b ?c)", BRIDGE));

  /** egg's limits for this suite. */
  static final RunLimits LIMITS = RunLimits.DEFAULT.withIterations(20).withNodes(5_000);

  /** What egg 73975c9's own run reports: iterations, nodes in classes, classes. */
  record Egg(int iterations, int nodes, int classes) {
  }

  /**
   * One of egg's {@code prove_something} tests: its rules, start term, goals and egg's counts,
   * every goal to be in the start's class after the run. {@code egg} is null for a case that is
   * not egg's. The cases are data so the measurement harness (PLAN.md 6) can run the same
   * suite.
   */
  record Case(String name, List<Rewrite<Prop, Boolean>> rules, String start, List<String> goals,
      Egg egg) {
  }

  static final Case CONTRAPOSITIVE_CASE = new Case("prove_contrapositive",
      List.of(DEF_IMPLY, DEF_IMPLY_FLIP, DOUBLE_NEG_FLIP, COMM_OR), "(-> x y)",
      List.of("(-> x y)", "(| (~ x) y)", "(| (~ x) (~ (~ y)))", "(| (~ (~ y)) (~ x))",
          "(-> (~ y) (~ x))"),
      new Egg(4, 14, 6));

  /**
   * egg runs it to its iteration limit, 20 iterations, with the graph at 31 nodes and 12 classes
   * from the fifth on: its multi-pattern {@code lem_imply} counts as applied each time it
   * matches, which stops egg's runner from calling an unchanged graph saturated, and so does
   * this one's ({@link #LEM_IMPLY}).
   */
  static final Case CHAIN_CASE = new Case("prove_chain",
      List.of(DEF_IMPLY, DEF_IMPLY_FLIP, DOUBLE_NEG_FLIP, COMM_OR, COMM_AND, LEM_IMPLY),
      "(& (-> x y) (-> y z))",
      List.of("(& (-> x y) (-> y z))", "(& (-> (~ y) (~ x)) (-> y z))",
          "(& (-> y z) (-> (~ y) (~ x)))", "(| z (~ x))", "(| (~ x) z)", "(-> x z)"),
      new Egg(20, 31, 12));

  /**
   * Distribution and association: (x | y) & (x | z) reaches x | (y & z). Not egg's test: the
   * larger case the measurement harness runs (PLAN.md 6), with no egg counts to pin.
   */
  static final Case FOLD_CASE = new Case("prove_fold",
      List.of(DIST_OR_AND, DIST_AND_OR, COMM_OR, COMM_AND, ASSOC_OR, DOUBLE_NEG, CONTRAPOSITIVE),
      "(& (| x y) (| x z))", List.of("(| x (& y z))"), null);

  static final List<Case> CASES = List.of(CONTRAPOSITIVE_CASE, CHAIN_CASE, FOLD_CASE);

  /** A case's graph before the run, as egg's {@code prove_something} makes it. */
  record Prepared(EGraph<Prop, Boolean> graph, int root) {
  }

  /**
   * The start term added, then {@code true} added and the start united with it: egg assumes the
   * input is true, which {@code lem_imply}'s soundness needs, and rebuilds before the run.
   */
  static Prepared prepare(Case c) {
    EGraph<Prop, Boolean> g = new EGraph<>(CONSTANT_FOLD);
    int root = g.addTree(Term.parse(c.start()), BRIDGE);
    g.merge(root, g.add(new Prop.Bool(true)));
    g.rebuild();
    return new Prepared(g, root);
  }

  /** egg's {@code prove_something}: every goal must be in the start's class after the run. */
  private static RunReport prove(Case c) {
    Prepared p = prepare(c);
    EGraph<Prop, Boolean> g = p.graph();
    RunReport report = new Runner<>(g, c.rules(), LIMITS, new BackoffScheduler<>()).run();
    for (String goal : c.goals()) {
      OptionalInt id = g.lookupTree(Term.parse(goal), BRIDGE);
      assertTrue(id.isPresent() && g.find(id.getAsInt()) == g.find(p.root()),
          goal + " is not in the class of " + c.start() + "\n" + report);
    }
    g.checkInvariants();
    g.checkAnalysisInvariant();
    if (c.egg() != null) {
      String why = "egg reports " + c.egg() + "\n" + report;
      assertEquals(c.egg().iterations(), report.size(), why);
      assertEquals(c.egg().nodes(), g.numNodes(), why);
      assertEquals(c.egg().classes(), g.numClasses(), why);
    }
    return report;
  }

  @Test
  void proveContrapositive() {
    assertEquals(new StopReason.Saturated(), prove(CONTRAPOSITIVE_CASE).stop());
  }

  @Test
  void proveChain() {
    // egg runs it to its iteration limit (20) with the graph unchanged from iteration 5, at 31
    // nodes and 12 classes; so does this: lem_imply counts every match as applied.
    assertEquals(new StopReason.IterationLimit(20), prove(CHAIN_CASE).stop());
  }

  @Test
  void proveFoldWithTheOtherRules() {
    prove(FOLD_CASE);
  }

  @Test
  void constFold() {
    // egg's const_fold: (| (& false true) (& true false)) folds to false, with no run at all.
    EGraph<Prop, Boolean> g = new EGraph<>(CONSTANT_FOLD);
    int root = g.addTree(Term.parse("(| (& false true) (& true false))"), BRIDGE);
    g.rebuild();
    OptionalInt end = g.lookupTree(Term.parse("false"), BRIDGE);
    assertTrue(end.isPresent() && g.find(end.getAsInt()) == g.find(root));
    assertEquals(false, g.data(root));
    assertEquals(5, g.numNodes());
    assertEquals(2, g.numClasses());
    g.checkInvariants();
    g.checkAnalysisInvariant();
  }

  @Test
  void theAnalysisFoldsEachConnectiveAndRefusesAContradiction() {
    EGraph<Prop, Boolean> g = new EGraph<>(CONSTANT_FOLD);
    int implies = g.addTree(Term.parse("(-> true false)"), BRIDGE);
    int not = g.addTree(Term.parse("(~ (-> true false))"), BRIDGE);
    int unknown = g.addTree(Term.parse("(& x true)"), BRIDGE);
    g.rebuild();
    assertEquals(false, g.data(implies));
    assertEquals(true, g.data(not));
    assertEquals(null, g.data(unknown));
    // true and false in one class is a contradiction the analysis refuses, as egg's does.
    assertThrows(IllegalStateException.class, () -> {
      g.merge(implies, not);
      g.rebuild();
    });
  }

  @Test
  void lemImplyAppliesOnlyInTheClassOfTrue() {
    // The conjunction of (x -> y) and (~x -> z) gives y | z only where it is known true.
    String conj = "(& (-> x y) (-> (~ x) z))";
    EGraph<Prop, Boolean> loose = new EGraph<>(CONSTANT_FOLD);
    int notTrue = loose.addTree(Term.parse(conj), BRIDGE);
    loose.rebuild();
    new Runner<>(loose, List.of(LEM_IMPLY), LIMITS, new BackoffScheduler<>()).run();
    assertEquals(OptionalInt.empty(), loose.lookupTree(Term.parse("(| y z)"), BRIDGE));

    EGraph<Prop, Boolean> known = new EGraph<>(CONSTANT_FOLD);
    int root = known.addTree(Term.parse(conj), BRIDGE);
    known.merge(root, known.add(new Prop.Bool(true)));
    known.rebuild();
    new Runner<>(known, List.of(LEM_IMPLY), LIMITS, new BackoffScheduler<>()).run();
    OptionalInt got = known.lookupTree(Term.parse("(| y z)"), BRIDGE);
    assertTrue(got.isPresent() && known.find(got.getAsInt()) == known.find(root));
    assertTrue(notTrue >= 0);
  }

  @Test
  void aTermParsesAndBridgesBothWays() {
    Term t = Term.parse("(-> (~ x) (| true z))");
    assertEquals("->", t.op());
    assertEquals(2, t.kids().size());
    EGraph<Prop, Boolean> g = new EGraph<>(CONSTANT_FOLD);
    int id = g.addTree(t, BRIDGE);
    assertEquals(OptionalInt.of(id), g.lookupTree(t, BRIDGE));
    assertEquals(OptionalInt.empty(), g.lookupTree(Term.parse("(| x y)"), BRIDGE));
  }
}
