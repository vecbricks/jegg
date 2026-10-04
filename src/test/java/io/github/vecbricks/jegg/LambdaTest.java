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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * egg's {@code tests/lambda.rs} (the paper's Figures 10 and 11), ported test for test: a lambda
 * calculus with an analysis carrying free variables and constants, conditional rewrites that
 * read it, and a capture-avoiding substitution as a dynamic applier. Each test adds a term, runs
 * the rules with egg's limits until its goals are in the term's class, and checks them there,
 * as egg's {@code test_fn!} does; egg's {@code should_panic} tests check that goal 0 is not.
 */
class LambdaTest {

  sealed interface Lambda extends Language<Lambda>
      permits Lambda.Leaf, Lambda.Var, Lambda.Add, Lambda.Eq, Lambda.App, Lambda.Lam, Lambda.Let,
      Lambda.Fix, Lambda.If {

    /** A leaf: no children, and its payload is its head. */
    sealed interface Leaf extends Lambda permits Bool, Num, Sym {
      @Override
      default IntList children() {
        return IntList.EMPTY;
      }

      @Override
      default Lambda withChildren(IntList children) {
        return this;
      }
    }

    record Bool(boolean value) implements Leaf {
      @Override
      public String toString() {
        return Boolean.toString(value);
      }
    }

    record Num(int value) implements Leaf {
      @Override
      public String toString() {
        return Integer.toString(value);
      }
    }

    /** A variable's name. */
    record Sym(String name) implements Leaf {
      @Override
      public String toString() {
        return name;
      }
    }

    /** {@code (var x)}: a use of the variable whose name is the child's class. */
    record Var(IntList children) implements Lambda {
      @Override
      public Lambda withChildren(IntList c) {
        return new Var(c);
      }
    }

    record Add(IntList children) implements Lambda {
      @Override
      public Lambda withChildren(IntList c) {
        return new Add(c);
      }
    }

    record Eq(IntList children) implements Lambda {
      @Override
      public Lambda withChildren(IntList c) {
        return new Eq(c);
      }
    }

    record App(IntList children) implements Lambda {
      @Override
      public Lambda withChildren(IntList c) {
        return new App(c);
      }
    }

    /** {@code (lam x body)}. */
    record Lam(IntList children) implements Lambda {
      @Override
      public Lambda withChildren(IntList c) {
        return new Lam(c);
      }
    }

    /** {@code (let x value body)}. */
    record Let(IntList children) implements Lambda {
      @Override
      public Lambda withChildren(IntList c) {
        return new Let(c);
      }
    }

    /** {@code (fix x body)}. */
    record Fix(IntList children) implements Lambda {
      @Override
      public Lambda withChildren(IntList c) {
        return new Fix(c);
      }
    }

    record If(IntList children) implements Lambda {
      @Override
      public Lambda withChildren(IntList c) {
        return new If(c);
      }
    }
  }

  static final TreeBridge<Term, Lambda> BRIDGE = new TreeBridge<>() {
    @Override
    public List<Term> childrenOf(Term t) {
      return t.kids();
    }

    // As egg's define_language! parses: an operator at its arity, else a literal, else a
    // symbol; an operator at another arity, or an unknown one with children, is an error, so a
    // misspelt rule fails here and not as a rule that never matches. The arity is the term's:
    // a pattern's head prototype is built over no children.
    @Override
    public Lambda node(Term t, IntList children) {
      int arity = t.kids().size();
      Lambda node = switch (t.op()) {
        case "var" -> arity == 1 ? new Lambda.Var(children) : null;
        case "+" -> arity == 2 ? new Lambda.Add(children) : null;
        case "=" -> arity == 2 ? new Lambda.Eq(children) : null;
        case "app" -> arity == 2 ? new Lambda.App(children) : null;
        case "lam" -> arity == 2 ? new Lambda.Lam(children) : null;
        case "let" -> arity == 3 ? new Lambda.Let(children) : null;
        case "fix" -> arity == 2 ? new Lambda.Fix(children) : null;
        case "if" -> arity == 3 ? new Lambda.If(children) : null;
        default -> null;
      };
      if (node != null) {
        return node;
      }
      if (arity > 0) {
        throw new IllegalArgumentException("no operator " + t.op() + " of " + arity
            + " children in " + t);
      }
      return switch (t.op()) {
        case "true" -> new Lambda.Bool(true);
        case "false" -> new Lambda.Bool(false);
        default -> {
          try {
            yield new Lambda.Num(Integer.parseInt(t.op()));
          } catch (NumberFormatException notANumber) {
            yield new Lambda.Sym(t.op());
          }
        }
      };
    }

    @Override
    public Term build(Lambda node, List<Term> children) {
      String op = switch (node) {
        case Lambda.Leaf leaf -> leaf.toString();
        case Lambda.Var v -> "var";
        case Lambda.Add a -> "+";
        case Lambda.Eq e -> "=";
        case Lambda.App a -> "app";
        case Lambda.Lam l -> "lam";
        case Lambda.Let l -> "let";
        case Lambda.Fix f -> "fix";
        case Lambda.If i -> "if";
      };
      return new Term(op, children);
    }
  };

