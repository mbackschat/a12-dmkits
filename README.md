# a12-dmkits

Typed, **model-aware** authoring & edit kits for the A12 Kernel document model — *"jOOQ for the A12 validation DSL,"* the reverse direction included. JSON in, JSON out; every result is **confirmed by the real engine, not just plausible**.

> **Not an official A12 artifact.** A personal exploration, not affiliated with or endorsed by mgm. Shipped code binds only to the kernel's **public** OSS surface — no internal systems. (An opt-in, Java-only adapter module may read the kernel's internal AST tier under strict guardrails.)

## What's in the box

Two sibling kits over a shared, kernel-bound core:

- **rulekit** *(mature)* — validation- and computation-rule conditions. It replaces fragile hand-written `errorCondition` strings with a typed, model-bound builder, so you can:
  - **author** rules and computations through the typed builder,
  - **read** existing rule text back into the same typed representation,
  - **modify** them with reviewable diffs that disclose any lossiness up front,
  - **render** (English / German) and **validate** against the real kernel.
- **modelkit** — authors document-model *structure* (fields, groups, types, includes, config) and emits DM-JSON the kernel accepts.

**Two personas, one engine:** a *developer* edits rulekit-the-library inside their own project; a *modeller/BA* drives **`dmtool`**, a self-describing CLI with no Java toolchain to install. *(Java first; a TypeScript twin is a deferred, design-informing milestone.)*

## Try it — the `dmtool` CLI

`dmtool` is **target-first** (`dmtool -m <model> <target> <op>`) and **self-describing**, so a cold LLM agent can drive it from the binary alone — no docs required:

```sh
dmtool manifest                 # every verb × parameter, machine-readable, in one call
dmtool operators GroupFilled    # one operator's meaning, worked examples, and gotchas

# Author a rule and have the REAL kernel confirm it:
dmtool -m order.dm.json rule check \
  --field /Order/DeliveryDate \
  --condition 'AllFieldsFilled(OrderDate, DeliveryDate) And [DeliveryDate] < [OrderDate]' \
  --code DELIVERY_BEFORE_ORDER          # → {"valid": true, …}

# Which rules actually FIRE on a document instance? (what a static check can't tell you)
dmtool -m order.dm.json doc eval --doc order-instance.json
```

Runnable, verifiable walkthroughs of the whole surface live in [`examples/`](examples/) — each is also a doc-drift regression test.

## Documentation

Start at the **[docs index](docs/README.md)**; the rest of the map:

| To find… | See |
|---|---|
| Vision, requirements, the decision log | [`docs/PROJECT-PROPOSAL.md`](docs/PROJECT-PROPOSAL.md) |
| Per-feature implementation status | [`docs/API-POC-STUDY.md` §9](docs/API-POC-STUDY.md) |
| The CLI contract | [`docs/CLI-SPEC.md`](docs/CLI-SPEC.md) |
| Verified kernel facts | [`docs/KERNEL-FINDINGS.md`](docs/KERNEL-FINDINGS.md) · [`docs/KERNEL-INTEGRATION-NOTES.md`](docs/KERNEL-INTEGRATION-NOTES.md) |

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

A green `test` means the kit's output is confirmed by the engine, not just plausible. The suite covers:

- kernel wiring and the model index
- the renderer (EN/DE) and the validator
- the self-verifying catalog — every example checked against the real kernel
- the constraint-mining instruments (slot parity, the MVK / RT-semantics / grammar-production ledgers)

With a sibling `a12-kernel-mm` clone present, an opt-in harness also loads all 15 of the kernel's own metamodels through the index (skipped when absent).

Other useful tasks:

```sh
./gradlew :adapter:test                              # the opt-in internal-tier module
./gradlew :cli:run --args="-m model.json doc validate"   # run the standalone CLI in dev (no packaging)
```

`./gradlew :test` keeps the core build free of the internal tier; see [`docs/CLI-SPEC.md`](docs/CLI-SPEC.md) for the CLI.

## Commit hooks

A committed `commit-msg` hook (`.githooks/commit-msg`) enforces the repo's commit convention — Conventional Commits subject, subject-only by default, no bullet lists or test counts. Enable it once per clone:

```sh
git config core.hooksPath .githooks
```

It hard-rejects the mechanical violations and reminds on the judgment rule; bypass a single commit with `git commit --no-verify`. To apply the same check across all your repos, point your global git template at it — copy the hook into `~/.git-template/hooks` and set `git config --global init.templateDir ~/.git-template`.

## License

**MIT** — see [`LICENSE`](LICENSE).

- **Source: MIT.** The kits' own original work, shipping no kernel binary, so they carry no copyleft entanglement — consistent with the sibling `a12-kernel-demo` / `a12-kernel-bootstrap` (both MIT).
- **The kernel: dual `EUPL-1.2 OR commercial`** (upstream, not ours).
- **The future standalone CLI distributable: EUPL-1.2**, because it *bundles* the kernel — the source stays MIT.

Full analysis: [`docs/LICENSING.md`](docs/LICENSING.md).
