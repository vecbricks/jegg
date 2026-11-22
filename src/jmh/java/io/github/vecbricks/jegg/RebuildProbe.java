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
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/**
 * The rebuild's work on both sides (#134): egg's counters from {@code dev/fuzz/probe.patch}
 * and jegg's {@code EGraph.Probe} over the fuzz term sets of a seed, bucketed as the comparison
 * of #72 buckets them and summed by quintile of egg's time, and over
 * {@code lambda_function_repeat}; then a JFR profile of jegg's rebuild on the largest
 * {@code SAME} quintile, its self frames and its allocation per parent visited. Not a timing:
 * the counts are exact whatever the load. {@code dev/probe.sh} runs it.
 */
public final class RebuildProbe {

  /** egg's counter names, in the order of {@code egg::probe::NAMES} in the patch. */
  static final List<String> EGG_NAMES = List.of("pops", "pended_added", "pended_absorbed",
      "memo_collisions", "congruence_unions", "union_kept_parents", "unions", "analysis_pops",
      "modify_calls", "rebuild_classes", "rebuild_nodes", "rebuild_trimmed", "adds", "add_hits",
      "add_news", "modify_from_add", "union_calls", "union_effective", "matches_applied",
      "apply_adds", "apply_news", "applications", "apply_news_merged", "apply_news_merged_leaf");

  /** What is set beside what: the label, egg's counter, jegg's. */
  record Pair(String what, String egg, String jegg) {
  }

  static final List<Pair> PAIRS = List.of(
      new Pair("parents visited (egg: the absorbed side's, pended)", "pended_absorbed",
          "parentsVisited"),
      new Pair("counterfactual: jegg visiting the absorbed side only", "pended_absorbed",
          "mergeAbsorbedParents"),
      new Pair("counterfactual: jegg visiting the smaller side only", "pended_absorbed",
          "mergeMinParents"),
      new Pair("forms re-made (jegg) against egg's visits", "pended_absorbed", "formsRemade"),
      new Pair("hash operations: egg's memo inserts (every pop), jegg's removes and puts",
          "pops", "hashconsOps"),
      new Pair("of which egg pays for the nodes added since the last rebuild", "pended_added",
          "zero"),
      new Pair("unions / merges", "unions", "merges"),
      new Pair("of which congruences found by the rebuild", "congruence_unions",
          "congruenceMerges"),
      new Pair("facts re-made (analysis_pending pops / analysis.make calls)", "analysis_pops",
          "analysisMakes"),
      new Pair("modify calls (egg: one per union and per fact change; jegg: one per root owed"
          + " one, per pass)", "modify_calls", "modifyCalls"),
      new Pair("node-list nodes walked (rebuild_classes / re-canonicalised classes)",
          "rebuild_nodes", "nodesChecked"),
      new Pair("classes walked for that", "rebuild_classes", "staleClasses"),
      new Pair("nodes re-made in the lists (egg: trimmed duplicates)", "rebuild_trimmed",
          "nodesRemade"));

  /** The apply's pairs (#154): what an application does on both sides. */
  static final List<Pair> APPLY_PAIRS = List.of(
      new Pair("applications: substitutions handed to the appliers (jegg: conditions held and"
          + " refused)", "applications", "applicationsOffered"),
      new Pair("of which a pattern applier instantiated (egg) / conditions held (jegg)",
          "matches_applied", "matchesApplied"),
      new Pair("adds by the right-hand sides (egg: apply_pat; jegg: Matcher.instantiate)",
          "apply_adds", "addsInApply"),
      new Pair("of which made a class", "apply_news", "addNewsInApply"),
      new Pair("of which the iteration's rebuild merged away (#137)", "apply_news_merged",
          "applyNewsMerged"),
      new Pair("of which into a class holding a leaf (a constant or a symbol)",
          "apply_news_merged_leaf", "applyNewsMergedLeaf"),
      new Pair("of which into a class with a fact (jegg; a folded constant)", "zero",
          "applyNewsMergedFact"),
      new Pair("adds, every caller (the harness's term, the analysis's modify included)", "adds",
          "adds"),
      new Pair("of which found a node (hits)", "add_hits", "addHits"),
      new Pair("of which jegg found through the pruned memory", "zero", "addPrunedHits"),
      new Pair("of which made a class (news; each makes a fact)", "add_news", "addNews"),
      new Pair("modify calls from an add", "modify_from_add", "modifyFromAdd"),
      new Pair("union calls outside the rebuild (egg: one per application and per modify;"
          + " jegg: the applier skips the call when the two are one class)",
          "union_calls", "mergeCallsOutsideRebuild"),
      new Pair("of which effective", "union_effective", "ruleMerges"),
      new Pair("conditions refused (jegg; egg applies through a conditional applier)", "zero",
          "conditionsRefused"));

