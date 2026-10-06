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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import org.jspecify.annotations.Nullable;

/**
 * An e-graph: a union-find over e-class ids, a hashcons from canonical e-nodes to the class each
 * is in, and the classes themselves - egg's {@code EGraph}, the paper's Figure 4, with the
 * data structures in plain Java.
 *
 * <p>{@link #add} canonicalises a node and looks it up in the hashcons; {@link #merge} unions two
 * classes and does nothing else but note the class on a worklist; {@link #rebuild} works the list
 * off, repairing the hashcons and merging the parents a merge made congruent, until the list is
 * empty. Keeping the repair out of {@code merge} is the paper's whole point (its section 3): a
 * batch of merges is repaired once, and a class merged many times is repaired once. Between a
 * merge and the next rebuild the graph is not congruent, and {@link #lookup} may miss a node
 * whose children were merged. The {@link Analysis}'s facts are kept in the same rebuild: a
 * class's fact is made when its first node is added, joined when classes merge, re-made for
 * parents whose children's facts grew, and the analysis's modify hook runs on every class whose
 * fact changed (paper section 4.1).
 *
 * <p><b>Order is fixed.</b> Ids are assigned by insertion order and a class's nodes and parents
 * are kept in insertion order, so everything the graph exposes is a function of the sequence of
 * calls that built it; the hashcons is a {@link HashMap}, but nothing iterates over it.
 *
 * <p><b>Threads.</b> An e-graph is confined to one thread at a time: nothing in it is
 * synchronized, so another thread may use it only after a handover that makes its writes visible
 * (a {@code Future}, an executor's {@code submit}, a lock, a volatile write and read), as for any
 * object that is not thread-safe. This holds for the {@link Runner} and the
 * {@link Extractor} built over it and for a {@link BackoffScheduler}, which holds one run's bans.
 * Separate e-graphs share no state, so one per thread needs no coordination. The language's
 * nodes must be immutable, as they are hashcons keys; a client's {@link Analysis},
 * {@link Condition}, {@link Applier} and {@link CostFunction} are called from the thread that
 * calls the graph, and need to be thread-safe only if one instance is given to graphs that run
 * at the same time.
 *
 * @param <L> the language
 * @param <D> the analysis fact, {@code Void} under {@link Analysis#none}
 */
public final class EGraph<L extends Language<L>, D extends @Nullable Object> {

  private final Analysis<L, D> analysis;
  private final UnionFind unionFind = new UnionFind();
  private final Map<L, Integer> hashcons = new HashMap<>();
  // Nodes retainNodes dropped from their class, with the class: egg leaves such entries in its
  // memo, so adding a dropped node again finds its class and changes nothing, which is what
  // lets a run whose rules keep re-making folded forms saturate. Kept apart from the hashcons
  // so that it still holds exactly the graph's nodes.
  private final Map<L, Integer> pruned = new HashMap<>();
  // What a lookup answers for a node the graph does not hold; ids are never negative.
  private static final int MISSING = -1;
  // Indexed by id; an entry is null once its id is no longer a root.
  private final List<EClass<L, D>> classes = new ArrayList<>();
  // How many entries of classes are not null, kept by add and merge so counting is free.
  private int liveClasses;
  // How many times the graph changed: a class made by add, two roots joined by merge. The
  // runner reads it to tell an iteration that changed nothing, which sizes cannot: an add and
  // a merge in one iteration leave them as they were.
  private long changes;
  // Roots of classes a merge touched since the last rebuild; repaired in id order.
  private final IntArray worklist = new IntArray();
  // The classes named by the parent entries this rebuild's repairs processed: the only classes
  // whose node lists can hold a form a merge made stale, re-canonicalised when the rebuild ends.
  private final BitSet staleOwners = new BitSet();
  // The classes holding a node of each node class (operator), a bit per class id: where a search
  // for a head that names its node class starts (Pattern.Head.type). Exact: set at add, moved
  // at merge, cleared by retainNodes when a class's last node of the class goes; checkInvariants
  // checks it both ways. Insertion-ordered, since iterating it reaches ids.
  private final Map<Class<?>, BitSet> byNodeClass = new LinkedHashMap<>();
  // The unions asked for through classesHolding for a type that is not one node class, kept
  // current on set and recomputed after a clear or a new node class.
  private final Map<Class<?>, BitSet> unions = new LinkedHashMap<>();
  private boolean unionsStale;
  private int parentSerial;
  // Parent entries whose class's fact may have grown because a child's fact did (paper
  // section 4.1, egg's analysis_pending): each is re-made and joined into its class's fact.
  private final List<EClass.Parent<L>> analysisPending = new ArrayList<>();
  // Roots whose fact changed in a merge, owed a call of the analysis's modify hook.
  private final IntArray modifyPending = new IntArray();

