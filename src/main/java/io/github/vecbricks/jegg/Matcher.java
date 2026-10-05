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
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * E-matching, the naive way: a pattern is matched against a class by trying each of the class's
 * nodes whose head matches, then each child pattern against the child's class, threading the
 * substitution through and backtracking by discarding it. Quadratic in the worst case and
 * enough at the graph sizes the plan targets (its prediction 5); egg's compiled machine is the
 * replacement if a measurement asks for it.
 *
 * <p>Order is fixed: classes in id order, a class's nodes in insertion order, a node's children
 * depth-first, so the list of matches is a function of the graph. A search starts at the classes
 * holding a node of the root head's class where the head names it, and allocates per match, not
 * per class visited.
 */
public final class Matcher {

  private Matcher() {
  }

  /**
   * One match: the class the pattern's root matched in, and what its variables stand for. A
   * value candidate (PLAN.md 3.1): immutable, compared by content.
   *
   * @param eclass the class the pattern's root matched in, as the id the search visited it by,
   *     which is a canonical id in a rebuilt graph
   * @param subst the bindings of the pattern's variables, extending the empty substitution
   */
  public record Match(int eclass, Subst subst) {
  }

  /**
   * Every match of {@code pattern} anywhere in {@code graph}, in class order.
   *
   * @param <L> the language
   * @param <D> the analysis fact
   * @param graph the graph to search, rebuilt so that its classes are canonical
   * @param pattern the pattern to match at each class
   * @return a fresh list of the matches, empty if there are none
   */
  public static <L extends Language<L>, D> List<Match> search(EGraph<L, D> graph,
      Pattern<L> pattern) {
    return search(graph, pattern, Integer.MAX_VALUE);
  }

