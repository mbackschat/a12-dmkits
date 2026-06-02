# a12-rulekit

A **model-aware**, typed, combinator-style **builder** (Java first, TypeScript later) that produces **valid A12 Kernel validation- and computation-rule conditions for a specific `DocumentModel`** — the `errorCondition` / computation text the kernel otherwise expects you to hand-write as fragile strings. Think *"jOOQ for the A12 validation DSL"*.

> **Not an official A12 artifact.** A personal exploration; not affiliated with or endorsed by mgm. It builds only against the kernel's **public** OSS surface (no internal systems).

## Status

Phase 1 in progress (Java vertical slice). The design is captured in **[`docs/PROJECT-PROPOSAL.md`](docs/PROJECT-PROPOSAL.md)** — vision, requirements, architecture, the operator-catalog spine, the decision log (§17, locked), and the phased roadmap (§16); verified kernel facts in **[`docs/KERNEL-INTEGRATION-NOTES.md`](docs/KERNEL-INTEGRATION-NOTES.md)**.

Working end-to-end today:
- **Model index** (F0) — load DM-JSON → `expand` → `checkConsistency` → index group tree, field kinds, number scale, repetition.
- **Operator catalog** (§9) — bundled JSON spine with verified bilingual keywords; **self-verifying**: every example is checked against the real kernel (N7).
- **Typed condition AST + renderer** — model-bound handles compose immutable `Condition`s; the catalog-driven renderer emits canonical EN/DE text.
- **Ground-truth validator** (F4) — injects a rendered rule as DM-JSON and runs `checkConsistency`, mapping `MVK_*` to problems.

Not yet built: relative/short path policy (§18.9), computations (F2), broader operator/type coverage, explainability (F9), modify (F8), the TS port.

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

The suite covers kernel wiring, the model index, the renderer (EN/DE), the validator, and the self-verifying catalog (each example checked against the real kernel) — so a green `test` means rulekit's output is confirmed by the engine, not just plausible.

## License

Deliberately left open for now; a permissive OSS license (Apache-2.0 or MIT) is the working intent — to be settled before first publish. See proposal §19.