  /**
   * An empty graph whose class facts are kept by {@code analysis}.
   *
   * @param analysis the analysis that makes, joins and modifies facts; not null
   */
  public EGraph(Analysis<L, D> analysis) {
    this.analysis = Objects.requireNonNull(analysis, "analysis");
  }

  /**
   * A graph with no analysis.
   *
   * @param <L> the language
   * @return a new empty graph whose class facts are all {@code null}
   */
  public static <L extends Language<L>> EGraph<L, Void> withoutAnalysis() {
    return new EGraph<L, Void>(Analysis.<L>none());
  }

  /**
   * The analysis this graph keeps facts with.
   *
   * @return the analysis given at construction
   */
  public Analysis<L, D> analysis() {
    return analysis;
  }

  /**
   * The root id of {@code id}'s class.
   *
   * @param id an e-class id this graph issued, root or not
   * @return the canonical id of that class
   */
  public int find(int id) {
    return unionFind.find(id);
  }

  /**
   * {@code node} with every child replaced by its class's root.
   *
   * @param node an e-node whose children are ids this graph issued
   * @return the canonical form of {@code node}; {@code node} itself if its children are roots
   */
  public L canonicalize(L node) {
    IntList children = node.children();
    IntList canonical = children.map(unionFind::find);
    return canonical == children ? node : node.withChildren(canonical);
  }

  /**
   * The class {@code node} is in, if it is in the graph; {@code node} need not be canonical.
   * Reads the hashcons and changes nothing.
   *
   * @param node an e-node whose children are ids this graph issued
   * @return the root id of the node's class, or empty if the graph holds no such node; a node
   *     {@link #retainNodes} dropped from its class is still found, with that class
   */
  public OptionalInt lookup(L node) {
    int id = idOf(canonicalize(node));
    return id == MISSING ? OptionalInt.empty() : OptionalInt.of(id);
  }

  /** The class of a canonical node, from the hashcons or the pruned memory, or MISSING. */
  private int idOf(L canonical) {
    Integer id = hashcons.get(canonical);
    if (id == null) {
      id = pruned.get(canonical);
    }
    return id == null ? MISSING : unionFind.find(id);
  }

  /**
   * Adds {@code node} and returns its class: the class already holding an equal canonical node,
   * or a new class of this node alone, recorded as a parent of each child's class (paper
   * Figure 4, lines 1-11). The children must be ids this graph issued.
   *
   * @param node the e-node to add; not necessarily canonical
   * @return the canonical id of the class now holding the node
   */
  public int add(L node) {
    L canonical = canonicalize(node);
    int existing = idOf(canonical);
    if (existing != MISSING) {
      return existing;
    }
    int id = unionFind.makeSet();
    EClass<L, D> eclass = new EClass<>(id, null);
    classes.add(eclass);
    liveClasses++;
    changes++;
    eclass.addNode(canonical);
    index(canonical, id);
    IntList children = canonical.children();
    // One entry, shared by every child's list, so that a repair through any child re-keys the
    // form all of them and the hashcons hold.
    EClass.Parent<L> entry = new EClass.Parent<>(canonical, id, parentSerial++);
    for (int i = 0; i < children.size(); i++) {
      classOf(children.get(i)).addParent(entry);
    }
    hashcons.put(canonical, id);
    eclass.setData(analysis.make(this, canonical));
    analysis.modify(this, id);
    return unionFind.find(id);
  }

  /**
   * Adds a client's tree bottom-up through {@code bridge} and returns the root's class. A
   * subtree object reached twice (a DAG) is added once; equal subtrees reached through different
   * objects hashcons to the same class anyway. The walk recurses once per level of the tree, so
   * its depth is bounded by the thread's stack, and a client structure with a cycle is not a
   * tree: the walk would not end.
   *
   * <p>"Reached twice" is by object identity. A client whose tree type is a value class has no
   * identity to share by: the walk then adds a shared subtree once per path to it, still
   * correct, but in time proportional to the tree, not the DAG. Such a client shares subterms
   * through class ids, adding each once and building the parents over the ids.
   *
   * @param <T> the client's tree type
   * @param root the root of the client's tree
   * @param bridge how to read a tree's subtrees and build its e-node over their class ids
   * @return the canonical id of the class holding the whole tree
   */
  public <T> int addTree(T root, TreeBridge<T, L> bridge) {
    return walk(root, bridge, new IdentityHashMap<>(), this::add);
  }

