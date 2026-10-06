/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

import java.util.Arrays;
import java.util.function.IntUnaryOperator;
import java.util.stream.IntStream;

/**
 * An immutable list of {@code int}s: the children of an e-node, held as e-class ids.
 *
 * <p>This is the one container every e-node uses for its children, and the reason it exists
 * rather than a bare {@code int[]} is equality: a record with an {@code int[]} component compares
 * that component by reference, so two e-nodes built from separate arrays would never be equal,
 * the hashcons would never find a node already in the graph, and the e-graph would silently be a
 * tree. {@link #equals} and {@link #hashCode} here are over the elements.
 *
 * <p>A value candidate (PLAN.md 3.1): immutable, compared by content; the {@code this == o}
 * shortcut in {@link #equals} is correct under substitutability.
 */
public final class IntList {

  /** The empty list, shared: a leaf's children. */
  public static final IntList EMPTY = new IntList(new int[0]);

  private final int[] elements;

  private IntList(int[] elements) {
    this.elements = elements;
  }

  /**
   * A list of these elements; the array is copied, so the caller may keep mutating it.
   *
   * @param elements the e-class ids, in order; may be empty
   * @return the list, {@link #EMPTY} if there are no elements
   */
  public static IntList of(int... elements) {
    return elements.length == 0 ? EMPTY : new IntList(elements.clone());
  }

  /**
   * The list over {@code elements} itself, not a copy: for a caller that made the array for
   * this list and gives it up, never to change it again.
   */
  static IntList wrap(int[] elements) {
    return elements.length == 0 ? EMPTY : new IntList(elements);
  }

  /**
   * The number of elements.
   *
   * @return the length of the list, 0 for {@link #EMPTY}
   */
  public int size() {
    return elements.length;
  }

  /**
   * Whether the list has no elements.
   *
   * @return true if {@link #size} is 0
   */
  public boolean isEmpty() {
    return elements.length == 0;
  }

  /**
   * The element at a position.
   *
   * @param index a position from 0 up to {@link #size} exclusive
   * @return the element there
   */
  public int get(int index) {
    return elements[index];
  }

  /**
   * A list with {@code f} applied to every element; this one if nothing changed.
   *
   * @param f the function applied to each element, in order, without side effects
   * @return the mapped list, which is this list when {@code f} returned every element unchanged
   */
  public IntList map(IntUnaryOperator f) {
    int[] mapped = null;
    for (int i = 0; i < elements.length; i++) {
      int v = f.applyAsInt(elements[i]);
      if (v != elements[i]) {
        if (mapped == null) {
          mapped = elements.clone();
        }
        mapped[i] = v;
      }
    }
    return mapped == null ? this : new IntList(mapped);
  }

  /**
   * The elements as a stream.
   *
   * @return a sequential stream of the elements in order
   */
  public IntStream stream() {
    return Arrays.stream(elements);
  }

  /**
   * A copy of the elements, for a caller that wants an array.
   *
   * @return a fresh array the caller may modify
   */
  public int[] toArray() {
    return elements.clone();
  }

  @Override
  public boolean equals(Object o) {
    return this == o || (o instanceof IntList other && Arrays.equals(elements, other.elements));
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(elements);
  }

  @Override
  public String toString() {
    return Arrays.toString(elements);
  }
}
