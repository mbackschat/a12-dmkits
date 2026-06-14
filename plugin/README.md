# dmtool — release distribution + Claude Code plugin

Public distribution for **`dmtool`**, a CLI for authoring and validating **A12 Kernel** document-model validation rules. This repository is **release-only** (binaries + this plugin are pushed here from a separate source repo); it is not where the tool is developed.

> **Not an official A12 / mgm artifact.** `dmtool` is an independent tool built against the public A12 Kernel.

## Install (in Claude Code)

```
/plugin marketplace add mbackschat/dmtool-releases
/plugin install dmtool
```

On first session the plugin downloads the right native binary for your OS from this repo's Releases (anonymous, checksum-verified) and puts `dmtool` on your PATH, and loads the rule-authoring skill.

## Install (raw binary)

Download the binary for your OS from the latest [Release](../../releases) + `SHA256SUMS`, verify, and put it on your PATH:

```sh
shasum -a 256 -c SHA256SUMS            # verify integrity
chmod +x dmtool-macos-arm64            # macOS / Linux
# macOS only, if Gatekeeper blocks a browser download:
xattr -d com.apple.quarantine dmtool-macos-arm64
```

The CLI is **self-describing** — `dmtool --help`, `dmtool manifest`, `dmtool operators`, `dmtool schema <target> <op>`.

> The native binary covers rule **authoring / checking / structure / read**. The runtime-evaluation verbs (`model eval`, `rule test`, `model compute`) require a JVM and are **not** in the native binary.

## Licensing (per artifact)

- **Native CLI binaries → EUPL-1.2.** They embed the A12 Kernel, so each binary is offered under the European Union Public Licence v1.2. See [`LICENSE`](LICENSE), [`NOTICE`](NOTICE), and [`THIRD_PARTY_NOTICES`](THIRD_PARTY_NOTICES). **Kernel source** (EUPL-1.2): <https://github.com/mgm-tp> (satisfies EUPL Art. 5).
- **Plugin source** (this skill + hooks) **→ MIT.**

No warranty; see the licence text.
