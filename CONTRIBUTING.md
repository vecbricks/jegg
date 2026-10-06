# Contributing to jegg

The order of work, for a feature and for a fix alike:

1. **An issue first.** It states the problem or the goal: what is known, what is open, and what
   would count as done. Nothing is coded against a goal that is not written down.
2. **Then a plan, in the issue.** The files it touches, the design and the alternatives it set
   aside, the tests that accept it, and - where it measures - the predictions, registered before
   the run. The owner reads the plan before any code exists; that is the point at which the
   design is steered.
3. **Then the code, as a pull request.** On a branch, never on `main`; it names the issue, its
   description says what was built and scores the plan's predictions, and CI is green before it
   is merged. A finding the work makes that is outside its issue becomes a new issue, not a
   paragraph in the PR.

`PLAN.md` is the library's plan as a whole - where it came from, the design, the sequencing -
and stays the record; an issue's plan is the plan of one change against it, and updates
`PLAN.md` where the change moves it.

Measurements are committed under `benchmarks/` with a provenance header (commit, JDK, machine,
load), and every number a document quotes traces to one of those files; `benchmarks/README.md`
says how they are regenerated, pinned to the fast cores on an idle machine, as Varka's are. Code comments explain
the code to a new reader; how it came to be this way is in the issues, the plan and git.

Java 25, Maven, `mvn -B verify` must be clean under `-Xlint:all -Werror`.
It also writes JaCoCo's coverage report of `src/main` to `target/site/jacoco/` and prints the line
and branch totals, with the least covered classes, at the end; the report is not committed.
`verify` fails when the line coverage of `src/main` falls under 97% or the branch coverage under
92% (`coverage.minimum.line` and `coverage.minimum.branch` in `pom.xml`, a little under the
measured 97.7% and 93.4%); a change that moves a threshold says so in its description. It also
fails on a Javadoc warning of the public surface (`-Xdoclint:all`: a broken `{@link}`, bad HTML, a
missing `@param`, `@return` or comment), as CI and the Pages build do. Dependabot opens one grouped
pull request a week for the Maven plugins and one for the Actions (`.github/dependabot.yml`).
Two of egg's ported tests (`lambda_fib`, `lambda_function_repeat`), which egg runs only in release
builds, take most of a run's time and are tagged `slow`; `mvn -Dsurefire.excludedGroups=slow verify`
skips them locally, while CI runs everything.

A pull request fills in `.github/PULL_REQUEST_TEMPLATE`, the template Apache Spark and Varka use.
Following the ASF Generative Tooling Guidance
(https://www.apache.org/legal/generative-tooling.html), work authored or co-authored with
generative AI tooling says so with a `Generated-by:` line naming the tool and its version, in the
template's last section and as the last line of each commit message.

The process was set on 3 October 2026 (https://github.com/vecbricks/jegg/issues/3) after the first steps had gone code-first.

## Releases

jegg is at 0.x while its API moves, as the README says. Versions follow semantic versioning with
the 0.x convention: a change that breaks the public API raises the minor version (0.1 to 0.2), a
fix or an addition raises the patch (0.1.0 to 0.1.1); 1.0.0 is the first version that promises
the API stays. A release is the tag `v<version>` on `main`: the tagged commit carries the release
version in the pom, and the next commit returns to the next `-SNAPSHOT`. Varka and any other
client depend on a release, never on a snapshot.

`CHANGELOG.md` lists what a release changed, in the form of Keep a Changelog. Each pull request
that changes what a user sees (the API, a behaviour, a speed worth quoting) adds one line with its
number under `Unreleased`, in Added, Changed or Fixed; a change to tests, docs or the build alone
does not.

Cutting a release is the owner's, since the Central token and the signing key are theirs:

1. Move the `Unreleased` lines under a `## [<version>]` heading of `CHANGELOG.md` and add its
   link line, in a pull request.
2. On an up-to-date `main`, `dev/release.sh <version>` checks the tree, sets the version, runs
   `mvn -B verify` with every test, commits "Release <version>", tags `v<version>`, and commits
   the next `-SNAPSHOT`. It pushes nothing; `dev/release.sh --check <version>` only checks.
3. `git checkout v<version> && mvn -B -Prelease deploy` builds the sources and Javadoc jars,
   signs them and uploads them to Central's portal; publish the deployment there. Then
   `git checkout main && git push origin main v<version>`.

What the owner needs once: a Central portal account with the namespace `io.github.vecbricks`
verified, a GPG key published to a key server, and `~/.m2/settings.xml` with a `central` server
holding the portal's token.

## The practical half

- **One test:** `mvn -q test -Dtest=LambdaTest`, or one method, `-Dtest='LambdaTest#lambdaIf'`;
  surefire's syntax. The whole suite is `mvn -B verify`, which also prints the coverage.
- **The slow tests:** `lambda_fib` and `lambda_function_repeat` take most of a run and are tagged
  `slow`; `-Dsurefire.excludedGroups=slow` skips them locally. CI runs everything.
- **The counts pinned to egg.** Each ported egg test (`LambdaTest`, `MathTest`) asserts that its
  run ends at egg's own iteration, node and class counts, read from egg's run of the same test
  (`cargo test --release --test <suite> -- --exact <name> --nocapture` prints a report; the
  `Egg` records hold the numbers). A count that differs means the port diverged from egg, and
  the per-iteration sizes (`RUST_LOG=egg=info` on egg's side, the `RunReport` on jegg's) say
  where; #19 and #30 record two such hunts. Do not relax a count to make a test pass.
- **The invariants.** `EGraph.checkInvariants()` and `checkAnalysisInvariant()` throw on a
  broken hashcons, congruence or analysis invariant; tests call them after every operation
  worth checking, and a new operation on the graph gets a test that does.
- **The benchmarks** run only on an idle machine, pinned, and their files name the commit they
  were measured from; `benchmarks/README.md` has the rules and the command. A pull request that
  changes performance says what it predicts and leaves the regeneration to an idle window; the
  prediction is scored when the file lands.
- **A first pull request:** the issues labelled `good first issue` have their plans written, so
  the process - issue, plan, pull request with the template - is followed by example.