  /**
   * The class a client's tree is in, if the graph holds it, through {@code bridge}, adding
   * nothing: a subtree missing anywhere means the whole is missing. As in {@link #addTree}, a
   * subtree object reached twice is looked up once. Like {@link #lookup} it reads the hashcons,
   * so after a merge it is exact only once the graph is rebuilt: before, a tree whose children
   * were merged can read as missing. The walk's depth is bounded as {@link #addTree}'s is.
   * Shares subtree objects by identity as {@link #addTree} does, with the same caveat for a
   * value-class tree type.
   *
   * @param <T> the client's tree type
   * @param root the root of the client's tree
   * @param bridge how to read a tree's subtrees and build its e-node over their class ids
   * @return the canonical id of the class holding the whole tree, or empty if any subtree is
   *     missing; as for {@link #lookup}, a node {@link #retainNodes} dropped still counts
   */
  public <T> OptionalInt lookupTree(T root, TreeBridge<T, L> bridge) {
    int id = walk(root, bridge, new IdentityHashMap<>(), node -> idOf(canonicalize(node)));
    return id == MISSING ? OptionalInt.empty() : OptionalInt.of(id);
  }

  /**
   * The one walk of a client's tree behind {@link #addTree} and {@link #lookupTree}: bottom-up,
   * each subtree object visited once ({@code seen} is by identity), and {@code step} applied to
   * the node built over the children's classes. A step answering {@link #MISSING} ends the whole
   * walk with it, so a miss needs no memo: nothing reads one before the walk returns.
   */
  private <T> int walk(T tree, TreeBridge<T, L> bridge, Map<T, Integer> seen,
      ToIntFunction<L> step) {
    Integer done = seen.get(tree);
    if (done != null) {
      return unionFind.find(done);
    }
    List<T> subtrees = bridge.childrenOf(tree);
    int[] ids = new int[subtrees.size()];
    for (int i = 0; i < ids.length; i++) {
      ids[i] = walk(subtrees.get(i), bridge, seen, step);
      if (ids[i] == MISSING) {
        return MISSING;
      }
    }
    int id = step.applyAsInt(bridge.node(tree, IntList.wrap(ids)));
    if (id != MISSING) {
      seen.put(tree, id);
    }
    return id;
  }

  /**
   * The class with this root id.
   *
   * @param id an e-class id this graph issued; a non-root is resolved to its root
   * @return the live class holding {@code id}, not a copy
   */
  public EClass<L, D> classOf(int id) {
    EClass<L, D> eclass = classes.get(unionFind.find(id));
    if (eclass == null) {
      throw new IllegalStateException("no class is rooted at " + id);
    }
    return eclass;
  }

  /**
   * The analysis fact of {@code id}'s class.
   *
   * @param id an e-class id this graph issued, root or not
   * @return the class's fact; {@code null} under {@link Analysis#none}
   */
  public D data(int id) {
    return classOf(id).data();
  }

  /**
   * The live classes, in id order, read-only.
   *
   * @return a fresh unmodifiable list, a snapshot: later merges do not change it
   */
  public List<EClass<L, D>> classes() {
    List<EClass<L, D>> live = new ArrayList<>();
    for (EClass<L, D> eclass : classes) {
      if (eclass != null) {
        live.add(eclass);
      }
    }
    return Collections.unmodifiableList(live);
  }

  /** One past the largest id issued: the bound for an array indexed by class id. */
  int idBound() {
    return classes.size();
  }

  /** The smallest live class id at or after {@code from}, or -1. */
  int nextLiveClass(int from) {
    for (int id = from; id < classes.size(); id++) {
      if (classes.get(id) != null) {
        return id;
      }
    }
    return -1;
  }

