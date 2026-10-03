/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;

/**
 * Extraction: the cheapest term in a class under a {@link CostFunction}, as a bottom-up fixed
 * point over the classes (the paper's section 4.3, egg's {@code Extractor}); and
 * {@link #extractAll} over several roots at once, which chooses one node per class so that a
 * subterm the roots share is chosen once and paid once - what egg's per-root extraction cannot
 * do, and the case a compiler with many outputs over shared prefixes is in (PLAN.md 3.2).
 *
 * <p>Ties are broken by the class's node order, which is insertion order, so what is extracted
 * is a function of the graph.
 *
 * @param <L> the language
 * @param <D> the analysis fact
 */
public final class Extractor<L extends Language<L>, D> {

  /** A class's best node and the cost of the tree rooted there. */
  public record Best<L extends Language<L>>(L node, double cost) {
  }

  private final EGraph<L, D> graph;
  private final CostFunction<L> costs;
  private final Map<Integer, Best<L>> best = new HashMap<>();

  /** Prices every class of the graph; the graph must be rebuilt. */
  public Extractor(EGraph<L, D> graph, CostFunction<L> costs) {
    if (graph.isDirty()) {
      throw new IllegalStateException("rebuild the graph before extracting from it");
    }
    this.graph = graph;
    this.costs = costs;
    findCosts();
  }

  /** The best node of a class and its tree cost; every class reachable from leaves has one. */
  public Best<L> best(int eclass) {
    Best<L> b = best.get(graph.find(eclass));
    if (b == null) {
      throw new IllegalStateException("class " + eclass + " has no finite-cost term");
    }
    return b;
  }

  /** The cheapest term in the class, as a tree. */
  public Extracted<L> extract(int eclass) {
    int root = graph.find(eclass);
    L node = best(root).node();
    List<Extracted<L>> kids = node.children().stream().mapToObj(this::extract).toList();
    return new Extracted<>(root, node, kids);
  }

  // The fixed point of egg's find_costs: a class's cost is the cheapest of its nodes' costs
  // given its children's costs so far, until no class gets cheaper. Classes in id order and
  // nodes in insertion order; a node replaces the class's best only when strictly cheaper.
  private void findCosts() {
    boolean changed = true;
    while (changed) {
      changed = false;
      for (EClass<L, D> eclass : graph.classes()) {
        Best<L> current = best.get(eclass.id());
        for (L node : eclass.nodes()) {
          double c = costIfKnown(node);
          if (!Double.isNaN(c) && (current == null || c < current.cost())) {
            current = new Best<>(node, c);
            changed = true;
          }
        }
        if (current != null) {
          best.put(eclass.id(), current);
        }
      }
    }
  }

  private double costIfKnown(L node) {
    IntList children = node.children();
    for (int i = 0; i < children.size(); i++) {
      if (!best.containsKey(graph.find(children.get(i)))) {
        return Double.NaN;
      }
    }
    return costs.cost(node, id -> best.get(graph.find(id)).cost());
  }

  /** {@link #extractAll(IntList, ToDoubleFunction)} minimising the selection's summed cost. */
  public Selection<L> extractAll(IntList roots) {
    return extractAll(roots, Selection::cost);
  }

  /**
   * One node per class for every class the roots reach, chosen so that {@code score} of the
   * whole selection is small - by default the sum of the chosen nodes' own costs, each class
   * once, so a subterm two roots share is chosen once and paid once.
   *
   * <p>A greedy start, then a descent. The start gives each class the node whose DAG - itself
   * and its children's chosen DAGs, as a set of classes - costs least: the best DAG for the
   * class alone, which is not the best for the union of the roots. The descent then tries, for
   * each class the selection covers in id order, each of its other nodes, and keeps it if the
   * selection scores lower; a change that scores higher by itself is held while a descent over
   * the other classes settles them, and kept with what they settled if the whole scores lower.
   * The second move is the one sharing needs: a decomposition one root takes costs more until
   * the other roots take it too. It repeats until a pass keeps nothing. Deterministic (class
   * order, node order, strict improvement) and bounded, as every kept change lowers the score;
   * the exact problem is the ILP the plan leaves out.
   */
  public Selection<L> extractAll(IntList roots, ToDoubleFunction<Selection<L>> score) {
    IntList canonical = roots.map(graph::find);
    for (int i = 0; i < canonical.size(); i++) {
      if (!best.containsKey(canonical.get(i))) {
        throw new IllegalStateException("root " + roots.get(i) + " has no finite-cost term");
      }
    }
    Map<Integer, L> assigned = greedy();
    Selection<L> start = select(canonical, assigned);
    if (start == null) {
      // The greedy choices, each made against its children's choices at the time, can close
      // a cycle once a child changes; the tree choice cannot, so it is the start instead.
      assigned = new HashMap<>();
      for (Map.Entry<Integer, Best<L>> e : best.entrySet()) {
        assigned.put(e.getKey(), e.getValue().node());
      }
      start = select(canonical, assigned);
    }
    return descend(canonical, assigned, start, score, NONE);
  }

