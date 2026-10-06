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
 * egg's multi-pattern syntax for the tests: {@code ?x = p, ?y = q = r}, a comma between
 * statements and {@code =} chaining patterns on one variable, so that {@code ?y = q = r} is the
 * two clauses {@code ?y = q} and {@code ?y = r}. The library parses no text.
 */
final class MultiTerm {

  private MultiTerm() {
  }

  static <L extends Language<L>> MultiPattern<L> parse(String s, TreeBridge<Term, L> bridge) {
    List<MultiPattern.Clause<L>> clauses = new ArrayList<>();
    for (String statement : s.split(",")) {
      String trimmed = statement.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      String[] parts = trimmed.split("=");
      String var = parts[0].trim().substring(1);
      for (int i = 1; i < parts.length; i++) {
        clauses.add(MultiPattern.clause(var, Term.pattern(parts[i].trim(), bridge)));
      }
    }
    return new MultiPattern<>(clauses);
  }
}
