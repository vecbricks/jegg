/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

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
 */
public final class Subst {

  /** The substitution that binds nothing. */
  public static final Subst EMPTY = new Subst(new LinkedHashMap<>(), new LinkedHashMap<>());

  private final LinkedHashMap<String, Integer> ids;
  private final LinkedHashMap<String, Object> payloads;

  private Subst(LinkedHashMap<String, Integer> ids, LinkedHashMap<String, Object> payloads) {
    this.ids = ids;
    this.payloads = payloads;
  }

  /** This substitution with {@code variable} bound to {@code id}. */
  public Subst bind(String variable, int id) {
    LinkedHashMap<String, Integer> next = new LinkedHashMap<>(ids);
    next.put(variable, id);
    return new Subst(next, payloads);
  }

  /** This substitution with the payload variable {@code variable} bound to {@code value}. */
  public Subst bindPayload(String variable, Object value) {
    LinkedHashMap<String, Object> next = new LinkedHashMap<>(payloads);
    next.put(variable, value);
    return new Subst(ids, next);
  }

  /** The class a subterm variable is bound to, if it is. */
  public OptionalInt id(String variable) {
    Integer id = ids.get(variable);
    return id == null ? OptionalInt.empty() : OptionalInt.of(id);
  }

  /** The class a subterm variable is bound to; it must be. */
  public int idOf(String variable) {
    Integer id = ids.get(variable);
    if (id == null) {
      throw new IllegalArgumentException("unbound variable " + variable + " in " + this);
    }
    return id;
  }

  /** Whether the payload variable is bound. */
  public boolean hasPayload(String variable) {
    return payloads.containsKey(variable);
  }

  /** The value a payload variable is bound to; it must be. */
  public Object payload(String variable) {
    if (!payloads.containsKey(variable)) {
      throw new IllegalArgumentException("unbound payload variable " + variable + " in " + this);
    }
    return payloads.get(variable);
  }

  /** The subterm bindings, in binding order, read-only. */
  public Map<String, Integer> ids() {
    return java.util.Collections.unmodifiableMap(ids);
  }

  /** The payload bindings, in binding order, read-only. */
  public Map<String, Object> payloads() {
    return java.util.Collections.unmodifiableMap(payloads);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Subst s && ids.equals(s.ids) && payloads.equals(s.payloads);
  }

  @Override
  public int hashCode() {
    return Objects.hash(ids, payloads);
  }

  @Override
  public String toString() {
    StringBuilder b = new StringBuilder("{");
    ids.entrySet().stream().sorted(Map.Entry.comparingByKey())
        .forEach(e -> b.append(e.getKey()).append('=').append(e.getValue()).append(' '));
    payloads.entrySet().stream().sorted(Map.Entry.comparingByKey())
        .forEach(e -> b.append(e.getKey()).append(':').append(e.getValue()).append(' '));
    return b.toString().trim() + "}";
  }
}
