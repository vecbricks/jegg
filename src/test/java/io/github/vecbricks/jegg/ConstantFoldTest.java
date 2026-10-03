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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Constant folding as an e-class analysis, the paper's first example (its section 4.2): the fact
 * is the class's value when every term in it is a constant, {@code make} computes it from the
 * children's facts, {@code join} keeps the one that is known, and {@code modify} puts the
 * constant's own node in the class, so a class that folds to a number holds that number.
 */
class ConstantFoldTest {

  /** The folding analysis over {@link Toy}: the fact is a {@code Long} or null. */
  static final Analysis<Toy, Long> FOLD = new Analysis<>() {
    @Override
    public Long make(EGraph<Toy, Long> g, Toy node) {
      return switch (node) {
        case Toy.Num n -> n.value();
        case Toy.Var v -> null;
        case Toy.Add a -> binary(g, a.children(), Long::sum);
        case Toy.Mul m -> binary(g, m.children(), (x, y) -> x * y);
        case Toy.Div d -> {
          Long y = g.data(d.children().get(1));
          yield y == null || y == 0 ? null : binary(g, d.children(), (p, q) -> p / q);
        }
      };
    }

    private Long binary(EGraph<Toy, Long> g, IntList kids,
        java.util.function.LongBinaryOperator f) {
      Long x = g.data(kids.get(0));
      Long y = g.data(kids.get(1));
      return x == null || y == null ? null : f.applyAsLong(x, y);
    }

    @Override
    public Long join(Long a, Long b) {
      if (a != null && b != null && !a.equals(b)) {
        throw new IllegalStateException("two constants in one class: " + a + " and " + b);
      }
      return a != null ? a : b;
    }

    @Override
    public void modify(EGraph<Toy, Long> g, int id) {
      Long value = g.data(id);
      if (value != null) {
        g.merge(id, g.add(new Toy.Num(value)));
      }
    }
  };

  @Test
  void aConstantExpressionFoldsToItsNumberOnAdd() {
    EGraph<Toy, Long> g = new EGraph<>(FOLD);
    int one = g.add(new Toy.Num(1));
    int two = g.add(new Toy.Num(2));
    int sum = g.add(new Toy.Add(IntList.of(one, two)));
    // modify added Num(3) and merged it into the sum's class; the rebuild settles the union.
    assertEquals(3L, g.data(sum));
    g.rebuild();
    assertEquals(g.find(sum), g.find(g.add(new Toy.Num(3))));
    assertTrue(g.classOf(sum).nodes().contains(new Toy.Num(3)));
    g.checkInvariants();
    g.checkAnalysisInvariant();
  }

  @Test
  void aFactLearnedByMergingPropagatesUpToTheParentsAndFoldsThem() {
    // (x + 1) * 2 with x unknown, then x = 5 learned by a merge: the sum becomes 6 and the
    // product 12, two levels up, and each class gains its number.
    EGraph<Toy, Long> g = new EGraph<>(FOLD);
    int x = g.add(new Toy.Var("x"));
    int one = g.add(new Toy.Num(1));
    int two = g.add(new Toy.Num(2));
    int sum = g.add(new Toy.Add(IntList.of(x, one)));
    int product = g.add(new Toy.Mul(IntList.of(sum, two)));
    assertNull(g.data(sum));
    assertNull(g.data(product));
    g.merge(x, g.add(new Toy.Num(5)));
    g.rebuild();
    assertEquals(5L, g.data(x));
    assertEquals(6L, g.data(sum));
    assertEquals(12L, g.data(product));
    assertTrue(g.classOf(sum).nodes().contains(new Toy.Num(6)));
    assertTrue(g.classOf(product).nodes().contains(new Toy.Num(12)));
    g.checkInvariants();
    g.checkAnalysisInvariant();
  }

  @Test
  void theAnalysisInvariantHoldsAfterRandomAddsAndMergesOfConsistentConstants() {
    // Variables each tied to a constant by a merge at a random point; sums and products over
    // them; the facts must be the join of the nodes' facts after every rebuild, however the
    // merges were interleaved. Merging two classes known as different constants is never done
    // here, since that would make the graph inconsistent and the analysis throws.
    for (long seed = 1; seed <= 5; seed++) {
      Random rnd = new Random(seed);
      EGraph<Toy, Long> g = new EGraph<>(FOLD);
      java.util.List<Integer> ids = new java.util.ArrayList<>();
      long[] values = new long[4];
      for (int i = 0; i < 4; i++) {
        ids.add(g.add(new Toy.Var("v" + i)));
        values[i] = rnd.nextInt(5) + 1;
      }
      boolean[] known = new boolean[4];
      for (int step = 0; step < 60; step++) {
        if (rnd.nextInt(4) == 0) {
          int v = rnd.nextInt(4);
          if (!known[v]) {
            known[v] = true;
            g.merge(ids.get(v), g.add(new Toy.Num(values[v])));
          }
        } else {
          int a = ids.get(rnd.nextInt(ids.size()));
          int b = ids.get(rnd.nextInt(ids.size()));
          ids.add(g.add(rnd.nextBoolean() ? new Toy.Add(IntList.of(a, b))
              : new Toy.Mul(IntList.of(a, b))));
        }
        if (rnd.nextInt(5) == 0) {
          g.rebuild();
          g.checkAnalysisInvariant();
        }
      }
      g.rebuild();
      g.checkInvariants();
      g.checkAnalysisInvariant();
    }
  }
}
