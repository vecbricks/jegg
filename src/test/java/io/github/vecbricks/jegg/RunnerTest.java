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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    assertTrue(scheduler.timesBanned(1) >= 1);
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
}
