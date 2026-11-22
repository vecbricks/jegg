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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * egg's side of the differential fuzzing (#44): the built {@code fuzz_<language>} test of an egg
 * checkout (see {@code dev/fuzz.sh}) run over a batch of cases through {@code cargo}. One batch
 * is one process, so a shrinking round of many candidates costs one start.
 */
final class EggProcess {

  private final String language;
  private final Path eggDir;
  private final Path workDir;
  private int batches;

  /**
   * An egg runner.
   *
   * @param language {@code prop} or {@code math}
   * @param eggDir the egg checkout, whose {@code tests/fuzz_<language>.rs} is built
   * @param workDir where the batches' files are written
   */
  EggProcess(String language, Path eggDir, Path workDir) {
    this.language = language;
    this.eggDir = eggDir;
    this.workDir = workDir;
  }

  /**
   * Runs egg over the cases.
   *
   * @param cases the cases, in order
   * @return egg's result line for each, in order
   * @throws IOException if the process cannot run, or fails
   */
  List<String> run(List<FuzzRun.Case> cases) throws IOException, InterruptedException {
    return runTimed(cases).stream().map(Timed::result).toList();
  }

  /**
   * egg's result line for a case and the times its runner's report gives, in nanoseconds
   * (#72): the search, apply and rebuild phases summed over the iterations, and egg's total,
   * which is the three plus the hooks.
   *
   * @param result the result line, without the times
   * @param searchNanos the search phase
   * @param applyNanos the apply phase
   * @param rebuildNanos the rebuild
   * @param totalNanos egg's own total
   * @param counts the rebuild's counters from {@code dev/fuzz/probe.patch} (#134), space
   *     separated in {@code egg::probe::NAMES} order, or empty where the harness has none
   */
  record Timed(String result, long searchNanos, long applyNanos, long rebuildNanos,
      long totalNanos, String counts) {
    /** The line as the harness writes it: the result, then {@code times s a r t}. */
    static Timed parse(String line) {
      int at = line.lastIndexOf(" times ");
      if (at < 0) {
        return new Timed(line, 0, 0, 0, 0, "");
      }
      String[] t = line.substring(at + " times ".length()).trim().split(" ");
      if (t.length != 4) {
        throw new IllegalArgumentException("not four times: " + line);
      }
      return new Timed(line.substring(0, at), Long.parseLong(t[0]), Long.parseLong(t[1]),
          Long.parseLong(t[2]), Long.parseLong(t[3]), "");
    }

    Timed withCounts(String c) {
      return new Timed(result, searchNanos, applyNanos, rebuildNanos, totalNanos, c);
    }
  }

  /**
   * Runs egg over the cases and keeps the times.
   *
   * @param cases the cases, in order
   * @return egg's result and times for each, in order
   * @throws IOException if the process cannot run, or fails
   */
  List<Timed> runTimed(List<FuzzRun.Case> cases) throws IOException, InterruptedException {
    Files.createDirectories(workDir);
    Path terms = workDir.resolve(language + "-" + (++batches) + ".terms").toAbsolutePath();
    Path out = workDir.resolve(language + "-" + batches + ".egg").toAbsolutePath();
    Path counts = workDir.resolve(language + "-" + batches + ".counts").toAbsolutePath();
    Files.write(terms, cases.stream().map(FuzzRun.Case::line).toList(), StandardCharsets.UTF_8);
    Files.deleteIfExists(out);
    Files.deleteIfExists(counts);
    ProcessBuilder pb = new ProcessBuilder("cargo", "test", "-q", "--release", "--test",
        "fuzz_" + language, "--", "--exact", "fuzz_run").directory(eggDir.toFile())
        .redirectErrorStream(true);
    Map<String, String> env = pb.environment();
    env.put("FUZZ_TERMS", terms.toString());
    env.put("FUZZ_OUT", out.toString());
    env.put("FUZZ_COUNTS", counts.toString());
    Process p = pb.start();
    String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (p.waitFor() != 0 || !Files.exists(out)) {
      throw new IOException("egg's fuzz_" + language + " failed:\n" + log);
    }
    List<Timed> results = new ArrayList<>();
    for (String line : Files.readAllLines(out, StandardCharsets.UTF_8)) {
      int space = line.indexOf(' ');
      int index = Integer.parseInt(line.substring(0, space));
      if (index != results.size()) {
        throw new IOException("egg's results skip a case at " + index);
      }
      results.add(Timed.parse(line.substring(space + 1)));
    }
    if (results.size() != cases.size()) {
      throw new IOException("egg answered " + results.size() + " of " + cases.size() + " cases");
    }
    if (Files.exists(counts)) {
      List<String> lines = Files.readAllLines(counts, StandardCharsets.UTF_8);
      if (lines.size() != results.size()) {
        throw new IOException("egg counted " + lines.size() + " of " + results.size() + " runs");
      }
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        results.set(i, results.get(i).withCounts(line.substring(line.indexOf(' ') + 1)));
      }
    }
    return results;
  }
}
