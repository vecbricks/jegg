/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

/**
 * An e-node of a client's language: an operator, a payload and children held as e-class ids.
 *
 * <p>A client's language is a sealed hierarchy of records implementing this interface, one
 * record per operator, with whatever payload the operator carries - an arithmetic mode, a
 * divisor, a literal's index, a lane width - as further record components, and its children as
 * one {@link IntList}. A record's generated {@code equals} and {@code hashCode} then cover all
 * three, which is exactly the equality the hashcons needs: two e-nodes are the same node when they
 * have the same operator, the same payload and the same children. Children must be an
 * {@code IntList} and never an {@code int[]}, for the reason {@link IntList} gives.
 *
 * <p>The only operation the e-graph needs beyond equality is {@link #withChildren}, which is
 * how a node is canonicalised (its children replaced by their classes' roots) and how an
 * extractor rebuilds a term; {@link #head} is what matching compares before it looks at the
 * children.
 *
 * @param <L> the language itself, so that {@code withChildren} returns the client's type
 */
public interface Language<L extends Language<L>> {

  /** The children, as e-class ids, in the operator's argument order. */
  IntList children();

  /** The same operator and payload over these children. */
  L withChildren(IntList children);

  /**
   * The operator and payload without the children: what two nodes must share to match up to
   * their arguments. By default the node over no children, which for a record is equal to
   * another's exactly when every component but the children is; a language may override it
   * with a cheaper key.
   */
  default Object head() {
    return withChildren(IntList.EMPTY);
  }
}
