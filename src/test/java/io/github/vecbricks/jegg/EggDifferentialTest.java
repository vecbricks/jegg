/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The differential fuzzing against egg (#44), run by {@code dev/fuzz.sh}, not by the build: it
 * needs cargo and an egg checkout, and is on only with {@code -Dfuzz.lang=prop|math}. Two modes
 * ({@code -Dfuzz.mode}):
 *
 * <ul>
 *   <li>{@code known}, the gate: egg's own harness must reproduce the counts the ported tests pin
 *       ({@link FuzzRun#knownMath}, {@link FuzzRun#knownProp}), or the harness, not jegg, is
 *       wrong and no fuzzing means anything;
 *   <li>{@code fuzz}, default: {@code -Dfuzz.count} random terms from {@code -Dfuzz.seed} through
 *       egg and jegg, compared; a divergence is shrunk and written under {@code
 *       dev/fuzz/found/}, and the test fails.
 * </ul>
 */
@EnabledIfSystemProperty(named = "fuzz.lang", matches = "prop|math")
class EggDifferentialTest {

  private static final Path WORK = Path.of("target", "fuzz");
  private static final Path FOUND = Path.of("dev", "fuzz", "found");

  private final String language = System.getProperty("fuzz.lang");
  private final EggProcess egg = new EggProcess(language,
      Path.of(System.getProperty("fuzz.eggdir", System.getProperty("user.home")
          + "/.cache/jegg/egg")), WORK);

  @Test
  void run() throws IOException, InterruptedException {
    if (System.getProperty("fuzz.mode", "fuzz").equals("known")) {
      known();
    } else {
      fuzz(Integer.getInteger("fuzz.count", 1000), Long.getLong("fuzz.seed", 1L));
    }
  }

  private void known() throws IOException, InterruptedException {
    List<FuzzRun.Known> cases = language.equals("math") ? FuzzRun.knownMath()
        : FuzzRun.knownProp();
    List<String> results = egg.run(cases.stream().map(FuzzRun.Known::input).toList());
    List<String> wrong = new ArrayList<>();
    for (int i = 0; i < cases.size(); i++) {
      FuzzRun.Known k = cases.get(i);
      String line = results.get(i);
      FuzzRun.Result r = line.startsWith("PANIC") ? null : FuzzRun.Result.parse(line);
      if (r == null || r.iterations() != k.iterations() || r.nodes() != k.nodes()
          || r.classes() != k.classes()) {
        wrong.add(k.input().term() + ": egg's harness gave " + line + ", pinned "
            + k.iterations() + " iterations, " + k.nodes() + " nodes, " + k.classes()
            + " classes");
      }
    }
    System.out.printf("FUZZ known %s: %d cases, egg's harness reproduces %d%n", language,
        cases.size(), cases.size() - wrong.size());
    assertTrue(wrong.isEmpty(), "the harness does not reproduce the pinned counts:\n"
        + String.join("\n", wrong));
  }

  private void fuzz(int count, long seed) throws IOException, InterruptedException {
    String ruleset = language.equals("prop") ? "prop-all" : "math";
    List<FuzzRun.Case> cases = new TermGenerator(language, seed).terms(count).stream()
        .map(t -> new FuzzRun.Case(ruleset, t, List.of())).toList();
    long eggStart = System.nanoTime();
    List<String> eggResults = egg.run(cases);
    double eggSeconds = (System.nanoTime() - eggStart) / 1e9;
    long jeggStart = System.nanoTime();
    int same = 0;
    int bothFailed = 0;
    int nodeLimit = 0;
    int inconclusive = 0;
    List<Integer> diverged = new ArrayList<>();
    for (int i = 0; i < cases.size(); i++) {
      String jegg = FuzzRun.run(cases.get(i));
      switch (FuzzRun.compare(eggResults.get(i), jegg)) {
        case SAME -> same++;
        case NODE_LIMIT -> nodeLimit++;
        case BOTH_FAILED -> bothFailed++;
        case INCONCLUSIVE -> inconclusive++;
        case DIVERGE -> {
          diverged.add(i);
        }
      }
    }
    double jeggSeconds = (System.nanoTime() - jeggStart) / 1e9;
    System.out.printf("FUZZ %s seed %d: %d terms, %d the same, %d the same up to a node-limit"
        + " stop, %d refused by both, %d diverged, %d inconclusive; egg %.1f s, jegg"
        + " %.1f s%n", language, seed, count, same, nodeLimit, bothFailed, diverged.size(),
        inconclusive, eggSeconds, jeggSeconds);
    for (int k = 0; k < Math.min(5, diverged.size()); k++) {
      record(seed, diverged.get(k), cases.get(diverged.get(k)), eggResults.get(diverged.get(k)));
    }
    assertTrue(diverged.isEmpty(), diverged.size() + " of " + count + " terms diverge; see "
        + FOUND);
  }

  /** Shrinks a divergence round by round, one egg batch per round, and writes it down. */
  private void record(long seed, int index, FuzzRun.Case original, String originalEgg)
      throws IOException, InterruptedException {
    FuzzRun.Case best = original;
    for (int round = 0; round < 40; round++) {
      FuzzRun.Case current = best;
      List<FuzzRun.Case> candidates = FuzzShrinker.reductions(current.term(), language).stream()
          .map(t -> new FuzzRun.Case(current.ruleset(), t, current.goals())).toList();
      if (candidates.isEmpty()) {
        break;
      }
      List<String> results = egg.run(candidates);
      FuzzRun.Case next = null;
      for (int i = 0; i < candidates.size() && next == null; i++) {
        if (FuzzRun.compare(results.get(i), FuzzRun.run(candidates.get(i)))
            == FuzzRun.Verdict.DIVERGE) {
          next = candidates.get(i);
        }
      }
      if (next == null) {
        break;
      }
      best = next;
    }
    String eggLine = egg.run(List.of(best)).get(0);
    Files.createDirectories(FOUND);
    Files.write(FOUND.resolve(language + "-" + seed + "-" + index + ".txt"), List.of(
        "rule set: " + best.ruleset(),
        "found as: " + original.term(),
        "shrunk to: " + best.term(),
        "egg:  " + eggLine,
        "jegg: " + FuzzRun.run(best),
        "originally, egg: " + originalEgg,
        "Pin it as a Case with egg's counts (docs/skills/checking-a-port-against-egg.md) and"
            + " open an issue."), StandardCharsets.UTF_8);
  }
}
