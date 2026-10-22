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

/** The README's quick start, kept here so that it always compiles and does what it says. */
class QuickStartTest {

  // README: a language is one record.
  record Node(String op, IntList children) implements Language<Node> {
    public Node withChildren(IntList c) {
      return new Node(op, c);
    }
  }

  @Test
  void theQuickStartRuns() {
    EGraph<Node, Void> g = EGraph.withoutAnalysis();
    int x = g.add(new Node("x", IntList.EMPTY));
    int sum = g.add(new Node("+", IntList.of(x, g.add(new Node("0", IntList.EMPTY)))));
    Pattern<Node> v = Pattern.var("v");
    RunReport report = Runner.of(g, List.of(Rewrite.of("x + 0 -> x",
        Pattern.of(new Node("+", IntList.EMPTY), v,
            Pattern.of(new Node("0", IntList.EMPTY))), v))).run();
    Extracted<Node> best = new Extractor<>(g, CostFunction.astSize()).extract(sum);

    assertInstanceOf(StopReason.Saturated.class, report.stop(), report.toString());
    assertEquals(new Node("x", IntList.EMPTY), best.node());
    assertEquals(3, g.numNodes());
    assertEquals(g.find(x), g.find(sum), "x + 0 is x");
  }
}
