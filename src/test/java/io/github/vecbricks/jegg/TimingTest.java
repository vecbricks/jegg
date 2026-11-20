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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The timing driver's lines and its summary: the verdict reads a difference against the noise. */
class TimingTest {

  private static Timing.Line line(String workload, long ms) {
    return new Timing.Line(workload, ms, Map.of("terms", "10", "errors", "1"));
  }

  @Test
  void aLineRoundTripsThroughJson() {
    Map<String, Object> work = new LinkedHashMap<>();
    work.put("terms", 10);
    work.put("errors", 1);
    Timing.Line line = new Timing.Line("prop", 1234, work);
    assertEquals("{\"workload\":\"prop\",\"ms\":1234,\"terms\":10,\"errors\":1}", line.toJson());
    Timing.Line back = Timing.Line.parse(line.toJson());
    assertEquals("prop", back.workload());
    assertEquals(1234, back.ms());
    assertEquals(Map.of("terms", "10", "errors", "1"), back.work());
  }

  @Test
  void aDifferenceInsideTheLargerRangeIsNoise() {
    Timing.Row r = Timing.row("prop", List.of(line("prop", 100), line("prop", 104),
        line("prop", 110)), List.of(line("prop", 98), line("prop", 99), line("prop", 101)));
    assertEquals(104, r.baseMedian());
    assertEquals(99, r.headMedian());
    assertEquals("within noise", r.verdict(), "5 ms apart, the base range is 10 ms");
  }

  @Test
  void aDifferenceBeyondBothRangesIsAVerdict() {
    Timing.Row faster = Timing.row("prop",
        List.of(line("prop", 100), line("prop", 102), line("prop", 102)),
        List.of(line("prop", 90), line("prop", 91), line("prop", 91)));
    assertEquals("faster", faster.verdict());
    assertEquals(-10.8, faster.change(), 0.05, "medians 91 against 102");
    Timing.Row slower = Timing.row("math",
        List.of(line("math", 100), line("math", 100), line("math", 100)),
        List.of(line("math", 101), line("math", 101), line("math", 101)));
    assertEquals("slower", slower.verdict(), "no range at all: any difference counts");
    Timing.Row few = Timing.row("math", List.of(line("math", 100)), List.of(line("math", 50)));
    assertEquals("too few rounds", few.verdict(), "one round each cannot show a range");
  }

  @Test
  void sidesThatDidDifferentWorkGetNoVerdict() {
    Timing.Row r = Timing.row("prop", List.of(line("prop", 100)), List.of(new Timing.Line("prop",
        50, Map.of("terms", "10", "errors", "2"))));
    assertEquals("different work", r.verdict());
  }

  @Test
  void theSummaryReadsTheRoundsOfBothSides(@TempDir Path dir) throws IOException {
    for (int round = 1; round <= 3; round++) {
      Files.writeString(dir.resolve("base-" + round + ".jsonl"),
          line("prop", 100 + round).toJson() + "\n" + line("lambda", 10).toJson() + "\n");
      Files.writeString(dir.resolve("head-" + round + ".jsonl"),
          line("prop", 80 + round).toJson() + "\n" + line("lambda", 10).toJson() + "\n");
    }
    Files.writeString(dir.resolve("provenance.txt"), "not a round\n");
    String summary = Timing.summarize(dir);
    assertTrue(summary.contains("| prop | 102 (101–103) | 82 (81–83) | -19.6% | 3 | faster |"),
        summary);
    assertTrue(
        summary.contains("| lambda | 10 (10–10) | 10 (10–10) | +0.0% | 3 | within noise |"),
        summary);
    assertTrue(summary.startsWith("| workload |"), summary);
  }
}
