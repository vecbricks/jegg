#!/usr/bin/env bash
# jegg's time against egg's on the same runs, by phase (#72): the fuzz term sets of a seed through
# egg's harness and through jegg, alternating over rounds, and lambda_function_repeat through
# egg's own test and the harness's deferred run. This is what the Fuzz workflow's compare job
# runs; locally it runs only on a machine the owner has offered (AGENTS.md).
#   dev/compare.sh [rounds] [terms] [seed] [out-dir]      defaults 5, 1000, 2, compare/
# Writes <out-dir>/{prop,math,lambda}.jsonl, provenance.txt and summary.md. Needs cargo and mvn.
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:-}" in -h|--help) sed -n '2,7p' "$0"; exit 2 ;; esac
rounds="${1:-5}"; terms="${2:-1000}"; seed="${3:-2}"; out="${4:-compare}"
command -v mvn >/dev/null || { echo "mvn is not on the PATH" >&2; exit 2; }
cache=$(dev/egg_harness.sh prop | tail -1)
dev/egg_harness.sh math >/dev/null
(cd "$cache" && cargo test -q --release --test lambda --no-run)
mvn -B -q --no-transfer-progress test-compile dependency:build-classpath \
  -Dmdep.includeScope=test -Dmdep.outputFile=target/compare-cp.txt
mkdir -p "$out"
{
  echo "jegg   $(git rev-parse --short HEAD) $(git log -1 --format=%s)"
  echo "egg    $(git -C "$cache" rev-parse --short HEAD) (release build, $(cargo --version))"
  echo "rounds $rounds, terms $terms, seed $seed, heap 2g fixed, $(date -u +%FT%TZ)"
  java -version 2>&1 | head -1
  echo "cpus $(nproc); $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | sed 's/^ //')"
  echo "load before $(cut -d' ' -f1-3 /proc/loadavg)"
} > "$out/provenance.txt"
cat "$out/provenance.txt"
java -Xms2g -Xmx2g -cp "target/test-classes:target/classes:$(cat target/compare-cp.txt)" \
  io.github.vecbricks.jegg.EggCompare --egg "$cache" --out "$out" --terms "$terms" \
  --seed "$seed" --rounds "$rounds"
echo "load after $(cut -d' ' -f1-3 /proc/loadavg)" >> "$out/provenance.txt"
