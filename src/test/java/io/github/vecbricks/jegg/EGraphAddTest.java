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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class EGraphAddTest {

  @Test
  void anEqualNodeAddedTwiceIsOneClassAndTheInvariantHoldsAfterEveryAdd() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int x = g.add(new Toy.Var("x"));
    g.checkInvariants();
    int one = g.add(new Toy.Num(1));
    g.checkInvariants();
    int sum = g.add(new Toy.Add(IntList.of(x, one)));
    g.checkInvariants();
    // The same node from a fresh array hashconses to the same class.
    assertEquals(sum, g.add(new Toy.Add(IntList.of(new int[] {x, one}))));
    assertEquals(3, g.numClasses());
    assertEquals(3, g.numNodes());
    assertEquals(OptionalInt.of(sum), g.lookup(new Toy.Add(IntList.of(x, one))));
    assertEquals(OptionalInt.empty(), g.lookup(new Toy.Add(IntList.of(one, x))));
    // The parents of x and one are the sum, recorded with the class it was added to.
    assertEquals(List.of(new EClass.Parent<>(new Toy.Add(IntList.of(x, one)), sum)),
        g.classOf(x).parents());
    assertEquals(g.classOf(x).parents(), g.classOf(one).parents());
    g.checkInvariants();
  }

  @Test
  void thePayloadIsPartOfTheNodesIdentity() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int x = g.add(new Toy.Var("x"));
    int two = g.add(new Toy.Num(2));
    int checked = g.add(new Toy.Div(true, IntList.of(x, two)));
    int unchecked = g.add(new Toy.Div(false, IntList.of(x, two)));
    assertNotEquals(checked, unchecked);
    assertEquals(new Toy.Div(true, IntList.EMPTY), g.classOf(checked).nodes().get(0).head());
    assertNotEquals(g.classOf(checked).nodes().get(0).head(),
        g.classOf(unchecked).nodes().get(0).head());
    assertEquals(4, g.numClasses());
  }

  @Test
  void idsFollowInsertionOrderWhateverTheHashCodes() {
    // Determinism (PLAN.md 3.1): the id a node gets is the number of distinct nodes before it.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    for (int i = 0; i < 100; i++) {
      assertEquals(i, g.add(new Toy.Num(i * 7919L)));
    }
    for (int i = 0; i < 100; i++) {
      assertEquals(i, g.add(new Toy.Num(i * 7919L)));
    }
  }

  @Test
  void aTreeAddedThroughTheBridgeSharesItsEqualSubtreesAndBuildsBackEqual() {
    // (x + 1) * (x + 1) with the two sums as separate objects: one class for x, one for 1, one
    // for the sum, one for the product.
    Toy.Tree sum = Toy.Tree.add(Toy.Tree.var("x"), Toy.Tree.num(1));
    Toy.Tree again = Toy.Tree.add(Toy.Tree.var("x"), Toy.Tree.num(1));
    Toy.Tree product = Toy.Tree.mul(sum, again);
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int root = g.addTree(product, Toy.BRIDGE);
    g.checkInvariants();
    assertEquals(4, g.numClasses());
    assertEquals(4, g.numNodes());
    Toy node = g.classOf(root).nodes().get(0);
    assertTrue(node instanceof Toy.Mul);
    assertEquals(node.children().get(0), node.children().get(1));
    // Built back, leaf by leaf, the tree is equal to the one added.
    assertEquals(product, rebuild(g, root));
    // The same tree added again changes nothing.
    assertEquals(root, g.addTree(product, Toy.BRIDGE));
    assertEquals(4, g.numNodes());
  }

  @Test
  void aSharedSubtreeIsLookedUpOnceNotOncePerPath() {
    // t = t' + t' forty levels deep, every level one object reached twice: 2^40 paths, so a
    // lookup that walked each path would not return.
    Toy.Tree t = Toy.Tree.var("x");
    for (int i = 0; i < 40; i++) {
      t = Toy.Tree.add(t, t);
    }
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int root = g.addTree(t, Toy.BRIDGE);
    assertEquals(41, g.numClasses());
    assertEquals(OptionalInt.of(root), g.lookupTree(t, Toy.BRIDGE));
    assertEquals(OptionalInt.empty(), g.lookupTree(Toy.Tree.mul(t, t), Toy.BRIDGE));
  }

  /** The tree of a graph with one node per class, through the bridge's build. */
  private static Toy.Tree rebuild(EGraph<Toy, Void> g, int id) {
    Toy node = g.classOf(id).nodes().get(0);
    List<Toy.Tree> kids = node.children().stream().mapToObj(c -> rebuild(g, c)).toList();
    return Toy.BRIDGE.build(node, kids);
  }

  @Test
  void aMissingSubtreeReachedTwiceIsLookedUpOnce() {
    // The sum's two operands are the same missing object: the second reach reads the memo.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    g.add(new Toy.Var("x"));
    Toy.Tree missing = Toy.Tree.add(Toy.Tree.var("x"), Toy.Tree.num(5));
    assertEquals(OptionalInt.empty(), g.lookupTree(Toy.Tree.mul(missing, missing), Toy.BRIDGE));
    assertEquals(OptionalInt.empty(), g.lookupTree(Toy.Tree.num(5), Toy.BRIDGE));
    assertSame(Analysis.class, g.analysis().getClass().getInterfaces()[0]);
  }
}
