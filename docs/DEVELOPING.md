# Developing a12-dmkits

What a contributor needs to build, test and commit here. The [project README](../README.md) carries the short quick start; machine-specific build facts live in [BUILD-ENVIRONMENT.md](BUILD-ENVIRONMENT.md).

## Prerequisites

The A12 Kernel is resolved from your **local Maven repository** (`~/.m2`) — no private registry. Publish it from public sources once with the sibling [`a12-kernel-bootstrap`](https://github.com/mbackschat/a12-kernel-bootstrap) kit:

```sh
# in the a12-kernel-bootstrap checkout (a sibling of this repo)
./bootstrap.sh
```

This publishes kernel `31.1.1` (Java only) to `mavenLocal`. You also need:

- **JDK 21.** The build toolchain is pinned to 21, and a newer JDK isn't used even if installed ([why](BUILD-ENVIRONMENT.md): Groovy 3.0.25 and the validated baseline). GraalVM 25 is used only for the native build.
- **Node >=24.12.0**, for the repository's checked-in TypeScript tools and Node-backed demos.
- **[locmetry](https://github.com/mbackschat/locmetry)** at its latest release (`brew install mbackschat/tap/locmetry`; update with `brew update && brew upgrade locmetry`; never pin it). The source-size guard counts code lines with it.

Run [`scripts/doctor.sh`](../scripts/doctor.sh) to check the POSIX/TypeScript tooling boundary, Node, Java, locmetry, the Gradle home, hook wiring, and the local Kernel together.

### Maintainer agent setup (optional)

The build does not depend on personal agent configuration. To carry the maintainer's shared workflow defaults, including TDD and commit conventions, across machines, install the global instructions from [`mbackschat/coding-setup`](https://github.com/mbackschat/coding-setup): its guarded installer links `~/.claude/CLAUDE.md` for Claude Code and `~/.codex/AGENTS.md` for Codex to the same version-controlled source. This repository's tracked `AGENTS.md` → `CLAUDE.md` link remains the source of project-specific requirements for every contributor.

## Build & test

Like the sibling repos, build with a **clean/isolated Gradle home** so the machine's global config (which may point at a private registry) is bypassed — `build.gradle` declares only `mavenLocal()` + `mavenCentral()`:

```sh
ROOT="$(cd .. && pwd)"                               # the directory holding the A12 repos
export GRADLE_USER_HOME="$ROOT/.gradle-isolated"     # public-repos-only Gradle home
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # macOS; or point at any JDK 21
./gradlew test
```

**To avoid forgetting either variable** — the machine's global Gradle config can otherwise inject a private registry and fail confusingly ([root cause](BUILD-ENVIRONMENT.md)) — use the committed wrapper `./build.sh test` (it pins both, then runs Gradle), or [`.envrc`](../.envrc) if you have [direnv](https://direnv.net).

A plain `./gradlew test` works too on a machine with no global Gradle config, and on one whose init script honours the committed `.no-global-gradle-repos` marker. Two separate pieces cover different halves:

- **Plugin resolution:** `settings.gradle` declares the public plugin repositories **first**, so anything an init script appends afterwards becomes a fallback.
- **Dependency resolution:** covered by the marker alone, since an init script's `allprojects` hook runs before `build.gradle`.

On a machine whose init script ignores the marker, use the wrapper. It is what you want day to day regardless, because it also pins JDK 21 and the Gradle home shared across the sibling A12 checkouts. To build *through* a private registry on purpose, delete the marker. [`scripts/doctor.sh`](../scripts/doctor.sh) shows which of these applies to your machine.

**Build cache.** The repository enables Gradle's local build cache and gives the shared Kotlin compiler daemon enough heap for overlapping cold JVM, JS, and adapter test compilation in [`gradle.properties`](../gradle.properties).

- `build.sh` resolves Git's common directory, so even a linked worktree under `/private/tmp` uses the primary checkout's one isolated Gradle home. Relocatable compiler output is restored instead of repeated after a same-commit landing.
- CI persists the same isolated user home through the official Gradle setup action, so hosted-runner dependencies, transforms, and relocatable compilation outputs survive between runs. Performance measurements remain explicitly non-cacheable.
- [`scripts/verify-build-cache.sh`](../scripts/verify-build-cache.sh) proves the local contract with two clean worktrees while Gradle is offline, ruling out a remote cache or a dependency download.

A truly cold full suite still performs all compilation and tests. For bounded automation, run `:interpreter:check`, `:adapter:test`, and `:cli:test` as separate commands instead of one monolithic invocation. Which lane owns what, and when a full lane is warranted, is [AGENT-RUN-DISCIPLINE.md](AGENT-RUN-DISCIPLINE.md).

A green `test` means the kit's output is confirmed by the engine, not just plausible. The suite covers:

- kernel wiring and the model index
- the renderer (EN/DE) and the validator
- the self-verifying catalog — every example checked against the real kernel
- the constraint-mining instruments (slot parity, the MVK / RT-semantics / grammar-production ledgers)

With a sibling `a12-kernel-mm` clone present, an opt-in harness also loads all 15 of the kernel's own metamodels through the index (skipped when absent).

Other useful tasks:

```sh
./gradlew :adapter:test                              # the opt-in internal-tier module
./gradlew :cli:run --args="-m model.json model check"   # run the standalone CLI in dev (no packaging)
```

`./gradlew :test` runs the cross-kit library suite (root `:` is the integration-test host over the four kernel-free kit modules `:core`/`:rulekit`/`:modelkit`/`:edit`); the kits' main stays kernel-free and the kernel enters only as a test-scope oracle. See [`docs/CLI-SPEC.md`](CLI-SPEC.md) for the CLI.

## Commit and push hooks

The committed hooks enforce the commit-message convention, four pre-commit checks, and a pre-push CI check. Install the workflow validator and enable all hooks once per clone:

```sh
brew install actionlint
git config core.hooksPath .githooks
```

What each hook does:

- **`commit-msg`** hard-rejects mechanical convention violations and reminds on the judgment rule.
- **`pre-commit`** runs four checks. It refuses to stage build output or log files. It refuses a commit whose staged files can drift a CI-gated doc unless the doc guards have run green since. It refuses any commit unless the repository-wide `leakGuardTest` lane has run green since. And it refuses any commit when a test run of any lane, written since the newest staged change, recorded a failure, so a red run cannot be committed past because the command that ran it did not gate the commit. The two evidence checks exist because `-Pfast` skips those lanes by design, so the inner loop cannot otherwise see them fail.
- **`pre-push`** runs the typed infographic freshness and CI-cost-ordering check through its stable shell launcher, then validates every GitHub Actions workflow with `actionlint`. It refuses when Node or `actionlint` is missing instead of claiming a check it could not perform.

Native Git hooks make this neutral to the caller: terminal Git, an IDE, Claude Code, and Codex CLI all trigger the same gate.

**The local hooks are the early half of a defence, never the whole of one.** They are unwired in a fresh clone until you run the command above, and `--no-verify` bypasses them on purpose. A local hook must not be the only thing standing between a mistake and the repository, so each has an authoritative counterpart in the test and CI estate. `scripts/doctor.sh` reports both hook wiring and `actionlint` availability.

Source statistics are intentionally not a push or CI concern: the maintainer-only locmetry updater is run locally, and each release publisher checks the README it publishes.

To apply the commit-message check across all your repos, point your global Git template at it: copy that hook into `~/.git-template/hooks` and set `git config --global init.templateDir ~/.git-template`.
