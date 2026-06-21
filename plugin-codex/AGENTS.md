# dmtool — A12 rule authoring (Codex context)

`dmtool` is a self-describing CLI for authoring and validating **A12 Kernel** document-model validation rules. This plugin loads the rule-authoring **skill** (which teaches the judgment that the tool's help can't — condition *polarity*, error-field paths, iteration scope) and bundles an installer the skill runs **on demand** to download the native binary the first time you need it.

> This file is also a ready-made template: copy it into your project's `AGENTS.md` if you want dmtool guidance always in context (not only when the skill is invoked).

## Using the tool

The CLI **describes itself** — start there rather than guessing:

```
dmtool --help                     # the verb tree
dmtool manifest                   # every verb + params, as JSON
dmtool operators                  # the A12 DSL operator catalog
dmtool schema <target> <op>       # a verb's input/output shape
```

If `dmtool` is **`command not found`**, run the `ensure-dmtool.sh` installer bundled in the skill's directory (beside the rule-authoring skill's `SKILL.md`) to download it on demand. It fetches the per-OS native build, checksum-verifies it, and prints `dmtool ready: <absolute path>`; **invoke `dmtool` by that printed path** for the rest of the session (a script you run can't reliably add it to `PATH`). The binary also sits at the stable `$PLUGIN_DATA/bin/dmtool`. **Don't build it from source or install it any other way.**

A rule's condition is **true on a violation** (the error scenario), so to *enforce* a requirement you write its violation. Validate a candidate against the real kernel before persisting:

```
dmtool -m model.dm.json rule check --field /Order/DeliveryDate \
  --condition 'FieldNotFilled(DeliveryDate)' --code DELIVERY_REQUIRED
```

The native binary covers authoring / checking / structure / read. The runtime-evaluation verbs (`model eval`, `rule test`, `model compute`) need a JVM and are **not** in the native binary.

> Not an official A12 / mgm artifact. `dmtool` is an independent tool built against the public A12 Kernel.
