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
 * E-matching, the naive way: a pattern is matched against a class by trying each of the class's
 * nodes whose head matches, then each child pattern against the child's class, threading the
 * substitution through and backtracking by discarding it. Quadratic in the worst case and
 * enough at the graph sizes the plan targets (its prediction 5); egg's compiled machine is the
 * replacement if a measurement asks for it.
 *
 * <p>Order is fixed: classes in id order, a class's nodes in insertion order, so the list of
 * matches is a function of the graph.
 */
public final class Matcher {

  private Matcher() {
  }

  /** One match: the class the pattern's root matched in, and what its variables stand for. */
  public record Match(int eclass, Subst subst) {
  }

  /** Every match of {@code pattern} anywhere in {@code graph}, in class order. */
  public static <L extends Language<L>, D> List<Match> search(EGraph<L, D> graph,
      Pattern<L> pattern) {
    List<Match> matches = new ArrayList<>();
    for (EClass<L, D> eclass : graph.classes()) {
      for (Subst subst : matchIn(graph, pattern, eclass.id(), Subst.EMPTY)) {
        matches.add(new Match(eclass.id(), subst));
      }
    }
    return matches;
  }

  /**
   * The substitutions under which {@code pattern} matches class {@code id}, extending
   * {@code subst}.
   */
  public static <L extends Language<L>, D> List<Subst> matchIn(EGraph<L, D> graph,
      Pattern<L> pattern, int id, Subst subst) {
    int root = graph.find(id);
    switch (pattern) {
      case Pattern.Var<L> v -> {
        var bound = subst.id(v.name());
        if (bound.isPresent()) {
          return graph.find(bound.getAsInt()) == root ? List.of(subst) : List.of();
        }
        return List.of(subst.bind(v.name(), root));
      }
      case Pattern.Node<L> p -> {
        Set<Subst> out = new LinkedHashSet<>();
        for (L node : graph.classOf(root).nodes()) {
          if (node.children().size() != p.children().size()) {
            continue;
          }
          Subst headBound = p.head().match(node, subst);
          if (headBound == null) {
            continue;
          }
          List<Subst> partial = List.of(headBound);
          for (int i = 0; i < p.children().size() && !partial.isEmpty(); i++) {
            List<Subst> next = new ArrayList<>();
            for (Subst s : partial) {
              next.addAll(matchIn(graph, p.children().get(i), node.children().get(i), s));
            }
            partial = next;
          }
          out.addAll(partial);
        }
        return new ArrayList<>(out);
      }
    }
  }

  /**
   * The class of {@code pattern} instantiated under {@code subst}: a variable is its binding, a
   * node is added over its instantiated children. This is a right-hand side's application.
   */
  public static <L extends Language<L>, D> int instantiate(EGraph<L, D> graph,
      Pattern<L> pattern, Subst subst) {
    switch (pattern) {
      case Pattern.Var<L> v -> {
        return graph.find(subst.idOf(v.name()));
      }
      case Pattern.Node<L> p -> {
        int[] ids = new int[p.children().size()];
        for (int i = 0; i < ids.length; i++) {
          ids[i] = instantiate(graph, p.children().get(i), subst);
        }
        return graph.add(p.head().build(subst, IntList.of(ids)));
      }
    }
  }
}
