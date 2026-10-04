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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Equality saturation (the paper's Figure 5b): each iteration searches every rule over the
 * graph as it stands, then applies every match found, then rebuilds once; it stops when an
 * iteration changes nothing, a {@link RunLimits} limit is hit or a {@link Hook} asks, and
 * reports what it did.
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
  private final List<Hook<L, D>> hooks = new ArrayList<>();

  /**
   * Run before each iteration, on the rebuilt graph (egg's {@code with_hook}): a reason to stop
   * ends the run with {@link StopReason.Other}, an empty one (never {@code null}) lets the
   * iteration go ahead. A test stops once its goals are proved; a client might stop on a budget
   * of its own. A hook may change the graph: the runner rebuilds after the hooks, and an
   * iteration whose hooks added or merged is not saturation, as in egg.
   *
   * @param <L> the language
   * @param <D> the analysis fact
   */
  @FunctionalInterface
  public interface Hook<L extends Language<L>, D> {
    Optional<String> beforeIteration(EGraph<L, D> graph);
  }

  public Runner(EGraph<L, D> graph, List<Rewrite<L, D>> rules, RunLimits limits,
      Scheduler<L, D> scheduler) {
    this.graph = Objects.requireNonNull(graph, "graph");
    this.rules = List.copyOf(rules);
    // The report counts each rule's matches by its name, so two rules may not share one.
    Set<String> names = new HashSet<>();
    for (Rewrite<L, D> rule : this.rules) {
      if (!names.add(rule.name())) {
        throw new IllegalArgumentException("two rules are named " + rule.name());
      }
    }
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

  /** Adds a hook, run before each iteration in the order added; returns this runner. */
  public Runner<L, D> withHook(Hook<L, D> hook) {
    hooks.add(Objects.requireNonNull(hook, "hook"));
    return this;
  }

  /** Runs to saturation or a limit and returns the report. */
  public RunReport run() {
    List<RunReport.Iteration> iterations = new ArrayList<>();
    graph.rebuild();
    scheduler.reset();
    StopReason stop = overLimit();
    int iteration = 0;
    while (stop == null) {
      // The sizes before the hooks: an iteration counts as changing nothing only if the hooks,
      // the conditions and the appliers all left them as they were, as egg's does.
      int nodesBefore = graph.numNodes();
      int classesBefore = graph.numClasses();
      stop = askHooks();
      if (stop != null) {
        break;
      }
      graph.rebuild();
      iteration++;
      // Read every match before writing any, so no rule sees this iteration's additions.
      List<List<Matcher.Match>> matches = new ArrayList<>(rules.size());
      Map<String, Integer> counts = new LinkedHashMap<>();
      for (int i = 0; i < rules.size(); i++) {
        List<Matcher.Match> found = scheduler.search(iteration, i, rules.get(i), graph);
        matches.add(found);
        counts.put(rules.get(i).name(), found.size());
      }
      int applied = 0;
      int unions = 0;
      for (int i = 0; i < rules.size(); i++) {
        for (Matcher.Match m : matches.get(i)) {
          int changed = rules.get(i).apply(graph, m);
          if (changed >= 0) {
            unions += changed;
            applied++;
          }
        }
      }
      int repaired = graph.rebuild();
      iterations.add(new RunReport.Iteration(iteration, graph.numClasses(), graph.numNodes(),
          counts, applied, unions, repaired));
      stop = overLimit();
      // As egg: the scheduler is asked whenever no rule merged two classes, whether or not
      // nodes were added, so it may release its bans then (Scheduler.canStop says so); the
      // iteration is saturation only if, besides, nothing was added.
      boolean canStop = unions == 0 && scheduler.canStop(iteration);
      boolean unchanged = graph.numNodes() == nodesBefore
          && graph.numClasses() == classesBefore;
      if (stop == null && canStop && unchanged) {
        stop = new StopReason.Saturated();
      }
      if (stop == null && iteration >= limits.iterations()) {
        stop = new StopReason.IterationLimit(iteration);
      }
    }
    return new RunReport(iterations, stop);
  }

  private StopReason askHooks() {
    for (Hook<L, D> hook : hooks) {
      Optional<String> reason = hook.beforeIteration(graph);
      if (reason.isPresent()) {
        return new StopReason.Other(reason.get());
      }
    }
    return null;
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