  /**
   * The live classes holding a node of {@code type}: the set for that node class, or the union of
   * the sets for the node classes assignable to it, kept for the next call. Not to be modified,
   * and read before the graph changes: the sets are live.
   */
  BitSet classesHolding(Class<?> type) {
    BitSet exact = byNodeClass.get(type);
    if (exact != null) {
      return exact;
    }
    if (unionsStale) {
      for (Map.Entry<Class<?>, BitSet> e : unions.entrySet()) {
        e.setValue(unionFor(e.getKey()));
      }
      unionsStale = false;
    }
    return unions.computeIfAbsent(type, this::unionFor);
  }

  private BitSet unionFor(Class<?> type) {
    BitSet union = new BitSet();
    for (Map.Entry<Class<?>, BitSet> e : byNodeClass.entrySet()) {
      if (type.isAssignableFrom(e.getKey())) {
        union.or(e.getValue());
      }
    }
    return union;
  }

  private void index(L node, int id) {
    Class<?> type = node.getClass();
    BitSet holding = byNodeClass.get(type);
    if (holding == null) {
      holding = new BitSet();
      byNodeClass.put(type, holding);
      unionsStale = true;
    }
    holding.set(id);
    if (!unionsStale) {
      for (Map.Entry<Class<?>, BitSet> e : unions.entrySet()) {
        if (e.getKey().isAssignableFrom(type)) {
          e.getValue().set(id);
        }
      }
    }
  }

  private void unindex(Class<?> type, int id) {
    BitSet holding = byNodeClass.get(type);
    if (holding != null && holding.get(id)) {
      holding.clear(id);
      unionsStale = true;
    }
  }

  /**
   * How many classes are live.
   *
   * @return the number of classes that are roots of the union-find
   */
  public int numClasses() {
    return liveClasses;
  }

  /**
   * How many times the graph has changed: a class made by {@link #add}, two roots joined by
   * {@link #merge}, including the merges a {@link #rebuild} makes, or nodes dropped by
   * {@link #retainNodes}. Two readings that agree mean nothing happened in between; sizes cannot
   * say that, since an add and a merge cancel out.
   *
   * @return a count that never decreases
   */
  public long changes() {
    return changes;
  }

  /**
   * How many distinct canonical e-nodes the graph holds, once rebuilt.
   *
   * @return the number of entries in the hashcons
   */
  public int numNodes() {
    return hashcons.size();
  }

  /**
   * Whether a merge since the last {@link #rebuild} has left work to do.
   *
   * @return true if the graph needs a {@link #rebuild} to be congruent again
   */
  public boolean isDirty() {
    return !worklist.isEmpty() || !analysisPending.isEmpty() || !modifyPending.isEmpty();
  }

  /**
   * Makes {@code a} and {@code b} one class and returns its root (paper Figure 4, lines 12-22).
   * The surviving root is the smaller id. The other class's nodes and parents move to it and its
   * facts are joined; the root goes on the worklist and nothing is repaired until
   * {@link #rebuild}. Returns the root even when the two were one class already.
   *
   * @param a an e-class id this graph issued, root or not
   * @param b an e-class id this graph issued, root or not
   * @return the canonical id of the joined class, the smaller of the two roots
   */
  public int merge(int a, int b) {
    int ra = unionFind.find(a);
    int rb = unionFind.find(b);
    if (ra == rb) {
      return ra;
    }
    // The joined fact first, before anything is changed: an analysis whose join refuses the
    // merge (two constants in one class) must leave the graph as it was, not with the two
    // classes joined in the union-find and nowhere else. A join is symmetric, so it needs no
    // knowledge of which root the union-find will keep. This holds for a merge called here; a
    // join refusing inside rebuild, whose repairs merge congruent classes, leaves the rebuild
    // half done, as egg's panic would - a fact an analysis must not let two forms disagree on.
    D joined = analysis.join(classes.get(ra).data(), classes.get(rb).data());
    int root = unionFind.union(ra, rb);
    int other = root == ra ? rb : ra;
    EClass<L, D> kept = classes.get(root);
    EClass<L, D> gone = classes.get(other);
    changes++;
    // Where the joined fact grew past what a side had, that side's parents are re-made, since
    // their facts were made from the smaller one (paper Figure 9).
    if (!Objects.equals(joined, kept.data())) {
      analysisPending.addAll(kept.mutableParents());
    }
    if (!Objects.equals(joined, gone.data())) {
      analysisPending.addAll(gone.mutableParents());
    }
    for (L node : gone.readNodes()) {
      index(node, root);
      unindex(node.getClass(), other);
    }
    kept.mutableNodes().addAll(gone.readNodes());
    kept.setMergedNodes(true);
    kept.mutableParents().addAll(gone.mutableParents());
    kept.setData(joined);
    if (gone.hasPruned()) {
      kept.markPruned();
    }
    classes.set(other, null);
    liveClasses--;
    worklist.add(root);
    modifyPending.add(root);
    return root;
  }

