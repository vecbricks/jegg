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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RunnerTest {

  private static Pattern<Toy> v(String n) {
    return Pattern.var(n);
  }

  private static Pattern<Toy> add(Pattern<Toy> a, Pattern<Toy> b) {
    return Pattern.of(new Toy.Add(IntList.EMPTY), a, b);
  }

  /** Associativity and commutativity of +: the rules that grow a graph without bound. */
  private static List<Rewrite<Toy, Void>> expansive() {
    return List.of(
        Rewrite.of("assoc-add", add(add(v("a"), v("b")), v("c")), add(v("a"), add(v("b"), v("c")))),
        Rewrite.of("commute-add", add(v("a"), v("b")), add(v("b"), v("a"))));
  }

  /** a + b + c + d + e as a left-leaning tree; the root's class. */
  private static int sumOfFive(EGraph<Toy, Void> g) {
    int acc = g.add(new Toy.Var("a"));
    for (String name : List.of("b", "c", "d", "e")) {
      acc = g.add(new Toy.Add(IntList.of(acc, g.add(new Toy.Var(name)))));
    }
    return acc;
  }

  @Test
  void theSimpleRulesSaturateAndTheReportSaysSo() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int foo = g.add(new Toy.Var("foo"));
    int root = g.add(new Toy.Add(IntList.of(g.add(new Toy.Num(0)),
        g.add(new Toy.Mul(IntList.of(g.add(new Toy.Num(1)), foo))))));
    RunReport report = Runner.of(g, SimpleRulesTest.rules()).run();
    assertInstanceOf(StopReason.Saturated.class, report.stop());
    assertEquals(g.find(foo), g.find(root));
    assertTrue(report.size() >= 2 && report.size() <= 5, report.toString());
    assertEquals(0, report.iterations().get(report.size() - 1).unions());
    g.checkInvariants();
    assertThrows(UnsupportedOperationException.class,
        () -> report.iterations().get(0).matches().put("forged", 1));
  }

  @Test
  void aNodeLimitStopsTheRunAtTheSameGraphEveryTime() {
    // Five summands under associativity and commutativity, with a node limit below what the
    // first iterations add: the run stops on it, and three runs stop at the same iteration with
    // the same classes and nodes - the determinism the plan's 3.1 fixes. (Over a fixed set of
    // leaves the two rules do saturate, in a few hundred nodes, so the limit is what stops it.)
    RunReport first = null;
    for (int run = 0; run < 3; run++) {
      EGraph<Toy, Void> g = EGraph.withoutAnalysis();
      sumOfFive(g);
      RunReport report = new Runner<>(g, expansive(), RunLimits.DEFAULT.withNodes(25),
          Scheduler.simple()).run();
      assertInstanceOf(StopReason.NodeLimit.class, report.stop(), report.toString());
      assertTrue(g.numNodes() > 25);
      g.checkInvariants();
      if (first == null) {
        first = report;
      } else {
        assertEquals(first.toString(), report.toString());
      }
    }
  }

  @Test
  void anIterationLimitStopsTheRun() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    sumOfFive(g);
    RunReport report = new Runner<>(g, expansive(), new RunLimits(3, 1_000_000, 1_000_000),
        Scheduler.simple()).run();
    assertEquals(new StopReason.IterationLimit(3), report.stop());
    assertEquals(3, report.size());
  }

  @Test
  void theBackoffSchedulerBansAnExpansiveRuleAndReadmitsIt() {
    // A match limit of 2: commutativity's matches pass it at once on the five-summand tree, so
    // the rule is banned for two iterations and runs again after; its match count in the report
    // reads zero while banned. The run still ends on its limits, as the rules never saturate.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    sumOfFive(g);
    BackoffScheduler<Toy, Void> scheduler = new BackoffScheduler<>(2, 2);
    RunReport report = new Runner<>(g, expansive(), RunLimits.DEFAULT.withIterations(8)
        .withNodes(50_000), scheduler).run();
    List<Integer> commute = report.iterations().stream()
        .map(it -> it.matches().get("commute-add")).toList();
    assertEquals(0, commute.get(0), "banned in the iteration its matches passed the limit");
    assertTrue(report.iterations().get(0).banned().contains("commute-add"),
        "the report says why the count is zero: " + report);
    assertTrue(scheduler.timesBanned(1) >= 1);
    assertFalse(scheduler.isBanned(1, 1_000_000), "a rule never searched is not banned");
    assertEquals(0, scheduler.timesBanned(1_000_000));
    assertFalse(scheduler.isBanned(1, -1));
    assertEquals(0, scheduler.timesBanned(-1));
    assertTrue(commute.stream().anyMatch(n -> n > 0), "never readmitted: " + report);
    assertTrue(commute.stream().filter(n -> n == 0).count() >= 2, report.toString());
  }

  @Test
  void aHeldBackRuleKeepsTheRunFromReportingSaturation() {
    // One rule, commutativity, on a single sum, with a match limit it exceeds at once: the
    // first iteration applies nothing and would read as saturated, but the scheduler holds the
    // ban, so the runner goes on, releases it, applies the rule, and saturates only then.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    g.add(new Toy.Add(IntList.of(a, b)));
    g.add(new Toy.Add(IntList.of(b, g.add(new Toy.Var("c")))));
    List<Rewrite<Toy, Void>> commute = List.of(expansive().get(1));
    RunReport report = new Runner<>(g, commute, RunLimits.DEFAULT, new BackoffScheduler<>(1, 3))
        .run();
    assertInstanceOf(StopReason.Saturated.class, report.stop(), report.toString());
    assertTrue(report.iterations().get(0).applied() == 0, report.toString());
    assertTrue(report.iterations().stream().anyMatch(it -> it.unions() > 0), report.toString());
    assertTrue(g.classOf(g.add(new Toy.Add(IntList.of(a, b)))).nodes()
        .contains(new Toy.Add(IntList.of(b, a))));
  }

  @Test
  void anApplierThatOnlyAddsKeepsTheRunGoing() {
    // "seed" adds a * a under a's class but returns nothing to union, so its iteration has no
    // unions; the graph grew, though, and the next iteration's x * x => x has work to do.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    List<Rewrite<Toy, Void>> rules = List.of(
        Rewrite.dynamic("seed", Pattern.of(new Toy.Var("a")), (graph, eclass, subst) -> {
          graph.add(new Toy.Mul(IntList.of(eclass, eclass)));
          return IntList.EMPTY;
        }),
        Rewrite.of("mul-self", Pattern.of(new Toy.Mul(IntList.EMPTY), v("x"), v("x")), v("x")));
    RunReport report = new Runner<>(g, rules, RunLimits.DEFAULT, Scheduler.simple()).run();
    assertInstanceOf(StopReason.Saturated.class, report.stop(), report.toString());
    assertTrue(report.size() >= 2, report.toString());
    assertEquals(g.find(a), g.find(g.add(new Toy.Mul(IntList.of(a, a)))));
    g.checkInvariants();
  }

  @Test
  void aConditionIsCheckedAgainWhenItsMatchIsApplied() {
    // "mark" holds only while x and y are apart, which is true when it is searched; "zero",
    // applied before it in the same iteration, merges them, so "mark" must not fire.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int zero = g.add(new Toy.Num(0));
    g.add(new Toy.Mul(IntList.of(a, zero)));
    List<Rewrite<Toy, Void>> rules = List.of(
        Rewrite.of("zero", Pattern.of(new Toy.Var("a")), Pattern.of(new Toy.Num(0))),
        Rewrite.<Toy, Void>of("mark", Pattern.of(new Toy.Mul(IntList.EMPTY), v("x"), v("y")),
            Pattern.of(new Toy.Var("marked")))
            .when((graph, eclass, subst) -> graph.find(subst.idOf("x"))
                != graph.find(subst.idOf("y"))));
    RunReport report = new Runner<>(g, rules, RunLimits.DEFAULT, Scheduler.simple()).run();
    assertInstanceOf(StopReason.Saturated.class, report.stop(), report.toString());
    assertEquals(g.find(a), g.find(zero));
    assertFalse(g.lookup(new Toy.Var("marked")).isPresent(), report.toString());
    assertEquals(1, report.iterations().get(0).applied(), "only zero was applied: " + report);
  }

  @Test
  void aBanThatWouldOverflowSaturatesInstead() {
    // A ban of 2^30 iterations doubles to 2^31 on the second ban: past int, so it must hold at
    // the longest ban rather than wrap negative and lift it.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    sumOfFive(g);
    Rewrite<Toy, Void> commute = expansive().get(1);
    BackoffScheduler<Toy, Void> scheduler = new BackoffScheduler<>(1, 1 << 30);
    assertEquals(List.of(), scheduler.search(1, 0, commute, g));
    assertTrue(scheduler.isBanned(2, 0));
    assertFalse(scheduler.canStop(1));
    assertEquals(List.of(), scheduler.search(2, 0, commute, g));
    assertEquals(2, scheduler.timesBanned(0));
    assertTrue(scheduler.isBanned(3, 0));
    assertTrue(scheduler.isBanned(Integer.MAX_VALUE - 1, 0));
  }

  @Test
  void twoRulesWithOneNameAreRefused() {
    // The report keys match counts by name; a shared name would hide one rule's count.
    List<Rewrite<Toy, Void>> rules = List.of(expansive().get(1),
        Rewrite.of("commute-add", add(v("x"), v("y")), add(v("y"), v("x"))));
    assertThrows(IllegalArgumentException.class,
        () -> Runner.of(EGraph.<Toy>withoutAnalysis(), rules));
  }

  @Test
  void aRunStartsWithNoBansFromAnEarlierRun() {
    // Runner A, on the five-summand sum, bans its rule 1, commutativity (four matches past a
    // limit of three), for a hundred iterations, while associativity (three) changes the graph.
    // Runner B shares the scheduler; its rule 1 is a different rule with a single match, which
    // must not inherit A's ban by position: B's first iteration applies it.
    BackoffScheduler<Toy, Void> scheduler = new BackoffScheduler<>(3, 100);
    EGraph<Toy, Void> first = EGraph.withoutAnalysis();
    sumOfFive(first);
    new Runner<>(first, expansive(), RunLimits.DEFAULT.withIterations(1), scheduler).run();
    assertEquals(1, scheduler.timesBanned(1));
    EGraph<Toy, Void> second = EGraph.withoutAnalysis();
    int a = second.add(new Toy.Var("a"));
    int zero = second.add(new Toy.Num(0));
    int sum = second.add(new Toy.Add(IntList.of(a, zero)));
    List<Rewrite<Toy, Void>> rules = List.of(
        Rewrite.of("unused", Pattern.of(new Toy.Num(7)), Pattern.of(new Toy.Num(7))),
        Rewrite.of("add-0", add(v("x"), Pattern.of(new Toy.Num(0))), v("x")));
    RunReport report = new Runner<>(second, rules, RunLimits.DEFAULT, scheduler).run();
    assertEquals(1, report.iterations().get(0).matches().get("add-0"), report.toString());
    assertEquals(second.find(a), second.find(sum));
  }

  @Test
  void aHookStopsTheRunBeforeAnIterationWithItsReason() {
    // The hook sees the rebuilt graph before each iteration; on the third it asks to stop, so
    // two iterations ran and the report says why.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    sumOfFive(g);
    int[] calls = {0};
    RunReport report = new Runner<>(g, expansive(), RunLimits.DEFAULT, Scheduler.simple())
        .withHook(graph -> {
          assertFalse(graph.isDirty());
          return ++calls[0] == 3 ? java.util.Optional.of("enough") : java.util.Optional.empty();
        })
        .run();
    assertEquals(new StopReason.Other("enough"), report.stop());
    assertEquals(2, report.size());
    assertEquals(3, calls[0]);
  }

  @Test
  void theSchedulerIsAskedWheneverNothingMergedEvenIfNodesWereAdded() {
    // As egg: an iteration whose applier adds a node without a union asks the scheduler, which
    // may release its bans then, but is not saturation, since the graph grew.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    g.add(new Toy.Var("a"));
    List<Integer> asked = new java.util.ArrayList<>();
    Scheduler<Toy, Void> recording = new Scheduler<>() {
      @Override
      public List<Matcher.Match> search(int iteration, int ruleIndex, Rewrite<Toy, Void> rule,
          EGraph<Toy, Void> graph) {
        return rule.search(graph);
      }

      @Override
      public boolean canStop(int iteration) {
        asked.add(iteration);
        return true;
      }
    };
    List<Rewrite<Toy, Void>> rules = List.of(
        Rewrite.dynamic("seed", Pattern.of(new Toy.Var("a")), (graph, eclass, subst) -> {
          graph.add(new Toy.Mul(IntList.of(eclass, eclass)));
          return IntList.EMPTY;
        }));
    RunReport report = new Runner<>(g, rules, RunLimits.DEFAULT, recording).run();
    assertEquals(List.of(1, 2), asked, report.toString());
    assertEquals(2, report.size(), "iteration 1 grew the graph, so only iteration 2 saturates");
    assertInstanceOf(StopReason.Saturated.class, report.stop());
  }

  @Test
  void anIterationWhoseHookAddedToTheGraphIsNotSaturation() {
    // No rules; the hook adds a node before each of the first three iterations. Those are not
    // saturation, as egg's are not: the fourth, which it leaves alone, is.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    g.add(new Toy.Var("a"));
    int[] calls = {0};
    RunReport report = new Runner<>(g, List.of(), RunLimits.DEFAULT, Scheduler.simple())
        .withHook(graph -> {
          if (calls[0]++ < 3) {
            graph.add(new Toy.Num(calls[0]));
          }
          return java.util.Optional.empty();
        })
        .run();
    assertInstanceOf(StopReason.Saturated.class, report.stop(), report.toString());
    assertEquals(4, report.size(), report.toString());
    assertEquals(4, g.numNodes());
  }

  @Test
  void aHookThatMergesLeavesARebuiltGraphToTheSearch() {
    // a + b and b + a are two classes; the hook merges a and b without rebuilding. The runner
    // rebuilds before searching, so commutativity sees one class holding one node, a + a, and
    // matches once, not twice on a stale pair.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    g.add(new Toy.Add(IntList.of(a, b)));
    g.add(new Toy.Add(IntList.of(b, a)));
    int[] calls = {0};
    RunReport report = new Runner<>(g, List.of(expansive().get(1)), RunLimits.DEFAULT,
        Scheduler.simple())
        .withHook(graph -> {
          if (calls[0]++ == 0) {
            graph.merge(a, b);
          }
          return java.util.Optional.empty();
        })
        .run();
    assertEquals(1, report.iterations().get(0).matches().get("commute-add"), report.toString());
    assertEquals(0, report.iterations().get(0).unions(), report.toString());
    assertInstanceOf(StopReason.Saturated.class, report.stop(), report.toString());
    g.checkInvariants();
  }

  @Test
  void anAddAndAMergeThatLeaveTheSizesAsTheyWereAreNotSaturation() {
    // a and b apart, f(a) and f(b) one class: "join" merges a and b through the graph (so the
    // rebuild drops a node), "seed" adds a leaf c, and the sizes are as they were - 3 classes,
    // 4 nodes - while the graph changed. Reading sizes, the run would stop here; c -> d would
    // never fire.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    g.merge(g.add(new Toy.Mul(IntList.of(a, a))), g.add(new Toy.Mul(IntList.of(b, b))));
    g.rebuild();
    assertEquals(3, g.numClasses());
    assertEquals(4, g.numNodes());
    List<Rewrite<Toy, Void>> rules = List.of(
        Rewrite.dynamic("join", Pattern.of(new Toy.Var("a")), (graph, _, _) -> {
          graph.merge(a, b);
          return IntList.EMPTY;
        }),
        Rewrite.dynamic("seed", Pattern.of(new Toy.Var("b")), (graph, _, _) -> {
          graph.add(new Toy.Var("c"));
          return IntList.EMPTY;
        }),
        Rewrite.of("c-to-d", Pattern.of(new Toy.Var("c")), Pattern.of(new Toy.Var("d"))));
    RunReport report = new Runner<>(g, rules, RunLimits.DEFAULT, Scheduler.simple()).run();
    RunReport.Iteration first = report.iterations().get(0);
    assertEquals(3, first.classes(), report.toString());
    assertEquals(4, first.nodes(), report.toString());
    assertTrue(report.size() >= 2, "stopped after an iteration that changed the graph:\n" + report);
    assertTrue(g.lookup(new Toy.Var("d")).isPresent(), "c -> d never fired:\n" + report);
    assertInstanceOf(StopReason.Saturated.class, report.stop());
  }

  @Test
  void theNodeLimitIsCheckedAfterEachRuleNotOnlyAfterTheIteration() {
    // Associativity over eight summands passes a node limit of 20 in its first iteration; the
    // rule after it, which would add a marker, must then not be applied in that iteration, as
    // egg checks its limits between rules. Checked only after the iteration, the marker would
    // be there.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int acc = g.add(new Toy.Var("a"));
    for (String name : List.of("b", "c", "d", "e", "f", "g", "h")) {
      acc = g.add(new Toy.Add(IntList.of(acc, g.add(new Toy.Var(name)))));
    }
    List<Rewrite<Toy, Void>> rules = List.of(expansive().get(0),
        Rewrite.dynamic("mark", Pattern.of(new Toy.Var("a")), (graph, _, _) -> {
          graph.add(new Toy.Var("marker"));
          return IntList.EMPTY;
        }));
    RunReport report = new Runner<>(g, rules, RunLimits.DEFAULT.withNodes(20),
        Scheduler.simple()).run();
    assertInstanceOf(StopReason.NodeLimit.class, report.stop(), report.toString());
    assertEquals(1, report.size(), report.toString());
    assertTrue(g.numNodes() > 20);
    assertTrue(g.lookup(new Toy.Var("marker")).isEmpty(), "the rule after the limit was applied:\n"
        + report);
    assertEquals(new StopReason.NodeLimit(g.numNodes()), report.stop(), "the settled size");
    assertEquals(java.util.Set.of("mark"), report.iterations().get(0).skipped());
    g.checkInvariants();
  }

  @Test
  void aLimitPassedStopsTheRunEvenIfTheRebuildBringsTheSizeBackUnderIt() {
    // "blow" adds eight sums a + b_i and merges the b_i, so before the rebuild the graph has 17
    // nodes, past the limit of 10, and after it 10, as the sums collapse to one. The rule after
    // it was skipped on the limit's account, so the run must stop and say so, not go on as if
    // nothing had happened; the reason carries the size that passed.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int[] bs = new int[8];
    for (int i = 0; i < bs.length; i++) {
      bs[i] = g.add(new Toy.Var("b" + i));
    }
    List<Rewrite<Toy, Void>> rules = List.of(
        Rewrite.dynamic("blow", Pattern.of(new Toy.Var("a")), (graph, _, _) -> {
          for (int b : bs) {
            graph.add(new Toy.Add(IntList.of(a, b)));
          }
          for (int b : bs) {
            graph.merge(bs[0], b);
          }
          return IntList.EMPTY;
        }),
        Rewrite.dynamic("mark", Pattern.of(new Toy.Var("a")), (graph, _, _) -> {
          graph.add(new Toy.Var("marker"));
          return IntList.EMPTY;
        }));
    RunReport report = new Runner<>(g, rules, RunLimits.DEFAULT.withNodes(10),
        Scheduler.simple()).run();
    assertEquals(10, g.numNodes(), report.toString());
    assertEquals(1, report.size(), report.toString());
    assertEquals(new StopReason.NodeLimit(17), report.stop(), report.toString());
    assertEquals(java.util.Set.of("mark"), report.iterations().get(0).skipped());
    assertTrue(g.lookup(new Toy.Var("marker")).isEmpty());
    g.checkInvariants();
  }

  @Test
  void aClassLimitStopsTheRun() {
    // The five-summand sum has nine classes; associativity and commutativity add more each
    // iteration, so a limit of twelve classes stops the run on them, as the node limit does.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    sumOfFive(g);
    assertEquals(9, g.numClasses());
    RunReport report = new Runner<>(g, expansive(), RunLimits.DEFAULT.withClasses(12),
        Scheduler.simple()).run();
    assertInstanceOf(StopReason.ClassLimit.class, report.stop(), report.toString());
    assertEquals(new StopReason.ClassLimit(g.numClasses()), report.stop());
    assertTrue(g.numClasses() > 12);
    assertSame(g, new Runner<>(g, expansive(), RunLimits.DEFAULT, Scheduler.simple()).graph());
  }

  @Test
  void limitsAndBansMustBePositive() {
    assertThrows(IllegalArgumentException.class, () -> new RunLimits(0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> RunLimits.DEFAULT.withNodes(-5));
    assertThrows(IllegalArgumentException.class, () -> new BackoffScheduler<Toy, Void>(0, 5));
    assertThrows(IllegalArgumentException.class, () -> new BackoffScheduler<Toy, Void>(5, 0));
  }

  @Test
  void aBannedRuleIsSearchedOnlyPastItsThreshold() {
    // Commutativity on the five-summand sum has four matches. With a match limit of two the
    // scheduler bans it, having searched for three matches and no further: the head was asked
    // fewer nodes than a full search asks, as egg's search_with_limit does.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    sumOfFive(g);
    class Counting implements Pattern.Head<Toy> {
      final Pattern.Head<Toy> head = Pattern.head(new Toy.Add(IntList.EMPTY));
      int asked;

      @Override
      public Subst match(Toy node, Subst subst) {
        asked++;
        return head.match(node, subst);
      }

      @Override
      public Toy build(Subst subst, IntList children) {
        return head.build(subst, children);
      }

      @Override
      public java.util.Optional<java.util.Set<String>> variables() {
        return head.variables();
      }
    }
    Counting full = new Counting();
    Rewrite<Toy, Void> commute = Rewrite.of("commute", Pattern.node(full, v("a"), v("b")),
        add(v("b"), v("a")));
    assertEquals(4, commute.search(g).size());
    int fullyAsked = full.asked;
    Counting limited = new Counting();
    Rewrite<Toy, Void> commuteLimited = Rewrite.of("commute",
        Pattern.node(limited, v("a"), v("b")), add(v("b"), v("a")));
    BackoffScheduler<Toy, Void> scheduler = new BackoffScheduler<>(2, 5);
    assertEquals(List.of(), scheduler.search(1, 0, commuteLimited, g));
    assertEquals(1, scheduler.timesBanned(0));
    assertTrue(limited.asked < fullyAsked, limited.asked + " of " + fullyAsked + " nodes asked");
  }
}
