#!/usr/bin/env bash
# Prepare a release locally: set the version, verify, commit, tag, return to a snapshot.
#   dev/release.sh 0.1.0                  release 0.1.0, then start 0.1.1-SNAPSHOT
#   dev/release.sh 0.2.0 0.3.0-SNAPSHOT   release 0.2.0, then start the named snapshot
#   dev/release.sh --check 0.1.0          the checks only, nothing changed
# Nothing leaves the machine: the script commits and tags, then prints the two commands the
# owner runs (push, and deploy to Central from the tag). CONTRIBUTING.md, "Releases".
set -euo pipefail
cd "$(dirname "$0")/.."

case "${1:-}" in -h|--help|"") sed -n '2,7p' "$0"; exit 0 ;; esac
check_only=false
if [ "$1" = "--check" ]; then check_only=true; shift; fi
version="${1:?a version, such as 0.1.0}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
  || { echo "version must be MAJOR.MINOR.PATCH, not '$version'" >&2; exit 2; }
if [ -n "${2:-}" ]; then
  next="$2"
else
  next="${version%.*}.$(( ${version##*.} + 1 ))-SNAPSHOT"
fi
[[ "$next" == *-SNAPSHOT ]] || { echo "the next version must end in -SNAPSHOT: '$next'" >&2; exit 2; }
command -v mvn >/dev/null \
  || { echo "mvn is not on the PATH (Java 25 and Maven are needed)" >&2; exit 2; }
tag="v$version"

fail() { echo "refusing: $*" >&2; exit 1; }

[ "$(git branch --show-current)" = main ] || fail "not on main"
[ -z "$(git status --porcelain)" ] || fail "the working tree is not clean"
git fetch -q origin main
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] \
  || fail "main is not at origin/main (pull, or push what is not pushed)"
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && fail "the tag $tag exists"
git ls-remote --exit-code --tags origin "$tag" >/dev/null 2>&1 && fail "$tag exists on origin"
grep -q "^## \[$version\]" CHANGELOG.md \
  || fail "CHANGELOG.md has no '## [$version]' section: move the Unreleased lines under it"
grep -q "^\[$version\]: " CHANGELOG.md || fail "CHANGELOG.md has no [$version] link line"
current="$(mvn -B -q help:evaluate -Dexpression=project.version -DforceStdout)"
[ "$current" = "$version-SNAPSHOT" ] \
  || fail "the pom is at $current; a release of $version starts from $version-SNAPSHOT"

if $check_only; then
  echo "the checks pass for $version (next: $next); nothing was changed"
  exit 0
fi

set_version() { mvn -B -q versions:set -DnewVersion="$1" -DgenerateBackupPoms=false; }

# From here on a failure restores the tree, so a half-made release never stays behind.
start="$(git rev-parse HEAD)"
trap 'git tag -d "$tag" >/dev/null 2>&1 || true; git reset -q --hard "$start"' ERR

set_version "$version"
sed -i -E "s|(<project.build.outputTimestamp>)[^<]*|\\1$(date -u +%FT%TZ)|" pom.xml
sed -i -E "s/^## \[$version\].*/## [$version] - $(date +%F)/" CHANGELOG.md
mvn -B verify                        # everything, the slow tests included, as CI runs it
git commit -q -am "Release $version"
git tag -a "$tag" -m "jegg $version"

set_version "$next"
git commit -q -am "Start $next"
trap - ERR

cat <<EOF

Release $version is committed and tagged $tag locally; main is at $next.
Review it:   git log --oneline -3 && git show $tag --stat
Then, as the owner (Central token and signing key needed, see CONTRIBUTING.md):
  git checkout $tag && mvn -B -Prelease deploy && git checkout main
  git push origin main $tag
To drop the release before pushing:  git tag -d $tag && git reset --hard $start
EOF
