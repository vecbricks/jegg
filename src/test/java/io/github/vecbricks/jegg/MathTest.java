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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * egg's {@code tests/math.rs} (egg 73975c9), ported test for test: symbolic arithmetic with
 * derivatives and integrals, constant folding that prunes a folded class to its constant, and
 * egg's 44 rules under its names. Each test adds a term, runs the rules with egg's limits until
 * its goal is in the term's class, and checks that its run ends where egg's does.
 */
class MathTest {

  sealed interface Math extends Language<Math> permits Math.Leaf, Math.Diff, Math.Integral,
      Math.Add, Math.Sub, Math.Mul, Math.Div, Math.Pow, Math.Ln, Math.Sqrt, Math.Sin, Math.Cos {

    sealed interface Leaf extends Math permits Constant, Symbol {
      @Override
      default IntList children() {
        return IntList.EMPTY;
      }

      @Override
      default Math withChildren(IntList c) {
        return this;
      }
    }

    /**
     * A constant, never NaN, as egg's {@code NotNan<f64>}; -0.0 is 0.0, as egg's hashes it, so
     * the two are one node (a record would tell them apart).
     */
    record Constant(double value) implements Leaf {
      public Constant {
        if (Double.isNaN(value)) {
          throw new IllegalArgumentException("a constant is not NaN");
        }
        value = value + 0.0;
      }

      @Override
      public String toString() {
        return value == java.lang.Math.rint(value) && java.lang.Math.abs(value) < 1e15
            ? Long.toString((long) value) : Double.toString(value);
      }
    }

    record Symbol(String name) implements Leaf {
      @Override
      public String toString() {
        return name;
      }
    }

    /** {@code (d x e)}: the derivative of e with respect to x. */
    record Diff(IntList children) implements Math {
      @Override
      public Math withChildren(IntList c) {
        return new Diff(c);
      }
    }

    /** {@code (i e x)}: the integral of e over x. */
    record Integral(IntList children) implements Math {
      @Override
      public Math withChildren(IntList c) {
        return new Integral(c);
      }
    }

    record Add(IntList children) implements Math {
      @Override
      public Math withChildren(IntList c) {
        return new Add(c);
      }
    }

    record Sub(IntList children) implements Math {
      @Override
      public Math withChildren(IntList c) {
        return new Sub(c);
      }
    }

    record Mul(IntList children) implements Math {
      @Override
      public Math withChildren(IntList c) {
        return new Mul(c);
      }
    }

    record Div(IntList children) implements Math {
      @Override
      public Math withChildren(IntList c) {
        return new Div(c);
      }
    }

    record Pow(IntList children) implements Math {
      @Override
      public Math withChildren(IntList c) {
        return new Pow(c);
      }
    }

    record Ln(IntList children) implements Math {
      @Override
      public Math withChildren(IntList c) {
        return new Ln(c);
      }
    }

    record Sqrt(IntList children) implements Math {
      @Override
      public Math withChildren(IntList c) {
        return new Sqrt(c);
      }
    }

    record Sin(IntList children) implements Math {
      @Override
      public Math withChildren(IntList c) {
        return new Sin(c);
      }
    }

    record Cos(IntList children) implements Math {
      @Override
      public Math withChildren(IntList c) {
        return new Cos(c);
      }
    }
  }

