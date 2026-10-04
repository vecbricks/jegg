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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class IntListTest {

  @Test
  void equalElementsAreEqualListsWhateverArrayTheyCameFrom() {
    // The array trap (PLAN.md 7, risk 1): two lists built from separate arrays are equal, and so
    // are the records that hold them, which is what lets the hashcons find a node again.
    int[] a = {1, 2, 3};
    int[] b = {1, 2, 3};
    assertEquals(IntList.of(a), IntList.of(b));
    assertEquals(IntList.of(a).hashCode(), IntList.of(b).hashCode());
    assertEquals(new Toy.Add(IntList.of(a)), new Toy.Add(IntList.of(b)));
    assertNotEquals(IntList.of(1, 2, 3), IntList.of(3, 2, 1));
  }

  @Test
  void aListCopiesItsArrayAndMapsOnlyWhenSomethingChanges() {
    int[] a = {4, 5};
    IntList list = IntList.of(a);
    a[0] = 9;
    assertEquals(4, list.get(0));
    assertSame(list, list.map(x -> x));
    assertEquals(IntList.of(5, 6), list.map(x -> x + 1));
    assertSame(IntList.EMPTY, IntList.of());
  }

  @Test
  void theEmptyListAndTheArrayCopy() {
    assertTrue(IntList.EMPTY.isEmpty());
    assertFalse(IntList.of(1).isEmpty());
    IntList l = IntList.of(1, 2);
    int[] copy = l.toArray();
    copy[0] = 9;
    assertEquals(1, l.get(0), "toArray gives a copy");
    assertEquals(l, l);
    assertNotEquals(l, "1, 2");
  }
}