  private RebuildProbe() {
  }

  /** One side's counts by name; a name not counted reads 0. */
  record Counts(Map<String, Long> values) {
    static final Counts NONE = new Counts(Map.of());

    /** egg's line: the numbers in {@link #EGG_NAMES} order, space separated. */
    static Counts egg(String line) {
      if (line.isBlank()) {
        return NONE;
      }
      String[] t = line.trim().split("\\s+");
      if (t.length != EGG_NAMES.size()) {
        throw new IllegalArgumentException("egg counted " + t.length + " things: " + line);
      }
      Map<String, Long> m = new LinkedHashMap<>();
      for (int i = 0; i < t.length; i++) {
        m.put(EGG_NAMES.get(i), Long.parseLong(t[i]));
      }
      return new Counts(m);
    }

    /** egg's {@code Probe: [a, b, ...]} line of a printed report. */
    static Counts eggReport(String output) {
      for (String raw : output.split("\n")) {
        String line = raw.trim();
        if (line.startsWith("Probe: [")) {
          return egg(line.substring("Probe: [".length(), line.lastIndexOf(']'))
              .replace(",", " "));
        }
      }
      throw new IllegalArgumentException("no Probe line in:\n" + output);
    }

    /** jegg's {@code name=value} line, with {@code hashconsOps} derived. */
    static Counts jegg(String line) {
      if (line.isBlank()) {
        return NONE;
      }
      Map<String, Long> m = new LinkedHashMap<>();
      for (String kv : line.trim().split("\\s+")) {
        int eq = kv.indexOf('=');
        m.put(kv.substring(0, eq), Long.parseLong(kv.substring(eq + 1)));
      }
      m.put("hashconsOps", m.getOrDefault("hashconsRemoves", 0L)
          + m.getOrDefault("hashconsPuts", 0L));
      m.put("ruleMerges", m.getOrDefault("merges", 0L) - m.getOrDefault("congruenceMerges", 0L));
      m.put("mergeCallsOutsideRebuild", m.getOrDefault("mergeCalls", 0L)
          - m.getOrDefault("congruenceMerges", 0L));
      m.put("applicationsOffered", m.getOrDefault("matchesApplied", 0L)
          + m.getOrDefault("conditionsRefused", 0L));
      return new Counts(m);
    }

    long get(String name) {
      return values.getOrDefault(name, 0L);
    }

    Counts plus(Counts o) {
      Map<String, Long> m = new LinkedHashMap<>(values);
      o.values.forEach((k, v) -> m.merge(k, v, Long::sum));
      return new Counts(m);
    }

    String toJson() {
      StringBuilder b = new StringBuilder("{");
      values.forEach((k, v) -> b.append(b.length() > 1 ? "," : "").append('"').append(k)
          .append("\":").append(v));
      return b.append('}').toString();
    }
  }