  static final TreeBridge<Term, Math> BRIDGE = new TreeBridge<>() {
    // As egg's define_language! parses: an operator at its arity, else a number, else a symbol.
    @Override
    public Math node(Term t, IntList children) {
      int arity = t.kids().size();
      Math node = switch (t.op()) {
        case "d" -> arity == 2 ? new Math.Diff(children) : null;
        case "i" -> arity == 2 ? new Math.Integral(children) : null;
        case "+" -> arity == 2 ? new Math.Add(children) : null;
        case "-" -> arity == 2 ? new Math.Sub(children) : null;
        case "*" -> arity == 2 ? new Math.Mul(children) : null;
        case "/" -> arity == 2 ? new Math.Div(children) : null;
        case "pow" -> arity == 2 ? new Math.Pow(children) : null;
        case "ln" -> arity == 1 ? new Math.Ln(children) : null;
        case "sqrt" -> arity == 1 ? new Math.Sqrt(children) : null;
        case "sin" -> arity == 1 ? new Math.Sin(children) : null;
        case "cos" -> arity == 1 ? new Math.Cos(children) : null;
        default -> null;
      };
      if (node != null) {
        return node;
      }
      if (arity > 0) {
        throw new IllegalArgumentException("no operator " + t.op() + " of " + arity
            + " children in " + t);
      }
      try {
        return new Math.Constant(Double.parseDouble(t.op()));
      } catch (NumberFormatException notANumber) {
        return new Math.Symbol(t.op());
      }
    }

    @Override
    public List<Term> childrenOf(Term t) {
      return t.kids();
    }

    @Override
    public Term build(Math node, List<Term> children) {
      String op = switch (node) {
        case Math.Leaf leaf -> leaf.toString();
        case Math.Diff d -> "d";
        case Math.Integral i -> "i";
        case Math.Add a -> "+";
        case Math.Sub s -> "-";
        case Math.Mul m -> "*";
        case Math.Div d -> "/";
        case Math.Pow p -> "pow";
        case Math.Ln l -> "ln";
        case Math.Sqrt s -> "sqrt";
        case Math.Sin s -> "sin";
        case Math.Cos c -> "cos";
      };
      return new Term(op, children);
    }
  };

  static Pattern<Math> pattern(String s) {
    return Term.pattern(s, BRIDGE);
  }

  /**
   * egg's {@code ConstantFold}: the fact is the class's constant, if it has one. {@code make}
   * folds the four operations, division by a non-zero constant only; {@code join} refuses two
   * constants that differ, as egg's {@code assert_eq} does; {@code modify} adds the constant,
   * merges it in and prunes the class to its leaves, as egg's does, so the folded forms match
   * no rule again. egg's fact also carries a pattern for explanations, which jegg does not port.
   */
  static final Analysis<Math, Double> CONSTANT_FOLD = new Analysis<>() {
    @Override
    public Double make(EGraph<Math, Double> g, Math node) {
      return switch (node) {
        case Math.Constant c -> c.value();
        case Math.Add a -> fold(g, a, Double::sum);
        case Math.Sub s -> fold(g, s, (x, y) -> x - y);
        case Math.Mul m -> fold(g, m, (x, y) -> x * y);
        case Math.Div d -> {
          Double y = g.data(d.children().get(1));
          yield y == null || y == 0.0 ? null : fold(g, d, (p, q) -> p / q);
        }
        default -> null;
      };
    }

    private Double fold(EGraph<Math, Double> g, Math node,
        java.util.function.DoubleBinaryOperator f) {
      Double x = g.data(node.children().get(0));
      Double y = g.data(node.children().get(1));
      return x == null || y == null ? null : f.applyAsDouble(x, y) + 0.0;
    }

    @Override
    public Double join(Double a, Double b) {
      if (a != null && b != null && !a.equals(b)) {
        throw new IllegalStateException("Merged non-equal constants: " + a + " and " + b);
      }
      return a != null ? a : b;
    }

    @Override
    public void modify(EGraph<Math, Double> g, int id) {
      Double c = g.data(id);
      if (c != null) {
        int root = g.merge(id, g.add(new Math.Constant(c)));
        g.retainNodes(root, node -> node.children().isEmpty());
      }
    }
  };

  private static Rewrite<Math, Double> rule(String name, String lhs, String rhs) {
    return Rewrite.of(name, pattern(lhs), pattern(rhs));
  }

  private static boolean isSymbol(EGraph<Math, Double> g, int id) {
    return g.classOf(id).nodes().stream().anyMatch(n -> n instanceof Math.Symbol);
  }

  private static Condition<Math, Double> isNotZero(String v) {
    return (g, _, s) -> {
      Double c = g.data(s.idOf(v));
      return c == null || c != 0.0;
    };
  }

  private static Condition<Math, Double> isConst(String v) {
    return (g, _, s) -> g.data(s.idOf(v)) != null;
  }

