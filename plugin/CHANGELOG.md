# Changelog

All notable changes to the **publicly released `dmtool` artifacts** — the native CLI binary and the agent plugins (Claude Code, OpenAI Codex). This file is maintained in the source repo and shipped to the public [`a12-dmtool-releases`](https://github.com/mbackschat/a12-dmtool-releases) mirror; it tracks only what an end user can download, not internal development.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning is [SemVer](https://semver.org/) plus **A12 Kernel compatibility metadata** (the kernel each release targets is recorded per entry, never folded into the version string).

**Headline topics only.** An entry names the capabilities and breaking changes a user would notice, not every fix behind them; the source repo's git history is the complete record.

## [Unreleased]

## [0.12.0] — kernel 30.8.1 (A12 Tools 2025.06-ext5)

*(0.11.1 was prepared but never published; its interpreter fix ships here.)*

### Added

- **MCP support — the same tool over the Model Context Protocol** (revision `2026-07-28`), for callers that cannot execute the binary. `dmtool mcp` speaks newline-delimited JSON-RPC on stdin/stdout for a host that launches it; `dmtool mcp --http` binds a port and serves Streamable HTTP for one that reaches it across a network. The tool surface is *derived* from the live command tree, every call carries the model's content both ways, and the server accepts no filesystem path and keeps nothing between calls. A worked session is in `examples/cli-mcp.md`. Where the binary *can* be run, the CLI remains the better surface.
- **Published operational contracts.** Every verb declares its effect, failure persistence, dry-run support, return kind, and aggregate-success rule; every parameter declares what it carries and which way it flows; and a served document bundles the schemas it references, transitively.

### Changed

- **BREAKING:** non-transactional `batch` now exposes `data.dispatched` and `data.allSucceeded`, and its outer `ok` and exit code agree with whether every child exited zero. It still completes full dispatch and retains every child result.
- An **undeclared entity reference is refused before evaluation** rather than producing a confident answer about a rule that cannot exist.

### Fixed

- **Interpreter backend improvements:** row-invariant starred aggregates are folded once per validation call rather than per iteration, `SumOfProducts` is correct under partial relevance, temporal formats and aggregate message references are corrected, and sharing one interpreter across threads is refused outright instead of silently crossing documents.

## [0.11.0] — kernel 30.8.1 (A12 Tools 2025.06-ext5)

This release makes dmtool's result contract uniform and evidence-bearing, gives repeated runtime results stable A12 addresses, and carries the interpreter's latest semantic and browser improvements into the native CLI.

### Changed

- **BREAKING: the result contract is uniform.** The envelope no longer overloads `valid` as a universal success flag (`outcome`/`ok` describe execution, `valid` only a consistency verdict); `apply` and `batch` return the standard envelope; and the patterns catalogue list calls its string projection `paramNames`.
- Every successful write that ran a consistency gate carries an unforgeable `verification` receipt.

### Added

- Runtime results carry canonical A12 pointers, so repeated rows are distinguishable without inventing a pointer spelling.
- Interpreter semantics gain complete `DateRange` handling, owner-shaped `FirstFilledValue`, bounded `BaseYear`, and cross-kind DATE/DATETIME ordering.

## [0.10.1] — kernel 30.8.1 (A12 Tools 2025.06-ext5)

### Changed

- **BREAKING:** `dmtool meta <ref>` is the sole post-creation writer for labels, annotations, and descriptions across every element kind; the duplicated keys and flags are removed from the `modify` verbs, each refusal pointing at the canonical route. `apply` gains transactional metadata edits to match.

## [0.10.0] — kernel 30.8.1 (A12 Tools 2025.06-ext5)

The runtime document boundary now uses A12's own Document JSON shape and I/O behavior end to end. The built-in interpreter also gains partial temporal values and closes the kernel-semantics corrections measured since 0.9.1.

### Changed

- **BREAKING:** `model eval`, `rule eval`, and `model compute` read and write A12's canonical nested Document JSON; the former `{groups:[...], fields:[...]}` placement envelope is removed. Values follow A12's own ingress and egress behavior, including lexical number scale.

### Added

- Runtime support for all four A12 Date precisions, zero-to-three-component Time construction, and the pre-1900 Date validation option.

## [0.9.1] — kernel 30.8.1

Maintenance release — **no functional change to the `dmtool` CLI** (binaries are identical to v0.9.0). Hardens the release build's reproducibility by pinning the kernel bootstrap to exact upstream release sources.

## [0.9.0] — kernel 30.8.1

The built-in interpreter becomes the **sole** runtime evaluation engine, verified bit-for-bit against the A12 kernel on real-world corpora; `model diff` becomes fully comprehensive; and the authoring surface gets a consistency pass. Two breaking changes.

### Changed

- **BREAKING:** the JVM-only `--kernel` eval engine is removed — runtime evaluation runs solely on the native-safe interpreter on every target; the kernel is retained only for the consistency gate.
- **BREAKING:** `model import-jsonschema --format` is renamed **`--string-format`**.

### Added

- **Kernel-parity interpreter:** the built-in evaluation engine matches the A12 kernel bit-for-bit on two real-world corpora at zero divergences, closing a large semantics campaign.
- **Authoring consistency pass:** element metadata across the `rule`/`group`/`config` families, `include set-reference`/`include rename`, and operator-catalog DSL entry points.

### Fixed

- **`model diff` is comprehensive** — fields, groups, computations, type definitions, includes, and model identity are all first-class, eliminating earlier false "no change" results.

## [0.8.2] — kernel 30.8.1 (A12 Tools 2025.06-ext5)

A platform-coverage patch release on the 0.8.x surface — no change to the model operations themselves.

### Added

- **Linux ARM64 native binary**, alongside macOS ARM64, Linux x64, and Windows x64, with checksum coverage.

## [0.8.1] — kernel 30.8.1 (A12 Tools 2025.06-ext5)

A small maintenance + docs release on the 0.8.0 surface — no change to the model operations themselves. Adds `SCENARIOS.md`, a catalogue of realistic multi-turn authoring sessions, and tidies the bundled walkthroughs.

## [0.8.0] — kernel 30.8.1 (A12 Tools 2025.06-ext5)

### Added

- **Runtime evaluation in the native binary — a kernel-free interpreter.** `model eval`, `rule eval`, `model compute`, and `model seed` run in the shipped native image rather than only on the JVM, on a from-scratch evaluator that reproduces the kernel's runtime semantics without its on-the-fly Groovy. Custom field types and custom conditions are supported declaratively or through a Node worker, and an unsupported construct is surfaced rather than silently skipped.
- **Model review — understand and compare models.** `model diff` (structural, with risk tiers, reason codes, `POLARITY_INVERTED` detection, and `--since <ref>`), `model report` (identity, structure, field usage, and a glossed catalog of every rule and computation), and `model normalize`.
- **JSON Schema interop.** `model import-jsonschema` builds a document model from JSON Schema or OpenAPI (JSON or YAML), with dialect/component selection, best-effort or `--strict` import, and multi-model bundles; `model export-jsonschema` goes the other way.

### Changed

- **The native command tree no longer carries `--kernel`** — kernel evaluation needs Groovy, so it is removed from every native surface rather than refused at call time.

## [0.7.0] — kernel 30.8.1 (A12 Tools 2025.06-ext5)

A probe-driven **robustness + DX** wave: a cold agent ran realistic authoring sessions against the shipped binary, and every defect it surfaced was fixed at the root.

### Added

- **`typedef modify`** — change a shared type definition in place, kernel-gated, instead of removing and re-adding it and breaking every field that referenced it.
- **Fuller read-backs across `rule`/`computation`/`field`/`group`/`typedef`**, with served data-schemas for the read verbs, so an edit is verifiable straight from the tool.

### Changed

- **A field's value type is reported as `kind` everywhere, with one vocabulary** — the kernel discriminator no longer leaks, and a type-definition-typed field reports its resolved underlying kind plus the `typedef` it references. (If you parsed the old `type` key, switch to `kind`.)
- The authoring skill is now **`/a12-dmtool`** and covers full document-model authoring, not only validation rules.

### Fixed

- **Two native-image crashes** — a rule message using error-text parameter tokens, and serializing or expanding a model carrying a `roles` header annotation. Both worked on the JVM and killed the binary.
- **No verb leaks a raw Java stack trace**, and a broad diagnostics sweep gives opaque kernel rejections a meaning and a corrective fix.
