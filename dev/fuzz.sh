#!/usr/bin/env bash
# Differential fuzzing against egg (#44): random terms of egg's prop or math language run through
# egg (73975c9, built here from egg's own tests/prop.rs or tests/math.rs, so the language, the
# analysis and the rules are egg's) and through jegg's ports; the stop reason, the iterations and
# the nodes and classes at the start of each iteration and at the end are compared.
#   dev/fuzz.sh math 1000 1           1000 random math terms, seed 1
#   dev/fuzz.sh prop 1000 1           the same for prop (egg's whole rule set)
#   dev/fuzz.sh --known math          the known-answer gate: egg's own harness must reproduce the
#                                     counts the ported tests pin (EggDifferentialTest)
# A divergence is shrunk and written to dev/fuzz/found/. Needs cargo (https://rustup.rs) and mvn;
# egg's first release build takes a few minutes. Not a benchmark: nothing here is timed.
set -euo pipefail
cd "$(dirname "$0")/.."
EGG_COMMIT=73975c9
cache="${JEGG_EGG_CACHE:-$HOME/.cache/jegg/egg}"
mode=fuzz
if [ "${1:-}" = "--known" ]; then mode=known; shift; fi
lang="${1:-}"; count="${2:-1000}"; seed="${3:-1}"
case "$lang" in prop|math) ;; *) sed -n '2,13p' "$0"; exit 2 ;; esac
command -v cargo >/dev/null || { echo "cargo is not on the PATH; install Rust from https://rustup.rs" >&2; exit 2; }
command -v mvn >/dev/null || { echo "mvn is not on the PATH" >&2; exit 2; }
if [ ! -d "$cache/.git" ]; then git clone -q https://github.com/egraphs-good/egg.git "$cache"; fi
git -C "$cache" fetch -q origin "$EGG_COMMIT" 2>/dev/null || git -C "$cache" fetch -q origin
git -C "$cache" checkout -q "$EGG_COMMIT"
# egg's own test file, without its crate-level cfg line, then the run and the driver.
{ sed '1{/^#!\[cfg/d}' "$cache/tests/$lang.rs"; cat "dev/fuzz/${lang}_run.rs" dev/fuzz/common.rs; } \
  > "$cache/tests/fuzz_$lang.rs"
(cd "$cache" && cargo test -q --release --test "fuzz_$lang" --no-run)
mkdir -p dev/fuzz/found target/fuzz
mvn -B -q test -Dtest=EggDifferentialTest -Dfuzz.lang="$lang" -Dfuzz.mode="$mode" \
  -Dfuzz.count="$count" -Dfuzz.seed="$seed" -Dfuzz.eggdir="$cache"
