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

/**
 * The union-find over e-class ids: an {@code int[]} of parents with path compression, egg's
 * {@code UnionFind} ported as it is.
 *
 * <p>Ids are dense and assigned in order by {@link #makeSet}, so the array is the whole structure
 * and nothing is boxed. {@link #union} keeps the smaller id as the root, which is one of the
 * places this port fixes an order egg leaves to chance: which of two merged classes survives is
 * then a function of the input, not of which was found first.
 */
public final class UnionFind {

  private int[] parent = new int[16];
  private int size = 0;

  /** A new set of one element; its id, which is the next unused one. */
  public int makeSet() {
    if (size == parent.length) {
      parent = Arrays.copyOf(parent, size * 2);
    }
    parent[size] = size;
    return size++;
  }

  /** How many ids have been made, merged or not. */
  public int size() {
    return size;
  }

  /** The root of {@code id}'s set, with the path to it compressed. */
  public int find(int id) {
    int root = id;
    while (parent[root] != root) {
      root = parent[root];
    }
    while (parent[id] != root) {
      int next = parent[id];
      parent[id] = root;
      id = next;
    }
    return root;
  }

  /**
   * Joins the sets of {@code a} and {@code b} and returns the root of the joined set, which is
   * the smaller of the two roots. The two may already share a set.
   */
  public int union(int a, int b) {
    int ra = find(a);
    int rb = find(b);
    if (ra == rb) {
      return ra;
    }
    int root = Math.min(ra, rb);
    int other = Math.max(ra, rb);
    parent[other] = root;
    return root;
  }
}
