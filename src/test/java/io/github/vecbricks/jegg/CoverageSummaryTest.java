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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code CoverageSummary --json}: the same totals as the text form, parsed back. */
class CoverageSummaryTest {

  // JaCoCo's CSV header and two rows: EGraph fully covered, Matcher missing one line and branch.
  private static final String CSV = """
      GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,\
      LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED
      jegg,io.github.vecbricks.jegg,EGraph,0,500,0,40,0,100,0,30,0,20
      jegg,io.github.vecbricks.jegg,Matcher,10,200,2,20,1,80,1,15,0,10
      """;

  private String run(Path csv, String... extra) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream original = System.out;
    try (PrintStream captured = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      System.setOut(captured);
      String[] args = new String[1 + extra.length];
      args[0] = csv.toString();
      System.arraycopy(extra, 0, args, 1, extra.length);
      CoverageSummary.main(args);
    } finally {
      System.setOut(original);
    }
    return bytes.toString(StandardCharsets.UTF_8);
  }

  @Test
  void jsonHasTheSameTotalsAsTheTextForm(@org.junit.jupiter.api.io.TempDir Path dir)
      throws IOException {
    Path csv = dir.resolve("jacoco.csv");
    Files.writeString(csv, CSV);
    String text = run(csv);
    assertTrue(text.startsWith("Coverage of src/main: lines 99.4% (180 of 181), branches 96.8%"
        + " (60 of 62);"), text);
    assertTrue(text.contains("Matcher"), text);
    String json = run(csv, "--json");
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = (Map<String, Object>) Json.parse(json);
    @SuppressWarnings("unchecked")
    Map<String, Object> lines = (Map<String, Object>) parsed.get("lines");
    assertEquals(1.0, lines.get("missed"));
    assertEquals(180.0, lines.get("covered"));
    @SuppressWarnings("unchecked")
    Map<String, Object> branches = (Map<String, Object>) parsed.get("branches");
    assertEquals(2.0, branches.get("missed"));
    assertEquals(60.0, branches.get("covered"));
    @SuppressWarnings("unchecked")
    List<Object> least = (List<Object>) parsed.get("leastCoveredByLines");
    assertEquals(1, least.size(), least.toString());
    @SuppressWarnings("unchecked")
    Map<String, Object> worst = (Map<String, Object>) least.get(0);
    assertEquals("Matcher", worst.get("name"));
    assertEquals(1.0, worst.get("linesMissed"));
  }

  @Test
  void aMissingCsvPrintsNothingEitherWay(@org.junit.jupiter.api.io.TempDir Path dir)
      throws IOException {
    Path missing = dir.resolve("nope.csv");
    assertEquals("", run(missing));
    assertEquals("", run(missing, "--json"));
  }

  @Test
  void aPerfectClassIsNeverAmongTheLeastCovered(@org.junit.jupiter.api.io.TempDir Path dir)
      throws IOException {
    Path csv = dir.resolve("jacoco.csv");
    Files.writeString(csv, CSV.lines().filter(l -> !l.contains(",Matcher,"))
        .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b) + "\n");
    String json = run(csv, "--json");
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = (Map<String, Object>) Json.parse(json);
    @SuppressWarnings("unchecked")
    List<Object> least = (List<Object>) parsed.get("leastCoveredByLines");
    assertTrue(least.isEmpty(), least.toString());
    assertFalse(json.strip().contains("\n"), "one line, the trailing newline from println: "
        + json);
  }
}
