/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
 * <p>A substitution is a chain of bindings, newest first: each node holds one variable and what
 * it stands for, and shares the rest with the substitution it was made from, so a bind allocates
 * one node and copies nothing, and a lookup walks a few nodes (a pattern binds a handful of
 * variables); no map, no boxed id. Two substitutions are equal when they bind the same variables
 * to the same values, in any order. A value candidate (PLAN.md 3.1): immutable, compared by
 * content, never by identity.
 */
public final class Subst {

  /** The substitution that binds nothing. */
  public static final Subst EMPTY = new Subst();

  /** What {@link #idOrUnbound} answers for a variable that is not bound: no class has this id. */
  static final int UNBOUND = -1;

  // One binding and the rest of the chain: the variable's name, interned (null in EMPTY alone),
  // so that it is found by reference; the id it stands for, or for a payload variable the value;
  // and the substitution this one extends, which is EMPTY itself at the end of every chain.
  private final @Nullable String name;
  private final int id;
  private final @Nullable Object payload;
  private final boolean isPayload;
  private final Subst next;

  private Subst() {
    this.name = null;
    this.id = 0;
    this.payload = null;
    this.isPayload = false;
    this.next = this;
  }

  private Subst(String name, int id, @Nullable Object payload, boolean isPayload, Subst next) {
    this.name = name;
    this.id = id;
    this.payload = payload;
    this.isPayload = isPayload;
    this.next = next;
  }

  /**
   * The node binding {@code variable}, an interned name, as a subterm or a payload variable, or
   * null if none. Every name in a chain is interned (the public binds intern theirs, and the
   * matcher binds the names of patterns, which are interned when they are made), so a name is
   * found by reference, with no {@code equals} on the names that are not it.
   */
  private @Nullable Subst nodeOfInterned(String variable, boolean payload) {
    for (Subst n = this; n.name != null; n = n.next) {
      if (n.name == variable && n.isPayload == payload) {
        return n;
      }
    }
    return null;
  }

  /**
   * As {@link #nodeOfInterned} for a name that may not be interned, a client's: found by
   * reference first, which is the answer for a name that is, and otherwise looked for again under
   * its interned form.
   */
  private @Nullable Subst nodeOf(String variable, boolean payload) {
    Subst n = nodeOfInterned(variable, payload);
    if (n != null) {
      return n;
    }
    String interned = variable.intern();
    return interned == variable ? null : nodeOfInterned(interned, payload);
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
    return bindInterned(variable.intern(), id);
  }

  /** {@link #bind} for a name that is interned already: a pattern's, a clause's. */
  Subst bindInterned(String variable, int id) {
    Subst bound = nodeOfInterned(variable, false);
    if (bound == null) {
      return new Subst(variable, id, null, false, this);
    }
    if (bound.id == id) {
      return this;
    }
    return rebound(bound, new Subst(variable, id, null, false, bound.next));
  }

  /**
   * This substitution with {@code variable}, which must not be bound and must be interned, bound
   * to {@code id}: a bind without the lookup, for the matcher, which has just looked.
   */
  Subst bindNew(String variable, int id) {
    return new Subst(variable, id, null, false, this);
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
    return bindPayloadInterned(variable.intern(), value);
  }

  /** {@link #bindPayload} for a name that is interned already: a head's. */
  Subst bindPayloadInterned(String variable, @Nullable Object value) {
    Subst bound = nodeOfInterned(variable, true);
    if (bound == null) {
      return new Subst(variable, 0, value, true, this);
    }
    if (Objects.equals(bound.payload, value)) {
      return this;
    }
    return rebound(bound, new Subst(variable, 0, value, true, bound.next));
  }

  /**
   * This chain with the node {@code old} replaced by {@code fresh}, which extends what
   * {@code old} extended: the nodes newer than {@code old} are remade over it, in their order.
   */
  private Subst rebound(Subst old, Subst fresh) {
    List<Subst> newer = new ArrayList<>();
    for (Subst n = this; n != old; n = n.next) {
      newer.add(n);
    }
    Subst result = fresh;
    for (int i = newer.size() - 1; i >= 0; i--) {
      Subst n = newer.get(i);
      result = new Subst(Objects.requireNonNull(n.name), n.id, n.payload, n.isPayload, result);
    }
    return result;
  }

  /**
   * The class a subterm variable is bound to, if it is.
   *
   * @param variable the name of a subterm variable
   * @return the id as it was bound, empty if the variable is unbound
   */
  public OptionalInt id(String variable) {
    Subst bound = nodeOf(variable, false);
    return bound == null ? OptionalInt.empty() : OptionalInt.of(bound.id);
  }

