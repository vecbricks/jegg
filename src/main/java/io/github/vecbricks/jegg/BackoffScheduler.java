/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

import java.util.ArrayList;
import java.util.List;

/**
 * egg's {@code BackoffScheduler}, the runner's default: a rule whose matches in one iteration
 * exceed its match limit is banned for a number of iterations, and each time it is banned again
 * its limit and its ban double, so a rule that would grow the graph without bound - associativity,
 * commutativity, distributivity - gets its turn less and less often while the other rules
 * finish their work. The thresholds are the ones egg ships: a match limit of 1000 and a ban of
 * five iterations.
 *
 * <p>Order is fixed: the decision depends only on the iteration number and the rule's own
 * history, so two runs over the same graph ban the same rules at the same iterations.
 *
 * @param <L> the language
 * @param <D> the analysis fact
 */
public final class BackoffScheduler<L extends Language<L>, D> implements Scheduler<L, D> {

  private static final class RuleStats {
    int bannedUntil;
    int timesBanned;
    int matchLimit;
    int banLength;
  }

  private final int matchLimit;
  private final int banLength;
  private final List<RuleStats> stats = new ArrayList<>();

  /** egg's defaults: a limit of a thousand matches, a ban of five iterations. */
  public BackoffScheduler() {
    this(1000, 5);
  }

  public BackoffScheduler(int matchLimit, int banLength) {
    if (matchLimit < 1 || banLength < 1) {
      throw new IllegalArgumentException("the match limit and the ban must be positive");
    }
    this.matchLimit = matchLimit;
    this.banLength = banLength;
  }

  private RuleStats statsOf(int ruleIndex) {
    while (stats.size() <= ruleIndex) {
      RuleStats s = new RuleStats();
      s.matchLimit = matchLimit;
      s.banLength = banLength;
      stats.add(s);
    }
    return stats.get(ruleIndex);
  }

  /** Whether the rule is banned at this iteration. */
  public boolean isBanned(int iteration, int ruleIndex) {
    return statsOf(ruleIndex).bannedUntil > iteration;
  }

  /** How many times the rule has been banned so far. */
  public int timesBanned(int ruleIndex) {
    return statsOf(ruleIndex).timesBanned;
  }

  @Override
  public List<Matcher.Match> search(int iteration, int ruleIndex, Rewrite<L, D> rule,
      EGraph<L, D> graph) {
    RuleStats s = statsOf(ruleIndex);
    if (s.bannedUntil > iteration) {
      return List.of();
    }
    List<Matcher.Match> matches = rule.search(graph);
    if (matches.size() > doubled(s.matchLimit, s.timesBanned)) {
      long ban = doubled(s.banLength, s.timesBanned);
      s.timesBanned++;
      s.bannedUntil = (int) Math.min(Integer.MAX_VALUE, iteration + ban);
      return List.of();
    }
    return matches;
  }

  /**
   * {@code value} doubled {@code times} times, saturating at {@link Integer#MAX_VALUE}: a plain
   * {@code int} shift wraps after 31 bans (Java masks the shift count), where egg's
   * {@code checked_shl} would panic.
   */
  private static int doubled(int value, int times) {
    return times >= Integer.numberOfLeadingZeros(value) ? Integer.MAX_VALUE : value << times;
  }

  @Override
  public void reset() {
    stats.clear();
  }

  @Override
  public boolean canStop(int iteration) {
    int minBan = Integer.MAX_VALUE;
    for (RuleStats s : stats) {
      if (s.bannedUntil > iteration) {
        minBan = Math.min(minBan, s.bannedUntil - iteration);
      }
    }
    if (minBan == Integer.MAX_VALUE) {
      return true;
    }
    // Rather than idle through the bans, shorten every one by the shortest, as egg does: the
    // rules whose ban was shortest run in the next iteration, the others stay banned for what
    // is left of theirs, and the run saturates only once an iteration with no rule banned
    // changes nothing.
    for (RuleStats s : stats) {
      if (s.bannedUntil > iteration) {
        s.bannedUntil -= minBan;
      }
    }
    return false;
  }
}
