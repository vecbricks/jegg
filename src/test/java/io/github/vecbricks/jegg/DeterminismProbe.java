/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

import java.util.List;

/**
 * A main that runs one saturation and prints the graph: the determinism test forks it in fresh
 * JVMs and compares what each printed byte for byte.
 */
public final class DeterminismProbe {

  private DeterminismProbe() {
  }

  /** The graph rendered class by class: ids, nodes, facts. */
  static String render(EGraph<Toy, Long> g) {
    StringBuilder b = new StringBuilder();
    for (EClass<Toy, Long> c : g.classes()) {
      b.append(c.id()).append(": ").append(c.nodes()).append(" = ").append(c.data()).append('\n');
    }
    return b.toString();
  }

  static String saturate() {
    EGraph<Toy, Long> g = new EGraph<>(ConstantFoldTest.FOLD);
    // ((((a + 2) + b) + 3) + c) + (1 + 1) * a, under commutativity, associativity and mul-1,
    // with a node limit below the saturated size, so what is printed is a cut of the expansion:
    // the graph at the iteration the limit stopped it, which must be the same cut every time.
    int a = g.add(new Toy.Var("a"));
    int sum = a;
    for (Toy next : List.of(new Toy.Num(2), new Toy.Var("b"), new Toy.Num(3),
        new Toy.Var("c"))) {
      sum = g.add(new Toy.Add(IntList.of(sum, g.add(next))));
    }
    int one = g.add(new Toy.Num(1));
    int right = g.add(new Toy.Mul(IntList.of(g.add(new Toy.Add(IntList.of(one, one))), a)));
    g.add(new Toy.Add(IntList.of(sum, right)));
    Pattern<Toy> x = Pattern.var("x");
    Pattern<Toy> y = Pattern.var("y");
    Pattern<Toy> z = Pattern.var("z");
    List<Rewrite<Toy, Long>> rules = List.of(
        Rewrite.of("commute-add", Pattern.of(new Toy.Add(IntList.EMPTY), x, y),
            Pattern.of(new Toy.Add(IntList.EMPTY), y, x)),
        Rewrite.of("commute-mul", Pattern.of(new Toy.Mul(IntList.EMPTY), x, y),
            Pattern.of(new Toy.Mul(IntList.EMPTY), y, x)),
        Rewrite.of("assoc-add",
            Pattern.of(new Toy.Add(IntList.EMPTY),
                Pattern.of(new Toy.Add(IntList.EMPTY), x, y), z),
            Pattern.of(new Toy.Add(IntList.EMPTY), x,
                Pattern.of(new Toy.Add(IntList.EMPTY), y, z))),
        Rewrite.of("mul-1", Pattern.of(new Toy.Mul(IntList.EMPTY), x, Pattern.of(new Toy.Num(1))),
            x));
    RunReport report = new Runner<>(g, rules, RunLimits.DEFAULT.withNodes(40),
        new BackoffScheduler<>(20, 2)).run();
    return render(g) + report;
  }

  public static void main(String[] args) {
    System.out.print(saturate());
  }
}
