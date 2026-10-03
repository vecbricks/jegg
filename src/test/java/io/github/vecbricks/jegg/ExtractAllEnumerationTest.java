/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@link Extractor#extractAll} against the optimum found by enumerating every choice of one node
 * per class, on random graphs small enough to enumerate: the issue's prediction 2, at least 95
 * of 100 seeds equal, every miss named. Each seed draws its own prices too, so a form that is
 * cheap in one graph is dear in the next and sharing is not always the cheaper side.
 */
class ExtractAllEnumerationTest {

  /** A price per operator, drawn from 0 to 20; leaves cost one. */
  private static CostFunction<Toy> table(Random rnd) {
    double[] w = new double[5];
    for (int i = 0; i < w.length; i++) {
      w[i] = rnd.nextInt(21);
    }
    return node -> switch (node) {
      case Toy.Num n -> 1.0;
      case Toy.Var v -> 1.0;
      case Toy.Add a -> w[0];
      case Toy.Mul m -> w[1];
      case Toy.Div d -> d.checked() ? w[2] : w[3];
    };
  }

  /** A random rebuilt graph of at most six classes, or null if the draw grew past six. */
  private static EGraph<Toy, Void> graph(Random rnd) {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    List<Integer> ids = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      ids.add(g.add(new Toy.Var("v" + i)));
    }
    int steps = 3 + rnd.nextInt(6);
    for (int step = 0; step < steps; step++) {
      int x = ids.get(rnd.nextInt(ids.size()));
      int y = ids.get(rnd.nextInt(ids.size()));
      Toy node = switch (rnd.nextInt(3)) {
        case 0 -> new Toy.Add(IntList.of(x, y));
        case 1 -> new Toy.Mul(IntList.of(x, y));
        default -> new Toy.Div(rnd.nextBoolean(), IntList.of(x, y));
      };
      ids.add(g.add(node));
      if (rnd.nextInt(3) != 0) {
        g.merge(ids.get(rnd.nextInt(ids.size())), ids.get(rnd.nextInt(ids.size())));
      }
    }
    g.rebuild();
    return g.numClasses() <= 6 ? g : null;
  }

  /** The least summed node cost of any acyclic choice of one node per class, by enumeration. */
  private static double optimum(EGraph<Toy, Void> g, IntList roots, CostFunction<Toy> table) {
    List<EClass<Toy, Void>> classes = g.classes();
    int[] pick = new int[classes.size()];
    double best = Double.POSITIVE_INFINITY;
    while (true) {
      double c = unionCost(g, classes, pick, roots, table);
      best = Math.min(best, c);
      int i = 0;
      while (i < pick.length && ++pick[i] == classes.get(i).nodes().size()) {
        pick[i++] = 0;
      }
      if (i == pick.length) {
        return best;
      }
    }
  }

  private static double unionCost(EGraph<Toy, Void> g, List<EClass<Toy, Void>> classes,
      int[] pick, IntList roots, CostFunction<Toy> table) {
    BitSet done = new BitSet();
    double[] total = {0.0};
    for (int i = 0; i < roots.size(); i++) {
      if (!walk(g, classes, pick, g.find(roots.get(i)), done, new BitSet(), table, total)) {
        return Double.POSITIVE_INFINITY;
      }
    }
    return total[0];
  }

  private static boolean walk(EGraph<Toy, Void> g, List<EClass<Toy, Void>> classes, int[] pick,
      int id, BitSet done, BitSet path, CostFunction<Toy> table, double[] total) {
    if (path.get(id)) {
      return false;
    }
    if (done.get(id)) {
      return true;
    }
    int index = 0;
    while (classes.get(index).id() != id) {
      index++;
    }
    Toy node = classes.get(index).nodes().get(pick[index]);
    path.set(id);
    for (int k = 0; k < node.children().size(); k++) {
      if (!walk(g, classes, pick, g.find(node.children().get(k)), done, path, table, total)) {
        return false;
      }
    }
    path.clear(id);
    done.set(id);
    total[0] += table.nodeCost(node);
    return true;
  }

  @Test
  void theDescentReachesTheEnumeratedOptimumOnAtLeast95Of100SmallGraphs() {
    int seeds = 0;
    List<String> misses = new ArrayList<>();
    for (long seed = 1; seeds < 100; seed++) {
      Random rnd = new Random(seed);
      CostFunction<Toy> table = table(rnd);
      EGraph<Toy, Void> g = graph(rnd);
      if (g == null) {
        continue;
      }
      seeds++;
      List<EClass<Toy, Void>> classes = g.classes();
      IntList roots = IntList.of(classes.get(rnd.nextInt(classes.size())).id(),
          classes.get(rnd.nextInt(classes.size())).id());
      double optimum = optimum(g, roots, table);
      double got = new Extractor<>(g, table).extractAll(roots).cost();
      assertTrue(got >= optimum - 1e-9, "seed " + seed + " beat the enumeration");
      if (got > optimum + 1e-9) {
        misses.add("seed " + seed + ": " + got + " against " + optimum);
      }
    }
    assertTrue(misses.size() <= 5, "misses: " + misses);
  }
}
