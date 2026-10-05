/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

import java.util.function.IntToDoubleFunction;

/**
 * What a term costs, for extraction. {@link #nodeCost} prices one e-node by itself - the
 * client's measured table, keyed by operator and payload - and {@link #cost} prices a node
 * given its children's best costs, additive by default. Tree extraction uses {@code cost};
 * extraction over several roots sharing a DAG uses {@code nodeCost} alone, since a shared node
 * is paid once there and only an additive cost can be summed over a set.
 *
 * <p>Every cost must be finite and non-negative: a negative one lets a cycle of nodes get
 * cheaper forever, and the extractor refuses either with an {@link IllegalArgumentException}.
 *
 * @param <L> the language
 */
@FunctionalInterface
public interface CostFunction<L extends Language<L>> {

  /**
   * The cost of this one node, its children not counted.
   *
   * @param node the e-node to price
   * @return the node's own cost, finite and non-negative
   */
  double nodeCost(L node);

  /**
   * The cost of a term rooted at {@code node} whose children cost {@code childCost} each.
   *
   * @param node the e-node at the term's root
   * @param childCost the best cost of the tree for a child class, given the child's class id
   * @return the cost of the tree rooted at {@code node}, finite and non-negative; the sum of
   *     {@link #nodeCost} and the children's costs unless overridden
   */
  default double cost(L node, IntToDoubleFunction childCost) {
    double total = nodeCost(node);
    IntList children = node.children();
    for (int i = 0; i < children.size(); i++) {
      total += childCost.applyAsDouble(children.get(i));
    }
    return total;
  }

  /**
   * egg's {@code AstSize}: every node costs one, so the cheapest term is the smallest.
   *
   * @param <L> the language
   * @return a cost function pricing every node at 1
   */
  static <L extends Language<L>> CostFunction<L> astSize() {
    return node -> 1.0;
  }

  /**
   * egg's {@code AstDepth}: the cheapest term is the shallowest.
   *
   * @param <L> the language
   * @return a cost function whose tree cost is 1 plus the deepest child's, so a term costs its
   *     depth; its {@link #nodeCost} is 1
   */
  static <L extends Language<L>> CostFunction<L> astDepth() {
    return new CostFunction<>() {
      @Override
      public double nodeCost(L node) {
        return 1.0;
      }

      @Override
      public double cost(L node, IntToDoubleFunction childCost) {
        double deepest = 0.0;
        IntList children = node.children();
        for (int i = 0; i < children.size(); i++) {
          deepest = Math.max(deepest, childCost.applyAsDouble(children.get(i)));
        }
        return 1.0 + deepest;
      }
    };
  }
}
