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

/**
 * A growable array of {@code int}s: a worklist of class ids held unboxed, where a
 * {@code List<Integer>} would allocate an object per id past 127. Mutable, so not a value
 * candidate; {@link IntList} is the immutable one.
 */
final class IntArray {

  private int[] elements;
  private int size;

  IntArray() {
    this(16);
  }

  IntArray(int capacity) {
    elements = new int[Math.max(capacity, 1)];
  }

  void add(int value) {
    if (size == elements.length) {
      elements = Arrays.copyOf(elements, size * 2);
    }
    elements[size++] = value;
  }

  int get(int index) {
    if (index < 0 || index >= size) {
      throw new IndexOutOfBoundsException(index + " of " + size);
    }
    return elements[index];
  }

  int size() {
    return size;
  }

  boolean isEmpty() {
    return size == 0;
  }

  void clear() {
    size = 0;
  }

  /** The elements under {@code f}, sorted ascending with duplicates removed, as a new array. */
  int[] sortedDistinct(IntUnaryOperator f) {
    int[] out = new int[size];
    for (int i = 0; i < size; i++) {
      out[i] = f.applyAsInt(elements[i]);
    }
    Arrays.sort(out);
    int n = 0;
    for (int i = 0; i < out.length; i++) {
      if (i == 0 || out[i] != out[i - 1]) {
        out[n++] = out[i];
      }
    }
    return n == out.length ? out : Arrays.copyOf(out, n);
  }

  /** A copy of the elements. */
  int[] toArray() {
    return Arrays.copyOf(elements, size);
  }

  @Override
  public String toString() {
    return Arrays.toString(toArray());
  }
}
