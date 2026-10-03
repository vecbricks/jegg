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

  /** A parent entry: an e-node that has the class as a child, and the class it was added to. */
  public record Parent<L>(L node, int classId) {
  }

  private final int id;
  private final List<L> nodes = new ArrayList<>();
  private final List<Parent<L>> parents = new ArrayList<>();
  private D data;

  EClass(int id, D data) {
    this.id = id;
    this.data = data;
  }

  /** The class's id, which is a root of the union-find while the class is live. */
  public int id() {
    return id;
  }

  /** The nodes, in insertion order, read-only. */
  public List<L> nodes() {
    return Collections.unmodifiableList(nodes);
  }

  /** The parent entries, in insertion order, read-only. */
  public List<Parent<L>> parents() {
    return Collections.unmodifiableList(parents);
  }

  /** The analysis fact, {@code null} under {@link Analysis#none}. */
  public D data() {
    return data;
  }

  void setData(D data) {
    this.data = data;
  }

  void addNode(L node) {
    nodes.add(node);
  }

  void addParent(L node, int classId) {
    parents.add(new Parent<>(node, classId));
  }

  List<L> mutableNodes() {
    return nodes;
  }

  List<Parent<L>> mutableParents() {
    return parents;
  }
}
