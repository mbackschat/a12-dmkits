# a12-rulekit

A **model-aware**, typed, combinator-style **builder** (Java first, TypeScript later) that produces **valid A12 Kernel validation- and computation-rule conditions for a specific `DocumentModel`** — the `errorCondition` / computation text the kernel otherwise expects you to hand-write as fragile strings. Think *"jOOQ for the A12 validation DSL"*.

> **Not an official A12 artifact.** A personal exploration; not affiliated with or endorsed by mgm. It builds only against the kernel's **public** OSS surface (no internal systems).

## Status

Early scaffold. The design is captured in **[`docs/PROJECT-PROPOSAL.md`](docs/PROJECT-PROPOSAL.md)** — vision, requirements, architecture, the operator-catalog spine, the decision log (§17, locked), and the phased roadmap (§16). We are at the start of **Phase 1** (Java vertical slice); this repo currently contains the build skeleton and a kernel-wiring smoke test.

## Prerequisites

The A12 Kernel is resolved from your **local Maven repository** (`~/.m2`) — no private registry. Publish it from public sources once with the sibling [`a12-kernel-bootstrap`](https://github.com/mbackschat/a12-kernel-bootstrap) kit:

```sh
# in the a12-kernel-bootstrap checkout (a sibling of this repo)
./bootstrap.sh
```

This publishes kernel `30.8.1` (Java only) to `mavenLocal`. You also need **JDK 21**.

## Build & test

Like the sibling repos, build with a **clean/isolated Gradle home** so the machine's global config (which may point at a private registry) is bypassed — `build.gradle` declares only `mavenLocal()` + `mavenCentral()`:

```sh
ROOT="$(cd .. && pwd)"                               # the directory holding the A12 repos
export GRADLE_USER_HOME="$ROOT/.gradle-isolated"     # public-repos-only Gradle home
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # macOS; or point at any JDK 21
./gradlew test
```

The smoke test (`KernelWiringTest`) confirms the public kernel services rulekit's validate seam will use (`IDocumentModelService` — `expand` / `checkConsistency`) are reachable through `kernel-md-facade` alone.

## License

Deliberately left open for now; a permissive OSS license (Apache-2.0 or MIT) is the working intent — to be settled before first publish. See proposal §19.
