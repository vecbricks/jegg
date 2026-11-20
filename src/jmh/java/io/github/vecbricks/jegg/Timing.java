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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The A/B timing driver of {@code dev/timing.sh} and the {@code timing} workflow: runs the four
 * workloads of issue #74 once each and prints one JSON line per workload, or summarises the
 * lines two sides wrote over several rounds.
 *
 * <p>{@code java ... Timing prop,math,projection,lambda [--terms N] [--warmup N] [--seed S]}
 * prints, per workload, its name, the wall time in milliseconds and the work done, so that the
 * two sides of a comparison can be checked to have done the same work. {@code prop} and
 * {@code math} run {@code N} random terms of seed {@code S} through egg's rule sets after a
 * warm-up on the first {@code warmup} terms; {@code projection} saturates {@code Projection} to
 * a 10,000-node limit and {@code lambda} runs {@code lambda_function_repeat} deferred, each
 * once untimed and once timed. Nothing in the library is counted or changed.
 *
 * <p>{@code java ... Timing --summarize <dir>} reads {@code base-<round>.jsonl} and
 * {@code head-<round>.jsonl} from the directory and prints a Markdown table: per workload, each
 * side's median and range across rounds, the change of the medians, and a verdict that is
 * {@code faster} or {@code slower} only when there are at least three rounds and the medians
 * differ by more than the larger of the two sides' own ranges, and {@code within noise}
 * otherwise. The ranges are the noise floor a shared runner has; a difference inside them is
 * not a result.
 */
public final class Timing {

  static final List<String> WORKLOADS = List.of("prop", "math", "projection", "lambda");

  private Timing() {
  }

  /** One workload's line: the name, the time and the work done. */
  record Line(String workload, long ms, Map<String, Object> work) {

    String toJson() {
      StringBuilder b = new StringBuilder("{\"workload\":\"").append(workload)
          .append("\",\"ms\":").append(ms);
      work.forEach((k, v) -> b.append(",\"").append(k).append("\":")
          .append(v instanceof String s ? "\"" + s + "\"" : v));
      return b.append('}').toString();
    }

    static Line parse(String json) {
      String workload = field(json, "workload");
      long ms = Long.parseLong(field(json, "ms"));
      Map<String, Object> work = new LinkedHashMap<>();
      for (String part : json.substring(1, json.length() - 1).split(",")) {
        String key = part.substring(1, part.indexOf('"', 1));
        if (!key.equals("workload") && !key.equals("ms")) {
          work.put(key, part.substring(part.indexOf(':') + 1).replace("\"", ""));
        }
      }
      return new Line(workload, ms, work);
    }

    private static String field(String json, String key) {
      int at = json.indexOf("\"" + key + "\":") + key.length() + 3;
      int end = json.indexOf(',', at);
      return json.substring(at, end < 0 ? json.length() - 1 : end).replace("\"", "");
    }
  }

  /**
   * Runs or summarises, by the arguments described on the class.
   *
   * @param args the workloads and options, or {@code --summarize} and a directory
   * @throws IOException if the summary's directory cannot be read
   */
  public static void main(String[] args) throws IOException {
    if (args.length == 2 && args[0].equals("--summarize")) {
      System.out.print(summarize(Path.of(args[1])));
      return;
    }
    List<String> which = args.length > 0 && !args[0].startsWith("--")
        ? Arrays.asList(args[0].split(",")) : WORKLOADS;
    int terms = option(args, "--terms", 1000);
    int warmup = option(args, "--warmup", 20);
    long seed = option(args, "--seed", 2);
    for (String w : which) {
      if (!WORKLOADS.contains(w)) {
        System.err.println("unknown workload '" + w + "'; one of " + WORKLOADS);
        System.exit(2);
      }
    }
    PrintStream out = System.out;
    for (String w : which) {
      out.println(run(w, terms, warmup, seed).toJson());
      out.flush();
    }
  }

  private static int option(String[] args, String name, int fallback) {
    for (int i = 0; i + 1 < args.length; i++) {
      if (args[i].equals(name)) {
        return Integer.parseInt(args[i + 1]);
      }
    }
    return fallback;
  }

