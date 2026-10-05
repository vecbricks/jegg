#!/usr/bin/env bash
# egg's own run of one of its tests, for the counts a ported test pins (see
# docs/skills/checking-a-port-against-egg.md): clones egg at the commit jegg ports (73975c9) into
# a cache once, then runs the test and prints its report.
#   dev/egg_counts.sh math math_powers           Stop reason, Iterations, Egraph size
#   dev/egg_counts.sh lambda lambda_if --log     with egg's per-iteration sizes and bans
# Needs cargo (https://rustup.rs). egg's "nodes" is jegg's numNodes(); its "memo" is not
# comparable.
set -euo pipefail
EGG_COMMIT=73975c9
cache="${JEGG_EGG_CACHE:-$HOME/.cache/jegg/egg}"
suite="${1:-}"; test="${2:-}"; log="${3:-}"
[ -n "$suite" ] && [ -n "$test" ] || { sed -n "2,8p" "$0"; exit 2; }
command -v cargo >/dev/null \
  || { echo "cargo is not on the PATH; install Rust from https://rustup.rs" >&2; exit 2; }
if [ ! -d "$cache/.git" ]; then
  git clone -q https://github.com/egraphs-good/egg.git "$cache"
fi
git -C "$cache" fetch -q origin "$EGG_COMMIT" 2>/dev/null || git -C "$cache" fetch -q origin
git -C "$cache" checkout -q "$EGG_COMMIT"
cd "$cache"
if [ "$log" = "--log" ]; then
  RUST_LOG=egg=info cargo test -q --release --test "$suite" -- --exact "$test" --nocapture 2>&1 \
    | grep -E "Size: n=|Banning|fast-forwarded|Stop reason|Iterations:|Egraph size" \
    | sed -E 's/^\[[^]]*\] //; s/^ +//'
else
  cargo test -q --release --test "$suite" -- --exact "$test" --nocapture 2>&1 \
    | grep -E "Stop reason|Iterations:|Egraph size|test result" | sed -E 's/^ +//'
fi
