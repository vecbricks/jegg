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

  /** The matches whose condition holds, in the matcher's order. */
  public List<Matcher.Match> search(EGraph<L, D> graph) {
    return Matcher.search(graph, lhs).stream()
        .filter(m -> condition.holds(graph, m.eclass(), m.subst())).toList();
  }

  /**
   * Applies one match: the right-hand side's classes are unioned with the matched class. Returns
   * how many unions changed the graph (merged two classes that were different).
   *
   * <p>The condition is checked again here, as egg's {@code ConditionalApplier} checks it at
   * apply time: the runner applies a match only after the matches before it in the iteration,
   * whose merges may have made the condition false since the search.
   */
  public int apply(EGraph<L, D> graph, Matcher.Match match) {
    if (!condition.holds(graph, match.eclass(), match.subst())) {
      return 0;
    }
    IntList added = rhs.apply(graph, match.eclass(), match.subst());
    int changed = 0;
    for (int i = 0; i < added.size(); i++) {
      int before = graph.numClasses();
      graph.merge(match.eclass(), added.get(i));
      if (graph.numClasses() != before) {
        changed++;
      }
    }
    return changed;
  }

  @Override
  public String toString() {
    return name;
  }
}
