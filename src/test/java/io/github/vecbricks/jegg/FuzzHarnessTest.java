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
    assertEquals(FuzzRun.Verdict.SAME, FuzzRun.compare(eggLine, line, 5_000));
    // A different sample, stop or count is a divergence.
    assertEquals(FuzzRun.Verdict.DIVERGE,
        FuzzRun.compare(eggLine, "Saturated 4 4,3;13,6 final 14,6", 5_000));
    assertEquals(FuzzRun.Verdict.DIVERGE,
        FuzzRun.compare(eggLine, "IterationLimit 4 4,3;12,6 final 14,6", 5_000));
    // egg panicking or timing out cannot be compared; jegg throwing where egg did not, can.
    assertEquals(FuzzRun.Verdict.INCONCLUSIVE, FuzzRun.compare("PANIC", line, 5_000));
    assertEquals(FuzzRun.Verdict.INCONCLUSIVE,
        FuzzRun.compare("TimeLimit 3 - final 1,1 memo 1", line, 5_000));
    // Both refusing the same term, as a prop term that folds to false is, is agreement.
    assertEquals(FuzzRun.Verdict.BOTH_FAILED, FuzzRun.compare("PANIC",
        "ERROR IllegalStateException: Merged non-equal constants", 5_000));
    assertEquals(FuzzRun.Verdict.DIVERGE,
        FuzzRun.compare(eggLine, "ERROR IllegalStateException: x", 5_000));
  }

  @Test
  void eggsMemoSizedNodeLimitIsAnExplainedDifferenceOnlyWhenTheMemoExplainsIt() {
    // egg stops on a limit its nodes (2813) have not reached because its memo (5100) has; jegg,
    // counting distinct nodes, runs on past the iteration egg stopped in.
    String egg = "NodeLimit 3 7,6;21,10;27,12 final 2813,1075 memo 5100";
    String jegg = "Saturated 5 7,6;21,10;27,12;43,20;50,20 final 61,8";
    assertEquals(FuzzRun.Verdict.MEMO_LIMIT, FuzzRun.compare(egg, jegg, 5_000));
    // Not explained: the memo is under the limit, so egg's stop was not the memo's.
    assertEquals(FuzzRun.Verdict.DIVERGE, FuzzRun.compare(
        "NodeLimit 3 7,6;21,10;27,12 final 2813,1075 memo 4900", jegg, 5_000));
    // Not explained: egg's own nodes are over the limit, so jegg crossed it too.
    assertEquals(FuzzRun.Verdict.DIVERGE, FuzzRun.compare(
        "NodeLimit 3 7,6;21,10;27,12 final 5200,1075 memo 6100", jegg, 5_000));
    // Not explained: an iteration start the two do not share, before egg stopped.
    assertEquals(FuzzRun.Verdict.DIVERGE, FuzzRun.compare(egg,
        "Saturated 5 7,6;21,10;28,12;43,20;50,20 final 61,8", 5_000));
    // Not explained: jegg stopped sooner than egg.
    assertEquals(FuzzRun.Verdict.DIVERGE, FuzzRun.compare(egg,
        "Saturated 2 7,6;21,10 final 30,8", 5_000));
    // Not a node-limit stop of egg's.
    assertEquals(FuzzRun.Verdict.DIVERGE, FuzzRun.compare(
        "IterationLimit 3 7,6;21,10;27,12 final 2813,1075 memo 5100", jegg, 5_000));
    assertEquals(5_000, FuzzRun.nodeLimit("prop-all"));
    assertEquals(10_000, FuzzRun.nodeLimit("math"));
    assertEquals(75_000, FuzzRun.nodeLimit("math-75k"));
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
}
