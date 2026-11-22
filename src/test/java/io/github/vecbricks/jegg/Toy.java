/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vecbricks.jegg;

import java.util.List;

/**
 * A language for the tests: one record per operator, a payload where the operator has one, the
 * children in an {@link IntList}. The shape every client language takes.
 */
sealed interface Toy extends Language<Toy> permits Toy.Num, Toy.Var, Toy.Add, Toy.Mul, Toy.Div {

  /** A number: the payload is the value, there are no children. */
  record Num(long value) implements Toy {
    @Override
    public IntList children() {
      return IntList.EMPTY;
    }

    @Override
    public Toy withChildren(IntList children) {
      return this;
    }
  }

  /** A variable: the payload is its name. */
  record Var(String name) implements Toy {
    @Override
    public IntList children() {
      return IntList.EMPTY;
    }

    @Override
    public Toy withChildren(IntList children) {
      return this;
    }
  }

  record Add(IntList children) implements Toy {
    @Override
    public Toy withChildren(IntList children) {
      return new Add(children);
    }
  }

  record Mul(IntList children) implements Toy {
    @Override
    public Toy withChildren(IntList children) {
      return new Mul(children);
    }
  }

  /** A division with a payload beside its children: whether it is checked. */
  record Div(boolean checked, IntList children) implements Toy {
    @Override
    public Toy withChildren(IntList children) {
      return new Div(checked, children);
    }
  }

  /** The tests' own tree type, children held as nodes, bridged to {@link Toy}. */
  record Tree(String op, Object payload, List<Tree> kids) {
    static Tree num(long v) {
      return new Tree("num", v, List.of());
    }

    static Tree var(String n) {
      return new Tree("var", n, List.of());
    }

    static Tree add(Tree a, Tree b) {
      return new Tree("add", null, List.of(a, b));
    }

    static Tree mul(Tree a, Tree b) {
      return new Tree("mul", null, List.of(a, b));
    }

    static Tree div(boolean checked, Tree a, Tree b) {
      return new Tree("div", checked, List.of(a, b));
    }
  }

  TreeBridge<Tree, Toy> BRIDGE = new TreeBridge<>() {
    @Override
    public List<Tree> childrenOf(Tree tree) {
      return tree.kids();
    }

    @Override
    public Toy node(Tree tree, IntList children) {
      return switch (tree.op()) {
        case "num" -> new Num((Long) tree.payload());
        case "var" -> new Var((String) tree.payload());
        case "add" -> new Add(children);
        case "mul" -> new Mul(children);
        case "div" -> new Div((Boolean) tree.payload(), children);
        default -> throw new IllegalArgumentException(tree.op());
      };
    }

    @Override
    public Tree build(Toy node, List<Tree> children) {
      return switch (node) {
        case Num n -> Tree.num(n.value());
        case Var v -> Tree.var(v.name());
        case Add a -> new Tree("add", null, children);
        case Mul m -> new Tree("mul", null, children);
        case Div d -> new Tree("div", d.checked(), children);
      };
    }
  };

  /** A head for {@code +} that counts the nodes it is asked to match: how far a search went. */
  final class CountingHead implements Pattern.Head<Toy> {
    private final Pattern.Head<Toy> head = Pattern.head(new Add(IntList.EMPTY));
    int asked;

    @Override
    public Subst match(Toy node, Subst subst) {
      asked++;
      return head.match(node, subst);
    }

    @Override
    public Toy build(Subst subst, IntList children) {
      return head.build(subst, children);
    }

    @Override
    public java.util.Optional<java.util.Set<String>> variables() {
      return head.variables();
    }
  }
}
