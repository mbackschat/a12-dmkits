# a12-dmkits

**Model-aware**, typed, combinator-style kits for the A12 Kernel document model. The mature kit, **rulekit**, targets validation- and computation-rule conditions — think *"jOOQ for the A12 validation DSL"*, including the reverse direction, since the kernel's own parser supplies it. It replaces fragile hand-written `errorCondition` strings with a typed builder that lets you:

- **Author** rules through a typed, model-bound builder.
- **Read** existing rule text back into the same typed representation.
- **Modify** them with reviewable diffs that disclose any lossiness up front.
- **Render & validate** against the real kernel — confirmed by the engine, not just plausible.

A companion kit, **modelkit**, authors document-model *structure* (fields, groups, types, includes) and emits DM-JSON the kernel accepts — the same typed, kernel-confirmed approach applied to the model itself.

Java first; a TypeScript twin is a deferred, design-informing milestone.

> **Not an official A12 artifact.** A personal exploration, not affiliated with or endorsed by mgm. Shipped code binds only to the kernel's **public** OSS surface — no internal systems. An opt-in, Java-only adapter module may read the kernel's internal AST tier under strict guardrails (proposal D15).

## Status

**Phase 1 (the Java vertical slice) is complete. Phase 2 is in progress.**

| Area | State |
|---|---|
| **Phase 1** — author → render (EN/DE) → validate against the real kernel: model index, self-verifying operator catalog, typed AST + fork-9 group-relative renderer, the F4a diagnostic surface, the DM-JSON write path, F11 kernel-version tolerance | ✅ complete |
| **Phase 2** — the bidirectional loop over the opt-in kernel-adapter: *ingest → explain → modify → diff → validate → write-back*, for **rules and computations** alike (computations are first-class: `ingestModel`/`where-used`/`explain --computation`) | ✅ landed |
| **Phase 2** — standalone **CLI** — the modeller/BA front-end (verbs + JSON I/O), **self-describing** (`--help` · `manifest` · `operators` · `schema`) so an agent explores it without docs | ✅ landed |
| **Phase 2** — modeller/BA **skill** + self-sufficiency eval | ✅ landed |
| **Phase 2** — F9a exact `whereUsed` ✅, `IProblem → Diagnostic` ✅ (per-rule + candidate-condition diagnostics via the adapter); the F10 long tail = a few specialized constructs, parked | ✅ landed |

**Two personas, one engine** (proposal N10): a *developer* edits rulekit-the-library inside their own project; a *modeller/BA* uses the CLI, with no Java toolchain to install.

This snapshot deliberately isn't a feature list, so it can't drift — the per-feature truth lives in the status board. Where to look:

| To find… | See |
|---|---|
| Per-feature implementation status (the canonical board) | [`docs/API-POC-STUDY.md` §9](docs/API-POC-STUDY.md) |
| Vision, requirements, decision log (§17), roadmap (§16) | [`docs/PROJECT-PROPOSAL.md`](docs/PROJECT-PROPOSAL.md) |
| Kernel/editor API inventory | [`docs/KERNEL-RULE-API-SURVEY.md`](docs/KERNEL-RULE-API-SURVEY.md) |
| Verified kernel facts | [`docs/KERNEL-INTEGRATION-NOTES.md`](docs/KERNEL-INTEGRATION-NOTES.md) |
| Every doc, indexed | [`docs/README.md`](docs/README.md) |

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

A green `test` means rulekit's output is confirmed by the engine, not just plausible. The suite covers:

- kernel wiring and the model index
- the renderer (EN/DE) and the validator
- the self-verifying catalog — every example checked against the real kernel
- the constraint-mining instruments (slot parity, MVK ledger)

With a sibling `a12-kernel-mm` clone present, an opt-in harness also loads all 15 of the kernel's own metamodels through the index (skipped when absent).

Other useful tasks:

```sh
./gradlew :adapter:test                              # the opt-in internal-tier module (Phase 2)
./gradlew :cli:run --args="dump model.json fields"   # run the standalone CLI in dev (no packaging)
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

- **rulekit source: MIT.** Its own original work, shipping no kernel binary, so it carries no copyleft entanglement — consistent with the sibling `a12-kernel-demo` / `a12-kernel-bootstrap` (both MIT).
- **The kernel: dual `EUPL-1.2 OR commercial`** (upstream, not ours).
- **The future standalone CLI distributable: EUPL-1.2**, because it *bundles* the kernel — the source stays MIT.

Full analysis: [`docs/LICENSING.md`](docs/LICENSING.md).
