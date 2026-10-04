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

import org.junit.jupiter.api.Test;

/** The measurement's Varka-shaped workload is what PLAN.md 6 says: 64 nodes and 20 rules. */
class ProjectionTest {

  @Test
  void theProjectionHas64NodesAnd20Rules() {
    EGraph<Projection.Varka, Void> g = EGraph.withoutAnalysis();
    IntList roots = Projection.addProjection(g);
    assertEquals(64, g.numNodes(), "nodes");
    assertEquals(20, Projection.rules().size(), "rules");
    assertTrue(roots.size() >= 10, "roots: " + roots.size());
    g.checkInvariants();
  }

  @Test
  void theRunEndsOnItsNodeLimitAndExtractsEveryRoot() {
    Projection.Saturated s = Projection.saturate(1_000);
    assertTrue(s.report().stop() instanceof StopReason.NodeLimit, s.report().toString());
    Selection<Projection.Varka> sel = new Extractor<>(s.graph(), Projection.TABLE)
        .extractAll(s.roots());
    assertEquals(s.roots().size(), sel.terms().size());
    s.graph().checkInvariants();
  }
}