  /**
   * The first {@code limit} matches of {@code pattern}, in class order - a prefix of what
   * {@link #search(EGraph, Pattern)} returns - and the search stops within the node at which the
   * limit is reached: egg's {@code search_with_limit}, which lets a scheduler find out that a
   * rule has more matches than it will apply without paying for all of them. The limit must be
   * positive.
   *
   * <p>A root head that names its node class ({@link Pattern.Head#type}) is tried only at the
   * classes holding such a node; any other root is tried at every class.
   *
   * @param <L> the language
   * @param <D> the analysis fact
   * @param graph the graph to search, rebuilt so that its classes are canonical
   * @param pattern the pattern to match at each class
   * @param limit the most matches to return; at least 1, {@link Integer#MAX_VALUE} for all
   * @return a fresh list of at most {@code limit} matches, empty if there are none
   * @throws IllegalArgumentException if {@code limit} is less than 1
   */
  public static <L extends Language<L>, D> List<Match> search(EGraph<L, D> graph,
      Pattern<L> pattern, int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("the limit must be positive, not " + limit);
    }
    List<Match> matches = new ArrayList<>();
    List<Subst> found = new ArrayList<>();
    Class<?> type = pattern instanceof Pattern.Node<L> node
        ? node.head().type().orElse(null) : null;
    if (type != null) {
      BitSet classes = graph.classesHolding(type);
      for (int id = classes.nextSetBit(0); id >= 0 && matches.size() < limit;
          id = classes.nextSetBit(id + 1)) {
        collect(graph, pattern, id, limit, matches, found);
      }
    } else {
      for (int id = graph.nextLiveClass(0); id >= 0 && matches.size() < limit;
          id = graph.nextLiveClass(id + 1)) {
        collect(graph, pattern, id, limit, matches, found);
      }
    }
    return matches;
  }

  private static <L extends Language<L>, D> void collect(EGraph<L, D> graph, Pattern<L> pattern,
      int id, int limit, List<Match> matches, List<Subst> found) {
    found.clear();
    matchIn(graph, pattern, id, Subst.EMPTY, limit - matches.size(), found);
    for (Subst subst : found) {
      matches.add(new Match(id, subst));
    }
  }

  /**
   * The substitutions under which {@code pattern} matches class {@code id}, extending
   * {@code subst}.
   *
   * @param <L> the language
   * @param <D> the analysis fact
   * @param graph the graph holding the class
   * @param pattern the pattern to match
   * @param id the class to match in; any id of it, which is canonicalised
   * @param subst the bindings the matches must agree with, {@link Subst#EMPTY} for none
   * @return a fresh list of the distinct substitutions, each extending {@code subst}, in match
   *     order; empty if the pattern does not match
   */
  public static <L extends Language<L>, D> List<Subst> matchIn(EGraph<L, D> graph,
      Pattern<L> pattern, int id, Subst subst) {
    List<Subst> out = new ArrayList<>();
    matchIn(graph, pattern, id, subst, Integer.MAX_VALUE, out);
    return out;
  }

  /**
   * Fills the empty {@code out} with the first {@code limit} substitutions of
   * {@link #matchIn(EGraph, Pattern, int, Subst)}, in its order, the class's nodes left
   * unvisited once the limit is reached. The cut is made between a node's substitutions and the
   * next node's: a node's own are all computed, since a cut inside the walk of its children
   * could lose some of them to deduplication and leave the prefix short. One substitution can
   * come up twice, from two nodes of the class (a head that reads no payload over two nodes
   * differing in theirs) or within one node's walk (a child head that binds a payload for some
   * nodes and not others), so the results are deduplicated, keeping the first; the set that does
   * it is made only once a node yields more than one result or a second node yields any.
   */
  private static <L extends Language<L>, D> void matchIn(EGraph<L, D> graph,
      Pattern<L> pattern, int id, Subst subst, int limit, List<Subst> out) {
    int root = graph.find(id);
    switch (pattern) {
      case Pattern.Var<L>(var name) -> {
        var bound = subst.id(name);
        if (bound.isEmpty()) {
          out.add(subst.bind(name, root));
        } else if (graph.find(bound.getAsInt()) == root) {
          out.add(subst);
        }
      }
      case Pattern.Node<L>(var head, var children) -> {
        Set<Subst> seen = null;
        List<L> nodes = graph.classOf(root).mutableNodes();
        for (int i = 0; i < nodes.size() && out.size() < limit; i++) {
          L node = nodes.get(i);
          if (node.children().size() != children.size()) {
            continue;
          }
          Subst headBound = head.match(node, subst);
          if (headBound == null) {
            continue;
          }
          int before = out.size();
          matchChildren(graph, children, 0, node, headBound, out);
          int added = out.size() - before;
          if (added > 1 || (added > 0 && before > 0)) {
            if (seen == null) {
              seen = new HashSet<>(out.subList(0, before));
            }
            int kept = before;
            for (int j = before; j < out.size(); j++) {
              Subst s = out.get(j);
              if (seen.add(s)) {
                out.set(kept++, s);
              }
            }
            out.subList(kept, out.size()).clear();
          }
        }
        if (out.size() > limit) {
          out.subList(limit, out.size()).clear();
        }
      }
    }
  }

  /**
   * Matches {@code node}'s children from the {@code i}th on against the child patterns under
   * {@code subst}, appending each complete substitution to {@code out}: a variable child binds
   * or agrees in place, a node child's substitutions each continue to the next child.
   */
  private static <L extends Language<L>, D> void matchChildren(EGraph<L, D> graph,
      List<Pattern<L>> children, int i, L node, Subst subst, List<Subst> out) {
    if (i == children.size()) {
      out.add(subst);
      return;
    }
    int child = node.children().get(i);
    switch (children.get(i)) {
      case Pattern.Var<L>(var name) -> {
        var bound = subst.id(name);
        int root = graph.find(child);
        if (bound.isEmpty()) {
          matchChildren(graph, children, i + 1, node, subst.bind(name, root), out);
        } else if (graph.find(bound.getAsInt()) == root) {
          matchChildren(graph, children, i + 1, node, subst, out);
        }
      }
      case Pattern.Node<L> nested -> {
        List<Subst> partial = new ArrayList<>();
        matchIn(graph, nested, child, subst, Integer.MAX_VALUE, partial);
        for (Subst s : partial) {
          matchChildren(graph, children, i + 1, node, s, out);
        }
      }
    }
  }

  /**
   * The class of {@code pattern} instantiated under {@code subst}: a variable is its binding, a
   * node is added over its instantiated children. This is a right-hand side's application.
   *
   * @param <L> the language
   * @param <D> the analysis fact
   * @param graph the graph the nodes are added to
   * @param pattern the pattern to instantiate
   * @param subst the bindings of the pattern's variables; each must be bound, or the call throws
   *     an {@link IllegalArgumentException}
   * @return the canonical id of the class the instantiated pattern is in
   */
  public static <L extends Language<L>, D> int instantiate(EGraph<L, D> graph,
      Pattern<L> pattern, Subst subst) {
    switch (pattern) {
      case Pattern.Var<L>(var name) -> {
        return graph.find(subst.idOf(name));
      }
      case Pattern.Node<L>(var head, var children) -> {
        int[] ids = new int[children.size()];
        for (int i = 0; i < ids.length; i++) {
          ids[i] = instantiate(graph, children.get(i), subst);
        }
        return graph.add(head.build(subst, IntList.of(ids)));
      }
    }
  }
}
