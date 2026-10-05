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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * A substitution: what a pattern's variables stand for in one match. Subterm variables are
 * bound to e-class ids; payload variables, which this port adds to egg's patterns so a rule can
 * match an operator's mode or constant and carry it to its right-hand side, are bound to the
 * payload value. A substitution is immutable; binding returns a new one, which is what lets
 * the matcher backtrack by discarding it.
 *
 * <p>The bindings are parallel arrays, a few entries long (a pattern binds a handful of
 * variables), copied on each bind and scanned on each lookup: no map, no boxed id. Two
 * substitutions are equal when they bind the same variables to the same values, in any order.
 * A value candidate (PLAN.md 3.1): immutable, compared by content, never by identity.
 */
public final class Subst {

  /** The substitution that binds nothing. */
  public static final Subst EMPTY = new Subst(new String[0], new int[0], new String[0],
      new Object[0]);

  private final String[] names;
  private final int[] ids;
  private final String[] payloadNames;
  private final Object[] payloads;

  private Subst(String[] names, int[] ids, String[] payloadNames, Object[] payloads) {
    this.names = names;
    this.ids = ids;
    this.payloadNames = payloadNames;
    this.payloads = payloads;
  }

  private static int indexOf(String[] names, String variable) {
    for (int i = 0; i < names.length; i++) {
      if (names[i].equals(variable)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * This substitution with {@code variable} bound to {@code id}; a variable bound already is
   * rebound in place, keeping its position.
   */
  public Subst bind(String variable, int id) {
    int i = indexOf(names, variable);
    if (i >= 0) {
      if (ids[i] == id) {
        return this;
      }
      int[] next = ids.clone();
      next[i] = id;
      return new Subst(names, next, payloadNames, payloads);
    }
    String[] nextNames = Arrays.copyOf(names, names.length + 1);
    int[] nextIds = Arrays.copyOf(ids, ids.length + 1);
    nextNames[names.length] = variable;
    nextIds[ids.length] = id;
    return new Subst(nextNames, nextIds, payloadNames, payloads);
  }

  /** This substitution with the payload variable {@code variable} bound to {@code value}. */
  public Subst bindPayload(String variable, Object value) {
    int i = indexOf(payloadNames, variable);
    if (i >= 0) {
      Object[] next = payloads.clone();
      next[i] = value;
      return new Subst(names, ids, payloadNames, next);
    }
    String[] nextNames = Arrays.copyOf(payloadNames, payloadNames.length + 1);
    Object[] nextValues = Arrays.copyOf(payloads, payloads.length + 1);
    nextNames[payloadNames.length] = variable;
    nextValues[payloads.length] = value;
    return new Subst(names, ids, nextNames, nextValues);
  }

  /** The class a subterm variable is bound to, if it is. */
  public OptionalInt id(String variable) {
    int i = indexOf(names, variable);
    return i < 0 ? OptionalInt.empty() : OptionalInt.of(ids[i]);
  }

  /** The class a subterm variable is bound to; it must be. */
  public int idOf(String variable) {
    int i = indexOf(names, variable);
    if (i < 0) {
      throw new IllegalArgumentException("unbound variable " + variable + " in " + this);
    }
    return ids[i];
  }

  /** Whether the payload variable is bound. */
  public boolean hasPayload(String variable) {
    return indexOf(payloadNames, variable) >= 0;
  }

  /** The value a payload variable is bound to; it must be. */
  public Object payload(String variable) {
    int i = indexOf(payloadNames, variable);
    if (i < 0) {
      throw new IllegalArgumentException("unbound payload variable " + variable + " in " + this);
    }
    return payloads[i];
  }

  /** The subterm bindings, in binding order, read-only; built on request. */
  public Map<String, Integer> ids() {
    LinkedHashMap<String, Integer> out = LinkedHashMap.newLinkedHashMap(names.length);
    for (int i = 0; i < names.length; i++) {
      out.put(names[i], ids[i]);
    }
    return Collections.unmodifiableMap(out);
  }

  /** The payload bindings, in binding order, read-only; built on request. */
  public Map<String, Object> payloads() {
    LinkedHashMap<String, Object> out = LinkedHashMap.newLinkedHashMap(payloadNames.length);
    for (int i = 0; i < payloadNames.length; i++) {
      out.put(payloadNames[i], payloads[i]);
    }
    return Collections.unmodifiableMap(out);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Subst s) || names.length != s.names.length
        || payloadNames.length != s.payloadNames.length) {
      return false;
    }
    for (int i = 0; i < names.length; i++) {
      int j = indexOf(s.names, names[i]);
      if (j < 0 || s.ids[j] != ids[i]) {
        return false;
      }
    }
    for (int i = 0; i < payloadNames.length; i++) {
      int j = indexOf(s.payloadNames, payloadNames[i]);
      if (j < 0 || !Objects.equals(s.payloads[j], payloads[i])) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int hashCode() {
    // A sum over the bindings, so that equal substitutions in any order hash alike.
    int h = 0;
    for (int i = 0; i < names.length; i++) {
      h += names[i].hashCode() ^ ids[i];
    }
    for (int i = 0; i < payloadNames.length; i++) {
      h += payloadNames[i].hashCode() ^ Objects.hashCode(payloads[i]);
    }
    return h;
  }

  @Override
  public String toString() {
    StringBuilder b = new StringBuilder("{");
    ids().entrySet().stream().sorted(Map.Entry.comparingByKey())
        .forEach(e -> b.append(e.getKey()).append('=').append(e.getValue()).append(' '));
    payloads().entrySet().stream().sorted(Map.Entry.comparingByKey())
        .forEach(e -> b.append(e.getKey()).append(':').append(e.getValue()).append(' '));
    return b.toString().trim() + "}";
  }
}
