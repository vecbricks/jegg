#!/usr/bin/env bash
# A/B timing of two checkouts over the four workloads of #74 (prop and math at N random terms,
# the projection at 10,000 nodes, lambda_function_repeat), interleaved in fresh JVMs on this
# machine, each side's own round-to-round range measured so a difference is read against it.
# This is what .github/workflows/timing.yml runs; locally it runs only on a machine the owner
# has offered (AGENTS.md: no timing on the owner's machine otherwise).
#   dev/timing.sh <base-dir> <head-dir> [rounds] [terms] [out-dir]
# The driver (Timing) and the workloads come from this script's own checkout, compiled once; the
# two sides contribute only their library classes (src/main), so both do the same work by the
# same code, and a head older than the driver can still be timed. Writes
# <out-dir>/{base,head}-<round>.jsonl, provenance.txt and summary.md (default out-dir: timing/).
set -euo pipefail
case "${1:-}" in ""|-h|--help) sed -n '2,10p' "$0"; exit 2 ;; esac
tools=$(cd "$(dirname "$0")/.." && pwd)
base=$(cd "$1" && pwd); head=$(cd "$2" && pwd)
rounds="${3:-5}"; terms="${4:-1000}"; out="${5:-timing}"
command -v mvn >/dev/null || { echo "mvn is not on the PATH" >&2; exit 2; }
mkdir -p "$out"
echo "compiling the driver in $tools, then the library of base ($base) and of head ($head)"
(cd "$tools" && mvn -B -q --no-transfer-progress test-compile \
  dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/timing-cp.txt)
for side in "$base" "$head"; do
  [ "$side" = "$tools" ] || (cd "$side" && mvn -B -q --no-transfer-progress compile)
done
deps=$(cat "$tools/target/timing-cp.txt")
{
  echo "base   $(git -C "$base" rev-parse --short HEAD) $(git -C "$base" log -1 --format=%s)"
  echo "head   $(git -C "$head" rev-parse --short HEAD) $(git -C "$head" log -1 --format=%s)"
  echo "driver $(git -C "$tools" rev-parse --short HEAD) (this checkout's test tree)"
  echo "rounds $rounds, terms $terms, heap 2g fixed, $(date -u +%FT%TZ)"
  java -version 2>&1 | head -1
  echo "cpus $(nproc); $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | sed 's/^ //')"
  echo "load before $(cut -d' ' -f1-3 /proc/loadavg)"
} > "$out/provenance.txt"
cat "$out/provenance.txt"
run() {  # side round
  local classes="$1/target/classes"
  java -Xms2g -Xmx2g -cp "$classes:$tools/target/test-classes:$deps" \
    io.github.vecbricks.jegg.Timing prop,math,projection,lambda --terms "$terms" \
    > "$out/$2-$3.jsonl"
  sed "s/^/$2 $3: /" "$out/$2-$3.jsonl"
}
for ((r = 1; r <= rounds; r++)); do
  run "$base" base "$r"
  run "$head" head "$r"
done
echo "load after $(cut -d' ' -f1-3 /proc/loadavg)" >> "$out/provenance.txt"
java -cp "$tools/target/test-classes:$tools/target/classes:$deps" \
  io.github.vecbricks.jegg.Timing --summarize "$out" | tee "$out/summary.md"
