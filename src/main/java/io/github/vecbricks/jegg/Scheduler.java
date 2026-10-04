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
   */
  default boolean canStop(int iteration) {
    return true;
  }

  /**
   * Forgets what earlier runs taught the scheduler. The runner calls it at the start of every
   * run, whose iterations count from 1 again, so a run depends only on its graph and rules -
   * not on an earlier run of the same runner, nor on another runner sharing the scheduler.
   */
  default void reset() {
  }

  /** The scheduler that applies every match of every rule. */
  static <L extends Language<L>, D> Scheduler<L, D> simple() {
    return (iteration, ruleIndex, rule, graph) -> rule.search(graph);
  }
}
