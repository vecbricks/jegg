#!/usr/bin/env bash
# The rebuild's work on both sides (#134): egg's counters (dev/fuzz/probe.patch) and jegg's
# (EGraph.Probe) over the fuzz term sets of a seed and over lambda_function_repeat, then a JFR
# profile of jegg's rebuild on the largest SAME quintile. Not a timing: the counts are exact
# whatever the load. This is what the Fuzz workflow's probe job runs.
#   dev/probe.sh [terms] [seed] [out-dir]      defaults 1000, 2, probe/
# Writes <out-dir>/{prop,math}-counts.jsonl, profile-*.md, provenance.txt and summary.md.
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:-}" in -h|--help) sed -n '2,7p' "$0"; exit 2 ;; esac
terms="${1:-1000}"; seed="${2:-2}"; out="${3:-probe}"
command -v mvn >/dev/null || { echo "mvn is not on the PATH" >&2; exit 2; }
cache=$(dev/egg_harness.sh prop | tail -1)
dev/egg_harness.sh math >/dev/null
(cd "$cache" && cargo test -q --release --test lambda --no-run)
mvn -B -q --no-transfer-progress test-compile dependency:build-classpath \
  -Dmdep.includeScope=test -Dmdep.outputFile=target/compare-cp.txt
mkdir -p "$out"
{
  echo "jegg   $(git rev-parse --short HEAD) $(git log -1 --format=%s)"
  echo "egg    $(git -C "$cache" rev-parse --short HEAD) + dev/fuzz/probe.patch (release build)"
  echo "terms $terms, seed $seed, heap 2g fixed, $(date -u +%FT%TZ)"
  java -version 2>&1 | head -1
  echo "cpus $(nproc); $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | sed 's/^ //')"
} > "$out/provenance.txt"
cat "$out/provenance.txt"
java -Xms2g -Xmx2g -cp "target/test-classes:target/classes:$(cat target/compare-cp.txt)" \
  io.github.vecbricks.jegg.RebuildProbe --egg "$cache" --out "$out" --terms "$terms" \
  --seed "$seed" --profile-seconds 90