  static Pattern<Lambda> pattern(String s) {
    return Term.pattern(s, BRIDGE);
  }

  /**
   * egg's {@code Data}: the variables free in the class, as the classes of their names, and the
   * class's constant if it has one. egg's constant also carries a pattern for explanations,
   * which jegg does not port. The free set holds the ids as they were when the fact was made,
   * as egg's {@code HashSet<Id>} does; it stays exact while no two names' classes are merged,
   * which the suite's rules never do (a name is only ever merged with nothing).
   */
  record Data(Set<Integer> free, Lambda constant) {
  }

  static final Analysis<Lambda, Data> ANALYSIS = new Analysis<>() {
    @Override
    public Data make(EGraph<Lambda, Data> g, Lambda node) {
      Set<Integer> free = new TreeSet<>();
      IntList kids = node.children();
      switch (node) {
        case Lambda.Var v -> free.add(g.find(kids.get(0)));
        case Lambda.Let l -> {
          free.addAll(g.data(kids.get(2)).free());
          free.remove(g.find(kids.get(0)));
          free.addAll(g.data(kids.get(1)).free());
        }
        case Lambda.Lam _, Lambda.Fix _ -> {
          free.addAll(g.data(kids.get(1)).free());
          free.remove(g.find(kids.get(0)));
        }
        default -> {
          for (int i = 0; i < kids.size(); i++) {
            free.addAll(g.data(kids.get(i)).free());
          }
        }
      }
      return new Data(Collections.unmodifiableSet(free), eval(g, node));
    }

    // egg's eval: a literal is its own constant; + and = fold constants, + only within int.
    private Lambda eval(EGraph<Lambda, Data> g, Lambda node) {
      return switch (node) {
        case Lambda.Num n -> n;
        case Lambda.Bool b -> b;
        case Lambda.Add a -> {
          if (constant(g, a, 0) instanceof Lambda.Num x
              && constant(g, a, 1) instanceof Lambda.Num y) {
            try {
              yield new Lambda.Num(Math.addExact(x.value(), y.value()));
            } catch (ArithmeticException overflow) {
              yield null;
            }
          }
          yield null;
        }
        case Lambda.Eq e -> {
          Lambda x = constant(g, e, 0);
          Lambda y = constant(g, e, 1);
          yield x == null || y == null ? null : new Lambda.Bool(x.equals(y));
        }
        default -> null;
      };
    }

    private Lambda constant(EGraph<Lambda, Data> g, Lambda node, int child) {
      return g.data(node.children().get(child)).constant();
    }

    // As egg's merge: the free variables of both forms, so their intersection, and the one
    // constant, which two forms of one class cannot disagree on.
    @Override
    public Data join(Data a, Data b) {
      Set<Integer> free = new TreeSet<>(a.free());
      free.retainAll(b.free());
      if (a.constant() != null && b.constant() != null && !a.constant().equals(b.constant())) {
        throw new IllegalStateException("Merged non-equal constants: " + a.constant() + " and "
            + b.constant());
      }
      return new Data(Collections.unmodifiableSet(free),
          a.constant() != null ? a.constant() : b.constant());
    }

    @Override
    public void modify(EGraph<Lambda, Data> g, int id) {
      Lambda constant = g.data(id).constant();
      if (constant != null) {
        g.merge(id, g.add(constant));
      }
    }
  };

  private static Rewrite<Lambda, Data> rule(String name, String lhs, String rhs) {
    return Rewrite.of(name, pattern(lhs), pattern(rhs));
  }

