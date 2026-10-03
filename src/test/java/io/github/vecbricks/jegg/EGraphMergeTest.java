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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
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

}
