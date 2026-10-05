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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@link Extractor#extractAll} on extraction-gym's graphs of up to 300 nodes (see
 * {@code src/test/resources/extraction-gym/README.md}): against the greedy start it begins from,
 * which it must never make worse, and against the exact optimum by branch and bound where that
 * finishes, which it must never beat and should mostly meet. Issue #12's prediction 3: at
 * least 80% of the graphs the oracle finishes, every miss named. The graphs past 150 nodes have a
 * longer oracle deadline and are counted on their own: where a heuristic starts to miss.
 */
class ExtractionGymTest {

  private static final Path DIR = Path.of("src/test/resources/extraction-gym");
  /** The graphs past this many nodes are the larger ones, with a deadline of their own. */
  private static final int LARGE = 150;
  private static final long SMALL_DEADLINE_SECONDS = 3;
  /** The oracle's deadline per larger graph; {@code -Dgym.oracle.seconds=N} raises it. */
  private static final long LARGE_DEADLINE_SECONDS = Long.getLong("gym.oracle.seconds", 10);

  @Test
  void theDescentMeetsTheOracleOnMostGymGraphsAndNeverWorsensTheStart() throws IOException {
    List<String> rows = new ArrayList<>();
    List<String> misses = new ArrayList<>();
    int finished = 0;
    int met = 0;
    int cyclic = 0;
    int largeGraphs = 0;
    int largeFinished = 0;
    int largeMet = 0;
    List<String> largeTimedOut = new ArrayList<>();
    List<Path> files;
    try (Stream<Path> list = Files.list(DIR)) {
      files = list.filter(p -> p.toString().endsWith(".json")).sorted().toList();
    }
    assertTrue(files.size() >= 40, "gym graphs present: " + files.size());
    for (Path file : files) {
      var read = GymGraph.read(file);
      if (read.isEmpty()) {
        cyclic++;
        continue;
      }
      GymGraph gym = read.get();
      Extractor<GymGraph.Node, Void> ex = new Extractor<>(gym.graph, GymGraph.COST);
      double start = ex.greedyStart(gym.roots).cost();
      Selection<GymGraph.Node> sel = ex.extractAll(gym.roots);
      assertTrue(sel.cost() <= start + 1e-9, gym.name + ": the descent made the start worse, "
          + sel.cost() + " from " + start);
      sel.terms();
      OptionalDouble optimum = ExactExtraction.optimum(gym.graph, GymGraph.COST, gym.roots,
          System.nanoTime() + TimeUnit.SECONDS.toNanos(
              gym.gymNodes > LARGE ? LARGE_DEADLINE_SECONDS : SMALL_DEADLINE_SECONDS));
      boolean large = gym.gymNodes > LARGE;
      if (large) {
        largeGraphs++;
      }
      String verdict;
      if (optimum.isPresent()) {
        finished++;
        largeFinished += large ? 1 : 0;
        assertTrue(sel.cost() >= optimum.getAsDouble() - 1e-9, gym.name
            + ": the descent beat the oracle, " + sel.cost() + " under " + optimum.getAsDouble());
        if (sel.cost() <= optimum.getAsDouble() + 1e-9) {
          met++;
          largeMet += large ? 1 : 0;
          verdict = "optimal";
        } else {
          verdict = String.format("MISS by %.1f%%",
              100 * (sel.cost() - optimum.getAsDouble()) / optimum.getAsDouble());
          misses.add(gym.name + " " + verdict);
        }
      } else {
        verdict = "oracle timed out";
        if (large) {
          largeTimedOut.add(gym.name);
        }
      }
      rows.add(String.format("%-70s %4d nodes %4d classes %2d roots  start %9.1f  descent %9.1f"
          + "  optimum %9s  %s", gym.name, gym.gymNodes, gym.gymClasses, gym.roots.size(), start,
          sel.cost(), optimum.isPresent() ? String.format("%.1f", optimum.getAsDouble()) : "-",
          verdict));
    }
    rows.forEach(System.out::println);
    System.out.printf("GYM %d graphs, %d cyclic skipped, oracle finished %d, met %d, misses %s%n",
        files.size(), cyclic, finished, met, misses);
    System.out.printf("GYM past %d nodes: %d graphs, oracle finished %d (deadline %d s), met %d,"
        + " timed out %s%n", LARGE, largeGraphs, largeFinished, LARGE_DEADLINE_SECONDS, largeMet,
        largeTimedOut);
    assertTrue(largeGraphs >= 15, "larger graphs present: " + largeGraphs);
    assertTrue(largeFinished * 2 >= largeGraphs, "the oracle finished on " + largeFinished
        + " of the " + largeGraphs + " larger graphs");
    assertTrue(largeMet * 100 >= largeFinished * 80, "met the oracle on " + largeMet + " of "
        + largeFinished + " larger graphs: " + misses);
    assertTrue(finished >= 20, "the oracle finished on " + finished);
    assertTrue(met * 100 >= finished * 80, "met the oracle on " + met + " of " + finished
        + ": " + misses);
  }
}
