/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A pattern over a language: a variable, which matches any e-class and binds it, or a node,
 * whose {@link Head} matches an e-node's operator and payload and whose children are patterns.
 *
 * <p>egg's variables stand for subterms only. A client whose nodes carry payloads - an
 * arithmetic mode, a divisor, a trunc level - needs to match them too and carry them to a
 * right-hand side, so a head here may bind a payload variable ({@link #binding}) as well as
 * require a fixed one ({@link #head}). The same pattern serves as a right-hand side: a variable
 * is replaced by its binding and a node is built by its head from the substitution.
 *
 * @param <L> the language
 */
public sealed interface Pattern<L extends Language<L>> permits Pattern.Var, Pattern.Node {

  /**
   * A variable: matches any class, binding it; or must agree with its binding.
   *
   * @param <L> the language
   * @param name the variable's name, without the {@code ?} that {@link #toString} prefixes
   */
  record Var<L extends Language<L>>(String name) implements Pattern<L> {
    @Override
    public String toString() {
      return "?" + name;
    }
  }

  /**
   * An operator with its payload constraint, over child patterns in argument order.
   *
   * @param <L> the language
   * @param head matches the e-node's operator and payload, and builds a node on the right-hand
   *     side
   * @param children the child patterns in argument order, copied on construction so the list is
   *     immutable; empty for a leaf
   */
  record Node<L extends Language<L>>(Head<L> head, List<Pattern<L>> children)
      implements Pattern<L> {
    /** Copies {@code children} so the pattern is immutable. */
    public Node {
      children = List.copyOf(children);
    }

    @Override
    public String toString() {
      return head + children.toString();
    }
  }

  /**
   * What a pattern node says about an e-node's operator and payload, and how it builds one.
   *
   * @param <L> the language
   */
  interface Head<L extends Language<L>> {

    /**
     * The substitution extended by whatever this head binds if {@code node}'s operator and
     * payload match under {@code subst}, or {@code null} if they do not. Children are not
     * looked at here.
     *
     * @param node the e-node to test; its operator and payload are read, its children are not
     * @param subst the bindings so far, which this head must agree with for any payload variable
     *     it has already bound
     * @return {@code subst} or an extension of it with this head's payload bindings, or
     *     {@code null} (not an empty substitution) if the node does not match
     */
    Subst match(L node, Subst subst);

    /**
     * The e-node with this head over these children, payload variables read from the subst.
     *
     * @param subst the match's substitution, which must bind every payload variable this head
     *     reads
     * @param children the ids of the child classes, in argument order
     * @return a new e-node, not yet in any graph
     */
    L build(Subst subst, IntList children);

    /**
     * The payload variables this head binds in {@link #match}, declared, or empty if the head
     * does not say. {@link Rewrite} checks a right-hand side's payload variables against the
     * left's only when every head on both sides declares, so a head that binds without saying
     * so is never wrongly refused, only unchecked. jegg's own heads declare.
     *
     * @return the declared payload variable names, empty if the head does not say; an empty set
     *     inside means it declares that it binds none
     */
    default Optional<Set<String>> variables() {
      return Optional.empty();
    }

    /**
     * The class of e-node this head can match, when it is one class (a record per operator, as
     * jegg's languages are written): a search then starts at the classes holding such a node
     * rather than at every class. Empty, the default, means any node may match and every class
     * is looked at. A head that names a class must match no node outside it; jegg's own heads
     * name theirs.
     *
     * @return the one node class this head matches, or empty if any node may match
     */
    default Optional<Class<? extends L>> type() {
      return Optional.empty();
    }
  }

  /**
   * The subterm variables of this pattern, in first-occurrence order.
   *
   * @return a fresh set of variable names, without the {@code ?}, empty if there are none
   */
  default Set<String> subtermVariables() {
    Set<String> out = new LinkedHashSet<>();
    switch (this) {
      case Var<L>(var name) -> out.add(name);
      case Node<L>(var head, var children) -> {
        for (Pattern<L> child : children) {
          out.addAll(child.subtermVariables());
        }
      }
    }
    return out;
  }

  /**
   * The payload variables of this pattern's heads, in first-occurrence order, or empty if a
   * head does not declare its variables ({@link Head#variables}).
   *
   * @return a fresh set of payload variable names, or empty if some head does not declare; an
   *     empty set inside means every head declares and none binds a payload
   */
  default Optional<Set<String>> payloadVariables() {
    Set<String> out = new LinkedHashSet<>();
    return collectPayload(this, out) ? Optional.of(out) : Optional.empty();
  }

  private static <L extends Language<L>> boolean collectPayload(Pattern<L> p, Set<String> out) {
    switch (p) {
      case Var<L> _ -> {
        return true;
      }
      case Node<L>(var head, var children) -> {
        Optional<Set<String>> declared = head.variables();
        if (declared.isEmpty()) {
          return false;
        }
        out.addAll(declared.get());
        for (Pattern<L> child : children) {
          if (!collectPayload(child, out)) {
            return false;
          }
        }
        return true;
      }
    }
  }

  /**
   * A variable pattern.
   *
   * @param <L> the language
   * @param name the variable's name, without the {@code ?}; the same name in two places must
   *     bind the same class
   * @return the variable pattern
   */
  static <L extends Language<L>> Pattern<L> var(String name) {
    return new Var<>(name);
  }

  /**
   * A node pattern with this head over these children. The varargs array is read element by
   * element and never handed on, which is what makes the {@code @SafeVarargs} true.
   *
   * @param <L> the language
   * @param head the head the node matches or builds by
   * @param children the child patterns in argument order; none for a leaf
   * @return the node pattern, over a copy of {@code children}
   */
  @SafeVarargs
  static <L extends Language<L>> Pattern<L> node(Head<L> head, Pattern<L>... children) {
    List<Pattern<L>> list = new java.util.ArrayList<>(children.length);
    for (Pattern<L> child : children) {
      list.add(child);
    }
    return new Node<>(head, list);
  }

  /**
   * A node pattern with {@code prototype}'s operator and payload over these children.
   *
   * @param <L> the language
   * @param prototype a node supplying the operator and payload; its own children are ignored
   * @param children the child patterns in argument order; none for a leaf
   * @return the node pattern, over a copy of {@code children}
   */
  @SafeVarargs
  static <L extends Language<L>> Pattern<L> of(L prototype, Pattern<L>... children) {
    List<Pattern<L>> list = new java.util.ArrayList<>(children.length);
    for (Pattern<L> child : children) {
      list.add(child);
    }
    return new Node<>(head(prototype), list);
  }

  /**
   * A head that matches exactly {@code prototype}'s operator and payload - its
   * {@link Language#head} - and builds with {@link Language#withChildren}. The prototype's
   * own children are ignored.
   *
   * @param <L> the language
   * @param prototype a node supplying the operator and payload to match and to build with
   * @return a head that declares no payload variables and names the prototype's class when its
   *     head is a node of that class
   */
  static <L extends Language<L>> Head<L> head(L prototype) {
    Object key = prototype.head();
    // Equal heads are nodes of one class only when the head is itself a node of the prototype's
    // class (the default head(), or a key of that class): then a search may start at the classes
    // holding such a node. A key of another class, a language's cheaper key, could be shared
    // across node classes, and the head names no type: every class is looked at.
    @SuppressWarnings("unchecked")
    Optional<Class<? extends L>> type = key.getClass() == prototype.getClass()
        ? Optional.of((Class<? extends L>) prototype.getClass()) : Optional.empty();
    return new Head<>() {
      @Override
      public Subst match(L node, Subst subst) {
        return key.equals(node.head()) ? subst : null;
      }

      @Override
      public Optional<Class<? extends L>> type() {
        return type;
      }

      @Override
      public L build(Subst subst, IntList children) {
        return prototype.withChildren(children);
      }

      @Override
      public Optional<Set<String>> variables() {
        return Optional.of(Set.of());
      }

      @Override
      public String toString() {
        return key.toString();
      }
    };
  }

  /**
   * A head that matches any node of {@code type} and binds its payload, read by
   * {@code payloadOf}, to the payload variable {@code variable} - or requires it to equal the
   * variable's binding if there is one - and builds through {@code build} from the bound
   * payload and the children.
   *
   * @param <L> the language
   * @param <N> the node class the head matches
   * @param type the node class matched; a node of another class does not match
   * @param variable the payload variable's name, without the {@code ?}
   * @param payloadOf reads the payload from a node of {@code type}, compared by {@code equals}
   *     with the variable's binding
   * @param build builds an e-node from the payload bound to {@code variable} and the child
   *     class ids
   * @return a head that declares {@code variable} as its one payload variable
   */
  static <L extends Language<L>, N extends L> Head<L> binding(Class<N> type, String variable,
      Function<N, ?> payloadOf, BiFunction<Object, IntList, L> build) {
    return new Head<>() {
      @Override
      public Optional<Class<? extends L>> type() {
        return Optional.of(type);
      }

      @Override
      public Subst match(L node, Subst subst) {
        if (!type.isInstance(node)) {
          return null;
        }
        Object payload = payloadOf.apply(type.cast(node));
        if (subst.hasPayload(variable)) {
          return java.util.Objects.equals(subst.payload(variable), payload) ? subst : null;
        }
        return subst.bindPayload(variable, payload);
      }

      @Override
      public L build(Subst subst, IntList children) {
        return build.apply(subst.payload(variable), children);
      }

      @Override
      public Optional<Set<String>> variables() {
        return Optional.of(Set.of(variable));
      }

      @Override
      public String toString() {
        return type.getSimpleName() + "{?" + variable + "}";
      }
    };
  }
}
