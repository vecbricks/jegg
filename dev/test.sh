#!/usr/bin/env bash
# The fast suite: mvn verify with the two slow lambda tests skipped, lint clean, coverage printed.
#   dev/test.sh                 the fast suite
#   dev/test.sh --all           everything, as CI runs it
#   dev/test.sh LambdaTest      one class, or 'LambdaTest#lambdaIf' for one method
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:-}" in -h|--help) sed -n '2,5p' "$0"; exit 0 ;; esac
command -v mvn >/dev/null || { echo "mvn is not on the PATH (Java 25 and Maven are needed)" >&2; exit 2; }
case "${1:-}" in
  "")      exec mvn -B -q verify -Dsurefire.excludedGroups=slow ;;
  --all)   exec mvn -B -q verify ;;
  *)       exec mvn -B -q test -Dtest="$1" ;;
esac
