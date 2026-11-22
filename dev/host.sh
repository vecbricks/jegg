#!/bin/bash
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
#
# The table of named benchmark hosts and the check of a machine against it (#152), after
# vfdlibm's bench/host.sh. A timing compares only with a timing on the same host, so the Timing
# workflow and the Fuzz workflow's compare and probe jobs check the runner's CPU first and fail
# on any other machine; dev/dispatch.sh then dispatches again.
#
#   dev/host.sh                     print this machine's host key; exit 1 when it is no named host
#   dev/host.sh --expect <key>      check this machine against one host; exit 1 on any other CPU
#   dev/host.sh --keys              print the host keys, one per line
#   dev/host.sh --cpu               print this machine's model name as the check reads it
#   dev/host.sh --classify '<name>' print the key of the host with that model name; exit 1 if none
#   dev/host.sh --self-test         check the table against the names the pool has printed
#
# A string is never relaxed: a run on another CPU is not comparable with the runs before it.
# Adding a host is the owner's decision and a new row, not a looser string.
set -euo pipefail
export LC_ALL=C

# One host per line: key | where the string must sit in the model name (end or any) | the
# string, as runs printed it. The strings are /proc/cpuinfo's model names from jegg's own
# Timing, compare and probe runs of November 2026 (benchmarks/README.md names the runs).
HOSTS=(
  "epyc7763|end|AMD EPYC 7763 64-Core Processor"
  "epyc9v74|end|AMD EPYC 9V74 80-Core Processor"
  "epyc9v45|end|AMD EPYC 9V45 96-Core Processor"
  "xeon6975p|any|Intel(R) Xeon(R) 6975P-C"
)

keys() { local row; for row in "${HOSTS[@]}"; do printf '%s\n' "${row%%|*}"; done; }

row_of() {
  local row
  for row in "${HOSTS[@]}"; do
    if [ "${row%%|*}" = "$1" ]; then printf '%s\n' "$row"; return 0; fi
  done
  return 1; }

string_of() {
  local key where string
  IFS='|' read -r key where string <<<"$1"
  printf '%s\n' "$string"; }

trim() { sed 's/[[:space:]]*$//' <<<"$1"; }

# Whether a model name is a row's, compared ignoring case (Azure has printed Intel names in
# capitals).
name_matches() {
  local key where string lc want
  IFS='|' read -r key where string <<<"$1"
  lc="${2,,}"; want="${string,,}"
  case "$where" in
    end) [[ "$lc" == *"$want" ]] ;;
    any) [[ "$lc" == *"$want"* ]] ;;
    *) return 1 ;;
  esac; }

classify() {
  local row cpu
  cpu="$(trim "$1")"
  for row in "${HOSTS[@]}"; do
    if name_matches "$row" "$cpu"; then printf '%s\n' "${row%%|*}"; return 0; fi
  done
  return 1; }

this_cpu() { grep -m1 'model name' "${1:-/proc/cpuinfo}" | sed 's/.*: //'; }

expect() {
  local row cpu
  if ! row="$(row_of "$1")"; then echo "::error::unknown host '$1'"; return 1; fi
  cpu="$(trim "$2")"
  echo "Host: $1, CPU: $cpu"
  if ! name_matches "$row" "$cpu"; then
    echo "::error::CPU mismatch on host $1: expected '$(string_of "$row")', got '$cpu'"
    return 1
  fi; }

self_test() {
  local row key seen=""
  for row in "${HOSTS[@]}"; do
    key="${row%%|*}"
    case " $seen " in *" $key "*) echo "duplicate key $key"; return 1 ;; esac
    seen="$seen $key"
    if [ "$(classify "$(string_of "$row")")" != "$key" ]; then
      echo "row $key does not classify itself"; return 1
    fi
  done
  # Names the pool has printed, as the runs printed them, each to its key or to none.
  [ "$(classify 'AMD EPYC 7763 64-Core Processor')" = epyc7763 ] || return 1
  [ "$(classify 'AMD EPYC 9V74 80-Core Processor')" = epyc9v74 ] || return 1
  [ "$(classify 'AMD EPYC 9V45 96-Core Processor')" = epyc9v45 ] || return 1
  [ "$(classify 'Intel(R) Xeon(R) 6975P-C')" = xeon6975p ] || return 1
  [ "$(classify 'INTEL(R) XEON(R) 6975P-C ')" = xeon6975p ] || return 1
  ! classify 'Intel(R) Xeon(R) Platinum 8370C CPU @ 2.80GHz' > /dev/null || return 1
  ! classify 'AMD EPYC 7763 64-Core Processor with more' > /dev/null || return 1
  echo "host table ok: $(keys | tr '\n' ' ')"; }

case "${1:-}" in
  "") classify "$(this_cpu)" ;;
  --expect) [ $# -eq 2 ] || { echo "usage: dev/host.sh --expect <key>" >&2; exit 64; }
            expect "$2" "$(this_cpu)" ;;
  --keys) keys ;;
  --cpu) trim "$(this_cpu)" ;;
  --classify) [ $# -eq 2 ] || { echo "usage: dev/host.sh --classify '<model name>'" >&2; exit 64; }
              classify "$2" ;;
  --self-test) self_test ;;
  *) sed -n '11,22p' "$0" >&2; exit 64 ;;
esac
