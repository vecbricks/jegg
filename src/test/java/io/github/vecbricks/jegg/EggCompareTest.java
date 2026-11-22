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
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The comparison's arithmetic and parsing (#72): the medians, the buckets' sums, egg's timed
 * line and its printed report, and the summary's table.
 */
class EggCompareTest {

  @Test
  void eggsTimedLineSplitsIntoTheResultAndFourTimes() {
    EggProcess.Timed t = EggProcess.Timed.parse(
        "Saturated 3 1,1;4,2 final 7,3 memo 9 times 1200 300 40 1600");
    assertEquals("Saturated 3 1,1;4,2 final 7,3 memo 9", t.result());
    assertEquals(1200, t.searchNanos());
    assertEquals(300, t.applyNanos());
    assertEquals(40, t.rebuildNanos());
    assertEquals(1600, t.totalNanos());
    // The result alone still parses as before, and FuzzRun reads it as it always did.
    assertEquals(9, FuzzRun.Result.parse(t.result()).memo());
    EggProcess.Timed untimed = EggProcess.Timed.parse("PANIC");
    assertEquals("PANIC", untimed.result());
    assertEquals(0, untimed.totalNanos());
    assertThrows(IllegalArgumentException.class,
        () -> EggProcess.Timed.parse("Saturated 1 - final 1,1 memo 1 times 1 2"));
  }

  @Test
  void theMedianIsPerPhaseAndTheTotalIsTheMedianOfTheSums() {
    List<EggCompare.Side> rounds = List.of(new EggCompare.Side(10, 1, 100, 0),
        new EggCompare.Side(30, 3, 1, 0), new EggCompare.Side(20, 2, 10, 0));
    EggCompare.Side m = EggCompare.Side.median(rounds);
    assertEquals(20, m.search());
    assertEquals(2, m.apply());
    assertEquals(10, m.rebuild());
    // The sums are 111, 34 and 32: the median sum is 34, not 20 + 2 + 10.
    assertEquals(34, m.total());
    assertEquals(15, EggCompare.median(new long[] {10, 20}));
    assertEquals(0, EggCompare.median(new long[0]));
    assertEquals(3.0, EggCompare.percentile(List.of(1.0, 2.0, 3.0, 4.0, 5.0), 0.5));
    assertEquals(5.0, EggCompare.percentile(List.of(1.0, 2.0, 3.0, 4.0, 5.0), 1.0));
    assertEquals(4.6, EggCompare.percentile(List.of(1.0, 2.0, 3.0, 4.0, 5.0), 0.9), 1e-9);
    assertEquals(0.0, EggCompare.percentile(List.of(), 0.9));
  }

  @Test
  void eggsPrintedReportIsReadFromTheTestOutput() {
    String out = """
        running 1 test
        Runner report
        =============
          Stop reason: Other("Proved all goals")
          Iterations: 21
          Egraph size: 32636 nodes, 5000 classes, 40000 memo
          Rebuilds: 300
          Total time: 0.9
            Search:  (0.50) 0.45
            Apply:   (0.30) 0.27
            Rebuild: (0.20) 0.18
        test lambda_function_repeat ... ok
        """;
    EggCompare.EggReport r = EggCompare.EggReport.parse(out);
    assertEquals(21, r.iterations());
    assertEquals(32636, r.nodes());
    assertEquals(5000, r.classes());
    assertEquals(450_000_000L, r.times().search());
    assertEquals(270_000_000L, r.times().apply());
    assertEquals(180_000_000L, r.times().rebuild());
    assertEquals(900_000_000L, r.times().total());
    assertThrows(IllegalArgumentException.class, () -> EggCompare.EggReport.parse("nothing"));
  }

  private static EggCompare.TermRow row(int index, FuzzRun.Verdict bucket, long egg,
      long jegg) {
    return new EggCompare.TermRow("prop", index, bucket, new EggCompare.Side(egg, 0, 0, egg),
        new EggCompare.Side(jegg, 0, 0, jegg), jegg + 5, 3, 40, "comm_or");
  }

