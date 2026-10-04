/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.jegg;

import java.util.ArrayList;
import java.util.List;

/**
 * The Varka-shaped workload of PLAN.md 6, "absolute cost at Varka's size": a projection of 64
 * e-nodes (the size the plan fixes, before any rewriting) over the toy date language of
 * {@code DateSmokeTest} extended with integer arithmetic, several outputs over shared shifted
 * dates, and exactly twenty rules - the date rules, the
 * algebra of {@code +} and {@code *}, and a fused form a pair of fields can take. The term and
 * the rules are fixed here so the measurement is the same every time it is regenerated.
 */
final class Projection {

  private Projection() {
  }

  sealed interface Varka extends Language<Varka>
      permits Varka.Leaf, Varka.SlotSum, Varka.AddDays, Varka.Civil, Varka.Field, Varka.FieldOf,
      Varka.Months, Varka.Add, Varka.Mul {

    sealed interface Leaf extends Varka permits Col, Slot, Num {
      @Override
      default IntList children() {
        return IntList.EMPTY;
      }

      @Override
      default Varka withChildren(IntList c) {
        return this;
      }
    }

    /** The input column of dates. */
    record Col() implements Leaf {
    }

    /** A literal bound at run time: the payload is its slot. */
    record Slot(int index) implements Leaf {
    }

    record Num(long value) implements Leaf {
    }

    /** A scalar expression over slots, hoisted out of the loop by the emitter. */
    record SlotSum(IntList children) implements Varka {
      @Override
      public Varka withChildren(IntList c) {
        return new SlotSum(c);
      }
    }

    record AddDays(IntList children) implements Varka {
      @Override
      public Varka withChildren(IntList c) {
        return new AddDays(c);
      }
    }

    /** The civil decomposition of a day count: dear once, every field cheap after. */
    record Civil(IntList children) implements Varka {
      @Override
      public Varka withChildren(IntList c) {
        return new Civil(c);
      }
    }

    /** A field straight from the day count; the payload names which. */
    record Field(String name, IntList children) implements Varka {
      @Override
      public Varka withChildren(IntList c) {
        return new Field(name, c);
      }
    }

    /** A field read off a decomposition. */
    record FieldOf(String name, IntList children) implements Varka {
      @Override
      public Varka withChildren(IntList c) {
        return new FieldOf(name, c);
      }
    }

    /** Months since the epoch: year * 12 + month, as one node. */
    record Months(IntList children) implements Varka {
      @Override
      public Varka withChildren(IntList c) {
        return new Months(c);
      }
    }

    record Add(IntList children) implements Varka {
      @Override
      public Varka withChildren(IntList c) {
        return new Add(c);
      }
    }

    record Mul(IntList children) implements Varka {
      @Override
      public Varka withChildren(IntList c) {
        return new Mul(c);
      }
    }
  }

  /** The cost table: the numbers a measured register would give. */
  static final CostFunction<Varka> TABLE = node -> switch (node) {
    case Varka.Col c -> 0.0;
    case Varka.Slot s -> 0.0;
    case Varka.Num n -> 0.0;
    case Varka.SlotSum s -> 0.0;
    case Varka.AddDays a -> 1.0;
    case Varka.Civil c -> 30.0;
    case Varka.Field f -> 20.0;
    case Varka.FieldOf f -> 1.0;
    case Varka.Months m -> 25.0;
    case Varka.Add a -> 1.0;
    case Varka.Mul m -> 3.0;
  };

