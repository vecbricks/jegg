/*
 * Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vecbricks.jegg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An e-graph from extraction-gym's {@code egraph-serialize} JSON (see
 * {@code src/test/resources/extraction-gym/README.md}): nodes keyed by id with an operator, a
 * cost, children that are node ids, and a class; and the root classes. A node here carries its
 * gym id, so two gym nodes alike in operator and children but in different classes stay apart
 * when hashconsed, as the gym keeps them.
 */
final class GymGraph {

  /** A gym node: its id, operator and cost, and its children as class ids. */
  record Node(String id, String op, double cost, IntList children) implements Language<Node> {
    @Override
    public Node withChildren(IntList c) {
      return new Node(id, op, cost, c);
    }

    @Override
    public String toString() {
      return op;
    }
  }

  static final CostFunction<Node> COST = Node::cost;

  final String name;
  final EGraph<Node, Void> graph;
  final IntList roots;
  final int gymNodes;
  final int gymClasses;

  private GymGraph(String name, EGraph<Node, Void> graph, IntList roots, int gymNodes,
      int gymClasses) {
    this.name = name;
    this.graph = graph;
    this.roots = roots;
    this.gymNodes = gymNodes;
    this.gymClasses = gymClasses;
  }

  /** The graph in the file, or empty if it has a cycle or no root the graph here can hold. */
  @SuppressWarnings("unchecked")
  static Optional<GymGraph> read(Path file) throws IOException {
    Map<String, Object> doc = (Map<String, Object>) Json.parse(
        Files.readString(file, StandardCharsets.UTF_8));
    Map<String, Object> nodes = (Map<String, Object>) doc.get("nodes");
    Map<String, String> classOfNode = new HashMap<>();
    Map<String, List<String>> nodesOfClass = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : nodes.entrySet()) {
      String eclass = (String) ((Map<String, Object>) e.getValue()).get("eclass");
      classOfNode.put(e.getKey(), eclass);
      nodesOfClass.computeIfAbsent(eclass, _ -> new ArrayList<>()).add(e.getKey());
    }
    EGraph<Node, Void> g = EGraph.withoutAnalysis();
    Map<String, Integer> idOfClass = new HashMap<>();
    List<String> pending = new ArrayList<>(nodes.keySet());
    // Bottom-up: a node is added once every child's class has an id; a pass that adds nothing
    // with nodes left means a cycle.
    while (!pending.isEmpty()) {
      List<String> next = new ArrayList<>();
      for (String id : pending) {
        Map<String, Object> n = (Map<String, Object>) nodes.get(id);
        List<Object> children = (List<Object>) n.getOrDefault("children", List.of());
        int[] kids = new int[children.size()];
        boolean ready = true;
        for (int i = 0; i < kids.length; i++) {
          Integer c = idOfClass.get(classOfNode.get((String) children.get(i)));
          if (c == null) {
            ready = false;
            break;
          }
          kids[i] = c;
        }
        if (!ready) {
          next.add(id);
          continue;
        }
        double cost = n.get("cost") == null ? 1.0 : (Double) n.get("cost");
        int added = g.add(new Node(id, (String) n.get("op"), cost, IntList.of(kids)));
        String eclass = classOfNode.get(id);
        Integer existing = idOfClass.get(eclass);
        if (existing == null) {
          idOfClass.put(eclass, added);
        } else {
          g.merge(existing, added);
        }
      }
      if (next.size() == pending.size()) {
        return Optional.empty();
      }
      pending = next;
    }
    g.rebuild();
    // A root naming a class no node has (the format wants canonical roots, and some files do
    // not keep to it) is dropped; a file with no root left is skipped like a cyclic one.
    List<Object> rootClasses = (List<Object>) doc.getOrDefault("root_eclasses", List.of());
    List<Integer> roots = new ArrayList<>();
    for (Object r : rootClasses) {
      Integer id = idOfClass.get((String) r);
      if (id != null) {
        roots.add(g.find(id));
      }
    }
    if (roots.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new GymGraph(file.getFileName().toString(), g,
        IntList.of(roots.stream().mapToInt(Integer::intValue).toArray()), nodes.size(),
        nodesOfClass.size()));
  }
}
