#!/usr/bin/env bash
# Regenerates a results file under benchmarks/, following benchmarks/README.md: from a clean
# commit, pinned to the fast cores, on an idle machine - which only the owner can vouch for, so
# the script refuses to run without --idle. Afterwards it shows what moved against the committed
# file.
#   dev/bench.sh --idle projection            one benchmark: rebuild | projection | determinism
#   dev/bench.sh --idle projection,determinism  several in one process (one load check)
#   dev/bench.sh --idle all --quick           the quick wiring check; numbers not for committing
set -euo pipefail
cd "$(dirname "$0")/.."
idle=0; quick=false; which=""
for a in "$@"; do
  case "$a" in
    --idle) idle=1 ;;
    --quick) quick=true ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) which="$a" ;;
  esac
done
[ -n "$which" ] || { sed -n '2,9p' "$0"; exit 2; }
if [ "$idle" -ne 1 ]; then
  echo "A benchmark runs only on an idle machine the owner has offered: pass --idle to say so." >&2
  exit 1
fi
if [ -n "$(git status --porcelain)" ]; then
  echo "The tree has uncommitted changes; a results file names the commit it was measured from." >&2
  echo "Commit or stash first (the harness would stamp the file +dirty)." >&2
  exit 1
fi
command -v mvn >/dev/null || { echo "mvn is not on the PATH" >&2; exit 2; }
echo "load $(cut -d' ' -f1-3 /proc/loadavg), commit $(git rev-parse --short HEAD), $(date +%H:%M)"
mvn -Pbench -q test-compile exec:exec -Dbench="$which" -Dbench.quick="$quick"
echo
echo "What moved against the committed files:"
git --no-pager diff --stat -- benchmarks/
for f in $(git diff --name-only -- benchmarks/); do
  echo "== $f"
  git --no-pager diff -U0 -- "$f" | grep -E "^[-+] *[A-Za-z]+Benchmark\.[a-zA-Z]+ " | head -40
done
