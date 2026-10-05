/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.jegg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatFactory;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * The measurement of PLAN.md 6, run as {@code mvn -Pbench -q test-compile exec:exec
 * -Dbench=<name>[,<name>]}: each named benchmark through JMH's API, its results written under
 * {@code benchmarks/} as {@code <Name>-jdk<N>-results.txt} with the provenance header
 * CONTRIBUTING.md asks for - the JVM, the OS, the processor, the commit, the date, the load
 * average before and after, and the JMH settings. {@code -Dbench.quick=true} runs a short
 * version to check the harness, whose numbers are not for committing.
 *
 * <p>The method is Varka's ({@code dev/varka_bench_regen.sh}). A heterogeneous machine - this
 * one has four Zen 5 cores at 5.16 GHz and eight Zen 5c at 3.29 GHz on separate L3 slices -
 * reschedules an unpinned benchmark thread between core types during a run, which Varka measured
 * at 1.57x on the clock alone and 4x on a working set that leaves its L3. So the harness pins
 * itself, and the JMH forks it starts, to the cores sharing cpu0's L3 whose maximum clock is the
 * package's ({@code taskset}; a no-op in effect on a uniform machine; {@code -Dbench.pin=none}
 * to skip, which the header then says). It refuses to start on a busy machine, a one-minute
 * load average above 1.0, unless {@code -Dbench.force=true}; and it records the governor, the
 * energy preference and the platform profile, since the same code on the same machine has
 * measured apart on different days with those changed.
 */
public final class Benchmarks {

  private static final String BANNER = "=".repeat(96);
  // The commit, read once before anything is written: a results file this run writes is an
  // untracked file, which would otherwise mark the next file in the same run dirty.
  private static String commit;

  private Benchmarks() {
  }

  /** The benchmarks the harness runs, in the order {@code all} runs them. */
  private static final List<String> NAMES = List.of("rebuild", "projection", "determinism");

  /**
   * What {@code --help} prints, and what the README's command block quotes: a test holds each
   * line of it to the README.
   */
  static final String USAGE = """
      mvn -Pbench -q test-compile exec:exec -Dbench=<rebuild|projection|determinism|all>[,<name>]

      Benchmarks (-Dbench=, comma separated, default all):
        rebuild      deferred against eager rebuilding, one saturation of each ported test
        projection   a projection of 64 nodes over the toy date language: saturation, extraction
        determinism  DeterminismProbe in ten fresh JVMs, the renderings compared byte for byte
        all          the three above

      Properties:
        -Dbench.quick=true    a short run that checks the harness; its numbers are not for
                              committing (default false)
        -Dbench.force=true    run although the one-minute load average is above 1.0 (default
                              false)
        -Dbench.pin=<auto|none|cpu list>
                              the cores to pin to: the fast cores sharing cpu0's L3, none, or
                              a taskset list such as 0-3,12-15 (default auto)

      --help, -h, or -Dbench=help prints this text.""";

  /**
   * Checks the arguments before anything is started: an empty result means run; otherwise the
   * usage has been printed and the value is the exit status. {@code --help}, {@code -h} and
   * {@code -Dbench=help} are a request, answered on {@code out} with status 0; a benchmark name
   * that is not known is an error, named on {@code err} with the usage, status 2.
   */
  static OptionalInt check(String[] args, String bench, PrintStream out, PrintStream err) {
    for (String a : args) {
      if (a.equals("--help") || a.equals("-h")) {
        out.println(USAGE);
        return OptionalInt.of(0);
      }
    }
    if (bench.equals("help")) {
      out.println(USAGE);
      return OptionalInt.of(0);
    }
    for (String name : bench.split(",", -1)) {
      if (!name.equals("all") && !NAMES.contains(name)) {
        err.println("unknown benchmark '" + name + "'");
        err.println();
        err.println(USAGE);
        return OptionalInt.of(2);
      }
    }
    return OptionalInt.empty();
  }

  public static void main(String[] args) throws Exception {
    String bench = System.getProperty("bench", "all");
    OptionalInt status = check(args, bench, System.out, System.err);
    if (status.isPresent()) {
      System.exit(status.getAsInt());
    }
    java.util.Set<String> which = java.util.Set.of(bench.split(","));
    boolean quick = Boolean.getBoolean("bench.quick");
    if (Machine.relaunchPinned(args)) {
      return;
    }
    commit = Report.commit();
    // One load check for every benchmark named: a run of its own would trip the next one's.
    double load = Report.load();
    if (!quick && !Boolean.getBoolean("bench.force") && load > 1.0) {
      System.err.printf("load average is %.2f: the machine is not idle. Wait, or pass"
          + " -Dbench.force=true.%n", load);
      System.exit(1);
    }
    if (which.contains("all") || which.contains("rebuild")) {
      rebuild(quick);
    }
    if (which.contains("all") || which.contains("projection")) {
      projection(quick);
    }
    if (which.contains("all") || which.contains("determinism")) {
      determinism(quick);
    }
  }

