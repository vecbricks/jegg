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
 * The tests' term type: a tiny s-expression tree, which each suite bridges to its language and
 * reads patterns from through {@link #pattern}, where an atom starting with '?' is a variable.
 * Any whitespace separates, so egg's test terms parse as written, across lines.
 */
record Term(String op, List<Term> kids) {

  /** A pattern from an s-expression, each node built through {@code bridge}. */
  static <L extends Language<L>> Pattern<L> pattern(String s, TreeBridge<Term, L> bridge) {
    return pattern(parse(s), bridge);
  }

  static <L extends Language<L>> Pattern<L> pattern(Term t, TreeBridge<Term, L> bridge) {
    if (t.kids().isEmpty() && t.op().startsWith("?")) {
      return Pattern.var(t.op().substring(1));
    }
    List<Pattern<L>> kids = t.kids().stream().map(k -> pattern(k, bridge)).toList();
    return new Pattern.Node<>(Pattern.head(bridge.node(t, IntList.EMPTY)), kids);
  }

  static Term parse(String s) {
    Parser p = new Parser(s);
    Term t = p.term();
    p.skip();
    if (p.i != s.length()) {
      throw new IllegalArgumentException("trailing input at " + p.i + " in: " + s);
    }
    return t;
  }

  private static final class Parser {
    private final String s;
    private int i = 0;

    Parser(String s) {
      this.s = s;
    }

    Term term() {
      skip();
      if (peek() == '(') {
        i++;
        skip();
        String op = atom();
        List<Term> kids = new ArrayList<>();
        skip();
        while (peek() != ')') {
          kids.add(term());
          skip();
        }
        i++;
        return new Term(op, kids);
      }
      return new Term(atom(), List.of());
    }

    private char peek() {
      if (i >= s.length()) {
        throw new IllegalArgumentException("unexpected end of input at " + i + " in: " + s);
      }
      return s.charAt(i);
    }

    private String atom() {
      int start = i;
      while (i < s.length() && s.charAt(i) != '(' && s.charAt(i) != ')'
          && !Character.isWhitespace(s.charAt(i))) {
        i++;
      }
      if (i == start) {
        throw new IllegalArgumentException("expected an atom at " + i + " in: " + s);
      }
      return s.substring(start, i);
    }

    private void skip() {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
        i++;
      }
    }
  }
}
