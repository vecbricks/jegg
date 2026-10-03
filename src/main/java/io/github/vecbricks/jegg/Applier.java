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
 * A rewrite's right-hand side: given a match, adds whatever it adds to the graph and returns the
 * classes to make equal to the matched class. A pattern is the common case
 * ({@link #pattern}); a function of the match is egg's dynamic applier, for a right-hand side
 * that computes - a folded literal, a substitution with capture avoidance.
 *
 * @param <L> the language
 * @param <D> the analysis fact
 */
@FunctionalInterface
public interface Applier<L extends Language<L>, D> {

  /** The classes to union with {@code eclass}; empty if nothing was added. */
  IntList apply(EGraph<L, D> graph, int eclass, Subst subst);

  /** The applier that instantiates {@code rhs} under the match's substitution. */
  static <L extends Language<L>, D> Applier<L, D> pattern(Pattern<L> rhs) {
    return (graph, eclass, subst) -> IntList.of(Matcher.instantiate(graph, rhs, subst));
  }
}
