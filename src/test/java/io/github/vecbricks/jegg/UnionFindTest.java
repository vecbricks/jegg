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

import org.junit.jupiter.api.Test;

class UnionFindTest {

  @Test
  void findIsIdempotentAndUnionKeepsTheSmallerRoot() {
    UnionFind uf = new UnionFind();
    for (int i = 0; i < 40; i++) {
      assertEquals(i, uf.makeSet());
    }
    // A chain 39 -> 38 -> ... -> 0 built by unions in descending order; find compresses it.
    for (int i = 39; i > 0; i--) {
      assertEquals(i - 1, uf.union(i, i - 1));
    }
    assertEquals(0, uf.find(39));
    assertEquals(uf.find(39), uf.find(uf.find(39)));
    for (int i = 0; i < 40; i++) {
      assertEquals(0, uf.find(i));
    }
    assertEquals(0, uf.union(5, 7));
    assertEquals(40, uf.size());
  }

  @Test
  void theRootIsAFunctionOfTheIdsNotOfTheOrderOfUnions() {
    UnionFind a = new UnionFind();
    UnionFind b = new UnionFind();
    for (int i = 0; i < 6; i++) {
      a.makeSet();
      b.makeSet();
    }
    a.union(5, 3);
    a.union(3, 4);
    b.union(4, 3);
    b.union(5, 4);
    assertEquals(3, a.find(5));
    assertEquals(3, b.find(5));
  }
}
