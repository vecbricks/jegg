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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** What a rebuild costs and what it leaves: the work is the repairs', not the graph's. */
class EGraphRebuildTest {

  /** A language whose node equality is counted, for how much a repair compares. */
  sealed interface Counted extends Language<Counted> permits Counted.Leaf, Counted.Op {
    AtomicLong EQUALS = new AtomicLong();

    record Leaf(int name) implements Counted {
      @Override
      public IntList children() {
        return IntList.EMPTY;
      }

      @Override
      public Counted withChildren(IntList children) {
        return this;
      }
    }

    /** Not a record, so that its equals is this one. */
    final class Op implements Counted {
      private final int tag;
      private final IntList children;

      Op(int tag, IntList children) {
        this.tag = tag;
        this.children = children;
      }

      @Override
      public IntList children() {
        return children;
      }

      @Override
      public Counted withChildren(IntList children) {
        return new Op(tag, children);
      }

      @Override
      public boolean equals(Object o) {
        EQUALS.incrementAndGet();
        return o instanceof Op p && tag == p.tag && children.equals(p.children);
      }

      @Override
      public int hashCode() {
        return 31 * tag + children.hashCode();
      }

      @Override
      public String toString() {
        return "op" + tag + children;
      }
    }
  }

  @Test
  void onlyTheAbsorbedSidesParentsAreRepairedAndEachAboutOnce() {
    // x has n distinct parents and y has m. Merging them keeps x, the smaller id, and queues y's
    // parents alone: x's keep their children and so their keys. Each queued parent is re-keyed
    // by one hash put; a repair that looked for congruent pairs by scanning what it had seen
    // compared m^2/2 pairs.
    EGraph<Counted, Void> g = EGraph.withoutAnalysis();
    int x = g.add(new Counted.Leaf(0));
    int y = g.add(new Counted.Leaf(1));
    int n = 3000;
    int m = 300;
    for (int i = 0; i < n; i++) {
      g.add(new Counted.Op(i, IntList.of(x)));
    }
    for (int i = 0; i < m; i++) {
      g.add(new Counted.Op(n + i, IntList.of(y)));
    }
    g.merge(x, y);
    Counted.EQUALS.set(0);
    assertEquals(m, g.rebuild(), "y's parents are repaired, x's are not visited");
    long calls = Counted.EQUALS.get();
    assertTrue(calls < 50L * m, "a repair of " + m + " parents called equals " + calls + " times");
    g.checkInvariants();
    assertEquals(n + m + 1, g.numClasses());
    // The parents all on the kept side: nothing to repair.
    EGraph<Counted, Void> h = EGraph.withoutAnalysis();
    int hx = h.add(new Counted.Leaf(0));
    int hy = h.add(new Counted.Leaf(1));
    for (int i = 0; i < n; i++) {
      h.add(new Counted.Op(i, IntList.of(hx)));
    }
    h.merge(hx, hy);
    assertEquals(0, h.rebuild());
    h.checkInvariants();
    assertEquals(n + 1, h.numClasses());
  }

  @Test
  void anEntrySeenThroughTheOtherChildNamesTheFormTheHashconsHolds() {
    // op = a + b. a is merged into a2 (the smaller id survives), so a's entry for op is queued
    // and re-keyed. b's list was not visited, but its entry is the same one, and reads the new
    // form too; a later repair through b then removes the key the hashcons really holds.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a2 = g.add(new Toy.Var("a2"));
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int op = g.add(new Toy.Add(IntList.of(a, b)));
    assertEquals(a2, g.merge(a, a2));
    g.rebuild();
    Toy reKeyed = new Toy.Add(IntList.of(a2, b));
    assertEquals(List.of(new EClass.Parent<>(reKeyed, op)), g.classOf(a2).parents());
    assertEquals(List.of(new EClass.Parent<>(reKeyed, op)), g.classOf(b).parents(),
        "the entry in b's list is the one a2's repair re-keyed");
    EClass.Parent<Toy> entry = g.classOf(b).parents().get(0);
    assertEquals(new EClass.Parent<>(reKeyed, op).hashCode(), entry.hashCode());
    assertEquals("Parent[node=" + reKeyed + ", classId=" + op + "]", entry.toString());
    assertNotEquals(entry, reKeyed, "an entry is not its node");
    g.checkInvariants();
  }