  @Test
  void theSummarySumsTheBucketsApartAndNamesTheSlowestTerms() {
    List<EggCompare.TermRow> rows = List.of(row(0, FuzzRun.Verdict.SAME, 1_000_000, 3_000_000),
        row(1, FuzzRun.Verdict.SAME, 2_000_000, 4_000_000),
        row(2, FuzzRun.Verdict.NODE_LIMIT, 1_000_000, 9_000_000),
        row(3, FuzzRun.Verdict.DIVERGE, 1, 1));
    List<EggCompare.Side> rounds = List.of(new EggCompare.Side(4_000_000, 0, 0, 0));
    String s = EggCompare.summarize("prop", 4, 2, 1, rows, rounds, rounds, 7_000_010);
    assertTrue(s.contains("| SAME | 2 | 3.0 | 7.0 | 2.33x |"), s);
    assertTrue(s.contains("| NODE_LIMIT | 1 | 1.0 | 9.0 | 9.00x |"), s);
    assertTrue(s.contains("Excluded: BOTH_FAILED 0, INCONCLUSIVE 0, DIVERGE 1."), s);
    // The whole gap of 4 ms is in search; the two other phases are empty on both sides.
    assertTrue(s.contains("| search | 3.0 | 7.0 | 2.33x | 100% |"), s);
    assertTrue(s.contains("| apply | 0.0 | 0.0 | - | 0% |"), s);
    assertTrue(s.contains("| jegg's wall beyond the phases | | 0.0 | | |"), s);
    assertTrue(s.contains("(2): median 2.50x, p90 2.90x, max 3.00x"), s);
    // The slowest term is the node-limit one; the diverged term is not listed.
    int slowest = s.indexOf("| 2 | NODE_LIMIT | 9.0 | 1.0 | 9.00x | 3 | 40 | comm_or |");
    int next = s.indexOf("| 1 | SAME | 4.0 | 2.0 | 2.00x |");
    assertTrue(slowest > 0 && next > slowest, s);
    assertTrue(!s.contains("| 3 | DIVERGE"), s);
  }

  @Test
  void theLambdaWorkAllowsForEggsCountOfTheHookStoppedIteration() {
    EggCompare.Side t = EggCompare.Side.ZERO;
    EggCompare.EggReport egg = new EggCompare.EggReport(59, 32636, 5000, t);
    Saturation.Outcome proved = new Saturation.Outcome(Saturation.Mode.DEFERRED, 58, 0, 32636,
        5000, "Proved all goals", false, 0, 0, 0);
    assertTrue(EggCompare.sameLambdaWork(egg, proved));
    assertTrue(EggCompare.sameLambdaWork(new EggCompare.EggReport(58, 32636, 5000, t), proved));
    Saturation.Outcome limit = new Saturation.Outcome(Saturation.Mode.DEFERRED, 58, 0, 32636,
        5000, "NodeLimit", false, 0, 0, 0);
    assertTrue(!EggCompare.sameLambdaWork(egg, limit));
    assertTrue(!EggCompare.sameLambdaWork(new EggCompare.EggReport(59, 1, 5000, t), proved));
  }

  @Test
  void aRowRoundTripsThroughJson() {
    EggCompare.TermRow r = row(7, FuzzRun.Verdict.SAME, 10, 20);
    @SuppressWarnings("unchecked")
    Map<String, Object> m = (Map<String, Object>) Json.parse(r.toJson());
    assertEquals("prop", m.get("language"));
    assertEquals(7.0, m.get("index"));
    assertEquals("SAME", m.get("bucket"));
    assertEquals("comm_or", m.get("topRule"));
    @SuppressWarnings("unchecked")
    Map<String, Object> jegg = (Map<String, Object>) m.get("jegg");
    assertEquals(20.0, jegg.get("total"));
    assertEquals("\"a\\\"b\"", EggCompare.quoted("a\"b"));
  }
}