  static Line run(String workload, int terms, int warmup, long seed) {
    return switch (workload) {
      case "prop" -> fuzz("prop", "prop-all", terms, warmup, seed);
      case "math" -> fuzz("math", "math", terms, warmup, seed);
      case "projection" -> {
        Projection.saturate(10_000);
        long start = System.nanoTime();
        Projection.Saturated s = Projection.saturate(10_000);
        long ms = (System.nanoTime() - start) / 1_000_000;
        Map<String, Object> work = new LinkedHashMap<>();
        work.put("nodes", s.graph().numNodes());
        work.put("classes", s.graph().numClasses());
        work.put("stop", s.report().stop().toString());
        yield new Line("projection", ms, work);
      }
      case "lambda" -> {
        Saturation.Workload repeat = Saturation.suite("lambda_function_repeat").orElseThrow();
        repeat.run(Saturation.Mode.DEFERRED, Long.MAX_VALUE);
        long start = System.nanoTime();
        Saturation.Outcome o = repeat.run(Saturation.Mode.DEFERRED, Long.MAX_VALUE);
        long ms = (System.nanoTime() - start) / 1_000_000;
        Map<String, Object> work = new LinkedHashMap<>();
        work.put("iterations", o.iterations());
        work.put("nodes", o.nodes());
        work.put("classes", o.classes());
        yield new Line("lambda", ms, work);
      }
      default -> throw new IllegalArgumentException(workload);
    };
  }

  private static Line fuzz(String language, String ruleset, int terms, int warmup, long seed) {
    List<String> all = new TermGenerator(language, seed).terms(terms);
    for (String t : all.subList(0, Math.min(warmup, all.size()))) {
      FuzzRun.run(new FuzzRun.Case(ruleset, t, List.of()));
    }
    int errors = 0;
    long start = System.nanoTime();
    for (String t : all) {
      if (FuzzRun.run(new FuzzRun.Case(ruleset, t, List.of())).startsWith("ERROR")) {
        errors++;
      }
    }
    long ms = (System.nanoTime() - start) / 1_000_000;
    Map<String, Object> work = new LinkedHashMap<>();
    work.put("terms", terms);
    work.put("errors", errors);
    return new Line(language, ms, work);
  }

  /** The verdict of one workload: the two medians, their ranges and what they allow to say. */
  record Row(String workload, long baseMedian, long baseMin, long baseMax, long headMedian,
      long headMin, long headMax, int rounds, boolean sameWork) {

    double change() {
      return baseMedian == 0 ? 0.0 : 100.0 * (headMedian - baseMedian) / baseMedian;
    }

    String verdict() {
      if (!sameWork) {
        return "different work";
      }
      if (rounds < 3) {
        return "too few rounds";
      }
      long spread = Math.max(baseMax - baseMin, headMax - headMin);
      long difference = Math.abs(headMedian - baseMedian);
      if (difference <= spread) {
        return "within noise";
      }
      return headMedian < baseMedian ? "faster" : "slower";
    }
  }

  static String summarize(Path dir) throws IOException {
    Map<String, List<Line>> base = read(dir, "base");
    Map<String, List<Line>> head = read(dir, "head");
    StringBuilder b = new StringBuilder();
    b.append("| workload | base median (range) | head median (range) | change | rounds |")
        .append(" verdict |\n|---|---|---|---|---|---|\n");
    for (String w : WORKLOADS) {
      if (base.containsKey(w) && head.containsKey(w)) {
        Row r = row(w, base.get(w), head.get(w));
        b.append(String.format(Locale.ROOT,
            "| %s | %s (%s–%s) | %s (%s–%s) | %+.1f%% | %d | %s |%n", w, r.baseMedian(),
            r.baseMin(), r.baseMax(), r.headMedian(), r.headMin(), r.headMax(), r.change(),
            r.rounds(), r.verdict()));
      }
    }
    b.append("\nTimes in ms. A verdict is `faster` or `slower` only when there are three rounds")
        .append(" or more and the medians differ by more than the larger of the two ranges;")
        .append(" else `within noise`.\n");
    return b.toString();
  }

  static Row row(String workload, List<Line> base, List<Line> head) {
    long[] b = base.stream().mapToLong(Line::ms).sorted().toArray();
    long[] h = head.stream().mapToLong(Line::ms).sorted().toArray();
    boolean sameWork = base.get(0).work().equals(head.get(0).work());
    return new Row(workload, median(b), b[0], b[b.length - 1], median(h), h[0], h[h.length - 1],
        Math.min(b.length, h.length), sameWork);
  }

  private static long median(long[] sorted) {
    int n = sorted.length;
    return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
  }

  private static Map<String, List<Line>> read(Path dir, String side) throws IOException {
    Map<String, List<Line>> lines = new LinkedHashMap<>();
    try (Stream<Path> files = Files.list(dir)) {
      files.filter(p -> p.getFileName().toString().matches(side + "-\\d+\\.jsonl")).sorted()
          .forEach(p -> {
            try {
              for (String json : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (json.startsWith("{")) {
                  Line line = Line.parse(json);
                  lines.computeIfAbsent(line.workload(), k -> new ArrayList<>()).add(line);
                }
              }
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          });
    }
    return lines;
  }
}
