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

  /**
   * The classes to union with {@code eclass}; empty if nothing was added.
   *
   * @param graph the graph to add to
   * @param eclass the class the left-hand side matched, an id of the graph, not necessarily
   *     canonical
   * @param subst the match's bindings for the left-hand side's variables
   * @return the ids of the classes the applier built, each to be unioned with {@code eclass}
   */
  IntList apply(EGraph<L, D> graph, int eclass, Subst subst);

  /**
   * Applies this right-hand side to one match: adds what {@link #apply} builds and unions each
   * class it returns with the matched class. This is egg's {@code apply_one}; a right-hand side
   * that does its own unions, as {@link #multi}'s does, overrides it. An applier that wraps
   * another must call the wrapped one's {@code applyTo}, not its {@code apply}, or the unions a
   * wrapped multi-pattern makes go uncounted; a lambda cannot, so a lambda must not wrap one.
   *
   * @param graph the graph to write to; the match must come from a search of it
   * @param match the match: the matched class and the left-hand side's bindings
   * @return the unions that changed the graph, each counted as applied
   */
  default Applied applyTo(EGraph<L, D> graph, Matcher.Match match) {
    IntList added = apply(graph, match.eclass(), match.subst());
    int changed = 0;
    for (int i = 0; i < added.size(); i++) {
      if (graph.find(match.eclass()) != graph.find(added.get(i))) {
        graph.merge(match.eclass(), added.get(i));
        changed++;
      }
    }
    return Applied.unions(changed);
  }

  /**
   * The applier that instantiates {@code rhs} under the match's substitution.
   *
   * @param <L> the language
   * @param <D> the analysis fact
   * @param rhs the pattern to instantiate; its variables must be bound by the match
   * @return an applier that returns the one class {@code rhs} instantiates to
   */
  static <L extends Language<L>, D> Applier<L, D> pattern(Pattern<L> rhs) {
    return new PatternApplier<>(rhs);
  }

  /**
   * A right-hand side that is a pattern, kept as one so a {@link Rewrite} can check its
   * variables against the left-hand side's when it is made.
   *
   * @param <L> the language
   * @param <D> the analysis fact
   * @param rhs the pattern to instantiate under the match's substitution
   */
  record PatternApplier<L extends Language<L>, D>(Pattern<L> rhs) implements Applier<L, D> {
    @Override
    public IntList apply(EGraph<L, D> graph, int eclass, Subst subst) {
      return IntList.of(Matcher.instantiate(graph, rhs, subst));
    }
  }

  /**
   * The applier of a multi-pattern right-hand side: each clause, in order, instantiates its
   * pattern and either binds its variable (new) or unions the instance with the class the
   * variable names, so its unions are with the classes its clause variables name, which are
   * the matched class only where a clause names the first left-hand clause's variable. Unlike
   * egg's, it may sit under a {@link Condition} ({@link Rewrite#when}), which egg's
   * {@code ConditionalApplier} refuses for a multi-pattern.
   *
   * @param <L> the language
   * @param <D> the analysis fact
   * @param rhs the clauses; each pattern's variables must be bound by the match or by an
   *     earlier clause's variable
   * @return an applier that does its own unions and counts every match as applied
   */
  static <L extends Language<L>, D> Applier<L, D> multi(MultiPattern<L> rhs) {
    return new MultiApplier<>(rhs);
  }

  /**
   * A multi-pattern right-hand side, kept as one so a {@link Rewrite} can check its variables
   * when it is made. egg's {@code MultiPattern::apply_matches}: it counts every match as applied
   * whether or not a union changed anything, so a run in which it matches is never saturated.
   *
   * @param <L> the language
   * @param <D> the analysis fact
   * @param rhs the clauses to apply for each match
   */
  record MultiApplier<L extends Language<L>, D>(MultiPattern<L> rhs) implements Applier<L, D> {
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
        OptionalInt named = subst.id(clause.var());
        if (named.isPresent()) {
          if (graph.find(named.getAsInt()) != graph.find(id)) {
            graph.merge(named.getAsInt(), id);
            unions++;
          }
        } else {
          subst = subst.bind(clause.var(), id);
        }
      }
      return Applied.countedOnce(unions);
    }
  }
}