  /**
   * Keeps the nodes of {@code eclass}'s class that {@code keep} accepts and drops the others:
   * egg's pruning, which its {@code math} suite's constant folding does in {@code modify} to
   * leave a folded class with its constant alone, so no rule matches the folded forms again. A
   * dropped node leaves the hashcons, so that still holds exactly the graph's nodes, but is
   * remembered with its class - {@link #add} and {@link #lookup} of it find the class and change
   * nothing, as egg's leftover memo entry does, which lets a run whose rules keep re-making the
   * folded forms saturate - and it stays in its children's parent lists, as egg's does: a later
   * merge of a child can make it congruent with a live node, and {@link #rebuild} then unions
   * the two classes. A class cannot be emptied. Returns how many nodes were dropped.
   *
   * @param eclass an e-class id this graph issued, root or not
   * @param keep the test for a node to keep; at least one node of the class must pass it
   * @return the number of nodes removed from the class, 0 if every node passed
   */
  public int retainNodes(int eclass, Predicate<L> keep) {
    EClass<L, D> c = classOf(eclass);
    List<L> nodes = c.mutableNodes();
    if (nodes.stream().noneMatch(keep)) {
      throw new IllegalArgumentException("retainNodes would empty class " + c.id());
    }
    List<L> dropped = new ArrayList<>();
    nodes.removeIf(node -> !keep.test(node) && dropped.add(node));
    if (dropped.isEmpty()) {
      return 0;
    }
    c.markPruned();
    for (L node : dropped) {
      // Inside a rebuild a listed node may be stale while the hashcons holds its canonical form,
      // so both are removed; the canonical form is remembered, and repair keeps it current.
      L canonical = canonicalize(node);
      hashcons.remove(node);
      hashcons.remove(canonical);
      pruned.put(canonical, c.id());
      // The class leaves the index for a node class none of its nodes has any more, so searches
      // for that operator stop looking at it.
      Class<?> type = node.getClass();
      if (nodes.stream().noneMatch(n -> n.getClass() == type)) {
        unindex(type, c.id());
      }
    }
    changes++;
    return dropped.size();
  }

  /**
   * Restores the hashcons and congruence invariants after any number of merges (paper Figure 4,
   * lines 27-53): takes the worklist, deduplicates it by root, repairs each class, and repeats
   * while repairs merged more classes. Returns how many classes were repaired. The order is fixed:
   * each pass repairs its roots in ascending id order.
   *
   * @return the number of class repairs done, 0 if the graph was already clean
   */
  public int rebuild() {
    int repaired = 0;
    boolean touched = false;
    while (isDirty()) {
      touched = true;
      while (!worklist.isEmpty()) {
        int[] todo = worklist.sortedDistinct(unionFind::find);
        worklist.clear();
        for (int id : todo) {
          if (classes.get(id) != null) {
            repair(classes.get(id));
            repaired++;
          }
        }
      }
      // The facts: a parent whose child's fact grew is re-made and joined into its class; a
      // class whose fact grew enqueues its own parents and is offered to modify, which may add
      // nodes or merge (constant folding does both), feeding the union worklist again.
      while (!analysisPending.isEmpty() || !modifyPending.isEmpty()) {
        List<EClass.Parent<L>> pending = new ArrayList<>(analysisPending);
        analysisPending.clear();
        for (EClass.Parent<L> entry : pending) {
          int id = unionFind.find(entry.classId());
          EClass<L, D> eclass = classes.get(id);
          D made = analysis.make(this, canonicalize(entry.node()));
          D joined = analysis.join(eclass.data(), made);
          if (!Objects.equals(joined, eclass.data())) {
            eclass.setData(joined);
            analysisPending.addAll(eclass.mutableParents());
            modifyPending.add(id);
          }
        }
        int[] toModify = modifyPending.sortedDistinct(unionFind::find);
        modifyPending.clear();
        for (int id : toModify) {
          if (classes.get(id) != null) {
            analysis.modify(this, id);
          }
        }
        if (!worklist.isEmpty()) {
          break;
        }
      }
    }
    if (touched) {
      // The hashcons is repaired through the parent lists, but a node sits in its own class's
      // list too, with children that may have been merged since. A form goes stale only when a
      // child is merged away, and that child's entries were all repaired, so the classes those
      // entries name are the only ones that can hold a stale form: they alone are
      // re-canonicalised and deduplicated, egg's rebuild_classes narrowed to the work done.
      BitSet done = new BitSet();
      for (int id = staleOwners.nextSetBit(0); id >= 0; id = staleOwners.nextSetBit(id + 1)) {
        int root = unionFind.find(id);
        if (!done.get(root)) {
          done.set(root);
          canonicalizeNodes(classes.get(root));
        }
      }
      staleOwners.clear();
      // Every hashcons key is the form of the entry it came from, and repair re-keys the entry
      // in place, so the hashcons holds exactly the graph's nodes with nothing to sweep. The
      // pruned memory can hold a form retainNodes recorded between a merge and this rebuild,
      // which no entry carries: swept, over the pruned entries alone.
      if (!pruned.isEmpty()) {
        pruned.keySet().removeIf(node -> !isCanonical(node));
      }
    }
    return repaired;
  }

