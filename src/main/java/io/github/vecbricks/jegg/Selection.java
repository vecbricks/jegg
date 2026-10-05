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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One node chosen per class, for the classes some roots reach: the result of extraction over
 * several roots at once, where a subterm two roots share is one choice paid once. Reading it
 * back as terms gives a DAG, one {@link Extracted} object per class.
 *
 * <p>Held as a sorted array of class ids beside the chosen nodes, no map and no boxed id; the
 * map view {@link #nodes()} is built when asked for.
 *
 * @param <L> the language
 */
public final class Selection<L extends Language<L>> {

  private final int[] ids;
  private final Object[] chosen;
  private final IntList roots;
  private final double cost;

  /** {@code ids} ascending, {@code chosen} the node for each; both are kept, not copied. */
  Selection(int[] ids, Object[] chosen, IntList roots, double cost) {
    this.ids = ids;
    this.chosen = chosen;
    this.roots = roots;
    this.cost = cost;
  }

  /** The roots the selection was made for, as root ids. */
  public IntList roots() {
    return roots;
  }

  @SuppressWarnings("unchecked")
  private L at(int index) {
    return (L) chosen[index];
  }

  /** The node chosen for this class; the class must be one the roots reach. */
  public L node(int eclass) {
    int index = Arrays.binarySearch(ids, eclass);
    if (index < 0) {
      throw new IllegalArgumentException("class " + eclass + " is not in the selection");
    }
    return at(index);
  }

  /** The classes the selection covers, in id order, with their nodes, read-only. */
  public Map<Integer, L> nodes() {
    LinkedHashMap<Integer, L> out = LinkedHashMap.newLinkedHashMap(ids.length);
    for (int i = 0; i < ids.length; i++) {
      out.put(ids[i], at(i));
    }
    return Collections.unmodifiableMap(out);
  }

  /** The sum of the chosen nodes' own costs, each class once. */
  public double cost() {
    return cost;
  }

  /** How many classes the selection covers. */
  public int size() {
    return ids.length;
  }

  /** The selection's terms, one per root, sharing subterm objects where the roots share classes. */
  public List<Extracted<L>> terms() {
    Object[] built = new Object[ids.length];
    List<Extracted<L>> out = new ArrayList<>();
    for (int i = 0; i < roots.size(); i++) {
      out.add(term(roots.get(i), built));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private Extracted<L> term(int eclass, Object[] built) {
    int index = Arrays.binarySearch(ids, eclass);
    if (index < 0) {
      throw new IllegalArgumentException("class " + eclass + " is not in the selection");
    }
    if (built[index] != null) {
      return (Extracted<L>) built[index];
    }
    L node = at(index);
    List<Extracted<L>> kids = new ArrayList<>();
    IntList children = node.children();
    for (int i = 0; i < children.size(); i++) {
      kids.add(term(children.get(i), built));
    }
    Extracted<L> term = new Extracted<>(eclass, node, kids);
    built[index] = term;
    return term;
  }

  @Override
  public String toString() {
    return "Selection" + nodes() + " cost " + cost;
  }
}
