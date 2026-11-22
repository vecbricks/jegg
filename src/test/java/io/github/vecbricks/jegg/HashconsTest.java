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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The e-graph's own map (#138): its answers against {@code HashMap}'s, its growth, its gaps. */
class HashconsTest {

  /** A key whose hash is chosen, so that runs, collisions and the wrap can be arranged. */
  record Keyed(int id, int hash) {
    @Override
    public int hashCode() {
      return hash;
    }
  }

  /** The table's own spread of a hash, repeated here to aim a key at a slot. */
  private static int slot(int hash, int capacity) {
    int h = hash * 0x9E3779B9;
    h ^= h >>> 16;
    return h & (capacity - 1);
  }

  /** A hash whose slot in a table of {@code capacity} is {@code slot}. */
  private static int hashFor(int slot, int capacity) {
    for (int h = 0; ; h++) {
      if (slot(h, capacity) == slot) {
        return h;
      }
    }
  }

  @Test
  void putGetRemoveAnswerAsAMapDoes() {
    Hashcons<IntList> m = new Hashcons<>();
    assertTrue(m.isEmpty());
    assertEquals(Hashcons.MISSING, m.get(IntList.of(1)));
    assertEquals(Hashcons.MISSING, m.put(IntList.of(1), 10));
    assertEquals(Hashcons.MISSING, m.put(IntList.of(2, 3), 20));
    assertEquals(10, m.put(IntList.of(1), 11), "a put returns the id it replaced");
    assertEquals(11, m.get(IntList.of(1)));
    assertEquals(20, m.get(IntList.of(2, 3)));
    assertTrue(m.containsKey(IntList.of(2, 3)));
    assertFalse(m.containsKey(IntList.of(3, 2)));
    assertEquals(2, m.size());
    assertEquals(11, m.remove(IntList.of(1)));
    assertEquals(Hashcons.MISSING, m.remove(IntList.of(1)));
    assertEquals(Hashcons.MISSING, m.get(IntList.of(1)));
    assertEquals(1, m.size());
    assertEquals("Hashcons[1 of 16 slots]", m.toString());
    assertTrue(m.isConsistent());
  }

  @Test
  void growthKeepsEveryKeyAndTheLoadUnderThreeQuarters() {
    Hashcons<IntList> m = new Hashcons<>();
    int n = 10_000;
    for (int i = 0; i < n; i++) {
      assertEquals(Hashcons.MISSING, m.put(IntList.of(i, i * 7), i));
    }
    assertEquals(n, m.size());
    for (int i = 0; i < n; i++) {
      assertEquals(i, m.get(IntList.of(i, i * 7)));
    }
    assertTrue(m.size() * 4 <= m.capacity() * 3, "load " + m.size() + " of " + m.capacity());
    assertEquals(Integer.bitCount(m.capacity()), 1, "a power of two");
    assertTrue(m.isConsistent());
    assertTrue(m.meanDisplacement() < 1.5, "mean displacement " + m.meanDisplacement());
  }

  @Test
  void removeThenPutCyclesLeaveNoGarbageSoTheTableStaysSmall() {
    Hashcons<IntList> m = new Hashcons<>();
    for (int round = 0; round < 1000; round++) {
      for (int i = 0; i < 100; i++) {
        m.put(IntList.of(round, i), i);
      }
      for (int i = 0; i < 100; i++) {
        assertEquals(i, m.remove(IntList.of(round, i)));
      }
    }
    assertTrue(m.isEmpty());
    assertEquals(256, m.capacity(), "100 keys need 256 slots at three quarters; nothing more");
    assertTrue(m.isConsistent());
  }

  @Test
  void aSmallKeySpaceUnderRandomPutsRemovesAndGetsAgreesWithHashMap() {
    Random random = new Random(42);
    Hashcons<IntList> m = new Hashcons<>();
    Map<IntList, Integer> reference = new HashMap<>();
    for (int step = 0; step < 200_000; step++) {
      IntList key = IntList.of(random.nextInt(300), random.nextInt(3));
      int value = random.nextInt(1_000_000);
      Integer expected;
      int actual;
      switch (random.nextInt(3)) {
        case 0 -> {
          expected = reference.put(key, value);
          actual = m.put(key, value);
        }
        case 1 -> {
          expected = reference.remove(key);
          actual = m.remove(key);
        }
        default -> {
          expected = reference.get(key);
          actual = m.get(key);
        }
      }
      assertEquals(expected == null ? Hashcons.MISSING : expected, actual, "step " + step);
      assertEquals(reference.size(), m.size(), "step " + step);
      if (step % 50_000 == 0) {
        assertTrue(m.isConsistent());
      }
    }
    assertTrue(m.isConsistent());
    // removeIf against the same predicate on the reference.
    boolean removed = reference.keySet().removeIf(k -> k.get(0) % 2 == 0);
    assertEquals(removed, m.removeIf(k -> k.get(0) % 2 == 0));
    assertEquals(reference.size(), m.size());
    reference.forEach((k, v) -> assertEquals(v, m.get(k)));
    assertTrue(m.isConsistent());
    assertFalse(m.removeIf(k -> false));
  }