  private static Condition<Math, Double> isSym(String v) {
    return (g, _, s) -> isSymbol(g, s.idOf(v));
  }

  private static Condition<Math, Double> isConstOrDistinctVar(String v, String w) {
    return (g, _, s) -> g.find(s.idOf(v)) != g.find(s.idOf(w))
        && (g.data(s.idOf(v)) != null || isSymbol(g, s.idOf(v)));
  }

  /** egg's rules, by their names and in their order. */
  static List<Rewrite<Math, Double>> rules() {
    return List.of(
        rule("comm-add", "(+ ?a ?b)", "(+ ?b ?a)"),
        rule("comm-mul", "(* ?a ?b)", "(* ?b ?a)"),
        rule("assoc-add", "(+ ?a (+ ?b ?c))", "(+ (+ ?a ?b) ?c)"),
        rule("assoc-mul", "(* ?a (* ?b ?c))", "(* (* ?a ?b) ?c)"),
        rule("sub-canon", "(- ?a ?b)", "(+ ?a (* -1 ?b))"),
        rule("div-canon", "(/ ?a ?b)", "(* ?a (pow ?b -1))").when(isNotZero("b")),
        rule("zero-add", "(+ ?a 0)", "?a"),
        rule("zero-mul", "(* ?a 0)", "0"),
        rule("one-mul", "(* ?a 1)", "?a"),
        rule("add-zero", "?a", "(+ ?a 0)"),
        rule("mul-one", "?a", "(* ?a 1)"),
        rule("cancel-sub", "(- ?a ?a)", "0"),
        rule("cancel-div", "(/ ?a ?a)", "1").when(isNotZero("a")),
        rule("distribute", "(* ?a (+ ?b ?c))", "(+ (* ?a ?b) (* ?a ?c))"),
        rule("factor", "(+ (* ?a ?b) (* ?a ?c))", "(* ?a (+ ?b ?c))"),
        rule("pow-mul", "(* (pow ?a ?b) (pow ?a ?c))", "(pow ?a (+ ?b ?c))"),
        rule("pow0", "(pow ?x 0)", "1").when(isNotZero("x")),
        rule("pow1", "(pow ?x 1)", "?x"),
        rule("pow2", "(pow ?x 2)", "(* ?x ?x)"),
        rule("pow-recip", "(pow ?x -1)", "(/ 1 ?x)").when(isNotZero("x")),
        rule("recip-mul-div", "(* ?x (/ 1 ?x))", "1").when(isNotZero("x")),
        rule("d-variable", "(d ?x ?x)", "1").when(isSym("x")),
        rule("d-constant", "(d ?x ?c)", "0").when(isSym("x")).when(isConstOrDistinctVar("c", "x")),
        rule("d-add", "(d ?x (+ ?a ?b))", "(+ (d ?x ?a) (d ?x ?b))"),
        rule("d-mul", "(d ?x (* ?a ?b))", "(+ (* ?a (d ?x ?b)) (* ?b (d ?x ?a)))"),
        rule("d-sin", "(d ?x (sin ?x))", "(cos ?x)"),
        rule("d-cos", "(d ?x (cos ?x))", "(* -1 (sin ?x))"),
        rule("d-ln", "(d ?x (ln ?x))", "(/ 1 ?x)").when(isNotZero("x")),
        rule("d-power", "(d ?x (pow ?f ?g))",
            "(* (pow ?f ?g) (+ (* (d ?x ?f) (/ ?g ?f)) (* (d ?x ?g) (ln ?f))))")
            .when(isNotZero("f")).when(isNotZero("g")),
        rule("i-one", "(i 1 ?x)", "?x"),
        rule("i-power-const", "(i (pow ?x ?c) ?x)", "(/ (pow ?x (+ ?c 1)) (+ ?c 1))")
            .when(isConst("c")),
        rule("i-cos", "(i (cos ?x) ?x)", "(sin ?x)"),
        rule("i-sin", "(i (sin ?x) ?x)", "(* -1 (cos ?x))"),
        rule("i-sum", "(i (+ ?f ?g) ?x)", "(+ (i ?f ?x) (i ?g ?x))"),
        rule("i-dif", "(i (- ?f ?g) ?x)", "(- (i ?f ?x) (i ?g ?x))"),
        rule("i-parts", "(i (* ?a ?b) ?x)", "(- (* ?a (i ?b ?x)) (i (* (d ?x ?a) (i ?b ?x)) ?x))"));
  }