  /** Deferred against eager rebuilding on the ported suites: PLAN.md 6, prediction 1. */
  private static void rebuild(boolean quick) throws Exception {
    Report report = new Report("RebuildBenchmark",
        "Deferred against eager rebuilding, one saturation of each ported test");
    report.jmh("Single-shot time per saturation, ms",
        base(quick).include(RebuildBenchmark.class.getSimpleName())
            .mode(org.openjdk.jmh.annotations.Mode.SingleShotTime)
            .timeUnit(TimeUnit.MILLISECONDS)
            .warmupIterations(quick ? 0 : 2).measurementIterations(quick ? 1 : 3)
            .forks(quick ? 1 : 5).build());
    // The count of classes repaired in each mode, from one run each: what the time tracks.
    StringBuilder b = new StringBuilder(String.format("%-26s %9s %12s %10s %12s %s%n",
        "suite", "mode", "iterations", "repaired", "nodes", "stop"));
    for (var e : Saturation.suites().entrySet()) {
      boolean large = Saturation.large(e.getKey());
      for (Saturation.Mode mode : Saturation.Mode.values()) {
        if (quick && e.getKey().startsWith("lambda_f")) {
          continue;
        }
        // The large runs are eager under a cap: ten minutes, or ten seconds in a quick run.
        long cap = mode == Saturation.Mode.EAGER && large
            ? (quick ? TimeUnit.SECONDS.toNanos(10) : TimeUnit.MINUTES.toNanos(10))
            : TimeUnit.HOURS.toNanos(1);
        long start = System.nanoTime();
        Saturation.Outcome o = e.getValue().run(mode, System.nanoTime() + cap);
        double seconds = (System.nanoTime() - start) / 1e9;
        b.append(String.format("%-26s %9s %12d %10d %12d %s%s%n", e.getKey(), mode,
            o.iterations(), o.repaired(), o.nodes(), o.stop(),
            large ? String.format(" (%.1f s, one run)", seconds) : ""));
      }
    }
    report.section("Classes repaired per run, one run each; the three large runs timed, their"
        + " eager run capped at ten minutes", b.toString());
    report.write();
  }

  /** The absolute cost at Varka's size: PLAN.md 6, prediction 4. */
  private static void projection(boolean quick) throws Exception {
    Report report = new Report("ProjectionBenchmark",
        "A projection of 64 nodes over the toy date language, 20 rules: saturation and extraction");
    StringBuilder shape = new StringBuilder();
    for (int limit : new int[] {200, 1_000}) {
      shape.append(Projection.shape(limit, Long.MAX_VALUE)).append('\n');
    }
    // egg's default limit, not measured: shown once, under a deadline, for where it goes.
    long cap = TimeUnit.SECONDS.toNanos(quick ? 5 : 120);
    shape.append(Projection.shape(10_000, System.nanoTime() + cap)).append('\n');
    report.section("The runs the timings are of (the third stopped by a deadline hook if it"
        + " says so)", shape.toString());
    for (boolean compact : new boolean[] {false, true}) {
      String headers = compact ? "with -XX:+UseCompactObjectHeaders" : "default object headers";
      String[] jvmArgs = compact ? new String[] {"-XX:+UseCompactObjectHeaders"}
          : new String[0];
      report.jmh("Warm, average time per operation in us, with bytes allocated (gc profiler), "
              + headers,
          base(quick).include(ProjectionBenchmark.class.getSimpleName())
              .mode(org.openjdk.jmh.annotations.Mode.AverageTime)
              .timeUnit(TimeUnit.MICROSECONDS)
              .warmupIterations(quick ? 1 : 5).warmupTime(TimeValue.seconds(1))
              .measurementIterations(quick ? 2 : 10).measurementTime(TimeValue.seconds(1))
              .forks(quick ? 1 : 5).jvmArgsAppend(jvmArgs)
              .addProfiler("gc").build());
      report.jmh("Cold, the first call in a fresh JVM, single shot in us, " + headers,
          base(quick).include(ProjectionBenchmark.class.getSimpleName())
              .mode(org.openjdk.jmh.annotations.Mode.SingleShotTime)
              .timeUnit(TimeUnit.MICROSECONDS)
              .warmupIterations(0).measurementIterations(1)
              .forks(quick ? 1 : 5).jvmArgsAppend(jvmArgs).build());
    }
    report.write();
  }

