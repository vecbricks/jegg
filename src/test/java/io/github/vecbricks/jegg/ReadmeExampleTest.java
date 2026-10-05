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

import java.util.List;
import org.junit.jupiter.api.Test;

/** The README's example, kept here so that it always compiles and does what the README says. */
class ReadmeExampleTest {

  // README: a language is a sealed set of records; an e-node is an operator, a payload and its
  // children as class ids.
  sealed interface Arith extends Language<Arith> permits Num, Var, Add, Mul {
  }

  record Num(long value) implements Arith {
    public IntList children() {
      return IntList.EMPTY;
    }

    public Arith withChildren(IntList c) {
      return this;
    }
  }

  record Var(String name) implements Arith {
    public IntList children() {
      return IntList.EMPTY;
    }

    public Arith withChildren(IntList c) {
      return this;
    }
  }

  record Add(IntList children) implements Arith {
    public Arith withChildren(IntList c) {
      return new Add(c);
    }
  }

  record Mul(IntList children) implements Arith {
    public Arith withChildren(IntList c) {
      return new Mul(c);
    }
  }

  @Test
  void theReadmeExampleRuns() {
    // README: add (a * 2) + 0, rewrite with two rules, extract the smallest equal term.
    EGraph<Arith, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Var("a"));
    int root = g.add(new Add(IntList.of(
        g.add(new Mul(IntList.of(a, g.add(new Num(2))))), g.add(new Num(0)))));

    Pattern<Arith> x = Pattern.var("x");
    List<Rewrite<Arith, Void>> rules = List.of(
        Rewrite.of("add-0", Pattern.of(new Add(IntList.EMPTY), x, Pattern.of(new Num(0))), x),
        Rewrite.of("mul-2", Pattern.of(new Mul(IntList.EMPTY), x, Pattern.of(new Num(2))),
            Pattern.of(new Add(IntList.EMPTY), x, x)));

    RunReport report = Runner.of(g, rules).run();
    Extracted<Arith> best = new Extractor<>(g, CostFunction.astSize()).extract(root);
    // best is a * 2, the smallest term in the root's class; (a + a) is in the class too.
    // END README

    assertInstanceOf(StopReason.Saturated.class, report.stop(), report.toString());
    assertEquals(new Mul(IntList.of(g.find(a), g.find(g.add(new Num(2))))), best.node());
    assertEquals(3, best.treeSize());
    assertEquals(g.find(root), g.find(g.add(new Add(IntList.of(a, a)))));
  }
}
