# Changelog

All notable changes to the **publicly released `dmtool` artifacts** — the native CLI binary and the agent plugins (Claude Code, OpenAI Codex). This file is maintained in the source repo and shipped to the public [`a12-dmtool-releases`](https://github.com/mbackschat/a12-dmtool-releases) mirror; it tracks only what an end user can download, not internal development.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning is [SemVer](https://semver.org/) plus **A12 Kernel compatibility metadata** (the kernel each release targets is recorded per entry, never folded into the version string).

## [Unreleased]

_Empty._

## [0.3.1] — kernel 30.8.1 (A12 Tools 2025.06-ext5)

### Changed

- **The README now leads with `dmtool`** — a "Why dmtool" hook, a natural-language agent-session walkthrough (describe a rule in plain English → the agent authors a kernel-valid rule with the right polarity), and a clear explanation of the bundled **skill** and the **plugin**, so the tool's value shows up front. Documentation only; the CLI binary is unchanged.

## [0.3.0] — kernel 30.8.1 (A12 Tools 2025.06-ext5)

### Added

- **`workspace roles` — workspace access-control lint.** Resolves each model's `roles` annotation against the workspace's `roles.yaml` (and `users.yaml`) and flags undefined roles, ungated models, and missing/duplicate roles files. Advisory — it emits warnings and never blocks (always exits 0), and each finding carries an actionable fix.
- **`model usage` — model-wide usage audit.** Reports every field's referencing rules/computations plus the unreferenced set in one pass, so the blast radius of a change is visible before you make it.
- **`--text` global flag** renders the JSON result envelope as human-readable text for terminal use. JSON stays the default machine contract, and the catalogs (`operators`/`manifest`/…) stay JSON.
- **`apply`/`batch`'s `field add` takes the rich per-kind spec** — adding a field inside an atomic session now accepts the same full per-kind configuration (string patterns, number ranges, enum values, …) as the standalone `field add`.
- **`rule read` and `rule explain` surface a rule's stored messages and polarity**, so an agent can explain in natural language when a rule fires and which message it shows.
- **Richer, more accurate operator catalog** — many corrections and additions (date/date-time operand widening, interval-bound rules, the power-operator parse laws, semantic-index context, …) plus an extended `seeAlso` bridge cross-linking the comparison/pattern/value-list operators to their field-config twins.
- **The root `--help` carries an orientation footer** pointing a cold agent at `manifest` / `operators` / `schema` for self-guided discovery.

### Changed

- **`where-used` is now target-first and rides the standard result envelope** *(breaking)*. The entity to audit is passed positionally with the model via `-m` (e.g. `dmtool where-used /Order/Total -m model.json`), and its output now uses the same result-envelope shape as every other verb. Scripts on the old calling form must update — see `dmtool schema where-used`.

### Fixed

- **The self-describing surface no longer names removed verbs.** A few schema descriptions and `batch`'s help text referenced legacy hidden verbs (`check`, `eval-doc`) that were removed; they now name the canonical target-first verbs.

## [0.2.1] — kernel 30.8.1 (A12 Tools 2025.06-ext5)

### Changed

- **Clearer fix hint when a date is compared with an ISO-style literal.** Ordering a date field against an ISO literal (`[DeliveryDate] > "2024-12-31"`) is rejected as `MVK_INVALID_TYPE_FOR_COMPARISON`; the diagnostic's `fix` hint now names the real remedy — write the literal in German `dd.MM.yyyy` form (e.g. `"31.12.2024"`) — instead of only suggesting `== / !=` (which is wrong for a date). The bundled authoring skill's matching note is softened to match.

## [0.2.0] — kernel 30.8.1 (A12 Tools 2025.06-ext5)

### Added

- **`field modify` inside `apply`/`batch`** — re-type or add constraints to an existing field (e.g. give a STRING a `pattern`, tighten a NUMBER's scale) as one op in an atomic `apply`/`batch` session, using the same rich spec as the standalone `field modify` verb (which shipped in 0.1.3).

### Changed

- **`field modify` now warns when a kind change strands rules that read the field** — re-typing a field surfaces an advisory if existing rules or computations reference it, so a breaking re-type is visible before the write.

## [0.1.3] — kernel 30.8.1 (A12 Tools 2025.06-ext5)

### Added

- **`field modify`** — change an existing field's type/config in place (e.g. add a STRING `pattern`, tighten a NUMBER's scale) from the same rich spec as `field add`, **without delete-and-recreate**. Located by `group`+`name`; kernel-gated; a missing field refuses cleanly.
- **Operator `seeAlso`** — the operator catalog now cross-links the pattern / comparison / value-list operators to their field-config twin (`string.pattern`, `number.min`/`max`, `enum.values`), and the field schema points back. Factual and bidirectional, so an agent can discover that a constraint has both a field-config and a rule form (choosing between them stays a modelling judgment).

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