  /** Ten fresh JVMs render the same graph: PLAN.md 6, prediction 3. */
  private static void determinism(boolean quick) throws Exception {
    Report report = new Report("DeterminismRun",
        "DeterminismProbe in fresh JVMs, the renderings compared byte for byte");
    report.section("Forked JVMs", DeterminismRun.run(quick ? 2 : 10));
    report.write();
  }

  private static ChainedOptionsBuilder base(boolean quick) {
    return new OptionsBuilder().shouldFailOnError(true).shouldDoGC(true);
  }

  /** The machine: which cores the run is pinned to, and how they are set. */
  private static final class Machine {
    private static final Path CPUS = Path.of("/sys/devices/system/cpu");

    /**
     * Relaunches this JVM under {@code taskset} on the fast cores if it is not pinned to them
     * yet; true if it did, and the caller returns, the child having done the work. The child
     * inherits the classpath, the system properties and the pin as a property, so it does not
     * relaunch again. JMH's forks inherit the affinity from their parent.
     */
    static boolean relaunchPinned(String[] args) throws IOException, InterruptedException {
      String pin = System.getProperty("bench.pin", "auto");
      if (pin.equals("none") || System.getProperty("bench.pinned") != null
          || !Files.isDirectory(CPUS.resolve("cpu0/cpufreq"))) {
        return false;
      }
      String cpus = pin.equals("auto") ? fastCores() : pin;
      if (cpus.isEmpty()) {
        return false;
      }
      List<String> command = new ArrayList<>(List.of("taskset", "-c", cpus,
          System.getProperty("java.home") + "/bin/java", "-cp",
          System.getProperty("java.class.path"), "-Dbench.pinned=" + cpus));
      for (String key : List.of("bench", "bench.quick", "bench.force")) {
        if (System.getProperty(key) != null) {
          command.add("-D" + key + "=" + System.getProperty(key));
        }
      }
      command.add(Benchmarks.class.getName());
      command.addAll(List.of(args));
      Process p = new ProcessBuilder(command).inheritIO().start();
      int code = p.waitFor();
      if (code != 0) {
        System.exit(code);
      }
      return true;
    }

    /** The cores sharing cpu0's L3 whose maximum clock is the package's, as Varka picks them. */
    static String fastCores() throws IOException {
      long max = 0;
      Map<Integer, Long> clocks = new java.util.TreeMap<>();
      try (var dirs = Files.list(CPUS)) {
        for (Path d : dirs.toList()) {
          String name = d.getFileName().toString();
          Path freq = d.resolve("cpufreq/cpuinfo_max_freq");
          if (name.matches("cpu\\d+") && Files.exists(freq)) {
            long f = Long.parseLong(Files.readString(freq).trim());
            clocks.put(Integer.parseInt(name.substring(3)), f);
            max = Math.max(max, f);
          }
        }
      }
      java.util.Set<Integer> l3 = cpuList(read(CPUS.resolve("cpu0/cache/index3/shared_cpu_list")));
      List<String> fast = new ArrayList<>();
      for (var e : clocks.entrySet()) {
        if (e.getValue() == max && (l3.isEmpty() || l3.contains(e.getKey()))) {
          fast.add(e.getKey().toString());
        }
      }
      return String.join(",", fast);
    }

    private static java.util.Set<Integer> cpuList(String list) {
      java.util.Set<Integer> out = new java.util.HashSet<>();
      if (list.isBlank()) {
        return out;
      }
      for (String part : list.trim().split(",")) {
        String[] range = part.split("-");
        int lo = Integer.parseInt(range[0]);
        int hi = Integer.parseInt(range[range.length - 1]);
        for (int i = lo; i <= hi; i++) {
          out.add(i);
        }
      }
      return out;
    }

    static String read(Path p) {
      try {
        return Files.readString(p).trim();
      } catch (IOException e) {
        return "";
      }
    }

