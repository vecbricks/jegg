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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import org.jspecify.annotations.Nullable;

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
public final class EClass<L extends Language<L>, D extends @Nullable Object> {

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

  /**
   * The class's nodes laid flat for the matcher: the positions of the nodes sorted by head
   * ordinal, insertion order kept within a head, and every node's children in one array, so a
   * pattern node finds the run of its head by binary search and reads arities and children as
   * consecutive ints, touching no node object. Built on first use after a change of the list,
   * and dropped by the next change.
   */
  static final class Table {
    /** The head ordinal of each entry, ascending. */
    final int[] heads;
    /** The position in the node list of each entry; ascending within a head. */
    final int[] positions;
    /** Where each entry's children start in {@link #kids}; one more entry marks the end. */
    final int[] starts;
    /** Every node's children, in entry order. */
    final int[] kids;

    Table(int[] heads, int[] positions, int[] starts, int[] kids) {
      this.heads = heads;
      this.positions = positions;
      this.starts = starts;
      this.kids = kids;
    }

    /** The arity of the entry. */
    int arity(int entry) {
      return starts[entry + 1] - starts[entry];
    }

    /** The first entry whose head is {@code headId} or above: the start of the head's run. */
    int lower(int headId) {
      int lo = 0;
      int hi = heads.length;
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (heads[mid] < headId) {
          lo = mid + 1;
        } else {
          hi = mid;
        }
      }
      return lo;
    }

