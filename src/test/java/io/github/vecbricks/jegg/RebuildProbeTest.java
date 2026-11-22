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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The probe's parsing and arithmetic (#134), and jegg's counters' identity on a real run. */
class RebuildProbeTest {

  @Test
  void bothSidesCountsParse() {
    RebuildProbe.Counts egg = RebuildProbe.Counts.egg("12 7 5 3 2 40 9 6 4 30 100 8 50 30 20 20 11 9 25");
    assertEquals(12, egg.get("pops"));
    assertEquals(5, egg.get("pended_absorbed"));
    assertEquals(8, egg.get("rebuild_trimmed"));
    assertEquals(0, egg.get("nothing"));
    RebuildProbe.Counts fromReport = RebuildProbe.Counts.eggReport(
        "Runner report\n  Rebuild: (0.20) 0.18\n  Probe: [12, 7, 5, 3, 2, 40, 9, 6, 4, 30, 100,"
            + " 8, 50, 30, 20, 20, 11, 9, 25]\ntest ok\n");
    assertEquals(egg.values(), fromReport.values());
    assertThrows(IllegalArgumentException.class, () -> RebuildProbe.Counts.egg("1 2 3"));
    assertThrows(IllegalArgumentException.class,
        () -> RebuildProbe.Counts.eggReport("no report"));
    RebuildProbe.Counts jegg = RebuildProbe.Counts.jegg(
        "merges=9 parentsVisited=20 hashconsRemoves=21 hashconsPuts=19 prunedVisits=1");
    assertEquals(40, jegg.get("hashconsOps"));
    assertEquals(29, egg.plus(jegg).get("merges") + egg.plus(jegg).get("parentsVisited"));
    assertTrue(jegg.toJson().contains("\"hashconsOps\":40"), jegg.toJson());
  }

  @Test
  void theSummaryIsByQuintileOverSameAndNamesTheIdentities() {
    RebuildProbe.Counts e = RebuildProbe.Counts.egg("10 4 6 1 1 20 3 2 1 5 15 0 8 5 3 3 4 2 6");
    RebuildProbe.Counts j = RebuildProbe.Counts.jegg("merges=3 mergeAbsorbedParents=6"
        + " mergeMinParents=5 parentsVisited=12 formsRemade=4 hashconsRemoves=12"
        + " hashconsPuts=12 prunedVisits=0 congruenceMerges=1 analysisMakes=2 modifyCalls=1"
        + " staleClasses=2 nodesChecked=4 nodesRemade=0");
    List<RebuildProbe.Row> rows = List.of(
        new RebuildProbe.Row("prop", 0, FuzzRun.Verdict.SAME, 100, 2, 10, e, j),
        new RebuildProbe.Row("prop", 1, FuzzRun.Verdict.SAME, 900, 3, 20, e, j),
        new RebuildProbe.Row("prop", 2, FuzzRun.Verdict.DIVERGE, 50, 1, 1, e, j));
    String s = RebuildProbe.summarize("prop", 3, 2, rows);
    assertTrue(s.contains("Buckets: {SAME=2, DIVERGE=1}"), s);
    // Two SAME terms over five quintiles: the fifth holds the slower one, the first the faster.
    assertTrue(s.contains("| 5 | 1 | 6 | 12 | 2.00x | 5 | 0.83x | 4 | 10 | 24 | 2.40x |"), s);
    assertTrue(s.contains("| 1 | 0 | 0 | 0 | - | 0 | - | 0 | 0 | 0 | - |"), s);
    assertTrue(s.contains("| unions / merges | 6 | 6 | 1.00x |"), s);
    assertTrue(s.contains("| of which egg pays for the nodes added since the last rebuild"
        + " | 8 |  |  |"), s);
    assertTrue(s.contains("egg pops minus (added + absorbed) = 0"), s);
    assertTrue(s.contains("jegg parents visited minus (hashcons puts + pruned visits) = 0"), s);
    assertTrue(s.contains("(0): median 0.00x"), s);
    assertTrue(rows.get(0).toJson().contains("\"bucket\":\"SAME\",\"eggTotalNanos\":100"));
  }

  @Test
  void jeggsCountersHoldTheirIdentityOnARun() {
    FuzzRun.Timed t = FuzzRun.timed(new FuzzRun.Case("prop-all",
        "(& (| a (~ a)) (-> (~ (~ b)) (| b (& a b))))", List.of()));
    RebuildProbe.Counts c = RebuildProbe.Counts.jegg(t.counts());
    assertTrue(c.get("parentsVisited") > 0, t.counts());
    assertEquals(c.get("parentsVisited"), c.get("hashconsPuts") + c.get("prunedVisits"),
        t.counts());
    assertTrue(c.get("merges") >= c.get("congruenceMerges"), t.counts());
    assertTrue(c.get("mergeMinParents") <= c.get("mergeAbsorbedParents"), t.counts());
    assertTrue(c.get("repairs") <= c.get("merges") + 1, t.counts());
  }
}
