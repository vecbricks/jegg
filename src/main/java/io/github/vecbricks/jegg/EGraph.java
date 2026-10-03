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
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

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
 * whose children were merged.
 *
 * <p><b>Order is fixed.</b> Ids are assigned by insertion order and a class's nodes and parents
 * are kept in insertion order, so everything the graph exposes is a function of the sequence of
 * calls that built it; the hashcons is a {@link HashMap}, but nothing iterates over it.
 *
 * @param <L> the language
 * @param <D> the analysis fact, {@code Void} under {@link Analysis#none}
 */
public final class EGraph<L extends Language<L>, D> {

  private final Analysis<L, D> analysis;
  private final UnionFind unionFind = new UnionFind();
  private final Map<L, Integer> hashcons = new HashMap<>();
  // Indexed by id; an entry is null once its id is no longer a root.
  private final List<EClass<L, D>> classes = new ArrayList<>();
  // Roots of classes a merge touched since the last rebuild; repaired in id order.
  private final List<Integer> worklist = new ArrayList<>();

  public EGraph(Analysis<L, D> analysis) {
    this.analysis = Objects.requireNonNull(analysis, "analysis");
  }

  /** A graph with no analysis. */
  public static <L extends Language<L>> EGraph<L, Void> withoutAnalysis() {
    return new EGraph<L, Void>(Analysis.<L>none());
  }

  public Analysis<L, D> analysis() {
    return analysis;
  }

  /** The root id of {@code id}'s class. */
  public int find(int id) {
    return unionFind.find(id);
  }

  /** {@code node} with every child replaced by its class's root. */
  public L canonicalize(L node) {
    return node.withChildren(node.children().map(unionFind::find));
  }

  /**
   * The class {@code node} is in, if it is in the graph; {@code node} need not be canonical.
   * Reads the hashcons and changes nothing.
   */
  public OptionalInt lookup(L node) {
    Integer id = hashcons.get(canonicalize(node));
    return id == null ? OptionalInt.empty() : OptionalInt.of(unionFind.find(id));
  }

  /**
   * Adds {@code node} and returns its class: the class already holding an equal canonical node,
   * or a new class of this node alone, recorded as a parent of each child's class (paper
   * Figure 4, lines 1-11). The children must be ids this graph issued.
   */
  public int add(L node) {
    L canonical = canonicalize(node);
    Integer existing = hashcons.get(canonical);
    if (existing != null) {
      return unionFind.find(existing);
    }
    int id = unionFind.makeSet();
    EClass<L, D> eclass = new EClass<>(id, null);
    classes.add(eclass);
    eclass.addNode(canonical);
    IntList children = canonical.children();
    for (int i = 0; i < children.size(); i++) {
      classOf(children.get(i)).addParent(canonical, id);
    }
    hashcons.put(canonical, id);
    eclass.setData(analysis.make(this, canonical));
    analysis.modify(this, id);
    return unionFind.find(id);
  }

  /**
   * Adds a client's tree bottom-up through {@code bridge} and returns the root's class. A
   * subtree object reached twice (a DAG) is added once; equal subtrees reached through different
   * objects hashcons to the same class anyway.
   */
  public <T> int addTree(T root, TreeBridge<T, L> bridge) {
    return addTree(root, bridge, new IdentityHashMap<>());
  }

  private <T> int addTree(T tree, TreeBridge<T, L> bridge, Map<T, Integer> seen) {
    Integer done = seen.get(tree);
    if (done != null) {
      return unionFind.find(done);
    }
    List<T> subtrees = bridge.childrenOf(tree);
    int[] ids = new int[subtrees.size()];
    for (int i = 0; i < ids.length; i++) {
      ids[i] = addTree(subtrees.get(i), bridge, seen);
    }
    int id = add(bridge.node(tree, IntList.of(ids)));
    seen.put(tree, id);
    return id;
  }

  /** The class with this root id. */
  public EClass<L, D> classOf(int id) {
    EClass<L, D> eclass = classes.get(unionFind.find(id));
    if (eclass == null) {
      throw new IllegalStateException("no class is rooted at " + id);
    }
    return eclass;
  }

  /** The analysis fact of {@code id}'s class. */
  public D data(int id) {
    return classOf(id).data();
  }

  /** The live classes, in id order, read-only. */
  public List<EClass<L, D>> classes() {
    List<EClass<L, D>> live = new ArrayList<>();
    for (EClass<L, D> eclass : classes) {
      if (eclass != null) {
        live.add(eclass);
      }
    }
    return Collections.unmodifiableList(live);
  }

  /** How many classes are live. */
  public int numClasses() {
    int n = 0;
    for (EClass<L, D> eclass : classes) {
      if (eclass != null) {
        n++;
      }
    }
    return n;
  }

  /** How many distinct canonical e-nodes the graph holds, once rebuilt. */
  public int numNodes() {
    return hashcons.size();
  }

  /** Whether a merge since the last {@link #rebuild} has left work to do. */
  public boolean isDirty() {
    return !worklist.isEmpty();
  }

  /**
   * Makes {@code a} and {@code b} one class and returns its root (paper Figure 4, lines 12-22).
   * The surviving root is the smaller id. The other class's nodes and parents move to it and its
   * facts are joined; the root goes on the worklist and nothing is repaired until
   * {@link #rebuild}. Returns the root even when the two were one class already.
   */
  public int merge(int a, int b) {
    int ra = unionFind.find(a);
    int rb = unionFind.find(b);
    if (ra == rb) {
      return ra;
    }
    int root = unionFind.union(ra, rb);
    int other = root == ra ? rb : ra;
    EClass<L, D> kept = classes.get(root);
    EClass<L, D> gone = classes.get(other);
    kept.mutableNodes().addAll(gone.mutableNodes());
    kept.mutableParents().addAll(gone.mutableParents());
    kept.setData(analysis.join(kept.data(), gone.data()));
    classes.set(other, null);
    worklist.add(root);
    return root;
  }

  /**
   * Restores the hashcons and congruence invariants after any number of merges (paper Figure 4,
   * lines 27-53): takes the worklist, deduplicates it by root, repairs each class, and repeats
   * while repairs merged more classes. Returns how many classes were repaired. The order is fixed:
   * each pass repairs its roots in ascending id order.
   */
  public int rebuild() {
    int repaired = 0;
    while (!worklist.isEmpty()) {
      int[] todo = worklist.stream().mapToInt(unionFind::find).distinct().sorted().toArray();
      worklist.clear();
      for (int id : todo) {
        if (classes.get(id) != null) {
          repair(classes.get(id));
          repaired++;
        }
      }
    }
    if (repaired > 0) {
      // The hashcons is repaired through the parent lists, but a node sits in its own class's
      // list too, with children that may have been merged since: every class's nodes are
      // re-canonicalised and deduplicated once per rebuild, as egg's rebuild_classes does.
      for (EClass<L, D> eclass : classes) {
        if (eclass != null) {
          canonicalizeNodes(eclass);
        }
      }
      // A repair re-keys a parent under its canonical form and records that form in the repaired
      // class's parent list only; the node's other child classes keep the form they were given
      // at insertion. So a later merge through one of those can leave the re-keyed entry behind
      // with no list naming it. Such a key is non-canonical and no lookup can reach it (lookups
      // canonicalise first), so egg leaves it as garbage; here it is swept, so that the hashcons
      // holds exactly the graph's nodes and its size is their count.
      hashcons.keySet().removeIf(node -> !node.equals(canonicalize(node)));
    }
    return repaired;
  }

  private void canonicalizeNodes(EClass<L, D> eclass) {
    List<L> nodes = eclass.mutableNodes();
    List<L> canonicalNodes = new ArrayList<>(nodes.size());
    for (L node : nodes) {
      L canonical = canonicalize(node);
      if (!canonicalNodes.contains(canonical)) {
        canonicalNodes.add(canonical);
      }
    }
    nodes.clear();
    nodes.addAll(canonicalNodes);
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
    List<EClass.Parent<L>> seen = new ArrayList<>(parents.size());
    for (EClass.Parent<L> parent : parents) {
      hashcons.remove(parent.node());
      L canonical = canonicalize(parent.node());
      hashcons.put(canonical, unionFind.find(parent.classId()));
    }
    for (EClass.Parent<L> parent : parents) {
      L canonical = canonicalize(parent.node());
      int classId = unionFind.find(parent.classId());
      EClass.Parent<L> same = null;
      for (EClass.Parent<L> s : seen) {
        if (s.node().equals(canonical)) {
          same = s;
          break;
        }
      }
      if (same != null) {
        int merged = merge(same.classId(), classId);
        seen.set(seen.indexOf(same), new EClass.Parent<>(canonical, merged));
      } else {
        seen.add(new EClass.Parent<>(canonical, classId));
      }
    }
    classOf(eclass.id()).mutableParents().addAll(seen);
  }

  /**
   * Checks the hashcons invariant (paper Definition 2.7) and the congruence invariant (its
   * Theorem 3.1): every node of every live class is canonical and maps in the hashcons to that
   * class, every hashcons entry's node is in the class it maps to, and no two classes hold an
   * equal canonical node. Both hold after {@link #rebuild}; the second may not between a merge
   * and the rebuild. For tests and debugging; throws {@link IllegalStateException} naming the
   * first violation.
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
  }
}