  @Test
  void reKeyingThroughOneChildThenMergingThroughTheOtherLeavesNoKeyBehind() {
    // Three forms of one node in turn: a + b as added, a2 + b after the first rebuild, a2 + b2
    // after the second. Each rebuild must leave the hashcons with the current form alone.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a2 = g.add(new Toy.Var("a2"));
    int b2 = g.add(new Toy.Var("b2"));
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int op = g.add(new Toy.Add(IntList.of(a, b)));
    g.merge(a, a2);
    g.rebuild();
    g.checkInvariants();
    g.merge(b, b2);
    g.rebuild();
    g.checkInvariants();
    assertEquals(3, g.numClasses());
    assertEquals(5, g.numNodes(), "four variables and one sum; the old forms are no nodes");
    assertEquals(OptionalInt.of(op), g.lookup(new Toy.Add(IntList.of(a, b))));
    assertEquals(op, g.add(new Toy.Add(IntList.of(a2, b2))));
    assertEquals(5, g.numNodes());
  }

  @Test
  void congruentEntriesKeyedUnderTwoFormsLeaveOneKey() {
    // c's entry for c + s is queued when c is merged into b, and re-keyed under b + s finds the
    // class of b + s, v0's: b's class merges into v0's, which queues b's parents, b * v0 and
    // b * b, keyed a moment earlier under those forms. Both are re-keyed under v0 * v0; the
    // second finds the first's class, the two product classes merge, and the hashcons holds
    // the one key. b was b * b's only child, so a key left under that form would stay for good.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int v0 = g.add(new Toy.Var("v0"));
    int s = g.add(new Toy.Var("s"));
    int b = g.add(new Toy.Var("b"));
    int c = g.add(new Toy.Var("c"));
    int bs = g.add(new Toy.Add(IntList.of(b, s)));
    int cs = g.add(new Toy.Add(IntList.of(c, s)));
    g.merge(bs, v0);
    g.merge(cs, b);
    g.merge(c, b);
    int bv0 = g.add(new Toy.Mul(IntList.of(b, v0)));
    int bb = g.add(new Toy.Mul(IntList.of(b, b)));
    g.rebuild();
    g.checkInvariants();
    assertEquals(v0, g.find(b));
    assertEquals(g.find(bv0), g.find(bb));
    assertEquals(3, g.numClasses(), "v0 with b, c and the sums; s; the product");
    assertEquals(6, g.numNodes(), "four variables, one sum, one product");
    assertEquals(OptionalInt.of(g.find(bb)), g.lookup(new Toy.Mul(IntList.of(b, b))));
  }

  @Test
  void congruentEntriesBothStayAndAreReKeyedThroughEitherChild() {
    // p4 = x2 + y was added before p3 = x + y, so y's list reads [p4, p3] and, once x2 is merged
    // into x, x's list reads [p3, p4]. The two are congruent then and both stay in both lists,
    // as egg's do; every later merge through x or through y queues both, each is re-keyed under
    // the form of the moment, and the second finds the first's class. No key stays behind.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int c = g.add(new Toy.Var("c"));
    int x = g.add(new Toy.Var("x"));
    int x2 = g.add(new Toy.Var("x2"));
    int y = g.add(new Toy.Var("y"));
    int p4 = g.add(new Toy.Add(IntList.of(x2, y)));
    int p3 = g.add(new Toy.Add(IntList.of(x, y)));
    g.merge(x, x2);
    g.rebuild();
    g.checkInvariants();
    assertEquals(g.find(p3), g.find(p4));
    g.merge(y, c);
    g.rebuild();
    g.checkInvariants();
    g.merge(x, b);
    g.rebuild();
    g.checkInvariants();
    g.merge(c, a);
    g.rebuild();
    g.checkInvariants();
    assertEquals(7, g.numNodes(), "six variables and one sum");
    assertEquals(OptionalInt.of(g.find(p3)), g.lookup(new Toy.Add(IntList.of(b, a))));
  }

