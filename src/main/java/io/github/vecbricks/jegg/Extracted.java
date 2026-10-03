/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

import java.util.List;

/**
 * An extracted term: the node chosen for a class over the terms chosen for its children. A
 * tree when built by {@link Extractor#extract}; a DAG with shared subterms when built from a
 * {@link Selection}, where one class is one object however many parents it has.
 *
 * @param <L> the language
 */
public record Extracted<L extends Language<L>>(int eclass, L node, List<Extracted<L>> children) {

  public Extracted {
    children = List.copyOf(children);
  }

  /** The term as a client's tree, through {@code bridge}, bottom-up. */
  public <T> T toTree(TreeBridge<T, L> bridge) {
    List<T> kids = children.stream().map(c -> c.toTree(bridge)).toList();
    return bridge.build(node, kids);
  }

  /** How many nodes the term has as a tree, shared subterms counted each time. */
  public int treeSize() {
    int n = 1;
    for (Extracted<L> c : children) {
      n += c.treeSize();
    }
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
