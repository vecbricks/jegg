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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Explore}'s argument check, mirroring {@link BenchmarksUsageTest}, and its correctness:
 * the CLI's parsing of a rules file and its run must agree with calling {@link Runner} and
 * {@link Extractor} directly on the real rule constants, built without going through the
 * rules-file parser the CLI is being checked against.
 */
class ExploreTest {

  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
  private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

  private OptionalInt check(String... args) {
    return Explore.check(args, out, err);
  }

  private String out() {
    return outBytes.toString(StandardCharsets.UTF_8);
  }

  private String err() {
    return errBytes.toString(StandardCharsets.UTF_8);
  }

  @Test
  void helpIsAnsweredOnStdoutWithStatusZero() {
    assertEquals(OptionalInt.of(0), check("--help"));
    assertEquals(OptionalInt.of(0), check("nope", "-h"));
    assertEquals(2, out().split(java.util.regex.Pattern.quote(Explore.USAGE), -1).length - 1,
        "the usage, once per request");
    assertEquals("", err());
  }

  @Test
  void anUnknownLanguageIsNamedOnStderrAndExitsTwo() {
    assertEquals(OptionalInt.of(2), check("rust", "rules.txt", "x"));
    assertTrue(err().startsWith("unknown language 'rust'"), err());
    assertTrue(err().contains(Explore.USAGE));
    assertEquals("", out());
  }

  @Test
  void theWrongNumberOfPositionalArgumentsExitsTwo() {
    assertEquals(OptionalInt.of(2), check("prop", "rules.txt"));
    assertEquals(OptionalInt.of(2), check("prop", "rules.txt", "x", "extra"));
    assertEquals(OptionalInt.of(2), check());
  }

  @Test
  void flagsAreNotCountedAsPositional() {
    assertEquals(OptionalInt.empty(), check("prop", "rules.txt", "x", "--json"));
    assertEquals(OptionalInt.empty(),
        check("prop", "rules.txt", "x", "--limits", "5,100,100", "--json"));
  }

  @Test
  void theUsageNamesEveryFlag() {
    for (String word : new String[] {"prop", "math", "--limits", "--json", "--help"}) {
      assertTrue(Explore.USAGE.contains(word), word);
    }
  }

  private static Path writeRules(Path dir, String text) throws Exception {
    Path file = dir.resolve("rules.txt");
    Files.writeString(file, text);
    return file;
  }

