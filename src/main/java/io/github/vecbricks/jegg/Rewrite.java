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
import java.util.OptionalInt;
import java.util.Set;

/**
 * A named rewrite: a left-hand pattern, a right-hand {@link Applier} and a {@link Condition}.
 * Searching finds the matches; applying one adds the right-hand side and unions it with the
 * matched class. The runner does both for every rule each iteration, reading all matches
 * before writing any, which is the equality-saturation loop of the paper's Figure 5b.
 *
 * @param <L> the language
 * @param <D> the analysis fact
 */
public record Rewrite<L extends Language<L>, D>(String name, Pattern<L> lhs, Applier<L, D> rhs,
    Condition<L, D> condition) {

  public Rewrite {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(lhs, "lhs");
    Objects.requireNonNull(rhs, "rhs");
    Objects.requireNonNull(condition, "condition");
    // As egg's Rewrite::new: a pattern right-hand side may use only what the left binds, so a
    // misspelt variable fails here and not in the middle of an apply phase, with the graph
    // half-applied. A dynamic applier cannot be checked; its variables are its own business.
    if (rhs instanceof Applier.PatternApplier<L, D>(var pattern)) {
      Set<String> bound = lhs.subtermVariables();
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
  }

  /** A rewrite from one pattern to another, unconditional. */
  public static <L extends Language<L>, D> Rewrite<L, D> of(String name, Pattern<L> lhs,
      Pattern<L> rhs) {
    return new Rewrite<>(name, lhs, Applier.pattern(rhs), Condition.always());
  }

  /** A rewrite from a pattern to a computed right-hand side, unconditional. */
  public static <L extends Language<L>, D> Rewrite<L, D> dynamic(String name, Pattern<L> lhs,
      Applier<L, D> rhs) {
    return new Rewrite<>(name, lhs, rhs, Condition.always());
  }

  /** This rewrite under a further condition. */
  public Rewrite<L, D> when(Condition<L, D> extra) {
    return new Rewrite<>(name, lhs, rhs, condition.and(extra));
  }

  /**
   * The matches of the left-hand side, in the matcher's order. The condition is not read here
   * but in {@link #apply}, as egg's {@code ConditionalApplier} reads it.
   */
  public List<Matcher.Match> search(EGraph<L, D> graph) {
    return Matcher.search(graph, lhs);
  }

  /**
   * The first {@code limit} matches, the search stopped within the node that reached the limit
   * ({@link Matcher#search(EGraph, Pattern, int)}).
   */
  public List<Matcher.Match> search(EGraph<L, D> graph, int limit) {
    return Matcher.search(graph, lhs, limit);
  }

  /**
   * Applies one match if its condition holds: the right-hand side's classes are unioned with
   * the matched class. Returns how many unions changed the graph (merged two classes that were
   * different), or empty if the condition did not hold and nothing was applied.
   *
   * <p>The condition is read here, at apply time, not at search: the runner applies a match
   * only after the matches before it in the iteration, whose merges may have made the
   * condition false since the search.
   */
  public OptionalInt apply(EGraph<L, D> graph, Matcher.Match match) {
    if (!condition.holds(graph, match.eclass(), match.subst())) {
      return OptionalInt.empty();
    }
    IntList added = rhs.apply(graph, match.eclass(), match.subst());
    int changed = 0;
    for (int i = 0; i < added.size(); i++) {
      if (graph.find(match.eclass()) != graph.find(added.get(i))) {
        graph.merge(match.eclass(), added.get(i));
        changed++;
      }
    }
    return OptionalInt.of(changed);
  }

  @Override
  public String toString() {
    return name;
  }
}
