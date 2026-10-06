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

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The parts of the differential fuzzing (#44) that need no egg: the generator, jegg's side of the
 * known answers, the shrinker, and the comparison. {@code dev/fuzz.sh} runs the egg side.
 */
class FuzzHarnessTest {

  @Test
  void theGeneratorIsDeterministicPerSeedAndItsTermsParseAndRun() {
    for (String language : List.of("prop", "math")) {
      List<String> a = new TermGenerator(language, 7).terms(100);
      assertEquals(a, new TermGenerator(language, 7).terms(100));
      assertNotEquals(a, new TermGenerator(language, 8).terms(100));
      assertTrue(new HashSet<>(a).size() > 50, "terms vary: " + new HashSet<>(a).size());
      String ruleset = language.equals("prop") ? "prop-all" : "math";
      for (String term : a) {
        Term.parse(term);
        String result = FuzzRun.run(new FuzzRun.Case(ruleset, term, List.of()));
        if (result.startsWith("ERROR")) {
          // Only a term that folds to false, united with true as egg's harness assumes, which
          // the analysis refuses, as egg's does.
          assertTrue(result.contains("Merged non-equal constants"), term + ": " + result);
        } else {
          FuzzRun.Result.parse(result);
        }
      }
    }
    assertThrows(IllegalArgumentException.class, () -> new TermGenerator("lambda", 1));
  }

  @Test
  void jeggsSideReproducesWhatTheTestsPinForEggsRuns() {
    // The other half of the gate dev/fuzz.sh --known runs against egg: the harness's jegg side
    // gives the pinned iterations, nodes and classes for every case egg's harness can run.
    List<FuzzRun.Known> known = new java.util.ArrayList<>(FuzzRun.knownMath());
    known.addAll(FuzzRun.knownProp());
    assertEquals(20, known.size());
    for (FuzzRun.Known k : known) {
      FuzzRun.Result r = FuzzRun.Result.parse(FuzzRun.run(k.input()));
      assertEquals(k.iterations(), r.iterations(), k.input().term());
      assertEquals(k.nodes(), r.nodes(), k.input().term());
      assertEquals(k.classes(), r.classes(), k.input().term());
    }
  }

  @Test
  void aCaseIsALineOfTabSeparatedFieldsWithTheWhitespaceCollapsed() {
    FuzzRun.Case c = new FuzzRun.Case("math", "(/ 1\n   (- a\n      b))", List.of("(* 4 x)", "1"));
    assertEquals("math\t(/ 1 (- a b))\t(* 4 x)\t1", c.line());
    assertEquals("prop-all\t(~ x)", new FuzzRun.Case("prop-all", "(~ x)", List.of()).line());
  }

  @Test
  void aResultLineParsesIntoWhatItSays() {
    FuzzRun.Result r = FuzzRun.Result.parse("IterationLimit 20 7,6;23,12 final 31,12");
    assertEquals(new FuzzRun.Result("IterationLimit", 20, "7,6;23,12", 31, 12, -1), r);
    assertEquals(new FuzzRun.Result("Saturated", 1, "-", 3, 3, -1),
        FuzzRun.Result.parse("Saturated 1 - final 3,3"));
    // egg's line also carries its memo size.
    assertEquals(new FuzzRun.Result("NodeLimit", 6, "7,6;21,10", 2813, 1075, 5100),
        FuzzRun.Result.parse("NodeLimit 6 7,6;21,10 final 2813,1075 memo 5100"));
    assertEquals(List.of("7,6", "21,10"), FuzzRun.Result.parse("NodeLimit 6 7,6;21,10 final 2,1")
        .sampleList());
  }

  @Test
  void theComparisonSeparatesTheSameFromADivergenceFromWhatSaysNothing() {
    String line = "Saturated 4 4,3;12,6 final 14,6";
    String eggLine = line + " memo 14";
    assertEquals(FuzzRun.Verdict.SAME, FuzzRun.compare(eggLine, line));
    // A different sample, stop or count is a divergence.
    assertEquals(FuzzRun.Verdict.DIVERGE,
        FuzzRun.compare(eggLine, "Saturated 4 4,3;13,6 final 14,6"));
    assertEquals(FuzzRun.Verdict.DIVERGE,
        FuzzRun.compare(eggLine, "IterationLimit 4 4,3;12,6 final 14,6"));
    // egg panicking or timing out cannot be compared; jegg throwing where egg did not, can.
    assertEquals(FuzzRun.Verdict.INCONCLUSIVE, FuzzRun.compare("PANIC", line));
    assertEquals(FuzzRun.Verdict.INCONCLUSIVE,
        FuzzRun.compare("TimeLimit 3 - final 1,1 memo 1", line));
    // Both refusing the same term, as a prop term that folds to false is, is agreement.
    assertEquals(FuzzRun.Verdict.BOTH_FAILED, FuzzRun.compare("PANIC",
        "ERROR IllegalStateException: Merged non-equal constants"));
    assertEquals(FuzzRun.Verdict.DIVERGE,
        FuzzRun.compare(eggLine, "ERROR IllegalStateException: x after 4,3;12,6"));
  }

  @Test
  void aNodeLimitStopIsAnExplainedDifferenceOnlyForTheIterationStartsItReached() {
    // One side stopped on the node limit in the iteration that ended its run; every iteration
    // start it reached is the other side's too, and the other side ran at least as long.
    String eggStopped = "NodeLimit 3 7,6;21,10;27,12 final 2813,1075 memo 5100";
    String jeggRanOn = "Saturated 5 7,6;21,10;27,12;43,20;50,20 final 61,8";
    assertEquals(FuzzRun.Verdict.NODE_LIMIT, FuzzRun.compare(eggStopped, jeggRanOn));
    // The same the other way round: jegg's size crossed first, as it can, by application order.
    assertEquals(FuzzRun.Verdict.NODE_LIMIT, FuzzRun.compare(
        "Saturated 5 7,6;21,10;27,12;43,20;50,20 final 61,8 memo 80",
        "NodeLimit 3 7,6;21,10;27,12 final 2813,1075"));
    // Both stopped on the limit in the same iteration, with partial last iterations that differ.
    assertEquals(FuzzRun.Verdict.NODE_LIMIT, FuzzRun.compare(
        "NodeLimit 3 7,6;21,10;27,12 final 5200,1075 memo 6100",
        "NodeLimit 3 7,6;21,10;27,12 final 5100,1075"));
    // Not explained: an iteration start the two do not share, before the stop.
    assertEquals(FuzzRun.Verdict.DIVERGE, FuzzRun.compare(eggStopped,
        "Saturated 5 7,6;21,10;28,12;43,20;50,20 final 61,8"));
    // Not explained: the side that stopped on the limit ran longer than the other.
    assertEquals(FuzzRun.Verdict.DIVERGE, FuzzRun.compare(eggStopped,
        "Saturated 2 7,6;21,10 final 30,8"));
    // Not explained: the stop was not the node limit (egg's iteration limit, say).
    assertEquals(FuzzRun.Verdict.DIVERGE, FuzzRun.compare(
        "IterationLimit 3 7,6;21,10;27,12 final 2813,1075 memo 5100", jeggRanOn));
    // Not explained: a side that stopped on the limit but whose iteration starts differ.
    assertEquals(FuzzRun.Verdict.DIVERGE, FuzzRun.compare(
        "NodeLimit 3 7,6;21,10;27,12 final 2813,1075 memo 5100",
        "NodeLimit 3 7,6;21,11;27,12 final 2813,1075"));
  }

  @Test
  void anErrorPastWhereEggStoppedOnTheNodeLimitIsExplainedAndOneBeforeItIsNot() {
    // egg stopped on the node limit after three iteration starts; jegg ran on and threw in a
    // later iteration (the assumption that a prop term is true can lead to true = false).
    String egg = "NodeLimit 3 7,6;21,10;27,12 final 2813,1075 memo 5100";
    assertEquals(FuzzRun.Verdict.NODE_LIMIT, FuzzRun.compare(egg,
        "ERROR IllegalStateException: Merged non-equal constants after 7,6;21,10;27,12;43,20"));
    String contradiction = "ERROR IllegalStateException: Merged non-equal constants after ";
    // In the iteration egg stopped in, too.
    assertEquals(FuzzRun.Verdict.NODE_LIMIT,
        FuzzRun.compare(egg, contradiction + "7,6;21,10;27,12"));
    // Before it: egg went on from a state jegg threw in.
    assertEquals(FuzzRun.Verdict.DIVERGE, FuzzRun.compare(egg, contradiction + "7,6;21,10"));
    // Past it but from other states: not the same run.
    assertEquals(FuzzRun.Verdict.DIVERGE,
        FuzzRun.compare(egg, contradiction + "7,6;21,10;28,12;43,20"));
    // egg stopped for another reason: jegg's error is not past a node-limit stop.
    assertEquals(FuzzRun.Verdict.DIVERGE, FuzzRun.compare(
        "Saturated 3 7,6;21,10;27,12 final 30,8 memo 40", contradiction + "7,6;21,10;27,12;43,20"));
    // Any other error is jegg's own, past egg's stop or not: a bug is not excused by a limit.
    assertEquals(FuzzRun.Verdict.DIVERGE, FuzzRun.compare(egg,
        "ERROR NullPointerException: null after 7,6;21,10;27,12;43,20"));
    assertEquals(FuzzRun.Verdict.DIVERGE, FuzzRun.compare(egg,
        "ERROR IllegalStateException: class 5 holds null where the join is true after "
            + "7,6;21,10;27,12"));
  }

  @Test
  void aMistypedRuleSetIsRefusedNotRunAsAnotherOne() {
    for (String bad : List.of("prop-allx", "math-76k", "math-75K", "lambda", "prop", "",
        "prop:def_imply,comm_orr", "math:comm-add,nope", "prop:", "math:")) {
      assertThrows(IllegalArgumentException.class,
          () -> FuzzRun.run(new FuzzRun.Case(bad, "(~ x)", List.of())), bad);
    }
    // The rule sets that exist run, a list of real rules among them.
    for (String good : List.of("prop-all", "prop-chain", "prop:def_imply,comm_or",
        "prop:lem_imply")) {
      assertFalse(FuzzRun.run(new FuzzRun.Case(good, "(~ x)", List.of())).startsWith("ERROR"),
          good);
    }
    assertFalse(FuzzRun.run(new FuzzRun.Case("math:comm-add,add-zero", "(+ x 0)", List.of()))
        .startsWith("ERROR"));
  }

  @Test
  void aGoalStopsTheRunAsEggsTestHarnessDoesAndIsCountedOneIterationMore() {
    // egg's math_diff_same: (d x x) proves 1 at the second iteration's start; egg counts 2.
    FuzzRun.Result r = FuzzRun.Result.parse(FuzzRun.run(
        new FuzzRun.Case("math", "(d x x)", List.of("1"))));
    assertEquals("Other", r.stop());
    assertEquals(2, r.iterations());
    assertEquals(5, r.nodes());
    assertEquals(3, r.classes());
    // Without the goal the same term runs to saturation.
    assertEquals("Saturated", FuzzRun.Result.parse(FuzzRun.run(
        new FuzzRun.Case("math", "(d x x)", List.of()))).stop());
  }

  @Test
  void aShrinkingStepIsStrictlySmallerDistinctAndSmallestFirst() {
    String term = "(+ (* x 1) (sqrt (- y 2)))";
    List<String> steps = FuzzShrinker.reductions(term, "math");
    assertFalse(steps.isEmpty());
    assertTrue(steps.contains("(* x 1)"), "a child hoisted: " + steps);
    assertTrue(steps.contains("(+ x (sqrt (- y 2)))"), "a subterm made a leaf: " + steps);
    assertTrue(steps.contains("(+ (* x 1) (sqrt y))"), "a subterm hoisted inside: " + steps);
    assertEquals(steps.size(), new HashSet<>(steps).size(), "distinct");
    int previous = 0;
    int original = FuzzShrinker.size(Term.parse(term));
    for (String step : steps) {
      int size = FuzzShrinker.size(Term.parse(step));
      assertTrue(size < original, step);
      assertTrue(size >= previous, "smallest first: " + steps);
      previous = size;
    }
    assertEquals(List.of(), FuzzShrinker.reductions("x", "math"));
    // A subtree becomes the language's leaves: x and true for prop, x and 1 for math.
    assertTrue(FuzzShrinker.reductions("(& x (~ y))", "prop").containsAll(
        List.of("(& x x)", "(& x true)", "(~ y)", "x")));
    assertTrue(FuzzShrinker.reductions("(+ x (~ y))", "math").containsAll(
        List.of("(+ x x)", "(+ x 1)")));
  }

  @Test
  void shrinkingAlwaysReachesALeafWhateverTheTermIs() {
    // A divergence is shrunk by following reductions until none diverges; every chain of the
    // first reduction ends at a one-node term.
    for (String term : new TermGenerator("math", 3).terms(20)) {
      String current = term;
      for (int guard = 0; guard < 100; guard++) {
        List<String> steps = FuzzShrinker.reductions(current, "math");
        if (steps.isEmpty()) {
          break;
        }
        current = steps.get(0);
      }
      assertEquals(1, FuzzShrinker.size(Term.parse(current)), term + " ended at " + current);
    }
  }

  @Test
  void theSchedulerBehavesAsEggsDoesOnTheScenariosPinnedToEggsLines() {
    // The threshold, the ban length, the fast-forward, doubling, and a conditional rule's
    // structural matches counted toward the ban, each against the line egg printed (#75).
    List<FuzzRun.Pinned> pinned = FuzzRun.schedulerScenarios();
    assertEquals(14, pinned.size());
    for (FuzzRun.Pinned p : pinned) {
      assertEquals(p.egg(), FuzzRun.run(p.input()), p.input().ruleset() + " " + p.input().term());
    }
  }

  @Test
  void aSchedulerSuffixNeedsTwoPositiveNumbers() {
    for (String bad : List.of("math@1", "math@1,2,3", "math@0,5", "math@5,0", "math@a,b",
        "math@-1,2", "math:comm-add@", "prop-all@1,")) {
      assertThrows(IllegalArgumentException.class,
          () -> FuzzRun.run(new FuzzRun.Case(bad, "(+ x y)", List.of())), bad);
    }
    assertEquals(new FuzzRun.Parsed("math:comm-add", 3, 4), FuzzRun.parse("math:comm-add@3,4"));
    assertEquals(new FuzzRun.Parsed("math", 0, 0), FuzzRun.parse("math"));
  }
}
