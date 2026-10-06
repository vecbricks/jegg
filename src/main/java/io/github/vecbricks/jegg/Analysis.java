/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

import org.jspecify.annotations.Nullable;

/**
 * An e-class analysis: a fact of type {@code D} for every e-class, kept true as the graph
 * changes (egg's {@code Analysis} trait, the paper's section 4).
 *
 * <p>{@link #make} computes the fact for one e-node from the facts of its children's classes;
 * {@link #join} combines the facts of two classes that become one, and must be a semilattice
 * join - commutative, associative and idempotent - or rebuilding may not terminate;
 * {@link #modify} may add nodes or merge classes once a class's fact is known, as constant
 * folding does, and must be idempotent for the same reason. The invariant the graph keeps is that
 * every class's fact equals the join of {@code make} over its nodes.
 *
 * @param <L> the language
 * @param <D> the fact
 */
public interface Analysis<L extends Language<L>, D extends @Nullable Object> {

  /**
   * The fact for {@code node}, whose children's facts are readable through {@code graph}.
   *
   * @param graph the graph the node is being added to
   * @param node the e-node, whose children are ids of classes of {@code graph}
   * @return the node's fact; {@code null} only if the analysis allows it, as {@link #none} does
   */
  D make(EGraph<L, D> graph, L node);

  /**
   * The fact for a class made of two classes with these facts.
   *
   * @param a the fact of one class
   * @param b the fact of the other
   * @return the semilattice join of {@code a} and {@code b}
   */
  D join(D a, D b);

  /**
   * A hook after a class's fact changes: add nodes to it or merge, or do nothing.
   *
   * @param graph the graph to change
   * @param id the class whose fact is known or changed, an id of the graph, not necessarily
   *     canonical
   */
  default void modify(EGraph<L, D> graph, int id) {
  }

  /**
   * The analysis that knows nothing: every fact is {@code null} and nothing is modified.
   *
   * @param <L> the language
   * @return an analysis whose fact type is {@link Void}
   */
  static <L extends Language<L>> Analysis<L, Void> none() {
    return new Analysis<>() {
      @Override
      public Void make(EGraph<L, Void> graph, L node) {
        return null;
      }

      @Override
      public Void join(Void a, Void b) {
        return null;
      }
    };
  }
}
