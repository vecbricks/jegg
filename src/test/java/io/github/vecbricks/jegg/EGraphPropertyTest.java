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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Properties of the e-graph on random programs of adds, merges and rebuilds (#75): the
 * invariants hold after every rebuild, with an analysis whose {@code modify} adds and merges
 * (and one that prunes too); every order of rebuilding gives the same partition and the same
 * facts; the partition equals a naive congruence closure computed independently; and a rule that
 * unites every class with a constant leaves one class. hegg's properties are the model; the
 * analysis, the naive closure and the modes are what they lack.
 */
class EGraphPropertyTest {

  /** A tiny language: leaves {@code x y z T}, and {@code and}, {@code or}, {@code not}. */
  record Op(String name, IntList children) implements Language<Op> {
    @Override
    public Op withChildren(IntList c) {
      return new Op(name, c);
    }
  }

  static final Op TRUE = new Op("T", IntList.EMPTY);

  /**
   * Whether a class is known true: {@code T} is, {@code and} is if all its children are,
   * {@code or} if any is, {@code not} and the other leaves never are. Monotone, and it has no
   * conflicts, so random merges cannot contradict it. {@code modify} adds {@code T} and merges
   * it in, as constant folding does, and with {@code prune} drops the class's other nodes, as
   * math's does.
   */
  static Analysis<Op, Boolean> truth(boolean prune) {
    return new Analysis<>() {
      @Override
      public Boolean make(EGraph<Op, Boolean> g, Op node) {
        IntList kids = node.children();
        return switch (node.name()) {
          case "T" -> true;
          case "and" -> kids.size() > 0 && allKnown(g, kids);
          case "or" -> anyKnown(g, kids);
          default -> false;
        };
      }

      private boolean allKnown(EGraph<Op, Boolean> g, IntList kids) {
        for (int i = 0; i < kids.size(); i++) {
          if (!g.data(kids.get(i))) {
            return false;
          }
        }
        return true;
      }

      private boolean anyKnown(EGraph<Op, Boolean> g, IntList kids) {
        for (int i = 0; i < kids.size(); i++) {
          if (g.data(kids.get(i))) {
            return true;
          }
        }
        return false;
      }

      @Override
      public Boolean join(Boolean a, Boolean b) {
        return a || b;
      }

      @Override
      public void modify(EGraph<Op, Boolean> g, int id) {
        if (g.data(id)) {
          int root = g.merge(id, g.add(TRUE));
          if (prune) {
            g.retainNodes(root, n -> n.children().isEmpty());
          }
        }
      }
    };
  }

  /** One step of a program: add a leaf or a node over earlier results, merge two, rebuild. */
  record Step(String kind, String name, int a, int b) {
  }

  /**
   * A random program, in terms of the results of its own adds (indices), not of raw ids. A third
   * of the merges are chosen to build what random ones rarely do: a node merged with one of its
   * own children (a class holding a node over itself, as {@code and(e, T) = e} does) and a result
   * merged with the constant, which is where an analysis fact grows during a repair.
   */
  static List<Step> program(long seed) {
    Random rnd = new Random(seed);
    List<Step> steps = new ArrayList<>();
    List<int[]> kids = new ArrayList<>();
    int constant = -1;
    int length = 5 + rnd.nextInt(40);
    for (int i = 0; i < length; i++) {
      int pool = kids.size();
      int dice = rnd.nextInt(10);
      if (pool == 0 || dice < 2) {
        String[] leaves = {"x", "y", "z", "x", "y", "T"};
        String leaf = leaves[rnd.nextInt(leaves.length)];
        steps.add(new Step("add", leaf, -1, -1));
        kids.add(new int[] {-1, -1});
        if (leaf.equals("T") && constant < 0) {
          constant = pool;
        }
      } else if (dice < 5) {
        String[] ops = {"and", "or", "not"};
        String op = ops[rnd.nextInt(ops.length)];
        int a = rnd.nextInt(pool);
        int b = op.equals("not") ? -1 : rnd.nextInt(pool);
        steps.add(new Step("add", op, a, b));
        kids.add(new int[] {a, b});
      } else if (dice < 9) {
        int a = rnd.nextInt(pool);
        int b = rnd.nextInt(pool);
        int pick = rnd.nextInt(6);
        if (pick == 0 && kids.get(a)[0] >= 0) {
          b = kids.get(a)[rnd.nextInt(2) == 0 || kids.get(a)[1] < 0 ? 0 : 1];
        } else if (pick == 1 && constant >= 0) {
          b = constant;
        }
        steps.add(new Step("merge", null, a, b));
      } else {
        steps.add(new Step("rebuild", null, -1, -1));
      }
    }
    return steps;
  }

  /** How a program is run: where the rebuilds go. */
  enum Mode { AT_THE_END, WHERE_THE_PROGRAM_SAYS, AFTER_EVERY_MERGE, AFTER_EVERY_THIRD_MERGE }

  /** What a run left: each result's class, the fact of each (null without an analysis). */
  record Outcome<D>(EGraph<Op, D> graph, int[] classOf, List<D> facts) {
    boolean same(int i, int j) {
      return classOf[i] == classOf[j];
    }
  }

  static <D> Outcome<D> run(List<Step> steps, Mode mode, Supplier<EGraph<Op, D>> make,
      boolean checkAnalysis) {
    EGraph<Op, D> g = make.get();
    List<Integer> pool = new ArrayList<>();
    int merges = 0;
    for (Step s : steps) {
      switch (s.kind()) {
        case "add" -> {
          IntList kids = s.a() < 0 ? IntList.EMPTY : s.b() < 0 ? IntList.of(pool.get(s.a()))
              : IntList.of(pool.get(s.a()), pool.get(s.b()));
          pool.add(g.add(new Op(s.name(), kids)));
        }
        case "merge" -> {
          g.merge(pool.get(s.a()), pool.get(s.b()));
          merges++;
          if (mode == Mode.AFTER_EVERY_MERGE
              || mode == Mode.AFTER_EVERY_THIRD_MERGE && merges % 3 == 0) {
            rebuildAndCheck(g, checkAnalysis);
          }
        }
        default -> {
          if (mode == Mode.WHERE_THE_PROGRAM_SAYS) {
            rebuildAndCheck(g, checkAnalysis);
          }
        }
      }
    }
    rebuildAndCheck(g, checkAnalysis);
    int[] classOf = new int[pool.size()];
    List<D> facts = new ArrayList<>();
    for (int i = 0; i < classOf.length; i++) {
      classOf[i] = g.find(pool.get(i));
      facts.add(g.data(pool.get(i)));
    }
    return new Outcome<>(g, classOf, facts);
  }

  private static <D> void rebuildAndCheck(EGraph<Op, D> g, boolean checkAnalysis) {
    g.rebuild();
    g.checkInvariants();
    if (checkAnalysis) {
      g.checkAnalysisInvariant();
    }
  }

  private static <D> void samePartitionAndFacts(Outcome<D> a, Outcome<D> b, String why) {
    int n = a.classOf().length;
    for (int i = 0; i < n; i++) {
      assertEquals(a.facts().get(i), b.facts().get(i), why + ": the fact of result " + i);
      for (int j = i + 1; j < n; j++) {
        assertEquals(a.same(i, j), b.same(i, j), why + ": results " + i + " and " + j);
      }
    }
  }

  private void everyModeGivesTheSameGraph(boolean prune) {
    Supplier<EGraph<Op, Boolean>> make = () -> new EGraph<>(truth(prune));
    for (long seed = 1; seed <= 1000; seed++) {
      List<Step> steps = program(seed);
      Outcome<Boolean> atTheEnd = run(steps, Mode.AT_THE_END, make, true);
      for (Mode mode : List.of(Mode.WHERE_THE_PROGRAM_SAYS, Mode.AFTER_EVERY_MERGE,
          Mode.AFTER_EVERY_THIRD_MERGE)) {
        samePartitionAndFacts(atTheEnd, run(steps, mode, make, true),
            "seed " + seed + ", " + mode + " against at the end");
      }
    }
  }

  @Test
  void theInvariantsHoldAfterEveryRebuildAndEveryOrderOfRebuildingGivesOneGraph() {
    everyModeGivesTheSameGraph(false);
  }

  @Test
  void theSameHoldsWhenModifyPrunesTheClassToItsLeavesToo() {
    everyModeGivesTheSameGraph(true);
  }

  @Test
  void theFactOfEveryResultIsKnownTrueExactlyWhenItsClassHoldsTheConstant() {
    // modify merges a class known true with T, so after a rebuild the facts say which results
    // are in T's class, whatever the order of rebuilding.
    for (long seed = 1; seed <= 200; seed++) {
      Outcome<Boolean> o = run(program(seed), Mode.AFTER_EVERY_MERGE,
          () -> new EGraph<>(truth(false)), true);
      int truthClass = o.graph().lookup(TRUE).isPresent() ? o.graph().find(
          o.graph().lookup(TRUE).getAsInt()) : -1;
      for (int i = 0; i < o.classOf().length; i++) {
        assertEquals(o.facts().get(i), o.classOf()[i] == truthClass,
            "seed " + seed + ", result " + i);
      }
    }
  }

  /**
   * The partition a naive congruence closure computes over a program: a union-find over the
   * results, the program's merges applied, then "two nodes with one operator whose children
   * are equivalent are equivalent" repeated until nothing changes. No hashcons, no worklist, no
   * parents: nothing of what the e-graph does to be fast.
   */
  static int[] naiveClosure(List<Step> steps) {
    List<Step> nodes = new ArrayList<>();
    int[] parent = new int[steps.size()];
    for (int i = 0; i < parent.length; i++) {
      parent[i] = i;
    }
    List<int[]> merges = new ArrayList<>();
    int results = 0;
    List<Integer> nodeResult = new ArrayList<>();
    for (Step s : steps) {
      if (s.kind().equals("add")) {
        nodes.add(s);
        nodeResult.add(results++);
      } else if (s.kind().equals("merge")) {
        merges.add(new int[] {s.a(), s.b()});
      }
    }
    int[] uf = new int[results];
    for (int i = 0; i < results; i++) {
      uf[i] = i;
    }
    for (int[] m : merges) {
      union(uf, m[0], m[1]);
    }
    boolean changed = true;
    while (changed) {
      changed = false;
      for (int i = 0; i < nodes.size(); i++) {
        for (int j = i + 1; j < nodes.size(); j++) {
          Step p = nodes.get(i);
          Step q = nodes.get(j);
          if (find(uf, i) != find(uf, j) && p.name().equals(q.name())
              && (p.a() < 0) == (q.a() < 0) && (p.b() < 0) == (q.b() < 0)
              && (p.a() < 0 || find(uf, p.a()) == find(uf, q.a()))
              && (p.b() < 0 || find(uf, p.b()) == find(uf, q.b()))) {
            union(uf, i, j);
            changed = true;
          }
        }
      }
    }
    int[] roots = new int[results];
    for (int i = 0; i < results; i++) {
      roots[i] = find(uf, i);
    }
    return roots;
  }

  private static int find(int[] uf, int x) {
    while (uf[x] != x) {
      x = uf[x];
    }
    return x;
  }

  private static void union(int[] uf, int a, int b) {
    int ra = find(uf, a);
    int rb = find(uf, b);
    if (ra != rb) {
      uf[Math.max(ra, rb)] = Math.min(ra, rb);
    }
  }

  @Test
  void theRebuiltPartitionIsTheNaiveCongruenceClosureOfTheProgram() {
    // Without an analysis, nothing but the merges and congruence unites classes, so the
    // partition is exactly the closure a naive fixpoint computes: hegg's TODO, made a test.
    for (long seed = 1; seed <= 1000; seed++) {
      List<Step> steps = program(seed);
      int[] expected = naiveClosure(steps);
      for (Mode mode : List.of(Mode.AT_THE_END, Mode.AFTER_EVERY_MERGE)) {
        Outcome<Void> o = run(steps, mode, EGraph::withoutAnalysis, false);
        for (int i = 0; i < expected.length; i++) {
          for (int j = i + 1; j < expected.length; j++) {
            assertEquals(expected[i] == expected[j], o.same(i, j),
                "seed " + seed + ", " + mode + ": results " + i + " and " + j + " in " + steps);
          }
        }
      }
    }
  }

  @Test
  void aRuleThatUnitesEveryClassWithAConstantLeavesOneClass() {
    // hegg's fold-everything: ?x => T on every class, so every class, congruence and cascade
    // included, ends in the constant's, whatever the program built.
    Rewrite<Op, Boolean> fold = Rewrite.of("fold", Pattern.var("x"),
        Pattern.of(TRUE));
    for (long seed = 1; seed <= 300; seed++) {
      for (boolean analysed : new boolean[] {false, true}) {
        EGraph<Op, Boolean> g = analysed ? new EGraph<>(truth(seed % 2 == 0))
            : new EGraph<>(new Analysis<Op, Boolean>() {
              @Override
              public Boolean make(EGraph<Op, Boolean> graph, Op node) {
                return null;
              }

              @Override
              public Boolean join(Boolean a, Boolean b) {
                return null;
              }
            });
        Outcome<Boolean> built = run(program(seed), Mode.AT_THE_END, () -> g, analysed);
        RunReport report = Runner.of(g, List.of(fold)).run();
        assertEquals(1, g.numClasses(), "seed " + seed + " analysed " + analysed + "\n" + report);
        assertTrue(report.stop() instanceof StopReason.Saturated, report.toString());
        g.checkInvariants();
        if (analysed) {
          g.checkAnalysisInvariant();
        }
        assertEquals(built.classOf().length, built.facts().size());
      }
    }
  }
}