  /**
   * Puts the class's nodes in canonical form and drops the duplicates: a node is remade only
   * when a child is no longer a root, and the list is deduplicated only when a node was remade
   * or another class's nodes were merged in since the list was last canonical.
   */
  private void canonicalizeNodes(EClass<L, D> eclass) {
    // Read until a node is remade: a class whose list stays as it was keeps its indexes.
    List<L> nodes = eclass.readNodes();
    boolean changed = eclass.hasMergedNodes();
    eclass.setMergedNodes(false);
    for (int i = 0; i < nodes.size(); i++) {
      L node = nodes.get(i);
      if (!isCanonical(node)) {
        eclass.dropIndexes();
        nodes.set(i, canonicalize(node));
        changed = true;
      }
    }
    if (changed && nodes.size() > 1) {
      LinkedHashSet<L> distinct = LinkedHashSet.newLinkedHashSet(nodes.size());
      distinct.addAll(nodes);
      if (distinct.size() < nodes.size()) {
        nodes.clear();
        nodes.addAll(distinct);
      }
    }
  }

  /** Whether every child of {@code node} is a root: canonical, told without making the form. */
  private boolean isCanonical(L node) {
    IntList children = node.children();
    for (int i = 0; i < children.size(); i++) {
      if (unionFind.find(children.get(i)) != children.get(i)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether a parent entry is a node retainNodes dropped from its class: the class has pruned,
   * and does not list the node. The entry's form is no guide - an entry can carry a form older
   * than any the prune saw - so the class's list is read, which is short where pruning happens.
   */
  private boolean isPruned(L canonical, int root) {
    EClass<L, D> c = classes.get(root);
    if (!c.hasPruned()) {
      return false;
    }
    for (L n : c.readNodes()) {
      if (canonicalize(n).equals(canonical)) {
        return false;
      }
    }
    return true;
  }

  /**
   * One class's repair (paper Figure 4, lines 36-53): each parent is taken out of the hashcons
   * and put back canonical under its class's root, and two parents that became the same
   * canonical node are merged, which puts their root on the worklist for the next pass.
   */
  private void repair(EClass<L, D> eclass) {
    canonicalizeNodes(eclass);
    // The parent list is taken out of the class before the loop, as egg's is: a merge below may
    // append to the class's list (when it is the root the merge keeps) or merge the class away
    // into a smaller root (when the cascade reaches it), and in both cases the repaired entries
    // go to whichever class is the root when the loop ends, after anything appended meanwhile.
    List<EClass.Parent<L>> parents = new ArrayList<>(eclass.mutableParents());
    eclass.mutableParents().clear();
    // What the nodes held aside were made from: if a merge below changes this class's fact, they
    // are re-made, since merge, which re-makes a class's parents when its fact grows, finds this
    // list empty (paper Figure 9 does the re-make inside the repair for the same reason).
    D factBefore = eclass.data();
    for (EClass.Parent<L> parent : parents) {
      L canonical = canonicalize(parent.node());
      int root = unionFind.find(parent.classId());
      staleOwners.set(root);
      if (isPruned(canonical, root)) {
        // A node dropped from its class by retainNodes: re-keyed where it is remembered, so
        // that adding it again still finds the class, and never back into the hashcons. The key
        // the entry still has there goes: retainNodes removes the two forms it knows (the one the
        // class listed and the canonical one), and an entry keyed under an older form, from
        // before a child's class was merged away, kept its key past the prune. It goes only if
        // it names this class; one that names another is a live node's.
        Integer keyed = hashcons.get(parent.node());
        if (keyed != null && unionFind.find(keyed) == root) {
          hashcons.remove(parent.node());
        }
        pruned.remove(parent.node());
        pruned.put(canonical, root);
      } else {
        hashcons.remove(parent.node());
        hashcons.put(canonical, root);
        // A live node that takes a pruned node's form stands for it from now on.
        pruned.remove(canonical);
      }
      // The entry is the one in the node's other children's lists too: all of them now name
      // the form the hashcons holds.
      parent.rekey(canonical, root);
    }
    // Two entries that are one canonical node now are congruent, and their classes are merged.
    // Of the two, the older stays in the list and the newer leaves it: the older is in every
    // list that names the node (an entry is only ever dropped in favour of an older one), so a
    // repair through any child finds it, and when the newer was keyed above under a form of its
    // own, that key goes with it, since any other entry under that form is congruent, hence in
    // this list, hence dropped too. A merge here can change a later entry's form, so from the
    // first merge on the form is made again; an entry whose form changed keeps the one it was
    // keyed under, and the next pass, which the merge puts the root on the worklist for, re-keys
    // it. Insertion order is kept, so the merges and the list come out in the order met.
    LinkedHashMap<L, EClass.Parent<L>> seen = LinkedHashMap.newLinkedHashMap(parents.size());
    boolean merged = false;
    for (EClass.Parent<L> parent : parents) {
      L canonical = merged ? canonicalize(parent.node()) : parent.node();
      EClass.Parent<L> same = seen.putIfAbsent(canonical, parent);
      if (same == null || same == parent) {
        continue;
      }
      int root = merge(same.classId(), unionFind.find(parent.classId()));
      merged = true;
      EClass.Parent<L> kept = parent.serial() < same.serial() ? parent : same;
      EClass.Parent<L> dropped = kept == parent ? same : parent;
      kept.rekey(kept.node(), root);
      if (kept != same) {
        seen.put(canonical, kept);
      }
      if (!dropped.node().equals(kept.node())) {
        hashcons.remove(dropped.node());
        pruned.remove(dropped.node());
      }
    }
    EClass<L, D> root = classOf(eclass.id());
    root.mutableParents().addAll(seen.values());
    if (merged && !Objects.equals(root.data(), factBefore)) {
      analysisPending.addAll(seen.values());
    }
  }

  /**
   * Checks the analysis invariant (paper section 4.1): every class's fact equals the join of
   * {@code make} over its nodes. Holds after {@link #rebuild} for an analysis whose {@code join}
   * is a semilattice join and whose {@code modify} is idempotent. Throws
   * {@link IllegalStateException} naming the first class that breaks it.
   */
  public void checkAnalysisInvariant() {
    for (EClass<L, D> eclass : classes()) {
      D expected = null;
      boolean first = true;
      for (L node : eclass.nodes()) {
        D made = analysis.make(this, node);
        expected = first ? made : analysis.join(expected, made);
        first = false;
      }
      if (!Objects.equals(expected, eclass.data())) {
        throw new IllegalStateException("class " + eclass.id() + " holds " + eclass.data()
            + " where the join of its nodes' facts is " + expected);
      }
    }
  }

  /**
   * Checks the hashcons invariant (paper Definition 2.7) and the congruence invariant (its
   * Theorem 3.1): every node of every live class is canonical and maps in the hashcons to that
   * class, every hashcons entry's node is in the class it maps to, and no two classes hold an
   * equal canonical node. Also the parent entries: for every node some one entry is in every
   * list that names the node, in canonical form and mapped by the hashcons or the pruned memory
   * to its class, so that a repair through any child re-keys the form all of them hold; and no
   * entry left behind under an older form is a key. Also the matcher's indexes: a class's built
   * index agrees with its node list. All hold after {@link #rebuild}; none need hold between a
   * merge and the rebuild. For tests and debugging; throws
   * {@link IllegalStateException} naming the first violation.
   */
  public void checkInvariants() {
    int counted = 0;
    Map<L, Integer> owner = new HashMap<>();
    for (EClass<L, D> eclass : classes()) {
      for (L node : eclass.nodes()) {
        counted++;
        Integer elsewhere = owner.put(node, eclass.id());
        if (elsewhere != null) {
          throw new IllegalStateException("node " + node + " is in classes " + elsewhere
              + " and " + eclass.id());
        }
        if (!node.equals(canonicalize(node))) {
          throw new IllegalStateException("class " + eclass.id() + " holds a non-canonical node "
              + node);
        }
        Integer mapped = hashcons.get(node);
        if (mapped == null || unionFind.find(mapped) != eclass.id()) {
          throw new IllegalStateException("node " + node + " of class " + eclass.id()
              + " maps to " + (mapped == null ? "nothing" : unionFind.find(mapped)));
        }
      }
    }
    if (counted != hashcons.size()) {
      StringBuilder extra = new StringBuilder();
      hashcons.forEach((node, id) -> {
        if (!owner.containsKey(node)) {
          extra.append(' ').append(node).append("->").append(unionFind.find(id));
        }
      });
      throw new IllegalStateException(counted + " nodes in classes, " + hashcons.size()
          + " in the hashcons; not in any class:" + extra);
    }
    if (classes().size() != liveClasses) {
      throw new IllegalStateException(classes().size() + " live classes, " + liveClasses
          + " counted");
    }
    checkParentEntries();
    checkIndex();
  }

  private void checkIndex() {
    for (EClass<L, D> eclass : classes()) {
      eclass.checkIndexes();
      for (L node : eclass.nodes()) {
        BitSet holding = byNodeClass.get(node.getClass());
        if (holding == null || !holding.get(eclass.id())) {
          throw new IllegalStateException("class " + eclass.id() + " holds " + node
              + " but is not indexed under " + node.getClass().getSimpleName());
        }
      }
    }
    for (Map.Entry<Class<?>, BitSet> e : byNodeClass.entrySet()) {
      BitSet holding = e.getValue();
      for (int id = holding.nextSetBit(0); id >= 0; id = holding.nextSetBit(id + 1)) {
        EClass<L, D> eclass = id < classes.size() ? classes.get(id) : null;
        if (eclass == null || eclass.nodes().stream().noneMatch(n -> n.getClass() == e.getKey())) {
          throw new IllegalStateException("class " + id + " is indexed under "
              + e.getKey().getSimpleName() + " but " + (eclass == null ? "is not live"
              : "holds no such node"));
        }
      }
    }
  }

  private void checkParentEntries() {
    // Per node (canonical form): the lists naming it through any entry, and its entry objects
    // with the lists holding each (by identity; one object listed twice in a list is one entry).
    Map<L, Set<Integer>> listsOfNode = new HashMap<>();
    Map<EClass.Parent<L>, Set<Integer>> listsOfEntry = new IdentityHashMap<>();
    Map<L, List<EClass.Parent<L>>> entriesOfNode = new HashMap<>();
    for (EClass<L, D> eclass : classes()) {
      for (EClass.Parent<L> entry : eclass.parents()) {
        L node = entry.node();
        L canonical = canonicalize(node);
        if (!node.equals(canonical) && (hashcons.containsKey(node) || pruned.containsKey(node))) {
          throw new IllegalStateException("class " + eclass.id() + " has a parent entry " + node
              + " that is not canonical yet is a key");
        }
        listsOfNode.computeIfAbsent(canonical, n -> new HashSet<>()).add(eclass.id());
        Set<Integer> lists = listsOfEntry.computeIfAbsent(entry, e -> new HashSet<>());
        if (lists.isEmpty()) {
          entriesOfNode.computeIfAbsent(canonical, n -> new ArrayList<>()).add(entry);
        }
        lists.add(eclass.id());
      }
    }
    for (var e : entriesOfNode.entrySet()) {
      L canonical = e.getKey();
      int lists = Objects.requireNonNull(listsOfNode.get(canonical)).size();
      int mapped = idOf(canonical);
      boolean shared = e.getValue().stream().anyMatch(entry ->
          Objects.requireNonNull(listsOfEntry.get(entry)).size() == lists
              && entry.node().equals(canonical)
              && unionFind.find(entry.classId()) == mapped);
      if (!shared) {
        throw new IllegalStateException("no entry for " + canonical + " is in all " + lists
            + " lists naming it in canonical form and mapped to its class "
            + (mapped == MISSING ? "(none)" : String.valueOf(mapped)) + "; " + e.getValue().size()
            + " entries: " + e.getValue());
      }
    }
  }
}
