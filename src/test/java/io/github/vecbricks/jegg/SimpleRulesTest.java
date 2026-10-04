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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * egg's {@code tests/simple.rs}, ported: a language of numbers, symbols, {@code +} and
 * {@code *}, five rules, and the check that {@code (+ 0 (* 1 foo))} saturates into the class of
 * {@code foo}. The runner is the next step; here the saturation loop is written out, reading
 * every match before applying any and rebuilding once per iteration.
 */
class SimpleRulesTest {

  private static Pattern<Toy> v(String name) {
    return Pattern.var(name);
  }

  private static Pattern<Toy> add(Pattern<Toy> a, Pattern<Toy> b) {
    return Pattern.of(new Toy.Add(IntList.EMPTY), a, b);
  }

  private static Pattern<Toy> mul(Pattern<Toy> a, Pattern<Toy> b) {
    return Pattern.of(new Toy.Mul(IntList.EMPTY), a, b);
  }

  private static Pattern<Toy> num(long n) {
    return Pattern.of(new Toy.Num(n));
  }

  /** egg's `make_rules`. */
  static List<Rewrite<Toy, Void>> rules() {
    return List.of(
        Rewrite.of("commute-add", add(v("a"), v("b")), add(v("b"), v("a"))),
        Rewrite.of("commute-mul", mul(v("a"), v("b")), mul(v("b"), v("a"))),
        Rewrite.of("add-0", add(v("a"), num(0)), v("a")),
        Rewrite.of("mul-0", mul(v("a"), num(0)), num(0)),
        Rewrite.of("mul-1", mul(v("a"), num(1)), v("a")));
  }

  /** The saturation loop: at most {@code limit} iterations; returns how many ran. */
  static <L extends Language<L>, D> int saturate(EGraph<L, D> g, List<Rewrite<L, D>> rules,
      int limit) {
    for (int iteration = 1; iteration <= limit; iteration++) {
      List<List<Matcher.Match>> matches = new ArrayList<>();
      for (Rewrite<L, D> rule : rules) {
        matches.add(rule.search(g));
      }
      int changed = 0;
      for (int i = 0; i < rules.size(); i++) {
        for (Matcher.Match m : matches.get(i)) {
          changed += rules.get(i).apply(g, m).orElse(0);
        }
      }
      g.rebuild();
      if (changed == 0) {
        return iteration;
      }
    }
    return limit;
  }

  @Test
  void zeroPlusOneTimesFooSaturatesToFoo() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int foo = g.add(new Toy.Var("foo"));
    int one = g.add(new Toy.Num(1));
    int zero = g.add(new Toy.Num(0));
    int root = g.add(new Toy.Add(IntList.of(zero, g.add(new Toy.Mul(IntList.of(one, foo))))));
    int iterations = saturate(g, rules(), 10);
    assertTrue(iterations < 10, "did not saturate");
    assertEquals(g.find(foo), g.find(root));
    g.checkInvariants();
    // Saturation is a fixed point: the same classes and nodes however many more iterations run.
    int classes = g.numClasses();
    int nodes = g.numNodes();
    saturate(g, rules(), 3);
    assertEquals(classes, g.numClasses());
    assertEquals(nodes, g.numNodes());
  }

  @Test
  void theMatchesAreReadBeforeAnyIsApplied() {
    // Commutativity on a + b: one iteration adds b + a and nothing else, whatever order the
    // rules' matches came in, since the writes of one rule are not seen by the next's search.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int sum = g.add(new Toy.Add(IntList.of(a, b)));
    List<Rewrite<Toy, Void>> commute = List.of(rules().get(0));
    List<Matcher.Match> before = new ArrayList<>(commute.get(0).search(g));
    assertEquals(1, before.size());
    saturate(g, commute, 1);
    assertEquals(List.of(new Toy.Add(IntList.of(a, b)), new Toy.Add(IntList.of(b, a))),
        g.classOf(sum).nodes());
    assertEquals(3, g.numClasses());
  }
}
