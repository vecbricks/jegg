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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The saturation loop of the {@link Runner}, written out for the measurement so that it can
 * run in two modes: <em>deferred</em>, one {@code rebuild} per iteration as the runner does,
 * and <em>eager</em>, a {@code rebuild} after every application that merged, which is how the
 * paper's comparison (its Figure 7) ran. Everything else is the runner's: the scheduler, the
 * limits, the stop rule and the goal check before each iteration. A deadline bounds a run.
 */
final class Saturation {

  enum Mode { DEFERRED, EAGER }

  /** What a run did: how long, how many iterations and repaired classes, how it ended. */
  record Outcome(Mode mode, int iterations, long repaired, int nodes, int classes,
      String stop, boolean timedOut) {
  }

  private Saturation() {
  }

  static <L extends Language<L>, D> Outcome run(Mode mode, EGraph<L, D> graph,
      List<Rewrite<L, D>> rules, RunLimits limits, Scheduler<L, D> scheduler,
      Predicate<EGraph<L, D>> proved, long deadlineNanos) {
    long repaired = graph.rebuild();
    scheduler.reset();
    int iteration = 0;
    String stop = null;
    boolean timedOut = false;
    while (stop == null) {
      if (graph.numNodes() > limits.nodes()) {
        stop = "NodeLimit";
        break;
      }
      if (proved.test(graph)) {
        stop = "Proved all goals";
        break;
      }
      int nodesBefore = graph.numNodes();
      int classesBefore = graph.numClasses();
      iteration++;
      List<List<Matcher.Match>> matches = new ArrayList<>(rules.size());
      for (int i = 0; i < rules.size(); i++) {
        matches.add(scheduler.search(iteration, i, rules.get(i), graph));
      }
      int unions = 0;
      for (int i = 0; i < rules.size() && !timedOut; i++) {
        for (Matcher.Match m : matches.get(i)) {
          int changed = rules.get(i).apply(graph, m).orElse(0);
          if (changed > 0) {
            unions += changed;
            if (mode == Mode.EAGER) {
              repaired += graph.rebuild();
            }
          }
          if (System.nanoTime() > deadlineNanos) {
            timedOut = true;
            break;
          }
        }
      }
      repaired += graph.rebuild();
      if (timedOut) {
        stop = "TimedOut";
      } else if (graph.numNodes() > limits.nodes()) {
        stop = "NodeLimit";
      } else if (unions == 0 && scheduler.canStop(iteration)
          && graph.numNodes() == nodesBefore && graph.numClasses() == classesBefore) {
        stop = "Saturated";
      } else if (iteration >= limits.iterations()) {
        stop = "IterationLimit";
      }
    }
    return new Outcome(mode, iteration, repaired, graph.numNodes(), graph.numClasses(), stop,
        timedOut);
  }

  /** The runs of the ported suites, by name: the prop cases, the lambda cases, the math cases. */
  static Map<String, Workload> suites() {
    Map<String, Workload> out = new LinkedHashMap<>();
    for (PropRulesTest.Case c : PropRulesTest.CASES) {
      out.put(c.name(), (mode, deadline) -> {
        EGraph<PropRulesTest.Prop, Void> g = EGraph.withoutAnalysis();
        int root = g.addTree(Term.parse(c.start()), PropRulesTest.BRIDGE);
        List<Term> goals = c.goals().stream().map(Term::parse).toList();
        // prop's tests check their goals after the run, not during it, so the run goes to
        // saturation or a limit, as theirs do.
        Outcome o = run(mode, g, c.rules(), PropRulesTest.LIMITS, new BackoffScheduler<>(),
            graph -> false, deadline);
        for (Term goal : goals) {
          java.util.OptionalInt id = g.lookupTree(goal, PropRulesTest.BRIDGE);
          if (id.isEmpty() || g.find(id.getAsInt()) != g.find(root)) {
            throw new IllegalStateException(c.name() + " did not prove " + goal);
          }
        }
        return o;
      });
    }
    for (LambdaTest.Case c : LambdaTest.CASES) {
      out.put(c.name(), (mode, deadline) -> {
        EGraph<LambdaTest.Lambda, LambdaTest.Data> g = new EGraph<>(LambdaTest.ANALYSIS);
        int root = g.addTree(Term.parse(c.start()), LambdaTest.BRIDGE);
        List<Pattern<LambdaTest.Lambda>> goals =
            c.goals().stream().map(LambdaTest::pattern).toList();
        Outcome o = run(mode, g, LambdaTest.rules(), c.limits(), new BackoffScheduler<>(),
            graph -> goals.stream()
                .allMatch(p -> !Matcher.matchIn(graph, p, root, Subst.EMPTY).isEmpty()),
            deadline);
        boolean proved = o.stop().equals("Proved all goals");
        if (!o.timedOut() && proved != c.proves()) {
          throw new IllegalStateException(c.name() + " ended " + o.stop() + ", egg "
              + (c.proves() ? "proves it" : "does not"));
        }
        return o;
      });
    }
    for (MathTest.Case c : MathTest.CASES) {
      out.put(c.name(), (mode, deadline) -> {
        EGraph<MathTest.Math, Double> g = new EGraph<>(MathTest.CONSTANT_FOLD);
        int root = g.addTree(Term.parse(c.start()), MathTest.BRIDGE);
        for (String extra : c.extraTerms()) {
          g.addTree(Term.parse(extra), MathTest.BRIDGE);
        }
        List<Pattern<MathTest.Math>> goals = c.goals().stream().map(MathTest::pattern).toList();
        Outcome o = run(mode, g, MathTest.rules(), c.limits(), new BackoffScheduler<>(),
            graph -> goals.stream().allMatch(p -> MathTest.proved(graph, root, p)), deadline);
        boolean proved = o.stop().equals("Proved all goals");
        if (!o.timedOut() && proved != c.proves()) {
          throw new IllegalStateException(c.name() + " ended " + o.stop() + ", egg "
              + (c.proves() ? "proves it" : "does not"));
        }
        return o;
      });
    }
    return out;
  }

  /** The runs that grow past a few thousand nodes, where eager rebuilding takes minutes. */
  static boolean large(String suite) {
    return suite.equals("prove_fold") || suite.equals("lambda_fib")
        || suite.equals("lambda_function_repeat");
  }

  /** A suite's run, from a fresh graph, in a mode and under a deadline. */
  @FunctionalInterface
  interface Workload {
    Outcome run(Mode mode, long deadlineNanos);
  }

  static Optional<Workload> suite(String name) {
    return Optional.ofNullable(suites().get(name));
  }
}