  private static Condition<Lambda, Data> isNotSameVar(String v1, String v2) {
    return (g, eclass, s) -> g.find(s.idOf(v1)) != g.find(s.idOf(v2));
  }

  private static Condition<Lambda, Data> isConst(String v) {
    return (g, eclass, s) -> g.data(s.idOf(v)).constant() != null;
  }

  private static final Applier<Lambda, Data> IF_NOT_FREE =
      Applier.pattern(pattern("(lam ?v2 (let ?v1 ?e ?body))"));
  private static final Applier<Lambda, Data> IF_FREE =
      Applier.pattern(pattern("(lam ?fresh (let ?v1 ?e (let ?v2 (var ?fresh) ?body)))"));

  /**
   * egg's {@code CaptureAvoid}: pushing {@code let v1 = e} under {@code lam v2} renames v2 to a
   * fresh name first when v2 is free in e, so the binder does not capture e's v2.
   */
  private static final Applier<Lambda, Data> CAPTURE_AVOID = (g, eclass, s) -> {
    int v2 = g.find(s.idOf("v2"));
    boolean v2FreeInE = g.data(s.idOf("e")).free().stream().anyMatch(id -> g.find(id) == v2);
    if (v2FreeInE) {
      int fresh = g.add(new Lambda.Sym("_" + eclass));
      return IF_FREE.apply(g, eclass, s.bind("fresh", fresh));
    }
    return IF_NOT_FREE.apply(g, eclass, s);
  };

  /** egg's rules, by their names and in their order. */
  static List<Rewrite<Lambda, Data>> rules() {
    return List.of(
        // open term rules
        rule("if-true", "(if true ?then ?else)", "?then"),
        rule("if-false", "(if false ?then ?else)", "?else"),
        rule("if-elim", "(if (= (var ?x) ?e) ?then ?else)", "?else")
            .when(Condition.equal(pattern("(let ?x ?e ?then)"), pattern("(let ?x ?e ?else)"))),
        rule("add-comm", "(+ ?a ?b)", "(+ ?b ?a)"),
        rule("add-assoc", "(+ (+ ?a ?b) ?c)", "(+ ?a (+ ?b ?c))"),
        rule("eq-comm", "(= ?a ?b)", "(= ?b ?a)"),
        // subst rules
        rule("fix", "(fix ?v ?e)", "(let ?v (fix ?v ?e) ?e)"),
        rule("beta", "(app (lam ?v ?body) ?e)", "(let ?v ?e ?body)"),
        rule("let-app", "(let ?v ?e (app ?a ?b))", "(app (let ?v ?e ?a) (let ?v ?e ?b))"),
        rule("let-add", "(let ?v ?e (+ ?a ?b))", "(+ (let ?v ?e ?a) (let ?v ?e ?b))"),
        rule("let-eq", "(let ?v ?e (= ?a ?b))", "(= (let ?v ?e ?a) (let ?v ?e ?b))"),
        rule("let-const", "(let ?v ?e ?c)", "?c").when(isConst("c")),
        rule("let-if", "(let ?v ?e (if ?cond ?then ?else))",
            "(if (let ?v ?e ?cond) (let ?v ?e ?then) (let ?v ?e ?else))"),
        rule("let-var-same", "(let ?v1 ?e (var ?v1))", "?e"),
        rule("let-var-diff", "(let ?v1 ?e (var ?v2))", "(var ?v2)")
            .when(isNotSameVar("v1", "v2")),
        rule("let-lam-same", "(let ?v1 ?e (lam ?v1 ?body))", "(lam ?v1 ?body)"),
        Rewrite.dynamic("let-lam-diff", pattern("(let ?v1 ?e (lam ?v2 ?body))"), CAPTURE_AVOID)
            .when(isNotSameVar("v1", "v2")));
  }

  private static final String PROVED = "Proved all goals";

  /** Whether the goal pattern matches in the root's class: egg's {@code search_eclass}. */
  private static boolean proved(EGraph<Lambda, Data> g, int root, Pattern<Lambda> goal) {
    return !Matcher.matchIn(g, goal, root, Subst.EMPTY).isEmpty();
  }