  @Test
  void aPruneBetweenAMergeAndTheRebuildIsRememberedUnderTheFinalForm() {
    // op = a + b is merged with 6 and then, after a is merged with z but before the rebuild,
    // pruned to the constant: the prune records a + b under its form of that moment, z + b. Then
    // b is merged with w too. The rebuild must remember the node under z + w, so that adding the
    // sum again finds the class and adds nothing, whatever form the prune saw.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int z = g.add(new Toy.Var("z"));
    int w = g.add(new Toy.Var("w"));
    int six = g.add(new Toy.Num(6));
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int op = g.add(new Toy.Add(IntList.of(a, b)));
    g.merge(op, six);
    g.merge(a, z);
    assertEquals(1, g.retainNodes(g.find(op), node -> node instanceof Toy.Num));
    g.merge(b, w);
    g.rebuild();
    g.checkInvariants();
    assertEquals(List.of(new Toy.Num(6)), g.classOf(six).nodes());
    assertEquals(OptionalInt.of(six), g.lookup(new Toy.Add(IntList.of(a, b))));
    assertEquals(5, g.numNodes(), "z, a, w, b and 6");
    assertEquals(six, g.add(new Toy.Add(IntList.of(z, w))));
    assertEquals(5, g.numNodes(), "the pruned sum is remembered, not added");
    g.checkInvariants();
  }

  @Test
  void aMergeInsideARepairThatGrowsTheRepairedClassesFactRemakesItsParents() {
    // A congruence merge inside the rebuild that joins a class with one whose fact is bigger
    // (here a class known true) must re-make the facts of the class's parents, or they stay
    // stale. The repair once took the class's list out and had to re-make it itself; now the
    // list stays in the class and merge's own re-make sees it. Found by fuzzing prop against
    // egg (#44).
    EGraph<PropRulesTest.Prop, Boolean> g = new EGraph<>(PropRulesTest.CONSTANT_FOLD);
    int t = g.add(new PropRulesTest.Prop.Bool(true));
    int e = g.add(new PropRulesTest.Prop.Sym("e"));
    int e2 = g.add(new PropRulesTest.Prop.Sym("e2"));
    // E holds And[E, T], and T holds And[E2, T]: the two become congruent when E and E2 merge.
    g.merge(g.add(new PropRulesTest.Prop.And(IntList.of(e, t))), e);
    g.merge(g.add(new PropRulesTest.Prop.And(IntList.of(e2, t))), t);
    // Q holds Or[E, E2], a parent of both that the repair of E holds aside.
    int q = g.add(new PropRulesTest.Prop.Or(IntList.of(e, e2)));
    g.rebuild();
    g.checkAnalysisInvariant();
    assertEquals(null, g.data(q));
    g.merge(e, e2);
    g.rebuild();
    g.checkInvariants();
    g.checkAnalysisInvariant();
    assertEquals(true, g.data(q));
    // modify then merges the constant class in, as it does for any class known true.
    assertEquals(g.find(t), g.find(q));
  }

