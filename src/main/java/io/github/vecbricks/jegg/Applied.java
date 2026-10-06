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
 * the same for a rule whose right-hand side is a pattern or a function, but one per match for a
 * right-hand side that is a multi-pattern ({@link Applier#multi}), whose egg
 * {@code apply_matches} returns an id for every substitution whether or not a union changed
 * anything. A run saturates only in an iteration in which nothing was counted. The left-hand
 * side does not decide: a multi-pattern searched and a pattern applied counts unions.
 *
 * @param unions the unions that merged two classes
 * @param counted what egg's runner would count as applied
 */
public record Applied(int unions, int counted) {

  /** Nothing changed and nothing counted: a pattern rule whose union was there already. */
  static final Applied NOTHING = new Applied(0, 0);
  /** One union, counted once: a pattern rule's usual application. */
  static final Applied ONE_UNION = new Applied(1, 1);
  /** No union, counted once: a multi-pattern match that changed nothing. */
  static final Applied COUNTED_ONLY = new Applied(0, 1);

  /**
   * A pattern rule's result, shared for the common counts so an application allocates nothing.
   *
   * @param unions the unions that changed the graph
   * @return the result, which counts each union as applied
   */
  static Applied unions(int unions) {
    return switch (unions) {
      case 0 -> NOTHING;
      case 1 -> ONE_UNION;
      default -> new Applied(unions, unions);
    };
  }

  /**
   * A multi-pattern match's result, shared for the common counts.
   *
   * @param unions the unions that changed the graph
   * @return the result, which counts the match once whatever it changed
   */
  static Applied countedOnce(int unions) {
    return switch (unions) {
      case 0 -> COUNTED_ONLY;
      case 1 -> ONE_UNION;
      default -> new Applied(unions, 1);
    };
  }
}