  /** What egg 73975c9's own run reports: iterations, nodes in classes, classes. */
  record Egg(int iterations, int nodes, int classes) {
  }

  /**
   * One of egg's tests: its name, limits, start term, goals, and whether egg proves it. Extra
   * terms are added to the graph first, as egg's {@code with_expr} adds them. The cases are
   * data, so the measurement harness runs the same suite.
   */
  record Case(String name, RunLimits limits, Egg egg, boolean proves, String start,
      List<String> goals, List<String> extraTerms) {
    Case(String name, Egg egg, String start, String... goals) {
      this(name, RunLimits.DEFAULT, egg, true, start, List.of(goals), List.of());
    }

    Case withLimits(RunLimits limits) {
      return new Case(name, limits, egg, proves, start, goals, extraTerms);
    }

    Case disproved() {
      return new Case(name, limits, egg, false, start, goals, extraTerms);
    }

    Case withExtraTerm(String term) {
      return new Case(name, limits, egg, proves, start, goals, List.of(term));
    }
  }

  static final Case MATH_FAIL = new Case("math_fail", new Egg(4, 18, 5), "(+ x y)", "(/ x y)")
      .disproved();
  static final Case MATH_SIMPLIFY_ADD = new Case("math_simplify_add", new Egg(5, 36, 9),
      "(+ x (+ x (+ x x)))", "(* 4 x)");
  static final Case MATH_POWERS = new Case("math_powers", new Egg(2, 21, 9),
      "(* (pow 2 x) (pow 2 y))", "(pow 2 (+ x y))");
  static final Case MATH_SIMPLIFY_CONST = new Case("math_simplify_const", new Egg(3, 14, 7),
      "(+ 1 (- a (* (- 2 1) a)))", "1");
  static final Case MATH_SIMPLIFY_ROOT = new Case("math_simplify_root", new Egg(8, 211, 37), """
      (/ 1
         (- (/ (+ 1 (sqrt five))
               2)
            (/ (- 1 (sqrt five))
               2)))""", "(/ 1 (sqrt five))")
      .withLimits(RunLimits.DEFAULT.withNodes(75_000));
  static final Case MATH_SIMPLIFY_FACTOR = new Case("math_simplify_factor", new Egg(7, 142, 20),
      "(* (+ x 3) (+ x 1))", "(+ (+ (* x x) (* 4 x)) 3)");
  static final Case MATH_DIFF_SAME = new Case("math_diff_same", new Egg(2, 5, 3), "(d x x)", "1");
  static final Case MATH_DIFF_DIFFERENT = new Case("math_diff_different", new Egg(2, 8, 4),
      "(d x y)", "0");
  static final Case MATH_DIFF_SIMPLE1 = new Case("math_diff_simple1", new Egg(5, 20, 6),
      "(d x (+ 1 (* 2 x)))", "2");
  static final Case MATH_DIFF_SIMPLE2 = new Case("math_diff_simple2", new Egg(4, 26, 6),
      "(d x (+ 1 (* y x)))", "y");
  static final Case MATH_DIFF_LN = new Case("math_diff_ln", new Egg(2, 12, 5), "(d x (ln x))",
      "(/ 1 x)");
  static final Case DIFF_POWER_SIMPLE = new Case("diff_power_simple", new Egg(6, 80, 19),
      "(d x (pow x 3))", "(* 3 (pow x 2))");
  /** egg adds the goal's term to the graph first ("this needs to see the end expression"). */
  static final Case DIFF_POWER_HARDER = new Case("diff_power_harder", new Egg(8, 409, 90),
      "(d x (- (pow x 3) (* 7 (pow x 2))))", "(* x (- (* 3 x) 14))")
      .withLimits(RunLimits.DEFAULT.withIterations(60).withNodes(100_000))
      .withExtraTerm("(* x (- (* 3 x) 14))");
  static final Case INTEG_ONE = new Case("integ_one", new Egg(2, 6, 3), "(i 1 x)", "x");
  static final Case INTEG_SIN = new Case("integ_sin", new Egg(2, 12, 5), "(i (cos x) x)",
      "(sin x)");
  static final Case INTEG_X = new Case("integ_x", new Egg(2, 12, 6), "(i (pow x 1) x)",
      "(/ (pow x 2) 2)");
  static final Case INTEG_PART1 = new Case("integ_part1", new Egg(5, 486, 171),
      "(i (* x (cos x)) x)", "(+ (* x (sin x)) (cos x))");
  static final Case INTEG_PART2 = new Case("integ_part2", new Egg(6, 1991, 678),
      "(i (* (cos x) x) x)", "(+ (* x (sin x)) (cos x))");
  static final Case INTEG_PART3 = new Case("integ_part3", new Egg(5, 144, 55), "(i (ln x) x)",
      "(- (* x (ln x)) x)");

