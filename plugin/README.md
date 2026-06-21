# dmtool — release distribution + agent plugins (Claude Code, Codex)

Public distribution for **`dmtool`**, a CLI for authoring and validating **A12 Kernel** document-model validation rules. This repository is **release-only** (binaries + the plugins are pushed here from a separate source repo); it is not where the tool is developed.

> **Not an official A12 / mgm artifact.** `dmtool` is an independent tool built against the public A12 Kernel.

This release targets **A12 Kernel 30.8.1** (A12 Tools distribution **2025.06-ext5**) — reported by `dmtool --version` and `dmtool manifest`, and recorded per entry in the [`CHANGELOG`](CHANGELOG.md).

<!-- shared:dmtool-story — MIRRORED from the top-level README.md; do NOT edit here. Edit the canonical block there, then re-copy (SharedReadmeRegionTest enforces parity). -->
## Why `dmtool`

An A12 document model carries **validation rules** and **computations** whose logic is a condition in the kernel's expression language — *"a delivery date must not be before the order date"* becomes a string like:

```
AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0
```

Writing these by hand is fragile: a mistyped path, a wrong operator or decimal scale, or — most insidious — **inverted polarity** (the condition true on the *requirement* instead of the *violation*) slips through until the kernel runs, if it's caught at all.

**`dmtool` closes that gap from the command line** — one self-describing native binary, **JSON in / JSON out**, no Java toolchain. It authors, reads, and safely edits a model's rules *and* structure, and **confirms every result against the real A12 kernel**, so *valid* means the engine accepts it, not that it merely looks right.

It is built **agent-first** — the #1 use is inside a coding-agent session (Claude Code, Codex):

- **Self-describing — a cold agent needs no external docs.** `manifest` lists every verb × parameter with a worked example; `operators` / `patterns` / `diagnostics` / `schema` expose the DSL vocabulary, the validation idioms, each diagnostic code, and the exact I/O shape — all from the binary itself.
- **Judgment where `--help` can't reach.** A bundled skill teaches what the catalog can't — condition *polarity*, error-field paths, iteration scope — so a scaffolded rule has the right shape before the kernel ever sees it.
- **Safe by construction.** Every result rides one uniform envelope; deletes and structural refactors are **gated** (a referenced field won't silently vanish); a multi-op `apply` session is **atomic** (rollback on any failure).

### A session, in natural language

You don't type `dmtool` — your agent does. You state the rule in plain language; the agent resolves the model, reasons about **polarity** and **operator semantics**, and the **real kernel** confirms the result before anything is written:

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

- **The plugin** delivers the binary — its `SessionStart` hook downloads the right per-OS native build (anonymous, checksum-verified) on first use and puts `dmtool` on PATH. One install line (below); the same plugin model serves Claude Code and Codex.
- **The skill** is a small `SKILL.md` of *judgment* — the traps `--help` can't teach: rule **polarity**, valid **error fields**, **iteration** scope, the date/number gotchas. The binary stays self-describing (`manifest` / `operators` / `schema`); the skill teaches *when* and *why*, and the same canonical skill backs both agents.
<!-- /shared:dmtool-story -->

## Install — Claude Code

```
/plugin marketplace add mbackschat/a12-dmtool-releases
/plugin install dmtool
```

The plugin lives at the repository root (`.claude-plugin/`).

### Activation — it's a `SessionStart` hook, so restart after installing

The plugin does **not** fetch `dmtool` at install time. It registers a **`SessionStart` hook** that downloads the binary and adds it to `PATH`, and a `SessionStart` hook fires **only when a Claude Code session begins or resumes** — never retroactively in the session where you typed `/plugin install`.

**So `dmtool` is not yet available in the session you installed it in.** Run the hook by either **resuming this conversation** — `claude --continue` (or `claude --resume`), which **keeps your whole conversation intact** (no need to `/clear` or lose context) — or starting a fresh session. (`/reload-plugins` loads the bundled judgment skill into the current session, but only a session *start* runs the hook that downloads the binary.) The bundled skill knows this too: if you ask for `dmtool` work before the binary has loaded, the agent will tell you to resume the session — it won't try to download or build the tool by hand.

What the hook does on each session start:

| When | What happens |
|---|---|
| **First new session** | Downloads the per-OS native binary from this repo's [Releases](../../releases) — anonymous, checksum-verified against `SHA256SUMS` — caches it under `$CLAUDE_PLUGIN_DATA/bin/<version>/`, and prepends that directory to `PATH`. Needs network; takes a few seconds (60 s timeout). |
| **Every later session** | Same version already cached → no download. It just re-adds the directory to `PATH` (also on `claude --resume` / `--continue`). Effectively instant. |
| **After a plugin upgrade** | The cache is **keyed by version**, so a newer plugin re-downloads the matching binary on its first session (older versions are pruned) — an upgrade can never keep serving a stale binary. |
| **On any failure** | The hook warns to stderr and exits cleanly — it never blocks or aborts your session; `dmtool` simply won't be on `PATH` that session, and the next session start retries the download. |

The current binary is always reachable at the stable path `$CLAUDE_PLUGIN_DATA/bin/dmtool` (a symlink to the active version), so even on a host where the hook can't inject `PATH`, your agent can invoke it there directly.

## Install — OpenAI Codex

```
codex plugin marketplace add mbackschat/a12-dmtool-releases
codex plugin add dmtool
```

The Codex plugin lives under [`codex/`](codex/); the marketplace manifest at `.agents/plugins/marketplace.json` points Codex at it. Codex users can also drop the bundled [`codex/AGENTS.md`](codex/AGENTS.md) into their own repo's `AGENTS.md` to keep dmtool guidance always in context.

Codex uses the **same `SessionStart` hook**, so the *Activation* note above applies here too: the binary is fetched on the first new session, not at install time — restart Codex for the hook to run. If your host can't inject `PATH`, the binary still sits at `$PLUGIN_DATA/bin/dmtool` (Codex's data dir) for direct invocation.