  @Test
  void aMalformedLineIsRefusedWithItsNumber(@TempDir Path dir) throws Exception {
    Path rules = writeRules(dir, "double_neg: (~ (~ ?a))\n");
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> Explore.run(new String[] {"prop", rules.toString(), "x"}));
    assertTrue(e.getMessage().contains("line 1"), e.getMessage());
  }

  @Test
  void anOperatorThatSilentlyDropsItsChildrenIsRefused(@TempDir Path dir) throws Exception {
    // Prop's bridge maps an unknown op to a childless Sym; the CLI catches it before addTree.
    Path rules = writeRules(dir, "bad: (&& ?a ?b) => ?a\n");
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> Explore.run(new String[] {"prop", rules.toString(), "x"}));
    assertTrue(e.getMessage().contains("'&&' takes 0 children, not 2"), e.getMessage());
  }

  @Test
  void aMissingRulesFileIsRefused() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> Explore.run(new String[] {"prop", "/no/such/file.txt", "x"}));
    assertTrue(e.getMessage().contains("no such rules file"), e.getMessage());
  }

  /**
   * {@link PropRulesTest#CONTRAPOSITIVE_CASE}'s four rules, as a rules file in the CLI's own
   * syntax (the literal left- and right-hand text {@code PropRulesTest} builds its {@code
   * Rewrite} constants from), so the comparison below never shares code with what it checks.
   */
  private static final String CONTRAPOSITIVE_RULES = """
      def_imply: (-> ?a ?b) => (| (~ ?a) ?b)
      def_imply_flip: (| (~ ?a) ?b) => (-> ?a ?b)
      double_neg_flip: ?a => (~ (~ ?a))
      comm_or: (| ?a ?b) => (| ?b ?a)
      """;

  private static final String CONTRAPOSITIVE_TERM = "(-> x y)";

  @SuppressWarnings("unchecked")
  private static Map<String, Object> reportJson(String json) {
    Map<String, Object> parsed = (Map<String, Object>) Json.parse(json);
    return (Map<String, Object>) parsed.get("report");
  }

  /** As {@link Explore}'s own private {@code render}, duplicated so the test shares no code. */
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

  @Test
  void theClisRunMatchesCallingRunnerAndExtractorDirectly(@TempDir Path dir) throws Exception {
    Path rules = writeRules(dir, CONTRAPOSITIVE_RULES);
    String cliJson = Explore.run(new String[] {"prop", rules.toString(), CONTRAPOSITIVE_TERM,
        "--json"});
    Map<String, Object> cliReport = reportJson(cliJson);

    // The direct oracle: PropRulesTest's own Rewrite constants, never parsed from the file, and
    // never through Explore at all.
    EGraph<PropRulesTest.Prop, Boolean> g = new EGraph<>(PropRulesTest.CONSTANT_FOLD);
    int root = g.addTree(Term.parse(CONTRAPOSITIVE_TERM), PropRulesTest.BRIDGE);
    g.rebuild();
    RunReport direct = new Runner<>(g,
        List.of(PropRulesTest.DEF_IMPLY, PropRulesTest.DEF_IMPLY_FLIP,
            PropRulesTest.DOUBLE_NEG_FLIP, PropRulesTest.COMM_OR),
        RunLimits.DEFAULT, new BackoffScheduler<>()).run();
    Extractor<PropRulesTest.Prop, Boolean> extractor =
        new Extractor<>(g, CostFunction.astSize());
    double directCost = extractor.best(root).cost();
    String directExtracted = render(extractor.extract(root).toTree(PropRulesTest.BRIDGE));

    // The report, iteration for iteration: both sides call RunReport.toJson() on what must be
    // value-equal records if the CLI's parsing agrees with the real rule constants.
    assertEquals(Json.parse(direct.toJson()), cliReport);
    @SuppressWarnings("unchecked")
    Map<String, Object> cliExtracted =
        (Map<String, Object>) ((Map<String, Object>) Json.parse(cliJson)).get("extracted");
    assertEquals(directExtracted, cliExtracted.get("term"));
    assertEquals(directCost, cliExtracted.get("cost"));

    // The non-JSON form agrees too: it is the same report and the same rendered term.
    String text = Explore.run(new String[] {"prop", rules.toString(), CONTRAPOSITIVE_TERM});
    assertEquals(direct.toString() + "\nextracted: " + directExtracted + " (cost " + directCost
        + ")", text);
  }

  @Test
  void aMathRunSaturatesAndExtractsTheCheapestForm(@TempDir Path dir) throws Exception {
    Path rules = writeRules(dir, """
        comm-add: (+ ?a ?b) => (+ ?b ?a)
        zero-add: (+ ?a 0) => ?a
        """);
    String text = Explore.run(new String[] {"math", rules.toString(), "(+ x 0)"});
    assertTrue(text.contains("Saturated"), text);
    assertTrue(text.endsWith("extracted: x (cost 1.0)"), text);
  }

  @Test
  void theLimitsFlagIsHonoured(@TempDir Path dir) throws Exception {
    // comm_or alone saturates after 2 iterations by default (swapping the two forms once, then
    // finding nothing new); an iteration limit of 1 stops it before that.
    Path rules = writeRules(dir, "comm_or: (| ?a ?b) => (| ?b ?a)\n");
    String json = Explore.run(new String[] {"prop", rules.toString(), "(| x y)", "--limits",
        "1,1000,1000", "--json"});
    @SuppressWarnings("unchecked")
    Map<String, Object> stop =
        (Map<String, Object>) reportJson(json).get("stop");
    assertEquals("IterationLimit", stop.get("type"));
    assertEquals(1.0, stop.get("iterations"));
  }
}