  @Test
  void aNodePrunedByModifyLeavesNoStaleKeyWhenItsChildrenMergeInTheSameRebuild() {
    // The effects of math's sub-canon and pow0 on (pow 1 (- 0 (pow z 0))): (- 0 P) gains
    // (+ 0 (* -1 P)), and P = (pow z 0) is merged with 1. Rebuilding folds (* -1 P) to -1, whose
    // modify prunes the class to its constants, while the class of P is merged into 1's: the
    // pruned node's key is left in the hashcons under the child ids it was added with. Found by
    // fuzzing math against egg (#44), where jegg counted a node more than the graph holds.
    EGraph<MathTest.Math, Double> g = new EGraph<>(MathTest.CONSTANT_FOLD);
    int z = g.add(new MathTest.Math.Symbol("z"));
    int zero = g.add(new MathTest.Math.Constant(0));
    int one = g.add(new MathTest.Math.Constant(1));
    int p = g.add(new MathTest.Math.Pow(IntList.of(z, zero)));
    int sub = g.add(new MathTest.Math.Sub(IntList.of(zero, p)));
    g.add(new MathTest.Math.Pow(IntList.of(one, sub)));
    g.rebuild();
    int minusOne = g.add(new MathTest.Math.Constant(-1));
    int times = g.add(new MathTest.Math.Mul(IntList.of(minusOne, p)));
    int plus = g.add(new MathTest.Math.Add(IntList.of(zero, times)));
    g.merge(sub, plus);
    g.merge(p, one);
    g.rebuild();
    g.checkInvariants();
    g.checkAnalysisInvariant();
    assertEquals(5, g.numClasses());
    assertEquals(5, g.numNodes());
  }

  @Test
  void theCongruentPartnerMayBeAnEntryTheRebuildNeverVisits() {
    // x + z was added before y + z. Merging y into x queues y + z alone, and its re-key under
    // x + z finds the class of x + z, whose entry sits in x's list unvisited: congruence through
    // the hashcons, as egg's process_unions finds it. The entry of y + z is dropped, and the
    // lists read one entry for the sum.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int x = g.add(new Toy.Var("x"));
    int y = g.add(new Toy.Var("y"));
    int z = g.add(new Toy.Var("z"));
    int xz = g.add(new Toy.Add(IntList.of(x, z)));
    int yz = g.add(new Toy.Add(IntList.of(y, z)));
    assertEquals(x, g.merge(x, y));
    assertEquals(1, g.rebuild(), "y + z alone is repaired");
    g.checkInvariants();
    assertEquals(xz, g.find(yz));
    assertEquals(3, g.numClasses());
    assertEquals(4, g.numNodes(), "x, y, z and one sum");
    Toy sum = new Toy.Add(IntList.of(x, z));
    assertEquals(List.of(new EClass.Parent<>(sum, xz)), g.classOf(z).parents());
    assertEquals(List.of(new EClass.Parent<>(sum, xz)), g.classOf(x).parents());
    assertSame(g.classOf(z).parents().get(0), g.classOf(x).parents().get(0),
        "the one entry left is the same object in both lists");
  }

  @Test
  void aDroppedEntryIsNotQueuedAgainAndLeavesTheListsAMergeBuilds() {
    // After x + z and y + z are one node (above), merging z into w queues z's list: the live
    // entry alone is repaired, the dropped one is skipped and is not carried into w's list.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int w = g.add(new Toy.Var("w"));
    int x = g.add(new Toy.Var("x"));
    int y = g.add(new Toy.Var("y"));
    int z = g.add(new Toy.Var("z"));
    int xz = g.add(new Toy.Add(IntList.of(x, z)));
    g.add(new Toy.Add(IntList.of(y, z)));
    g.merge(x, y);
    assertEquals(1, g.rebuild());
    g.merge(z, w);
    assertEquals(1, g.rebuild(), "the dropped entry is skipped, not repaired");
    g.checkInvariants();
    Toy sum = new Toy.Add(IntList.of(x, w));
    assertEquals(List.of(new EClass.Parent<>(sum, xz)), g.classOf(w).parents());
    assertEquals(1, g.classOf(w).mutableParents().size(), "w's list was built without it");
    assertEquals(5, g.numNodes());
    assertEquals(OptionalInt.of(xz), g.lookup(new Toy.Add(IntList.of(y, z))));
  }

