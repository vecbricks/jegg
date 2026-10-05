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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SubstTest {

  @Test
  void anUnboundVariableIsNamedWhenAsked() {
    Subst s = Subst.EMPTY.bind("x", 3).bindPayload("c", true);
    assertEquals(3, s.idOf("x"));
    assertEquals(true, s.payload("c"));
    IllegalArgumentException id = assertThrows(IllegalArgumentException.class, () -> s.idOf("y"));
    assertTrue(id.getMessage().contains("y"), id.getMessage());
    IllegalArgumentException payload = assertThrows(IllegalArgumentException.class,
        () -> s.payload("d"));
    assertTrue(payload.getMessage().contains("d"), payload.getMessage());
    assertTrue(s.id("y").isEmpty());
    assertFalse(s.hasPayload("d"));
  }

  @Test
  void theBindingsAreReadOnlyViewsAndRenderSorted() {
    Subst s = Subst.EMPTY.bind("y", 2).bind("x", 1).bindPayload("c", true);
    assertEquals(Map.of("y", 2, "x", 1), s.ids());
    assertEquals(Map.of("c", true), s.payloads());
    assertThrows(UnsupportedOperationException.class, () -> s.ids().put("z", 3));
    assertThrows(UnsupportedOperationException.class, () -> s.payloads().put("d", 1));
    assertEquals("{x=1 y=2 c:true}", s.toString());
    assertEquals("{}", Subst.EMPTY.toString());
    assertEquals(Subst.EMPTY.bind("x", 1), Subst.EMPTY.bind("x", 1));
    assertEquals(Subst.EMPTY.bind("x", 1).hashCode(), Subst.EMPTY.bind("x", 1).hashCode());
  }

  @Test
  void rebindingKeepsThePositionAndEqualityIgnoresTheOrder() {
    Subst s = Subst.EMPTY.bind("x", 1).bind("y", 2);
    assertSame(s, s.bind("x", 1), "binding what is bound already is the same substitution");
    Subst rebound = s.bind("x", 5);
    assertEquals(List.of("x", "y"), new ArrayList<>(rebound.ids().keySet()));
    assertEquals(5, rebound.idOf("x"));
    assertEquals(1, s.idOf("x"), "the old one is unchanged");
    Subst reversed = Subst.EMPTY.bind("y", 2).bind("x", 1);
    assertEquals(s, s);
    assertEquals(s, reversed);
    assertEquals(s.hashCode(), reversed.hashCode());
    assertNotEquals(s, rebound);
    assertNotEquals(s, s.bind("z", 3));
    assertNotEquals(s, s.bindPayload("c", 1));
    assertNotEquals(s, "{x=1 y=2}");
    Subst payloads = s.bindPayload("c", 1).bindPayload("d", null);
    assertEquals(payloads, s.bindPayload("d", null).bindPayload("c", 1));
    assertEquals(payloads.hashCode(), s.bindPayload("d", null).bindPayload("c", 1).hashCode());
    assertEquals(2, payloads.bindPayload("c", 2).payload("c"), "a payload rebound in place");
    assertEquals(List.of("c", "d"),
        new ArrayList<>(payloads.bindPayload("c", 2).payloads().keySet()));
    assertSame(payloads, payloads.bindPayload("d", null), "bound already, to the same value");
    // Swapped ids hash apart: the buckets a commutative rule's matches would otherwise share.
    assertNotEquals(Subst.EMPTY.bind("a", 1).bind("b", 2).hashCode(),
        Subst.EMPTY.bind("a", 2).bind("b", 1).hashCode());
    assertNotEquals(payloads, s.bindPayload("c", 2).bindPayload("d", null));
    assertNotEquals(payloads, s.bindPayload("c", 1).bindPayload("e", null));
    assertTrue(payloads.hasPayload("d"));
    assertEquals(null, payloads.payload("d"));
  }
}
