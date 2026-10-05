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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * An extracted term: the node chosen for a class over the terms chosen for its children. A
 * tree when built by {@link Extractor#extract}; a DAG with shared subterms when built from a
 * {@link Selection}, where one class is one object however many parents it has.
 *
 * <p>A value candidate (PLAN.md 3.1): immutable, compared by content. {@link #toTree} and
 * {@link #treeSize} visit a shared subterm once by object identity; as a value class the term
 * would have no identity, and they would visit a shared subterm once per path to it: still
 * correct, but in time proportional to the tree, not the DAG. A client that needs the DAG then
 * walks the term by class id, which {@link #eclass()} gives for every subterm.
 *
 * @param <L> the language
 */
public record Extracted<L extends Language<L>>(int eclass, L node, List<Extracted<L>> children) {

  public Extracted {
    children = List.copyOf(children);
  }

  /**
   * The term as a client's tree, through {@code bridge}, bottom-up; a subterm object reached
   * twice is built once, and the client's tree shares it.
   */
  public <T> T toTree(TreeBridge<T, L> bridge) {
    return toTree(bridge, new IdentityHashMap<>());
  }

  private <T> T toTree(TreeBridge<T, L> bridge, Map<Extracted<L>, T> built) {
    T done = built.get(this);
    if (done != null) {
      return done;
    }
    List<T> kids = new ArrayList<>(children.size());
    for (Extracted<L> c : children) {
      kids.add(c.toTree(bridge, built));
    }
    T tree = bridge.build(node, kids);
    built.put(this, tree);
    return tree;
  }

  /** How many nodes the term has as a tree, shared subterms counted each time. */
  public long treeSize() {
    return treeSize(new IdentityHashMap<>());
  }

  private long treeSize(Map<Extracted<L>, Long> counted) {
    Long done = counted.get(this);
    if (done != null) {
      return done;
    }
    long n = 1;
    for (Extracted<L> c : children) {
      n += c.treeSize(counted);
    }
    counted.put(this, n);
    return n;
  }

  @Override
  public String toString() {
    if (children.isEmpty()) {
      return node.head().toString();
    }
    StringBuilder b = new StringBuilder(node.head().toString()).append('(');
    for (int i = 0; i < children.size(); i++) {
      if (i > 0) {
        b.append(", ");
      }
      b.append(children.get(i));
    }
    return b.append(')').toString();
  }
}
