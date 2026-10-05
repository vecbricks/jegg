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
  void repairingAClassWithThousandsOfParentsComparesEachAboutOnce() {
    // x has n distinct parents. Merging x with y repairs them all; a repair that looked for
    // congruent pairs by scanning what it had seen compared n^2/2 pairs, a hash finds them in n.
    EGraph<Counted, Void> g = EGraph.withoutAnalysis();
    int x = g.add(new Counted.Leaf(0));
    int y = g.add(new Counted.Leaf(1));
    int n = 3000;
    for (int i = 0; i < n; i++) {
      g.add(new Counted.Op(i, IntList.of(x)));
    }
    g.merge(x, y);
    Counted.EQUALS.set(0);
    assertEquals(1, g.rebuild());
    long calls = Counted.EQUALS.get();
    assertTrue(calls < 50L * n, "a repair of " + n + " parents called equals " + calls + " times");
    g.checkInvariants();
    assertEquals(n + 1, g.numClasses());
  }

  @Test
  void anEntrySeenThroughTheOtherChildNamesTheFormTheHashconsHolds() {
    // op = a + b. a is merged into a2 (the smaller id survives), so the repair of a2's class
    // re-keys op. b's class was not repaired, but its entry is the same one, and reads the new
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
  void aDuplicateDroppedUnderAnotherFormTakesItsKeyWithIt() {
    // The repair of b's class meets, in this order: b + s (in v0's class) and c + s (in b's class,
    // c having been merged into b), which are congruent and so merge b's class into v0's; then
    // b * v0 and b * b, keyed a moment earlier under those forms, which that merge has just made
    // one node, v0 * v0. b * b is the duplicate and leaves the list; b is its only child, so no
    // list will name it again, and its key must leave the hashcons with it. Found by the random
    // deferred-against-eager check; this is its smallest shape.
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
}
