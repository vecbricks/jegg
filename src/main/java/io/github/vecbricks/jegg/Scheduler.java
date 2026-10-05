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

/**
 * What decides, each iteration, which of a rule's matches are applied (egg's
 * {@code RewriteScheduler}). The simple scheduler applies them all; the
 * {@link BackoffScheduler} holds an expansive rule back for a while once its matches pass a
 * threshold, so associativity and commutativity do not swamp the rest.
 *
 * @param <L> the language
 * @param <D> the analysis fact
 */
public interface Scheduler<L extends Language<L>, D> {

  /**
   * The matches of {@code rule} to apply this iteration: usually {@code rule.search(graph)},
   * possibly empty when the rule is held back, possibly a prefix of the matches.
   *
   * @param iteration the iteration being run, counted from 1
   * @param ruleIndex the rule's position in the runner's rule list, counted from 0
   * @param rule the rule to search
   * @param graph the rebuilt graph to search, which the scheduler must not change
   * @return the matches to apply, in the matcher's order; empty if none or if the rule is held back
   */
  List<Matcher.Match> search(int iteration, int ruleIndex, Rewrite<L, D> rule,
      EGraph<L, D> graph);

  /**
   * Called after every iteration in which no rule merged two classes, whether or not nodes were
   * added - a condition or an applier may add without merging - as egg calls its
   * {@code can_stop}. A scheduler that held a rule back may release it here, and says no; the
   * runner then continues, and the run saturates only once an iteration with no rule held back
   * changes nothing. A scheduler that holds nothing back says yes; the runner reports
   * saturation only if, besides, the iteration added nothing.
   *
   * @param iteration the iteration just run, counted from 1
   * @return true if the scheduler holds no rule back, so the run may stop; false to run on. Not a
   *     pure query: a scheduler that holds rules back changes its bans here, as
   *     {@link BackoffScheduler} does by shortening each by the shortest
   */
  default boolean canStop(int iteration) {
    return true;
  }

  /**
   * Whether the rule is held back this iteration, for the report; false unless held.
   *
   * @param iteration the iteration being run, counted from 1
   * @param ruleIndex the rule's position in the runner's rule list, counted from 0
   * @return true if {@link #search} returned nothing for the rule because it is held back
   */
  default boolean isBanned(int iteration, int ruleIndex) {
    return false;
  }

  /**
   * Forgets what earlier runs taught the scheduler. The runner calls it at the start of every
   * run, whose iterations count from 1 again, so a run depends only on its graph and rules -
   * not on an earlier run of the same runner, nor on another runner sharing the scheduler.
   */
  default void reset() {
  }

  /**
   * The scheduler that applies every match of every rule.
   *
   * @param <L> the language
   * @param <D> the analysis fact
   * @return a scheduler that holds no rule back
   */
  static <L extends Language<L>, D> Scheduler<L, D> simple() {
    return (_, _, rule, graph) -> rule.search(graph);
  }
}
