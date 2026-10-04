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
 * of the bound classes, their nodes - and the substitution. It is evaluated at apply time, in
 * the iteration's write phase and just before its match is applied, never during the search:
 * a condition may add nodes to the graph, as {@link #equal} does, though never merge, and its
 * additions count against saturation like a right-hand side's. A scheduler must not evaluate
 * conditions while searching.
 *
 * @param <L> the language
 * @param <D> the analysis fact
 */
@FunctionalInterface
public interface Condition<L extends Language<L>, D> {

  boolean holds(EGraph<L, D> graph, int eclass, Subst subst);

  /** The condition that always holds. */
  static <L extends Language<L>, D> Condition<L, D> always() {
    return (_, _, _) -> true;
  }

  /**
   * egg's {@code ConditionEqual}: both patterns instantiated under the match's substitution land
   * in one class. Instantiating adds what the graph lacks, as egg's does; since conditions are
   * read at apply time, that is an addition like a right-hand side's, and the graph is not
   * rebuilt in between, so an equality only congruence would show is not seen.
   */
  static <L extends Language<L>, D> Condition<L, D> equal(Pattern<L> a, Pattern<L> b) {
    return (graph, eclass, subst) -> {
      // Both instantiated before either root is read: adding the second may run the analysis's
      // modify hook, which may merge the first's class under another root.
      int x = Matcher.instantiate(graph, a, subst);
      int y = Matcher.instantiate(graph, b, subst);
      return graph.find(x) == graph.find(y);
    };
  }

  /** Both conditions. */
  default Condition<L, D> and(Condition<L, D> other) {
    return (graph, eclass, subst) -> holds(graph, eclass, subst) && other.holds(graph, eclass,
        subst);
  }
}
