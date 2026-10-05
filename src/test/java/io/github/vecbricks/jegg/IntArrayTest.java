/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IntArrayTest {

  @Test
  void growsPastItsCapacityAndReadsBack() {
    IntArray a = new IntArray(2);
    assertTrue(a.isEmpty());
    for (int i = 0; i < 100; i++) {
      a.add(i * 3);
    }
    assertEquals(100, a.size());
    assertEquals(297, a.get(99));
    assertFalse(a.isEmpty());
    assertThrows(IndexOutOfBoundsException.class, () -> a.get(100));
    assertThrows(IndexOutOfBoundsException.class, () -> a.get(-1));
    a.clear();
    assertTrue(a.isEmpty());
    assertEquals("[]", a.toString());
    a.add(7);
    assertEquals("[7]", a.toString());
    assertArrayEquals(new int[] {7}, a.toArray());
  }

  @Test
  void sortedDistinctMapsSortsAndDeduplicates() {
    IntArray a = new IntArray();
    for (int v : new int[] {9, 4, 9, 12, 4, 1}) {
      a.add(v);
    }
    assertArrayEquals(new int[] {1, 4, 9, 12}, a.sortedDistinct(v -> v));
    // Under a map, values that become equal are one.
    assertArrayEquals(new int[] {0, 1}, a.sortedDistinct(v -> v % 3));
    assertArrayEquals(new int[0], new IntArray().sortedDistinct(v -> v));
    // Already distinct: the array comes back as is, sorted.
    IntArray b = new IntArray();
    b.add(3);
    b.add(1);
    assertArrayEquals(new int[] {1, 3}, b.sortedDistinct(v -> v));
  }
}
