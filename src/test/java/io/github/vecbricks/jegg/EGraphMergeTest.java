/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class EGraphMergeTest {

  @Test
  void mergingChildrenMergesTheParentsOnRebuildAndNotBefore() {
    // f(a) and f(b) with a = b: congruence says f(a) = f(b), which the rebuild establishes.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int fa = g.add(new Toy.Add(IntList.of(a, a)));
    int fb = g.add(new Toy.Add(IntList.of(b, b)));
    assertNotEquals(fa, fb);
    assertEquals(a, g.merge(a, b));
    assertTrue(g.isDirty());
    assertEquals(a, g.find(b));
    assertNotEquals(g.find(fa), g.find(fb));
    assertTrue(g.rebuild() >= 2, "the class of a and then the class of f(a) are repaired");
    assertFalse(g.isDirty());
    assertEquals(0, g.rebuild());
    assertEquals(g.find(fa), g.find(fb));
    assertEquals(2, g.numClasses());
    // The nodes: a, b (both in the class of a) and one f.
    assertEquals(3, g.numNodes());
    g.checkInvariants();
    // Merging what is already one class is a no-op that leaves nothing to rebuild.
    assertEquals(g.find(fa), g.merge(fa, fb));
    assertFalse(g.isDirty());
  }

  @Test
  void aRepairCascadesUpTheParents() {
    // h(g(a)) and h(g(b)): one merge at the leaves, two levels of parents to repair.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int one = g.add(new Toy.Num(1));
    int ga = g.add(new Toy.Add(IntList.of(a, one)));
    int gb = g.add(new Toy.Add(IntList.of(b, one)));
    int ha = g.add(new Toy.Mul(IntList.of(ga, ga)));
    int hb = g.add(new Toy.Mul(IntList.of(gb, gb)));
    g.merge(a, b);
    g.rebuild();
    assertEquals(g.find(ga), g.find(gb));
    assertEquals(g.find(ha), g.find(hb));
    assertEquals(4, g.numClasses());
    g.checkInvariants();
    // The surviving ids are the smaller ones.
    assertEquals(a, g.find(b));
    assertEquals(ga, g.find(gb));
    assertEquals(ha, g.find(hb));
  }

  @Test
  void deferredAndEagerRebuildingGiveTheSameGraph() {
    // The paper's section 3.4 check: a random sequence of adds and merges, repaired after every
    // merge and repaired once at the end, gives the same e-graph. Not the same ids: in the
    // deferred run an add between a merge and its rebuild can miss a congruent node still under
    // its stale key and take a fresh id, which the rebuild then merges away, so the two runs are
    // compared id-free, by the classes' shapes to a depth of three. The sequence is seeded, so a
    // failure replays.
    for (long seed = 1; seed <= 8; seed++) {
      EGraph<Toy, Void> eager = run(seed, true);
      EGraph<Toy, Void> deferred = run(seed, false);
      assertEquals(eager.numClasses(), deferred.numClasses(), "classes, seed " + seed);
      assertEquals(eager.numNodes(), deferred.numNodes(), "nodes, seed " + seed);
      assertEquals(shape(eager), shape(deferred), "seed " + seed);
    }
  }

  /**
   * An id-free rendering of the graph: every class as the sorted set of its nodes with each
   * child written as its class's rendering one level down, three levels deep, and the classes'
   * renderings sorted. Two graphs with the same classes render the same whatever their ids.
   */
  private static List<String> shape(EGraph<Toy, Void> g) {
    java.util.Map<Integer, String> labels = new java.util.HashMap<>();
    for (EClass<Toy, Void> c : g.classes()) {
      labels.put(c.id(), "_");
    }
    for (int depth = 0; depth < 3; depth++) {
      java.util.Map<Integer, String> next = new java.util.HashMap<>();
      for (EClass<Toy, Void> c : g.classes()) {
        TreeSet<String> nodes = new TreeSet<>();
        for (Toy n : c.nodes()) {
          StringBuilder b = new StringBuilder(n.head().toString()).append('(');
          IntList kids = n.children();
          for (int i = 0; i < kids.size(); i++) {
            b.append(labels.get(g.find(kids.get(i)))).append(',');
          }
          nodes.add(b.append(')').toString());
        }
        next.put(c.id(), nodes.toString());
      }
      labels = next;
    }
    List<String> out = new ArrayList<>(labels.values());
    java.util.Collections.sort(out);
    return out;
  }

  private static EGraph<Toy, Void> run(long seed, boolean eager) {
    Random rnd = new Random(seed);
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    List<Integer> ids = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      ids.add(g.add(new Toy.Var("v" + i)));
    }
    for (int step = 0; step < 200; step++) {
      int kind = rnd.nextInt(10);
      if (kind < 7) {
        int x = ids.get(rnd.nextInt(ids.size()));
        int y = ids.get(rnd.nextInt(ids.size()));
        Toy node = rnd.nextBoolean() ? new Toy.Add(IntList.of(x, y))
            : new Toy.Mul(IntList.of(x, y));
        ids.add(g.add(node));
      } else {
        g.merge(ids.get(rnd.nextInt(ids.size())), ids.get(rnd.nextInt(ids.size())));
        if (eager) {
          g.rebuild();
        }
      }
    }
    g.rebuild();
    g.checkInvariants();
    return g;
  }

  @Test
  void aJoinThatRefusesTheMergeLeavesTheGraphAsItWas() {
    // Constant folding refuses two constants in one class; the merge must then have changed
    // nothing: not the union-find, not the classes, not the counts.
    EGraph<Toy, Long> g = new EGraph<>(ConstantFoldTest.FOLD);
    int one = g.add(new Toy.Num(1));
    int two = g.add(new Toy.Num(2));
    long changes = g.changes();
    assertThrows(IllegalStateException.class, () -> g.merge(one, two));
    assertEquals(2, g.numClasses());
    assertEquals(one, g.find(one));
    assertEquals(two, g.find(two));
    assertEquals(changes, g.changes());
    assertFalse(g.isDirty());
    g.checkInvariants();
    g.checkAnalysisInvariant();
  }

  @Test
  void anIdTheGraphNeverIssuedIsRefused() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    assertThrows(IllegalArgumentException.class, () -> g.find(a + 5));
    assertThrows(IllegalArgumentException.class, () -> g.find(-1));
    assertThrows(IllegalArgumentException.class, () -> g.add(new Toy.Add(IntList.of(a, 7))));
    assertThrows(IllegalArgumentException.class, () -> g.merge(a, 9));
    g.checkInvariants();
  }

  @Test
  void pruningAClassKeepsTheInvariantsAndRefusesToEmptyIt() {
    // 1 + 2 folds to 3: the class holds Num 3 and Add(1, 2). Pruning to the leaves drops the
    // sum from the class and the hashcons, but remembers it: adding it again finds the class and
    // changes nothing, and it stays a parent of 1 and 2 for congruence, as egg's pruning leaves
    // it in the memo and the parent lists.
    EGraph<Toy, Long> g = new EGraph<>(ConstantFoldTest.FOLD);
    int one = g.add(new Toy.Num(1));
    int two = g.add(new Toy.Num(2));
    int sum = g.add(new Toy.Add(IntList.of(one, two)));
    g.rebuild();
    assertEquals(2, g.classOf(sum).nodes().size());
    assertEquals(1, g.retainNodes(sum, node -> node.children().isEmpty()));
    assertEquals(List.of(new Toy.Num(3)), g.classOf(sum).nodes());
    assertEquals(OptionalInt.of(g.find(sum)), g.lookup(new Toy.Add(IntList.of(one, two))),
        "a dropped node is still found, in its class, as egg's memo finds it");
    assertTrue(g.classOf(one).parents().stream().anyMatch(p -> p.node() instanceof Toy.Add),
        "the dropped node stays a parent of its children, as egg's does, for congruence");
    g.checkInvariants();
    g.checkAnalysisInvariant();
    assertEquals(0, g.retainNodes(sum, node -> true));
    assertThrows(IllegalArgumentException.class, () -> g.retainNodes(sum, node -> false));
    long changes = g.changes();
    int again = g.add(new Toy.Add(IntList.of(one, two)));
    assertEquals(g.find(sum), g.find(again));
    assertEquals(changes, g.changes(), "adding a dropped node again changes nothing");
    assertEquals(List.of(new Toy.Num(3)), g.classOf(sum).nodes());
    g.checkInvariants();
  }

  @Test
  void aPrunedNodeStillMakesItsClassCongruentWhenAChildIsMerged() {
    // 1 + 2 folds to 3 and is pruned from its class; a + b is a separate class. Merging a with 1
    // and b with 2 makes a + b congruent with the pruned 1 + 2: the rebuild must union the two
    // classes, as egg's does through the memo entry it leaves behind.
    EGraph<Toy, Long> g = new EGraph<>(ConstantFoldTest.FOLD);
    int one = g.add(new Toy.Num(1));
    int two = g.add(new Toy.Num(2));
    int sum = g.add(new Toy.Add(IntList.of(one, two)));
    g.rebuild();
    g.retainNodes(sum, node -> node.children().isEmpty());
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int ab = g.add(new Toy.Add(IntList.of(a, b)));
    g.merge(a, one);
    g.merge(b, two);
    g.rebuild();
    assertEquals(g.find(sum), g.find(ab), "a + b is 1 + 2, which is 3");
    assertEquals(3L, g.data(ab));
    g.checkInvariants();
    g.checkAnalysisInvariant();
  }

  /** ConstantFoldTest's analysis, pruning a folded class to its constant as egg's math does. */
  static final Analysis<Toy, Long> PRUNING_FOLD = new Analysis<>() {
    @Override
    public Long make(EGraph<Toy, Long> g, Toy node) {
      return ConstantFoldTest.FOLD.make(g, node);
    }

    @Override
    public Long join(Long a, Long b) {
      return ConstantFoldTest.FOLD.join(a, b);
    }

    @Override
    public void modify(EGraph<Toy, Long> g, int id) {
      Long value = g.data(id);
      if (value != null) {
        int root = g.merge(id, g.add(new Toy.Num(value)));
        g.retainNodes(root, node -> node.children().isEmpty());
      }
    }
  };

  @Test
  void aParentEntryOlderThanThePruneIsStillKnownAsPruned() {
    // op = a + b. a is merged with z, so z's parent entry for op is re-keyed, while b's still
    // reads a + b as added. Then a is merged with 1 and op folds and is pruned. Then b is merged
    // with y: the repair of y's class meets b's old entry, whose form no prune ever saw. It must
    // still know op as pruned and not put it back into the hashcons.
    EGraph<Toy, Long> g = new EGraph<>(PRUNING_FOLD);
    int y = g.add(new Toy.Var("y"));
    int z = g.add(new Toy.Var("z"));
    int one = g.add(new Toy.Num(1));
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Num(5));
    int op = g.add(new Toy.Add(IntList.of(a, b)));
    g.merge(a, z);
    g.rebuild();
    g.merge(a, one);
    g.rebuild();
    assertEquals(List.of(new Toy.Num(6)), g.classOf(op).nodes(), "folded and pruned");
    g.merge(b, y);
    g.rebuild();
    g.checkInvariants();
    g.checkAnalysisInvariant();
    assertEquals(List.of(new Toy.Num(6)), g.classOf(op).nodes());
    assertEquals(g.find(op), g.lookup(new Toy.Add(IntList.of(a, b))).getAsInt());
  }
}