  /** egg's tests under its full rule set, in egg's order. */
  static final List<Case> CASES = List.of(MATH_FAIL, MATH_SIMPLIFY_ADD, MATH_POWERS,
      MATH_SIMPLIFY_CONST, MATH_SIMPLIFY_ROOT, MATH_SIMPLIFY_FACTOR, MATH_DIFF_SAME,
      MATH_DIFF_DIFFERENT, MATH_DIFF_SIMPLE1, MATH_DIFF_SIMPLE2, MATH_DIFF_LN, DIFF_POWER_SIMPLE,
      DIFF_POWER_HARDER, INTEG_ONE, INTEG_SIN, INTEG_X, INTEG_PART1, INTEG_PART2, INTEG_PART3);

  private static final String PROVED = "Proved all goals";

  static boolean proved(EGraph<Math, ?> g, int root, Pattern<Math> goal) {
    return !Matcher.matchIn(g, goal, root, Subst.EMPTY).isEmpty();
  }

  private record Run(EGraph<Math, Double> graph, int root, RunReport report,
      List<Pattern<Math>> goals) {
    boolean proves(int goal) {
      return proved(graph, root, goals.get(goal));
    }
  }

  /** egg's {@code test_runner}: the terms added, the rules run until every goal is proved. */
  private static Run run(Case c) {
    EGraph<Math, Double> g = new EGraph<>(CONSTANT_FOLD);
    int root = g.addTree(Term.parse(c.start()), BRIDGE);
    for (String extra : c.extraTerms()) {
      g.addTree(Term.parse(extra), BRIDGE);
    }
    List<Pattern<Math>> goals = c.goals().stream().map(MathTest::pattern).toList();
    RunReport report = new Runner<>(g, rules(), c.limits(), new BackoffScheduler<>())
        .withHook(graph -> goals.stream().allMatch(p -> proved(graph, root, p))
            ? Optional.of(PROVED) : Optional.empty())
        .run();
    return new Run(g, root, report, goals);
  }

  private static void sameAsEgg(Run r, Egg egg) {
    boolean stoppedByHook = r.report().stop() instanceof StopReason.Other;
    String why = "egg reports " + egg + "\n" + r.report();
    assertEquals(egg.iterations(), r.report().size() + (stoppedByHook ? 1 : 0), why);
    assertEquals(egg.nodes(), r.graph().numNodes(), why);
    assertEquals(egg.classes(), r.graph().numClasses(), why);
  }

  private static void check(Case c) {
    Run r = run(c);
    if (c.proves()) {
      for (int i = 0; i < r.goals().size(); i++) {
        if (!r.proves(i)) {
          fail("Could not prove goal " + i + "\n" + r.report());
        }
      }
      assertEquals(new StopReason.Other(PROVED), r.report().stop(), r.report().toString());
    } else {
      assertFalse(r.proves(0), "goal 0 proved, which egg says it must not be\n" + r.report());
      assertEquals(new StopReason.Saturated(), r.report().stop(), r.report().toString());
    }
    r.graph().checkInvariants();
    r.graph().checkAnalysisInvariant();
    sameAsEgg(r, c.egg());
  }

