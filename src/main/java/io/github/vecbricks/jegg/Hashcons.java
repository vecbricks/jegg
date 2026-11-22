/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;

/**
 * A map from e-nodes to class ids, the e-graph's hashcons and its pruned memory: open
 * addressing with linear probing over three parallel arrays (the keys, their hashes, the ids),
 * so that an entry costs no object and an id is never boxed. A key is compared by its cached
 * hash first, then by identity, then by {@code equals}, as {@code HashMap} compares, so a hit
 * costs one {@code equals}. A removal closes the gap by shifting the entries after it back
 * (no tombstones), since the rebuild removes and puts a key per parent visited. The table
 * holds at most three quarters of its slots and doubles when it would hold more; it never
 * shrinks. Ids are non-negative, so {@link #MISSING} stands for no entry. Nothing iterates the
 * table but the invariant check and the pruned memory's sweep, so its order is nobody's.
 *
 * @param <L> the key type, an e-node
 */
final class Hashcons<L> {

  /** What {@link #get}, {@link #put} and {@link #remove} answer for a key not in the table. */
  static final int MISSING = -1;

  private static final int INITIAL_CAPACITY = 16;

  private Object[] keys;
  private int[] hashes;
  private int[] values;
  private int size;
  private int mask;

  Hashcons() {
    keys = new Object[INITIAL_CAPACITY];
    hashes = new int[INITIAL_CAPACITY];
    values = new int[INITIAL_CAPACITY];
    mask = INITIAL_CAPACITY - 1;
  }

  /** The key's hash, spread so that the low bits a mask keeps depend on all of them. */
  private static int mix(int h) {
    h *= 0x9E3779B9;
    return h ^ (h >>> 16);
  }

  /**
   * The id of {@code key}, or {@link #MISSING}.
   *
   * @param key the e-node; not null
   * @return its id, or MISSING
   */
  int get(L key) {
    int h = mix(key.hashCode());
    int i = h & mask;
    while (true) {
      Object k = keys[i];
      if (k == null) {
        return MISSING;
      }
      if (hashes[i] == h && (k == key || k.equals(key))) {
        return values[i];
      }
      i = (i + 1) & mask;
    }
  }

  /**
   * Whether the table holds {@code key}.
   *
   * @param key the e-node; not null
   * @return true if it is in the table
   */
  boolean containsKey(L key) {
    return get(key) != MISSING;
  }

  /**
   * Maps {@code key} to {@code value}, replacing the previous id if there was one.
   *
   * @param key the e-node; not null
   * @param value the id; not negative
   * @return the previous id, or MISSING if the key was not in the table
   */
  int put(L key, int value) {
    if ((size + 1) * 4 > (mask + 1) * 3) {
      grow();
    }
    int h = mix(key.hashCode());
    int i = h & mask;
    while (true) {
      Object k = keys[i];
      if (k == null) {
        keys[i] = key;
        hashes[i] = h;
        values[i] = value;
        size++;
        return MISSING;
      }
      if (hashes[i] == h && (k == key || k.equals(key))) {
        int previous = values[i];
        values[i] = value;
        return previous;
      }
      i = (i + 1) & mask;
    }
  }

  /**
   * Removes {@code key}.
   *
   * @param key the e-node; not null
   * @return the id it had, or MISSING if it was not in the table
   */
  int remove(L key) {
    int h = mix(key.hashCode());
    int i = h & mask;
    while (true) {
      Object k = keys[i];
      if (k == null) {
        return MISSING;
      }
      if (hashes[i] == h && (k == key || k.equals(key))) {
        int previous = values[i];
        delete(i);
        return previous;
      }
      i = (i + 1) & mask;
    }
  }

  /**
   * Empties slot {@code i} and closes the gap: each entry after it in the same run is moved
   * back into the gap when its own slot is not between the gap and it, so every entry stays
   * reachable from its own slot.
   */
  private void delete(int i) {
    int j = i;
    while (true) {
      j = (j + 1) & mask;
      if (keys[j] == null) {
        break;
      }
      int own = hashes[j] & mask;
      boolean movable = i <= j ? own <= i || own > j : own <= i && own > j;
      if (movable) {
        keys[i] = keys[j];
        hashes[i] = hashes[j];
        values[i] = values[j];
        i = j;
      }
    }
    keys[i] = null;
    size--;
  }

  private void grow() {
    Object[] oldKeys = keys;
    int[] oldHashes = hashes;
    int[] oldValues = values;
    int capacity = (mask + 1) * 2;
    keys = new Object[capacity];
    hashes = new int[capacity];
    values = new int[capacity];
    mask = capacity - 1;
    for (int s = 0; s < oldKeys.length; s++) {
      if (oldKeys[s] != null) {
        int i = oldHashes[s] & mask;
        while (keys[i] != null) {
          i = (i + 1) & mask;
        }
        keys[i] = oldKeys[s];
        hashes[i] = oldHashes[s];
        values[i] = oldValues[s];
      }
    }
  }

  /**
   * The number of entries.
   *
   * @return how many keys the table holds
   */
  int size() {
    return size;
  }

  /**
   * Whether the table is empty.
   *
   * @return true if it holds no key
   */
  boolean isEmpty() {
    return size == 0;
  }

  /**
   * Calls {@code action} with every entry, in the table's own order, which is nobody's.
   *
   * @param action what to do with each key and its id
   */
  @SuppressWarnings("unchecked")
  void forEach(ObjIntConsumer<? super L> action) {
    for (int i = 0; i < keys.length; i++) {
      if (keys[i] != null) {
        action.accept((L) keys[i], values[i]);
      }
    }
  }

  /**
   * Removes every entry whose key {@code test} accepts. A removal moves later entries of the
   * run back, so a slot is looked at again after its entry went.
   *
   * @param test which keys to remove
   * @return whether any entry was removed
   */
  @SuppressWarnings("unchecked")
  boolean removeIf(Predicate<? super L> test) {
    int before = size;
    for (int i = 0; i < keys.length; i++) {
      while (keys[i] != null && test.test((L) keys[i])) {
        delete(i);
      }
    }
    return size != before;
  }

  /**
   * The mean distance of the entries from their own slots: how long a lookup probes on
   * average, less one. For tests of the hash's spread.
   */
  double meanDisplacement() {
    long total = 0;
    for (int i = 0; i < keys.length; i++) {
      if (keys[i] != null) {
        total += (i - (hashes[i] & mask)) & mask;
      }
    }
    return size == 0 ? 0 : (double) total / size;
  }

  /** The number of slots, for tests of the growth. */
  int capacity() {
    return keys.length;
  }

  @Override
  public String toString() {
    return "Hashcons[" + size + " of " + keys.length + " slots]";
  }

  /** For tests: whether the count is right and every entry is found from its own slot. */
  @SuppressWarnings("unchecked")
  boolean isConsistent() {
    int counted = 0;
    for (int i = 0; i < keys.length; i++) {
      if (keys[i] != null) {
        counted++;
        if (hashes[i] != mix(keys[i].hashCode())) {
          return false;
        }
      }
    }
    if (counted != size) {
      return false;
    }
    for (int i = 0; i < keys.length; i++) {
      if (keys[i] != null && get((L) keys[i]) != values[i]) {
        return false;
      }
    }
    return true;
  }
}
