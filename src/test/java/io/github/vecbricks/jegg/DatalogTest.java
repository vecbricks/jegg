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
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * egg's {@code tests/datalog.rs}, ported: an e-graph as a Datalog engine. Facts are relation
 * nodes {@code (edge 1 2)} united with {@code true}; the rules are multi-patterns that join facts
 * on shared variables and derive {@code (path a c)}; the tests check the transitive closure.
 * Each run is pinned to egg's own iteration, node and class counts: egg's multi-pattern counts
 * every match as applied, so neither run saturates and both end at the iteration limit.
 */
class DatalogTest {

  sealed interface Lang extends Language<Lang> permits Lang.True, Lang.Int, Lang.Rel {
    /** egg's {@code "true"}. */
    record True() implements Lang {
      @Override
      public IntList children() {
        return IntList.EMPTY;
      }

      @Override
      public Lang withChildren(IntList c) {
        return this;
      }
    }

    /** egg's {@code Int(i32)}. */
    record Int(int value) implements Lang {
      @Override
      public IntList children() {
        return IntList.EMPTY;
      }

      @Override
      public Lang withChildren(IntList c) {
        return this;
      }
    }

    /** egg's {@code Relation(Symbol, Box<[Id]>)}: a name and any number of children. */
    record Rel(String name, IntList children) implements Lang {
      @Override
      public Lang withChildren(IntList c) {
        return new Rel(name, c);
      }
    }
  }

  static final TreeBridge<Term, Lang> BRIDGE = new TreeBridge<>() {
    @Override
    public List<Term> childrenOf(Term t) {
      return t.kids();
    }

    @Override
    public Lang node(Term t, IntList children) {
      if (t.op().equals("true")) {
        return new Lang.True();
      }
      if (t.op().matches("-?\\d+")) {
        return new Lang.Int(Integer.parseInt(t.op()));
      }
      return new Lang.Rel(t.op(), children);
    }

    @Override
    public Term build(Lang node, List<Term> children) {
      return switch (node) {
        case Lang.True t -> new Term("true", List.of());
        case Lang.Int i -> new Term(String.valueOf(i.value()), List.of());
        case Lang.Rel r -> new Term(r.name(), children);
      };
    }
  };

  static Rewrite<Lang, Void> rule(String name, String lhs, String rhs) {
    return Rewrite.multi(name, MultiTerm.parse(lhs, BRIDGE), MultiTerm.parse(rhs, BRIDGE));
  }

  /** What egg 73975c9's own run reports: iterations, nodes in classes, classes. */
  record Egg(int iterations, int nodes, int classes) {
  }

  /**
   * One of egg's tests: its rules, its facts, what must hold and what must not after the run,
   * and egg's counts.
   */
  record Case(String name, List<Rewrite<Lang, Void>> rules, String facts, List<String> holds,
      List<String> doesNotHold, Egg egg) {
  }

  /** egg's {@code path}: facts are united with {@code true}, and the paths are too. */
  static final Case PATH = new Case("path",
      List.of(
          rule("base-case", "?x = true = (edge ?a ?b)", "?x = (path ?a ?b)"),
          rule("transitive", "?x = true = (path ?a ?b) = (edge ?b ?c)", "?x = (path ?a ?c)")),
      "(edge 1 2), (edge 2 3), (edge 3 4)", List.of("(path 1 4)"), List.of("(path 4 1)"),
      new Egg(30, 14, 5));

  /**
   * egg's {@code path2}: a {@code pred} wrapper inserts a fact without making it true, and the
   * rules join facts in different classes.
   */
  static final Case PATH2 = new Case("path2",
      List.of(
          rule("base-case", "?x = (edge ?a ?b), ?t = true", "?t = (pred (path ?a ?b))"),
          rule("transitive", "?x = (path ?a ?b), ?y = (edge ?b ?c), ?t = true",
              "?t = (pred (path ?a ?c))")),
      "(edge 1 2), (edge 2 3), (edge 3 4), (edge 1 4)",
      List.of("(pred (path 1 4))", "(pred (path 2 3))"),
      List.of("(pred (path 4 1))", "(pred (path 3 1))"), new Egg(30, 21, 11));

  static final List<Case> CASES = List.of(PATH, PATH2);

  private static boolean isTrue(EGraph<Lang, Void> g, int truth, String term) {
    OptionalInt id = g.lookupTree(Term.parse(term), BRIDGE);
    return id.isPresent() && g.find(id.getAsInt()) == g.find(truth);
  }

  private static void check(Case c) {
    EGraph<Lang, Void> g = EGraph.withoutAnalysis();
    int truth = g.add(new Lang.True());
    for (String fact : c.facts().split(",")) {
      g.merge(truth, g.addTree(Term.parse(fact.trim()), BRIDGE));
    }
    RunReport report = Runner.of(g, c.rules()).run();
    String why = "egg reports " + c.egg() + "\n" + report;
    assertEquals(new StopReason.IterationLimit(30), report.stop(), why);
    assertEquals(c.egg().iterations(), report.size(), why);
    assertEquals(c.egg().nodes(), g.numNodes(), why);
    assertEquals(c.egg().classes(), g.numClasses(), why);
    for (String holds : c.holds()) {
      assertTrue(isTrue(g, truth, holds), holds + " is not true\n" + report);
    }
    for (String not : c.doesNotHold()) {
      assertFalse(isTrue(g, truth, not), not + " is true\n" + report);
    }
    g.checkInvariants();
  }

  @Test
  void path() {
    check(PATH);
  }

  @Test
  void path2() {
    check(PATH2);
  }

  @Test
  void theRulesAreMultiPatternsAndTheClosureIsWhatTheyDerive() {
    // Fewer edges, fewer paths: (path 1 4) needs the whole chain, and (path 2 4) is derived too.
    EGraph<Lang, Void> g = EGraph.withoutAnalysis();
    int truth = g.add(new Lang.True());
    for (String fact : List.of("(edge 1 2)", "(edge 2 3)")) {
      g.merge(truth, g.addTree(Term.parse(fact), BRIDGE));
    }
    Runner.of(g, PATH.rules()).run();
    assertTrue(isTrue(g, truth, "(path 1 3)"));
    assertFalse(g.lookupTree(Term.parse("(path 1 4)"), BRIDGE).isPresent());
  }
}
