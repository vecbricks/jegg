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
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The property this port adds to egg (PLAN.md 2 and 3.1): a run's result is a function of its
 * input. In process the same saturation is run several times; across processes
 * {@link DeterminismProbe} is forked in fresh JVMs, where any hash iteration order that reached
 * an id would show as a different rendering.
 */
class DeterminismTest {

  @Test
  void repeatedRunsInOneJvmRenderTheSame() {
    String first = DeterminismProbe.saturate();
    for (int i = 0; i < 5; i++) {
      assertEquals(first, DeterminismProbe.saturate());
    }
    assertTrue(first.contains("stopped: NodeLimit"), first);
  }

  @Test
  void freshJvmsRenderTheSame() throws Exception {
    String javaHome = System.getProperty("java.home");
    String classpath = System.getProperty("java.class.path");
    String first = null;
    for (int i = 0; i < 3; i++) {
      File out = File.createTempFile("jegg-probe", ".txt");
      out.deleteOnExit();
      Process p = new ProcessBuilder(List.of(javaHome + "/bin/java", "-cp", classpath,
          DeterminismProbe.class.getName())).redirectOutput(out)
          .redirectError(ProcessBuilder.Redirect.INHERIT).start();
      // A hung probe must fail the test, not stall the build.
      if (!p.waitFor(2, TimeUnit.MINUTES)) {
        p.destroyForcibly();
        fail("the probe did not finish in two minutes");
      }
      assertEquals(0, p.exitValue(), "the probe failed");
      String rendered = Files.readString(out.toPath(), StandardCharsets.UTF_8);
      assertTrue(rendered.contains("stopped: NodeLimit"), rendered);
      if (first == null) {
        first = rendered;
      } else {
        assertEquals(first, rendered, "run " + i + " differs");
      }
    }
    assertEquals(DeterminismProbe.saturate(), first, "in-process and forked differ");
  }
}
