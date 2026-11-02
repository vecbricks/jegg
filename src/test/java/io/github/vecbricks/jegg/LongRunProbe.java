package io.github.vecbricks.jegg;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The long-run probe of #84's item 3: {@link Matcher.Probe}'s counts over #74's four workloads.
 * Run alone: {@code mvn test -Dtest=LongRunProbe}; the lines start with {@code PROBE}. Not for
 * merging.
 */
final class LongRunProbe {

  private static void fuzz(String language, String ruleset) {
    List<String> terms = new TermGenerator(language, 2).terms(200);
    Matcher.Probe.reset();
    int errors = 0;
    String first = "";
    for (String t : terms) {
      String r = FuzzRun.run(new FuzzRun.Case(ruleset, t, List.of()));
      if (r.startsWith("ERROR")) {
        errors++;
        if (first.isEmpty()) {
          first = r + " for " + t;
        }
      }
    }
    System.out.print(Matcher.Probe.report(language));
    System.out.printf("PROBE %s: %d terms, %d errors; first: %s%n", language, terms.size(),
        errors, first);
  }

  @Test
  void probe() {
    fuzz("prop", "prop-all");
    fuzz("math", "math");
    Matcher.Probe.reset();
    Projection.Saturated s = Projection.saturate(20_000);
    System.out.print(Matcher.Probe.report("projection"));
    System.out.printf("PROBE projection: %s, %d nodes, %d classes%n", s.report().stop(),
        s.graph().numNodes(), s.graph().numClasses());
    Matcher.Probe.reset();
    Saturation.Outcome o = Saturation.suite("lambda_function_repeat").orElseThrow()
        .run(Saturation.Mode.DEFERRED, Long.MAX_VALUE);
    System.out.print(Matcher.Probe.report("lambda"));
    System.out.printf("PROBE lambda: %s, %d iterations, %d nodes%n", o.stop(), o.iterations(),
        o.nodes());
  }
}
