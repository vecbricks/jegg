#!/usr/bin/env bash
# Differential fuzzing against egg (#44): random terms of egg's prop or math language run through
# egg (73975c9, built here from egg's own tests/prop.rs or tests/math.rs, so the language, the
# analysis and the rules are egg's) and through jegg's ports; the stop reason, the iterations and
# the nodes and classes at the start of each iteration and at the end are compared.
#   dev/fuzz.sh math 1000 1           1000 random math terms, seed 1
#   dev/fuzz.sh prop 1000 1           the same for prop (egg's whole rule set)
#   dev/fuzz.sh --known math          the known-answer gate: egg's own harness must reproduce the
#                                     counts the ported tests pin (EggDifferentialTest)
#   dev/fuzz.sh --scenarios math      the scheduler scenarios (FuzzRun.schedulerScenarios): egg
#                                     must still print the lines they are pinned to
# A divergence is shrunk and written to dev/fuzz/found/. Needs cargo (https://rustup.rs) and mvn;
# egg's first release build takes a few minutes. Not a benchmark: nothing here is timed.
set -euo pipefail
cd "$(dirname "$0")/.."
EGG_COMMIT=73975c9
cache="${JEGG_EGG_CACHE:-$HOME/.cache/jegg/egg}"
mode=fuzz
if [ "${1:-}" = "--known" ]; then mode=known; shift; fi
if [ "${1:-}" = "--scenarios" ]; then mode=scenarios; shift; fi
lang="${1:-}"; count="${2:-1000}"; seed="${3:-1}"
case "$lang" in prop|math) ;; *) sed -n '2,15p' "$0"; exit 2 ;; esac
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
# The summary line is the test's output, which maven hides for a test that passes; show it, the
# failures with their messages, and keep maven's exit status and its whole log.
log=target/fuzz/mvn-$lang-$mode.log
status=0
mvn -B test -Dtest=EggDifferentialTest -Dfuzz.lang="$lang" -Dfuzz.mode="$mode" \
  -Dfuzz.count="$count" -Dfuzz.seed="$seed" -Dfuzz.eggdir="$cache" > "$log" 2>&1 || status=$?
grep -E "^FUZZ|^\[ERROR\]|Tests run:|AssertionFailedError|Exception|BUILD|does not reproduce|egg's harness gave" \
  "$log" | head -60 || true
if [ "$status" -ne 0 ]; then echo "maven failed ($status); the whole log is $log" >&2; fi
exit "$status"