  @Test
  void mathAssociateAdds() {
    // egg's own two rules, the simple scheduler, an iteration limit of 7 and no analysis: the
    // goal is in the start's class, and the graph has 127 classes, as egg asserts.
    EGraph<Math, Void> g = EGraph.withoutAnalysis();
    int root = g.addTree(Term.parse("(+ 1 (+ 2 (+ 3 (+ 4 (+ 5 (+ 6 7))))))"), BRIDGE);
    List<Rewrite<Math, Void>> rules = List.of(
        Rewrite.of("comm-add", pattern("(+ ?a ?b)"), pattern("(+ ?b ?a)")),
        Rewrite.of("assoc-add", pattern("(+ ?a (+ ?b ?c))"), pattern("(+ (+ ?a ?b) ?c)")));
    Pattern<Math> goal = pattern("(+ 7 (+ 6 (+ 5 (+ 4 (+ 3 (+ 2 1))))))");
    // egg installs no goal hook for a test with a check, so all seven iterations run.
    RunReport report = new Runner<>(g, rules, RunLimits.DEFAULT.withIterations(7),
        Scheduler.simple()).run();
    assertTrue(proved(g, root, goal), report.toString());
    assertEquals(127, g.numClasses(), report.toString());
    assertEquals(new StopReason.IterationLimit(7), report.stop(), report.toString());
    assertEquals(1939, g.numNodes(), "egg's count");
  }

  @Test
  void mathFail() {
    check(MATH_FAIL);
  }

  @Test
  void mathSimplifyAdd() {
    check(MATH_SIMPLIFY_ADD);
  }

  @Test
  void mathPowers() {
    check(MATH_POWERS);
  }

  @Test
  void mathSimplifyConst() {
    check(MATH_SIMPLIFY_CONST);
  }

  @Test
  void mathSimplifyRoot() {
    check(MATH_SIMPLIFY_ROOT);
  }

  @Test
  void mathSimplifyFactor() {
    check(MATH_SIMPLIFY_FACTOR);
  }

  @Test
  void mathDiffSame() {
    check(MATH_DIFF_SAME);
  }

  @Test
  void mathDiffDifferent() {
    check(MATH_DIFF_DIFFERENT);
  }

  @Test
  void mathDiffSimple1() {
    check(MATH_DIFF_SIMPLE1);
  }

  @Test
  void mathDiffSimple2() {
    check(MATH_DIFF_SIMPLE2);
  }

  @Test
  void mathDiffLn() {
    check(MATH_DIFF_LN);
  }

  @Test
  void diffPowerSimple() {
    check(DIFF_POWER_SIMPLE);
  }

  @Test
  void diffPowerHarder() {
    check(DIFF_POWER_HARDER);
  }

  @Test
  void integOne() {
    check(INTEG_ONE);
  }

  @Test
  void integSin() {
    check(INTEG_SIN);
  }

  @Test
  void integX() {
    check(INTEG_X);
  }

  @Test
  void integPart1() {
    check(INTEG_PART1);
  }

  @Test
  void integPart2() {
    check(INTEG_PART2);
  }

  @Test
  void integPart3() {
    check(INTEG_PART3);
  }

  @Test
  void assocMulSaturates() {
    // egg's assoc_mul_saturates: (* x 1) under the full rule set saturates within 3 iterations.
    EGraph<Math, Double> g = new EGraph<>(CONSTANT_FOLD);
    g.addTree(Term.parse("(* x 1)"), BRIDGE);
    RunReport report = new Runner<>(g, rules(), RunLimits.DEFAULT.withIterations(3),
        new BackoffScheduler<>()).run();
    assertInstanceOf(StopReason.Saturated.class, report.stop(), report.toString());
  }

  @Test
  void theCasesAreEggsNineteenUnderItsRulesInEggsOrder() {
    assertEquals(19, CASES.size());
    assertEquals("math_fail", CASES.get(0).name());
    assertEquals("integ_part3", CASES.get(18).name());
  }
}
