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
load), and every number a document quotes traces to one of those files. Code comments explain
the code to a new reader; how it came to be this way is in the issues, the plan and git.

Java 25, Maven, `mvn -B verify` must be clean under `-Xlint:all -Werror`.
Two of egg's ported tests (`lambda_fib`, `lambda_function_repeat`), which egg runs only in release
builds, take most of a run's time and are tagged `slow`; `mvn -Dsurefire.excludedGroups=slow verify`
skips them locally, while CI runs everything.

A pull request fills in `.github/PULL_REQUEST_TEMPLATE`, the template Apache Spark and Varka use.
Following the ASF Generative Tooling Guidance
(https://www.apache.org/legal/generative-tooling.html), work authored or co-authored with
generative AI tooling says so with a `Generated-by:` line naming the tool and its version, in the
template's last section and as the last line of each commit message.

The process was set on 3 October 2026 (https://github.com/vecbricks/jegg/issues/3) after the first steps had gone code-first.
