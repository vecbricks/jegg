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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
