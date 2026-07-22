# dmtool — release distribution + agent plugins (Claude Code, Codex)

**`dmtool`** is an agent-friendly, **self-describing** CLI for authoring, validating, and safely editing **A12 Kernel document models** — validation rules, computations, and structure — one native binary, **JSON in / JSON out**. Every edit is confirmed by the embedded **real A12 kernel** before it is written, and runtime evaluation (which rules fire on a real document) runs on a clean-room, kernel-exact **interpreter**. Point your coding agent at it: the tool describes itself (`manifest` / `operators` / `schema`), and the bundled skill teaches the judgment calls.

This repository is the **public distribution** — release-only (binaries + the plugins are pushed here from a separate source repo); it is not where the tool is developed.

> **Not an official A12 / mgm artifact.** `dmtool` is an independent tool built against the public A12 Kernel.

This release targets **A12 Kernel 30.8.1** (A12 Tools distribution **2025.06-ext5**) — reported by `dmtool --version` and `dmtool manifest`, and recorded per entry in the [`CHANGELOG`](CHANGELOG.md).

<!-- shared:dmtool-story — MIRRORED from the top-level README.md; do NOT edit here. Edit the canonical block there, then re-copy (SharedReadmeRegionTest enforces parity). -->
## Why `dmtool`

An A12 document model carries **validation rules** and **computations** whose logic is a condition in the kernel's expression language — *"a delivery date must not be before the order date"* becomes a string like:

```
AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0
```

Writing these by hand is fragile: a mistyped path, a wrong operator or decimal scale, or — most insidious — **inverted polarity** (the condition true on the *requirement* instead of the *violation*) slips through until the kernel runs, if it's caught at all.

**`dmtool` closes that gap from the command line** — one self-describing native binary, **JSON in / JSON out**, no Java toolchain. It authors, reads, and safely edits a model's rules *and* structure — **every edit is confirmed by the embedded real A12 kernel before it is written**, so *valid* means the engine accepts it, not that it merely looks right — and it runs rules against real documents on a **clean-room interpreter** held kernel-exact by a differential test estate and a tri-verified conformance corpus.

It is built **agent-first** — the #1 use is inside a coding-agent session (Claude Code, Codex):

