#!/usr/bin/env bash
# Checks the built jar's module metadata: its descriptor names the expected version, and a
# modular client compiles and runs against it, with JSpecify supplied as the pom declares it.
#   dev/module-check.sh              the pom's version
#   dev/module-check.sh 0.1.1        a release version
set -euo pipefail
cd "$(dirname "$0")/.."
expected="${1:-$(sed -n 's|.*<version>\(.*\)</version>.*|\1|p' pom.xml | head -1)}"
jar="target/jegg-$expected.jar"
if [ ! -f "$jar" ]; then
  jar=$(ls target/jegg-*.jar 2>/dev/null | grep -v -E 'sources|javadoc' | head -1 || true)
fi
[ -f "$jar" ] || {
  echo "module-check: no jegg jar in target; run 'mvn -B package' first" >&2; exit 2
}
first=$(java --module-path "$jar" --describe-module io.github.vecbricks.jegg 2>/dev/null | head -1)
name=${first%% *}
got=${name#io.github.vecbricks.jegg@}
[ "$got" != "$name" ] || got="(none)"
[ "$got" = "$expected" ] \
  || { echo "module-check: the jar's module version is '$got', not '$expected'" >&2; exit 1; }
jspecify=$(find "$HOME/.m2/repository/org/jspecify/jspecify" \
  -name 'jspecify-*.jar' 2>/dev/null | sort | tail -1 || true)
[ -n "$jspecify" ] \
  || { echo "module-check: jspecify is not in ~/.m2; run 'mvn -B verify' first" >&2; exit 2; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/src/client"
cat > "$work/src/client/module-info.java" <<'JAVA'
module client {
  requires io.github.vecbricks.jegg;
}
JAVA
cat > "$work/src/client/Client.java" <<'JAVA'
package client;

import io.github.vecbricks.jegg.EGraph;

public class Client {
  public static void main(String[] args) {
    System.out.println("module-check: modular client ok, " + EGraph.withoutAnalysis().numNodes()
        + " nodes");
  }
}
JAVA
javac -Xlint:all --module-path "$jar:$jspecify" -d "$work/out" \
  --module-source-path "$work/src" $(find "$work/src" -name '*.java')
# At run time JSpecify is not needed: the descriptor requires it only statically.
java --module-path "$jar:$work/out" -m client/client.Client
echo "module-check: $jar names version $expected"
