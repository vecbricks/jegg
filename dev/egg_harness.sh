#!/usr/bin/env bash
# Prepares and builds egg's side of the differential fuzz for one language: egg 73975c9 in the
# cache, its tests/<lang>.rs with dev/fuzz/<lang>_run.rs and common.rs appended as
# tests/fuzz_<lang>.rs, built in release mode. dev/fuzz.sh and dev/compare.sh call it; the cache
# is $JEGG_EGG_CACHE or ~/.cache/jegg/egg, and the script prints it.
#   dev/egg_harness.sh prop|math
set -euo pipefail
cd "$(dirname "$0")/.."
EGG_COMMIT=73975c9
cache="${JEGG_EGG_CACHE:-$HOME/.cache/jegg/egg}"
lang="${1:-}"
case "$lang" in prop|math) ;; *) sed -n '2,6p' "$0"; exit 2 ;; esac
command -v cargo >/dev/null \
  || { echo "cargo is not on the PATH; install Rust from https://rustup.rs" >&2; exit 2; }
if [ ! -d "$cache/.git" ]; then git clone -q https://github.com/egraphs-good/egg.git "$cache"; fi
git -C "$cache" fetch -q origin "$EGG_COMMIT" 2>/dev/null || git -C "$cache" fetch -q origin
git -C "$cache" checkout -q "$EGG_COMMIT"
# The probe's counters (#134): egg's source as checked out, then the patch where there is one.
git -C "$cache" checkout -q -- src
if [ -f dev/fuzz/probe.patch ]; then git -C "$cache" apply "$PWD/dev/fuzz/probe.patch"; fi
# egg's own test file, without its crate-level cfg line, then the run and the driver.
{
  sed '1{/^#!\[cfg/d}' "$cache/tests/$lang.rs"
  cat "dev/fuzz/${lang}_run.rs" dev/fuzz/common.rs
} \
  > "$cache/tests/fuzz_$lang.rs"
(cd "$cache" && cargo test -q --release --test "fuzz_$lang" --no-run)
echo "$cache"
