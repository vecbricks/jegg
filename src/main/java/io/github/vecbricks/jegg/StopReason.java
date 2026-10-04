/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

/** Why a run ended. */
public sealed interface StopReason {

  /** No rule changed the graph in the last iteration, and the scheduler had nothing held back. */
  record Saturated() implements StopReason {
  }

  /** The iteration limit was reached. */
  record IterationLimit(int iterations) implements StopReason {
  }

  /** The graph grew past the node limit. */
  record NodeLimit(int nodes) implements StopReason {
  }

  /** The graph grew past the class limit. */
  record ClassLimit(int classes) implements StopReason {
  }

  /** A {@link Runner.Hook} stopped the run, for the reason it gave (egg's {@code Other}). */
  record Other(String reason) implements StopReason {
  }
}
