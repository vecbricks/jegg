/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.jegg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The line and branch totals of JaCoCo's CSV report, printed at the end of {@code mvn verify} so
 * the number is in every build log, with the classes least covered by lines. Run by the pom's
 * exec plugin after the report; prints nothing if the report is missing (a build that skipped
 * the tests).
 */
public final class CoverageSummary {

  private CoverageSummary() {
  }

  private record Row(String name, int linesMissed, int linesCovered, int branchesMissed,
      int branchesCovered) {
    double linePercent() {
      return percent(linesMissed, linesCovered);
    }
  }

  private static double percent(int missed, int covered) {
    return missed + covered == 0 ? 100.0 : 100.0 * covered / (missed + covered);
  }

  public static void main(String[] args) throws IOException {
    Path csv = Path.of(args[0]);
    if (!Files.exists(csv)) {
      return;
    }
    List<Row> rows = new ArrayList<>();
    List<String> lines = Files.readAllLines(csv);
    String[] header = lines.get(0).split(",");
    int cls = List.of(header).indexOf("CLASS");
    int lm = List.of(header).indexOf("LINE_MISSED");
    int lc = List.of(header).indexOf("LINE_COVERED");
    int bm = List.of(header).indexOf("BRANCH_MISSED");
    int bc = List.of(header).indexOf("BRANCH_COVERED");
    for (String line : lines.subList(1, lines.size())) {
      String[] f = line.split(",");
      rows.add(new Row(f[cls], Integer.parseInt(f[lm]), Integer.parseInt(f[lc]),
          Integer.parseInt(f[bm]), Integer.parseInt(f[bc])));
    }
    int linesMissed = rows.stream().mapToInt(Row::linesMissed).sum();
    int linesCovered = rows.stream().mapToInt(Row::linesCovered).sum();
    int branchesMissed = rows.stream().mapToInt(Row::branchesMissed).sum();
    int branchesCovered = rows.stream().mapToInt(Row::branchesCovered).sum();
    System.out.printf("Coverage of src/main: lines %.1f%% (%d of %d), branches %.1f%% (%d of %d);"
            + " least covered by lines:%n", percent(linesMissed, linesCovered), linesCovered,
        linesMissed + linesCovered, percent(branchesMissed, branchesCovered), branchesCovered,
        branchesMissed + branchesCovered);
    rows.stream().filter(r -> r.linesMissed() > 0)
        .sorted((a, b) -> Double.compare(a.linePercent(), b.linePercent())).limit(5)
        .forEach(r -> System.out.printf("  %-24s lines %5.1f%% (%d missed)%n", r.name(),
            r.linePercent(), r.linesMissed()));
  }
}