  @Test
  void anEntryQueuedTwiceIsReKeyedOnceAndFoundOnceMore() {
    // op = a + b, with a and b both absorbed in one batch, into a0 and b0: the one entry is
    // queued from a's list and from b's. The first visit re-keys it under a0 + b0; the second
    // finds its form current and its key its own.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a0 = g.add(new Toy.Var("a0"));
    int b0 = g.add(new Toy.Var("b0"));
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int op = g.add(new Toy.Add(IntList.of(a, b)));
    g.merge(a, a0);
    g.merge(b, b0);
    assertEquals(2, g.rebuild());
    g.checkInvariants();
    assertEquals(List.of(new Toy.Add(IntList.of(a0, b0))), g.classOf(op).nodes());
    assertEquals(OptionalInt.of(op), g.lookup(new Toy.Add(IntList.of(a, b))));
    assertEquals(5, g.numNodes());
  }

  @Test
  void anAddUnderAStaleKeyBetweenAMergeAndTheRebuildIsMergedIn() {
    // n = a + b is keyed under a's id when a is merged into a0; adding a0 + b then misses it and
    // makes a class of its own. The rebuild re-keys n under a0 + b, finds the new class there
    // and merges the two; the joined class lists the node once.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a0 = g.add(new Toy.Var("a0"));
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int n = g.add(new Toy.Add(IntList.of(a, b)));
    g.merge(a, a0);
    int again = g.add(new Toy.Add(IntList.of(a0, b)));
    assertNotEquals(n, again);
    assertEquals(1, g.rebuild());
    g.checkInvariants();
    assertEquals(n, g.find(again));
    assertEquals(List.of(new Toy.Add(IntList.of(a0, b))), g.classOf(n).nodes());
    assertEquals(4, g.numNodes());
  }

  @Test
  void aPrunedNodeAndALiveNodeMadeCongruentMergeWhicheverWasFirst() {
    // The pruned entry's turn finds the live class. First: six holds 6 and a + b; a is merged
    // into a0, a0 + b is added (a class of its own, the key being a + b still), and then the
    // class is pruned to 6: the prune must not take the live node's key for the pruned node's.
    // The rebuild re-keys the pruned entry in the memory, finds a0 + b live, and merges the two.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a0 = g.add(new Toy.Var("a0"));
    int b = g.add(new Toy.Var("b"));
    int six = g.add(new Toy.Num(6));
    int a = g.add(new Toy.Var("a"));
    int op = g.add(new Toy.Add(IntList.of(a, b)));
    g.merge(op, six);
    g.merge(a, a0);
    int live = g.add(new Toy.Add(IntList.of(a0, b)));
    assertNotEquals(six, g.find(live));
    assertEquals(1, g.retainNodes(six, node -> node instanceof Toy.Num));
    g.rebuild();
    g.checkInvariants();
    assertEquals(six, g.find(live));
    assertEquals(5, g.numNodes(), "a0, b, 6, a and the sum, live again");
    assertEquals(OptionalInt.of(six), g.lookup(new Toy.Add(IntList.of(a, b))));
    // A merge through the other child queues the lists the drop left one live entry in.
    g.merge(b, a0);
    g.rebuild();
    g.checkInvariants();
    assertEquals(OptionalInt.of(six), g.lookup(new Toy.Add(IntList.of(a, b))));
    // The same with the prune before the merge: the memory holds a + b when a0 + b is added.
    EGraph<Toy, Void> h = EGraph.withoutAnalysis();
    int ha0 = h.add(new Toy.Var("a0"));
    int hb = h.add(new Toy.Var("b"));
    int hsix = h.add(new Toy.Num(6));
    int ha = h.add(new Toy.Var("a"));
    h.merge(h.add(new Toy.Add(IntList.of(ha, hb))), hsix);
    assertEquals(1, h.retainNodes(hsix, node -> node instanceof Toy.Num));
    h.merge(ha, ha0);
    int hlive = h.add(new Toy.Add(IntList.of(ha0, hb)));
    assertNotEquals(hsix, h.find(hlive));
    h.rebuild();
    h.checkInvariants();
    assertEquals(hsix, h.find(hlive));
    assertEquals(5, h.numNodes());
    h.merge(hb, ha0);
    h.rebuild();
    h.checkInvariants();
    assertEquals(OptionalInt.of(hsix), h.lookup(new Toy.Add(IntList.of(ha, hb))));
  }

