/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

/**
 * Where a run stops if it has not saturated: after this many iterations, or once the graph
 * holds more than this many nodes or classes. There is no wall-clock limit, on purpose: a run
 * bounded by nodes and iterations ends in the same graph on every machine, which is what lets
 * a client pin what it extracts (PLAN.md 2, determinism). A value candidate (PLAN.md 3.1):
 * immutable, compared by content.
 *
 * @param iterations the most iterations to run; at least one
 * @param nodes the run stops once the graph holds more nodes than this, checked after each
 *     iteration's rebuild
 * @param classes the same for classes
 */
public record RunLimits(int iterations, int nodes, int classes) {

  /** egg's defaults, less its five seconds: thirty iterations, ten thousand nodes. */
  public static final RunLimits DEFAULT = new RunLimits(30, 10_000, Integer.MAX_VALUE);

  /** Checks the limits; each must be at least one. */
  public RunLimits {
    if (iterations < 1 || nodes < 1 || classes < 1) {
      throw new IllegalArgumentException("limits must be positive: " + iterations + ", " + nodes
          + ", " + classes);
    }
  }

  /**
   * A copy with another iteration limit.
   *
   * @param n the new limit, at least one
   * @return the limits with {@code iterations} replaced, the others as they were
   */
  public RunLimits withIterations(int n) {
    return new RunLimits(n, nodes, classes);
  }

  /**
   * A copy with another node limit.
   *
   * @param n the new limit, at least one
   * @return the limits with {@code nodes} replaced, the others as they were
   */
  public RunLimits withNodes(int n) {
    return new RunLimits(iterations, n, classes);
  }

  /**
   * A copy with another class limit.
   *
   * @param n the new limit, at least one
   * @return the limits with {@code classes} replaced, the others as they were
   */
  public RunLimits withClasses(int n) {
    return new RunLimits(iterations, nodes, n);
  }
}
