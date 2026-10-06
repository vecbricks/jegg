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
 * A right-hand side that is a pattern, kept as one so a {@link Rewrite} can check its
 * variables against the left-hand side's when it is made.
 *
 * @param <L> the language
 * @param <D> the analysis fact
 * @param rhs the pattern to instantiate under the match's substitution
 */
record PatternApplier<L extends Language<L>, D extends @Nullable Object>(
    Pattern<L> rhs) implements Applier<L, D> {
  @Override
  public IntList apply(EGraph<L, D> graph, int eclass, Subst subst) {
    return IntList.wrap(new int[] {Matcher.instantiate(graph, rhs, subst)});
  }
}
