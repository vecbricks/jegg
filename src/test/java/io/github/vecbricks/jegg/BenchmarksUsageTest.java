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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The measurement harness's argument check: it answers before JMH, the pin or the load check. */
class BenchmarksUsageTest {

  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
  private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

  private OptionalInt check(String bench, String... args) {
    return Benchmarks.check(args, bench, out, err);
  }

  private String out() {
    return outBytes.toString(StandardCharsets.UTF_8);
  }

  private String err() {
    return errBytes.toString(StandardCharsets.UTF_8);
  }

  @Test
  void anUnknownNameIsNamedWithTheUsageOnStderrAndExitsTwo() {
    assertEquals(OptionalInt.of(2), check("rebuild,nope"));
    assertTrue(err().startsWith("unknown benchmark 'nope'"), err());
    assertTrue(err().contains(Benchmarks.USAGE));
    assertEquals("", out());
  }

  @Test
  void anEmptyNameIsUnknownToo() {
    assertEquals(OptionalInt.of(2), check(""));
    assertEquals(OptionalInt.of(2), check("rebuild,"));
    assertTrue(err().contains("unknown benchmark ''"), err());
  }

  @Test
  void helpIsAnsweredOnStdoutWithStatusZeroWhateverTheNames() {
    assertEquals(OptionalInt.of(0), check("rebuild", "--help"));
    assertEquals(OptionalInt.of(0), check("nope", "-h"));
    assertEquals(OptionalInt.of(0), check("help"));
    assertEquals(3, out().split(Pattern.quote(Benchmarks.USAGE), -1).length - 1,
        "the usage, once per request");
    assertEquals("", err());
  }

  @Test
  void theKnownNamesRun() {
    for (String bench : new String[] {"all", "rebuild", "projection", "determinism",
        "rebuild,projection,determinism"}) {
      assertEquals(OptionalInt.empty(), check(bench), bench);
    }
    assertEquals("", out());
    assertEquals("", err());
  }

  @Test
  void theUsageNamesEveryBenchmarkAndPropertyTheHarnessReads() {
    for (String word : new String[] {"rebuild", "projection", "determinism", "all",
        "bench.quick", "bench.force", "bench.pin"}) {
      assertTrue(Benchmarks.USAGE.contains(word), word);
    }
  }

  @Test
  void theReadmeQuotesTheUsageLineForLine() throws IOException {
    String readme = Files.readString(Path.of("benchmarks/README.md"));
    for (String line : Benchmarks.USAGE.split("\n")) {
      if (!line.isBlank()) {
        assertTrue(readme.contains("    " + line), "README lacks: " + line);
      }
    }
    assertFalse(readme.contains("-Dbench=<rebuild|projection|determinism|all>[,<name>]\n\nand"),
        "the old command block is replaced");
  }
}
