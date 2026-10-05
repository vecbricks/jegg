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
 * least 80% of the graphs the oracle finishes, every miss named. The graphs past 150 nodes are
 * counted on their own too: where a heuristic starts to miss.
 */
class ExtractionGymTest {

  private static final Path DIR = Path.of("src/test/resources/extraction-gym");
  /** The graphs past this many nodes are the larger ones, counted on their own. */
  private static final int LARGE = 150;
  /**
   * The oracle's budget per graph, in search steps: the same on every machine, so the set of
   * graphs it finishes is too. The largest graph it finishes takes 2,839,044 steps
   * ({@code babble__list_list_hard_test...bench000_it0}); the budget is 3.5 times that, and the
   * test asserts the 2x margin. The one graph it does not finish ({@code egg__lambda_compose_many},
   * every cost 1.0, so the bound prunes nothing) had taken 221 million steps at 20 s.
   * {@code -Dgym.oracle.steps=N} sets it.
   */
  private static final long MAX_STEPS = Long.getLong("gym.oracle.steps", 10_000_000);
  /**
   * The wall-clock deadline per graph, a safety net far above the budget (10 million steps take
   * about a second); {@code -Dgym.oracle.seconds=N} sets it.
   */
  private static final long DEADLINE_SECONDS = Long.getLong("gym.oracle.seconds", 30);

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
    List<String> largeMisses = new ArrayList<>();
    long mostSteps = 0;
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
      boolean large = gym.gymNodes > LARGE;
      if (large) {
        largeGraphs++;
      }
      Extractor<GymGraph.Node, Void> ex = new Extractor<>(gym.graph, GymGraph.COST);
      double start = ex.greedyStart(gym.roots).cost();
      Selection<GymGraph.Node> sel = ex.extractAll(gym.roots);
      assertTrue(sel.cost() <= start + 1e-9, gym.name + ": the descent made the start worse, "
          + sel.cost() + " from " + start);
      sel.terms();
      ExactExtraction.Outcome outcome = ExactExtraction.optimum(gym.graph, GymGraph.COST,
          gym.roots, MAX_STEPS, System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS));
      OptionalDouble optimum = outcome.optimum();
      if (optimum.isPresent()) {
        mostSteps = Math.max(mostSteps, outcome.steps());
      }
      String verdict;
      if (optimum.isPresent()) {
        finished++;
        if (large) {
          largeFinished++;
        }
        assertTrue(sel.cost() >= optimum.getAsDouble() - 1e-9, gym.name
            + ": the descent beat the oracle, " + sel.cost() + " under " + optimum.getAsDouble());
        if (sel.cost() <= optimum.getAsDouble() + 1e-9) {
          met++;
          if (large) {
            largeMet++;
          }
          verdict = "optimal";
        } else {
          verdict = String.format("MISS by %.1f%%",
              100 * (sel.cost() - optimum.getAsDouble()) / optimum.getAsDouble());
          misses.add(gym.name + " " + verdict);
          if (large) {
            largeMisses.add(gym.name + " " + verdict);
          }
        }
      } else {
        verdict = "oracle timed out";
        if (large) {
          largeTimedOut.add(gym.name);
        }
      }
      rows.add(String.format("%-70s %4d nodes %4d classes %2d roots  start %9.1f  descent %9.1f"
          + "  optimum %9s  steps %10d  %s", gym.name, gym.gymNodes, gym.gymClasses,
          gym.roots.size(), start, sel.cost(),
          optimum.isPresent() ? String.format("%.1f", optimum.getAsDouble()) : "-",
          outcome.steps(), verdict));
    }
    rows.forEach(System.out::println);
    System.out.printf("GYM %d graphs, %d cyclic skipped, oracle finished %d (budget %d steps,"
        + " the most a finished graph took %d), met %d, misses %s%n", files.size(), cyclic,
        finished, MAX_STEPS, mostSteps, met, misses);
    System.out.printf("GYM past %d nodes: %d graphs, oracle finished %d, met %d, timed out %s,"
        + " misses %s%n", LARGE, largeGraphs, largeFinished, largeMet, largeTimedOut,
        largeMisses);
    assertTrue(mostSteps * 2 <= MAX_STEPS, "a finished graph took " + mostSteps
        + " steps, over half the budget of " + MAX_STEPS);
    assertTrue(largeGraphs >= 15, "larger graphs present: " + largeGraphs);
    assertTrue(largeFinished * 2 >= largeGraphs, "the oracle finished on " + largeFinished
        + " of the " + largeGraphs + " larger graphs");
    assertTrue(largeMet * 100 >= largeFinished * 80, "met the oracle on " + largeMet + " of "
        + largeFinished + " larger graphs: " + largeMisses);
    assertTrue(finished >= 20, "the oracle finished on " + finished);
    assertTrue(met * 100 >= finished * 80, "met the oracle on " + met + " of " + finished
        + ": " + misses);
  }
}