- **Self-describing — a cold agent needs no external docs.** `manifest` lists every verb × parameter with a worked example; `operators` / `patterns` / `diagnostics` / `schema` expose the DSL vocabulary, the validation idioms, each diagnostic code, and the exact I/O shape — all from the binary itself.
- **Judgment where `--help` can't reach.** A bundled skill teaches what the catalog can't — condition *polarity*, error-field paths, iteration scope — so a scaffolded rule has the right shape before the kernel ever sees it.
- **Safe by construction.** Every result rides one uniform envelope; deletes and structural refactors are **gated** (a referenced field won't silently vanish); a multi-op `apply` session is **atomic** (rollback on any failure).

### A session, in natural language

You don't type `dmtool` — your agent does. You state the rule in plain language; the agent resolves the model, reasons about **polarity** and **operator semantics**, and the **real kernel** confirms the authored rule before anything is written:

**You —** *"In the order model, a delivery date must never be before the order date."*

**Agent —** *finds the fields, then checks the operator's sign convention:*

```sh
dmtool -m order.dm.json model describe | jq -c '.data.fields[] | select(.kind=="DATE") | .path'
# → "/Order/OrderDate"   "/Order/DeliveryDate"
dmtool operators DifferenceInDays          # its meaning, sign convention, gotchas, and OPPOSITE
```

*An A12 rule fires on the **violation**, so the condition must be true when delivery is before order — and that gets validated against the real kernel before anything is written:*

```sh
dmtool -m order.dm.json rule check --field /Order/DeliveryDate --code DELIVERY_BEFORE_ORDER \
  --condition 'AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0'
# → { "valid": true, "diagnostics": [] }   ✓ accepted — polarity lint clean, error field legal & in scope
dmtool -m order.dm.json rule add delivery-rule.json     # persist (kernel-checked again on write)
```

**Done** — the rule is written and re-validated: true *exactly* when delivery precedes the order (the violation), with a referenced, in-scope error field. The agent didn't guess: had it written the condition true on the *requirement*, the built-in **polarity lint** would have flagged it; had it invented a field path or misread the operator, the kernel would have rejected it. That is the point — **deep rule + operator semantics, gated by the engine itself**, reachable from one English sentence.

### The skill & the plugin

Two pieces make an agent this good with `dmtool`:

- **The plugin** delivers the binary — a bundled installer the skill runs **on demand** (the first time `dmtool` is needed, in that same session) fetches the right per-OS native build (anonymous, checksum-verified). One install line (below); the same plugin model serves Claude Code and Codex.
- **The skill** is a small `SKILL.md` of *judgment* — the traps `--help` can't teach: rule **polarity**, valid **error fields**, **iteration** scope, the date/number gotchas. The binary stays self-describing (`manifest` / `operators` / `schema`); the skill teaches *when* and *why*, and the same canonical skill backs both agents.
<!-- /shared:dmtool-story -->

**A second bundled skill — `/a12-dmtool-bug-report`.** When something breaks, ask your agent to file a bug report. It isolates a **minimal reproduction**, tells a genuine **tool defect** apart from a rejected input (an internal error surfaces as a structured `error` envelope with `RK_INTERNAL_ERROR`, exit 70 — never a raw stack trace), captures the friction it hit, and writes a crisp report to **a folder you choose** — ready to attach to an issue or send however you like. Good reports help us fix the tool fast; it's the same report rigor we use to probe `dmtool` ourselves.

## Install — Claude Code

```
/plugin marketplace add mbackschat/a12-dmtool-releases
/plugin install dmtool
```

The plugin lives at the repository root (`.claude-plugin/`).

### Activation — on demand, no restart

`dmtool` is fetched **the first time your agent needs it** — no restart, no resume, no session hook. The plugin bundles a small installer beside the skill; when the agent reaches for `dmtool` and it isn't there, the skill runs that installer. Because it's triggered by *use* (not by a session starting), it works **in the very session you installed the plugin in**.

| The installer | |
|---|---|
| **on first use / a new version** | downloads the per-OS binary from [Releases](../../releases), checksum-verifies it against `SHA256SUMS`, caches it under `$CLAUDE_PLUGIN_DATA/bin/<version>/` (version-keyed, so an upgrade can't serve a stale binary), and **prints the absolute path** — a few seconds. |
| **thereafter** | already cached → resolves instantly. |
| **on any failure** | warns, and is retried the next time `dmtool` is requested; never blocks your session. |

A script the agent runs mid-session can't reliably add a binary to `PATH`, so the agent invokes `dmtool` by the path the installer prints — always the stable `$CLAUDE_PLUGIN_DATA/bin/dmtool` (a symlink to the active version).

## Install — OpenAI Codex

```
codex plugin marketplace add mbackschat/a12-dmtool-releases
codex plugin add dmtool
```

The Codex plugin lives under [`codex/`](codex/); the marketplace manifest at `.agents/plugins/marketplace.json` points Codex at it. Codex users can also drop the bundled [`codex/AGENTS.md`](codex/AGENTS.md) into their own repo's `AGENTS.md` to keep dmtool guidance always in context.

Codex uses the **same on-demand installer**, so the *Activation* note above applies here too: the skill fetches the binary the first time you need it — no restart. The binary lands at `$PLUGIN_DATA/bin/dmtool` (Codex's data dir), and the agent invokes it by the path the installer prints.

## Install — raw binary (no agent)

Download the binary for your OS — `dmtool-macos-arm64`, `dmtool-linux-x64`, `dmtool-linux-arm64`, or `dmtool-windows-x64.exe` (the assets are platform-suffixed because four platforms share one release page) — from the latest [Release](../../releases) + `SHA256SUMS`, verify, and put it on your PATH:

```sh
shasum -a 256 -c SHA256SUMS            # verify integrity
# macOS only, if Gatekeeper blocks a browser download:
xattr -d com.apple.quarantine dmtool-macos-arm64
# rename on install, so the tool on your PATH is a stable `dmtool`:
mv dmtool-macos-arm64 dmtool && chmod +x dmtool   # or dmtool-linux-x64 / dmtool-linux-arm64
```

The CLI is **self-describing** — `dmtool --help`, `dmtool manifest`, `dmtool operators`, `dmtool schema <target> <op>`.

> The native binary covers rule **authoring / checking / structure / read** **and runtime evaluation** — `model eval`, `rule eval`, `model compute`, `model seed` run on the native-safe interpreter (kernel-free), the sole runtime eval engine on every target.

## What you can do — a quick tour

Discover the whole surface from the binary alone (these need no model):

```sh
dmtool manifest                 # every verb × parameter + a worked example, as JSON
dmtool operators                # the A12 DSL operator catalogue (or `operators <Name>` for one)
dmtool patterns                 # validation idioms — date-order, required-when, … (or `patterns <id>`)
dmtool schema rule check        # a verb's exact input / output shape
```

### From zero to a kernel-checked rule

Create a model, add fields, and attach a validation rule — every authoring step confirmed by the **real A12 kernel** before it is written (this whole sequence runs as-is):

```sh
# 1. a new, empty, kernel-valid model
dmtool model new --id orders --locale en_US --root Order -o order.dm.json

# 2. add two date fields (structure editing — writes in place)
dmtool -m order.dm.json field add --group /Order --name OrderDate --kind DATE
dmtool -m order.dm.json field add --group /Order --name DeliveryDate --kind DATE
# → { "outcome": "applied", "changed": { "added": "/Order/DeliveryDate", "kind": "DATE" } }

# 3. CHECK a rule against the kernel before saving anything (dry-run, writes nothing)
dmtool -m order.dm.json rule check \
  --field /Order/DeliveryDate \
  --condition 'AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0' \
  --code DELIVERY_BEFORE_ORDER
# → { "valid": true, "diagnostics": [] }

# 4. once it checks out, persist it
echo '{ "field": "/Order/DeliveryDate",
        "condition": "AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0",
        "code": "DELIVERY_BEFORE_ORDER",
        "messages": [ { "locale": "en_US", "text": "Delivery date must not be before the order date." } ] }' > rule.json
dmtool -m order.dm.json rule add rule.json
# → { "outcome": "applied", "changed": { "rule": "/Order/DELIVERY_BEFORE_ORDER" }, "written": true }
```

**Reading the `rule check` call** (step 3) — its pieces:

| Part | Meaning |
|---|---|
| `-m order.dm.json` | the model to check against (global option; before or after the verb) |
| `rule check` | validate a *candidate* rule against the kernel — **writes nothing** (vs `rule add`, which persists) |
| `--field /Order/DeliveryDate` | the **error field**: a path *inside the model*, flagged when the rule fires |
| `--condition '…'` | the rule logic in A12 DSL |
| `--code DELIVERY_BEFORE_ORDER` | the error code the rule carries |

**The key idea — polarity:** a condition is **true on the *violation*, not the requirement.** To enforce *"delivery must not be before the order date,"* you write the case to **reject** — `DifferenceInDays(OrderDate, DeliveryDate) < 0` is true exactly when delivery *is* before order (the `AllFieldsFilled(…)` guard skips the check until both dates are present). Writing the requirement directly would flag every *valid* document — the inverted-polarity trap the bundled skill helps you avoid.

Every result is a uniform JSON envelope (`{ok, valid, outcome, changed, diagnostics[], …}`) with structured, fix-oriented diagnostics.

### Review an edit semantically

`model diff` is a structural semantic diff: it independently binds both models, ignores formatting noise, and reports typed field, group, rule, computation, typedef, authored include/import, and model-configuration changes with per-aspect deltas, review-impact tiers, and reason codes. Multi-model two-file diffs resolve each side from `-w` or its own folder; `--since` resolves the base model and dependencies at the Git ref. Delta JSON preserves deterministic `before`/`after` strings and adds tagged `beforeValue`/`afterValue` scalars or string lists for typed consumers.

When base and head contain different revisions of the same referenced model id, pass repeatable `--base-workspace` and `--head-workspace` roots; shared `-w` is for a common dependency snapshot.

```sh
dmtool model diff before.dm.json after.dm.json
dmtool model diff --base-workspace old --head-workspace new old/app.dm.json new/app.dm.json
dmtool -m order.dm.json model diff --since HEAD
```

A field diff covers complete typed config, requiredness, enum values, metadata, and flags; removing or retyping a field with rule/computation readers is HIGH. A rule diff includes its error entity, localized messages, and metadata. A group diff covers repeatability, index/sort declarations, mount alias/options, metadata, and mixed authored ordering. A computation operation changing from `Price × Quantity` to `Price + Quantity` is a first-class `COMPUTATION_CHANGED` modification. Typedefs carry complete config/metadata; authored includes and type-definition imports carry purpose/reference separately from expanded content. The stable `CONFIG model` element covers identity/version, locales, scalar settings, supported characters, base year, labels, comment, annotations, and roles without set-order noise. Rule polarity inversions are `POLARITY_INVERTED` (HIGH), while message-only changes remain LOW. The JSON shape is self-described by `dmtool schema model diff`; `--text` gives the impact-sorted human view.

### From a JSON Schema or OpenAPI document

Originate a model from an existing **JSON Schema** or **OpenAPI** spec (3.0 / 3.1 / 3.2), and export one back out — the same two verbs handle both, because OpenAPI's Schema Object *is* a JSON-Schema dialect:

```sh
# OpenAPI → model: --dialect AUTO-detects 3.0/3.1/3.2; --component picks the components/schemas entry
dmtool model import-jsonschema \
  --schema petstore.openapi.json \
  --component Order \
  -o order.dm.json

# …or import the WHOLE document as a bundle — one model per component, wired by includes
dmtool model import-jsonschema \
  --schema petstore.openapi.json \
  --out-dir ./models/

# model → a drop-in OpenAPI components/schemas envelope
dmtool -m order.dm.json model export-jsonschema \
  --dialect OPENAPI_31 --wrap-openapi > order.openapi.json
```

Import is **best-effort** — it maps as much as possible (even unbounded arrays and recursive `$ref`s) and flags every guess in the stderr report; `--strict` omits everything uncertain. Constraints with no field-config home become real a12 **rules** (`required`/`enum`/`pattern`, exclusive bounds, `multipleOf`, `const`, `dependentRequired`, `uniqueItems`, discriminators, `if`/`then`/`else`, `not`, `contains`), each kernel-checked. `--dialect`, `--component`, `--out-dir`, and `--wrap-openapi` are self-describing: `dmtool model import-jsonschema --help` enumerates every value and its implication.

## Worked examples

End-to-end walkthroughs of **every verb** — command + real captured output — live in [`examples/`](examples/) (full [index](examples/README.md)): discovering the tool, reading & **reviewing** a model (`model report` / `model diff`), the rule/computation edit loop, structure editing with the safe-delete gate, the atomic `apply` session, runtime evaluation, **JSON Schema ⇄ model** interop, custom field types & conditions, multi-file **workspaces**, and the version/compatibility surface.

Want ideas for what to *ask*? [`SCENARIOS.md`](SCENARIOS.md) catalogues realistic **multi-step sessions** — ordered, plain-language asks you can hand to your agent verbatim: from-scratch authoring, evolving an existing model's rules, refactors, computations, and more.

## Changelog

What changed in each release: [`CHANGELOG.md`](CHANGELOG.md).

## Licensing (per artifact)

- **Native CLI binaries → EUPL-1.2.** They embed the A12 Kernel, so each binary is offered under the European Union Public Licence v1.2. See [`LICENSE`](LICENSE), [`NOTICE`](NOTICE), [`THIRD_PARTY_NOTICES`](THIRD_PARTY_NOTICES), and the embedded kernel's own dual licence in [`KERNEL-LICENSE`](KERNEL-LICENSE). **Kernel source** (EUPL-1.2): <https://github.com/mgm-tp> (satisfies EUPL Art. 5).
- **Plugin source** (the skills + hooks, for both agents) **→ MIT.**

No warranty; see the licence text.
