# Changelog

All notable changes to the **publicly released `dmtool` artifacts** — the native CLI binary and the agent plugins (Claude Code, OpenAI Codex). This file is maintained in the source repo and shipped to the public [`dmtool-releases`](https://github.com/mbackschat/dmtool-releases) mirror; it tracks only what an end user can download, not internal development.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning is [SemVer](https://semver.org/) plus **A12 Kernel compatibility metadata** (the kernel the binary targets is recorded per release, never folded into the version string).

## [0.1.0] — unreleased

Initial public release: the `dmtool` CLI as a single self-contained native binary, plus agent plugins that install it.

### Added

- **`dmtool` — a self-describing CLI for authoring & validating A12 Kernel validation rules.** A cold agent (or person) can discover the whole surface from the binary alone:
  - `dmtool --help`, `dmtool manifest` (every verb × parameter + a worked example, as JSON), `dmtool schema <target> <op>` (a verb's exact I/O shape).
  - `dmtool operators` (the DSL operator catalogue), `dmtool patterns` (validation idioms), `dmtool diagnostics` (the diagnostic-code catalogue) — each listable or explorable one entry at a time.
- **Author & check rules, confirmed by the real kernel.** `rule check` validates a candidate condition (with polarity-aware lint) without persisting; `rule add` persists a kernel-validated rule; `rule check --suggest-error-field` names the legal error fields up front.
- **Scaffold from a validation pattern** — `patterns` turns a named idiom (date-order, required-when, …) into a correct rule-spec with the right polarity and error field baked in.
- **Computations** — author, read, explain, and format computed fields, with author-time lints.
- **Read & navigate a model** — describe (the model card: fields, kinds, enum domains, groups), read rules/computations back into typed form, explain, find references, walk a folder of models.
- **Edit structure with safety gates** — add/modify fields, groups, type-defs, includes, multi-select and attachment templates; a **safe-delete gate** refuses removing a referenced element (with `--cascade` to resolve); reference-preserving **rename / move / extract / inline** refactors that re-validate.
- **Uniform JSON result envelope** on every verb (`{ok, valid, outcome, data, diagnostics[], …}`) and structured, fix-oriented diagnostics.
- **German render target** in addition to the canonical English.
- **Native binary for macOS (Apple Silicon)** — single self-contained file, no JVM to install; downloads anonymously with `SHA256SUMS` integrity verification.
- **Agent plugins (Claude Code + OpenAI Codex)** — install the tool into your coding agent: a `SessionStart` hook downloads the right binary and puts `dmtool` on PATH, and a rule-authoring **skill** teaches the judgment the help can't (condition polarity, error-field paths, iteration scope). The same canonical skill backs both agents.

### Notes

- The native binary covers rule **authoring / checking / structure / read**. The **runtime-evaluation** verbs (`model eval`, `rule test`, `model compute`) require a JVM and are **not** included in the native binary.
- **Licensing is per artifact:** the native binaries embed the A12 Kernel and are offered under **EUPL-1.2** (see `LICENSE` / `NOTICE`); the plugin source (skills + hooks) is **MIT**.
- **Not an official A12 / mgm artifact** — an independent tool built against the public A12 Kernel.

**Compatibility:** A12 Kernel **30.8.1**.