  @Test
  void keysThatCollideAreToldApartAndARunAcrossTheWrapCloses() {
    // Twelve keys with one hash, aimed at the last slots of a 32-slot table (12 keys stay under
    // three quarters of 16, so the table grows once, to 32, at the thirteenth; use 11): the run
    // wraps past the end. Removing from its middle shifts the keys after it, across the wrap.
    int hash = hashFor(13, 16);
    Hashcons<Keyed> m = new Hashcons<>();
    for (int i = 0; i < 11; i++) {
      assertEquals(Hashcons.MISSING, m.put(new Keyed(i, hash), i));
    }
    assertEquals(16, m.capacity());
    for (int i = 0; i < 11; i++) {
      assertEquals(i, m.get(new Keyed(i, hash)));
    }
    assertEquals(Hashcons.MISSING, m.get(new Keyed(99, hash)), "same hash, another key");
    assertEquals(1, m.remove(new Keyed(1, hash)));
    assertEquals(5, m.remove(new Keyed(5, hash)));
    assertEquals(Hashcons.MISSING, m.remove(new Keyed(5, hash)));
    for (int i : new int[] {0, 2, 3, 4, 6, 7, 8, 9, 10}) {
      assertEquals(i, m.get(new Keyed(i, hash)), "key " + i + " after the shifts");
    }
    assertTrue(m.isConsistent());
    assertEquals(9, m.size());
    // Two runs that meet: keys of another hash aimed just before the first run's slot stay
    // where they are when the first run's keys are removed.
    int before = hashFor(12, 16);
    assertEquals(Hashcons.MISSING, m.put(new Keyed(100, before), 100));
    assertEquals(Hashcons.MISSING, m.put(new Keyed(101, before), 101));
    assertEquals(0, m.remove(new Keyed(0, hash)));
    assertEquals(100, m.get(new Keyed(100, before)));
    assertEquals(101, m.get(new Keyed(101, before)));
    assertTrue(m.isConsistent());
    // removeIf over a run across the wrap: every even id goes, the odd ones stay reachable.
    assertTrue(m.removeIf(k -> k.id() % 2 == 0));
    for (int i : new int[] {3, 7, 9, 101}) {
      assertEquals(i, m.get(new Keyed(i, i > 100 ? before : hash)));
    }
    for (int i : new int[] {2, 4, 6, 8, 10, 100}) {
      assertEquals(Hashcons.MISSING, m.get(new Keyed(i, i >= 100 ? before : hash)));
    }
    assertTrue(m.isConsistent());
  }

  @Test
  void forEachVisitsEveryEntryOnce() {
    Hashcons<IntList> m = new Hashcons<>();
    for (int i = 0; i < 100; i++) {
      m.put(IntList.of(i), i * 2);
    }
    m.remove(IntList.of(50));
    List<Integer> seen = new ArrayList<>();
    m.forEach((k, v) -> {
      assertEquals(k.get(0) * 2, v);
      seen.add(k.get(0));
    });
    assertEquals(99, seen.size());
    assertEquals(99, seen.stream().distinct().count());
    assertFalse(seen.contains(50));
  }

  @Test
  void realNodesSpreadWellEnoughThatALookupProbesLittle() {
    // The nodes of math's saturations of forty random terms, as the e-graph would key them: the
    // mean distance of a key from its own slot says how many extra probes a lookup makes.
    Hashcons<MathTest.Math> m = new Hashcons<>();
    for (String term : new TermGenerator("math", 2).terms(40)) {
      EGraph<MathTest.Math, Double> g = new EGraph<>(MathTest.CONSTANT_FOLD);
      g.addTree(Term.parse(term), MathTest.BRIDGE);
      new Runner<>(g, MathTest.rules(), RunLimits.DEFAULT.withIterations(6).withNodes(3000),
          new BackoffScheduler<>()).run();
      for (EClass<MathTest.Math, Double> c : g.classes()) {
        for (MathTest.Math node : c.nodes()) {
          m.put(node, c.id());
        }
      }
    }
    assertTrue(m.size() > 1000, "enough nodes to mean something: " + m.size());
    // Under 0.5 at this load with a hash that spread the operators; the records' hashCode is
    // over the children alone, so the operators over one pair of children share a hash (#145),
    // and the table reads about 1.06. The bound guards the mix, not the keys.
    double displacement = m.meanDisplacement();
    assertTrue(displacement < 1.5, m.size() + " nodes in " + m.capacity()
        + " slots, mean displacement " + displacement);
    assertTrue(m.isConsistent());
  }
}
