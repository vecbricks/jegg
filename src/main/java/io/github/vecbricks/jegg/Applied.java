/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.jegg;

/**
 * What applying one match did, in the two senses a run needs. {@code unions} is the number of
 * unions that changed the graph. {@code counted} is the number egg's runner counts as applied:
 * the same for a rule that adds and unions one right-hand side, but one per substitution for a
 * multi-pattern, whose egg {@code apply_matches} returns an id for every substitution whether
 * or not a union changed anything. A run saturates only in an iteration in which nothing was
 * counted.
 *
 * @param unions the unions that merged two classes
 * @param counted what egg's runner would count as applied
 */
public record Applied(int unions, int counted) {
}
