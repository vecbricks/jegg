/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */


package io.github.vecbricks.jegg;

/**
 * A pattern head whose {@link Pattern.Head#build} is a function of the children alone: the node
 * it builds over the same children is always the same node, whatever the substitution. The
 * graph may then remember the class such a node was found in ({@code ApplyMemo}) and skip
 * building it again. A head that reads a payload variable, or a client's own head, is not one.
 *
 * @param <L> the language
 */
interface PureHead<L extends Language<L>> extends Pattern.Head<L> {

  /**
   * A number the head was given when it was made, from {@link ApplyMemo#nextSerial}: what the
   * memo hashes, so that its layout does not depend on object identity and a run is the same
   * run every time.
   *
   * @return the head's serial
   */
  int serial();
}
