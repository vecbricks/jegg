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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * jegg's time against egg's on the same runs, by phase (#72): the fuzz term sets of a seed run
 * through egg's harness ({@code dev/fuzz.sh} builds it; {@link EggProcess}) and through jegg's
 * port ({@link FuzzRun#timed}), the two sides alternating over {@code rounds}, and
 * {@code lambda_function_repeat} through egg's own test and {@link Saturation} in deferred mode.
 *
 * <p>{@code java ... EggCompare --egg <egg checkout> [--terms N] [--seed S] [--rounds R]
 * [--warmup W] [--languages prop,math] [--lambda true|false] --out <dir>}
 *
 * <p>Per term and side, the time of each phase is the median over the rounds, and the total is
 * the median over the rounds of the three phases' sum (not the sum of the medians). Work is
 * compared by {@link FuzzRun#compare}: the sums, the phase ratios and the per-term ratios are
 * over the {@code SAME} bucket (identical runs); the {@code NODE_LIMIT} bucket (the same up to a
 * node-limit stop, which egg checks against its memo and so can take in another iteration) is
 * summed apart; the rest is excluded and counted. egg's total is its report's, which also holds
 * its hooks; jegg's wall time beyond its three phases (hooks, the first rebuild, bookkeeping)
 * is printed beside the sum, not inside it. Writes {@code <language>.jsonl}, one line per term,
 * {@code lambda.jsonl}, one per round, and {@code summary.md}.
 */
public final class EggCompare {

  private EggCompare() {
  }

  /** The three phases of one run or one sum, in nanoseconds, and their total. */
  record Side(long search, long apply, long rebuild, long total) {

    static final Side ZERO = new Side(0, 0, 0, 0);

    Side plus(Side o) {
      return new Side(search + o.search, apply + o.apply, rebuild + o.rebuild, total + o.total);
    }

    long phases() {
      return search + apply + rebuild;
    }

    /** Per phase the median over the rounds; the total the median of the per-round sums. */
    static Side median(List<Side> rounds) {
      return new Side(EggCompare.median(rounds.stream().mapToLong(Side::search).toArray()),
          EggCompare.median(rounds.stream().mapToLong(Side::apply).toArray()),
          EggCompare.median(rounds.stream().mapToLong(Side::rebuild).toArray()),
          EggCompare.median(rounds.stream().mapToLong(Side::phases).toArray()));
    }
  }

  /** One term: its bucket, both sides' medians, and what jegg's run was. */
  record TermRow(String language, int index, FuzzRun.Verdict bucket, Side egg, Side jegg,
      long jeggWall, int iterations, int nodes, String topRule) {

    String toJson() {
      return "{\"language\":\"" + language + "\",\"index\":" + index + ",\"bucket\":\""
          + bucket + "\",\"egg\":" + json(egg) + ",\"jegg\":" + json(jegg) + ",\"jeggWall\":"
          + jeggWall + ",\"iterations\":" + iterations + ",\"nodes\":" + nodes
          + ",\"topRule\":" + quoted(topRule) + "}";
    }

    private static String json(Side s) {
      return "{\"search\":" + s.search() + ",\"apply\":" + s.apply() + ",\"rebuild\":"
          + s.rebuild() + ",\"total\":" + s.total() + "}";
    }
  }

  /** egg's printed report of a test run ({@code Runner report} block). */
  record EggReport(int iterations, int nodes, int classes, Side times) {

    /** The first report block of a {@code cargo test -- --nocapture} output. */
    static EggReport parse(String output) {
      int iterations = -1;
      int nodes = -1;
      int classes = -1;
      double total = -1;
      double search = -1;
      double apply = -1;
      double rebuild = -1;
      for (String raw : output.split("\n")) {
        String line = raw.trim();
        if (line.startsWith("Iterations: ") && iterations < 0) {
          iterations = Integer.parseInt(line.substring("Iterations: ".length()).trim());
        } else if (line.startsWith("Egraph size: ") && nodes < 0) {
          String[] t = line.substring("Egraph size: ".length()).split(",");
          nodes = Integer.parseInt(t[0].trim().split(" ")[0]);
          classes = Integer.parseInt(t[1].trim().split(" ")[0]);
        } else if (line.startsWith("Total time: ") && total < 0) {
          total = Double.parseDouble(line.substring("Total time: ".length()).trim());
        } else if (line.startsWith("Search:") && search < 0) {
          search = lastNumber(line);
        } else if (line.startsWith("Apply:") && apply < 0) {
          apply = lastNumber(line);
        } else if (line.startsWith("Rebuild:") && rebuild < 0) {
          rebuild = lastNumber(line);
        }
      }
      if (iterations < 0 || nodes < 0 || total < 0 || search < 0 || apply < 0 || rebuild < 0) {
        throw new IllegalArgumentException("no complete Runner report in:\n" + output);
      }
      return new EggReport(iterations, nodes, classes,
          new Side(nanos(search), nanos(apply), nanos(rebuild), nanos(total)));
    }

    private static double lastNumber(String line) {
      String[] t = line.trim().split("\\s+");
      return Double.parseDouble(t[t.length - 1]);
    }

    private static long nanos(double seconds) {
      return Math.round(seconds * 1e9);
    }
  }

  static long median(long[] values) {
    long[] sorted = values.clone();
    Arrays.sort(sorted);
    int n = sorted.length;
    return n == 0 ? 0 : n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
  }

  /**
   * The value at a share of the sorted list, interpolated between neighbours: 0.5 the median,
   * 0.9 the ninetieth percentile, 1.0 the maximum.
   */
  static double percentile(List<Double> values, double share) {
    if (values.isEmpty()) {
      return 0;
    }
    List<Double> sorted = new ArrayList<>(values);
    sorted.sort(null);
    double at = share * (sorted.size() - 1);
    int lo = (int) Math.floor(at);
    int hi = Math.min(sorted.size() - 1, lo + 1);
    return sorted.get(lo) + (at - lo) * (sorted.get(hi) - sorted.get(lo));
  }

  static Side sum(List<TermRow> rows, FuzzRun.Verdict bucket, boolean eggSide) {
    Side acc = Side.ZERO;
    for (TermRow r : rows) {
      if (r.bucket() == bucket) {
        acc = acc.plus(eggSide ? r.egg() : r.jegg());
      }
    }
    return acc;
  }

  static String ratio(long egg, long jegg) {
    return egg == 0 ? "-" : String.format(Locale.ROOT, "%.2fx", (double) jegg / egg);
  }

  static String ms(long nanos) {
    return String.format(Locale.ROOT, "%.1f", nanos / 1e6);
  }

  /** The Markdown summary of one language's rows and per-round sums. */
  static String summarize(String language, int terms, long seed, int rounds, List<TermRow> rows,
      List<Side> eggRounds, List<Side> jeggRounds, long jeggWallSame) {
    Map<FuzzRun.Verdict, Integer> counts = new EnumMap<>(FuzzRun.Verdict.class);
    for (TermRow r : rows) {
      counts.merge(r.bucket(), 1, Integer::sum);
    }
    StringBuilder b = new StringBuilder();
    b.append("## ").append(language).append(": ").append(terms).append(" terms of seed ")
        .append(seed).append(", ").append(rounds).append(" rounds\n\n");
    b.append("| bucket | terms | egg ms | jegg ms | jegg/egg |\n|---|---|---|---|---|\n");
    for (FuzzRun.Verdict v : List.of(FuzzRun.Verdict.SAME, FuzzRun.Verdict.NODE_LIMIT)) {
      Side e = sum(rows, v, true);
      Side j = sum(rows, v, false);
      b.append("| ").append(v).append(" | ").append(counts.getOrDefault(v, 0)).append(" | ")
          .append(ms(e.phases())).append(" | ").append(ms(j.phases())).append(" | ")
          .append(ratio(e.phases(), j.phases())).append(" |\n");
    }
    b.append("\nExcluded: ");
    for (FuzzRun.Verdict v : List.of(FuzzRun.Verdict.BOTH_FAILED, FuzzRun.Verdict.INCONCLUSIVE,
        FuzzRun.Verdict.DIVERGE)) {
      b.append(v).append(' ').append(counts.getOrDefault(v, 0)).append(", ");
    }
    b.setLength(b.length() - 2);
    b.append(".\n\n");
    Side e = sum(rows, FuzzRun.Verdict.SAME, true);
    Side j = sum(rows, FuzzRun.Verdict.SAME, false);
    long gap = j.phases() - e.phases();
    b.append("Phases over SAME:\n\n| phase | egg ms | jegg ms | jegg/egg | share of the gap |\n")
        .append("|---|---|---|---|---|\n");
    long[][] phases = {{e.search(), j.search()}, {e.apply(), j.apply()},
        {e.rebuild(), j.rebuild()}};
    String[] names = {"search", "apply", "rebuild"};
    for (int i = 0; i < 3; i++) {
      long eg = phases[i][0];
      long jg = phases[i][1];
      b.append("| ").append(names[i]).append(" | ").append(ms(eg)).append(" | ").append(ms(jg))
          .append(" | ").append(ratio(eg, jg)).append(" | ")
          .append(gap == 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", 100.0 * (jg - eg) / gap))
          .append(" |\n");
    }
    b.append("| three phases | ").append(ms(e.phases())).append(" | ").append(ms(j.phases()))
        .append(" | ").append(ratio(e.phases(), j.phases())).append(" | 100% |\n");
    b.append("| egg's total with hooks | ").append(ms(e.total())).append(" | | | |\n");
    b.append("| jegg's wall beyond the phases | | ").append(ms(jeggWallSame - j.phases()))
        .append(" | | |\n\n");
    List<Double> ratios = new ArrayList<>();
    for (TermRow r : rows) {
      if (r.bucket() == FuzzRun.Verdict.SAME && r.egg().phases() >= 10_000) {
        ratios.add((double) r.jegg().phases() / r.egg().phases());
      }
    }
    b.append(String.format(Locale.ROOT,
        "Per-term jegg/egg over SAME, terms where egg took 10 us or more (%d): median %.2fx,"
            + " p90 %.2fx, max %.2fx.%n%n", ratios.size(), percentile(ratios, 0.5),
        percentile(ratios, 0.9), percentile(ratios, 1.0)));
    b.append("Per-round sums of the three phases, ms, all terms: egg");
    for (Side s : eggRounds) {
      b.append(' ').append(ms(s.phases()));
    }
    b.append("; jegg");
    for (Side s : jeggRounds) {
      b.append(' ').append(ms(s.phases()));
    }
    b.append(".\n\n");
    List<TermRow> slowest = new ArrayList<>(rows.stream()
        .filter(r -> r.bucket() == FuzzRun.Verdict.SAME
            || r.bucket() == FuzzRun.Verdict.NODE_LIMIT).toList());
    slowest.sort((x, y) -> Long.compare(y.jegg().phases(), x.jegg().phases()));
    b.append("jegg's slowest ten:\n\n| term | bucket | jegg ms | egg ms | jegg/egg | iterations"
        + " | nodes | most matched rule |\n|---|---|---|---|---|---|---|---|\n");
    for (TermRow r : slowest.subList(0, Math.min(10, slowest.size()))) {
      b.append("| ").append(r.index()).append(" | ").append(r.bucket()).append(" | ")
          .append(ms(r.jegg().phases())).append(" | ").append(ms(r.egg().phases())).append(" | ")
          .append(ratio(r.egg().phases(), r.jegg().phases())).append(" | ")
          .append(r.iterations()).append(" | ").append(r.nodes()).append(" | ")
          .append(r.topRule()).append(" |\n");
    }
    return b.toString();
  }

  /** One language: the rounds, the rows, the files, the summary. */
  static String compareLanguage(String language, Path eggDir, Path work, int terms, long seed,
      int rounds, int warmup, PrintStream log) throws IOException, InterruptedException {
    String ruleset = language.equals("prop") ? "prop-all" : "math";
    List<FuzzRun.Case> cases = new TermGenerator(language, seed).terms(terms).stream()
        .map(t -> new FuzzRun.Case(ruleset, t, List.of())).toList();
    for (FuzzRun.Case c : cases.subList(0, Math.min(warmup, cases.size()))) {
      FuzzRun.timed(c);
    }
    EggProcess egg = new EggProcess(language, eggDir, work.resolve("egg-" + language));
    List<List<EggProcess.Timed>> eggRuns = new ArrayList<>();
    List<List<FuzzRun.Timed>> jeggRuns = new ArrayList<>();
    List<Side> eggRounds = new ArrayList<>();
    List<Side> jeggRounds = new ArrayList<>();
    for (int r = 1; r <= rounds; r++) {
      boolean eggFirst = r % 2 == 1;
      for (int side = 0; side < 2; side++) {
        if ((side == 0) == eggFirst) {
          List<EggProcess.Timed> run = egg.runTimed(cases);
          eggRuns.add(run);
          Side s = Side.ZERO;
          for (EggProcess.Timed t : run) {
            s = s.plus(new Side(t.searchNanos(), t.applyNanos(), t.rebuildNanos(),
                t.totalNanos()));
          }
          eggRounds.add(s);
          log.println(language + " round " + r + " egg: " + ms(s.phases()) + " ms");
        } else {
          List<FuzzRun.Timed> run = new ArrayList<>(cases.size());
          Side s = Side.ZERO;
          for (FuzzRun.Case c : cases) {
            FuzzRun.Timed t = FuzzRun.timed(c);
            run.add(t);
            s = s.plus(new Side(t.searchNanos(), t.applyNanos(), t.rebuildNanos(),
                t.wallNanos()));
          }
          jeggRuns.add(run);
          jeggRounds.add(s);
          log.println(language + " round " + r + " jegg: " + ms(s.phases()) + " ms");
        }
      }
    }
    List<TermRow> rows = new ArrayList<>(cases.size());
    long jeggWallSame = 0;
    for (int i = 0; i < cases.size(); i++) {
      String eggResult = eggRuns.get(0).get(i).result();
      String jeggResult = jeggRuns.get(0).get(i).result();
      for (int r = 1; r < rounds; r++) {
        if (!eggRuns.get(r).get(i).result().equals(eggResult)
            || !jeggRuns.get(r).get(i).result().equals(jeggResult)) {
          throw new IllegalStateException(language + " term " + i + " differs between rounds");
        }
      }
      FuzzRun.Verdict bucket = FuzzRun.compare(eggResult, jeggResult);
      List<Side> e = new ArrayList<>();
      List<Side> j = new ArrayList<>();
      long[] walls = new long[rounds];
      for (int r = 0; r < rounds; r++) {
        EggProcess.Timed et = eggRuns.get(r).get(i);
        FuzzRun.Timed jt = jeggRuns.get(r).get(i);
        e.add(new Side(et.searchNanos(), et.applyNanos(), et.rebuildNanos(), et.totalNanos()));
        j.add(new Side(jt.searchNanos(), jt.applyNanos(), jt.rebuildNanos(), jt.wallNanos()));
        walls[r] = jt.wallNanos();
      }
      FuzzRun.Timed last = jeggRuns.get(0).get(i);
      long wall = median(walls);
      if (bucket == FuzzRun.Verdict.SAME) {
        jeggWallSame += wall;
      }
      rows.add(new TermRow(language, i, bucket, Side.median(e), Side.median(j), wall,
          last.iterations(), last.nodes(), last.topRule()));
    }
    Files.write(work.resolve(language + ".jsonl"), rows.stream().map(TermRow::toJson).toList(),
        StandardCharsets.UTF_8);
    return summarize(language, terms, seed, rounds, rows, eggRounds, jeggRounds, jeggWallSame);
  }

  /** {@code lambda_function_repeat}: egg's own test against the harness's deferred run. */
  static String compareLambda(Path eggDir, Path work, int rounds, PrintStream log)
      throws IOException, InterruptedException {
    Saturation.Workload repeat = Saturation.suite("lambda_function_repeat").orElseThrow();
    repeat.run(Saturation.Mode.DEFERRED, Long.MAX_VALUE);
    List<EggReport> eggs = new ArrayList<>();
    List<Saturation.Outcome> jeggs = new ArrayList<>();
    List<String> lines = new ArrayList<>();
    for (int r = 1; r <= rounds; r++) {
      boolean eggFirst = r % 2 == 1;
      for (int side = 0; side < 2; side++) {
        if ((side == 0) == eggFirst) {
          EggReport e = EggReport.parse(eggLambda(eggDir));
          eggs.add(e);
          log.println("lambda round " + r + " egg: " + ms(e.times().phases()) + " ms");
          lines.add("{\"side\":\"egg\",\"round\":" + r + ",\"iterations\":" + e.iterations()
              + ",\"nodes\":" + e.nodes() + ",\"search\":" + e.times().search() + ",\"apply\":"
              + e.times().apply() + ",\"rebuild\":" + e.times().rebuild() + ",\"total\":"
              + e.times().total() + "}");
        } else {
          Saturation.Outcome o = repeat.run(Saturation.Mode.DEFERRED, Long.MAX_VALUE);
          jeggs.add(o);
          long phases = o.searchNanos() + o.applyNanos() + o.rebuildNanos();
          log.println("lambda round " + r + " jegg: " + ms(phases) + " ms");
          lines.add("{\"side\":\"jegg\",\"round\":" + r + ",\"iterations\":" + o.iterations()
              + ",\"nodes\":" + o.nodes() + ",\"search\":" + o.searchNanos() + ",\"apply\":"
              + o.applyNanos() + ",\"rebuild\":" + o.rebuildNanos() + "}");
        }
      }
    }
    Files.write(work.resolve("lambda.jsonl"), lines, StandardCharsets.UTF_8);
    Side e = Side.median(eggs.stream().map(EggReport::times).toList());
    Side j = Side.median(jeggs.stream().map(o -> new Side(o.searchNanos(), o.applyNanos(),
        o.rebuildNanos(), 0)).toList());
    EggReport e0 = eggs.get(0);
    Saturation.Outcome j0 = jeggs.get(0);
    boolean sameWork = e0.iterations() == j0.iterations() && e0.nodes() == j0.nodes();
    StringBuilder b = new StringBuilder("## lambda_function_repeat, ").append(rounds)
        .append(" rounds\n\n");
    b.append("egg: ").append(e0.iterations()).append(" iterations, ").append(e0.nodes())
        .append(" nodes; jegg: ").append(j0.iterations()).append(" iterations, ")
        .append(j0.nodes()).append(" nodes: ").append(sameWork ? "the same work"
            : "**different work, the ratios below are not a comparison**").append(".\n\n");
    b.append("| phase | egg ms | jegg ms | jegg/egg |\n|---|---|---|---|\n");
    b.append("| search | ").append(ms(e.search())).append(" | ").append(ms(j.search()))
        .append(" | ").append(ratio(e.search(), j.search())).append(" |\n");
    b.append("| apply | ").append(ms(e.apply())).append(" | ").append(ms(j.apply()))
        .append(" | ").append(ratio(e.apply(), j.apply())).append(" |\n");
    b.append("| rebuild | ").append(ms(e.rebuild())).append(" | ").append(ms(j.rebuild()))
        .append(" | ").append(ratio(e.rebuild(), j.rebuild())).append(" |\n");
    b.append("| three phases | ").append(ms(e.phases())).append(" | ").append(ms(j.phases()))
        .append(" | ").append(ratio(e.phases(), j.phases())).append(" |\n\n");
    b.append("Per-round, ms: egg");
    for (EggReport r : eggs) {
      b.append(' ').append(ms(r.times().phases()));
    }
    b.append("; jegg");
    for (Saturation.Outcome o : jeggs) {
      b.append(' ').append(ms(o.searchNanos() + o.applyNanos() + o.rebuildNanos()));
    }
    b.append(".\n");
    return b.toString();
  }

  private static String eggLambda(Path eggDir) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder("cargo", "test", "-q", "--release", "--test",
        "lambda", "--", "--exact", "lambda_function_repeat", "--nocapture")
        .directory(eggDir.toFile()).redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (p.waitFor() != 0) {
      throw new IOException("egg's lambda_function_repeat failed:\n" + out);
    }
    return out;
  }

  static String quoted(String s) {
    StringBuilder b = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      if (c == '"' || c == '\\') {
        b.append('\\');
      }
      b.append(c);
    }
    return b.append('"').toString();
  }

  /**
   * Runs the comparison and writes its files.
   *
   * @param args the options named in the class comment
   * @throws IOException if a file cannot be written or egg cannot run
   * @throws InterruptedException if interrupted
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    Map<String, String> o = new LinkedHashMap<>();
    for (int i = 0; i + 1 < args.length; i += 2) {
      o.put(args[i], args[i + 1]);
    }
    if (!o.containsKey("--egg") || !o.containsKey("--out")) {
      System.err.println("usage: EggCompare --egg <egg checkout> --out <dir> [--terms N]"
          + " [--seed S] [--rounds R] [--warmup W] [--languages prop,math] [--lambda true]");
      System.exit(2);
    }
    Path eggDir = Path.of(o.get("--egg")).toAbsolutePath();
    Path out = Path.of(o.get("--out")).toAbsolutePath();
    Files.createDirectories(out);
    int terms = Integer.parseInt(o.getOrDefault("--terms", "1000"));
    long seed = Long.parseLong(o.getOrDefault("--seed", "2"));
    int rounds = Integer.parseInt(o.getOrDefault("--rounds", "5"));
    int warmup = Integer.parseInt(o.getOrDefault("--warmup", "20"));
    List<String> languages = Arrays.asList(o.getOrDefault("--languages", "prop,math")
        .split(","));
    boolean lambda = Boolean.parseBoolean(o.getOrDefault("--lambda", "true"));
    StringBuilder summary = new StringBuilder();
    for (String language : languages) {
      summary.append(compareLanguage(language, eggDir, out, terms, seed, rounds, warmup,
          System.err)).append('\n');
    }
    if (lambda) {
      summary.append(compareLambda(eggDir, out, rounds, System.err));
    }
    Files.writeString(out.resolve("summary.md"), summary.toString(), StandardCharsets.UTF_8);
    System.out.print(summary);
  }
}
