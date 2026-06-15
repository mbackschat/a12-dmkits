# dmtool — release distribution + agent plugins (Claude Code, Codex)

Public distribution for **`dmtool`**, a CLI for authoring and validating **A12 Kernel** document-model validation rules. This repository is **release-only** (binaries + the plugins are pushed here from a separate source repo); it is not where the tool is developed.

> **Not an official A12 / mgm artifact.** `dmtool` is an independent tool built against the public A12 Kernel.

`dmtool` is **LLM-agent-first**: it installs as a plugin for your coding agent, which downloads the right native binary for your OS (anonymous, checksum-verified) and loads a **rule-authoring skill** that teaches the judgment the tool's help can't — condition *polarity*, error-field paths, iteration scope.

## Install — Claude Code

```
/plugin marketplace add mbackschat/a12-dmtool-releases
/plugin install dmtool
```

The plugin lives at the repository root (`.claude-plugin/`).

## Install — OpenAI Codex

```
codex plugin marketplace add mbackschat/a12-dmtool-releases
codex plugin add dmtool
```

The Codex plugin lives under [`codex/`](codex/); the marketplace manifest at `.agents/plugins/marketplace.json` points Codex at it. Codex users can also drop the bundled [`codex/AGENTS.md`](codex/AGENTS.md) into their own repo's `AGENTS.md` to keep dmtool guidance always in context.

Either way, on the first session the plugin downloads the per-OS native binary from this repo's Releases and puts `dmtool` on PATH. If your host can't inject PATH, the binary is cached at `$PLUGIN_DATA/bin/dmtool`.

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

Author a rule and let the **real kernel** confirm it. A condition is **true on a violation**, so to *enforce* a requirement you write its violation:

```sh
dmtool -m model.dm.json rule check \
  --field /Order/DeliveryDate \
  --condition 'FieldNotFilled(DeliveryDate)' \
  --code DELIVERY_REQUIRED
# → { "valid": true, "diagnostics": [] }
```

Every result is a uniform JSON envelope (`{ok, valid, outcome, data, diagnostics[], …}`) with structured, fix-oriented diagnostics.

## Worked examples

End-to-end walkthroughs of **every verb** — command + real captured output — live in [`examples/`](examples/): the verb tour, the rule/computation edit loop, structure editing with the safe-delete gate, the atomic `apply` session, runtime evaluation, and the version/compatibility surface.

## Changelog

What changed in each release: [`CHANGELOG.md`](CHANGELOG.md).

## Licensing (per artifact)

- **Native CLI binaries → EUPL-1.2.** They embed the A12 Kernel, so each binary is offered under the European Union Public Licence v1.2. See [`LICENSE`](LICENSE), [`NOTICE`](NOTICE), and [`THIRD_PARTY_NOTICES`](THIRD_PARTY_NOTICES). **Kernel source** (EUPL-1.2): <https://github.com/mgm-tp> (satisfies EUPL Art. 5).
- **Plugin source** (the skills + hooks, for both agents) **→ MIT.**

No warranty; see the licence text.
