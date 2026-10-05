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
import java.util.BitSet;
import java.util.List;
import java.util.OptionalInt;
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

  /**
   * A class's best node and the cost of the tree rooted there. A value candidate (PLAN.md 3.1):
   * immutable, compared by content.
   *
   * @param <L> the language
   * @param node the cheapest node of the class
   * @param cost the cost of the tree rooted at {@code node}, finite and non-negative
   */
  public record Best<L extends Language<L>>(L node, double cost) {
  }

  private final EGraph<L, D> graph;
  private final CostFunction<L> costs;
  // The best node of each class and its tree cost, by class id; null where the class has no
  // finite-cost term. Arrays by id, not maps: ids are dense from 0, and a map would box each.
  private final Object[] bestNode;
  private final double[] bestCost;
  // The memo of one tree extraction, by class id, kept between calls and cleared of what a call
  // wrote, so that a call costs the term it builds and not the graph.
  private Object[] built;
  private final IntArray builtIds = new IntArray();
  // The graph's size when priced: a changed size means the prices are stale.
  private final int nodes;
  private final int classes;

  /**
   * Prices every class of the graph, which must be rebuilt. The extractor is a snapshot of the
   * graph as it is now: after the graph changes, build a new one. A call that sees the graph's
   * size changed refuses with an {@link IllegalStateException}; a change that keeps the size
   * goes unseen.
   *
   * @param graph the graph to extract from, rebuilt; the constructor throws
   *     {@link IllegalStateException} if it is not
   * @param costs the cost function that prices each node; its costs must be finite and
   *     non-negative
   */
  public Extractor(EGraph<L, D> graph, CostFunction<L> costs) {
    if (graph.isDirty()) {
      throw new IllegalStateException("rebuild the graph before extracting from it");
    }
    this.graph = graph;
    this.costs = costs;
    this.nodes = graph.numNodes();
    this.classes = graph.numClasses();
    this.bestNode = new Object[graph.idBound()];
    this.bestCost = new double[bestNode.length];
    findCosts();
  }

  @SuppressWarnings("unchecked")
  private L bestNode(int id) {
    return (L) bestNode[id];
  }

  private boolean hasBest(int id) {
    return bestNode[id] != null;
  }

  private void checkUnchanged() {
    if (graph.isDirty() || graph.numNodes() != nodes || graph.numClasses() != classes
        || graph.idBound() != bestNode.length) {
      throw new IllegalStateException("the graph changed since this extractor priced it");
    }
  }

  /**
   * The best node of a class and its tree cost; every class reachable from leaves has one.
   *
   * @param eclass the id of the class, canonical or not
   * @return the class's cheapest node and the cost of the tree rooted there
   * @throws IllegalStateException if the class has no finite-cost term, or if the graph changed
   *     or is dirty since this extractor priced it (a change that keeps the node, class and id
   *     counts goes unseen)
   */
  public Best<L> best(int eclass) {
    checkUnchanged();
    int root = graph.find(eclass);
    if (!hasBest(root)) {
      throw new IllegalStateException("class " + eclass + " has no finite-cost term");
    }
    return new Best<>(bestNode(root), bestCost[root]);
  }

  /**
   * The cheapest term in the class, as a tree. A class the tree reaches more than once is one
   * object, so a tree exponentially larger than the graph is built in time linear in it.
   *
   * @param eclass the id of the class to extract from, canonical or not
   * @return the term, whose root is the class's best node
   * @throws IllegalStateException if the class has no finite-cost term, or if the graph changed
   *     or is dirty since this extractor priced it (a change that keeps the node, class and id
   *     counts goes unseen)
   */
  public Extracted<L> extract(int eclass) {
    checkUnchanged();
    if (built == null) {
      built = new Object[bestNode.length];
    }
    try {
      return extract(graph.find(eclass), built);
    } finally {
      for (int i = 0; i < builtIds.size(); i++) {
        built[builtIds.get(i)] = null;
      }
      builtIds.clear();
    }
  }

  @SuppressWarnings("unchecked")
  private Extracted<L> extract(int root, Object[] built) {
    if (built[root] != null) {
      return (Extracted<L>) built[root];
    }
    if (!hasBest(root)) {
      throw new IllegalStateException("class " + root + " has no finite-cost term");
    }
    L node = bestNode(root);
    List<Extracted<L>> kids = new ArrayList<>();
    IntList children = node.children();
    for (int i = 0; i < children.size(); i++) {
      kids.add(extract(graph.find(children.get(i)), built));
    }
    Extracted<L> term = new Extracted<>(root, node, kids);
    built[root] = term;
    builtIds.add(root);
    return term;
  }

  // The fixed point of egg's find_costs: a class's cost is the cheapest of its nodes' costs
  // given its children's costs so far, until no class gets cheaper. Classes in id order and
  // nodes in insertion order; a node replaces the class's best only when strictly cheaper. A
  // class's own cost is stored after its nodes are priced, as egg's make_pass does, so a node
  // with the class itself as a child reads the previous pass's cost, not this one's.
  private void findCosts() {
    boolean changed = true;
    while (changed) {
      changed = false;
      for (EClass<L, D> eclass : graph.classes()) {
        int id = eclass.id();
        L current = bestNode(id);
        double currentCost = bestCost[id];
        for (L node : eclass.nodes()) {
          double c = costIfKnown(node);
          if (!Double.isNaN(c) && (current == null || c < currentCost)) {
            current = node;
            currentCost = c;
            changed = true;
          }
        }
        bestNode[id] = current;
        bestCost[id] = currentCost;
      }
    }
  }

  private double costIfKnown(L node) {
    if (!priced(node)) {
      return Double.NaN;
    }
    // The node's own price is checked too: a negative one can hide in a non-negative tree.
    nodeCost(node);
    return checked(node, costs.cost(node, id -> bestCost[graph.find(id)]));
  }

  /** The node's own cost, checked. */
  private double nodeCost(L node) {
    return checked(node, costs.nodeCost(node));
  }

  private static double checked(Object node, double cost) {
    if (!(cost >= 0.0) || cost == Double.POSITIVE_INFINITY) {
      throw new IllegalArgumentException("the cost function priced " + node + " at " + cost
          + "; costs must be finite and non-negative");
    }
    return cost;
  }

  /**
   * {@link #extractAll(IntList, ToDoubleFunction)} minimising the selection's summed cost.
   *
   * @param roots the ids of the classes to extract, canonical or not, in the order of the terms
   *     wanted back
   * @return the selection covering every class the roots reach
   */
  public Selection<L> extractAll(IntList roots) {
    return extractAll(roots, null);
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
   * selection scores lower. A change that scores higher by itself but brings new classes into
   * the selection - an investment others could share - is held while the selected parents of
   * those classes are offered their nodes that use them, and kept with what they took if the
   * whole scores lower: the move sharing needs, since a decomposition one root takes costs more
   * until the others take it too. The descent repeats until a pass keeps nothing. Deterministic
   * (class order, node order, strict improvement) and bounded, as every kept change lowers the
   * score; the exact problem is the ILP the plan leaves out.
   *
   * <p>The selection's cost sums {@link CostFunction#nodeCost}; an overridden
   * {@link CostFunction#cost}, such as {@link CostFunction#astDepth}'s, is a tree's cost and is
   * not used here. A cost that is not a sum over nodes belongs in {@code score}, which is then
   * applied to each candidate selection; the default sum is kept incrementally and costs each
   * candidate only what it changes.
   *
   * @param roots the ids of the classes to extract, canonical or not, in the order of the terms
   *     wanted back
   * @param score the cost of a whole candidate selection, lower being better, or null for the
   *     sum of the chosen nodes' own costs
   * @return the selection covering every class the roots reach
   * @throws IllegalStateException if a root has no finite-cost term, or if the graph changed or
   *     is dirty since this extractor priced it
   */
  public Selection<L> extractAll(IntList roots, ToDoubleFunction<Selection<L>> score) {
    checkUnchanged();
    IntList canonical = roots.map(graph::find);
    for (int i = 0; i < canonical.size(); i++) {
      if (!hasBest(canonical.get(i))) {
        throw new IllegalStateException("root " + roots.get(i) + " has no finite-cost term");
      }
    }
    Descent descent = started(canonical, score);
    descent.run();
    return descent.selection();
  }

  /** A descent started from the greedy choice, or from the tree choice if that closes a cycle. */
  private Descent started(IntList canonical, ToDoubleFunction<Selection<L>> score) {
    Descent descent = new Descent(canonical, score);
    if (!descent.start(greedy())) {
      // The greedy choices, each made against its children's choices at the time, can close
      // a cycle once a child changes; the tree choice, with costs non-negative, cannot, so it
      // is the start instead.
      if (!descent.start(null)) {
        throw new IllegalStateException("the tree choice closed a cycle");
      }
    }
    return descent;
  }

  /**
   * The greedy start of {@link #extractAll} as a selection, before any descent: what the tests
   * compare the descent against.
   */
  Selection<L> greedyStart(IntList roots) {
    checkUnchanged();
    return started(roots.map(graph::find), null).selection();
  }

  /** Whether every child of the node has a finite-cost term, so the node can be chosen. */
  private boolean priced(L node) {
    IntList children = node.children();
    for (int i = 0; i < children.size(); i++) {
      if (!hasBest(graph.find(children.get(i)))) {
        return false;
      }
    }
    return true;
  }

  /**
   * The state of one extraction over several roots: the node assigned to each class, how many
   * selected parents (and roots) refer to each class - it is selected while that is positive -
   * and the running sum of the selected nodes' costs. A change of one class's node releases the
   * old node's children and acquires the new node's, a cascade over the classes whose count
   * reaches zero or leaves it, through an undo log; so a candidate costs what it touches, and a
   * rejected one is reverted in the same cost.
   */
  private final class Descent {
    private static final double EPSILON = 1e-9;

    private final IntList roots;
    private final ToDoubleFunction<Selection<L>> score;
    private final Object[] assigned;
    private final int[] refs;
    private double cost;
    // The undo log: a class whose count changed and its old count, and a class whose node
    // changed and its old node, in order; parallel arrays, so an entry is no object.
    private int[] logIds = new int[64];
    private int[] logRefs = new int[64];
    private int logSize;
    private int[] logNodeIds = new int[16];
    private Object[] logNodes = new Object[16];
    private int nodeLogSize;
    // The classes a change newly brought into the selection, when asked to collect them.
    private final IntArray added = new IntArray();
    private boolean collecting;
    // For the cycle walk: the classes seen in the current walk.
    private final int[] seen;
    private int walk;

    Descent(IntList roots, ToDoubleFunction<Selection<L>> score) {
      this.roots = roots;
      this.score = score;
      int size = bestNode.length;
      assigned = new Object[size];
      refs = new int[size];
      seen = new int[size];
    }

    @SuppressWarnings("unchecked")
    private L node(int id) {
      return (L) assigned[id];
    }

    /**
     * Takes an assignment (a node per class id, null where the class's best node stands; the
     * whole array null for the best nodes alone) and selects from the roots; false, and nothing
     * kept, on a cycle.
     */
    boolean start(Object[] assignment) {
      System.arraycopy(bestNode, 0, assigned, 0, assigned.length);
      java.util.Arrays.fill(refs, 0);
      cost = 0.0;
      logSize = 0;
      nodeLogSize = 0;
      if (assignment != null) {
        for (int id = 0; id < assignment.length; id++) {
          if (assignment[id] != null) {
            assigned[id] = assignment[id];
          }
        }
      }
      byte[] color = new byte[assigned.length];
      for (int i = 0; i < roots.size(); i++) {
        if (cyclic(roots.get(i), color)) {
          return false;
        }
      }
      for (int i = 0; i < roots.size(); i++) {
        acquire(roots.get(i));
      }
      logSize = 0;
      return true;
    }

    /** Whether a cycle is reached from {@code id} through the assigned nodes: a gray hit. */
    private boolean cyclic(int id, byte[] color) {
      if (color[id] == 2) {
        return false;
      }
      if (color[id] == 1) {
        return true;
      }
      color[id] = 1;
      IntList children = node(id).children();
      for (int i = 0; i < children.size(); i++) {
        if (cyclic(graph.find(children.get(i)), color)) {
          return true;
        }
      }
      color[id] = 2;
      return false;
    }

    /** Whether {@code target} is reached from {@code from}'s children through assigned nodes. */
    private boolean reaches(L from, int target) {
      walk++;
      return reachesFrom(from, target);
    }

    private boolean reachesFrom(L from, int target) {
      IntList children = from.children();
      for (int i = 0; i < children.size(); i++) {
        int child = graph.find(children.get(i));
        if (child == target) {
          return true;
        }
        if (seen[child] != walk) {
          seen[child] = walk;
          if (reachesFrom(node(child), target)) {
            return true;
          }
        }
      }
      return false;
    }

    private void acquire(int id) {
      logRef(id);
      if (++refs[id] == 1) {
        L n = node(id);
        cost += nodeCost(n);
        if (collecting) {
          added.add(id);
        }
        IntList children = n.children();
        for (int i = 0; i < children.size(); i++) {
          acquire(graph.find(children.get(i)));
        }
      }
    }

    private void release(int id) {
      logRef(id);
      if (--refs[id] == 0) {
        L n = node(id);
        cost -= nodeCost(n);
        IntList children = n.children();
        for (int i = 0; i < children.size(); i++) {
          release(graph.find(children.get(i)));
        }
      }
    }

    private void logRef(int id) {
      if (logSize == logIds.length) {
        logIds = java.util.Arrays.copyOf(logIds, logSize * 2);
        logRefs = java.util.Arrays.copyOf(logRefs, logSize * 2);
      }
      logIds[logSize] = id;
      logRefs[logSize] = refs[id];
      logSize++;
    }

    private void logNode(int id, L old) {
      if (nodeLogSize == logNodeIds.length) {
        logNodeIds = java.util.Arrays.copyOf(logNodeIds, nodeLogSize * 2);
        logNodes = java.util.Arrays.copyOf(logNodes, nodeLogSize * 2);
      }
      logNodeIds[nodeLogSize] = id;
      logNodes[nodeLogSize] = old;
      nodeLogSize++;
    }

    /** A mark to undo to: the log's size and the cost, with the node log's size. */
    private long mark() {
      return ((long) logSize << 32) | nodeLogSize;
    }

    private void undo(long mark, double costBefore) {
      int toRefs = (int) (mark >>> 32);
      int toNodes = (int) mark;
      while (logSize > toRefs) {
        logSize--;
        refs[logIds[logSize]] = logRefs[logSize];
      }
      while (nodeLogSize > toNodes) {
        nodeLogSize--;
        assigned[logNodeIds[nodeLogSize]] = logNodes[nodeLogSize];
      }
      cost = costBefore;
    }

    /**
     * Changes the selected class {@code id} to {@code n}, if that closes no cycle; true if it
     * did. The classes newly selected are collected into {@code added} when asked.
     */
    private boolean change(int id, L n, boolean collect) {
      if (reaches(n, id)) {
        return false;
      }
      L old = node(id);
      logNode(id, old);
      cost += nodeCost(n) - nodeCost(old);
      assigned[id] = n;
      // The new node's children first, then the old node's released: a class both reach keeps
      // its count above zero and is not collected as a newcomer.
      collecting = collect;
      added.clear();
      IntList children = n.children();
      for (int i = 0; i < children.size(); i++) {
        acquire(graph.find(children.get(i)));
      }
      collecting = false;
      IntList oldChildren = old.children();
      for (int i = 0; i < oldChildren.size(); i++) {
        release(graph.find(oldChildren.get(i)));
      }
      return true;
    }

    /** The score of the state: the running sum, or the client's score of its selection. */
    private double evaluate() {
      return score == null ? cost : score.applyAsDouble(selection());
    }

    void run() {
      double current = evaluate();
      boolean kept = true;
      while (kept) {
        kept = false;
        for (EClass<L, D> eclass : graph.classes()) {
          int id = eclass.id();
          if (refs[id] == 0) {
            continue;
          }
          for (L n : eclass.nodes()) {
            if (refs[id] == 0 || n.equals(node(id)) || !priced(n)) {
              continue;
            }
            // Nothing undoes past a top-level candidate, so the logs start afresh at each.
            logSize = 0;
            nodeLogSize = 0;
            long mark = mark();
            double costBefore = cost;
            if (!change(id, n, true)) {
              continue;
            }
            double changed = evaluate();
            if (changed < current - EPSILON) {
              current = changed;
              kept = true;
              continue;
            }
            // Held: the change brought classes in; the selected parents of those classes are
            // offered their nodes that use them, each kept if it lowers the score, and the
            // whole kept if the total fell.
            if (!added.isEmpty()) {
              double settled = offerToParents(id, added.toArray(), changed);
              if (settled < current - EPSILON) {
                current = settled;
                kept = true;
                continue;
              }
            }
            undo(mark, costBefore);
          }
        }
      }
    }

    /**
     * For each class newly selected, each selected parent of it other than {@code held} is
     * offered the parent's nodes that have it as a child, each kept if it lowers the score from
     * {@code score}; the score settled at. A parent entry may be a node under an older form,
     * with children since merged, so it is canonicalised and its class read from the hashcons.
     */
    private double offerToParents(int held, int[] newClasses, double score) {
      double current = score;
      for (int newClass : newClasses) {
        for (EClass.Parent<L> parent : graph.classOf(newClass).parents()) {
          L pn = graph.canonicalize(parent.node());
          OptionalInt in = graph.lookup(pn);
          if (in.isEmpty()) {
            continue;
          }
          int pc = in.getAsInt();
          // lookup finds a node pruned from its class too (EGraph.retainNodes); only a node the
          // class lists may be chosen for it.
          if (pc == held || refs[pc] == 0 || pn.equals(node(pc)) || !priced(pn)
              || !graph.classOf(pc).nodes().contains(pn)) {
            continue;
          }
          long mark = mark();
          double costBefore = cost;
          if (!change(pc, pn, false)) {
            continue;
          }
          double changed = evaluate();
          if (changed < current - EPSILON) {
            current = changed;
          } else {
            undo(mark, costBefore);
          }
        }
      }
      return current;
    }

    /** The selection the state holds: the classes referred to, with their nodes, in id order. */
    Selection<L> selection() {
      int count = 0;
      for (int id = 0; id < refs.length; id++) {
        if (refs[id] > 0) {
          count++;
        }
      }
      int[] ids = new int[count];
      Object[] chosen = new Object[count];
      double total = 0.0;
      int k = 0;
      for (int id = 0; id < refs.length; id++) {
        if (refs[id] > 0) {
          L n = node(id);
          ids[k] = id;
          chosen[k] = n;
          k++;
          total += nodeCost(n);
        }
      }
      return new Selection<>(ids, chosen, roots, total);
    }
  }

  // The greedy start: a fixed point in which a class's choice is the node whose DAG costs
  // least, a subterm two children share counted once.
  private Object[] greedy() {
    Object[] choice = new Object[bestNode.length];
    boolean changed = true;
    while (changed) {
      changed = false;
      for (EClass<L, D> eclass : graph.classes()) {
        int id = eclass.id();
        Choice<L> current = choice(choice, id);
        for (L node : eclass.nodes()) {
          Choice<L> candidate = dagChoice(id, node, choice);
          if (candidate != null && (current == null || candidate.cost() < current.cost())) {
            current = candidate;
            changed = true;
          }
        }
        if (current != null) {
          choice[id] = current;
        }
      }
    }
    Object[] assigned = new Object[choice.length];
    for (int id = 0; id < choice.length; id++) {
      if (choice[id] != null) {
        assigned[id] = choice(choice, id).node();
      }
    }
    return assigned;
  }

  /**
   * A class's DAG choice: the node, the classes its DAG covers, and their nodes' costs summed. A
   * value candidate: immutable once made, compared by content.
   */
  private record Choice<L extends Language<L>>(L node, BitSet classes, double cost) {
  }

  @SuppressWarnings("unchecked")
  private Choice<L> choice(Object[] choice, int id) {
    return (Choice<L>) choice[id];
  }

  private Choice<L> dagChoice(int id, L node, Object[] choice) {
    BitSet classes = new BitSet();
    classes.set(id);
    IntList children = node.children();
    for (int i = 0; i < children.size(); i++) {
      Choice<L> child = choice(choice, graph.find(children.get(i)));
      if (child == null || child.classes().get(id)) {
        // Not priced yet, or a DAG through this class itself: a cycle, never a term.
        return null;
      }
      classes.or(child.classes());
    }
    double total = 0.0;
    for (int c = classes.nextSetBit(0); c >= 0; c = classes.nextSetBit(c + 1)) {
      total += nodeCost(c == id ? node : choice(choice, c).node());
    }
    return new Choice<>(node, classes, total);
  }
}
