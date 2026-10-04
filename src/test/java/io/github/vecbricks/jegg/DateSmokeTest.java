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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Varka-shaped smoke test of PLAN.md 5, with no Varka dependency: a toy date language in
 * which a date's fields come either directly from the day count or through a civil decomposition
 * the fields can share; a folding rule whose right-hand side is a scalar expression over literal
 * slots; a cost table; and two roots extracted together, where the decomposition is chosen once
 * and shared although each root alone would not choose it. It exercises the bridge, a payload
 * variable and {@code extractAll} at once, which is what PLAN.md 3.2 asked the API for.
 */
class DateSmokeTest {

  sealed interface Date extends Language<Date> permits Date.Col, Date.Slot, Date.SlotSum,
      Date.AddDays, Date.Civil, Date.Field, Date.FieldOf {
    /** The input column. */
    record Col() implements Date {
      @Override
      public IntList children() {
        return IntList.EMPTY;
      }

      @Override
      public Date withChildren(IntList c) {
        return this;
      }
    }

    /** A literal bound at run time: the payload is its slot. */
    record Slot(int index) implements Date {
      @Override
      public IntList children() {
        return IntList.EMPTY;
      }

      @Override
      public Date withChildren(IntList c) {
        return this;
      }
    }

    /** A scalar expression over slots, hoisted out of the loop by the emitter: free per row. */
    record SlotSum(IntList children) implements Date {
      @Override
      public Date withChildren(IntList c) {
        return new SlotSum(c);
      }
    }

    record AddDays(IntList children) implements Date {
      @Override
      public Date withChildren(IntList c) {
        return new AddDays(c);
      }
    }

    /** The civil decomposition of a day count: expensive once, every field cheap after. */
    record Civil(IntList children) implements Date {
      @Override
      public Date withChildren(IntList c) {
        return new Civil(c);
      }
    }

    /** A field straight from the day count; the payload names which. */
    record Field(String name, IntList children) implements Date {
      @Override
      public Date withChildren(IntList c) {
        return new Field(name, c);
      }
    }

    /** A field read off a decomposition. */
    record FieldOf(String name, IntList children) implements Date {
      @Override
      public Date withChildren(IntList c) {
        return new FieldOf(name, c);
      }
    }
  }

  /** The cost table: the numbers a measured register would give. */
  static final CostFunction<Date> TABLE = node -> switch (node) {
    case Date.Col c -> 0.0;
    case Date.Slot s -> 0.0;
    case Date.SlotSum s -> 0.0;
    case Date.AddDays a -> 1.0;
    case Date.Civil c -> 30.0;
    case Date.Field f -> 20.0;
    case Date.FieldOf f -> 1.0;
  };

  private static Pattern<Date> v(String n) {
    return Pattern.var(n);
  }

  private static Pattern<Date> addDays(Pattern<Date> d, Pattern<Date> s) {
    return Pattern.of(new Date.AddDays(IntList.EMPTY), d, s);
  }

  /** The rules: fold two offsets into one scalar; a field through the decomposition. */
  static List<Rewrite<Date, Void>> rules() {
    Pattern.Head<Date> field = Pattern.binding(Date.Field.class, "f", Date.Field::name,
        (name, kids) -> new Date.Field((String) name, kids));
    Pattern.Head<Date> fieldOf = Pattern.binding(Date.FieldOf.class, "f", Date.FieldOf::name,
        (name, kids) -> new Date.FieldOf((String) name, kids));
    return List.of(
        Rewrite.of("fold-offsets", addDays(addDays(v("d"), v("s1")), v("s2")),
            addDays(v("d"), Pattern.of(new Date.SlotSum(IntList.EMPTY), v("s1"), v("s2")))),
        Rewrite.of("field-through-civil", Pattern.node(field, v("d")),
            Pattern.node(fieldOf, Pattern.of(new Date.Civil(IntList.EMPTY), v("d")))),
        Rewrite.of("civil-field-direct",
            Pattern.node(fieldOf, Pattern.of(new Date.Civil(IntList.EMPTY), v("d"))),
            Pattern.node(field, v("d"))));
  }

  @Test
  void twoFieldsOfOneShiftedDateShareTheDecompositionWhenExtractedTogether() {
    EGraph<Date, Void> g = EGraph.withoutAnalysis();
    int d = g.add(new Date.Col());
    int shifted = g.add(new Date.AddDays(IntList.of(
        g.add(new Date.AddDays(IntList.of(d, g.add(new Date.Slot(0))))), g.add(new Date.Slot(1)))));
    int year = g.add(new Date.Field("year", IntList.of(shifted)));
    int month = g.add(new Date.Field("month", IntList.of(shifted)));
    RunReport report = Runner.of(g, rules()).run();
    assertTrue(report.stop() instanceof StopReason.Saturated, report.toString());
    Extractor<Date, Void> ex = new Extractor<>(g, TABLE);

    // Alone, each field is cheaper straight from the day count: 20 + 1 against 30 + 1 + 1.
    Extracted<Date> yearAlone = ex.extract(year);
    assertEquals(21.0, ex.best(year).cost(), 1e-9);
    assertTrue(yearAlone.node() instanceof Date.Field, yearAlone.toString());
    // The offsets folded into one scalar under the one AddDays.
    assertTrue(yearAlone.children().get(0).children().get(1).node() instanceof Date.SlotSum,
        yearAlone.toString());

    // Together, the decomposition is shared: 30 + 1 + 1 + 1 for AddDays, against 2 * 21 = 42
    // as trees - and the two single-root extractions summed with the shared AddDays paid twice
    // is what the plan's prediction 6 compares against.
    Selection<Date> both = ex.extractAll(IntList.of(year, month));
    assertEquals(33.0, both.cost(), 1e-9, both.toString());
    assertTrue(both.node(g.find(year)) instanceof Date.FieldOf, both.toString());
    assertTrue(both.node(g.find(month)) instanceof Date.FieldOf, both.toString());
    List<Extracted<Date>> terms = both.terms();
    assertTrue(terms.get(0).children().get(0) == terms.get(1).children().get(0),
        "one Civil object under both fields");
    assertTrue(both.cost() < ex.best(year).cost() + ex.best(month).cost());
  }
}
