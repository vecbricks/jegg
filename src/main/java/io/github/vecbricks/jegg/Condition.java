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
 * A rewrite's side condition: whether a match may be applied, read from the graph - the facts
 * of the bound classes, their nodes - and the substitution.
 *
 * @param <L> the language
 * @param <D> the analysis fact
 */
@FunctionalInterface
public interface Condition<L extends Language<L>, D> {

  boolean holds(EGraph<L, D> graph, int eclass, Subst subst);

  /** The condition that always holds. */
  static <L extends Language<L>, D> Condition<L, D> always() {
    return (graph, eclass, subst) -> true;
  }

  /** Both conditions. */
  default Condition<L, D> and(Condition<L, D> other) {
    return (graph, eclass, subst) -> holds(graph, eclass, subst) && other.holds(graph, eclass,
        subst);
  }
}
