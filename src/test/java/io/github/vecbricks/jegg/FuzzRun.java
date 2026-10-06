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
 * {@code math} and {@code math-75k} for {@link MathTest}'s, and {@code prop:a,b} or {@code
 * math:a,b} for the named rules of either, to find which of them a divergence needs; the goals
 * stop the run as egg's {@code test_fn!} does.
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
      return samplesOf(samples);
    }
  }

  /**
   * How two lines compare. {@code NODE_LIMIT} is a difference that a node-limit stop explains.
   */
  enum Verdict { SAME, NODE_LIMIT, BOTH_FAILED, DIVERGE, INCONCLUSIVE }

  private static List<String> samplesOf(String samples) {
    return samples.equals("-") ? List.of() : List.of(samples.split(";"));
  }

  /**
   * Compares egg's line with jegg's. egg panicking where jegg throws too is {@code BOTH_FAILED}
   * (a term that folds to {@code false} under the assumption that it is true is refused by both
   * as "Merged non-equal constants"); egg panicking where jegg runs, or egg's time limit, is
   * inconclusive, as the count would depend on the clock or there is no egg count; jegg throwing
   * where egg did not is a divergence, unless it is explained as below; equal lines (egg's memo
   * size aside) are the same.
   *
   * <p>A stop on the node limit is not comparable past the iteration it came in, for two
   * reasons that are not defects. The limit is checked between rules, against the size then,
   * which is transient: before the rebuild it counts the stale entries that merges leave, and how
   * many there are depends on the order the iteration's matches were applied in, which is egg's
   * (the order of its classes) and jegg's (class id, then node insertion) and differs. And egg
   * checks its memo, which keeps stale entries for good, where jegg checks the distinct nodes.
   * So when one side stopped on the node limit, in the iteration that ended its run, and every
   * iteration start it reached is the other side's too, with the other side having run at least
   * as long, the verdict is {@code NODE_LIMIT}: the same up to that stop. The partial last
   * iteration is not compared, and what follows it on the side that ran on cannot be. Anything
   * else that differs is a divergence.
   *
   * @param egg egg's line
   * @param jegg jegg's line
   * @return the verdict
   */
  static Verdict compare(String egg, String jegg) {
    if (egg.startsWith("PANIC")) {
      return jegg.startsWith("ERROR") ? Verdict.BOTH_FAILED : Verdict.INCONCLUSIVE;
    }
    if (egg.startsWith("TimeLimit")) {
      return Verdict.INCONCLUSIVE;
    }
    if (jegg.startsWith("ERROR")) {
      // jegg threw where egg did not: a divergence, unless jegg threw the contradiction that
      // the assumption that a prop term is true can lead to ("Merged non-equal constants", which
      // egg raises too, as a panic) and egg stopped on the node limit in the iteration jegg threw
      // in or before it, so that jegg ran into it past where egg stopped. Any other error is
      // jegg's own, wherever it came.
      Result e = Result.parse(egg);
      int after = jegg.lastIndexOf(" after ");
      List<String> js = after < 0 ? List.of()
          : samplesOf(jegg.substring(after + " after ".length()));
      List<String> es = e.sampleList();
      boolean ranPastEggsStop = jegg.contains("Merged non-equal constants")
          && e.stop().equals("NodeLimit") && js.size() >= es.size()
          && js.subList(0, es.size()).equals(es);
      return ranPastEggsStop ? Verdict.NODE_LIMIT : Verdict.DIVERGE;
    }
    if (egg.replaceFirst(" memo -?\\d+$", "").equals(jegg)) {
      return Verdict.SAME;
    }
    Result e = Result.parse(egg);
    Result j = Result.parse(jegg);
    List<String> es = e.sampleList();
    List<String> js = j.sampleList();
    boolean eggStopped = e.stop().equals("NodeLimit") && es.size() <= js.size()
        && js.subList(0, es.size()).equals(es);
    boolean jeggStopped = j.stop().equals("NodeLimit") && js.size() <= es.size()
        && es.subList(0, js.size()).equals(js);
    return eggStopped || jeggStopped ? Verdict.NODE_LIMIT : Verdict.DIVERGE;
  }

  /**
   * A case with the line egg's own run of it printed, for the cases whose point is how the
   * backoff scheduler behaves, not what a term saturates to (#75): the threshold, the ban length,
   * the fast-forward when only banned rules remain, and a conditional rule's structural matches,
   * which egg counts toward the ban whether or not their condition holds (hegg's {@code T51}
   * expects the opposite; it describes hegg). Each was run through egg 73975c9 by
   * {@code dev/fuzz.sh --scenarios}, which checks the pins again.
   *
   * @param input the case, its rule set carrying the scheduler's parameters ({@code @limit,ban})
   * @param egg egg's result line, without its memo size
   */
  record Pinned(Case input, String egg) {
  }

  /** The scheduler scenarios, in the languages of {@link PropRulesTest} and {@link MathTest}. */
  static List<Pinned> schedulerScenarios() {
    return List.of(
        new Pinned(new Case("math:assoc-add@1,30", "(+ a (+ b (+ c d)))", List.of()),
            "Saturated 7 7,7;7,7;11,9;11,9;15,11;15,11;14,10 final 14,10"),
        new Pinned(new Case("math:cancel-div@1,5", "(+ (/ 0 0) (/ x x))", List.of()),
            "Saturated 3 5,5;5,5;5,5 final 5,5"),
        new Pinned(new Case("math:assoc-add,comm-add@2,3", "(+ a (+ b (+ c (+ d e))))", List.of()),
            "Saturated 16 9,9;9,9;19,12;19,12;19,12;45,22;45,22;45,22;98,35;98,35;162,39;"
            + "162,39;183,35;183,35;188,32;185,31 final 185,31"),
        new Pinned(new Case("math:comm-add@3,4", "(+ a (+ b (+ c d)))", List.of()),
            "Saturated 3 7,7;10,7;10,7 final 10,7"),
        new Pinned(new Case(
            "math:assoc-add,comm-add,add-zero@1,2", "(+ (+ a 0) (+ b 0))", List.of()),
            "Saturated 10 6,6;8,7;8,7;8,7;8,7;11,4;11,4;11,4;11,4;11,4 final 11,4"),
        new Pinned(new Case("prop-all@3,2", "(| (& x y) (-> z w))", List.of()),
            "IterationLimit 20 8,7;14,9;25,14;31,17;31,17;45,25;45,25;49,27;67,39;73,43;"
            + "73,43;73,43;93,43;93,43;117,55;117,55;117,55;117,55;117,55;"
            + "147,67 final 151,67"),
        new Pinned(new Case("math:assoc-add@1,1", "(+ a (+ b (+ c d)))", List.of()),
            "Saturated 7 7,7;7,7;11,9;11,9;15,11;15,11;14,10 final 14,10"),
        new Pinned(new Case("math:cancel-div,pow0@1,5", "(+ (/ x x) (pow y 0))", List.of()),
            "Saturated 2 6,6;5,5 final 5,5"),
        new Pinned(new Case("math@10,3", "(+ x (+ x (+ x x)))", List.of()),
            "Saturated 9 4,4;17,6;28,7;30,7;30,7;35,9;36,9;36,9;36,9 final 36,9"),
        new Pinned(new Case("math@1,1", "(* (+ x 3) (+ x 1))", List.of()),
            "IterationLimit 30 6,6;10,8;10,7;16,10;16,10;16,10;22,10;27,10;27,10;31,12;"
            + "31,12;31,12;48,12;48,12;48,12;52,14;56,14;56,14;56,14;56,14;72,13;72,13;"
            + "72,13;72,13;72,13;72,13;72,13;72,13;72,13;101,20 final 136,27"),
        new Pinned(new Case("math:cancel-div@2,5", "(+ (/ 0 0) (/ x x))", List.of()),
            "Saturated 2 5,5;5,5 final 5,5"),
        new Pinned(new Case("math:cancel-div@1,5", "(/ 0 0)", List.of()),
            "Saturated 1 2,2 final 2,2"),
        new Pinned(new Case("math:d-constant@1,5", "(+ (d 3 y) (d x z))", List.of()),
            "Saturated 3 7,7;7,7;7,7 final 7,7"),
        new Pinned(new Case("math:d-constant,d-variable@1,3", "(+ (d x x) (d x y))", List.of()),
            "Saturated 4 5,5;5,5;5,5;4,4 final 4,4"));
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
    validate(c.ruleset());
    Parsed parsed = parse(c.ruleset());
    Case base = new Case(parsed.base(), c.term(), c.goals());
    try {
      if (parsed.base().startsWith("prop")) {
        return prop(base, parsed);
      }
      if (parsed.base().startsWith("math")) {
        return math(base, parsed);
      }
      throw new IllegalArgumentException("unknown rule set " + c.ruleset());
    } catch (RuntimeException | StackOverflowError e) {
      return "ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
    }
  }

  /** Every rule of egg's {@code prop.rs}, in the order of {@code dev/fuzz/prop_run.rs}. */
  private static List<Rewrite<PropRulesTest.Prop, Boolean>> allProp() {
    return List.of(PropRulesTest.DEF_IMPLY, PropRulesTest.DEF_IMPLY_FLIP,
        PropRulesTest.DOUBLE_NEG, PropRulesTest.DOUBLE_NEG_FLIP, PropRulesTest.ASSOC_OR,
        PropRulesTest.DIST_AND_OR, PropRulesTest.DIST_OR_AND, PropRulesTest.COMM_OR,
        PropRulesTest.COMM_AND, PropRulesTest.LEM, PropRulesTest.OR_TRUE, PropRulesTest.AND_TRUE,
        PropRulesTest.CONTRAPOSITIVE, PropRulesTest.LEM_IMPLY);
  }

  private static String prop(Case c, Parsed scheduling) {
    List<Rewrite<PropRulesTest.Prop, Boolean>> all = allProp();
    List<Rewrite<PropRulesTest.Prop, Boolean>> rules = switch (c.ruleset()) {
      case "prop-contrapositive" -> PropRulesTest.CONTRAPOSITIVE_CASE.rules();
      case "prop-chain" -> PropRulesTest.CHAIN_CASE.rules();
      case "prop-all" -> all;
      default -> named(all, c.ruleset(), "prop:");
    };
    EGraph<PropRulesTest.Prop, Boolean> g = new EGraph<>(PropRulesTest.CONSTANT_FOLD);
    int root = g.addTree(Term.parse(c.term()), PropRulesTest.BRIDGE);
    // egg's prove_something: the input is assumed true, which lem_imply's soundness needs.
    g.merge(root, g.add(new PropRulesTest.Prop.Bool(true)));
    g.rebuild();
    return execute(g, root, rules, PropRulesTest.LIMITS, List.of(), scheduling);
  }

  private static String math(Case c, Parsed scheduling) {
    RunLimits limits = c.ruleset().equals("math-75k") ? RunLimits.DEFAULT.withNodes(75_000)
        : RunLimits.DEFAULT;
    EGraph<MathTest.Math, Double> g = new EGraph<>(MathTest.CONSTANT_FOLD);
    int root = g.addTree(Term.parse(c.term()), MathTest.BRIDGE);
    List<Pattern<MathTest.Math>> goals = c.goals().stream().map(MathTest::pattern).toList();
    List<Rewrite<MathTest.Math, Double>> rules = c.ruleset().startsWith("math:")
        ? named(MathTest.rules(), c.ruleset(), "math:") : MathTest.rules();
    return execute(g, root, rules, limits, goals, scheduling);
  }

  /**
   * A rule set with the backoff scheduler's parameters: {@code <rule set>@<limit>,<ban>} runs it
   * under a scheduler with that match limit and ban length (both positive), as egg's
   * {@code BackoffScheduler::default().with_initial_match_limit(limit).with_ban_length(ban)};
   * without the suffix the defaults, a limit of 1000 and a ban of 5.
   *
   * @param base the rule set without the suffix
   * @param matchLimit the match limit, 0 for the default
   * @param banLength the ban length, 0 for the default
   */
  record Parsed(String base, int matchLimit, int banLength) {
  }

  static Parsed parse(String ruleset) {
    int at = ruleset.indexOf('@');
    if (at < 0) {
      return new Parsed(ruleset, 0, 0);
    }
    String[] params = ruleset.substring(at + 1).split(",");
    try {
      int limit = Integer.parseInt(params[0]);
      int ban = Integer.parseInt(params[1]);
      if (params.length != 2 || limit < 1 || ban < 1) {
        throw new IllegalArgumentException("the match limit and the ban must be positive");
      }
      return new Parsed(ruleset.substring(0, at), limit, ban);
    } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
      throw new IllegalArgumentException("rule set " + ruleset
          + ": the suffix is @<match limit>,<ban length>", e);
    }
  }

  /**
   * Refuses a rule set that is not one of the named ones or a {@code prop:}/{@code math:} list of
   * rules that exist, as a mistyped name would otherwise run a smaller rule set, or another
   * language's, and compare the wrong thing.
   *
   * @param ruleset the rule set of a case
   * @throws IllegalArgumentException if it is unknown, or a list names a rule that does not exist
   */
  static void validate(String full) {
    String ruleset = parse(full).base();
    switch (ruleset) {
      case "prop-all", "prop-contrapositive", "prop-chain", "math", "math-75k" -> {
        return;
      }
      default -> {
      }
    }
    if (ruleset.startsWith("prop:")) {
      checkNames(ruleset, "prop:", allProp().stream().map(Rewrite::name).toList());
    } else if (ruleset.startsWith("math:")) {
      checkNames(ruleset, "math:", MathTest.rules().stream().map(Rewrite::name).toList());
    } else {
      throw new IllegalArgumentException("unknown rule set " + ruleset);
    }
  }

  private static void checkNames(String ruleset, String prefix, List<String> known) {
    for (String name : ruleset.substring(prefix.length()).split(",")) {
      if (!known.contains(name)) {
        throw new IllegalArgumentException("rule set " + ruleset + " names " + name
            + ", which is not one of " + known);
      }
    }
  }

  /** The rules of {@code all} that a {@code prefix:name,name} rule set names, in their order. */
  private static <L extends Language<L>, D> List<Rewrite<L, D>> named(List<Rewrite<L, D>> all,
      String ruleset, String prefix) {
    if (!ruleset.startsWith(prefix)) {
      throw new IllegalArgumentException("unknown rule set " + ruleset);
    }
    List<String> names = List.of(ruleset.substring(prefix.length()).split(","));
    return all.stream().filter(r -> names.contains(r.name())).toList();
  }

  private static <L extends Language<L>, D> String execute(EGraph<L, D> g, int root,
      List<Rewrite<L, D>> rules, RunLimits limits, List<Pattern<L>> goals, Parsed scheduling) {
    List<String> samples = new ArrayList<>();
    Scheduler<L, D> scheduler = scheduling.matchLimit() == 0 ? new BackoffScheduler<>()
        : new BackoffScheduler<>(scheduling.matchLimit(), scheduling.banLength());
    Runner<L, D> runner = new Runner<>(g, rules, limits, scheduler)
        .withHook(graph -> {
          samples.add(graph.numNodes() + "," + graph.numClasses());
          return Optional.empty();
        });
    if (!goals.isEmpty()) {
      runner.withHook(graph -> goals.stream().allMatch(
          p -> !Matcher.matchIn(graph, p, root, Subst.EMPTY).isEmpty())
              ? Optional.of("Proved all goals") : Optional.empty());
    }
    RunReport report;
    try {
      report = runner.run();
    } catch (RuntimeException | StackOverflowError e) {
      // The iteration starts reached, so that an error past where egg stopped can be told from
      // one before it.
      return "ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage() + " after "
          + (samples.isEmpty() ? "-" : String.join(";", samples));
    }
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
