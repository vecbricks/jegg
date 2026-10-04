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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Prediction 3 of PLAN.md 6.1: {@link DeterminismProbe} forked in ten fresh JVMs, on this
 * classpath, renders the same graph byte for byte. The test does three; the measurement does
 * ten and records the hash.
 */
final class DeterminismRun {

  private DeterminismRun() {
  }

  static String run(int jvms) throws IOException, InterruptedException {
    List<String> renderings = new ArrayList<>();
    long start = System.nanoTime();
    for (int i = 0; i < jvms; i++) {
      Process p = new ProcessBuilder(List.of(System.getProperty("java.home") + "/bin/java",
          "-cp", System.getProperty("java.class.path"), DeterminismProbe.class.getName()))
          .redirectError(ProcessBuilder.Redirect.INHERIT).start();
      byte[] out = p.getInputStream().readAllBytes();
      if (!p.waitFor(2, TimeUnit.MINUTES) || p.exitValue() != 0) {
        p.destroyForcibly();
        throw new IllegalStateException("probe " + i + " failed");
      }
      renderings.add(new String(out, StandardCharsets.UTF_8));
    }
    long elapsed = System.nanoTime() - start;
    boolean identical = renderings.stream().distinct().count() == 1;
    String first = renderings.get(0);
    return String.format("%d fresh JVMs, %s renderings%n%d classes rendered, sha-256 %s%n"
            + "%.1f s in all, %.0f ms per JVM including its start%n",
        jvms, identical ? "byte-identical" : "DIFFERING", first.lines().count(), sha256(first),
        elapsed / 1e9, elapsed / 1e6 / jvms);
  }

  private static String sha256(String s) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
