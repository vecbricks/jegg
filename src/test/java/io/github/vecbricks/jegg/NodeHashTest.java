/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The test languages' nodes hash with their operator (#145): two operators over one pair of
 * children no longer share a hash, which the hashcons paid an {@code equals} for at every
 * lookup of either.
 */
class NodeHashTest {

  @Test
  void theOperatorsOverOnePairOfChildrenHashApart() {
    // The nodes of math's saturations of forty random terms: before, 1,074 of 5,322 hashes
    // were shared by more than one node, one of them by nine.
    Set<MathTest.Math> nodes = new HashSet<>();
    for (String term : new TermGenerator("math", 2).terms(40)) {
      EGraph<MathTest.Math, Double> g = new EGraph<>(MathTest.CONSTANT_FOLD);
      g.addTree(Term.parse(term), MathTest.BRIDGE);
      new Runner<>(g, MathTest.rules(), RunLimits.DEFAULT.withIterations(6).withNodes(3000),
          new BackoffScheduler<>()).run();
      for (EClass<MathTest.Math, Double> c : g.classes()) {
        nodes.addAll(c.nodes());
      }
    }
    assertTrue(nodes.size() > 5000, "enough nodes to mean something: " + nodes.size());
    Map<Integer, Integer> byHash = new HashMap<>();
    for (MathTest.Math node : nodes) {
      byHash.merge(node.hashCode(), 1, Integer::sum);
    }
    // What remains shared are pairs of children whose Arrays.hashCode coincides (31 * a + b),
    // which no operator salt tells apart: 617 hashes of 6,364, at most six nodes to a hash.
    long shared = byHash.values().stream().filter(k -> k > 1).count();
    assertTrue(byHash.size() * 100 >= nodes.size() * 85, nodes.size() + " nodes, "
        + byHash.size() + " hashes, " + shared + " shared by more than one node");
  }
}
