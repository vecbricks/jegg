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
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * E-matching by backtracking: a pattern is matched against a class by trying each of the class's
 * nodes whose head matches, then each child pattern against the child's class, threading the
 * substitution through and backtracking by discarding it. egg's compiled machine is the
 * replacement if a measurement asks for it.
 *
 * <p>Order is fixed: classes in id order, a class's nodes in insertion order, a node's children
 * depth-first, so the list of matches is a function of the graph. A search starts at the classes
 * holding a node of the root head's class where the head names it, and allocates per match, not
 * per class visited. In a class of {@link #INDEX_FROM} nodes or more, a pattern node whose head
 * has a {@link Pattern.Head#key} visits only the nodes with that head, and one whose head names
 * a {@link Pattern.Head#type} only the nodes of that class, through the indexes the class keeps
 * ({@code EClass}); the positions come in insertion order, so the matches are the ones a walk of
 * every node would find, in its order. A smaller class is walked, which costs less than
 * building its index.
 */
public final class Matcher {

  /** The size from which a class's nodes are found through its indexes rather than walked. */
  static final int INDEX_FROM = 8;

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
  public static <L extends Language<L>, D extends @Nullable Object>
      List<Match> search(EGraph<L, D> graph, Pattern<L> pattern) {
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
  public static <L extends Language<L>, D extends @Nullable Object>
      List<Match> search(EGraph<L, D> graph, Pattern<L> pattern, int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("the limit must be positive, not " + limit);
    }
    List<Match> matches = new ArrayList<>();
    List<Subst> found = new ArrayList<>();
    Walk<L, D> walk = new Walk<>(graph);
    boolean distinct = distinctByConstruction(graph, pattern);
    Class<?> type = pattern instanceof Pattern.Node<L> node
        ? node.head().type().orElse(null) : null;
    if (type != null) {
      BitSet classes = graph.classesHolding(type);
      for (int id = classes.nextSetBit(0); id >= 0 && matches.size() < limit;
          id = classes.nextSetBit(id + 1)) {
        collect(walk, pattern, id, limit, matches, found, distinct);
      }
    } else {
      for (int id = graph.nextLiveClass(0); id >= 0 && matches.size() < limit;
          id = graph.nextLiveClass(id + 1)) {
        collect(walk, pattern, id, limit, matches, found, distinct);
      }
    }
    return matches;
  }

  /**
   * The first {@code limit} matches of a multi-pattern, in class order of its first clause: a
   * depth-first join, egg's machine without the compilation. For each clause, in order:
   * <ul>
   *   <li>a pattern that is ground under the bindings so far, and not a leaf, is looked up in the
   *       hashcons, as egg's compiled {@code Lookup} does; so it finds a node that
   *       {@link EGraph#retainNodes} dropped from its class, which a walk of the class's nodes
   *       would not;
   *   <li>otherwise a clause whose variable is bound is matched in that class only, and so is a
   *       clause whose pattern is a bare variable already bound;
   *   <li>otherwise the clause ranges over the classes holding a node of the pattern's root
   *       class where its head names one, else over every class, which is egg's {@code Scan}.
   * </ul>
   * A pattern variable named like its clause variable ({@code ?x = (f ?x)}) is a join, as in
   * egg: the clause variable is bound before the pattern is matched. The match's class is the
   * one the first clause matched in, and its substitution binds every clause variable and every
   * pattern variable. Substitutions are distinct: two partial matches that differ only in a
   * payload variable some heads bind and others do not can extend to one substitution, which
   * is kept once.
   *
   * <p>One difference from egg remains: a ground subterm inside a clause that is not ground
   * itself is matched by walking its class's nodes, as a single pattern's is, where egg looks it
   * up; the two differ only for a node {@code retainNodes} dropped.
   *
   * @param <L> the language
   * @param <D> the analysis fact
   * @param graph the graph to search, rebuilt so that its classes are canonical
   * @param multi the clauses to join
   * @param limit the most matches to return; at least 1, {@link Integer#MAX_VALUE} for all
   * @return a fresh list of at most {@code limit} matches, empty if there are none
   * @throws IllegalArgumentException if {@code limit} is less than 1, or the first clause's
   *     pattern is a bare variable, which has no class to start from
   */
  public static <L extends Language<L>, D extends @Nullable Object>
      List<Match> search(EGraph<L, D> graph, MultiPattern<L> multi, int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("the limit must be positive, not " + limit);
    }
    if (multi.startsWithBareVariable()) {
      throw new IllegalArgumentException(
          "a multi-pattern cannot start with a bare variable: " + multi);
    }
    List<Match> matches = new ArrayList<>();
    new Join<>(graph, multi.clauses(), limit, matches).join(0, Subst.EMPTY, -1);
    return matches;
  }

  /** The state of one multi-pattern search: the clauses, a buffer per clause, the leaves seen. */
  private static final class Join<L extends Language<L>, D extends @Nullable Object> {
    private final EGraph<L, D> graph;
    private final Walk<L, D> walk;
    private final List<MultiPattern.Clause<L>> clauses;
    private final int limit;
    private final List<Match> out;
    private final Set<Subst> seen = new HashSet<>();
    // One reusable list per clause: the substitutions a clause yields in one class are read
    // while the clauses after it fill their own, so a search allocates per match, not per class.
    private final List<List<Subst>> buffers;
    private final List<Set<String>> variables;
    private final List<Optional<Set<String>>> payloads;
    // Per clause, whether its pattern's substitutions in one class are distinct by construction
    // (matchIn), so that the walk of the class needs no set of its own.
    private final boolean[] distinct;

    Join(EGraph<L, D> graph, List<MultiPattern.Clause<L>> clauses, int limit, List<Match> out) {
      this.graph = graph;
      this.walk = new Walk<>(graph);
      this.clauses = clauses;
      this.limit = limit;
      this.out = out;
      this.buffers = new ArrayList<>(clauses.size());
      this.variables = new ArrayList<>(clauses.size());
      this.payloads = new ArrayList<>(clauses.size());
      this.distinct = new boolean[clauses.size()];
      for (int i = 0; i < clauses.size(); i++) {
        MultiPattern.Clause<L> clause = clauses.get(i);
        buffers.add(new ArrayList<>());
        variables.add(clause.pattern().subtermVariables());
        payloads.add(clause.pattern().payloadVariables());
        distinct[i] = distinctByConstruction(graph, clause.pattern());
      }
    }

    void join(int index, Subst subst, int first) {
      if (index == clauses.size()) {
        if (seen.add(subst)) {
          out.add(new Match(first, subst));
        }
        return;
      }
      MultiPattern.Clause<L> clause = clauses.get(index);
      OptionalInt named = subst.id(clause.var());
      if (clause.pattern() instanceof Pattern.Node<L> node && !node.children().isEmpty()
          && isGround(index, subst)) {
        OptionalInt found = lookUp(node, subst);
        if (found.isEmpty()) {
          return;
        }
        int id = graph.find(found.getAsInt());
        if (named.isPresent() && graph.find(named.getAsInt()) != id) {
          return;
        }
        join(index + 1, subst.bind(clause.var(), id), index == 0 ? id : first);
        return;
      }
      if (named.isPresent()) {
        joinIn(index, graph.find(named.getAsInt()), subst, first);
        return;
      }
      if (clause.pattern() instanceof Pattern.Var<L>(var name) && subst.id(name).isPresent()) {
        joinIn(index, graph.find(subst.id(name).getAsInt()), subst, first);
        return;
      }
      Class<?> type = clause.pattern() instanceof Pattern.Node<L> node
          ? node.head().type().orElse(null) : null;
      if (type != null) {
        BitSet classes = graph.classesHolding(type);
        for (int id = classes.nextSetBit(0); id >= 0 && out.size() < limit;
            id = classes.nextSetBit(id + 1)) {
          joinIn(index, id, subst, first);
        }
      } else {
        for (int id = graph.nextLiveClass(0); id >= 0 && out.size() < limit;
            id = graph.nextLiveClass(id + 1)) {
          joinIn(index, id, subst, first);
        }
      }
    }

    private void joinIn(int index, int id, Subst subst, int first) {
      MultiPattern.Clause<L> clause = clauses.get(index);
      int start = index == 0 ? id : first;
      // Bound first when the pattern names its own clause variable, so the pattern's ?x and the
      // clause's ?x are one class (egg's compiler binds the clause register before the pattern);
      // otherwise bound after, which costs nothing for the classes that do not match.
      boolean self = variables.get(index).contains(clause.var());
      Subst given = self ? subst.bind(clause.var(), id) : subst;
      List<Subst> found = buffers.get(index);
      found.clear();
      walk.matchIn(clause.pattern(), id, given, Integer.MAX_VALUE, found, distinct[index]);
      for (int i = 0; i < found.size() && out.size() < limit; i++) {
        Subst matched = found.get(i);
        join(index + 1, self ? matched : matched.bind(clause.var(), id), start);
      }
    }

    /**
     * Whether every variable of the clause's pattern, subterm and payload, is bound; a head that
     * does not declare its payload variables makes the pattern not ground, so it is walked.
     */
    private boolean isGround(int index, Subst subst) {
      for (String v : variables.get(index)) {
        if (subst.id(v).isEmpty()) {
          return false;
        }
      }
      if (payloads.get(index).isEmpty()) {
        return false;
      }
      for (String v : payloads.get(index).get()) {
        if (!subst.hasPayload(v)) {
          return false;
        }
      }
      return true;
    }

    /** The class of a ground pattern through the hashcons, or empty if the graph lacks it. */
    private OptionalInt lookUp(Pattern<L> pattern, Subst subst) {
      return switch (pattern) {
        case Pattern.Var<L>(var name) -> subst.id(name);
        case Pattern.Node<L>(var head, var children) -> {
          int[] ids = new int[children.size()];
          for (int i = 0; i < ids.length; i++) {
            OptionalInt child = lookUp(children.get(i), subst);
            if (child.isEmpty()) {
              yield OptionalInt.empty();
            }
            ids[i] = child.getAsInt();
          }
          yield graph.lookup(head.build(subst, IntList.wrap(ids)));
        }
      };
    }
  }

  private static <L extends Language<L>, D extends @Nullable Object>
      void collect(Walk<L, D> walk, Pattern<L> pattern, int id, int limit, List<Match> matches,
          List<Subst> found, boolean distinct) {
    found.clear();
    walk.matchIn(pattern, id, Subst.EMPTY, limit - matches.size(), found, distinct);
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
  public static <L extends Language<L>, D extends @Nullable Object>
      List<Subst> matchIn(EGraph<L, D> graph,
      Pattern<L> pattern, int id, Subst subst) {
    List<Subst> out = new ArrayList<>();
    new Walk<>(graph).matchIn(pattern, id, subst, Integer.MAX_VALUE, out,
        distinctByConstruction(graph, pattern));
    return out;
  }

  /**
   * Whether every substitution {@code pattern} yields in one class of {@code graph} is distinct
   * from the others without a set to tell: so when the graph is rebuilt and every head of the
   * pattern has a {@link Pattern.Head#key}. Then no head binds anything, and a substitution fixes
   * the node chosen at each pattern node - its head and, by the variables' bindings or by
   * induction on the children, each child's class - and a canonical node with that head and
   * those children is one node in one class (the hashcons invariant), listed once
   * ({@code canonicalizeNodes}); two derivations of one substitution are then one derivation,
   * which the walk makes once. A dirty graph, whose merged class may list one node twice, and a
   * head without a key, which may bind for some nodes and not others, each need the set.
   */
  private static <L extends Language<L>> boolean distinctByConstruction(EGraph<L, ?> graph,
      Pattern<L> pattern) {
    return !graph.isDirty() && allKeyed(pattern);
  }

  private static <L extends Language<L>> boolean allKeyed(Pattern<L> pattern) {
    return switch (pattern) {
      case Pattern.Var<L> v -> true;
      case Pattern.Node<L>(var head, var children) -> {
        if (head.key().isEmpty()) {
          yield false;
        }
        for (Pattern<L> child : children) {
          if (!allKeyed(child)) {
            yield false;
          }
        }
        yield true;
      }
    };
  }

  /**
   * One search's walk of the graph: the graph, and a pool of the lists a nested pattern node's
   * substitutions are gathered in, so that a search allocates per match, not per candidate.
   */
  private static final class Walk<L extends Language<L>, D extends @Nullable Object> {
    private final EGraph<L, D> graph;
    private final List<List<Subst>> free = new ArrayList<>();

    Walk(EGraph<L, D> graph) {
      this.graph = graph;
    }

    /**
     * Fills the empty {@code out} with the first {@code limit} substitutions of
     * {@link #matchIn(EGraph, Pattern, int, Subst)}, in its order, the class's nodes left
     * unvisited once the limit is reached. The cut is made between a node's substitutions and the
     * next node's: a node's own are all computed, since a cut inside the walk of its children
     * could lose some of them to deduplication and leave the prefix short. One substitution can
     * come up twice, from two nodes of the class (a head that reads no payload over two nodes
     * differing in theirs) or within one node's walk (a child head that binds a payload for some
     * nodes and not others), so unless the results are {@code distinct} by construction
     * ({@link #distinctByConstruction}) they are deduplicated, keeping the first; the set that does
     * it is made only once a node yields more than one result or a second node yields any.
     */
      void matchIn(Pattern<L> pattern, int id, Subst subst, int limit, List<Subst> out,
          boolean distinct) {
      int root = graph.find(id);
      switch (pattern) {
        case Pattern.Var<L>(var name) -> {
          int bound = subst.idOrUnbound(name);
          if (bound == Subst.UNBOUND) {
            out.add(subst.bindNew(name, root));
          } else if (graph.find(bound) == root) {
            out.add(subst);
          }
        }
        case Pattern.Node<L>(var head, var children) -> {
          Set<Subst> seen = null;
          EClass<L, D> eclass = graph.classOf(root);
          List<L> nodes = eclass.readNodes();
          // The candidates: every node of a small class; in a larger one, the positions of the
          // nodes with the head's key (whose heads then need no test) or of the head's node class.
          IntArray positions = null;
          boolean indexed = false;
          boolean headsMatch = false;
          if (nodes.size() >= INDEX_FROM) {
            Optional<Object> key = head.key();
            if (key.isPresent()) {
              positions = eclass.positionsWithHead(key.get());
              indexed = true;
              headsMatch = true;
            } else {
              Optional<Class<? extends L>> type = head.type();
              if (type.isPresent()) {
                positions = eclass.positionsOfType(type.get());
                indexed = true;
              }
            }
          }
          if (indexed && positions == null) {
            return;
          }
          int candidates = indexed ? positions.size() : nodes.size();
          for (int i = 0; i < candidates && out.size() < limit; i++) {
            L node = nodes.get(indexed ? positions.get(i) : i);
            if (node.children().size() != children.size()) {
              continue;
            }
            Subst headBound = headsMatch ? subst : head.match(node, subst);
            if (headBound == null) {
              continue;
            }
            int before = out.size();
            matchChildren(children, 0, node, headBound, out, distinct);
            int added = out.size() - before;
            if (!distinct && (added > 1 || (added > 0 && before > 0))) {
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
      void matchChildren(List<Pattern<L>> children, int i, L node, Subst subst, List<Subst> out,
          boolean distinct) {
      if (i == children.size()) {
        out.add(subst);
        return;
      }
      int child = node.children().get(i);
      switch (children.get(i)) {
        case Pattern.Var<L>(var name) -> {
          int bound = subst.idOrUnbound(name);
          int root = graph.find(child);
          if (bound == Subst.UNBOUND) {
            matchChildren(children, i + 1, node, subst.bindNew(name, root), out, distinct);
          } else if (graph.find(bound) == root) {
            matchChildren(children, i + 1, node, subst, out, distinct);
          }
        }
        case Pattern.Node<L> nested -> {
          // The nested node's substitutions go into a list from the pool, read by index while the
          // children after it fill lists of their own, and returned once read.
          List<Subst> partial = free.isEmpty() ? new ArrayList<>() : free.remove(free.size() - 1);
          matchIn(nested, child, subst, Integer.MAX_VALUE, partial, distinct);
          for (int k = 0; k < partial.size(); k++) {
            matchChildren(children, i + 1, node, partial.get(k), out, distinct);
          }
          partial.clear();
          free.add(partial);
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
  public static <L extends Language<L>, D extends @Nullable Object>
      int instantiate(EGraph<L, D> graph, Pattern<L> pattern, Subst subst) {
    switch (pattern) {
      case Pattern.Var<L>(var name) -> {
        return graph.find(subst.idOf(name));
      }
      case Pattern.Node<L>(var head, var children) -> {
        int[] ids = new int[children.size()];
        for (int i = 0; i < ids.length; i++) {
          ids[i] = instantiate(graph, children.get(i), subst);
        }
        return graph.add(head.build(subst, IntList.wrap(ids)));
      }
    }
  }
}
