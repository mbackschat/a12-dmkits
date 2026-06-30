# Changelog

All notable changes to the **publicly released `dmtool` artifacts** — the native CLI binary and the agent plugins (Claude Code, OpenAI Codex). This file is maintained in the source repo and shipped to the public [`a12-dmtool-releases`](https://github.com/mbackschat/a12-dmtool-releases) mirror; it tracks only what an end user can download, not internal development.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning is [SemVer](https://semver.org/) plus **A12 Kernel compatibility metadata** (the kernel each release targets is recorded per entry, never folded into the version string).

## [0.8.1] — kernel 30.8.1 (A12 Tools 2025.06-ext5)

A small maintenance + docs release on the 0.8.0 surface — no change to the model operations themselves.

### Added

- **`SCENARIOS.md`** — a catalogue of realistic, multi-turn authoring/validation sessions the CLI is exercised with (the natural-language *asks*, complementing the runnable `examples/` walkthroughs).

### Changed

- The JSON Schema **transcoding report** (`model import-jsonschema` / `model export-jsonschema`, on stderr) now word-wraps its omission/to-do notes at a fixed width, so a long note no longer scrolls off the right edge of a terminal.
- The bundled `examples/` walkthroughs were tidied for readability — long commands wrapped, nested JSON output pretty-printed.

### Fixed

- The release mirror now ships the real, maintained `examples/README.md` index (a hardcoded copy could previously go stale).

## [0.8.0] — kernel 30.8.1 (A12 Tools 2025.06-ext5)

Three headline capabilities this release: the **native binary now evaluates document instances** (a from-scratch, kernel-free interpreter powers the runtime verbs, so `model eval` / `rule eval` / `model compute` / `model seed` run in the shipped native image — previously JVM-only); a **model-review** family for understanding and comparing models (`model diff`, `model report`, `model normalize`); and **JSON Schema interop** (`model import-jsonschema` / `model export-jsonschema`).

### Added

- **Runtime evaluation in the native binary — a kernel-free interpreter.** `model eval` (which rules fire on a document instance), `rule eval` (a single rule), `model compute` (what a computed field evaluates to), and `model seed` (generate a valid sample instance) now default to a from-scratch evaluator that reproduces the A12 kernel's runtime semantics **without the kernel's on-the-fly Groovy** — so they run in the GraalVM native image, not just on the JVM. The interpreter is verified rule-for-rule against the kernel, and scales linearly on large documents (it overtakes the kernel on instances with thousands of repeated rows). Add `--kernel` to evaluate with the A12 kernel itself (JVM only). `model eval` / `rule eval` compute-then-validate by default, with `--apply-computed-back` exposed.
- **Custom constructs at runtime.** `--predefined-types` declares custom field types declaratively; `--custom-conditions-js` and `--custom-field-types-js` run a model's imperative custom conditions / field types via a Node worker; `--strict-custom` fails loudly instead of degrading when a custom construct can't be honored. Unsupported custom constructs are surfaced, never silently skipped.
- **Model review — understand and compare models.**
  - `model diff` — a structural two-file diff with **risk tiers and reason codes** (a loosening change outranks a tightening one), **`POLARITY_INVERTED`** detection (a rule's condition was logically flipped) read straight from the rule ASTs, and **`--since <ref>`** to diff the working model against a git ref.
  - `model report` — a self-describing comprehension surface: model identity, structure, field usage, and a glossed catalog of every rule and computation (plain-language gloss + polarity + message).
  - `model normalize` — a deterministic, order-preserving canonical write-out.
  - `--text` gives `model diff` and `model report` a compact human-readable rendering alongside the JSON envelope.
- **JSON Schema interop.** `model import-jsonschema` builds a document model from a JSON Schema or OpenAPI document (JSON **or** YAML), with `--dialect` / `--component` selection, best-effort import defaults (every guess flagged) or `--strict`, multi-model bundle import (`--out-dir`, mounts, includes), and a structured transcoding report carried in the `-o` envelope. `model export-jsonschema` goes the other way, with dialect selection and `--wrap-openapi`.

### Changed

- **The native command tree no longer carries `--kernel`.** Kernel evaluation needs Groovy, so `--kernel` is a JVM-only option — it is now removed from every native surface (`manifest`, `--help`, schema) rather than merely refused at call time.

## [0.7.0] — kernel 30.8.1 (A12 Tools 2025.06-ext5)

A probe-driven **robustness + DX** wave: a cold agent ran realistic authoring sessions against the shipped binary, and every defect it surfaced was fixed at the root. The themes are a **unified value-type vocabulary**, **fuller read-backs**, a new **`typedef modify`** verb, **two native-image crash fixes**, and a broad sweep of **corrective, self-describing diagnostics** — so the tool explains itself and never leaks a raw stack trace.

### Added

- **`typedef modify --id <id> <spec.json>` — change a shared type definition in one place.** A model-level ENUM or restricted-STRING type definition could be added (`typedef add`) but not edited; changing its config meant removing and re-adding it, breaking every field that referenced it. `typedef modify` re-binds the type in place, kernel-gated, with the same per-kind spec `typedef add` takes.
- **`group read` echoes a group's direct child fields.** Reading a group now reports whether it repeats (and its max rows / row-key) **and** the field paths it directly contains, so an agent can see a group's shape without a separate `field read` per child.
- **Fuller read-backs across `rule` / `computation` / `field` / `typedef`.** The read verbs now surface the complete stored spec — a rule/computation's full condition, message set and placement; a field's per-kind config (number/string/enum/date), label, descriptions, requiredness and annotations; a typedef's per-kind config and import provenance — so an edit is verifiable straight from the tool.
- **Served data-schemas for the read verbs — `schema field read` / `schema typedef read` / `schema group read`.** The read output shape (including the value-type `kind` vocabulary) is now self-describing and guard-checked, like the write verbs already were.
- **Per-locale custom error text for a string pattern.** A patterned STRING field can carry a localized "doesn't match" message per display locale, alongside the existing required-field and enum error messages.

### Changed

- **A field's value type is reported as `kind` everywhere, with one vocabulary — the kernel discriminator never leaks.** `field read`, `typedef read`, and the `apply` read/echo ops used to print the raw kernel type (`NumberType`, and the bare `TypeDefType` for a type-definition-typed field), which `field add --kind` won't accept — a broken round-trip. They now report the same `kind` names `model describe` uses (`NUMBER`, `STRING`, `ENUMERATION`, …); a type-definition-typed field reports its **resolved underlying** kind plus the `typedef` id it references. (If you parsed the old `type` key, switch to `kind`.)
- **The authoring skill is now `/a12-dmtool` and covers full document-model authoring, not just rules.** It was `/a12-rules`, scoped by name and description to validation rules, so it wouldn't reliably activate for model-building work (create a model, add fields/groups, factor out an include) even though `dmtool` does all of that. Renamed and rebroadened to **structure *and* rules**, with sharper guidance on bilingual message provisioning, the direction-aware "unguarded number" lint, and inspecting only via the structured read verbs (never the raw model JSON). The CLI binary is unchanged by this item.

### Fixed

- **Native crash on a rule message that uses error-text parameter tokens (`$<Field>.value$`, `$#<Group>$`).** Such a message invokes a kernel parser whose initializer loads a `LexerTerminals` resource bundle the native image hadn't registered, so adding *any* token-bearing message crashed the binary (`MissingResourceException` → exit 70) while the JVM was fine. The bundle is now registered (base + fallback), covering every locale.
- **Native crash when serializing or expanding a model that carries a `roles` header annotation.** The annotation's Jackson getters fire only during kernel expand/copy, a path no read/validate over an un-annotated fixture exercised, so the capture missed them and `include add` / `export` / validate over an annotated model crashed natively. The getters are now registered.
- **No verb leaks a raw Java stack trace anymore.** Every boundary throwable is classified into the structured `rejected` envelope (the `MVK_*`/`RK_*` code + message in `diagnostics[]`) — including a duplicate `rule add` / `computation add` name, a missing group, an invalid-on-disk model loaded by a read verb, and the `apply` / `batch` terminal commit gate (which now surfaces the kernel's actual cause instead of a bare failure).
- **A broad diagnostics sweep — opaque kernel rejections now carry a meaning and a corrective fix.** `MVK_UNEXPECTED_TOKEN` names the bracket-the-operand / boolean-casing cause; `MVK_NO_WILDCARD`, `MVK_INVALID_ENTITY`, and `MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES` get operator-correct fixes; the diagnostic ↔ catalog join is sound (it unions on disagreement instead of picking the first match); and a content-free or misleading rejection now explains its cause.
- **A wrong locator flag on a positional-locator verb self-corrects** — `rule read --path …`, `where-used --field …` and the like now point at the right positional form instead of dead-ending on "Unknown option".
- **The spec-rejection summary reports the right kind of problem** — a wrong-shaped value (e.g. a JSON array where an object is required) is counted as an invalid value, not mislabeled an "unrecognized key".
- **`field modify` no longer silently drops a config block with no `kind`** — it's refused with a precise corrective instead of reporting a false `applied`.
- **Field labels with an incomplete locale set reject cleanly** instead of crashing with exit 70.
- **A no-op `rule` / `computation move` names itself** rather than reporting a confusing bare name clash.
- **The counting operators are discoverable by the word "count"** in `operators count`, and `field read` of a not-found path on a model with includes now names the include caveat (an included field is read on its own model).
