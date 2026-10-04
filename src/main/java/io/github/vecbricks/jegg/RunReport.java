/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a run did, iteration by iteration, and why it stopped: the record a client's plan quotes.
 *
 * @param iterations one entry per iteration run, in order
 * @param stop why the run ended
 */
public record RunReport(List<Iteration> iterations, StopReason stop) {

  /**
   * One iteration: the graph's size after its rebuild, the matches each rule was given to
   * apply, the rules the scheduler held back (whose matches read zero for that reason), the
   * rules whose matches were not applied because a limit was passed before their turn, how
   * many matches were applied (their condition held at apply time), how many applications
   * merged two classes that were different, and how many classes the rebuild repaired.
   */
  public record Iteration(int number, int classes, int nodes, Map<String, Integer> matches,
      Set<String> banned, Set<String> skipped, int applied, int unions, int repaired) {
    public Iteration {
      matches = Collections.unmodifiableMap(new LinkedHashMap<>(matches));
      banned = Collections.unmodifiableSet(new LinkedHashSet<>(banned));
      skipped = Collections.unmodifiableSet(new LinkedHashSet<>(skipped));
    }
  }

  public RunReport {
    iterations = List.copyOf(iterations);
  }

  /** How many iterations ran. */
  public int size() {
    return iterations.size();
  }

  @Override
  public String toString() {
    StringBuilder b = new StringBuilder();
    for (Iteration it : iterations) {
      b.append("iteration ").append(it.number()).append(": ").append(it.classes())
          .append(" classes, ").append(it.nodes()).append(" nodes, ").append(it.applied())
          .append(" applied, ").append(it.unions()).append(" unions, ").append(it.repaired())
          .append(" repaired; matches ").append(it.matches())
          .append(it.banned().isEmpty() ? "" : ", banned " + it.banned())
          .append(it.skipped().isEmpty() ? "" : ", skipped " + it.skipped()).append('\n');
    }
    return b.append("stopped: ").append(stop).toString();
  }
}
