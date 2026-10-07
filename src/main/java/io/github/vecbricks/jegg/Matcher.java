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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
 * depth-first in an order the pattern alone decides ({@code View}), so the list of matches is a
 * function of the graph and the pattern. A search starts at the classes
 * holding a node of the root head's class where the head names it, and allocates per match, not
 * per class visited. A pattern node whose head has a {@link Pattern.Head#key} reads the class's
 * table ({@code EClass.Table}): the nodes sorted by head, insertion order kept within a head,
 * with every node's arity and children laid flat, so the run of its head is found by binary
 * search and read as consecutive ints, and no node object is touched until a match is complete;
 * egg's sorted node list, kept as a cache the class drops when its list changes. A head that
 * names a {@link Pattern.Head#type} and reads the node's payload visits the nodes of that class
 * through the class's type index, in a class of {@link #INDEX_FROM} nodes or more, and walks a
 * smaller class; a head with neither walks every node. The candidates come in insertion order
 * either way, so the matches are the ones a walk of every node would find, in its order.
 *
 * <p>Two of egg's compiled matcher's devices are kept. A nested pattern node whose variables are
 * all bound when it is reached, and that is not a leaf, may be looked up instead of walked: the
 * node it names is built from the bindings and found through the hashcons (egg's {@code Lookup}),
 * and must be in the child's class. The lookup finds a node {@link EGraph#retainNodes} dropped,
 * as egg's memo finds it, so it is always used where the child's class has pruned, the one
 * place a walk of the class could miss a node; elsewhere it gives the matches the walk gives,
 * and is used only where the head's run in the child's class is longer than
 * {@link #LOOKUP_FROM} nodes, since a lookup costs about as much as walking that many. And a
 * node's nested children are matched in an order that follows egg's compiler, the one with more
 * variables first and the smaller among equals, so that the ones after it are ground, and looked
 * up, or narrower; egg counts only the variables not yet bound where it reaches a node, this
 * counts all of a child's, which differs only for a variable an ancestor or an earlier sibling
 * binds. A node whose nested children tie keeps its written order, so most patterns match as
 * written. egg's third order, a node's variables bound before its
 * nested nodes, is not kept: with substitutions that are bound by allocation, it binds for every
 * candidate a nested node then rejects, and costs more than it saves (#74).
 */
public final class Matcher {

  /**
   * The size from which a class's nodes of one node class are found through the class's type
   * index rather than by a walk, for a head that names its type and has no key.
   */
  static final int INDEX_FROM = 8;

  /**
   * The length of a head's run in a class past which a ground nested pattern node is looked up
   * in the hashcons rather than walked, on a graph without pruned nodes.
   */
  static final int LOOKUP_FROM = 8;

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
    ArrayList<Match> matches = new ArrayList<>();
    Scratch scratch = graph.borrowScratch();
    try {
      search(graph, pattern, limit, matches, scratch);
    } finally {
      graph.returnScratch(scratch);
    }
    return matches;
  }

  private static <L extends Language<L>, D extends @Nullable Object>
      void search(EGraph<L, D> graph, Pattern<L> pattern, int limit, ArrayList<Match> matches,
          Scratch scratch) {
    List<Subst> found = scratch.found;
    Walk<L, D> walk = new Walk<>(graph, scratch);
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
   * is kept once. A ground subterm inside a clause that is not ground itself is looked up too,
   * as every ground nested node is.
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
    Scratch scratch = graph.borrowScratch();
    try {
      new Join<>(graph, scratch, multi.clauses(), limit, matches).join(0, Subst.EMPTY, -1);
    } finally {
      graph.returnScratch(scratch);
    }
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
    // Per clause, whether its pattern's substitutions in one class are distinct by construction
    // (matchIn), so that the walk of the class needs no set of its own.
    private final boolean[] distinct;

    Join(EGraph<L, D> graph, Scratch scratch, List<MultiPattern.Clause<L>> clauses, int limit,
        List<Match> out) {
      this.graph = graph;
      this.walk = new Walk<>(graph, scratch);
      this.clauses = clauses;
      this.limit = limit;
      this.out = out;
      this.buffers = scratch.clauseBuffers(clauses.size());
      this.variables = new ArrayList<>(clauses.size());
      this.distinct = new boolean[clauses.size()];
      for (int i = 0; i < clauses.size(); i++) {
        MultiPattern.Clause<L> clause = clauses.get(i);
        variables.add(clause.pattern().subtermVariables());
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
      OptionalInt named = subst.idInterned(clause.var());
      if (clause.pattern() instanceof Pattern.Node<L> node && !node.children().isEmpty()
          && walk.isGround(node, subst)) {
        int found = walk.lookUp(node, subst);
        if (found == EGraph.MISSING) {
          return;
        }
        int id = graph.find(found);
        if (named.isPresent() && graph.find(named.getAsInt()) != id) {
          return;
        }
        join(index + 1, subst.bindInterned(clause.var(), id), index == 0 ? id : first);
        return;
      }
      if (named.isPresent()) {
        joinIn(index, graph.find(named.getAsInt()), subst, first);
        return;
      }
      if (clause.pattern() instanceof Pattern.Var<L>(var name)
          && subst.idInterned(name).isPresent()) {
        joinIn(index, graph.find(subst.idInterned(name).getAsInt()), subst, first);
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
      Subst given = self ? subst.bindInterned(clause.var(), id) : subst;
      List<Subst> found = buffers.get(index);
      found.clear();
      walk.matchIn(clause.pattern(), id, given, Integer.MAX_VALUE, found, distinct[index]);
      for (int i = 0; i < found.size() && out.size() < limit; i++) {
        Subst matched = found.get(i);
        join(index + 1, self ? matched : matched.bindInterned(clause.var(), id), start);
      }
    }

  }

  private static <L extends Language<L>, D extends @Nullable Object>
      void collect(Walk<L, D> walk, Pattern<L> pattern, int id, int limit,
          ArrayList<Match> matches, List<Subst> found, boolean distinct) {
    found.clear();
    walk.matchIn(pattern, id, Subst.EMPTY, limit - matches.size(), found, distinct);
    matches.ensureCapacity(matches.size() + found.size());
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
    new Walk<>(graph, new Scratch()).matchIn(pattern, id, subst, Integer.MAX_VALUE, out,
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
   * The lists searches gather substitutions in, which a graph keeps between searches so that
   * their capacity is not grown again by each one: the walk's pool for nested pattern nodes, the
   * list of one class's substitutions, and a multi-pattern's list per clause. A search borrows
   * them from the graph ({@link EGraph#borrowScratch}) and gives them back emptied; a search that
   * starts while they are lent, from inside another, gets lists of its own. The graph is
   * confined to one thread, so nothing here is shared between threads.
   */
  static final class Scratch {
    final List<List<Subst>> free = new ArrayList<>();
    final List<Subst> found = new ArrayList<>();
    private final List<List<Subst>> clauses = new ArrayList<>();

    /** The first {@code count} clause lists, made as needed, each empty. */
    List<List<Subst>> clauseBuffers(int count) {
      while (clauses.size() < count) {
        clauses.add(new ArrayList<>());
      }
      return clauses.subList(0, count);
    }

    /** Empties every list, so that a scratch kept between searches holds no substitution. */
    void clear() {
      found.clear();
      for (List<Subst> list : clauses) {
        list.clear();
      }
    }
  }

  /**
   * One search's walk of the graph: the graph, and a pool of the lists a nested pattern node's
   * substitutions are gathered in, so that a search allocates per match, not per candidate.
   */
  private static final class Walk<L extends Language<L>, D extends @Nullable Object> {
    private final EGraph<L, D> graph;
    // Whether the graph was rebuilt when the search began: then every node of every class is
    // canonical, so the children the walk reads from a class are roots and need no find. A
    // search between a merge and the rebuild finds each child's root.
    private final boolean rebuilt;
    private final List<List<Subst>> free;
    // Each pattern node met in this search, compiled once ({@link View}), by the node object.
    // Not kept between searches: a view holds the graph's ordinal for its head, which a later
    // add can give a head that has none yet.
    private final Map<Pattern.Node<L>, View<L>> views = new IdentityHashMap<>();

    Walk(EGraph<L, D> graph, Scratch scratch) {
      this.graph = graph;
      this.rebuilt = !graph.isDirty();
      this.free = scratch.free;
    }

    /**
     * A pattern node as the walk reads it: its children as an array, which the hot loops index
     * without a {@code List} call per candidate; its head's key and the graph's ordinal for it;
     * and the order its children are matched in. Made once per search per node.
     *
     * <p>The order is the written one, except that the nested children are matched the one with
     * more variables first and, among equals, the smaller first, as egg's compiler orders a
     * pattern's nodes, except that egg counts the variables not yet bound and this counts all of
     * a child's: a nested node that binds more variables first makes the ones after it ground,
     * to be looked up, or narrower. So {@code (+ (* (field year ?d) 12) (field month ?d))}
     * matches the field first and the product, then ground, is looked up; a node whose nested
     * children tie, {@code (& (-> ?a ?b) (-> (~ ?a) ?c))}, keeps its written order. A variable
     * child keeps its place: binding it ahead of a nested node costs a binding for every
     * candidate the nested node rejects. The order depends on the pattern alone, so the matches
     * stay a function of the graph and the pattern.
     */
    private static final class View<L extends Language<L>> {
      final Pattern.Head<L> head;
      final Pattern<L>[] children;
      final int arity;
      final @Nullable Object key;
      final int[] order;
      // The graph's ordinal of the key, -1 if no node has that head; -2 until asked.
      int ordinal = -2;

      @SuppressWarnings("unchecked")
      View(Pattern.Node<L> node) {
        this.head = node.head();
        this.children = node.children().toArray((Pattern<L>[]) new Pattern<?>[0]);
        this.arity = children.length;
        this.key = head.key().orElse(null);
        this.order = new int[arity];
        List<Integer> nested = new ArrayList<>();
        for (int i = 0; i < arity; i++) {
          order[i] = i;
          if (children[i] instanceof Pattern.Node) {
            nested.add(i);
          }
        }
        if (nested.size() > 1) {
          int[] variables = new int[arity];
          int[] sizes = new int[arity];
          for (int i : nested) {
            variables[i] = children[i].subtermVariables().size();
            sizes[i] = size(children[i]);
          }
          List<Integer> sorted = new ArrayList<>(nested);
          sorted.sort((a, b) -> {
            int byVariables = Integer.compare(variables[b], variables[a]);
            return byVariables != 0 ? byVariables : Integer.compare(sizes[a], sizes[b]);
          });
          for (int k = 0; k < nested.size(); k++) {
            order[nested.get(k)] = sorted.get(k);
          }
        }
      }

      /** The number of nodes and variables in the pattern. */
      private static <L extends Language<L>> int size(Pattern<L> pattern) {
        return switch (pattern) {
          case Pattern.Var<L> v -> 1;
          case Pattern.Node<L>(var head, var children) -> {
            int n = 1;
            for (Pattern<L> child : children) {
              n += size(child);
            }
            yield n;
          }
        };
      }
    }

    private View<L> viewOf(Pattern.Node<L> node) {
      View<L> view = views.get(node);
      if (view == null) {
        view = new View<>(node);
        views.put(node, view);
      }
      return view;
    }

    /** The graph's ordinal of the view's key, or -1 if no node has that head. */
    private int ordinalOf(View<L> view) {
      if (view.ordinal == -2) {
        view.ordinal = graph.headOrdinalOf(view.key);
      }
      return view.ordinal;
    }

    /**
     * Whether {@code pattern} is ground under {@code subst}: every subterm variable bound, every
     * head declaring its payload variables ({@link Pattern.Head#variables}) and each of them
     * bound. A head that does not declare makes the pattern not ground, so it is walked.
     */
    boolean isGround(Pattern<L> pattern, Subst subst) {
      return switch (pattern) {
        case Pattern.Var<L>(var name) -> subst.idOrUnbound(name) != Subst.UNBOUND;
        case Pattern.Node<L>(var head, var children) -> {
          // The children first, by index: an unbound variable, the common answer, is found
          // without an iterator or a look at the head.
          for (int i = 0; i < children.size(); i++) {
            if (!isGround(children.get(i), subst)) {
              yield false;
            }
          }
          Optional<Set<String>> declared = head.variables();
          if (declared.isEmpty()) {
            yield false;
          }
          if (!declared.get().isEmpty()) {
            for (String v : declared.get()) {
              if (!subst.hasPayload(v)) {
                yield false;
              }
            }
          }
          yield true;
        }
      };
    }

    /**
     * The class of the node a ground pattern names, built from the bindings and found through
     * the hashcons and the pruned memory as {@link EGraph#lookup} finds it, or
     * {@link EGraph#MISSING} if the graph has no such node.
     */
    int lookUp(Pattern<L> pattern, Subst subst) {
      return switch (pattern) {
        case Pattern.Var<L>(var name) -> graph.find(subst.idOrUnbound(name));
        case Pattern.Node<L>(var head, var children) -> {
          int[] ids = new int[children.size()];
          for (int i = 0; i < ids.length; i++) {
            int child = lookUp(children.get(i), subst);
            if (child == EGraph.MISSING) {
              yield EGraph.MISSING;
            }
            ids[i] = child;
          }
          yield graph.lookupCanonical(head.build(subst, IntList.wrap(ids)));
        }
      };
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
      matchIn(pattern, id, subst, limit, out, distinct, false);
    }

    /**
     * As {@link #matchIn(Pattern, int, Subst, int, List, boolean)}; {@code nested} says the
     * pattern is a child of another's, which is where a ground node may be looked up rather
     * than walked (the root of a search is always walked, as egg's {@code Bind} walks it).
     */
    void matchIn(Pattern<L> pattern, int id, Subst subst, int limit, List<Subst> out,
        boolean distinct, boolean nested) {
      matchInRoot(pattern, graph.find(id), subst, limit, out, distinct, nested);
    }

    /** As {@link #matchIn(Pattern, int, Subst, int, List, boolean, boolean)} for a root id. */
    private void matchInRoot(Pattern<L> pattern, int root, Subst subst, int limit,
        List<Subst> out, boolean distinct, boolean nested) {
      switch (pattern) {
        case Pattern.Var<L>(var name) -> {
          int bound = subst.idOrUnbound(name);
          if (bound == Subst.UNBOUND) {
            out.add(subst.bindNew(name, root));
          } else if (graph.find(bound) == root) {
            out.add(subst);
          }
        }
        case Pattern.Node<L> patternNode -> {
          Set<Subst> seen = null;
          EClass<L, D> eclass = graph.rootClass(root);
          View<L> view = viewOf(patternNode);
          Pattern.Head<L> head = view.head;
          int arity = view.arity;
          // The candidates: for a head with a key, the run of its ordinal in the class's table,
          // whose heads need no test; for a head that names its node class, the positions of
          // that class's nodes (or every node of a small class); for any other head, every node.
          EClass.Table table = null;
          int lo = 0;
          int hi = 0;
          List<L> nodes = null;
          IntArray positions = null;
          int candidates;
          if (view.key != null) {
            int headId = ordinalOf(view);
            if (headId < 0) {
              return;
            }
            table = eclass.table();
            lo = table.lower(headId);
            hi = table.upper(headId, lo);
            candidates = hi - lo;
          } else {
            nodes = eclass.readNodes();
            Optional<Class<? extends L>> type = head.type();
            if (type.isPresent() && nodes.size() >= INDEX_FROM) {
              positions = eclass.positionsOfType(type.get());
              if (positions == null) {
                return;
              }
              candidates = positions.size();
            } else {
              candidates = nodes.size();
            }
          }
          // egg's Lookup where it pays: a long run of the head, for a ground nested node (in a
          // class that has pruned, matchChildren has looked it up before coming here).
          if (nested && candidates > LOOKUP_FROM && arity > 0 && isGround(pattern, subst)) {
            if (lookUp(pattern, subst) == root) {
              out.add(subst);
            }
            return;
          }
          for (int i = 0; i < candidates && out.size() < limit; i++) {
            int[] kids;
            int start;
            Subst headBound;
            if (table != null) {
              int entry = lo + i;
              if (table.arity(entry) != arity) {
                continue;
              }
              kids = table.kids;
              start = table.starts[entry];
              headBound = subst;
            } else {
              L node = nodes.get(positions == null ? i : positions.get(i));
              IntList nodeKids = node.children();
              if (nodeKids.size() != arity) {
                continue;
              }
              headBound = head.match(node, subst);
              if (headBound == null) {
                continue;
              }
              kids = nodeKids.raw();
              start = 0;
            }
            int before = out.size();
            matchChildren(view, view.order, 0, kids, start, headBound, out, distinct);
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
     * Matches a candidate node's children, {@code kids} from {@code start} on, in the
     * {@code order} given from its {@code k}th entry on, against the child patterns of the view
     * under {@code subst}, appending each complete substitution to {@code out}: a variable child
     * binds or agrees in place; a node child that is ground is looked up where that alone gives
     * egg's answer (a child class that has pruned) and otherwise matched in the child's class,
     * where a long run is looked up too ({@link #matchIn}); each of its substitutions continues
     * to the next child in the order.
     */
    void matchChildren(View<L> view, int[] order, int k, int[] kids, int start, Subst subst,
        List<Subst> out, boolean distinct) {
      if (k == order.length) {
        out.add(subst);
        return;
      }
      int i = order[k];
      int child = kids[start + i];
      switch (view.children[i]) {
        case Pattern.Var<L>(var name) -> {
          int bound = subst.idOrUnbound(name);
          int root = rebuilt ? child : graph.find(child);
          if (bound == Subst.UNBOUND) {
            matchChildren(view, order, k + 1, kids, start, subst.bindNew(name, root), out,
                distinct);
          } else if (graph.find(bound) == root) {
            matchChildren(view, order, k + 1, kids, start, subst, out, distinct);
          }
        }
        case Pattern.Node<L> nested -> {
          // In a class that has pruned, a ground nested node is looked up without entering it:
          // the class's list no longer holds a dropped node and the hashcons still does. A class
          // that never pruned, or was never joined by one, lists every node it holds.
          int childRoot = rebuilt ? child : graph.find(child);
          if (graph.anyPruned() && graph.rootClass(childRoot).hasPruned()
              && !nested.children().isEmpty()
              && isGround(nested, subst)) {
            if (lookUp(nested, subst) == childRoot) {
              matchChildren(view, order, k + 1, kids, start, subst, out, distinct);
            }
            return;
          }
          // The nested node's substitutions go into a list from the pool, read by index while
          // the children after it fill lists of their own, and returned once read.
          List<Subst> partial = free.isEmpty() ? new ArrayList<>() : free.remove(free.size() - 1);
          matchInRoot(nested, childRoot, subst, Integer.MAX_VALUE, partial, distinct, true);
          for (int j = 0; j < partial.size(); j++) {
            matchChildren(view, order, k + 1, kids, start, partial.get(j), out, distinct);
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
        if (head instanceof PureHead<L>) {
          // The node such a head builds is a function of the children, which are roots here: the
          // class add found for it before is the class it finds now, so add is not asked again.
          int known = graph.recall(head, ids);
          if (known != EGraph.MISSING) {
            return known;
          }
          int id = graph.add(head.build(subst, IntList.wrap(ids)));
          graph.remember(head, ids, id);
          return id;
        }
        return graph.add(head.build(subst, IntList.wrap(ids)));
      }
    }
  }
}
