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
import java.util.Optional;

/**
 * jegg's side of the differential fuzzing against egg (#44, {@code dev/fuzz.sh}): one run of a
 * case, rendered as the line egg's side prints ({@code dev/fuzz/common.rs}) so the two compare as
 * strings: why the run stopped, the iterations egg would count, the nodes and classes at the
 * start of each iteration (read by a hook, the point egg's hook reads) and after the run.
 *
 * <p>A case is a rule set, a term and optionally goals. The rule sets are egg's: {@code prop-all},
 * {@code prop-contrapositive} and {@code prop-chain} for {@link PropRulesTest}'s language,
 * {@code math} and {@code math-75k} for {@link MathTest}'s; the goals stop the run as egg's
 * {@code test_fn!} does.
 */
final class FuzzRun {

  private FuzzRun() {
  }

  /**
   * One case: its rule set, its term, and the goals (patterns that must match at the term's class
   * to stop the run), none for a run to saturation or a limit.
   *
   * @param ruleset one of the rule sets above
   * @param term the term as an s-expression, whitespace collapsed
   * @param goals the goals, as s-expressions
   */
  record Case(String ruleset, String term, List<String> goals) {
    Case {
      term = term.replaceAll("\\s+", " ").trim();
      goals = goals.stream().map(g -> g.replaceAll("\\s+", " ").trim()).toList();
    }

    /** The line egg's driver reads: the rule set, the term and the goals, tab separated. */
    String line() {
      StringBuilder b = new StringBuilder(ruleset).append('\t').append(term);
      goals.forEach(g -> b.append('\t').append(g));
      return b.toString();
    }
  }

  /**
   * A case with the counts a ported test pins for egg's run of it.
   *
   * @param input the case
   * @param iterations egg's iterations
   * @param nodes the nodes in the classes after the run
   * @param classes the classes after the run
   */
  record Known(Case input, int iterations, int nodes, int classes) {
  }

  /**
   * What a result line says.
   *
   * @param stop why the run stopped
   * @param iterations the iterations egg counts
   * @param samples the nodes and classes at each iteration's start, as {@code n,e;n,e}
   * @param nodes the nodes after the run
   * @param classes the classes after the run
   * @param memo egg's memo size after the run, or -1 for jegg's line, which has none
   */
  record Result(String stop, int iterations, String samples, int nodes, int classes, int memo) {
    /** The result of a line {@code <STOP> <iterations> <samples> final <n>,<e> [memo <m>]}. */
    static Result parse(String line) {
      String[] t = line.trim().split(" ");
      int f = List.of(t).indexOf("final");
      String[] last = t[f + 1].split(",");
      return new Result(t[0], Integer.parseInt(t[1]), t[2], Integer.parseInt(last[0]),
          Integer.parseInt(last[1]), f + 3 < t.length ? Integer.parseInt(t[f + 3]) : -1);
    }

    List<String> sampleList() {
      return samples.equals("-") ? List.of() : List.of(samples.split(";"));
    }
  }

  /**
   * How two lines compare. {@code MEMO_LIMIT} is a difference egg's node limit explains.
   */
  enum Verdict { SAME, MEMO_LIMIT, BOTH_FAILED, DIVERGE, INCONCLUSIVE }

  /** The node limit of a rule set's runs. */
  static int nodeLimit(String ruleset) {
    return switch (ruleset) {
      case "math" -> RunLimits.DEFAULT.nodes();
      case "math-75k" -> 75_000;
      default -> PropRulesTest.LIMITS.nodes();
    };
  }

  /**
   * Compares egg's line with jegg's. egg panicking where jegg throws too is {@code BOTH_FAILED}
   * (a term that folds to {@code false} under the assumption that it is true is refused by both
   * as "Merged non-equal constants"); egg panicking where jegg runs, or egg's time limit, is
   * inconclusive, as the count would depend on the clock or there is no egg count; jegg throwing
   * where egg did not is a divergence; equal lines (egg's memo size aside) are the same.
   *
   * <p>egg checks its node limit against its memo, which keeps stale entries, where jegg checks
   * the distinct nodes, so egg can stop on a limit its nodes have not reached while jegg runs on.
   * That is {@code MEMO_LIMIT}, and only when explained: egg stopped on the node limit with its
   * nodes at or under it and its memo over it, jegg ran at least as long, and every iteration
   * start egg reached is jegg's too. The last iteration, which egg left part way, is then not
   * compared; anything else that differs is a divergence.
   *
   * @param egg egg's line
   * @param jegg jegg's line
   * @param nodeLimit the run's node limit
   * @return the verdict
   */
  static Verdict compare(String egg, String jegg, int nodeLimit) {
    if (egg.startsWith("PANIC")) {
      return jegg.startsWith("ERROR") ? Verdict.BOTH_FAILED : Verdict.INCONCLUSIVE;
    }
    if (egg.startsWith("TimeLimit")) {
      return Verdict.INCONCLUSIVE;
    }
    if (jegg.startsWith("ERROR")) {
      return Verdict.DIVERGE;
    }
    if (egg.replaceFirst(" memo -?\\d+$", "").equals(jegg)) {
      return Verdict.SAME;
    }
    Result e = Result.parse(egg);
    Result j = Result.parse(jegg);
    boolean explained = e.stop().equals("NodeLimit") && e.nodes() <= nodeLimit
        && e.memo() > nodeLimit && j.iterations() >= e.iterations()
        && j.sampleList().size() >= e.sampleList().size()
        && j.sampleList().subList(0, e.sampleList().size()).equals(e.sampleList());
    return explained ? Verdict.MEMO_LIMIT : Verdict.DIVERGE;
  }

