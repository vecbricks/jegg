/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Equality saturation (the paper's Figure 5b): each iteration searches every rule over the
 * graph as it stands, then applies every match found, then rebuilds once; it stops when an
 * iteration changes nothing or a {@link RunLimits} limit is hit, and reports what it did.
 *
 * <p>Two things are fixed that egg leaves to chance. Rules are searched and applied in the
 * order they were given, and each rule's matches are applied in the matcher's order (class id,
 * then node insertion), so the order in which right-hand sides are added - and with it every id
 * the run assigns - is a function of the graph and the rules. A run bounded by nodes
 * and iterations, never by time, then ends in the same graph wherever it runs.
 *
 * @param <L> the language
 * @param <D> the analysis fact
 */
public final class Runner<L extends Language<L>, D> {

  private final EGraph<L, D> graph;
  private final List<Rewrite<L, D>> rules;
  private final RunLimits limits;
  private final Scheduler<L, D> scheduler;

  public Runner(EGraph<L, D> graph, List<Rewrite<L, D>> rules, RunLimits limits,
      Scheduler<L, D> scheduler) {
    this.graph = Objects.requireNonNull(graph, "graph");
    this.rules = List.copyOf(rules);
    this.limits = Objects.requireNonNull(limits, "limits");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
  }

  /** A runner with the default limits and the backoff scheduler, as egg's. */
  public static <L extends Language<L>, D> Runner<L, D> of(EGraph<L, D> graph,
      List<Rewrite<L, D>> rules) {
    return new Runner<>(graph, rules, RunLimits.DEFAULT, new BackoffScheduler<>());
  }

  public EGraph<L, D> graph() {
    return graph;
  }

  /** Runs to saturation or a limit and returns the report. */
  public RunReport run() {
    List<RunReport.Iteration> iterations = new ArrayList<>();
    graph.rebuild();
    StopReason stop = overLimit();
    int iteration = 0;
    while (stop == null) {
      iteration++;
      // Read every match before writing any, so no rule sees this iteration's additions.
      List<List<Matcher.Match>> matches = new ArrayList<>(rules.size());
      Map<String, Integer> counts = new LinkedHashMap<>();
      for (int i = 0; i < rules.size(); i++) {
        List<Matcher.Match> found = scheduler.search(iteration, i, rules.get(i), graph);
        matches.add(found);
        counts.put(rules.get(i).name(), found.size());
      }
      int nodesBefore = graph.numNodes();
      int classesBefore = graph.numClasses();
      int applied = 0;
      int unions = 0;
      for (int i = 0; i < rules.size(); i++) {
        for (Matcher.Match m : matches.get(i)) {
          unions += rules.get(i).apply(graph, m);
          applied++;
        }
      }
      int repaired = graph.rebuild();
      iterations.add(new RunReport.Iteration(iteration, graph.numClasses(), graph.numNodes(),
          counts, applied, unions, repaired));
      stop = overLimit();
      // As egg: saturated only if nothing was merged and nothing was added, since an applier
      // may add nodes without returning them for a union.
      boolean unchanged = unions == 0 && graph.numNodes() == nodesBefore
          && graph.numClasses() == classesBefore;
      if (stop == null && unchanged && scheduler.canStop(iteration)) {
        stop = new StopReason.Saturated();
      }
      if (stop == null && iteration >= limits.iterations()) {
        stop = new StopReason.IterationLimit(iteration);
      }
    }
    return new RunReport(iterations, stop);
  }

  private StopReason overLimit() {
    if (graph.numNodes() > limits.nodes()) {
      return new StopReason.NodeLimit(graph.numNodes());
    }
    if (graph.numClasses() > limits.classes()) {
      return new StopReason.ClassLimit(graph.numClasses());
    }
    return null;
  }
}