    /** The first entry at or after {@code from} whose head is above {@code headId}. */
    int upper(int headId, int from) {
      int lo = from;
      int hi = heads.length;
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (heads[mid] <= headId) {
          lo = mid + 1;
        } else {
          hi = mid;
        }
      }
      return lo;
    }
  }

  private final int id;
  private final List<L> nodes = new ArrayList<>();
  // The head ordinal of each node ({@code EGraph.headOrdinal}), parallel to the list: computed
  // once when the node is added, so the table is built and a head compared without making the
  // head again.
  private final IntArray headIds = new IntArray();
  private final List<Parent<L>> parents = new ArrayList<>();
  // The matcher's two views of the node list, each built on first use and dropped when the list
  // changes: the table, for a head with a key, and the positions of the nodes of a node class,
  // for a head that names its class and reads the node's payload. Only positions are ever
  // iterated, in insertion order, so a map's own order is not read.
  private @Nullable Table table;
  private @Nullable Map<Class<?>, IntArray> byType;
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

  /** Appends a node with its head ordinal. */
  void addNode(L node, int headId) {
    nodes.add(node);
    headIds.add(headId);
    dropIndexes();
  }

  /** Appends another class's nodes, with their head ordinals, in their order. */
  void appendNodes(EClass<L, D> other) {
    nodes.addAll(other.nodes);
    for (int i = 0; i < other.headIds.size(); i++) {
      headIds.add(other.headIds.get(i));
    }
    dropIndexes();
  }

  /** Replaces the node at a position by another form of it: same head, other children. */
  void setNode(int position, L node) {
    nodes.set(position, node);
    dropIndexes();
  }

  /**
   * Drops every node equal to an earlier one, keeping the first of each; returns whether any
   * was dropped.
   */
  boolean dedupNodes() {
    HashSet<L> seen = HashSet.newHashSet(nodes.size());
    int kept = 0;
    for (int i = 0; i < nodes.size(); i++) {
      L node = nodes.get(i);
      if (seen.add(node)) {
        if (kept != i) {
          nodes.set(kept, node);
          headIds.set(kept, headIds.get(i));
        }
        kept++;
      }
    }
    if (kept == nodes.size()) {
      return false;
    }
    nodes.subList(kept, nodes.size()).clear();
    headIds.truncate(kept);
    dropIndexes();
    return true;
  }

  /**
   * Keeps the nodes {@code keep} accepts, in their order, and adds the others to
   * {@code dropped}; returns how many were dropped.
   */
  int retainNodes(Predicate<L> keep, List<L> dropped) {
    // The predicate is the caller's and may throw: ask it about every node before the class
    // changes, so that a throw leaves the class as it was.
    boolean[] accepted = new boolean[nodes.size()];
    for (int i = 0; i < accepted.length; i++) {
      accepted[i] = keep.test(nodes.get(i));
    }
    int kept = 0;
    for (int i = 0; i < accepted.length; i++) {
      L node = nodes.get(i);
      if (accepted[i]) {
        if (kept != i) {
          nodes.set(kept, node);
          headIds.set(kept, headIds.get(i));
        }
        kept++;
      } else {
        dropped.add(node);
      }
    }
    if (kept == nodes.size()) {
      return 0;
    }
    nodes.subList(kept, nodes.size()).clear();
    headIds.truncate(kept);
    dropIndexes();
    return dropped.size();
  }

  void addParent(Parent<L> entry) {
    parents.add(entry);
  }

  /** The live node list, for a reader that will not change it. */
  List<L> readNodes() {
    return nodes;
  }

  /** Forgets the matcher's views of the node list; called by whatever changes the list. */
  void dropIndexes() {
    table = null;
    byType = null;
  }

  /**
   * The nodes laid flat ({@link Table}), built on the first call after a change of the list: the
   * entries are the positions sorted by head ordinal, each (head, position) pair packed in a
   * long so that one sort orders the heads and keeps insertion order within a head.
   */
  Table table() {
    if (table == null) {
      int n = nodes.size();
      long[] order = new long[n];
      int totalKids = 0;
      for (int i = 0; i < n; i++) {
        order[i] = ((long) headIds.get(i) << 32) | i;
        totalKids += nodes.get(i).children().size();
      }
      if (n > 1) {
        Arrays.sort(order);
      }
      int[] heads = new int[n];
      int[] positions = new int[n];
      int[] starts = new int[n + 1];
      int[] kids = new int[totalKids];
      int k = 0;
      for (int e = 0; e < n; e++) {
        heads[e] = (int) (order[e] >>> 32);
        int position = (int) order[e];
        positions[e] = position;
        starts[e] = k;
        IntList children = nodes.get(position).children();
        for (int j = 0; j < children.size(); j++) {
          kids[k++] = children.get(j);
        }
      }
      starts[n] = k;
      table = new Table(heads, positions, starts, kids);
    }
    return table;
  }

  /**
   * The positions in the node list of the nodes with head ordinal {@code headId}, in insertion
   * order; empty if there are none. A fresh array, from the table.
   */
  int[] positionsWithHead(int headId) {
    Table t = table();
    int lo = t.lower(headId);
    return Arrays.copyOfRange(t.positions, lo, t.upper(headId, lo));
  }

  /**
   * The positions in the node list of the nodes of class {@code type}, in insertion order, or
   * null if there are none; built on the first call after a change of the list.
   */
  @Nullable IntArray positionsOfType(Class<?> type) {
    if (byType == null) {
      Map<Class<?>, IntArray> index = new HashMap<>();
      for (int i = 0; i < nodes.size(); i++) {
        index.computeIfAbsent(nodes.get(i).getClass(), k -> new IntArray()).add(i);
      }
      byType = index;
    }
    return byType.get(type);
  }

  /** Whether a view of the node list is built, for the invariant check. */
  boolean hasIndex() {
    return table != null || byType != null;
  }

  /**
   * Checks the head ordinals against the nodes' heads, and a built view against the node list:
   * the table's entries are the positions, sorted by head and in insertion order within one,
   * with each node's children; the type index's positions name nodes of that class and cover
   * every node.
   *
   * @param headIdOf the graph's ordinal of a node's head
   * @throws IllegalStateException naming the first disagreement
   */
  void checkIndexes(ToIntFunction<L> headIdOf) {
    if (headIds.size() != nodes.size()) {
      throw new IllegalStateException("class " + id + ": " + headIds.size()
          + " head ordinals for " + nodes.size() + " nodes");
    }
    for (int i = 0; i < nodes.size(); i++) {
      if (headIdOf.applyAsInt(nodes.get(i)) != headIds.get(i)) {
        throw new IllegalStateException("class " + id + ": the node at " + i + ", "
            + nodes.get(i) + ", is listed under head ordinal " + headIds.get(i));
      }
    }
    if (table != null) {
      Table t = table;
      if (t.heads.length != nodes.size()) {
        throw new IllegalStateException("class " + id + ": the table has " + t.heads.length
            + " entries for " + nodes.size() + " nodes");
      }
      boolean[] seen = new boolean[nodes.size()];
      for (int e = 0; e < t.heads.length; e++) {
        int position = t.positions[e];
        if (seen[position] || t.heads[e] != headIds.get(position)) {
          throw new IllegalStateException("class " + id + ": table entry " + e + " names position "
              + position + " under head ordinal " + t.heads[e]);
        }
        seen[position] = true;
        if (e > 0 && (t.heads[e - 1] > t.heads[e]
            || (t.heads[e - 1] == t.heads[e] && t.positions[e - 1] > position))) {
          throw new IllegalStateException("class " + id + ": table entries " + (e - 1) + " and "
              + e + " are out of order");
        }
        IntList children = nodes.get(position).children();
        if (t.arity(e) != children.size()) {
          throw new IllegalStateException("class " + id + ": table entry " + e + " has arity "
              + t.arity(e) + " for " + nodes.get(position));
        }
        for (int j = 0; j < children.size(); j++) {
          if (t.kids[t.starts[e] + j] != children.get(j)) {
            throw new IllegalStateException("class " + id + ": table entry " + e
                + " has child " + t.kids[t.starts[e] + j] + " for " + nodes.get(position));
          }
        }
      }
    }
    if (byType != null) {
      int listed = 0;
      for (Map.Entry<Class<?>, IntArray> e : byType.entrySet()) {
        IntArray positions = e.getValue();
        for (int i = 0; i < positions.size(); i++) {
          if (nodes.get(positions.get(i)).getClass() != e.getKey()) {
            throw new IllegalStateException("class " + id + ": the type index lists position "
                + positions.get(i) + " under " + e.getKey().getSimpleName());
          }
        }
        listed += positions.size();
      }
      if (listed != nodes.size()) {
        throw new IllegalStateException("class " + id + ": the type index lists " + listed
            + " positions for " + nodes.size() + " nodes");
      }
    }
  }

  List<Parent<L>> mutableParents() {
    return parents;
  }
}