  /** The cases of egg's math tests that run under the runner's own limits, with egg's counts. */
  static List<Known> knownMath() {
    List<Known> out = new ArrayList<>();
    for (MathTest.Case c : MathTest.CASES) {
      // egg's diff_power_harder runs with explanations on and an extra term: not this harness's.
      if (!c.extraTerms().isEmpty()) {
        continue;
      }
      String ruleset = c.limits().nodes() == 75_000 ? "math-75k" : "math";
      out.add(new Known(new Case(ruleset, c.start(), c.goals()), c.egg().iterations(),
          c.egg().nodes(), c.egg().classes()));
    }
    return out;
  }

  /** The two prop tests that run, with egg's counts: no goals, a run to saturation or a limit. */
  static List<Known> knownProp() {
    PropRulesTest.Case contrapositive = PropRulesTest.CONTRAPOSITIVE_CASE;
    PropRulesTest.Case chain = PropRulesTest.CHAIN_CASE;
    return List.of(
        new Known(new Case("prop-contrapositive", contrapositive.start(), List.of()),
            contrapositive.egg().iterations(), contrapositive.egg().nodes(),
            contrapositive.egg().classes()),
        new Known(new Case("prop-chain", chain.start(), List.of()), chain.egg().iterations(),
            chain.egg().nodes(), chain.egg().classes()));
  }

  /**
   * Runs the case on jegg and renders it as egg's driver does.
   *
   * @param c the case
   * @return the result line, or {@code ERROR <exception>} if jegg threw
   */
  static String run(Case c) {
    try {
      return switch (c.ruleset()) {
        case "prop-all", "prop-contrapositive", "prop-chain" -> prop(c);
        case "math", "math-75k" -> math(c);
        default -> throw new IllegalArgumentException("unknown rule set " + c.ruleset());
      };
    } catch (RuntimeException | StackOverflowError e) {
      return "ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
    }
  }

  private static String prop(Case c) {
    List<Rewrite<PropRulesTest.Prop, Boolean>> rules = switch (c.ruleset()) {
      case "prop-contrapositive" -> PropRulesTest.CONTRAPOSITIVE_CASE.rules();
      case "prop-chain" -> PropRulesTest.CHAIN_CASE.rules();
      default -> List.of(PropRulesTest.DEF_IMPLY, PropRulesTest.DEF_IMPLY_FLIP,
          PropRulesTest.DOUBLE_NEG, PropRulesTest.DOUBLE_NEG_FLIP, PropRulesTest.ASSOC_OR,
          PropRulesTest.DIST_AND_OR, PropRulesTest.DIST_OR_AND, PropRulesTest.COMM_OR,
          PropRulesTest.COMM_AND, PropRulesTest.LEM, PropRulesTest.OR_TRUE,
          PropRulesTest.AND_TRUE, PropRulesTest.CONTRAPOSITIVE, PropRulesTest.LEM_IMPLY);
    };
    EGraph<PropRulesTest.Prop, Boolean> g = new EGraph<>(PropRulesTest.CONSTANT_FOLD);
    int root = g.addTree(Term.parse(c.term()), PropRulesTest.BRIDGE);
    // egg's prove_something: the input is assumed true, which lem_imply's soundness needs.
    g.merge(root, g.add(new PropRulesTest.Prop.Bool(true)));
    g.rebuild();
    return execute(g, root, rules, PropRulesTest.LIMITS, List.of());
  }

  private static String math(Case c) {
    RunLimits limits = c.ruleset().equals("math-75k") ? RunLimits.DEFAULT.withNodes(75_000)
        : RunLimits.DEFAULT;
    EGraph<MathTest.Math, Double> g = new EGraph<>(MathTest.CONSTANT_FOLD);
    int root = g.addTree(Term.parse(c.term()), MathTest.BRIDGE);
    List<Pattern<MathTest.Math>> goals = c.goals().stream().map(MathTest::pattern).toList();
    return execute(g, root, MathTest.rules(), limits, goals);
  }

  private static <L extends Language<L>, D> String execute(EGraph<L, D> g, int root,
      List<Rewrite<L, D>> rules, RunLimits limits, List<Pattern<L>> goals) {
    List<String> samples = new ArrayList<>();
    Runner<L, D> runner = new Runner<>(g, rules, limits, new BackoffScheduler<>())
        .withHook(graph -> {
          samples.add(graph.numNodes() + "," + graph.numClasses());
          return Optional.empty();
        });
    if (!goals.isEmpty()) {
      runner.withHook(graph -> goals.stream().allMatch(
          p -> !Matcher.matchIn(graph, p, root, Subst.EMPTY).isEmpty())
              ? Optional.of("Proved all goals") : Optional.empty());
    }
    RunReport report = runner.run();
    String stop = switch (report.stop()) {
      case StopReason.Saturated s -> "Saturated";
      case StopReason.IterationLimit s -> "IterationLimit";
      case StopReason.NodeLimit s -> "NodeLimit";
      case StopReason.ClassLimit s -> "ClassLimit";
      case StopReason.Other s -> "Other";
    };
    // egg counts the iteration a hook stops as one more than the report here does.
    int iterations = report.size() + (report.stop() instanceof StopReason.Other ? 1 : 0);
    return stop + " " + iterations + " "
        + (samples.isEmpty() ? "-" : String.join(";", samples)) + " final " + g.numNodes()
        + "," + g.numClasses();
  }
}
