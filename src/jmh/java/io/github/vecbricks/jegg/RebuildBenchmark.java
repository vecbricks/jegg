/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.jegg;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/**
 * Deferred against eager rebuilding on the ported suites (PLAN.md 6, prediction 1): each run
 * is one saturation of a prop, lambda or math test, from a fresh graph, with one
 * {@code rebuild} per iteration or one after every merge. Single-shot, since a run is the unit.
 * The three runs that grow past a few thousand nodes (prop's fold, lambda's fib and repeat) are
 * not here: eager rebuilding takes minutes on them, so {@link Benchmarks} times them once each,
 * eager under a
 * cap.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class RebuildBenchmark {

  @Param({"prove_contrapositive", "prove_chain", "lambda_under", "lambda_if_elim",
      "lambda_let_simple", "lambda_capture", "lambda_capture_free", "lambda_closure_not_seven",
      "lambda_compose", "lambda_if_simple", "lambda_compose_many", "lambda_if", "math_fail",
      "math_simplify_add", "math_powers", "math_simplify_const", "math_simplify_root",
      "math_simplify_factor", "math_diff_same", "math_diff_different", "math_diff_simple1",
      "math_diff_simple2", "math_diff_ln", "diff_power_simple", "diff_power_harder", "integ_one",
      "integ_sin", "integ_x", "integ_part1", "integ_part2", "integ_part3"})
  public String suite;

  @Param({"DEFERRED", "EAGER"})
  public String mode;

  @Benchmark
  public Saturation.Outcome saturate() {
    return Saturation.suite(suite).orElseThrow()
        .run(Saturation.Mode.valueOf(mode), Long.MAX_VALUE);
  }
}
