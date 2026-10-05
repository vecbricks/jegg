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
 * How a client's own tree type becomes e-nodes and comes back: the three functions
 * {@link EGraph#addTree} and an extractor need, so that a client whose terms are an existing
 * tree of records - children held as nodes, not ids - does not mirror its language by hand.
 *
 * @param <T> the client's tree node
 * @param <L> the language the e-graph holds
 */
public interface TreeBridge<T, L extends Language<L>> {

  /**
   * The subtrees of {@code tree}, in the operator's argument order; empty for a leaf.
   *
   * @param tree a node of the client's tree
   * @return its children, empty if it has none
   */
  List<T> childrenOf(T tree);

  /**
   * The e-node for {@code tree}'s operator and payload over these children's class ids.
   *
   * @param tree a node of the client's tree, whose own children are not read here
   * @param children the class ids of the tree's children, in argument order
   * @return the e-node, with {@code children} as its children
   */
  L node(T tree, IntList children);

  /**
   * The tree for this e-node over these subtrees, already built, in argument order.
   *
   * @param node the e-node whose operator and payload the tree node takes
   * @param children the client's trees for the node's children, in argument order
   * @return the client's tree node
   */
  T build(L node, List<T> children);
}
