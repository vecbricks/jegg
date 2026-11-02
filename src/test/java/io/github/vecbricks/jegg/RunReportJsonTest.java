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

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link RunReport#toJson()}: round-tripped through the test tree's {@link Json} reader and
 * compared field by field against the record, for every {@link StopReason} and for text that
 * needs escaping.
 */
class RunReportJsonTest {

  private static RunReport.Iteration iteration(int number, Map<String, Integer> matches,
      Set<String> banned, Set<String> skipped) {
    return new RunReport.Iteration(number, 10 + number, 20 + number, matches, banned, skipped,
        number, number - 1, number - 1, number + 1);
  }

  @SuppressWarnings("unchecked")
  private static void assertIterationMatches(RunReport.Iteration it, Object parsed) {
    Map<String, Object> m = (Map<String, Object>) parsed;
    assertEquals((double) it.number(), m.get("number"));
    assertEquals((double) it.classes(), m.get("classes"));
    assertEquals((double) it.nodes(), m.get("nodes"));
    Map<String, Object> matches = (Map<String, Object>) m.get("matches");
    assertEquals(it.matches().keySet(), matches.keySet());
    for (var e : it.matches().entrySet()) {
      assertEquals((double) e.getValue(), matches.get(e.getKey()), e.getKey());
    }
    assertEquals(it.banned(), Set.copyOf((List<String>) m.get("banned")));
    assertEquals(it.skipped(), Set.copyOf((List<String>) m.get("skipped")));
    assertEquals((double) it.applied(), m.get("applied"));
    assertEquals((double) it.unions(), m.get("unions"));
    assertEquals((double) it.counted(), m.get("counted"));
    assertEquals((double) it.repaired(), m.get("repaired"));
  }

  @Test
  void anIterationRoundTripsEveryField() {
    RunReport.Iteration it = iteration(1, Map.of("comm", 3, "assoc", 0), Set.of("assoc"),
        Set.of("comm"));
    RunReport report = new RunReport(List.of(it), new StopReason.Saturated());
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = (Map<String, Object>) Json.parse(report.toJson());
    @SuppressWarnings("unchecked")
    List<Object> iterations = (List<Object>) parsed.get("iterations");
    assertEquals(1, iterations.size());
    assertIterationMatches(it, iterations.get(0));
  }

  @Test
  void twoIterationsKeepTheirOrder() {
    RunReport.Iteration first = iteration(1, Map.of(), Set.of(), Set.of());
    RunReport.Iteration second = iteration(2, Map.of(), Set.of(), Set.of());
    RunReport report = new RunReport(List.of(first, second), new StopReason.Saturated());
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = (Map<String, Object>) Json.parse(report.toJson());
    @SuppressWarnings("unchecked")
    List<Object> iterations = (List<Object>) parsed.get("iterations");
    assertEquals(2, iterations.size());
    assertIterationMatches(first, iterations.get(0));
    assertIterationMatches(second, iterations.get(1));
  }

  private static RunReport emptyReport(StopReason stop) {
    return new RunReport(List.of(), stop);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> stopOf(StopReason stop) {
    Map<String, Object> parsed = (Map<String, Object>) Json.parse(emptyReport(stop).toJson());
    return (Map<String, Object>) parsed.get("stop");
  }

  @Test
  void everyStopReasonHasItsOwnFields() {
    assertEquals(Map.of("type", "Saturated"), stopOf(new StopReason.Saturated()));
    assertEquals(Map.of("type", "IterationLimit", "iterations", 7.0),
        stopOf(new StopReason.IterationLimit(7)));
    assertEquals(Map.of("type", "NodeLimit", "nodes", 5000.0),
        stopOf(new StopReason.NodeLimit(5000)));
    assertEquals(Map.of("type", "ClassLimit", "classes", 300.0),
        stopOf(new StopReason.ClassLimit(300)));
    assertEquals(Map.of("type", "Other", "reason", "a hook's own reason"),
        stopOf(new StopReason.Other("a hook's own reason")));
  }

  @Test
  void aQuoteABackslashANewlineAndAControlCharacterInAHookReasonSurviveTheRoundTrip() {
    String reason = "line one\nline \"two\"\\ line\tthree\u0001end";
    Map<String, Object> stop = stopOf(new StopReason.Other(reason));
    assertEquals(reason, stop.get("reason"));
  }

  @Test
  void aRuleNameThatNeedsEscapingSurvivesTheRoundTrip() {
    String name = "a \"rule\" with\\backslash and\nnewline";
    RunReport.Iteration it = iteration(1, Map.of(name, 1), Set.of(name), Set.of(name));
    RunReport report = new RunReport(List.of(it), new StopReason.Saturated());
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = (Map<String, Object>) Json.parse(report.toJson());
    @SuppressWarnings("unchecked")
    List<Object> iterations = (List<Object>) parsed.get("iterations");
    assertIterationMatches(it, iterations.get(0));
  }

  @Test
  void theJsonIsOneLineWithNoTrailingNewline() {
    String json = emptyReport(new StopReason.Saturated()).toJson();
    assertTrue(json.indexOf('\n') < 0, json);
    assertEquals('}', json.charAt(json.length() - 1));
  }
}
