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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The memo of the classes {@code instantiate} found ({@code ApplyMemo}) returns what {@code add}
 * of the built node would, through merges, rebuilds and pruning, and only a pure head takes part.
 */
class ApplyMemoTest {

  private static Pattern<Toy> v(String name) {
    return Pattern.var(name);
  }

  private static Pattern<Toy> add(Pattern<Toy> a, Pattern<Toy> b) {
    return Pattern.of(new Toy.Add(IntList.EMPTY), a, b);
  }

  private static Pattern<Toy> mul(Pattern<Toy> a, Pattern<Toy> b) {
    return Pattern.of(new Toy.Mul(IntList.EMPTY), a, b);
  }

  private static Pattern<Toy> num(long n) {
    return Pattern.of(new Toy.Num(n));
  }

  /** The head of a division that reads its checked flag from a payload variable. */
  private static final Pattern.Head<Toy> DIV = Pattern.binding(Toy.Div.class, "c",
      Toy.Div::checked, (c, kids) -> new Toy.Div((Boolean) c, kids));

  /** What instantiate did before the memo: build the node over the children and add it. */
  private static int reference(EGraph<Toy, Void> g, Pattern<Toy> pattern, Subst subst) {
    return switch (pattern) {
      case Pattern.Var<Toy>(var name) -> g.find(subst.idOf(name));
      case Pattern.Node<Toy>(var head, var children) -> {
        int[] ids = new int[children.size()];
        for (int i = 0; i < ids.length; i++) {
          ids[i] = reference(g, children.get(i), subst);
        }
        yield g.add(head.build(subst, IntList.wrap(ids)));
      }
    };
  }

  /** The head {@code Pattern.of(prototype)} gives its node: a pure head. */
  @SuppressWarnings("unchecked")
  private static PureHead<Toy> pureHead(Toy prototype) {
    return (PureHead<Toy>) Pattern.head(prototype);
  }

  private static Pattern<Toy> randomPattern(Random r, int depth) {
    int kind = depth == 0 ? r.nextInt(3) : r.nextInt(6);
    return switch (kind) {
      case 0 -> v(new String[] {"x", "y", "z"}[r.nextInt(3)]);
      case 1 -> num(r.nextInt(4));
      case 2 -> v("x");
      case 3 -> add(randomPattern(r, depth - 1), randomPattern(r, depth - 1));
      case 4 -> mul(randomPattern(r, depth - 1), randomPattern(r, depth - 1));
      default -> Pattern.node(DIV, randomPattern(r, depth - 1), randomPattern(r, depth - 1));
    };
  }

  private static void sameGraph(EGraph<Toy, Void> a, EGraph<Toy, Void> b, String when) {
    assertEquals(a.numClasses(), b.numClasses(), "classes " + when);
    assertEquals(a.numNodes(), b.numNodes(), "nodes " + when);
  }