  /** One run: the graph, the start term's class, the report and the goals. */
  private record Run(EGraph<Lambda, Data> graph, int root, RunReport report,
      List<Pattern<Lambda>> goals) {

    boolean proves(int goal) {
      return proved(graph, root, goals.get(goal));
    }

    /** egg's {@code check_goals}: every goal is in the start's class. */
    void checkGoals() {
      for (int i = 0; i < goals.size(); i++) {
        if (!proves(i)) {
          fail("Could not prove goal " + i + "\n" + report);
        }
      }
      graph.checkInvariants();
    }
  }

  /** egg's {@code test_runner}: the term added, the rules run until every goal is proved. */
  private static Run run(RunLimits limits, String start, String... goals) {
    EGraph<Lambda, Data> g = new EGraph<>(ANALYSIS);
    int root = g.addTree(Term.parse(start), BRIDGE);
    List<Pattern<Lambda>> patterns = java.util.Arrays.stream(goals).map(LambdaTest::pattern)
        .toList();
    RunReport report = new Runner<>(g, rules(), limits, new BackoffScheduler<>())
        .withHook(graph -> patterns.stream().allMatch(p -> proved(graph, root, p))
            ? Optional.of(PROVED) : Optional.empty())
        .run();
    return new Run(g, root, report, patterns);
  }

  /**
   * What egg 73975c9's own run of the test reports: iterations, nodes and classes. egg records
   * the iteration a hook stops as one more, so a run stopped by its goals counts one iteration
   * more there than in jegg's report.
   */
  private record Egg(int iterations, int nodes, int classes) {
  }

  /**
   * The run ends where egg's does, at the same size, which names the divergence if not. The
   * sizes are read from the graph, which is also what a report with no iterations - goals that
   * held before the first - leaves to compare.
   */
  private static void sameAsEgg(Run r, Egg egg) {
    boolean stoppedByHook = r.report().stop() instanceof StopReason.Other;
    String why = "egg reports " + egg + "\n" + r.report();
    assertEquals(egg.iterations(), r.report().size() + (stoppedByHook ? 1 : 0), why);
    assertEquals(egg.nodes(), r.graph().numNodes(), why);
    assertEquals(egg.classes(), r.graph().numClasses(), why);
  }

  private static void proves(RunLimits limits, Egg egg, String start, String... goals) {
    Run r = run(limits, start, goals);
    r.checkGoals();
    assertEquals(new StopReason.Other(PROVED), r.report().stop(), r.report().toString());
    sameAsEgg(r, egg);
  }

  /** egg's {@code should_panic(expected = "Could not prove goal 0")}: egg saturates them. */
  private static void doesNotProve(RunLimits limits, Egg egg, String start, String goal) {
    Run r = run(limits, start, goal);
    assertFalse(r.proves(0), "goal 0 proved, which egg says it must not be\n" + r.report());
    assertEquals(new StopReason.Saturated(), r.report().stop(), r.report().toString());
    sameAsEgg(r, egg);
    r.graph().checkInvariants();
  }

  private static final RunLimits DEFAULT = RunLimits.DEFAULT;

  @Test
  void lambdaUnder() {
    proves(DEFAULT, new Egg(3, 10, 7), """
        (lam x (+ 4
                  (app (lam y (var y))
                       4)))""", "(lam x 8)");
  }

  @Test
  void lambdaIfElim() {
    proves(DEFAULT, new Egg(5, 19, 8), """
        (if (= (var a) (var b))
            (+ (var a) (var a))
            (+ (var a) (var b)))""", "(+ (var a) (var b))");
  }

  @Test
  void lambdaLetSimple() {
    proves(DEFAULT, new Egg(4, 18, 8), """
        (let x 0
        (let y 1
        (+ (var x) (var y))))""", "1");
  }

  @Test
  void lambdaCapture() {
    doesNotProve(DEFAULT, new Egg(2, 5, 4), "(let x 1 (lam x (var x)))", "(lam x 1)");
  }

  @Test
  void lambdaCaptureFree() {
    doesNotProve(DEFAULT, new Egg(4, 12, 9), "(let y (+ (var x) (var x)) (lam x (var y)))",
        "(lam x (+ (var x) (var x)))");
  }

  @Test
  void lambdaClosureNotSeven() {
    doesNotProve(DEFAULT, new Egg(8, 39, 15), """
        (let five 5
        (let add-five (lam x (+ (var x) (var five)))
        (let five 6
        (app (var add-five) 1))))""", "7");
  }