  @Test
  void aLiveNodeReKeyedIntoAPrunedNodesFormIsMergedWithIt() {
    // The live entry's turn finds the memory. six holds 6 and a0 + b, pruned to 6 (the memory
    // keeps a0 + b); then a + b is added, a class of its own, and a is merged into a0. The
    // rebuild re-keys a + b under a0 + b, and the memory says that form is six's.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a0 = g.add(new Toy.Var("a0"));
    int b = g.add(new Toy.Var("b"));
    int six = g.add(new Toy.Num(6));
    g.merge(g.add(new Toy.Add(IntList.of(a0, b))), six);
    assertEquals(1, g.retainNodes(six, node -> node instanceof Toy.Num));
    int a = g.add(new Toy.Var("a"));
    int live = g.add(new Toy.Add(IntList.of(a, b)));
    g.merge(a, a0);
    assertEquals(1, g.rebuild());
    g.checkInvariants();
    assertEquals(six, g.find(live));
    assertEquals(5, g.numNodes(), "a0, b, 6, a and the sum, live again");
    assertEquals(OptionalInt.of(six), g.lookup(new Toy.Add(IntList.of(a0, b))));
    // The live entry was dropped in favour of the pruned one, which then stands for the live
    // node: a merge through the other child re-keys it as a live node.
    g.merge(b, a0);
    g.rebuild();
    g.checkInvariants();
    assertEquals(OptionalInt.of(six), g.lookup(new Toy.Add(IntList.of(a, b))));
    assertEquals(List.of(new Toy.Num(6), new Toy.Add(IntList.of(a0, a0))),
        g.classOf(six).nodes());
  }

  @Test
  void twoPrunedNodesMadeCongruentMergeTheirClasses() {
    // one holds 1 and a1 + b, two holds 2 and a2 + b, each pruned to its constant; then a1 and
    // a2 are merged into a0. Both pruned entries are queued; the second's turn in the memory
    // finds the first's class under a0 + b, and the two classes merge.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a0 = g.add(new Toy.Var("a0"));
    int b = g.add(new Toy.Var("b"));
    int one = g.add(new Toy.Num(1));
    int a1 = g.add(new Toy.Var("a1"));
    g.merge(g.add(new Toy.Add(IntList.of(a1, b))), one);
    int two = g.add(new Toy.Num(2));
    int a2 = g.add(new Toy.Var("a2"));
    g.merge(g.add(new Toy.Add(IntList.of(a2, b))), two);
    assertEquals(1, g.retainNodes(one, node -> node instanceof Toy.Num));
    assertEquals(1, g.retainNodes(two, node -> node instanceof Toy.Num));
    g.merge(a1, a0);
    g.merge(a2, a0);
    assertEquals(2, g.rebuild());
    g.checkInvariants();
    assertEquals(one, g.find(two));
    assertEquals(6, g.numNodes(), "a0, b, 1, a1, 2 and a2");
    assertEquals(OptionalInt.of(one), g.lookup(new Toy.Add(IntList.of(a1, b))));
    assertEquals(OptionalInt.of(one), g.lookup(new Toy.Add(IntList.of(a0, b))));
    assertEquals(one, g.add(new Toy.Add(IntList.of(a2, b))));
    assertEquals(6, g.numNodes(), "the pruned sum is remembered, not added");
    g.merge(b, a0);
    g.rebuild();
    g.checkInvariants();
    assertEquals(OptionalInt.of(one), g.lookup(new Toy.Add(IntList.of(a1, b))));
    assertEquals(6, g.numNodes());
  }
}
