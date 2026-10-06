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
import java.util.List;

/**
 * An equivalence class of e-nodes: its id, its nodes, its parents and its analysis fact.
 *
 * <p>The nodes are kept in the order they were added, which with ids assigned in order makes
 * every iteration over a class a function of the input. The parents are the e-nodes that have
 * this class as a child, with the class each was added to, which is what rebuilding reads to
 * find the nodes a merge made congruent.
 *
 * @param <L> the language
 * @param <D> the analysis fact
 */
public final class EClass<L extends Language<L>, D> {

  /**
   * A parent entry: an e-node that has the class as a child, and the class it was added to. The
   * entry made when the node was added is shared by the lists of all its children, and
   * {@code rebuild} re-keys it in place when a child's class is merged, so every list and the
   * hashcons name the node by one form. When a merge makes two nodes one, the older entry stands
   * for both from then on, and a repair drops the newer from each list it repairs. Two entries
   * are equal when they name the same node and class; since a rebuild changes both in place, an
   * entry is not a stable key for a hash table while the graph changes.
   *
   * @param <L> the language
   */
  static final class Parent<L> {
    private L node;
    private int classId;
    // The order the entries were made in: of two entries a merge makes congruent, the older
    // survives in every list, which is what keeps one entry shared by all of a node's lists.
    private final int serial;

    /**
     * A parent entry with serial 0, for a caller outside the e-graph.
     *
     * @param node the e-node, which has the class as a child
     * @param classId the id of the class the node was added to
     */
    public Parent(L node, int classId) {
      this(node, classId, 0);
    }

    Parent(L node, int classId, int serial) {
      this.node = node;
      this.classId = classId;
      this.serial = serial;
    }

    int serial() {
      return serial;
    }

    /**
     * The node, in the form the hashcons holds it under.
     *
     * @return the e-node; canonical once the graph is rebuilt
     */
    public L node() {
      return node;
    }

    /**
     * The class the node was added to, or a root it was merged into since; not always a root.
     *
     * @return an e-class id; pass it through {@code find} for the canonical id
     */
    public int classId() {
      return classId;
    }

    void rekey(L node, int classId) {
      this.node = node;
      this.classId = classId;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Parent<?> p && classId == p.classId && node.equals(p.node);
    }

    @Override
    public int hashCode() {
      return 31 * node.hashCode() + classId;
    }

    @Override
    public String toString() {
      return "Parent[node=" + node + ", classId=" + classId + "]";
    }
  }

  private final int id;
  private final List<L> nodes = new ArrayList<>();
  private final List<Parent<L>> parents = new ArrayList<>();
  private D data;
  // Whether retainNodes ever dropped a node of this class: then a parent entry naming this class
  // may be a dropped node, and repair tells by the node list, not by the entry's form.
  private boolean pruned;
  // Whether another class's nodes were merged into this one since its list was last put in
  // canonical form: two classes can hold one canonical node between a merge and its rebuild
  // (an add can miss the node under a stale key), so the merged list may hold a duplicate
  // without any node being stale.
  private boolean mergedNodes;

  EClass(int id, D data) {
    this.id = id;
    this.data = data;
  }

  /**
   * The class's id, which is a root of the union-find while the class is live.
   *
   * @return the canonical e-class id
   */
  public int id() {
    return id;
  }

  /**
   * The nodes, in insertion order, as a read-only view of the live list: {@code add},
   * {@code merge} and {@code rebuild} change it, so a caller that will change the graph while
   * iterating - an applier, a condition - copies it first.
   *
   * @return the class's e-nodes, never empty for a live class
   */
  public List<L> nodes() {
    return Collections.unmodifiableList(nodes);
  }

  /**
   * The parent entries, in insertion order, a read-only view of the live list, as above.
   *
   * @return the e-nodes having this class as a child, each with the class it was added to
   */
  List<Parent<L>> parents() {
    return Collections.unmodifiableList(parents);
  }

  /**
   * The analysis fact, {@code null} under {@link Analysis#none}.
   *
   * @return the fact of this class as last joined
   */
  public D data() {
    return data;
  }

  boolean hasPruned() {
    return pruned;
  }

  void markPruned() {
    pruned = true;
  }

  boolean hasMergedNodes() {
    return mergedNodes;
  }

  void setMergedNodes(boolean mergedNodes) {
    this.mergedNodes = mergedNodes;
  }

  void setData(D data) {
    this.data = data;
  }

  void addNode(L node) {
    nodes.add(node);
  }

  void addParent(Parent<L> entry) {
    parents.add(entry);
  }

  List<L> mutableNodes() {
    return nodes;
  }

  List<Parent<L>> mutableParents() {
    return parents;
  }
}
