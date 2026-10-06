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

  /** A name equal to {@code name} that is not the interned one: what a client builds by hand. */
  private static String fresh(String name) {
    String copy = new String(name.toCharArray());
    assertTrue(copy != name.intern(), "a copy is a different object from the interned name");
    return copy;
  }

  @Test
  void namesAreFoundWhetherOrNotTheClientInternedThem() {
    // The chain finds a name by reference, so what a client passes in is interned on the way
    // in and, for a lookup, looked for again under its interned form.
    Subst s = Subst.EMPTY.bind(fresh("x"), 3).bindPayload(fresh("c"), 'k');
    assertEquals(3, s.idOf("x"));
    assertEquals(3, s.idOf(fresh("x")));
    assertTrue(s.id(fresh("x")).isPresent());
    assertEquals(3, s.idOrUnbound("x"));
    assertTrue(s.hasPayload(fresh("c")));
    assertEquals('k', s.payload(fresh("c")));
    assertTrue(s.id(fresh("y")).isEmpty(), "a name that is not bound is not found");
    assertFalse(s.hasPayload(fresh("d")));
    assertThrows(IllegalArgumentException.class, () -> s.idOf(fresh("y")));
    // Rebinding under another copy of the name replaces in place and keeps one binding.
    Subst rebound = s.bind(fresh("x"), 4);
    assertEquals(4, rebound.idOf("x"));
    assertEquals(1, rebound.ids().size());
    assertSame(s, s.bind(fresh("x"), 3), "bound to the same id already");
    assertSame(s, s.bindPayload(fresh("c"), 'k'), "bound to an equal value already");
    // Two chains built from different copies of the names are equal, and hash alike.
    Subst a = Subst.EMPTY.bind(fresh("p"), 1).bind(fresh("q"), 2);
    Subst b = Subst.EMPTY.bind("q", 2).bind("p", 1);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void aPatternAndAClauseHoldInternedNames() {
    Pattern.Var<Toy> var = new Pattern.Var<>(fresh("w"));
    assertSame(var.name(), var.name().intern());
    MultiPattern.Clause<Toy> clause = new MultiPattern.Clause<>(fresh("k"),
        Pattern.<Toy>var("m"));
    assertSame(clause.var(), clause.var().intern());
    assertThrows(NullPointerException.class, () -> Pattern.<Toy>var(null));
  }

  @Test
  void aSearchWithNamesBuiltByHandFindsTheSameMatches() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    g.add(new Toy.Add(IntList.of(a, b)));
    g.add(new Toy.Add(IntList.of(a, a)));
    g.rebuild();
    Pattern<Toy> plain = Pattern.of(new Toy.Add(IntList.EMPTY), Pattern.var("x"),
        Pattern.var("y"));
    Pattern<Toy> byHand = Pattern.of(new Toy.Add(IntList.EMPTY), Pattern.var(fresh("x")),
        Pattern.var(fresh("y")));
    assertEquals(Matcher.search(g, plain), Matcher.search(g, byHand));
    Pattern<Toy> twice = Pattern.of(new Toy.Add(IntList.EMPTY), Pattern.var(fresh("x")),
        Pattern.var(fresh("x")));
    assertEquals(1, Matcher.search(g, twice).size(), "the same name twice is one variable");
    // A substitution handed in from outside, built from copies of the names, constrains the same.
    List<Subst> given = Matcher.matchIn(g, byHand, g.find(g.add(new Toy.Add(IntList.of(a, b)))),
        Subst.EMPTY.bind(fresh("x"), a));
    assertEquals(1, given.size());
    assertEquals(b, given.get(0).idOf("y"));
  }
}
