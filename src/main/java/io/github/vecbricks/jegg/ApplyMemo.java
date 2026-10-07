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
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * A memo of the class {@code add} returned for the node a pure head ({@link PureHead}) builds
 * over some child ids, for the graph's {@code instantiate}: keyed by the head, by identity, and
 * the child ids, which are roots when they are asked. It needs no invalidation. A root that
 * stops being one never becomes one again, so an entry is found only by ids that have been roots
 * since it was made, that is, for a node whose canonical form has not changed since, and the
 * class it names can only have been merged, which the caller's {@code find} resolves.
 *
 * <p>A direct-mapped table: an entry overwrites the one it collides with, so the memo is a cache
 * and its size is bounded, and a collision is a miss. It grows with the number of entries made,
 * to a limit. A node of more than {@link #MAX_ARITY} children is not memoized.
 */
final class ApplyMemo {

  /** The most children an entry holds. */
  static final int MAX_ARITY = 4;

  private static final AtomicInteger SERIALS = new AtomicInteger();

  private static final int INITIAL = 1 << 10;
  private static final int LIMIT = 1 << 20;

  private @Nullable PureHead<?>[] heads = new PureHead<?>[INITIAL];
  private int[] arities = new int[INITIAL];
  private int[] kids = new int[INITIAL * MAX_ARITY];
  private int[] classes = new int[INITIAL];
  private int mask = INITIAL - 1;
  private int filled;

  /** The next head serial: heads are numbered in the order they are made. */
  static int nextSerial() {
    return SERIALS.getAndIncrement();
  }

  private static int hash(PureHead<?> head, int[] ids) {
    int h = head.serial() * 0x7FEB352D;
    for (int id : ids) {
      h = h * 0x9E3779B1 + id;
    }
    h ^= h >>> 16;
    h *= 0x85ebca6b;
    h ^= h >>> 13;
    return h;
  }

  /** The class remembered for {@code head} over {@code ids}, or -1 if none is. */
  int get(PureHead<?> head, int[] ids) {
    if (ids.length > MAX_ARITY) {
      return -1;
    }
    int slot = hash(head, ids) & mask;
    if (heads[slot] != head || arities[slot] != ids.length) {
      return -1;
    }
    int base = slot * MAX_ARITY;
    for (int i = 0; i < ids.length; i++) {
      if (kids[base + i] != ids[i]) {
        return -1;
      }
    }
    return classes[slot];
  }

  /** Remembers that {@code head} over {@code ids} is in class {@code id}. */
  void put(PureHead<?> head, int[] ids, int id) {
    if (ids.length > MAX_ARITY) {
      return;
    }
    if (filled >= mask && mask < LIMIT - 1) {
      grow();
    }
    store(head, ids, id);
  }

  private void store(PureHead<?> head, int[] ids, int id) {
    int slot = hash(head, ids) & mask;
    if (heads[slot] == null) {
      filled++;
    }
    heads[slot] = head;
    arities[slot] = ids.length;
    int base = slot * MAX_ARITY;
    for (int i = 0; i < ids.length; i++) {
      kids[base + i] = ids[i];
    }
    classes[slot] = id;
  }

  private void grow() {
    @Nullable PureHead<?>[] oldHeads = heads;
    int[] oldArities = arities;
    int[] oldKids = kids;
    int[] oldClasses = classes;
    int capacity = (mask + 1) * 2;
    heads = new PureHead<?>[capacity];
    arities = new int[capacity];
    kids = new int[capacity * MAX_ARITY];
    classes = new int[capacity];
    mask = capacity - 1;
    filled = 0;
    for (int slot = 0; slot < oldHeads.length; slot++) {
      PureHead<?> head = oldHeads[slot];
      if (head != null) {
        int n = oldArities[slot];
        store(head, Arrays.copyOfRange(oldKids, slot * MAX_ARITY, slot * MAX_ARITY + n),
            oldClasses[slot]);
      }
    }
  }

  /** What {@link #forEach} shows of an entry. */
  interface Entries {
    void accept(PureHead<?> head, int[] ids, int id);
  }

  /** Calls {@code visitor} for every entry, in slot order, with a copy of its ids. */
  void forEach(Entries visitor) {
    for (int slot = 0; slot < heads.length; slot++) {
      PureHead<?> head = heads[slot];
      if (head != null) {
        int base = slot * MAX_ARITY;
        visitor.accept(head, Arrays.copyOfRange(kids, base, base + arities[slot]), classes[slot]);
      }
    }
  }
}
