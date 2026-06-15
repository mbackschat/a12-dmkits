# Changelog

All notable changes to the **publicly released `dmtool` artifacts** — the native CLI binary and the agent plugins (Claude Code, OpenAI Codex). This file is maintained in the source repo and shipped to the public [`a12-dmtool-releases`](https://github.com/mbackschat/a12-dmtool-releases) mirror; it tracks only what an end user can download, not internal development.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning is [SemVer](https://semver.org/) plus **A12 Kernel compatibility metadata** (the kernel each release targets is recorded per entry, never folded into the version string).

## [Unreleased]

_Empty._

## [0.1.2] — kernel 30.8.1 (A12 Tools 2025.06-ext5)

### Fixed

- **Field-level STRING/ENUM patterns and constraints now work in the native binary.** Adding or loading a field with a `pattern`+`patternMessage` (or other localized constraint message) crashed the native binary with `MissingReflectionRegistrationError`, and — once construction worked — validating such a model failed with a StringTemplate `no such property` error. Both were native-image reflection gaps; the JVM was never affected.
- **German-locale (and any-locale) rule diagnostics now render in the native binary.** `rule check` / `model validate` of an invalid condition failed with `Can't find bundle … error_messages, locale <X>` in every locale (it surfaced first on `de_DE`); the parser's i18n message bundle wasn't included in the native image.

The underlying fix registers the kernel's two by-name reflective surfaces by rule (so coverage is shape-independent, not sample-dependent) and includes all i18n bundles; a broad no-crash gate now guards against the whole class.

## [0.1.1] — kernel 30.8.1 (A12 Tools 2025.06-ext5)

### Added

- `dmtool --version` and `manifest.version` now report the **A12 Tools distribution** label (`2025.06-ext5`) alongside the kernel semver, so the human-facing A12 release the kernel ships in is discoverable from the binary.

### Changed

- README and plugin metadata now state the targeted **A12 Kernel 30.8.1 (A12 Tools 2025.06-ext5)** up front.

## [0.1.0] — kernel 30.8.1 (A12 Tools 2025.06-ext5)

Initial public release: the native `dmtool` CLI (authoring / checking / structure / read) plus the Claude Code and OpenAI Codex plugins with the rule-authoring skill.