## Install — raw binary (no agent)

Download the binary for your OS from the latest [Release](../../releases) + `SHA256SUMS`, verify, and put it on your PATH:

```sh
shasum -a 256 -c SHA256SUMS            # verify integrity
chmod +x dmtool-macos-arm64            # macOS / Linux
# macOS only, if Gatekeeper blocks a browser download:
xattr -d com.apple.quarantine dmtool-macos-arm64
```

The CLI is **self-describing** — `dmtool --help`, `dmtool manifest`, `dmtool operators`, `dmtool schema <target> <op>`.

> The native binary covers rule **authoring / checking / structure / read**. The runtime-evaluation verbs (`model eval`, `rule test`, `model compute`) require a JVM and are **not** in the native binary.

## What you can do — a quick tour

Discover the whole surface from the binary alone (these need no model):

```sh
dmtool manifest                 # every verb × parameter + a worked example, as JSON
dmtool operators                # the A12 DSL operator catalogue (or `operators <Name>` for one)
dmtool patterns                 # validation idioms — date-order, required-when, … (or `patterns <id>`)
dmtool schema rule check        # a verb's exact input / output shape
```

### From zero to a kernel-checked rule

Create a model, add fields, and attach a validation rule — every step confirmed by the **real A12 kernel** (this whole sequence runs as-is):

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

## Worked examples

End-to-end walkthroughs of **every verb** — command + real captured output — live in [`examples/`](examples/): the verb tour, the rule/computation edit loop, structure editing with the safe-delete gate, the atomic `apply` session, runtime evaluation, and the version/compatibility surface.

## Changelog

What changed in each release: [`CHANGELOG.md`](CHANGELOG.md).

## Licensing (per artifact)

- **Native CLI binaries → EUPL-1.2.** They embed the A12 Kernel, so each binary is offered under the European Union Public Licence v1.2. See [`LICENSE`](LICENSE), [`NOTICE`](NOTICE), and [`THIRD_PARTY_NOTICES`](THIRD_PARTY_NOTICES). **Kernel source** (EUPL-1.2): <https://github.com/mgm-tp> (satisfies EUPL Art. 5).
- **Plugin source** (the skills + hooks, for both agents) **→ MIT.**

No warranty; see the licence text.