  /** The projection added to a graph: its output roots, in order. */
  static IntList addProjection(EGraph<Varka, Void> g) {
    int d = g.add(new Varka.Col());
    int[] s = new int[4];
    for (int i = 0; i < s.length; i++) {
      s[i] = g.add(new Varka.Slot(i));
    }
    int zero = g.add(new Varka.Num(0));
    int one = g.add(new Varka.Num(1));
    int two = g.add(new Varka.Num(2));
    int twelve = g.add(new Varka.Num(12));
    // Four shifted dates sharing prefixes, as a projection's outputs share them.
    int d1 = addDays(g, addDays(g, d, s[0]), s[1]);
    int d2 = addDays(g, addDays(g, d, s[2]), s[3]);
    int d3 = addDays(g, d1, s[2]);
    int d4 = addDays(g, d2, s[1]);
    int[] dates = {d1, d2, d3, d4};
    int[] year = new int[4];
    int[] month = new int[4];
    int[] day = new int[4];
    for (int i = 0; i < 4; i++) {
      year[i] = g.add(new Varka.Field("year", IntList.of(dates[i])));
      month[i] = g.add(new Varka.Field("month", IntList.of(dates[i])));
      day[i] = g.add(new Varka.Field("day", IntList.of(dates[i])));
    }
    List<Integer> roots = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      // year * 12 + month, the form the fused Months node stands for.
      roots.add(add(g, mul(g, year[i], twelve), month[i]));
    }
    roots.add(mul(g, add(g, day[0], zero), one));
    roots.add(mul(g, add(g, year[1], year[2]), two));
    roots.add(mul(g, month[1], add(g, one, zero)));
    roots.add(add(g, day[2], day[1]));
    roots.add(add(g, year[0], year[0]));
    roots.add(mul(g, day[0], add(g, month[0], day[1])));
    roots.add(add(g, mul(g, day[0], month[0]), mul(g, day[0], day[1])));
    roots.add(mul(g, add(g, year[3], year[0]), add(g, one, zero)));
    roots.add(add(g, mul(g, day[3], two), add(g, day[2], day[1])));
    roots.add(mul(g, add(g, year[0], year[1]), twelve));
    roots.add(mul(g, add(g, month[2], zero), one));
    roots.add(add(g, mul(g, year[2], twelve), day[2]));
    roots.add(mul(g, add(g, month[3], day[3]), two));
    roots.add(add(g, mul(g, year[3], twelve), day[3]));
    roots.add(mul(g, day[2], add(g, day[2], zero)));
    roots.add(add(g, mul(g, month[1], two), year[1]));
    int[] out = new int[roots.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = roots.get(i);
    }
    return IntList.of(out);
  }

  private static int addDays(EGraph<Varka, Void> g, int date, int offset) {
    return g.add(new Varka.AddDays(IntList.of(date, offset)));
  }

  private static int add(EGraph<Varka, Void> g, int a, int b) {
    return g.add(new Varka.Add(IntList.of(a, b)));
  }

  private static int mul(EGraph<Varka, Void> g, int a, int b) {
    return g.add(new Varka.Mul(IntList.of(a, b)));
  }

  private static Pattern<Varka> v(String n) {
    return Pattern.var(n);
  }

  private static Pattern<Varka> add(Pattern<Varka> a, Pattern<Varka> b) {
    return Pattern.of(new Varka.Add(IntList.EMPTY), a, b);
  }

  private static Pattern<Varka> mul(Pattern<Varka> a, Pattern<Varka> b) {
    return Pattern.of(new Varka.Mul(IntList.EMPTY), a, b);
  }

  private static Pattern<Varka> num(long n) {
    return Pattern.of(new Varka.Num(n));
  }

  private static Pattern<Varka> addDays(Pattern<Varka> d, Pattern<Varka> s) {
    return Pattern.of(new Varka.AddDays(IntList.EMPTY), d, s);
  }

  private static Pattern<Varka> slotSum(Pattern<Varka> a, Pattern<Varka> b) {
    return Pattern.of(new Varka.SlotSum(IntList.EMPTY), a, b);
  }

  private static Pattern<Varka> field(String name, Pattern<Varka> d) {
    return Pattern.of(new Varka.Field(name, IntList.EMPTY), d);
  }

  private static Pattern<Varka> civil(Pattern<Varka> d) {
    return Pattern.of(new Varka.Civil(IntList.EMPTY), d);
  }

  /** The twenty rules. */
  static List<Rewrite<Varka, Void>> rules() {
    Pattern.Head<Varka> anyField = Pattern.binding(Varka.Field.class, "f", Varka.Field::name,
        (name, kids) -> new Varka.Field((String) name, kids));
    Pattern.Head<Varka> anyFieldOf = Pattern.binding(Varka.FieldOf.class, "f",
        Varka.FieldOf::name, (name, kids) -> new Varka.FieldOf((String) name, kids));
    return List.of(
        // dates
        Rewrite.of("fold-offsets", addDays(addDays(v("d"), v("a")), v("b")),
            addDays(v("d"), slotSum(v("a"), v("b")))),
        Rewrite.of("field-through-civil", Pattern.node(anyField, v("d")),
            Pattern.node(anyFieldOf, civil(v("d")))),
        Rewrite.of("civil-field-direct", Pattern.node(anyFieldOf, civil(v("d"))),
            Pattern.node(anyField, v("d"))),
        Rewrite.of("slotsum-comm", slotSum(v("a"), v("b")), slotSum(v("b"), v("a"))),
        Rewrite.of("slotsum-assoc", slotSum(slotSum(v("a"), v("b")), v("c")),
            slotSum(v("a"), slotSum(v("b"), v("c")))),
        // the algebra of + and *
        Rewrite.of("add-comm", add(v("a"), v("b")), add(v("b"), v("a"))),
        Rewrite.of("add-assoc", add(add(v("a"), v("b")), v("c")),
            add(v("a"), add(v("b"), v("c")))),
        Rewrite.of("add-assoc-rev", add(v("a"), add(v("b"), v("c"))),
            add(add(v("a"), v("b")), v("c"))),
        Rewrite.of("mul-comm", mul(v("a"), v("b")), mul(v("b"), v("a"))),
        Rewrite.of("mul-assoc", mul(mul(v("a"), v("b")), v("c")),
            mul(v("a"), mul(v("b"), v("c")))),
        Rewrite.of("mul-assoc-rev", mul(v("a"), mul(v("b"), v("c"))),
            mul(mul(v("a"), v("b")), v("c"))),
        Rewrite.of("add-0", add(v("x"), num(0)), v("x")),
        Rewrite.of("mul-1", mul(v("x"), num(1)), v("x")),
        Rewrite.of("mul-0", mul(v("x"), num(0)), num(0)),
        Rewrite.of("distribute", mul(v("a"), add(v("b"), v("c"))),
            add(mul(v("a"), v("b")), mul(v("a"), v("c")))),
        Rewrite.of("factor", add(mul(v("a"), v("b")), mul(v("a"), v("c"))),
            mul(v("a"), add(v("b"), v("c")))),
        Rewrite.of("add-same", add(v("x"), v("x")), mul(v("x"), num(2))),
        Rewrite.of("mul-2", mul(v("x"), num(2)), add(v("x"), v("x"))),
        // the fused form of a pair of fields
        Rewrite.of("months-def", add(mul(field("year", v("d")), num(12)), field("month", v("d"))),
            Pattern.of(new Varka.Months(IntList.EMPTY), v("d"))),
        Rewrite.of("months-expand", Pattern.of(new Varka.Months(IntList.EMPTY), v("d")),
            add(mul(field("year", v("d")), num(12)), field("month", v("d")))));
  }

  /** A saturated graph under a node limit, with its roots and report. */
  record Saturated(EGraph<Varka, Void> graph, IntList roots, RunReport report) {
  }

  static Saturated saturate(int nodeLimit) {
    return saturate(nodeLimit, Long.MAX_VALUE);
  }

  /** As {@link #saturate(int)}, stopped by a hook once the deadline has passed. */
  static Saturated saturate(int nodeLimit, long deadlineNanos) {
    EGraph<Varka, Void> g = EGraph.withoutAnalysis();
    IntList roots = addProjection(g);
    RunReport report = new Runner<>(g, rules(), RunLimits.DEFAULT.withNodes(nodeLimit),
        new BackoffScheduler<>())
        .withHook(graph -> System.nanoTime() > deadlineNanos
            ? java.util.Optional.of("deadline") : java.util.Optional.empty())
        .run();
    return new Saturated(g, roots, report);
  }

  /** The run's shape, for the results file: what the timings were timings of. */
  static String shape(int nodeLimit, long deadlineNanos) {
    long start = System.nanoTime();
    Saturated s = saturate(nodeLimit, deadlineNanos);
    double seconds = (System.nanoTime() - start) / 1e9;
    RunReport r = s.report();
    int nodes = s.graph().numNodes();
    int classes = s.graph().numClasses();
    long applied = r.iterations().stream().mapToLong(RunReport.Iteration::applied).sum();
    long unions = r.iterations().stream().mapToLong(RunReport.Iteration::unions).sum();
    return String.format(
        "node limit %d: %d iterations, %d nodes, %d classes (%.2f nodes per class), %d applied,"
            + " %d unions, stopped: %s, %.1f s in one run; %d roots, extractAll cost %.1f",
        nodeLimit, r.size(), nodes, classes, (double) nodes / classes, applied, unions,
        r.stop(), seconds, s.roots().size(),
        new Extractor<>(s.graph(), TABLE).extractAll(s.roots()).cost());
  }
}
