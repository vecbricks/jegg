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

/**
 * Several patterns joined, egg's {@code MultiPattern}: a list of clauses {@code ?var = pattern}.
 * As a searcher a match is a substitution under which every clause's pattern matches in the
 * class its variable is bound to, so clauses naming one variable match in one class and clauses
 * sharing a pattern variable are joined on it; a clause variable not yet bound ranges over the
 * classes. As a right-hand side ({@link Applier#multi}) each clause instantiates its pattern
 * and either binds its variable, if it is new, or unions the instance with the class the
 * variable names, so a right-hand side may introduce variables for later clauses.
 *
 * <p>egg writes {@code ?x = true = (path ?a ?b)} for the two clauses {@code ?x = true} and
 * {@code ?x = (path ?a ?b)}; here that is two clauses. A clause variable and a pattern variable
 * share a namespace, as in egg. The library parses no text: a client builds clauses from its
 * own trees, and the tests parse egg's syntax.
 *
 * @param <L> the language
 * @param clauses the clauses in join order, copied on construction; at least one
 */
public record MultiPattern<L extends Language<L>>(List<Clause<L>> clauses)
    implements Searcher<L> {

  /**
   * One clause: a pattern that must match in the class a variable names.
   *
   * @param <L> the language
   * @param var the clause variable's name, without the {@code ?}
   * @param pattern the pattern to match in the variable's class, or to instantiate and union
   *     with it
   */
  public record Clause<L extends Language<L>>(String var, Pattern<L> pattern) {
    /** Checks that neither component is null. */
    public Clause {
      java.util.Objects.requireNonNull(var, "var");
      java.util.Objects.requireNonNull(pattern, "pattern");
    }

    @Override
    public String toString() {
      return "?" + var + " = " + pattern;
    }
  }

  /**
   * Copies the clauses so the pattern is immutable.
   *
   * @throws IllegalArgumentException if there are no clauses
   */
  public MultiPattern {
    clauses = List.copyOf(clauses);
    if (clauses.isEmpty()) {
      throw new IllegalArgumentException("a multi-pattern needs at least one clause");
    }
  }

  /**
   * A multi-pattern of these clauses. The varargs array is read element by element and never
   * handed on, which is what makes the {@code @SafeVarargs} true.
   *
   * @param <L> the language
   * @param clauses the clauses in join order
   * @return the multi-pattern
   */
  @SafeVarargs
  public static <L extends Language<L>> MultiPattern<L> of(Clause<L>... clauses) {
    List<Clause<L>> list = new java.util.ArrayList<>(clauses.length);
    for (Clause<L> clause : clauses) {
      list.add(clause);
    }
    return new MultiPattern<>(list);
  }

  /**
   * A clause.
   *
   * @param <L> the language
   * @param var the clause variable's name, without the {@code ?}
   * @param pattern the pattern in that variable's class
   * @return the clause
   */
  public static <L extends Language<L>> Clause<L> clause(String var, Pattern<L> pattern) {
    return new Clause<>(var, pattern);
  }

  @Override
  public <D> List<Matcher.Match> search(EGraph<L, D> graph, int limit) {
    return Matcher.search(graph, this, limit);
  }

  /**
   * Every variable a match binds: each clause's variable, then its pattern's, in first-occurrence
   * order.
   */
  @Override
  public Set<String> subtermVariables() {
    Set<String> out = new LinkedHashSet<>();
    for (Clause<L> c : clauses) {
      out.add(c.var());
      out.addAll(c.pattern().subtermVariables());
    }
    return out;
  }

  @Override
  public Optional<Set<String>> payloadVariables() {
    Set<String> out = new LinkedHashSet<>();
    for (Clause<L> c : clauses) {
      Optional<Set<String>> declared = c.pattern().payloadVariables();
      if (declared.isEmpty()) {
        return Optional.empty();
      }
      out.addAll(declared.get());
    }
    return Optional.of(out);
  }

  /**
   * Whether the first clause's pattern is a bare variable, which has no class to start from:
   * egg refuses it as a searcher.
   *
   * @return true if the first clause is {@code ?v = ?w}
   */
  boolean startsWithBareVariable() {
    return clauses.get(0).pattern() instanceof Pattern.Var<L>;
  }

  @Override
  public String toString() {
    return clauses.toString();
  }
}
