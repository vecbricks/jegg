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
}
