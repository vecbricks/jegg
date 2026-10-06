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
import org.jspecify.annotations.Nullable;

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
  private final @Nullable Object[] payloads;

  private Subst(String[] names, int[] ids, String[] payloadNames, @Nullable Object[] payloads) {
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
   *
   * @param variable the name of a subterm variable of the pattern
   * @param id the e-class id the variable stands for; the matcher binds canonical ids
   * @return the new substitution, or this one if the variable is bound to {@code id} already
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

  /**
   * This substitution with the payload variable {@code variable} bound to {@code value}; a
   * variable bound already is rebound in place, keeping its position.
   *
   * @param variable the name of a payload variable of the pattern
   * @param value the payload value, compared by {@code equals}; may be null
   * @return the new substitution, or this one if the variable is bound to an equal value already
   */
  public Subst bindPayload(String variable, @Nullable Object value) {
    int i = indexOf(payloadNames, variable);
    if (i >= 0) {
      if (Objects.equals(payloads[i], value)) {
        return this;
      }
      @Nullable Object[] next = payloads.clone();
      next[i] = value;
      return new Subst(names, ids, payloadNames, next);
    }
    String[] nextNames = Arrays.copyOf(payloadNames, payloadNames.length + 1);
    @Nullable Object[] nextValues = Arrays.copyOf(payloads, payloads.length + 1);
    nextNames[payloadNames.length] = variable;
    nextValues[payloads.length] = value;
    return new Subst(names, ids, nextNames, nextValues);
  }

  /**
   * The class a subterm variable is bound to, if it is.
   *
   * @param variable the name of a subterm variable
   * @return the id as it was bound, empty if the variable is unbound
   */
  public OptionalInt id(String variable) {
    int i = indexOf(names, variable);
    return i < 0 ? OptionalInt.empty() : OptionalInt.of(ids[i]);
  }

  /**
   * The class a subterm variable is bound to; it must be.
   *
   * @param variable the name of a subterm variable, which must be bound
   * @return the id as it was bound
   * @throws IllegalArgumentException if the variable is unbound
   */
  public int idOf(String variable) {
    int i = indexOf(names, variable);
    if (i < 0) {
      throw new IllegalArgumentException("unbound variable " + variable + " in " + this);
    }
    return ids[i];
  }

  /**
   * Whether the payload variable is bound.
   *
   * @param variable the name of a payload variable
   * @return true if it is bound, even to null
   */
  public boolean hasPayload(String variable) {
    return indexOf(payloadNames, variable) >= 0;
  }

  /**
   * The value a payload variable is bound to; it must be.
   *
   * @param variable the name of a payload variable, which must be bound
   * @return the bound value, which may be null
   * @throws IllegalArgumentException if the variable is unbound
   */
  public @Nullable Object payload(String variable) {
    int i = indexOf(payloadNames, variable);
    if (i < 0) {
      throw new IllegalArgumentException("unbound payload variable " + variable + " in " + this);
    }
    return payloads[i];
  }

  /**
   * The subterm bindings, in binding order, read-only; built on request.
   *
   * @return a fresh unmodifiable map from variable name to e-class id
   */
  public Map<String, Integer> ids() {
    LinkedHashMap<String, Integer> out = LinkedHashMap.newLinkedHashMap(names.length);
    for (int i = 0; i < names.length; i++) {
      out.put(names[i], ids[i]);
    }
    return Collections.unmodifiableMap(out);
  }

  /**
   * The payload bindings, in binding order, read-only; built on request.
   *
   * @return a fresh unmodifiable map from variable name to payload value
   */
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
    // A sum over the bindings, so that equal substitutions in any order hash alike; each term
    // mixed first, so that two variables with their ids swapped (what a commutative rule yields
    // in every class) do not cancel out to one bucket.
    int h = 0;
    for (int i = 0; i < names.length; i++) {
      h += mix(31 * names[i].hashCode() + ids[i]);
    }
    for (int i = 0; i < payloadNames.length; i++) {
      h += mix(31 * payloadNames[i].hashCode() + Objects.hashCode(payloads[i]));
    }
    return h;
  }

  /** MurmurHash3's finaliser: every input bit affects every output bit. */
  private static int mix(int h) {
    h ^= h >>> 16;
    h *= 0x85ebca6b;
    h ^= h >>> 13;
    h *= 0xc2b2ae35;
    h ^= h >>> 16;
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
