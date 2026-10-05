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
import java.util.Optional;
import java.util.Set;

/**
 * What a {@link Rewrite} searches with: a {@link Pattern}, or a {@link MultiPattern} that joins
 * several. egg's {@code Searcher} trait, reduced to what the runner and the schedulers use: the
 * matches, to a limit, and the variables they bind. An implementation must return them in a
 * fixed order, so that a search is a function of the graph.
 *
 * @param <L> the language
 */
public interface Searcher<L extends Language<L>> {

  /**
   * The first {@code limit} matches in the graph, in the matcher's order: a prefix of all of
   * them, which lets a scheduler find out that a rule has more matches than it will apply
   * without paying for all of them.
   *
   * @param <D> the analysis fact
   * @param graph the graph to search, rebuilt so that its classes are canonical; not changed
   * @param limit the most matches to return; at least 1, {@link Integer#MAX_VALUE} for all
   * @return a fresh list of at most {@code limit} matches, empty if there are none
   * @throws IllegalArgumentException if {@code limit} is less than 1
   */
  <D> List<Matcher.Match> search(EGraph<L, D> graph, int limit);

  /**
   * The subterm variables a match binds, in first-occurrence order.
   *
   * @return the names, without the {@code ?}
   */
  Set<String> subtermVariables();

  /**
   * The payload variables a match binds, or empty if a head does not declare its variables
   * ({@link Pattern.Head#variables}).
   *
   * @return the names, without the {@code ?}, or empty if unknown
   */
  Optional<Set<String>> payloadVariables();
}