  @Test
  void theMemoReturnsWhatAddOfTheBuiltNodeReturns() {
    for (long seed = 1; seed <= 40; seed++) {
      Random r = new Random(seed);
      EGraph<Toy, Void> memo = EGraph.withoutAnalysis();
      EGraph<Toy, Void> plain = EGraph.withoutAnalysis();
      List<Integer> ids = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        int a = memo.add(new Toy.Num(i));
        assertEquals(a, plain.add(new Toy.Num(i)));
        ids.add(a);
      }
      // What was instantiated before, replayed later: the memo answers only for a pattern and
      // ids it has seen, and the classes it remembers are merged and pruned in between.
      List<Pattern<Toy>> seenPatterns = new ArrayList<>();
      List<Subst> seenSubsts = new ArrayList<>();
      for (int step = 0; step < 300; step++) {
        String when = "seed " + seed + " step " + step;
        int op = r.nextInt(10);
        if (op < 6) {
          Pattern<Toy> p;
          Subst s;
          if (seenPatterns.size() > 3 && r.nextBoolean()) {
            int which = r.nextInt(seenPatterns.size());
            p = seenPatterns.get(which);
            s = seenSubsts.get(which);
          } else {
            s = Subst.EMPTY.bind("x", ids.get(r.nextInt(ids.size())))
                .bind("y", ids.get(r.nextInt(ids.size())))
                .bind("z", ids.get(r.nextInt(ids.size())))
                .bindPayload("c", r.nextBoolean());
            p = randomPattern(r, 3);
            seenPatterns.add(p);
            seenSubsts.add(s);
          }
          // The ids are roots when they are bound only on a rebuilt graph; instantiate finds
          // each variable's root, so they need not be.
          int got = Matcher.instantiate(memo, p, s);
          assertEquals(reference(plain, p, s), got, "the class of " + p + " " + when);
          ids.add(got);
        } else if (op < 8) {
          int a = ids.get(r.nextInt(ids.size()));
          int b = ids.get(r.nextInt(ids.size()));
          assertEquals(plain.merge(a, b), memo.merge(a, b), "merge " + when);
        } else if (op == 8) {
          memo.rebuild();
          plain.rebuild();
        } else {
          int id = memo.find(ids.get(r.nextInt(ids.size())));
          List<Toy> nodes = new ArrayList<>(memo.classOf(id).nodes());
          if (nodes.size() > 1 && !memo.isDirty()) {
            Toy kept = nodes.get(0);
            assertEquals(plain.retainNodes(id, n -> n.equals(kept)),
                memo.retainNodes(id, n -> n.equals(kept)), "retain " + when);
          }
        }
        sameGraph(memo, plain, when);
      }
      memo.rebuild();
      plain.rebuild();
      memo.checkInvariants();
      plain.checkInvariants();
      sameGraph(memo, plain, "seed " + seed + " at the end");
      for (int id : ids) {
        assertEquals(plain.find(id), memo.find(id), "the partition at id " + id);
      }
    }
  }

  @Test
  void aPrunedNodeIsFoundThroughTheMemoAsItIsThroughAdd() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    int ab = g.add(new Toy.Add(IntList.of(a, b)));
    int ba = g.add(new Toy.Add(IntList.of(b, a)));
    g.merge(ab, ba);
    g.rebuild();
    Subst s = Subst.EMPTY.bind("x", a).bind("y", b);
    Pattern<Toy> swapped = add(v("y"), v("x"));
    int first = Matcher.instantiate(g, swapped, s);
    Toy dropped = new Toy.Add(IntList.of(b, a));
    assertEquals(1, g.retainNodes(g.find(ab), node -> !node.equals(dropped)));
    int nodes = g.numNodes();
    assertEquals(first, Matcher.instantiate(g, swapped, s), "the dropped node's class");
    assertEquals(nodes, g.numNodes(), "nothing was added");
    g.checkInvariants();
  }

  @Test
  void aPayloadBindingHeadAndAClientsHeadAreNotMemoized() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    // A payload variable that changes between calls changes the node: a memo keyed by the
    // children alone would answer the first call's class for the second.
    Pattern<Toy> div = Pattern.node(DIV, v("x"), v("x"));
    int checked = Matcher.instantiate(g, div, Subst.EMPTY.bind("x", a).bindPayload("c", true));
    int unchecked = Matcher.instantiate(g, div, Subst.EMPTY.bind("x", a).bindPayload("c", false));
    assertNotEquals(checked, unchecked);
    assertEquals(checked,
        Matcher.instantiate(g, div, Subst.EMPTY.bind("x", a).bindPayload("c", true)));
    // A head of the client's, with a key but no promise about its build, is built every time.
    int[] builds = {0};
    Pattern.Head<Toy> own = new Pattern.Head<>() {
      @Override
      public @Nullable Subst match(Toy node, Subst subst) {
        return node instanceof Toy.Add ? subst : null;
      }

      @Override
      public Toy build(Subst subst, IntList children) {
        builds[0]++;
        return new Toy.Add(children);
      }

      @Override
      public Optional<Object> key() {
        return Optional.of(new Toy.Add(IntList.EMPTY));
      }

      @Override
      public Optional<Set<String>> variables() {
        return Optional.of(Set.of());
      }
    };
    Pattern<Toy> sum = Pattern.node(own, v("x"), v("x"));
    Subst s = Subst.EMPTY.bind("x", a);
    int first = Matcher.instantiate(g, sum, s);
    assertEquals(first, Matcher.instantiate(g, sum, s));
    assertEquals(2, builds[0], "built both times: it is not a pure head");
  }

  @Test
  void aNodeOfMoreChildrenThanTheMemoHoldsIsStillInstantiated() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int[] ids = new int[ApplyMemo.MAX_ARITY + 1];
    Toy prototype = new Toy.Add(IntList.EMPTY);
    List<Pattern<Toy>> kids = new ArrayList<>();
    Subst s = Subst.EMPTY;
    for (int i = 0; i < ids.length; i++) {
      ids[i] = g.add(new Toy.Num(i));
      s = s.bind("v" + i, ids[i]);
      kids.add(v("v" + i));
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    Pattern<Toy> wide = Pattern.of(prototype, kids.toArray(new Pattern[0]));
    // Toy.Add has no fixed arity: five children is a node, memoized or not.
    int first = Matcher.instantiate(g, wide, s);
    assertEquals(first, Matcher.instantiate(g, wide, s));
    assertTrue(g.find(first) == first);
  }

  @Test
  void theMemoNeverAnswersForAKeyItWasNotGiven() {
    // Against a plain map, over far more keys than the table has slots, so that keys collide:
    // whatever it answers must be what the last put of that exact key said, and it may answer
    // nothing (it is a cache). A key that reached another key's slot is told apart by its
    // children and its head.
    List<PureHead<Toy>> heads = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      heads.add(pureHead(new Toy.Num(i)));
    }
    java.util.Map<List<Object>, Integer> truth = new java.util.HashMap<>();
    ApplyMemo memo = new ApplyMemo();
    Random r = new Random(7);
    int answered = 0;
    for (int i = 0; i < 20_000; i++) {
      int arity = r.nextInt(ApplyMemo.MAX_ARITY + 1);
      int[] ids = new int[arity];
      List<Object> key = new ArrayList<>();
      PureHead<Toy> head = heads.get(r.nextInt(heads.size()));
      key.add(head);
      for (int k = 0; k < arity; k++) {
        ids[k] = r.nextInt(12);
        key.add(ids[k]);
      }
      int known = memo.get(head, ids);
      if (known >= 0) {
        answered++;
        assertEquals(truth.get(key), known, "an answer for a key that was given another class");
      }
      int cls = r.nextInt(1000);
      memo.put(head, ids, cls);
      truth.put(key, cls);
      assertEquals(cls, memo.get(head, ids), "the key just put is found");
    }
    assertTrue(answered > 1000, "the memo answered often enough to be tested: " + answered);
    assertEquals(-1, memo.get(heads.get(0), new int[ApplyMemo.MAX_ARITY + 1]),
        "more children than an entry holds is never answered");
  }

  /** Adding a node named {@code boom} merges its class into class 1 and class 2 into class 0. */
  private static final Analysis<Toy, Void> HOSTILE = new Analysis<>() {
    @Override
    public Void make(EGraph<Toy, Void> g, Toy node) {
      return null;
    }

    @Override
    public Void join(Void a, Void b) {
      return null;
    }

    @Override
    public void modify(EGraph<Toy, Void> g, int id) {
      for (Toy node : g.classOf(id).nodes()) {
        if (node instanceof Toy.Var v && v.name().equals("boom")) {
          g.merge(g.find(1), id);
          g.merge(g.find(2), g.find(0));
          return;
        }
      }
    }
  };

  @Test
  void aModifyThatMergesAnEarlierSiblingMidInstantiateDoesNotMakeTheMemoAnswerForIt() {
    // (x + boom): the child x is found first, then boom is added, and its modify merges class 2,
    // x's, into class 0, so x is no longer a root when the sum is looked for. The sum of the
    // roots 2 and 1 is in the memo from an earlier call; add, asked with the ids as they stand,
    // looks under the canonical form (0, 1), which the hashcons has not been rebuilt to hold, and
    // makes a class. The memo must not answer with the old one.
    EGraph<Toy, Void> memo = new EGraph<>(HOSTILE);
    EGraph<Toy, Void> plain = new EGraph<>(HOSTILE);
    for (EGraph<Toy, Void> g : List.of(memo, plain)) {
      assertEquals(0, g.add(new Toy.Num(0)));
      assertEquals(1, g.add(new Toy.Num(5)));
      assertEquals(2, g.add(new Toy.Var("a")));
    }
    // One head for both sums: the memo is keyed by the head, as a rule's right-hand side is the
    // same head each time it fires.
    Pattern.Head<Toy> sum = Pattern.head(new Toy.Add(IntList.EMPTY));
    Pattern<Toy> first = Pattern.node(sum, v("x"), num(5));
    Pattern<Toy> second = Pattern.node(sum, v("x"), Pattern.of(new Toy.Var("boom")));
    Subst s = Subst.EMPTY.bind("x", 2);
    assertEquals(reference(plain, first, s), Matcher.instantiate(memo, first, s));
    sameGraph(memo, plain, "after the first sum");
    assertEquals(reference(plain, second, s), Matcher.instantiate(memo, second, s),
        "the class of the second sum");
    sameGraph(memo, plain, "after the second sum");
    assertTrue(memo.find(2) != 2, "class 2 was merged away while the sum was built");
    memo.rebuild();
    plain.rebuild();
    memo.checkInvariants();
    for (int id = 0; id < 6; id++) {
      assertEquals(plain.find(id), memo.find(id), "the partition at id " + id);
    }
  }

  /**
   * Rebuilds both graphs; false if the analysis refused (two constants in one class, which a
   * random merge can lead to), when the other graph must refuse too and the run is over.
   */
  private static boolean rebuildBoth(EGraph<Toy, Long> memo, EGraph<Toy, Long> plain) {
    try {
      memo.rebuild();
    } catch (IllegalStateException e) {
      assertThrows(IllegalStateException.class, plain::rebuild);
      return false;
    }
    plain.rebuild();
    return true;
  }

  @Test
  void theTwinGraphsAgreeUnderAnAnalysisThatFoldsMergesAndPrunes() {
    int finished = 0;
    for (long seed = 1; seed <= 40; seed++) {
      Random r = new Random(seed);
      EGraph<Toy, Long> memo = new EGraph<>(EGraphMergeTest.PRUNING_FOLD);
      EGraph<Toy, Long> plain = new EGraph<>(EGraphMergeTest.PRUNING_FOLD);
      List<Integer> ids = new ArrayList<>();
      List<Pattern<Toy>> seenPatterns = new ArrayList<>();
      List<Subst> seenSubsts = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        ids.add(memo.add(new Toy.Num(i)));
        assertEquals(ids.get(i), plain.add(new Toy.Num(i)));
      }
      ids.add(memo.add(new Toy.Var("a")));
      assertEquals(ids.get(3), plain.add(new Toy.Var("a")));
      boolean alive = true;
      for (int step = 0; step < 250 && alive; step++) {
        String when = "seed " + seed + " step " + step;
        int op = r.nextInt(10);
        if (op < 7) {
          Pattern<Toy> p;
          Subst s;
          if (seenPatterns.size() > 3 && r.nextBoolean()) {
            int which = r.nextInt(seenPatterns.size());
            p = seenPatterns.get(which);
            s = seenSubsts.get(which);
          } else {
            s = Subst.EMPTY.bind("x", ids.get(r.nextInt(ids.size())))
                .bind("y", ids.get(r.nextInt(ids.size())))
                .bind("z", ids.get(r.nextInt(ids.size())))
                .bindPayload("c", true);
            p = randomPattern(r, 3);
            seenPatterns.add(p);
            seenSubsts.add(s);
          }
          int expected = referenceFold(plain, p, s);
          int got = Matcher.instantiate(memo, p, s);
          assertEquals(expected, got, "the class of " + p + " " + when);
          ids.add(got);
        } else if (op < 9) {
          int a = ids.get(r.nextInt(ids.size()));
          int b = ids.get(r.nextInt(ids.size()));
          Long fa = memo.data(a);
          Long fb = memo.data(b);
          if (fa != null && fb != null && !fa.equals(fb)) {
            continue; // two different constants cannot be one class
          }
          assertEquals(plain.merge(a, b), memo.merge(a, b), "merge " + when);
        } else {
          alive = rebuildBoth(memo, plain);
        }
        if (alive) {
          assertEquals(plain.numClasses(), memo.numClasses(), "classes " + when);
          assertEquals(plain.numNodes(), memo.numNodes(), "nodes " + when);
        }
      }
      if (alive && rebuildBoth(memo, plain)) {
        memo.checkInvariants();
        plain.checkInvariants();
        for (int id : ids) {
          assertEquals(plain.find(id), memo.find(id), "the partition at id " + id);
        }
        finished++;
      }
    }
    assertTrue(finished >= 10, "enough runs reached the end to be compared: " + finished);
  }

  /** {@link #reference} over an analysed graph. */
  private static int referenceFold(EGraph<Toy, Long> g, Pattern<Toy> pattern, Subst subst) {
    return switch (pattern) {
      case Pattern.Var<Toy>(var name) -> g.find(subst.idOf(name));
      case Pattern.Node<Toy>(var head, var children) -> {
        int[] ids = new int[children.size()];
        for (int i = 0; i < ids.length; i++) {
          ids[i] = referenceFold(g, children.get(i), subst);
        }
        yield g.add(head.build(subst, IntList.wrap(ids)));
      }
    };
  }

  @Test
  void theInvariantsRefuseAMemoEntryThatNamesTheWrongClass() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    int a = g.add(new Toy.Var("a"));
    int b = g.add(new Toy.Var("b"));
    Pattern<Toy> sum = add(v("x"), v("y"));
    int ab = Matcher.instantiate(g, sum, Subst.EMPTY.bind("x", a).bind("y", b));
    g.checkInvariants();
    @SuppressWarnings("unchecked")
    PureHead<Toy> head = (PureHead<Toy>) ((Pattern.Node<Toy>) sum).head();
    g.remember(head, new int[] {a, b}, a); // a is not where a + b is
    IllegalStateException e = assertThrows(IllegalStateException.class, g::checkInvariants);
    assertTrue(e.getMessage().contains("the memo has"), e.getMessage());
    g.remember(head, new int[] {a, b}, ab);
    g.checkInvariants();
  }

  @Test
  void theMemoGrowsAndKeepsAnswering() {
    EGraph<Toy, Void> g = EGraph.withoutAnalysis();
    List<Integer> ids = new ArrayList<>();
    for (int i = 0; i < 3000; i++) {
      ids.add(g.add(new Toy.Num(i)));
    }
    Pattern<Toy> sum = add(v("x"), v("y"));
    List<Integer> first = new ArrayList<>();
    for (int i = 0; i + 1 < ids.size(); i++) {
      first.add(Matcher.instantiate(g, sum,
          Subst.EMPTY.bind("x", ids.get(i)).bind("y", ids.get(i + 1))));
    }
    int nodes = g.numNodes();
    for (int i = 0; i + 1 < ids.size(); i++) {
      assertEquals(first.get(i), Matcher.instantiate(g, sum,
          Subst.EMPTY.bind("x", ids.get(i)).bind("y", ids.get(i + 1))), "pair " + i);
    }
    assertEquals(nodes, g.numNodes(), "the second round added nothing");
    g.checkInvariants();
  }
}
