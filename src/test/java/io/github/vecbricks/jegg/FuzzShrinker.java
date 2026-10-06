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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The one-step reductions of a term, for shrinking a divergence found by the differential
 * fuzzing (#44) to the smallest term that still diverges: a subterm replaced by one of its
 * children, or by a leaf. The candidates come smallest first and distinct, and every one is
 * strictly smaller than the term.
 */
final class FuzzShrinker {

  private FuzzShrinker() {
  }

  /**
   * The terms one step smaller than {@code term}.
   *
   * @param term an s-expression
   * @param language {@code prop} or {@code math}, which says the leaves a subterm may shrink to
   * @return the distinct reductions, fewest nodes first
   */
  static List<String> reductions(String term, String language) {
    List<String> leaves = language.equals("prop") ? List.of("x", "true") : List.of("x", "1");
    Set<Term> found = new LinkedHashSet<>();
    collect(Term.parse(term), leaves, found);
    Term original = Term.parse(term);
    List<Term> sorted = new ArrayList<>(found);
    sorted.removeIf(t -> t.equals(original) || size(t) >= size(original));
    sorted.sort((a, b) -> Integer.compare(size(a), size(b)));
    return sorted.stream().map(FuzzShrinker::render).toList();
  }

  private static void collect(Term t, List<String> leaves, Set<Term> out) {
    out.addAll(t.kids());
    for (int i = 0; i < t.kids().size(); i++) {
      Term kid = t.kids().get(i);
      if (!kid.kids().isEmpty()) {
        for (String leaf : leaves) {
          out.add(replace(t, i, new Term(leaf, List.of())));
        }
      }
      Set<Term> inner = new LinkedHashSet<>();
      collect(kid, leaves, inner);
      for (Term reduced : inner) {
        out.add(replace(t, i, reduced));
      }
    }
  }

  private static Term replace(Term t, int index, Term with) {
    List<Term> kids = new ArrayList<>(t.kids());
    kids.set(index, with);
    return new Term(t.op(), kids);
  }

  static int size(Term t) {
    return 1 + t.kids().stream().mapToInt(FuzzShrinker::size).sum();
  }

  /** The s-expression of a term. */
  static String render(Term t) {
    if (t.kids().isEmpty()) {
      return t.op();
    }
    StringBuilder b = new StringBuilder("(").append(t.op());
    t.kids().forEach(k -> b.append(' ').append(render(k)));
    return b.append(')').toString();
  }
}
