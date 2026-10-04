/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.jegg;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * The absolute cost at Varka's size (PLAN.md 6, prediction 4): the 64-node {@link Projection}
 * added and saturated or run to its node limit, and the saturated graph extracted, over all the
 * roots at once and one root at a time. The mode (warm average, cold single shot) and the
 * profilers are set by {@link Benchmarks}.
 */
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ProjectionBenchmark {

  /**
   * Node limits a compiler with a budget might set. egg's default, 10,000, is not measured:
   * under these twenty rules the run to it takes minutes, which the results file's shape
   * section shows; prediction 4 is about a compile-time budget.
   */
  @Param({"200", "1000"})
  public int nodes;

  private Projection.Saturated saturated;

  @Setup(Level.Trial)
  public void saturateOnce() {
    saturated = Projection.saturate(nodes);
  }

  /** The term added and run to saturation or the node limit, from an empty graph. */
  @Benchmark
  public RunReport saturate() {
    return Projection.saturate(nodes).report();
  }

  /** One node per class over all the roots, the shared decomposition paid once. */
  @Benchmark
  public Selection<Projection.Varka> extractAll() {
    return new Extractor<>(saturated.graph(), Projection.TABLE).extractAll(saturated.roots());
  }

  /** The cheapest tree of each root, egg's extraction. */
  @Benchmark
  public void extractTrees(Blackhole hole) {
    Extractor<Projection.Varka, Void> ex = new Extractor<>(saturated.graph(), Projection.TABLE);
    IntList roots = saturated.roots();
    for (int i = 0; i < roots.size(); i++) {
      hole.consume(ex.extract(roots.get(i)));
    }
  }
}
