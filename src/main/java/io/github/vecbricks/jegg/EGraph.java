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
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * An e-graph: a union-find over e-class ids, a hashcons from canonical e-nodes to the class each
 * is in, and the classes themselves - egg's {@code EGraph}, the paper's Figure 4, with the
 * data structures in plain Java.
 *
 * <p>This commit holds the part of the graph that grows: {@link #add} with its canonicalisation
 * and hashcons lookup, {@link #find}, and {@link #addTree} through a {@link TreeBridge}. Merging
 * and the deferred rebuild that restores congruence come next, as the plan sequences them.
 *
 * <p><b>Order is fixed.</b> Ids are assigned by insertion order and a class's nodes and parents
 * are kept in insertion order, so everything the graph exposes is a function of the sequence of
 * calls that built it; the hashcons is a {@link HashMap}, but nothing iterates over it.
 *
 * @param <L> the language
 * @param <D> the analysis fact, {@code Void} under {@link Analysis#none}
 */
public final class EGraph<L extends Language<L>, D> {

  private final Analysis<L, D> analysis;
  private final UnionFind unionFind = new UnionFind();
  private final Map<L, Integer> hashcons = new HashMap<>();
  // Indexed by id; an entry is null once its id is no longer a root.
  private final List<EClass<L, D>> classes = new ArrayList<>();
  private int numNodes = 0;

  public EGraph(Analysis<L, D> analysis) {
    this.analysis = Objects.requireNonNull(analysis, "analysis");
  }

  /** A graph with no analysis. */
  public static <L extends Language<L>> EGraph<L, Void> withoutAnalysis() {
    return new EGraph<L, Void>(Analysis.<L>none());
  }

  public Analysis<L, D> analysis() {
    return analysis;
  }

  /** The root id of {@code id}'s class. */
  public int find(int id) {
    return unionFind.find(id);
  }

  /** {@code node} with every child replaced by its class's root. */
  public L canonicalize(L node) {
    return node.withChildren(node.children().map(unionFind::find));
  }

  /**
   * The class {@code node} is in, if it is in the graph; {@code node} need not be canonical.
   * Reads the hashcons and changes nothing.
   */
  public OptionalInt lookup(L node) {
    Integer id = hashcons.get(canonicalize(node));
    return id == null ? OptionalInt.empty() : OptionalInt.of(unionFind.find(id));
  }

  /**
   * Adds {@code node} and returns its class: the class already holding an equal canonical node,
   * or a new class of this node alone, recorded as a parent of each child's class (paper
   * Figure 4, lines 1-11). The children must be ids this graph issued.
   */
  public int add(L node) {
    L canonical = canonicalize(node);
    Integer existing = hashcons.get(canonical);
    if (existing != null) {
      return unionFind.find(existing);
    }
    int id = unionFind.makeSet();
    EClass<L, D> eclass = new EClass<>(id, null);
    classes.add(eclass);
    eclass.addNode(canonical);
    IntList children = canonical.children();
    for (int i = 0; i < children.size(); i++) {
      classOf(children.get(i)).addParent(canonical, id);
    }
    hashcons.put(canonical, id);
    numNodes++;
    eclass.setData(analysis.make(this, canonical));
    analysis.modify(this, id);
    return unionFind.find(id);
  }

  /**
   * Adds a client's tree bottom-up through {@code bridge} and returns the root's class. A
   * subtree object reached twice (a DAG) is added once; equal subtrees reached through different
   * objects hashcons to the same class anyway.
   */
  public <T> int addTree(T root, TreeBridge<T, L> bridge) {
    return addTree(root, bridge, new IdentityHashMap<>());
  }

  private <T> int addTree(T tree, TreeBridge<T, L> bridge, Map<T, Integer> seen) {
    Integer done = seen.get(tree);
    if (done != null) {
      return unionFind.find(done);
    }
    List<T> subtrees = bridge.childrenOf(tree);
    int[] ids = new int[subtrees.size()];
    for (int i = 0; i < ids.length; i++) {
      ids[i] = addTree(subtrees.get(i), bridge, seen);
    }
    int id = add(bridge.node(tree, IntList.of(ids)));
    seen.put(tree, id);
    return id;
  }

  /** The class with this root id. */
  public EClass<L, D> classOf(int id) {
    EClass<L, D> eclass = classes.get(unionFind.find(id));
    if (eclass == null) {
      throw new IllegalStateException("no class is rooted at " + id);
    }
    return eclass;
  }

  /** The analysis fact of {@code id}'s class. */
  public D data(int id) {
    return classOf(id).data();
  }

  /** The live classes, in id order, read-only. */
  public List<EClass<L, D>> classes() {
    List<EClass<L, D>> live = new ArrayList<>();
    for (EClass<L, D> eclass : classes) {
      if (eclass != null) {
        live.add(eclass);
      }
    }
    return Collections.unmodifiableList(live);
  }

  /** How many classes are live. */
  public int numClasses() {
    int n = 0;
    for (EClass<L, D> eclass : classes) {
      if (eclass != null) {
        n++;
      }
    }
    return n;
  }

  /** How many distinct e-nodes the graph holds. */
  public int numNodes() {
    return numNodes;
  }

  /**
   * Checks the hashcons invariant (paper Definition 2.7): every node of every live class is
   * canonical and maps in the hashcons to that class, and every hashcons entry's node is in the
   * class it maps to. For tests and debugging; throws {@link IllegalStateException} naming the
   * first violation.
   */
  public void checkHashconsInvariant() {
    int counted = 0;
    for (EClass<L, D> eclass : classes()) {
      for (L node : eclass.nodes()) {
        counted++;
        if (!node.equals(canonicalize(node))) {
          throw new IllegalStateException("class " + eclass.id() + " holds a non-canonical node "
              + node);
        }
        Integer mapped = hashcons.get(node);
        if (mapped == null || unionFind.find(mapped) != eclass.id()) {
          throw new IllegalStateException("node " + node + " of class " + eclass.id()
              + " maps to " + (mapped == null ? "nothing" : unionFind.find(mapped)));
        }
      }
    }
    if (counted != hashcons.size()) {
      throw new IllegalStateException(counted + " nodes in classes, " + hashcons.size()
          + " in the hashcons");
    }
  }
}
