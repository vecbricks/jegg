#!/usr/bin/env bash
# Runs a rules file and a term through prop's or math's language without writing Java (#39,
# #48): dev/explore.sh <prop|math> <rules-file> '<term>' [--limits i,n,c] [--json], egg's rule
# syntax 'name: lhs => rhs', one per non-blank line of the rules file.
#   dev/explore.sh prop rules.txt '(-> x y)'
#   dev/explore.sh math rules.txt '(+ x 0)' --limits 10,1000,100000 --json
set -euo pipefail
cd "$(dirname "$0")/.."
for a in "$@"; do
  case "$a" in -h|--help) sed -n '2,6p' "$0"; exit 0 ;; esac
done
command -v mvn >/dev/null \
  || { echo "mvn is not on the PATH (Java 25 and Maven are needed)" >&2; exit 2; }
mvn -B -q test-compile
cp_file=$(mktemp)
trap 'rm -f "$cp_file"' EXIT
mvn -B -q dependency:build-classpath -Dmdep.outputFile="$cp_file" -DincludeScope=test
exec java -cp "target/classes:target/test-classes:$(cat "$cp_file")" \
    io.github.vecbricks.jegg.Explore "$@"
