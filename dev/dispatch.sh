#!/bin/bash
#
# Licensed to the vecbricks contributors under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy of the
# License at http://www.apache.org/licenses/LICENSE-2.0. Software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language governing permissions and
# limitations under the License.
#
# Dispatches the Timing workflow or the Fuzz workflow on a named host (#152), after vfdlibm's
# bench/dispatch-bench.sh: the run checks the runner's CPU first (dev/host.sh), and when that
# check fails, this waits and dispatches again, until a run passes the check, fails on something
# else, or the attempts run out.
#
#   dev/dispatch.sh --host <key> [--ref <branch>] [--attempts n] [--wait minutes] \
#       <timing|fuzz> [-f input=value ...]
#   dev/dispatch.sh --host epyc7763 timing -f head=main -f base=main      # an A/A run
#   dev/dispatch.sh --host epyc7763 --ref my-branch timing -f head=my-branch
#   dev/dispatch.sh --host epyc7763 fuzz -f ref=my-branch -f count=1000 -f compare=true
#
# --ref is the ref whose workflow file is dispatched (default main); the Timing's head and base
# and the Fuzz's ref are inputs. The default is 12 attempts 20 minutes apart, about four hours.
# Exit status: 0 when a run passed the check and succeeded; 1 when a run failed past the check
# or could not be dispatched; 3 when the attempts ran out on the check alone.
set -uo pipefail
host=""; ref=main; attempts=12; wait=20
usage() {
  echo "usage: dev/dispatch.sh --host <key> [--ref <branch>] [--attempts n] [--wait minutes]" \
    "<timing|fuzz> [-f input=value ...]" >&2
  exit 64; }
while [ $# -gt 0 ]; do
  case "$1" in
    --host) [ $# -ge 2 ] || usage; host=$2; shift 2 ;;
    --ref) [ $# -ge 2 ] || usage; ref=$2; shift 2 ;;
    --attempts) [ $# -ge 2 ] || usage; attempts=$2; shift 2 ;;
    --wait) [ $# -ge 2 ] || usage; wait=$2; shift 2 ;;
    -*) usage ;;
    *) break ;;
  esac
done
[ $# -ge 1 ] || usage
case "$1" in
  timing) workflow=timing.yml ;;
  fuzz) workflow=fuzz.yml ;;
  *) usage ;;
esac
shift
here="$(dirname "$0")"
bash "$here/host.sh" --keys | grep -qx -- "$host" || { echo "unknown host '$host'" >&2; usage; }
repo=$(gh repo view --json nameWithOwner --jq .nameWithOwner) || exit 1
t() { date -u +%H:%M; }
n=0
while :; do
  n=$((n + 1))
  since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  sleep 1
  gh workflow run "$workflow" -R "$repo" --ref "$ref" -f host="$host" "$@" || {
    echo "$(t) could not dispatch $workflow on $ref"; exit 1; }
  run=""
  for _ in $(seq 30); do
    sleep 10
    run=$(gh run list -R "$repo" --workflow "$workflow" --event workflow_dispatch \
      --created ">=$since" --limit 1 --json databaseId --jq '.[0].databaseId // empty')
    [ -n "$run" ] && break
  done
  [ -n "$run" ] || { echo "$(t) no run of $workflow appeared in five minutes"; exit 1; }
  echo "$(t) dispatched $workflow on $ref for host $host: run $run, attempt $n of $attempts"
  status=""
  for _ in $(seq 200); do
    status=$(gh run view "$run" -R "$repo" --json status --jq .status 2>/dev/null || true)
    [ "$status" = completed ] && break
    sleep 45
  done
  [ "$status" = completed ] || { echo "$(t) run $run did not complete in time"; exit 1; }
  conclusion=$(gh run view "$run" -R "$repo" --json conclusion,url --jq '.conclusion + " " + .url')
  echo "$(t) run $run: $conclusion"
  case "$conclusion" in success*) exit 0 ;; esac
  steps=$(gh api "repos/$repo/actions/runs/$run/jobs" \
    --jq '.jobs[].steps[] | "\(.conclusion) \(.name)"')
  if grep -qx "failure Check the runner's CPU" <<<"$steps"; then
    if [ "$n" -ge "$attempts" ]; then
      echo "$(t) the runner was not $host on attempt $n of $attempts; no attempts left"; exit 3
    fi
    echo "$(t) the runner was not $host; the next attempt in $wait minutes"
    sleep $((wait * 60))
    continue
  fi
  echo "$(t) run $run failed past the CPU check; its failed steps:"
  sed -n 's/^failure //p' <<<"$steps"
  exit 1
done
