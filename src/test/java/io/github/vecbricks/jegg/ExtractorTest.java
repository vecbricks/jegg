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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ExtractorTest {

  @Test
  void theSimpleRulesExtractFooFromZeroPlusOneTimesFoo() {
    // egg's simple test to its end: the cheapest term in the root's class is `foo`.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int foo = g.add(new Toy.Var("foo"));
    int root = g.add(new Toy.Add(IntList.of(g.add(new Toy.Num(0)),
        g.add(new Toy.Mul(IntList.of(g.add(new Toy.Num(1)), foo))))));
    Runner.of(g, SimpleRulesTest.rules()).run();
    Extractor<Toy, Void> ex = new Extractor<>(g, CostFunction.astSize());
    assertEquals(new Extractor.Best<>(new Toy.Var("foo"), 1.0), ex.best(root));
    assertEquals("Var[name=foo]", ex.extract(root).toString());
    assertEquals(Toy.Tree.var("foo"), ex.extract(root).toTree(Toy.BRIDGE));
  }

  @Test
  void tiesBreakByTheClassesNodeOrder() {
    // a + b and b + a in one class, both size three: the one added first is extracted.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int ab = g.add(new Toy.Add(IntList.of(a, b)));
    g.merge(ab, g.add(new Toy.Add(IntList.of(b, a))));
    g.rebuild();
    assertEquals(new Toy.Add(IntList.of(a, b)),
        new Extractor<>(g, CostFunction.astSize()).best(ab).node());
    assertEquals(2.0, new Extractor<>(g, CostFunction.astDepth()).best(ab).cost());
  }

  @Test
  void theCheapestTermMatchesBruteForceEnumerationOnSmallGraphs() {
    // Random small graphs: a few leaves, random binary nodes over them, random merges, then
    // the extractor's cost for every class against the cheapest of every term it represents up
    // to a depth, under a cost table with a payload-dependent price.
    CostFunction<Toy> table = node -> switch (node) {
      case Toy.Num n -> 1.0;
      case Toy.Var v -> 1.0;
      case Toy.Add a -> 2.0;
      case Toy.Mul m -> 3.0;
      case Toy.Div d -> d.checked() ? 7.0 : 5.0;
    };
    for (long seed = 1; seed <= 30; seed++) {
      Random rnd = new Random(seed);
      EGraph<Toy, Void> g = EGraph.withoutAnalysis();
      List<Integer> ids = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        ids.add(g.add(new Toy.Var("v" + i)));
      }
      for (int step = 0; step < 8; step++) {
        int x = ids.get(rnd.nextInt(ids.size()));
        int y = ids.get(rnd.nextInt(ids.size()));
        Toy node = switch (rnd.nextInt(3)) {
          case 0 -> new Toy.Add(IntList.of(x, y));
          case 1 -> new Toy.Mul(IntList.of(x, y));
          default -> new Toy.Div(rnd.nextBoolean(), IntList.of(x, y));
        };
        ids.add(g.add(node));
        if (rnd.nextInt(3) == 0) {
          g.merge(ids.get(rnd.nextInt(ids.size())), ids.get(rnd.nextInt(ids.size())));
        }
      }
      g.rebuild();
      Extractor<Toy, Void> ex = new Extractor<>(g, table);
      // With non-negative costs a cheapest tree repeats no class on a path, so a depth of the
      // number of classes enumerates every term that can be cheapest.
      Map<Long, Double> memo = new HashMap<>();
      for (EClass<Toy, Void> c : g.classes()) {
        double enumerated = cheapest(g, c.id(), table, g.numClasses(), memo);
        assertEquals(enumerated, ex.best(c.id()).cost(), 1e-9,
            "seed " + seed + " class " + c.id());
      }
    }
  }

  /**
   * The cheapest tree the class represents within {@code depth}, by enumeration of every node
   * at every level; memoised by class and depth, which changes the work, not the answer.
   */
  private static double cheapest(EGraph<Toy, Void> g, int id, CostFunction<Toy> table,
      int depth, Map<Long, Double> memo) {
    if (depth == 0) {
      return Double.POSITIVE_INFINITY;
    }
    long key = ((long) g.find(id) << 32) | depth;
    Double known = memo.get(key);
    if (known != null) {
      return known;
    }
    double min = Double.POSITIVE_INFINITY;
    for (Toy node : g.classOf(id).nodes()) {
      double c = table.nodeCost(node);
      IntList kids = node.children();
      for (int i = 0; i < kids.size() && c < Double.POSITIVE_INFINITY; i++) {
        c += cheapest(g, kids.get(i), table, depth - 1, memo);
      }
      min = Math.min(min, c);
    }
    memo.put(key, min);
    return min;
  }

  @Test
  void extractAllChoosesOneNodePerClassAndPaysASharedSubtermOnce() {
    // Two roots over one shared class with two forms: cheap alone but expensive to share? No -
    // the reverse, which is the compiler's case: a form whose own cost is high but which both
    // roots can use once, against a form each root would pay for by itself.
    // s = x + y has two nodes: Add(x, y) (cost 2) and Mul(x, y) merged in as equal (cost 3).
    // Roots r1 = Div(true, s, x), r2 = Div(false, s, y): either way s is shared; the test is
    // that the selection names s once and its cost counts it once.
    CostFunction<Toy> table = node -> switch (node) {
      case Toy.Num n -> 1.0;
      case Toy.Var v -> 1.0;
      case Toy.Add a -> 2.0;
      case Toy.Mul m -> 3.0;
      case Toy.Div d -> 5.0;
    };
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int x = g.add(new Toy.Var("x"));
    int y = g.add(new Toy.Var("y"));
    int s = g.add(new Toy.Add(IntList.of(x, y)));
    g.merge(s, g.add(new Toy.Mul(IntList.of(x, y))));
    int r1 = g.add(new Toy.Div(true, IntList.of(s, x)));
    int r2 = g.add(new Toy.Div(false, IntList.of(s, y)));
    g.rebuild();
    Extractor<Toy, Void> ex = new Extractor<>(g, table);
    Selection<Toy> sel = ex.extractAll(IntList.of(r1, r2));
    assertEquals(5, sel.size(), sel.toString());
    assertEquals(new Toy.Add(IntList.of(x, y)), sel.node(g.find(s)));
    // x, y, the sum once, two divisions: 1 + 1 + 2 + 5 + 5.
    assertEquals(14.0, sel.cost(), 1e-9);
    // As trees each root pays its own sum and leaves: 5 + (2 + 1 + 1) + 1 = 10 each, 20 for
    // the two, of which the DAG is 14.
    assertEquals(10.0, ex.best(r1).cost(), 1e-9);
    assertEquals(10.0, ex.best(r2).cost(), 1e-9);
    List<Extracted<Toy>> terms = sel.terms();
    assertEquals(2, terms.size());
    assertTrue(terms.get(0).children().get(0) == terms.get(1).children().get(0),
        "the shared subterm is one object in both roots' terms");
  }

  @Test
  void aClientsScoreOfTheWholeSelectionSteersTheDescent() {
    // The same shared sum: by summed cost Add (2) beats Mul (3), but a client whose own score
    // of a selection rewards having no Add - its prediction over the candidate - gets Mul.
    CostFunction<Toy> table = node -> switch (node) {
      case Toy.Add a -> 2.0;
      case Toy.Mul m -> 3.0;
      case Toy.Div d -> 5.0;
      default -> 1.0;
    };
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int x = g.add(new Toy.Var("x"));
    int y = g.add(new Toy.Var("y"));
    int s = g.add(new Toy.Add(IntList.of(x, y)));
    g.merge(s, g.add(new Toy.Mul(IntList.of(x, y))));
    int r1 = g.add(new Toy.Div(true, IntList.of(s, x)));
    int r2 = g.add(new Toy.Div(false, IntList.of(s, y)));
    g.rebuild();
    Extractor<Toy, Void> ex = new Extractor<>(g, table);
    Selection<Toy> byCost = ex.extractAll(IntList.of(r1, r2));
    Selection<Toy> byScore = ex.extractAll(IntList.of(r1, r2), sel -> sel.cost()
        + (sel.nodes().values().stream().anyMatch(n -> n instanceof Toy.Add) ? 10.0 : 0.0));
    assertEquals(new Toy.Add(IntList.of(x, y)), byCost.node(g.find(s)));
    assertEquals(new Toy.Mul(IntList.of(x, y)), byScore.node(g.find(s)));
    assertEquals(15.0, byScore.cost(), 1e-9, "the selection's cost stays the summed node cost");
  }

  @Test
  void noRootsSelectNothing() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    g.add(new Toy.Var("x"));
    Selection<Toy> none = new Extractor<>(g, CostFunction.<Toy>astSize()).extractAll(IntList.EMPTY);
    assertEquals(0, none.size());
    assertEquals(0.0, none.cost());
    assertEquals(List.of(), none.terms());
  }

  @Test
  void aTreeThatRepeatsAClassIsBuiltOncePerClass() {
    // s_i = s_(i-1) * s_(i-1), forty deep: a tree of 2^41 - 1 nodes over 41 classes.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int s = g.add(new Toy.Var("x"));
    for (int i = 0; i < 40; i++) {
      s = g.add(new Toy.Mul(IntList.of(s, s)));
    }
    Extracted<Toy> term = new Extractor<>(g, CostFunction.<Toy>astSize()).extract(s);
    assertSame(term.children().get(0), term.children().get(1));
    assertEquals((1L << 41) - 1, term.treeSize());
    Toy.Tree tree = term.toTree(Toy.BRIDGE);
    assertSame(tree.kids().get(0), tree.kids().get(1));
  }

  @Test
  void aNodeThroughItsOwnClassIsNeverChosen() {
    // x's class also holds x + 0, free under this table: a DAG through the class itself, which
    // the greedy start must not take, as no term ends there.
    CostFunction<Toy> table = node -> switch (node) {
      case Toy.Var v -> 1.0;
      default -> 0.0;
    };
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int x = g.add(new Toy.Var("x"));
    int zero = g.add(new Toy.Num(0));
    g.merge(x, g.add(new Toy.Add(IntList.of(x, zero))));
    int root = g.add(new Toy.Mul(IntList.of(x, zero)));
    g.rebuild();
    Selection<Toy> sel = new Extractor<>(g, table).extractAll(IntList.of(root));
    assertEquals(new Toy.Var("x"), sel.node(g.find(x)));
    assertEquals(1.0, sel.cost());
  }

  @Test
  void aNegativeOrNotANumberCostIsRefused() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int x = g.add(new Toy.Var("x"));
    g.merge(x, g.add(new Toy.Add(IntList.of(x, x))));
    g.rebuild();
    assertThrows(IllegalArgumentException.class,
        () -> new Extractor<>(g, node -> node instanceof Toy.Add ? -1.0 : 1.0));
    assertThrows(IllegalArgumentException.class,
        () -> new Extractor<>(g, node -> Double.NaN));
  }

  @Test
  void anExtractorRefusesAGraphThatChangedSinceItPricedIt() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int x = g.add(new Toy.Var("x"));
    Extractor<Toy, Void> ex = new Extractor<>(g, CostFunction.astSize());
    g.add(new Toy.Var("y"));
    assertThrows(IllegalStateException.class, () -> ex.extract(x));
    assertThrows(IllegalStateException.class, () -> ex.extractAll(IntList.of(x)));
  }

  @Test
  void aGreedyStartThatClosesACycleOfThreeFallsBackToTheTrees() {
    // Three classes, each holding a leaf and a free node over the next class, the leaves 13,
    // 12 and 11: the greedy choice, made class by class against the choices of the moment,
    // switches each class to its free node in turn - each strictly cheaper then - and ends
    // with a cycle of three that no term has. The start must see it and begin from the trees
    // instead, and the selection must be a term: the free nodes down to the cheapest leaf, 11.
    CostFunction<Toy> table = node -> switch (node) {
      case Toy.Var v -> switch (v.name()) {
        case "x" -> 13.0;
        case "y" -> 12.0;
        default -> 11.0;
      };
      default -> 0.0;
    };
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("x"));
    int b = g.add(new Toy.Var("y"));
    int c = g.add(new Toy.Var("z"));
    g.merge(a, g.add(new Toy.Add(IntList.of(b, b))));
    g.merge(b, g.add(new Toy.Add(IntList.of(c, c))));
    g.merge(c, g.add(new Toy.Add(IntList.of(a, a))));
    g.rebuild();
    Selection<Toy> sel = new Extractor<>(g, table).extractAll(IntList.of(a));
    assertEquals(11.0, sel.cost(), 1e-9, sel.toString());
    assertEquals(1, sel.terms().size(), "a term, not a cycle: " + sel);
    assertEquals(7, sel.terms().get(0).treeSize(), sel.toString());
  }

  @Test
  void aParentAddedBeforeAMergeIsStillOfferedTheSharedForm() {
    // c = Div(u, v), cost 6, is the investment two roots can share: s = Add(c, q) and t = Mul(c,
    // q), cost 1 each; each root also has a direct leaf of cost 5. Alone, each prefers its leaf
    // (5 against 7); together, both through c cost 8 against 10. q is merged into r's class
    // after both were added, so the entries in c's parent list were re-keyed by the repair of
    // r's class, which c's list shares with it (they read the node over r now, not q as added).
    // r is a root too, so it is selected throughout and a held root brings in c alone: c's
    // entries are then the only route to the other root, which must be found through the node's
    // class, or the sharing is missed from both sides.
    CostFunction<Toy> table = node -> switch (node) {
      case Toy.Var v -> v.name().startsWith("direct") ? 5.0 : 0.0;
      case Toy.Div d -> 6.0;
      default -> 1.0;
    };
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int u = g.add(new Toy.Var("u"));
    int v = g.add(new Toy.Var("v"));
    int c = g.add(new Toy.Div(true, IntList.of(u, v)));
    int r = g.add(new Toy.Var("r"));
    int q = g.add(new Toy.Var("q"));
    int s = g.add(new Toy.Add(IntList.of(c, q)));
    g.merge(s, g.add(new Toy.Var("directS")));
    int t = g.add(new Toy.Mul(IntList.of(c, q)));
    g.merge(t, g.add(new Toy.Var("directT")));
    g.merge(q, r);
    g.rebuild();
    assertTrue(g.classOf(c).parents().stream()
        .allMatch(p -> p.node().children().get(1) == g.find(r)),
        "the entries were re-keyed through r's class, which shares them");
    Selection<Toy> sel = new Extractor<>(g, table).extractAll(IntList.of(s, t, r));
    assertEquals(8.0, sel.cost(), 1e-9, sel.toString());
    assertEquals(new Toy.Mul(IntList.of(c, g.find(r))), sel.node(g.find(t)));
  }

  @Test
  void theGuardsRefuseADirtyGraphAndAClassOutsideTheSelection() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    g.merge(a, b);
    assertThrows(IllegalStateException.class, () -> new Extractor<>(g, CostFunction.astSize()),
        "a merge not yet rebuilt");
    g.rebuild();
    int sum = g.add(new Toy.Add(IntList.of(a, a)));
    Selection<Toy> sel = new Extractor<>(g, CostFunction.<Toy>astSize())
        .extractAll(IntList.of(sum));
    assertEquals(IntList.of(sum), sel.roots());
    assertEquals(2, sel.size());
    assertThrows(IllegalArgumentException.class, () -> sel.node(1_000));
    // A class with no finite-cost term cannot be built: every node is added over classes that
    // exist, so every class holds a term, and the extractor refuses infinite costs. The guards
    // for it in best and extractAll stay as defensive code.
  }

  @Test
  void aCandidateThatWouldCloseACycleIsRefusedAndTheDescentGoesOn() {
    // A = {x (3), Add(B, B) (0)}, B = {y (1), Add(A, A) (0)}, root A. The greedy start takes
    // A -> Add(B, B) over y, cost 1. The descent then tries B -> Add(A, A), which would close
    // A -> B -> A: refused, and the selection stays a term of cost 1.
    CostFunction<Toy> table = node -> switch (node) {
      case Toy.Var v -> v.name().equals("x") ? 3.0 : 1.0;
      default -> 0.0;
    };
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("x"));
    int b = g.add(new Toy.Var("y"));
    g.merge(a, g.add(new Toy.Add(IntList.of(b, b))));
    g.merge(b, g.add(new Toy.Add(IntList.of(a, a))));
    g.rebuild();
    Selection<Toy> sel = new Extractor<>(g, table).extractAll(IntList.of(a));
    assertEquals(1.0, sel.cost(), sel.toString());
    assertEquals(new Toy.Add(IntList.of(g.find(b), g.find(b))), sel.node(g.find(a)));
    assertEquals(new Toy.Var("y"), sel.node(g.find(b)));
    assertEquals(1, sel.terms().size());
  }

  @Test
  void aGraphThatIssuedIdsSinceThePricingIsRefusedWhateverItsCounts() {
    // After the pricing, a class is added and two others merged, whose two parents become one
    // node: the node and class counts are what they were, but there is an id the pricing never
    // saw, and the extractor must refuse rather than price it as missing.
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int f = g.add(new Toy.Add(IntList.of(a, a)));
    g.merge(f, g.add(new Toy.Add(IntList.of(b, b))));
    g.rebuild();
    Extractor<Toy, Void> ex = new Extractor<>(g, CostFunction.astSize());
    int nodes = g.numNodes();
    int classes = g.numClasses();
    int c = g.add(new Toy.Var("c"));
    g.merge(a, b);
    g.rebuild();
    assertEquals(nodes, g.numNodes());
    assertEquals(classes, g.numClasses());
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> ex.extract(c));
    assertTrue(e.getMessage().contains("changed"), e.getMessage());
    assertThrows(IllegalStateException.class, () -> ex.extractAll(IntList.of(c)));
    assertThrows(IllegalStateException.class, () -> ex.best(a));
  }

  @Test
  void aNodeWithItsOwnClassAsAChildReadsThePreviousPassesCost() {
    // One class, nodes in order x (cost 5), h(C) and m(C), where h costs 1 + half its child and
    // m costs 10 less twice its child, a cost that falls as the child's rises. egg's make_pass
    // prices a class's nodes against the costs before the pass and stores the class's cost after:
    // pass one prices x alone, pass two prices h and m against 5, and m at 0 wins for good. Read
    // against the pass's own running cost, m would see h's 3.5 first and lose to h at 2.
    CostFunction<Toy> table = new CostFunction<>() {
      @Override
      public double nodeCost(Toy node) {
        return switch (node) {
          case Toy.Var v -> 5.0;
          case Toy.Add h -> 1.0;
          default -> 10.0;
        };
      }

      @Override
      public double cost(Toy node, java.util.function.IntToDoubleFunction childCost) {
        return switch (node) {
          case Toy.Add h -> 1.0 + 0.5 * childCost.applyAsDouble(h.children().get(0));
          case Toy.Mul m -> 10.0 - 2.0 * childCost.applyAsDouble(m.children().get(0));
          default -> nodeCost(node);
        };
      }
    };
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int c = g.add(new Toy.Var("x"));
    g.merge(c, g.add(new Toy.Add(IntList.of(c))));
    g.merge(c, g.add(new Toy.Mul(IntList.of(c))));
    g.rebuild();
    assertEquals(List.of(new Toy.Var("x"), new Toy.Add(IntList.of(c)), new Toy.Mul(IntList.of(c))),
        g.classOf(c).nodes());
    assertEquals(new Extractor.Best<>(new Toy.Mul(IntList.of(c)), 0.0),
        new Extractor<>(g, table).best(c));
  }
}
