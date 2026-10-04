/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.jegg;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * The exact extraction over several roots, by branch and bound, for the tests to measure
 * {@link Extractor#extractAll} against: one node per class the roots reach, the sum of the
 * chosen nodes' costs least, the chosen nodes acyclic. The bound is the cost so far plus each
 * open class's cheapest node. Exponential, so it is given a deadline and answers empty past it.
 */
final class ExactExtraction<L extends Language<L>> {

  private final EGraph<L, ?> graph;
  private final CostFunction<L> costs;
  private final long deadline;
  private final Map<Integer, Double> cheapest = new HashMap<>();
  private double best = Double.POSITIVE_INFINITY;
  private boolean timedOut;

  private ExactExtraction(EGraph<L, ?> graph, CostFunction<L> costs, long deadline) {
    this.graph = graph;
    this.costs = costs;
    this.deadline = deadline;
    for (EClass<L, ?> c : graph.classes()) {
      double min = Double.POSITIVE_INFINITY;
      for (L n : c.nodes()) {
        min = Math.min(min, costs.nodeCost(n));
      }
      cheapest.put(c.id(), min);
    }
  }

  /** The optimum, or empty if the search did not finish within the deadline. */
  static <L extends Language<L>> OptionalDouble optimum(EGraph<L, ?> graph, CostFunction<L> costs,
      IntList roots, long deadlineNanos) {
    ExactExtraction<L> e = new ExactExtraction<>(graph, costs, deadlineNanos);
    Deque<Integer> open = new ArrayDeque<>();
    Map<Integer, L> chosen = new HashMap<>();
    for (int i = 0; i < roots.size(); i++) {
      int r = graph.find(roots.get(i));
      if (!open.contains(r)) {
        open.add(r);
      }
    }
    e.search(open, chosen, 0.0);
    return e.timedOut ? OptionalDouble.empty() : OptionalDouble.of(e.best);
  }

  private void search(Deque<Integer> open, Map<Integer, L> chosen, double cost) {
    if (timedOut || System.nanoTime() > deadline) {
      timedOut = true;
      return;
    }
    if (open.isEmpty()) {
      if (cost < best && acyclic(chosen)) {
        best = cost;
      }
      return;
    }
    double bound = cost;
    for (int o : open) {
      bound += cheapest.get(o);
    }
    if (bound >= best) {
      return;
    }
    int id = open.removeFirst();
    List<L> nodes = new ArrayList<>(graph.classOf(id).nodes());
    nodes.sort((a, b) -> Double.compare(costs.nodeCost(a), costs.nodeCost(b)));
    for (L n : nodes) {
      chosen.put(id, n);
      List<Integer> pushed = new ArrayList<>();
      IntList kids = n.children();
      for (int i = 0; i < kids.size(); i++) {
        int c = graph.find(kids.get(i));
        if (!chosen.containsKey(c) && !open.contains(c)) {
          open.addLast(c);
          pushed.add(c);
        }
      }
      search(open, chosen, cost + costs.nodeCost(n));
      for (int c : pushed) {
        open.removeLastOccurrence(c);
      }
      chosen.remove(id);
      if (timedOut) {
        return;
      }
    }
    open.addFirst(id);
  }

  /** Whether the chosen nodes, followed from any class, close no cycle. */
  private boolean acyclic(Map<Integer, L> chosen) {
    Map<Integer, Integer> state = new HashMap<>();
    for (int id : chosen.keySet()) {
      if (!visit(id, chosen, state)) {
        return false;
      }
    }
    return true;
  }

  private boolean visit(int id, Map<Integer, L> chosen, Map<Integer, Integer> state) {
    Integer s = state.get(id);
    if (s != null) {
      return s == 2;
    }
    state.put(id, 1);
    IntList kids = chosen.get(id).children();
    for (int i = 0; i < kids.size(); i++) {
      if (!visit(graph.find(kids.get(i)), chosen, state)) {
        return false;
      }
    }
    state.put(id, 2);
    return true;
  }
}
