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
import java.util.TreeMap;

/**
 * One node chosen per class, for the classes some roots reach: the result of extraction over
 * several roots at once, where a subterm two roots share is one choice paid once. Reading it
 * back as terms gives a DAG, one {@link Extracted} object per class.
 *
 * @param <L> the language
 */
public final class Selection<L extends Language<L>> {

  private final TreeMap<Integer, L> chosen;
  private final IntList roots;
  private final double cost;

  Selection(TreeMap<Integer, L> chosen, IntList roots, double cost) {
    this.chosen = chosen;
    this.roots = roots;
    this.cost = cost;
  }

  /** The roots the selection was made for, as root ids. */
  public IntList roots() {
    return roots;
  }

  /** The node chosen for this class; the class must be one the roots reach. */
  public L node(int eclass) {
    L node = chosen.get(eclass);
    if (node == null) {
      throw new IllegalArgumentException("class " + eclass + " is not in the selection");
    }
    return node;
  }

  /** The classes the selection covers, in id order, with their nodes, read-only. */
  public Map<Integer, L> nodes() {
    return Collections.unmodifiableMap(chosen);
  }

  /** The sum of the chosen nodes' own costs, each class once. */
  public double cost() {
    return cost;
  }

  /** The lowest class the selection covers. */
  Integer firstClass() {
    return chosen.firstKey();
  }

  /** The next class the selection covers after {@code eclass}, or null. */
  Integer classAfter(int eclass) {
    return chosen.higherKey(eclass);
  }

  /** How many classes the selection covers. */
  public int size() {
    return chosen.size();
  }

  /** The selection's terms, one per root, sharing subterm objects where the roots share classes. */
  public List<Extracted<L>> terms() {
    Map<Integer, Extracted<L>> built = new LinkedHashMap<>();
    List<Extracted<L>> out = new ArrayList<>();
    for (int i = 0; i < roots.size(); i++) {
      out.add(term(roots.get(i), built));
    }
    return out;
  }

  private Extracted<L> term(int eclass, Map<Integer, Extracted<L>> built) {
    Extracted<L> done = built.get(eclass);
    if (done != null) {
      return done;
    }
    L node = node(eclass);
    List<Extracted<L>> kids = new ArrayList<>();
    IntList children = node.children();
    for (int i = 0; i < children.size(); i++) {
      kids.add(term(children.get(i), built));
    }
    Extracted<L> term = new Extracted<>(eclass, node, kids);
    built.put(eclass, term);
    return term;
  }

  @Override
  public String toString() {
    return "Selection" + chosen + " cost " + cost;
  }
}
