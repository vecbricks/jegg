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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A named rewrite: a left-hand {@link Searcher} (a pattern, or several joined in a
 * {@link MultiPattern}), a right-hand {@link Applier} and a {@link Condition}.
 * Searching finds the matches; applying one adds the right-hand side and unions it with the
 * matched class. The runner does both for every rule each iteration, reading all matches
 * before writing any, which is the equality-saturation loop of the paper's Figure 5b.
 *
 * @param <L> the language
 * @param <D> the analysis fact
 * @param name the rule's name, as {@link #toString} shows it; not null
 * @param lhs the left-hand pattern or multi-pattern to search for; not null
 * @param rhs what to add for each match; not null. A pattern applier's variables are checked
 *     against {@code lhs}'s on construction
 * @param condition whether a match may be applied, read at apply time; not null
 */
public record Rewrite<L extends Language<L>, D>(String name, Searcher<L> lhs, Applier<L, D> rhs,
    Condition<L, D> condition) {

  /**
   * Checks that no component is null and that a pattern right-hand side uses only variables the
   * left-hand side binds.
   *
   * @throws IllegalArgumentException if the right-hand side has a subterm or payload variable the
   *     left-hand side does not bind
   */
  public Rewrite {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(lhs, "lhs");
    Objects.requireNonNull(rhs, "rhs");
    Objects.requireNonNull(condition, "condition");
    if (lhs instanceof MultiPattern<L> multi && multi.startsWithBareVariable()) {
      throw new IllegalArgumentException("rewrite " + name
          + ": a multi-pattern cannot start with a bare variable: " + multi);
    }
    // As egg's Rewrite::new: a pattern right-hand side may use only what the left binds, so a
    // misspelt variable fails here and not in the middle of an apply phase, with the graph
    // half-applied. A dynamic applier cannot be checked; its variables are its own business.
    if (rhs instanceof Applier.PatternApplier<L, D>(var pattern)) {
      checkBound(name, lhs, lhs.subtermVariables(), pattern);
    } else if (rhs instanceof Applier.MultiApplier<L, D>(var multi)) {
      // A multi-pattern right-hand side binds each clause's variable for the clauses after it
      // ("?z = (baz ?y), ?x = ?z"), so a variable is checked against the left's and the
      // earlier clauses'.
      Set<String> bound = new java.util.LinkedHashSet<>(lhs.subtermVariables());
      for (MultiPattern.Clause<L> clause : multi.clauses()) {
        checkBound(name, lhs, bound, clause.pattern());
        bound.add(clause.var());
      }
    }
  }

  private static <L extends Language<L>> void checkBound(String name, Searcher<L> lhs,
      Set<String> bound, Pattern<L> pattern) {
    for (String v : pattern.subtermVariables()) {
      if (!bound.contains(v)) {
        throw new IllegalArgumentException("rewrite " + name + ": the right-hand side's ?" + v
            + " is not bound by the left-hand side " + lhs);
      }
    }
    // Payload variables only when every head on both sides declares what it binds
    // (Pattern.Head.variables): a head that does not say is unchecked, never wrongly refused.
    Optional<Set<String>> boundPayloads = lhs.payloadVariables();
    Optional<Set<String>> usedPayloads = pattern.payloadVariables();
    if (boundPayloads.isPresent() && usedPayloads.isPresent()) {
      for (String v : usedPayloads.get()) {
        if (!boundPayloads.get().contains(v)) {
          throw new IllegalArgumentException("rewrite " + name + ": the right-hand side's"
              + " payload variable ?" + v + " is not bound by the left-hand side " + lhs);
        }
      }
    }
  }

  /**
   * A rewrite from one pattern to another, unconditional.
   *
   * @param <L> the language
   * @param <D> the analysis fact
   * @param name the rule's name
   * @param lhs the pattern to search for
   * @param rhs the pattern to instantiate for each match, over variables {@code lhs} binds
   * @return the rewrite, whose condition always holds
   */
  public static <L extends Language<L>, D> Rewrite<L, D> of(String name, Pattern<L> lhs,
      Pattern<L> rhs) {
    return new Rewrite<>(name, lhs, Applier.pattern(rhs), Condition.always());
  }

  /**
   * A rewrite from a multi-pattern to a multi-pattern, unconditional: egg's {@code
   * multi_rewrite!}. Each match applies the right-hand clauses in order.
   *
   * @param <L> the language
   * @param <D> the analysis fact
   * @param name the rule's name
   * @param lhs the clauses to join for a match; the first is not a bare variable
   * @param rhs the clauses to apply for each match, over variables {@code lhs} binds or an
   *     earlier right-hand clause does
   * @return the rewrite, whose condition always holds
   */
  public static <L extends Language<L>, D> Rewrite<L, D> multi(String name, MultiPattern<L> lhs,
      MultiPattern<L> rhs) {
    return new Rewrite<>(name, lhs, Applier.multi(rhs), Condition.always());
  }

  /**
   * A rewrite from a pattern to a computed right-hand side, unconditional.
   *
   * @param <L> the language
   * @param <D> the analysis fact
   * @param name the rule's name
   * @param lhs the pattern to search for
   * @param rhs computes the classes to union with each match, whose variables are not checked
   * @return the rewrite, whose condition always holds
   */
  public static <L extends Language<L>, D> Rewrite<L, D> dynamic(String name, Searcher<L> lhs,
      Applier<L, D> rhs) {
    return new Rewrite<>(name, lhs, rhs, Condition.always());
  }

  /**
   * This rewrite under a further condition.
   *
   * @param extra a condition that must hold as well as this rewrite's own
   * @return a new rewrite, equal to this one but for the condition, which is this one's and
   *     {@code extra}
   */
  public Rewrite<L, D> when(Condition<L, D> extra) {
    return new Rewrite<>(name, lhs, rhs, condition.and(extra));
  }

  /**
   * The matches of the left-hand side, in the matcher's order. The condition is not read here
   * but in {@link #apply}, as egg's {@code ConditionalApplier} reads it.
   *
   * @param graph the graph to search; not changed
   * @return a fresh list of matches, empty if there are none
   */
  public List<Matcher.Match> search(EGraph<L, D> graph) {
    return lhs.search(graph, Integer.MAX_VALUE);
  }

  /**
   * The first {@code limit} matches, the search stopped within the node that reached the limit
   * ({@link Searcher#search}).
   *
   * @param graph the graph to search; not changed
   * @param limit the most matches to return; must be positive
   * @return a fresh list of at most {@code limit} matches, in the matcher's order
   */
  public List<Matcher.Match> search(EGraph<L, D> graph, int limit) {
    return lhs.search(graph, limit);
  }

  /**
   * Applies one match if its condition holds: the right-hand side's {@link Applier#applyTo}
   * adds its classes and unions them with the matched class, or, for a multi-pattern, does its
   * own unions. Returns what that did, or empty if the condition did not hold and nothing was
   * applied.
   *
   * <p>The condition is read here, at apply time, not at search: the runner applies a match
   * only after the matches before it in the iteration, whose merges may have made the
   * condition false since the search.
   *
   * @param graph the graph to write to; the match must come from a search of it
   * @param match a match of this rewrite's left-hand side: the matched class and the
   *     substitution for the variables
   * @return the unions that changed the graph and the number egg counts as applied, or empty if
   *     the condition did not hold
   */
  public Optional<Applied> apply(EGraph<L, D> graph, Matcher.Match match) {
    if (!condition.holds(graph, match.eclass(), match.subst())) {
      return Optional.empty();
    }
    return Optional.of(rhs.applyTo(graph, match));
  }

  @Override
  public String toString() {
    return name;
  }
}