    /** The pin and the power settings, for the header. */
    static String describe() throws IOException {
      String pinned = System.getProperty("bench.pinned");
      String allowed = read(Path.of("/proc/self/status")).lines()
          .filter(l -> l.startsWith("Cpus_allowed_list")).map(l -> l.substring(l.indexOf(':') + 1)
              .trim()).findFirst().orElse("unknown");
      StringBuilder b = new StringBuilder();
      b.append(pinned != null ? "pinned to cpus " + pinned
          : "not pinned (numbers are not comparable with a pinned run), cpus " + allowed);
      Path cpu0 = CPUS.resolve("cpu0/cpufreq");
      if (Files.isDirectory(cpu0)) {
        b.append("; max clock ").append(read(cpu0.resolve("cpuinfo_max_freq"))).append(" kHz")
            .append(", governor ").append(read(cpu0.resolve("scaling_governor")))
            .append(", energy preference ")
            .append(read(cpu0.resolve("energy_performance_preference")));
      }
      String profile = read(Path.of("/sys/firmware/acpi/platform_profile"));
      if (!profile.isEmpty()) {
        b.append(", platform profile ").append(profile);
      }
      return b.toString();
    }
  }

  /** One results file: the header, then each section as it is produced. */
  private static final class Report {
    private final String name;
    private final StringBuilder body = new StringBuilder();
    private final double loadBefore = load();
    private final List<String> settings = new ArrayList<>();

    Report(String name, String title) {
      this.name = name;
      body.append(BANNER).append('\n').append(title).append('\n').append(BANNER).append("\n\n");
    }

    void jmh(String title, Options options) throws RunnerException {
      settings.add(title + ": " + describe(options));
      Collection<RunResult> results = new Runner(options).run();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8)) {
        ResultFormatFactory.getInstance(ResultFormatType.TEXT, ps).writeOut(results);
      }
      section(title, out.toString(StandardCharsets.UTF_8));
    }

    void section(String title, String text) {
      body.append(title).append(":\n").append(text.stripTrailing()).append("\n\n");
    }

    void write() throws IOException, InterruptedException {
      StringBuilder header = new StringBuilder();
      header.append(System.getProperty("java.vm.name")).append(' ')
          .append(System.getProperty("java.runtime.version")).append(" on ")
          .append(System.getProperty("os.name")).append(' ')
          .append(System.getProperty("os.version")).append('\n');
      header.append(processor()).append(", ")
          .append(Runtime.getRuntime().availableProcessors()).append(" threads\n");
      header.append(Machine.describe()).append('\n');
      header.append("commit ").append(commit).append(", ")
          .append(ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
          .append('\n');
      header.append(String.format("load average before %.2f, after %.2f%n", loadBefore,
          load()));
      header.append("JMH ").append(jmhVersion()).append("; mvn -Pbench -q test-compile exec:exec"
          + " -Dbench=").append(name.toLowerCase().replace("benchmark", "").replace("run", ""))
          .append('\n');
      for (String s : settings) {
        header.append("  ").append(s).append('\n');
      }
      Path file = Path.of("benchmarks",
          name + "-jdk" + Runtime.version().feature() + "-results.txt");
      Files.createDirectories(file.getParent());
      Files.writeString(file, body.substring(0, body.indexOf("\n\n") + 2) + header + "\n"
          + body.substring(body.indexOf("\n\n") + 2));
      System.out.println("wrote " + file);
    }

    private static String describe(Options o) {
      return "mode " + o.getBenchModes() + ", forks " + o.getForkCount().orElse(-1)
          + ", warmup " + o.getWarmupIterations().orElse(-1) + " x "
          + o.getWarmupTime().orElse(null) + ", measurement "
          + o.getMeasurementIterations().orElse(-1) + " x " + o.getMeasurementTime().orElse(null)
          + ", jvm args " + o.getJvmArgsAppend().orElse(List.of());
    }

    static double load() {
      return ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
    }

    private static String processor() {
      try {
        return Files.readAllLines(Path.of("/proc/cpuinfo")).stream()
            .filter(l -> l.startsWith("model name")).findFirst()
            .map(l -> l.substring(l.indexOf(':') + 1).trim()).orElse("unknown processor");
      } catch (IOException e) {
        return "unknown processor";
      }
    }

    /** The commit, marked dirty if the tree has uncommitted changes: such a file is not final. */
    static String commit() throws IOException, InterruptedException {
      String head = git("rev-parse", "--short", "HEAD");
      if (head == null) {
        return "unknown";
      }
      return git("status", "--porcelain").isEmpty() ? head : head + "+dirty";
    }

    private static String git(String... args) throws IOException, InterruptedException {
      List<String> command = new ArrayList<>(List.of("git"));
      command.addAll(List.of(args));
      Process p = new ProcessBuilder(command).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      return p.waitFor() == 0 ? out : null;
    }

    private static String jmhVersion() {
      String v = Runner.class.getPackage().getImplementationVersion();
      return v == null ? "1.37" : v;
    }
  }
}