  /** One term's counts on both sides, with the bucket and egg's time, which orders them. */
  record Row(String language, int index, FuzzRun.Verdict bucket, long eggTotalNanos,
      int iterations, int nodes, Counts egg, Counts jegg) {
    String toJson() {
      return "{\"language\":" + EggCompare.quoted(language) + ",\"index\":" + index
          + ",\"bucket\":\"" + bucket + "\",\"eggTotalNanos\":" + eggTotalNanos
          + ",\"iterations\":" + iterations + ",\"nodes\":" + nodes + ",\"egg\":"
          + egg.toJson() + ",\"jegg\":" + jegg.toJson() + "}";
    }
  }

  static String ratio(long egg, long jegg) {
    return egg == 0 ? "-" : String.format(Locale.ROOT, "%.2fx", (double) jegg / egg);
  }

  /** The pairs table over the summed counts of a set of runs. */
  static String pairsTable(Counts egg, Counts jegg) {
    return pairsTable(PAIRS, egg, jegg);
  }

  static String pairsTable(List<Pair> pairs, Counts egg, Counts jegg) {
    StringBuilder b = new StringBuilder("| what | egg | jegg | jegg/egg |\n|---|---|---|---|\n");
    for (Pair p : pairs) {
      long e = egg.get(p.egg());
      long j = jegg.get(p.jegg());
      b.append("| ").append(p.what()).append(" | ").append(e).append(" | ")
          .append(p.jegg().equals("zero") ? "" : String.valueOf(j)).append(" | ")
          .append(p.jegg().equals("zero") ? "" : ratio(e, j)).append(" |\n");
    }
    return b.toString();
  }

  /** The apply's identities: each side's adds are its hits plus its news. */
  static String applyIdentities(Counts egg, Counts jegg) {
    long eggGap = egg.get("adds") - egg.get("add_hits") - egg.get("add_news");
    long jeggGap = jegg.get("adds") - jegg.get("addHits") - jegg.get("addNews");
    return "Apply identities: egg adds minus (hits + news) = " + eggGap
        + " (expected 0); jegg adds minus (hits + news) = " + jeggGap + " (expected 0).";
  }

  /** The identities that tell a broken counter from a finding. */
  static String identities(Counts egg, Counts jegg) {
    long eggGap = egg.get("pops") - egg.get("pended_added") - egg.get("pended_absorbed");
    long jeggGap = jegg.get("parentsVisited") - jegg.get("hashconsPuts")
        - jegg.get("prunedVisits");
    return "Identities: egg pops minus (added + absorbed) = " + eggGap
        + " (0 unless a run stopped between a union and its rebuild); jegg parents visited"
        + " minus (hashcons puts + pruned visits) = " + jeggGap + " (expected 0).\n";
  }