  @Test
  void lambdaCompose() {
    proves(DEFAULT, new Egg(15, 78, 31), """
        (let compose (lam f (lam g (lam x (app (var f)
                                           (app (var g) (var x))))))
        (let add1 (lam y (+ (var y) 1))
        (app (app (var compose) (var add1)) (var add1))))""",
        """
        (lam ?x (+ 1
                   (app (lam ?y (+ 1 (var ?y)))
                        (var ?x))))""",
        "(lam ?x (+ (var ?x) 2))");
  }

  @Test
  void lambdaIfSimple() {
    proves(DEFAULT, new Egg(2, 6, 4), "(if (= 1 1) 7 9)", "7");
  }

  @Test
  void lambdaComposeMany() {
    proves(DEFAULT, new Egg(18, 284, 61), """
        (let compose (lam f (lam g (lam x (app (var f)
                                           (app (var g) (var x))))))
        (let add1 (lam y (+ (var y) 1))
        (app (app (var compose) (var add1))
             (app (app (var compose) (var add1))
                  (app (app (var compose) (var add1))
                       (app (app (var compose) (var add1))
                            (app (app (var compose) (var add1))
                                 (app (app (var compose) (var add1))
                                      (var add1)))))))))""",
        "(lam ?x (+ (var ?x) 7))");
  }

  @Test
  @Tag("slow")
  void lambdaFunctionRepeat() {
    // egg runs this in release builds only, with a 20 s time limit jegg does not have; here it
    // is tagged slow (see CONTRIBUTING.md), as is fib below.
    proves(DEFAULT.withIterations(60).withNodes(150_000), new Egg(59, 32636, 6825), """
        (let compose (lam f (lam g (lam x (app (var f)
                                           (app (var g) (var x))))))
        (let repeat (fix repeat (lam fun (lam n
            (if (= (var n) 0)
                (lam i (var i))
                (app (app (var compose) (var fun))
                     (app (app (var repeat)
                               (var fun))
                          (+ (var n) -1)))))))
        (let add1 (lam y (+ (var y) 1))
        (app (app (var repeat)
                  (var add1))
             2))))""", "(lam ?x (+ (var ?x) 2))");
  }

  @Test
  void lambdaIf() {
    proves(DEFAULT, new Egg(9, 42, 15), """
        (let zeroone (lam x
            (if (= (var x) 0)
                0
                1))
            (+ (app (var zeroone) 0)
            (app (var zeroone) 10)))""", "1");
  }

  @Test
  @Tag("slow")
  void lambdaFib() {
    proves(DEFAULT.withIterations(60).withNodes(500_000), new Egg(57, 14582, 4996), """
        (let fib (fix fib (lam n
            (if (= (var n) 0)
                0
            (if (= (var n) 1)
                1
            (+ (app (var fib)
                    (+ (var n) -1))
                (app (var fib)
                    (+ (var n) -2)))))))
            (app (var fib) 4))""", "3");
  }

  @Test
  void theAnalysisInvariantHoldsAfterARun() {
    Run r = run(DEFAULT, """
        (let x 0
        (let y 1
        (+ (var x) (var y))))""", "1");
    r.graph().checkAnalysisInvariant();
    assertTrue(r.proves(0));
  }

  @Test
  void aMisspeltOrMisusedOperatorIsRefusedWhenParsed() {
    assertThrows(IllegalArgumentException.class, () -> pattern("(lambda ?x ?body)"));
    assertThrows(IllegalArgumentException.class, () -> pattern("(lam ?x)"));
    assertThrows(IllegalArgumentException.class, () -> pattern("(var x y)"));
    assertThrows(IllegalArgumentException.class, () -> Term.parse("(lam x"));
    assertEquals(new Lambda.Sym("+"), BRIDGE.node(Term.parse("+"), IntList.EMPTY));
    assertEquals(new Lambda.Sym("99999999999"),
        BRIDGE.node(Term.parse("99999999999"), IntList.EMPTY));
    assertEquals(new Lambda.Num(5), BRIDGE.node(Term.parse("+5"), IntList.EMPTY));
  }

  @Test
  void aGoalThatHoldsBeforeTheFirstIterationStopsAtOnce() {
    // 1 + 2 folds to 3 as it is added, so the hook stops the run with no iteration run; egg
    // would record that as one.
    proves(DEFAULT, new Egg(1, 4, 3), "(+ 1 2)", "3");
  }
}