  /**
   * The class a subterm variable is bound to, or {@link #UNBOUND}: {@link #id} without the
   * {@code OptionalInt}, for the matcher, which asks with the interned name of a pattern's
   * variable.
   */
  int idOrUnbound(String variable) {
    Subst bound = nodeOfInterned(variable, false);
    return bound == null ? UNBOUND : bound.id;
  }

  /** {@link #id} for an interned name, for the join, which asks about its clauses' variables. */
  OptionalInt idInterned(String variable) {
    Subst bound = nodeOfInterned(variable, false);
    return bound == null ? OptionalInt.empty() : OptionalInt.of(bound.id);
  }

  /** {@link #hasPayload} for an interned name: a head's own variable. */
  boolean hasPayloadInterned(String variable) {
    return nodeOfInterned(variable, true) != null;
  }

  /** {@link #payload} for an interned name that is bound: a head's own variable. */
  @Nullable Object payloadInterned(String variable) {
    Subst bound = nodeOfInterned(variable, true);
    if (bound == null) {
      throw new IllegalArgumentException("unbound payload variable " + variable + " in " + this);
    }
    return bound.payload;
  }

  /**
   * The class a subterm variable is bound to; it must be.
   *
   * @param variable the name of a subterm variable, which must be bound
   * @return the id as it was bound
   * @throws IllegalArgumentException if the variable is unbound
   */
  public int idOf(String variable) {
    Subst bound = nodeOf(variable, false);
    if (bound == null) {
      throw new IllegalArgumentException("unbound variable " + variable + " in " + this);
    }
    return bound.id;
  }

  /**
   * Whether the payload variable is bound.
   *
   * @param variable the name of a payload variable
   * @return true if it is bound, even to null
   */
  public boolean hasPayload(String variable) {
    return nodeOf(variable, true) != null;
  }

  /**
   * The value a payload variable is bound to; it must be.
   *
   * @param variable the name of a payload variable, which must be bound
   * @return the bound value, which may be null
   * @throws IllegalArgumentException if the variable is unbound
   */
  public @Nullable Object payload(String variable) {
    Subst bound = nodeOf(variable, true);
    if (bound == null) {
      throw new IllegalArgumentException("unbound payload variable " + variable + " in " + this);
    }
    return bound.payload;
  }

  /** The nodes of one kind, oldest first: the order the variables were bound in. */
  private List<Subst> bindings(boolean payload) {
    List<Subst> out = new ArrayList<>();
    for (Subst n = this; n.name != null; n = n.next) {
      if (n.isPayload == payload) {
        out.add(n);
      }
    }
    Collections.reverse(out);
    return out;
  }

  /**
   * The subterm bindings, in binding order, read-only; built on request.
   *
   * @return a fresh unmodifiable map from variable name to e-class id
   */
  public Map<String, Integer> ids() {
    LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
    for (Subst n : bindings(false)) {
      out.put(n.name, n.id);
    }
    return Collections.unmodifiableMap(out);
  }

  /**
   * The payload bindings, in binding order, read-only; built on request.
   *
   * @return a fresh unmodifiable map from variable name to payload value
   */
  public Map<String, Object> payloads() {
    LinkedHashMap<String, Object> out = new LinkedHashMap<>();
    for (Subst n : bindings(true)) {
      out.put(n.name, n.payload);
    }
    return Collections.unmodifiableMap(out);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Subst s)) {
      return false;
    }
    // Each variable is bound once in a chain, so the same count and every binding of this one
    // found in the other with the same value is equality.
    int mine = 0;
    for (Subst n = this; n.name != null; n = n.next) {
      mine++;
      Subst theirs = s.nodeOfInterned(n.name, n.isPayload);
      if (theirs == null
          || (n.isPayload ? !Objects.equals(theirs.payload, n.payload) : theirs.id != n.id)) {
        return false;
      }
    }
    int others = 0;
    for (Subst n = s; n.name != null; n = n.next) {
      others++;
    }
    return mine == others;
  }

  @Override
  public int hashCode() {
    // A sum over the bindings, so that equal substitutions in any order hash alike; each term
    // mixed first, so that two variables with their ids swapped (what a commutative rule yields
    // in every class) do not cancel out to one bucket.
    int h = 0;
    for (Subst n = this; n.name != null; n = n.next) {
      h += mix(31 * n.name.hashCode() + (n.isPayload ? Objects.hashCode(n.payload) : n.id));
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
