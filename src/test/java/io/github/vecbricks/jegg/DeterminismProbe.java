/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.jegg;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The saturation the determinism tests render, forked into fresh JVMs by {@code DeterminismTest}:
 * a cut of an expansion under commutativity, associativity and {@code x * 1}, with constant
 * folding, stopped by a node limit, so what is rendered is the graph at an arbitrary point and
 * not a fixed point that would agree anyway.
 *
 * <p>Its language's variable names are {@link Name}s: interned objects without a {@code hashCode}
 * of their own, so their hash is the identity hash, which differs from one JVM to the next. Every
 * {@code HashMap} keyed by such a node then iterates in a different order in each fresh JVM, and
 * a hash order leaking into an id or a merge shows as a different rendering. {@code Toy}'s
 * value-based hashes could not show it: they are the same in every JVM.
 */
public final class DeterminismProbe {

  private DeterminismProbe() {
  }

  /** A variable's name, one instance per text, equal and hashed by identity. */
  static final class Name {
    private static final Map<String, Name> INTERNED = new HashMap<>();
    private final String text;

    private Name(String text) {
      this.text = text;
    }

    static Name of(String text) {
      return INTERNED.computeIfAbsent(text, Name::new);
    }

    @Override
    public String toString() {
      return text;
    }
  }

  sealed interface Probe extends Language<Probe> permits Probe.Num, Probe.Var, Probe.Add,
      Probe.Mul {
    record Num(long value) implements Probe {
      @Override
      public IntList children() {
        return IntList.EMPTY;
      }

      @Override
      public Probe withChildren(IntList c) {
        return this;
      }

      @Override
      public String toString() {
        return Long.toString(value);
      }
    }

    record Var(Name name) implements Probe {
      @Override
      public IntList children() {
        return IntList.EMPTY;
      }

      @Override
      public Probe withChildren(IntList c) {
        return this;
      }

      @Override
      public String toString() {
        return name.toString();
      }
    }

    record Add(IntList children) implements Probe {
      @Override
      public Probe withChildren(IntList c) {
        return new Add(c);
      }
    }

    record Mul(IntList children) implements Probe {
      @Override
      public Probe withChildren(IntList c) {
        return new Mul(c);
      }
    }
  }

  /** Constant folding, as ConstantFoldTest's FOLD on this language. */
  static final Analysis<Probe, Long> FOLD = new Analysis<>() {
    @Override
    public Long make(EGraph<Probe, Long> g, Probe node) {
      return switch (node) {
        case Probe.Num n -> n.value();
        case Probe.Var _ -> null;
        case Probe.Add a -> binary(g, a.children(), Long::sum);
        case Probe.Mul m -> binary(g, m.children(), (x, y) -> x * y);
      };
    }

    private Long binary(EGraph<Probe, Long> g, IntList kids,
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
    public void modify(EGraph<Probe, Long> g, int id) {
      Long value = g.data(id);
      if (value != null) {
        g.merge(id, g.add(new Probe.Num(value)));
      }
    }
  };

  /** The graph rendered class by class: ids, nodes, facts. */
  static String render(EGraph<Probe, Long> g) {
    StringBuilder b = new StringBuilder();
    for (EClass<Probe, Long> c : g.classes()) {
      b.append(c.id()).append(": ").append(c.nodes()).append(" = ").append(c.data()).append('\n');
    }
    return b.toString();
  }

  private static Probe var(String name) {
    return new Probe.Var(Name.of(name));
  }

  static String saturate() {
    EGraph<Probe, Long> g = new EGraph<>(FOLD);
    // ((((a + 2) + b) + 3) + c) + (1 + 1) * a, with a node limit below the saturated size.
    int a = g.add(var("a"));
    int sum = a;
    for (Probe next : List.of(new Probe.Num(2), var("b"), new Probe.Num(3), var("c"))) {
      sum = g.add(new Probe.Add(IntList.of(sum, g.add(next))));
    }
    int one = g.add(new Probe.Num(1));
    int right = g.add(new Probe.Mul(IntList.of(g.add(new Probe.Add(IntList.of(one, one))), a)));
    g.add(new Probe.Add(IntList.of(sum, right)));
    Pattern<Probe> x = Pattern.var("x");
    Pattern<Probe> y = Pattern.var("y");
    Pattern<Probe> z = Pattern.var("z");
    Probe add = new Probe.Add(IntList.EMPTY);
    Probe mul = new Probe.Mul(IntList.EMPTY);
    List<Rewrite<Probe, Long>> rules = List.of(
        Rewrite.of("commute-add", Pattern.of(add, x, y), Pattern.of(add, y, x)),
        Rewrite.of("commute-mul", Pattern.of(mul, x, y), Pattern.of(mul, y, x)),
        Rewrite.of("assoc-add", Pattern.of(add, Pattern.of(add, x, y), z),
            Pattern.of(add, x, Pattern.of(add, y, z))),
        Rewrite.of("mul-1", Pattern.of(mul, x, Pattern.of(new Probe.Num(1))), x));
    RunReport report = new Runner<>(g, rules, RunLimits.DEFAULT.withNodes(40),
        new BackoffScheduler<>(20, 2)).run();
    return render(g) + report;
  }

  public static void main(String[] args) {
    System.out.print(saturate());
  }
}
