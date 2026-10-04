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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A reader for the small JSON the tests need: objects, arrays, strings, numbers, booleans, null. */
final class Json {

  private final String s;
  private int i;

  private Json(String s) {
    this.s = s;
  }

  /** The document as maps ({@code LinkedHashMap}, in document order), lists, strings, doubles. */
  static Object parse(String s) {
    Json j = new Json(s);
    Object v = j.value();
    j.skip();
    if (j.i != s.length()) {
      throw new IllegalArgumentException("trailing input at " + j.i);
    }
    return v;
  }

  private Object value() {
    skip();
    char c = s.charAt(i);
    switch (c) {
      case '{' -> {
        i++;
        Map<String, Object> m = new LinkedHashMap<>();
        skip();
        if (s.charAt(i) == '}') {
          i++;
          return m;
        }
        while (true) {
          skip();
          String key = string();
          skip();
          expect(':');
          m.put(key, value());
          skip();
          if (s.charAt(i) == ',') {
            i++;
          } else {
            expect('}');
            return m;
          }
        }
      }
      case '[' -> {
        i++;
        List<Object> l = new ArrayList<>();
        skip();
        if (s.charAt(i) == ']') {
          i++;
          return l;
        }
        while (true) {
          l.add(value());
          skip();
          if (s.charAt(i) == ',') {
            i++;
          } else {
            expect(']');
            return l;
          }
        }
      }
      case '"' -> {
        return string();
      }
      case 't' -> {
        i += 4;
        return Boolean.TRUE;
      }
      case 'f' -> {
        i += 5;
        return Boolean.FALSE;
      }
      case 'n' -> {
        i += 4;
        return null;
      }
      default -> {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
          i++;
        }
        return Double.parseDouble(s.substring(start, i));
      }
    }
  }

  private String string() {
    expect('"');
    StringBuilder b = new StringBuilder();
    while (s.charAt(i) != '"') {
      char c = s.charAt(i++);
      if (c == '\\') {
        char e = s.charAt(i++);
        switch (e) {
          case 'n' -> b.append('\n');
          case 't' -> b.append('\t');
          case 'r' -> b.append('\r');
          case 'b' -> b.append('\b');
          case 'f' -> b.append('\f');
          case 'u' -> {
            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
            i += 4;
          }
          default -> b.append(e);
        }
      } else {
        b.append(c);
      }
    }
    i++;
    return b.toString();
  }

  private void expect(char c) {
    if (s.charAt(i) != c) {
      throw new IllegalArgumentException("expected " + c + " at " + i + ", found " + s.charAt(i));
    }
    i++;
  }

  private void skip() {
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
      i++;
    }
  }
}
