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
import java.util.Random;

/**
 * Random terms of egg's {@code prop} and {@code math} languages for the differential fuzzing
 * (#44), seeded so a run is reproducible, bounded in depth so a term stays small, and printed as
 * s-expressions egg parses. {@code prop}: four variables and the constants over {@code &},
 * {@code |}, {@code ->} and {@code ~}. {@code math}: three symbols and small constants (so that
 * constant folding cannot overflow, where egg's {@code NotNan} arithmetic would panic) over
 * egg's operators, with the derivative and the integral as top-level shapes as egg's tests have
 * them.
 */
final class TermGenerator {

  private static final String[] PROP_LEAVES = {"x", "y", "z", "w", "true", "false"};
  private static final String[] MATH_LEAVES = {"x", "y", "z", "0", "1", "2", "3", "0.5"};
  private static final String[] MATH_UNARY = {"ln", "sqrt", "sin", "cos"};
  private static final String[] MATH_BINARY = {"+", "-", "*", "/", "pow"};
  private static final String[] MATH_VARIABLES = {"x", "y", "z"};

  private final Random random;
  private final boolean math;

  /**
   * A generator.
   *
   * @param language {@code prop} or {@code math}
   * @param seed the seed; the same seed gives the same terms
   */
  TermGenerator(String language, long seed) {
    if (!language.equals("prop") && !language.equals("math")) {
      throw new IllegalArgumentException("unknown language " + language);
    }
    this.math = language.equals("math");
    this.random = new Random(seed);
  }

  /** The next term. */
  String next() {
    if (!math) {
      return prop(4);
    }
    String variable = MATH_VARIABLES[random.nextInt(MATH_VARIABLES.length)];
    return switch (random.nextInt(5)) {
      case 0 -> "(d " + variable + " " + math(3) + ")";
      case 1 -> "(i " + math(3) + " " + variable + ")";
      default -> math(4);
    };
  }

  /**
   * The next {@code count} terms.
   *
   * @param count how many
   * @return the terms in order
   */
  List<String> terms(int count) {
    List<String> out = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      out.add(next());
    }
    return out;
  }

  private String prop(int depth) {
    if (depth == 0 || random.nextInt(4) == 0) {
      return PROP_LEAVES[random.nextInt(PROP_LEAVES.length)];
    }
    return switch (random.nextInt(8)) {
      case 0 -> "(~ " + prop(depth - 1) + ")";
      case 1, 2 -> "(& " + prop(depth - 1) + " " + prop(depth - 1) + ")";
      case 3, 4 -> "(| " + prop(depth - 1) + " " + prop(depth - 1) + ")";
      default -> "(-> " + prop(depth - 1) + " " + prop(depth - 1) + ")";
    };
  }

  private String math(int depth) {
    if (depth == 0 || random.nextInt(4) == 0) {
      return MATH_LEAVES[random.nextInt(MATH_LEAVES.length)];
    }
    if (random.nextInt(4) == 0) {
      return "(" + MATH_UNARY[random.nextInt(MATH_UNARY.length)] + " " + math(depth - 1) + ")";
    }
    return "(" + MATH_BINARY[random.nextInt(MATH_BINARY.length)] + " " + math(depth - 1) + " "
        + math(depth - 1) + ")";
  }
}