  private static final int NONE = -1;

  /**
   * The descent from {@code start}, changing {@code assigned} in place; {@code pinned} is a
   * class held fixed, or {@link #NONE} at the top level, which alone tries the held move.
   */
  private Selection<L> descend(IntList roots, Map<Integer, L> assigned, Selection<L> start,
      ToDoubleFunction<Selection<L>> score, int pinned) {
    Selection<L> current = start;
    double currentScore = score.applyAsDouble(current);
    boolean kept = true;
    while (kept) {
      kept = false;
      for (Integer id = current.firstClass(); id != null; id = current.classAfter(id)) {
        if (id == pinned) {
          continue;
        }
        for (L node : graph.classOf(id).nodes()) {
          L old = assigned.get(id);
          if (node.equals(old) || !priced(node)) {
            continue;
          }
          assigned.put(id, node);
          Selection<L> candidate = select(roots, assigned);
          if (candidate == null) {
            assigned.put(id, old);
            continue;
          }
          double candidateScore = score.applyAsDouble(candidate);
          if (candidateScore >= currentScore && pinned == NONE) {
            Map<Integer, L> held = new HashMap<>(assigned);
            Selection<L> settled = descend(roots, held, candidate, score, id);
            double settledScore = score.applyAsDouble(settled);
            if (settledScore < currentScore) {
              assigned.putAll(held);
              candidate = settled;
              candidateScore = settledScore;
            }
          }
          if (candidateScore < currentScore) {
            current = candidate;
            currentScore = candidateScore;
            kept = true;
          } else {
            assigned.put(id, old);
          }
        }
      }
    }
    return current;
  }

  /** Whether every child of the node has a finite-cost term, so the node can be chosen. */
  private boolean priced(L node) {
    IntList children = node.children();
    for (int i = 0; i < children.size(); i++) {
      if (!best.containsKey(graph.find(children.get(i)))) {
        return false;
      }
    }
    return true;
  }

  /**
   * The selection the assignment gives the roots: the classes reached from them through the
   * assigned nodes, or null if those nodes close a cycle.
   */
  private Selection<L> select(IntList roots, Map<Integer, L> assigned) {
    TreeMap<Integer, L> chosen = new TreeMap<>();
    BitSet onPath = new BitSet();
    for (int i = 0; i < roots.size(); i++) {
      if (!reach(roots.get(i), assigned, chosen, onPath)) {
        return null;
      }
    }
    double total = 0.0;
    for (L node : chosen.values()) {
      total += costs.nodeCost(node);
    }
    return new Selection<>(chosen, roots, total);
  }

  private boolean reach(int id, Map<Integer, L> assigned, TreeMap<Integer, L> chosen,
      BitSet onPath) {
    if (onPath.get(id)) {
      return false;
    }
    if (chosen.containsKey(id)) {
      return true;
    }
    L node = assigned.get(id);
    onPath.set(id);
    IntList children = node.children();
    for (int i = 0; i < children.size(); i++) {
      if (!reach(graph.find(children.get(i)), assigned, chosen, onPath)) {
        return false;
      }
    }
    onPath.clear(id);
    chosen.put(id, node);
    return true;
  }

  // The greedy start: a fixed point in which a class's choice is the node whose DAG costs
  // least, a subterm two children share counted once.
  private Map<Integer, L> greedy() {
    Map<Integer, Choice<L>> choice = new HashMap<>();
    boolean changed = true;
    while (changed) {
      changed = false;
      for (EClass<L, D> eclass : graph.classes()) {
        Choice<L> current = choice.get(eclass.id());
        for (L node : eclass.nodes()) {
          Choice<L> candidate = dagChoice(eclass.id(), node, choice);
          if (candidate != null && (current == null || candidate.cost < current.cost)) {
            current = candidate;
            changed = true;
          }
        }
        if (current != null) {
          choice.put(eclass.id(), current);
        }
      }
    }
    Map<Integer, L> assigned = new HashMap<>();
    choice.forEach((id, c) -> assigned.put(id, c.node));
    return assigned;
  }

  /** A class's DAG choice: the node, the classes its DAG covers, and their nodes' costs summed. */
  private static final class Choice<L extends Language<L>> {
    final L node;
    final BitSet classes;
    final double cost;

    Choice(L node, BitSet classes, double cost) {
      this.node = node;
      this.classes = classes;
      this.cost = cost;
    }
  }

  private Choice<L> dagChoice(int id, L node, Map<Integer, Choice<L>> choice) {
    BitSet classes = new BitSet();
    classes.set(id);
    IntList children = node.children();
    for (int i = 0; i < children.size(); i++) {
      Choice<L> child = choice.get(graph.find(children.get(i)));
      if (child == null) {
        return null;
      }
      classes.or(child.classes);
    }
    double total = 0.0;
    for (int c = classes.nextSetBit(0); c >= 0; c = classes.nextSetBit(c + 1)) {
      total += costs.nodeCost(c == id ? node : choice.get(c).node);
    }
    return new Choice<>(node, classes, total);
  }
}
