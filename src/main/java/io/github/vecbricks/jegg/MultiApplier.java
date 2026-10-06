/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * A multi-pattern right-hand side, kept as one so a {@link Rewrite} can check its variables
 * when it is made. egg's {@code MultiPattern::apply_matches}: it counts every match as applied
 * whether or not a union changed anything, so a run in which it matches is never saturated.
 *
 * @param <L> the language
 * @param <D> the analysis fact
 * @param rhs the clauses to apply for each match
 */
record MultiApplier<L extends Language<L>, D extends @Nullable Object>(
    MultiPattern<L> rhs) implements Applier<L, D> {
  /**
   * Refused, as egg's {@code apply_one} for a multi-pattern panics: a caller that applies it
   * through {@code apply} would see no class to union and no count, so its unions would go
   * unreported and a run would saturate where egg's does not. Use {@link #applyTo}.
   *
   * @throws UnsupportedOperationException always
   */
  @Override
  public IntList apply(EGraph<L, D> graph, int eclass, Subst subst) {
    throw new UnsupportedOperationException(
        "a multi-pattern right-hand side applies through applyTo, not apply");
  }

  /**
   * Applies the clauses to one match, in order: each instantiates its pattern and binds its
   * variable if it is new, or unions the instance with the class the variable names.
   *
   * @param graph the graph to write to; the match must come from a search of it
   * @param match the match whose bindings the clauses read
   * @return the unions that changed the graph, and a count of one whatever they were
   */
  @Override
  public Applied applyTo(EGraph<L, D> graph, Matcher.Match match) {
    Subst subst = match.subst();
    int unions = 0;
    for (MultiPattern.Clause<L> clause : rhs.clauses()) {
      int id = Matcher.instantiate(graph, clause.pattern(), subst);
      OptionalInt named = subst.idInterned(clause.var());
      if (named.isPresent()) {
        if (graph.find(named.getAsInt()) != graph.find(id)) {
          graph.merge(named.getAsInt(), id);
          unions++;
        }
      } else {
        subst = subst.bindInterned(clause.var(), id);
      }
    }
    return Applied.countedOnce(unions);
  }
}