  /** Per SAME term, jegg's adds over egg's: median, p90 and max, over terms egg added 10+. */
  static String addsPerTerm(List<Row> rows) {
    List<Double> ratios = new ArrayList<>();
    for (Row r : rows) {
      if (r.bucket() == FuzzRun.Verdict.SAME && r.egg().get("adds") >= 10) {
        ratios.add((double) r.jegg().get("adds") / r.egg().get("adds"));
      }
    }
    Collections.sort(ratios);
    StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "Per term, jegg's adds over"
        + " egg's, terms with 10 or more adds on egg's side (%d): median %.2fx, p90 %.2fx,"
        + " max %.2fx.", ratios.size(), EggCompare.percentile(ratios, 0.5),
        EggCompare.percentile(ratios, 0.9), EggCompare.percentile(ratios, 1.0)));
    // The five terms whose adds differ most, by index, so they can be replayed.
    List<Row> far = new ArrayList<>();
    for (Row r : rows) {
      if (r.bucket() == FuzzRun.Verdict.SAME && r.egg().get("adds") >= 10) {
        far.add(r);
      }
    }
    far.sort((x, y) -> Double.compare(
        Math.abs((double) y.jegg().get("adds") / y.egg().get("adds") - 1),
        Math.abs((double) x.jegg().get("adds") / x.egg().get("adds") - 1)));
    b.append(" Farthest terms (index: jegg adds / egg adds, matches applied jegg / egg):");
    for (Row r : far.subList(0, Math.min(5, far.size()))) {
      b.append(String.format(Locale.ROOT, " %d: %d/%d, %d/%d;", r.index(),
          r.jegg().get("adds"), r.egg().get("adds"), r.jegg().get("matchesApplied"),
          r.egg().get("matches_applied")));
    }
    return b.append("\n\n").toString();
  }

  /** The summary of one language: the buckets, the quintiles, the sums, the identities. */
  static String summarize(String language, int terms, long seed, List<Row> rows) {
    StringBuilder b = new StringBuilder();
    b.append("## ").append(language).append(": ").append(terms).append(" terms of seed ")
        .append(seed).append("\n\n");
    Map<FuzzRun.Verdict, Long> buckets = new TreeMap<>();
    for (Row r : rows) {
      buckets.merge(r.bucket(), 1L, Long::sum);
    }
    b.append("Buckets: ").append(buckets).append(". The tables are over `SAME`.\n\n");
    List<Row> same = rows.stream().filter(r -> r.bucket() == FuzzRun.Verdict.SAME)
        .sorted(Comparator.comparingLong(Row::eggTotalNanos)).toList();
    b.append("### By quintile of egg's time, smallest to largest\n\n");
    b.append("| quintile | terms | egg absorbed-side parents | jegg parents visited | jegg/egg"
        + " | jegg smaller-side sum | /egg | jegg forms re-made | egg pops | jegg hash ops"
        + " | jegg/egg |\n|---|---|---|---|---|---|---|---|---|---|---|\n");
    int n = same.size();
    for (int q = 0; q < 5; q++) {
      List<Row> part = same.subList(q * n / 5, (q + 1) * n / 5);
      Counts e = Counts.NONE;
      Counts j = Counts.NONE;
      for (Row r : part) {
        e = e.plus(r.egg());
        j = j.plus(r.jegg());
      }
      b.append("| ").append(q + 1).append(" | ").append(part.size()).append(" | ")
          .append(e.get("pended_absorbed")).append(" | ").append(j.get("parentsVisited"))
          .append(" | ").append(ratio(e.get("pended_absorbed"), j.get("parentsVisited")))
          .append(" | ").append(j.get("mergeMinParents")).append(" | ")
          .append(ratio(e.get("pended_absorbed"), j.get("mergeMinParents"))).append(" | ")
          .append(j.get("formsRemade")).append(" | ").append(e.get("pops")).append(" | ")
          .append(j.get("hashconsOps")).append(" | ")
          .append(ratio(e.get("pops"), j.get("hashconsOps"))).append(" |\n");
    }
    Counts e = Counts.NONE;
    Counts j = Counts.NONE;
    List<Double> ratios = new ArrayList<>();
    for (Row r : same) {
      e = e.plus(r.egg());
      j = j.plus(r.jegg());
      if (r.egg().get("pended_absorbed") >= 10) {
        ratios.add((double) r.jegg().get("parentsVisited") / r.egg().get("pended_absorbed"));
      }
    }
    b.append("\n### All `SAME` terms, summed\n\n").append(pairsTable(e, j)).append('\n');
    b.append("\n### The apply, all `SAME` terms, summed (#154)\n\n")
        .append(pairsTable(APPLY_PAIRS, e, j)).append('\n');
    b.append(String.format(Locale.ROOT, "Per term, jegg's parents visited over egg's"
        + " absorbed-side parents, terms with 10 or more on egg's side (%d): median %.2fx,"
        + " p90 %.2fx, max %.2fx.%n%n", ratios.size(), EggCompare.percentile(ratios, 0.5),
        EggCompare.percentile(ratios, 0.9), EggCompare.percentile(ratios, 1.0)));
    b.append(identities(e, j)).append('\n');
    b.append(addsPerTerm(rows)).append(applyIdentities(e, j)).append('\n');
    return b.toString();
  }

  /** One language: egg once, jegg once, the rows, the file, the summary. */
  static String countLanguage(String language, Path eggDir, Path work, int terms, long seed,
      List<FuzzRun.Case> largestOut, PrintStream log) throws IOException, InterruptedException {
    String ruleset = language.equals("prop") ? "prop-all" : "math";
    List<FuzzRun.Case> cases = new TermGenerator(language, seed).terms(terms).stream()
        .map(t -> new FuzzRun.Case(ruleset, t, List.of())).toList();
    EggProcess egg = new EggProcess(language, eggDir, work.resolve("egg-" + language));
    List<EggProcess.Timed> eggRun = egg.runTimed(cases);
    log.println(language + ": egg done");
    List<Row> rows = new ArrayList<>(cases.size());
    for (int i = 0; i < cases.size(); i++) {
      EggProcess.Timed et = eggRun.get(i);
      FuzzRun.Timed jt = FuzzRun.timed(cases.get(i));
      if (et.counts().isBlank()) {
        throw new IllegalStateException("egg's harness carries no counts: is the patch applied?");
      }
      rows.add(new Row(language, i, FuzzRun.compare(et.result(), jt.result()), et.totalNanos(),
          jt.iterations(), jt.nodes(), Counts.egg(et.counts()), Counts.jegg(jt.counts())));
    }
    log.println(language + ": jegg done");
    Files.write(work.resolve(language + "-counts.jsonl"),
        rows.stream().map(Row::toJson).toList(), StandardCharsets.UTF_8);
    List<Row> same = rows.stream().filter(r -> r.bucket() == FuzzRun.Verdict.SAME)
        .sorted(Comparator.comparingLong(Row::eggTotalNanos)).toList();
    for (Row r : same.subList(same.size() * 4 / 5, same.size())) {
      largestOut.add(cases.get(r.index()));
    }
    return summarize(language, terms, seed, rows);
  }

  /** {@code lambda_function_repeat}: egg's test's report against the harness's deferred run. */
  static String countLambda(Path eggDir, PrintStream log) throws IOException,
      InterruptedException {
    String out = EggCompare.eggLambda(eggDir);
    EggCompare.EggReport report = EggCompare.EggReport.parse(out);
    Counts e = Counts.eggReport(out);
    Saturation.Workload repeat = Saturation.suite("lambda_function_repeat").orElseThrow();
    Saturation.Outcome o = repeat.run(Saturation.Mode.DEFERRED, Long.MAX_VALUE);
    Counts j = Counts.jegg(o.counts());
    log.println("lambda: both done");
    StringBuilder b = new StringBuilder("## lambda_function_repeat\n\n");
    b.append("egg: ").append(report.iterations()).append(" iterations, ").append(report.nodes())
        .append(" nodes; jegg: ").append(o.iterations()).append(" iterations, ")
        .append(o.nodes()).append(" nodes: ")
        .append(EggCompare.sameLambdaWork(report, o) ? "the same work"
            : "**different work, the ratios are not a comparison**")
        .append(".\n\n").append(pairsTable(e, j)).append('\n')
        .append(pairsTable(APPLY_PAIRS, e, j)).append('\n').append(identities(e, j))
        .append('\n').append(applyIdentities(e, j))
        .append('\n');
    return b.toString();
  }

  /** What a JFR recording says about the rebuild. */
  record Hot(long samples, long underRebuild, Map<String, Long> phases,
      Map<String, Long> selfUnderRebuild, Map<String, Long> ownUnderRebuild,
      long allocBytes, long allocUnderRebuild, Map<String, Long> allocByClass,
      Map<String, Long> allocByOwnFrame, Map<String, Long> allocByPhase,
      Map<String, Long> allocByClassApply, Map<String, Long> allocByOwnFrameApply) {
  }

  private static final String PKG = "io.github.vecbricks.jegg.";

  private static String frameName(RecordedFrame f) {
    String type = f.getMethod().getType().getName();
    int dot = type.lastIndexOf('.');
    return type.substring(dot + 1) + "." + f.getMethod().getName();
  }

  /** The phase a stack is in: rebuild, search, apply, or other, by the frames it holds. */
  private static String phase(List<RecordedFrame> frames) {
    boolean search = false;
    boolean apply = false;
    for (RecordedFrame f : frames) {
      String type = f.getMethod().getType().getName();
      String name = f.getMethod().getName();
      if (type.equals(PKG + "EGraph") && name.equals("rebuild")) {
        return "rebuild";
      }
      // The apply instantiates a right-hand side through Matcher.instantiate, so the apply's
      // frames are asked for before the matcher's (#146).
      if ((type.equals(PKG + "Rewrite") && name.equals("apply"))
          || (type.equals(PKG + "Matcher") && name.equals("instantiate"))
          || type.startsWith(PKG + "PatternApplier") || type.startsWith(PKG + "MultiApplier")) {
        apply = true;
      } else if (type.startsWith(PKG + "Matcher")) {
        search = true;
      }
    }
    return apply ? "apply" : search ? "search" : "other";
  }

  /** The first frame of the graph's own code, from the top. */
  private static String ownFrame(List<RecordedFrame> frames) {
    for (RecordedFrame f : frames) {
      if (f.getMethod().getType().getName().startsWith(PKG)) {
        return frameName(f);
      }
    }
    return "?";
  }

  static Hot analyze(Path jfr) throws IOException {
    long samples = 0;
    long under = 0;
    Map<String, Long> phases = new TreeMap<>();
    Map<String, Long> self = new LinkedHashMap<>();
    Map<String, Long> own = new LinkedHashMap<>();
    long alloc = 0;
    long allocUnder = 0;
    Map<String, Long> byClass = new LinkedHashMap<>();
    Map<String, Long> byOwn = new LinkedHashMap<>();
    Map<String, Long> byPhase = new TreeMap<>();
    Map<String, Long> byClassApply = new LinkedHashMap<>();
    Map<String, Long> byOwnApply = new LinkedHashMap<>();
    for (RecordedEvent e : RecordingFile.readAllEvents(jfr)) {
      String type = e.getEventType().getName();
      RecordedStackTrace st = e.getStackTrace();
      if (st == null) {
        continue;
      }
      List<RecordedFrame> frames = st.getFrames();
      if (type.equals("jdk.ExecutionSample")) {
        samples++;
        String ph = phase(frames);
        phases.merge(ph, 1L, Long::sum);
        if (ph.equals("rebuild")) {
          under++;
          self.merge(frameName(frames.get(0)), 1L, Long::sum);
          own.merge(ownFrame(frames), 1L, Long::sum);
        }
      } else if (type.equals("jdk.ObjectAllocationSample")) {
        long w = e.getLong("weight");
        alloc += w;
        String ph = phase(frames);
        byPhase.merge(ph, w, Long::sum);
        if (ph.equals("apply")) {
          byClassApply.merge(e.getClass("objectClass").getName(), w, Long::sum);
          byOwnApply.merge(ownFrame(frames), w, Long::sum);
        }
        if (ph.equals("rebuild")) {
          allocUnder += w;
          byClass.merge(e.getClass("objectClass").getName(), w, Long::sum);
          byOwn.merge(ownFrame(frames), w, Long::sum);
        }
      }
    }
    return new Hot(samples, under, phases, self, own, alloc, allocUnder, byClass, byOwn,
        byPhase, byClassApply, byOwnApply);
  }

  private static String top(Map<String, Long> m, long total, int n, boolean bytes) {
    StringBuilder b = new StringBuilder();
    m.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .limit(n).forEach(en -> b.append("| ").append(en.getKey()).append(" | ")
            .append(bytes ? String.format(Locale.ROOT, "%.1f MB", en.getValue() / 1e6)
                : String.valueOf(en.getValue()))
            .append(" | ").append(String.format(Locale.ROOT, "%.0f%%",
                total == 0 ? 0 : 100.0 * en.getValue() / total)).append(" |\n"));
    return b.toString();
  }

  /** The profile's text: the sample counts, the phases, the self and own frames, the bytes. */
  static String describe(String what, Hot h, long parentsVisited, long runs) {
    return describe(what, h, parentsVisited, 0, runs);
  }

  /** As above, with the adds of the runs, for the apply's bytes per add (#154). */
  static String describe(String what, Hot h, long parentsVisited, long adds, long runs) {
    StringBuilder b = new StringBuilder("## Profile: ").append(what).append("\n\n");
    b.append(runs).append(" runs; ").append(h.samples()).append(" execution samples, ")
        .append(h.underRebuild()).append(" under `EGraph.rebuild`");
    if (h.underRebuild() < 1000) {
      b.append(" (**thin: under 1,000, lengthen the run before scoring**)");
    }
    b.append(". Phases by samples: ").append(h.phases()).append(".\n\n");
    b.append("Self frames under the rebuild:\n\n| frame | samples | of rebuild |\n|---|---|---|\n")
        .append(top(h.selfUnderRebuild(), h.underRebuild(), 15, false));
    b.append("\nFirst jegg frame under the rebuild (where the time is charged):\n\n"
        + "| frame | samples | of rebuild |\n|---|---|---|\n")
        .append(top(h.ownUnderRebuild(), h.underRebuild(), 12, false));
    b.append(String.format(Locale.ROOT, "%nAllocation (sampled, weighted): %.1f MB in all,"
        + " %.1f MB under the rebuild (%.0f%%), %d parents visited: %.0f bytes per parent"
        + " visited.%n%n", h.allocBytes() / 1e6, h.allocUnderRebuild() / 1e6,
        h.allocBytes() == 0 ? 0 : 100.0 * h.allocUnderRebuild() / h.allocBytes(),
        parentsVisited, parentsVisited == 0 ? 0 : (double) h.allocUnderRebuild()
            / parentsVisited));
    long underApply = h.allocByPhase().getOrDefault("apply", 0L);
    b.append(String.format(Locale.ROOT, "Allocation by phase: %s; %.1f MB under the apply"
        + " (%.0f%%), %d adds: %.0f bytes per add.%n%n", h.allocByPhase(), underApply / 1e6,
        h.allocBytes() == 0 ? 0 : 100.0 * underApply / h.allocBytes(), adds,
        adds == 0 ? 0 : (double) underApply / adds));
    b.append("| class | bytes | of apply |\n|---|---|---|\n")
        .append(top(h.allocByClassApply(), underApply, 10, true));
    b.append("\n| first jegg frame | bytes | of apply |\n|---|---|---|\n")
        .append(top(h.allocByOwnFrameApply(), underApply, 10, true)).append('\n');
    b.append("Under the rebuild:\n\n| class | bytes | of rebuild |\n|---|---|---|\n")
        .append(top(h.allocByClass(), h.allocUnderRebuild(), 10, true));
    b.append("\n| first jegg frame | bytes | of rebuild |\n|---|---|---|\n")
        .append(top(h.allocByOwnFrame(), h.allocUnderRebuild(), 10, true)).append('\n');
    return b.toString();
  }

  /** Records {@code work} for at least {@code seconds} and returns what the recording says. */
  static String profile(String what, Path jfr, int seconds, Runnable warm, ProbeRun work)
      throws IOException, ParseException {
    warm.run();
    Counts total = null;
    long runs = 0;
    try (Recording r = new Recording(Configuration.getConfiguration("profile"))) {
      r.start();
      long until = System.nanoTime() + seconds * 1_000_000_000L;
      do {
        Counts c = work.run();
        total = total == null ? c : total.plus(c);
        runs++;
      } while (System.nanoTime() < until);
      r.stop();
      r.dump(jfr);
    }
    return describe(what, analyze(jfr), total.get("parentsVisited"), total.get("adds"), runs);
  }

  /** One pass of a profiled workload; returns jegg's counts of it. */
  interface ProbeRun {
    Counts run();
  }

  /**
   * Runs the probe and writes its files.
   *
   * @param args {@code --egg <egg checkout> --out <dir> [--terms N] [--seed S]
   *     [--languages prop,math] [--lambda true] [--profile-seconds 20]}
   * @throws IOException if a file cannot be written or egg cannot run
   * @throws InterruptedException if interrupted
   * @throws ParseException if the JDK's profile settings do not parse
   */
  public static void main(String[] args) throws IOException, InterruptedException,
      ParseException {
    Map<String, String> o = new LinkedHashMap<>();
    for (int i = 0; i + 1 < args.length; i += 2) {
      o.put(args[i], args[i + 1]);
    }
    if (!o.containsKey("--egg") || !o.containsKey("--out")) {
      System.err.println("usage: RebuildProbe --egg <egg checkout> --out <dir> [--terms N]"
          + " [--seed S] [--languages prop,math] [--lambda true] [--profile-seconds 20]");
      System.exit(2);
    }
    Path eggDir = Path.of(o.get("--egg")).toAbsolutePath();
    Path out = Path.of(o.get("--out")).toAbsolutePath();
    Files.createDirectories(out);
    int terms = Integer.parseInt(o.getOrDefault("--terms", "1000"));
    long seed = Long.parseLong(o.getOrDefault("--seed", "2"));
    int seconds = Integer.parseInt(o.getOrDefault("--profile-seconds", "20"));
    List<String> languages = Arrays.asList(o.getOrDefault("--languages", "prop,math")
        .split(","));
    boolean lambda = Boolean.parseBoolean(o.getOrDefault("--lambda", "true"));
    StringBuilder summary = new StringBuilder(
        "# The rebuild's and the apply's work on both sides (#134, #154)\n\n");
    Map<String, List<FuzzRun.Case>> largest = new LinkedHashMap<>();
    for (String language : languages) {
      List<FuzzRun.Case> cases = new ArrayList<>();
      summary.append(countLanguage(language, eggDir, out, terms, seed, cases, System.err));
      largest.put(language, cases);
    }
    if (lambda) {
      summary.append(countLambda(eggDir, System.err));
    }
    for (String language : languages) {
      List<FuzzRun.Case> cases = largest.get(language);
      String text = profile(language + ", the largest SAME quintile (" + cases.size()
          + " terms)", out.resolve(language + ".jfr"), seconds,
          () -> cases.forEach(FuzzRun::timed), () -> {
            Counts t = null;
            for (FuzzRun.Case c : cases) {
              Counts one = Counts.jegg(FuzzRun.timed(c).counts());
              t = t == null ? one : t.plus(one);
            }
            return t;
          });
      Files.writeString(out.resolve("profile-" + language + ".md"), text,
          StandardCharsets.UTF_8);
      summary.append(text);
      System.err.println(language + ": profiled");
    }
    if (lambda) {
      Saturation.Workload repeat = Saturation.suite("lambda_function_repeat").orElseThrow();
      String text = profile("lambda_function_repeat", out.resolve("lambda.jfr"), seconds,
          () -> repeat.run(Saturation.Mode.DEFERRED, Long.MAX_VALUE),
          () -> Counts.jegg(repeat.run(Saturation.Mode.DEFERRED, Long.MAX_VALUE).counts()));
      Files.writeString(out.resolve("profile-lambda.md"), text, StandardCharsets.UTF_8);
      summary.append(text);
      System.err.println("lambda: profiled");
    }
    Files.writeString(out.resolve("summary.md"), summary.toString(), StandardCharsets.UTF_8);
    System.out.print(summary);
  }
}
