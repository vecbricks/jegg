/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * Runs a rules file and a term through {@code prop}'s or {@code math}'s language without
 * writing Java (#39 item 6, #48): {@code dev/explore.sh <prop|math> <rules-file> '<term>'
 * [--limits i,n,c] [--json]}. Same package as {@link PropRulesTest} and {@link MathTest}, test
 * scope, not {@code io.github.vecbricks.jegg.cli}: their {@code BRIDGE} and
 * {@code CONSTANT_FOLD} are package-private, and a separate package could not reach them
 * without making them public or duplicating both languages and their analyses.
 *
 * <p>The rules file holds one unconditional, single-pattern rewrite per non-blank,
 * non-{@code #} line, egg's own syntax: {@code name: lhs => rhs}, parsed by {@link Term}
 * (a bare word starting with {@code ?} is a variable). There are no multi-patterns and no
 * conditions: prop's {@code lem_imply} is the only multi-pattern rule in either suite, and
 * division, {@code pow0}, {@code cancel-div} and the derivative rules that read
 * {@code isNotZero}/{@code isSym} in {@code MathTest} all need a condition the format has no
 * syntax for, so a rules file cannot express them.
 */
public final class Explore {

  private Explore() {
  }

  /** What {@code --help} prints, and what the README's command block quotes. */
  static final String USAGE = """
      dev/explore.sh <prop|math> <rules-file> '<term>' [--limits i,n,c] [--json]

        <prop|math>     the language: PropRulesTest's or MathTest's e-nodes, with the
                        constant-fold analysis
        <rules-file>    one unconditional rewrite per non-blank, non-'#' line:
                        name: lhs => rhs, in s-expression syntax ('?x' a variable);
                        no multi-patterns, no conditions
        <term>          the starting term, in the same s-expression syntax
        --limits i,n,c  the run's iteration, node and class limits (default 30,10000,2147483647)
        --json          the report and the extracted term as JSON, one line, instead of text

      --help, -h prints this text.""";

  /**
   * Checks the arguments before any file is read: an empty result means run; otherwise the
   * usage or an error has been printed and the value is the exit status, as
   * {@link Benchmarks#check}.
   */
  static OptionalInt check(String[] args, PrintStream out, PrintStream err) {
    for (String a : args) {
      if (a.equals("--help") || a.equals("-h")) {
        out.println(USAGE);
        return OptionalInt.of(0);
      }
    }
    List<String> positional = positionalArgs(args);
    if (positional.size() != 3) {
      err.println("expected a language, a rules file and a term, got " + positional.size());
      err.println();
      err.println(USAGE);
      return OptionalInt.of(2);
    }
    String language = positional.get(0);
    if (!language.equals("prop") && !language.equals("math")) {
      err.println("unknown language '" + language + "': prop or math");
      err.println();
      err.println(USAGE);
      return OptionalInt.of(2);
    }
    return OptionalInt.empty();
  }

  private static List<String> positionalArgs(String[] args) {
    List<String> positional = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--json" -> { /* a flag, not positional */ }
        case "--limits" -> i++; // its value, consumed with it
        default -> positional.add(args[i]);
      }
    }
    return positional;
  }

  public static void main(String[] args) {
    OptionalInt status = check(args, System.out, System.err);
    if (status.isPresent()) {
      System.exit(status.getAsInt());
    }
    try {
      System.out.println(run(args));
    } catch (IllegalArgumentException | IllegalStateException | UncheckedIOException e) {
      System.err.println(e.getMessage());
      System.exit(2);
    }
  }

  /** As {@link #main}, but returns the output instead of printing it, for tests. */
  static String run(String[] args) {
    List<String> positional = positionalArgs(args);
    RunLimits limits = RunLimits.DEFAULT;
    boolean json = false;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--json" -> json = true;
        case "--limits" -> limits = parseLimits(args[++i]);
        default -> { /* positional, already collected */ }
      }
    }
    String language = positional.get(0);
    Path rulesFile = Path.of(positional.get(1));
    String term = positional.get(2);
    List<String> lines;
    try {
      lines = Files.readAllLines(rulesFile);
    } catch (NoSuchFileException e) {
      throw new IllegalArgumentException("no such rules file: " + rulesFile);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return switch (language) {
      case "prop" ->
          explore(PropRulesTest.BRIDGE, PropRulesTest.CONSTANT_FOLD, lines, term, limits, json);
      case "math" ->
          explore(MathTest.BRIDGE, MathTest.CONSTANT_FOLD, lines, term, limits, json);
      default -> throw new IllegalArgumentException("unknown language '" + language + "'");
    };
  }

  private static RunLimits parseLimits(String spec) {
    String[] parts = spec.split(",", -1);
    if (parts.length != 3) {
      throw new IllegalArgumentException("--limits wants i,n,c, not '" + spec + "'");
    }
    try {
      return new RunLimits(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
          Integer.parseInt(parts[2]));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("--limits wants three integers, not '" + spec + "'", e);
    }
  }

  private static <L extends Language<L>, D extends @Nullable Object> String explore(
      TreeBridge<Term, L> bridge, Analysis<L, D> analysis, List<String> ruleLines, String term,
      RunLimits limits, boolean json) {
    EGraph<L, D> g = new EGraph<>(analysis);
    List<Rewrite<L, D>> rules = parseRules(ruleLines, bridge);
    Term start = Term.parse(term);
    checkShape(start, bridge, "the term");
    int root = g.addTree(start, bridge);
    g.rebuild();
    RunReport report = new Runner<>(g, rules, limits, new BackoffScheduler<>()).run();
    Extractor<L, D> extractor = new Extractor<>(g, CostFunction.astSize());
    double cost = extractor.best(root).cost();
    String extracted = render(extractor.extract(root).toTree(bridge));
    if (json) {
      return "{\"report\":" + report.toJson() + ",\"extracted\":{\"term\":"
          + quoted(extracted) + ",\"cost\":" + cost + "}}";
    }
    return report + "\nextracted: " + extracted + " (cost " + cost + ")";
  }

  private static <L extends Language<L>, D extends @Nullable Object> List<Rewrite<L, D>>
      parseRules(List<String> lines, TreeBridge<Term, L> bridge) {
    List<Rewrite<L, D>> rules = new ArrayList<>();
    for (int n = 0; n < lines.size(); n++) {
      String line = lines.get(n).strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      int colon = line.indexOf(':');
      int arrow = line.indexOf("=>");
      if (colon < 0 || arrow < 0 || arrow < colon) {
        throw new IllegalArgumentException("rules file line " + (n + 1)
            + ": expected 'name: lhs => rhs', got: " + line);
      }
      String name = line.substring(0, colon).strip();
      String lhsText = line.substring(colon + 1, arrow).strip();
      String rhsText = line.substring(arrow + 2).strip();
      try {
        Term lhsTerm = Term.parse(lhsText);
        Term rhsTerm = Term.parse(rhsText);
        checkShape(lhsTerm, bridge, "the left side");
        checkShape(rhsTerm, bridge, "the right side");
        rules.add(Rewrite.of(name, Term.pattern(lhsTerm, bridge), Term.pattern(rhsTerm, bridge)));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "rules file line " + (n + 1) + " ('" + name + "'): " + e.getMessage(), e);
      }
    }
    return rules;
  }

  /**
   * Refuses a term whose operator the language's bridge takes but whose arity it then drops:
   * {@code PropRulesTest.BRIDGE} maps any op it does not know to a childless {@code Prop.Sym},
   * which would otherwise silently turn a typo such as {@code (&& ?a ?b)} into an always-empty
   * leaf rather than failing. {@code MathTest.BRIDGE} already throws for this itself; the check
   * costs nothing extra there; and {@code ?}-prefixed leaves are a pattern's variables, not
   * built through the bridge, so they are not checked.
   */
  private static <L extends Language<L>> void checkShape(Term t, TreeBridge<Term, L> bridge,
      String context) {
    if (t.kids().isEmpty() && t.op().startsWith("?")) {
      return;
    }
    for (Term kid : t.kids()) {
      checkShape(kid, bridge, context);
    }
    L node = bridge.node(t, IntList.wrap(new int[t.kids().size()]));
    if (node.children().size() != t.kids().size()) {
      throw new IllegalArgumentException(context + ": '" + t.op() + "' takes "
          + node.children().size() + " children, not " + t.kids().size() + " as written in "
          + render(t));
    }
  }

  /** {@code t} as the s-expression text it would have parsed from, {@link Term}'s own syntax. */
  private static String render(Term t) {
    if (t.kids().isEmpty()) {
      return t.op();
    }
    StringBuilder b = new StringBuilder("(").append(t.op());
    for (Term kid : t.kids()) {
      b.append(' ').append(render(kid));
    }
    return b.append(')').toString();
  }

  /** A JSON string literal, as {@code RunReport.toJson()}'s own (no shared helper, test scope). */
  private static String quoted(String s) {
    StringBuilder b = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        default -> b.append(c);
      }
    }
    return b.append('"').toString();
  }
}
