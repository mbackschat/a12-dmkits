# dmtool — A12 rule authoring (Codex context)

`dmtool` is a self-describing CLI for authoring and validating **A12 Kernel** document-model validation rules. This plugin installs it (the `SessionStart` hook downloads the native binary on the first session start) and loads the rule-authoring **skill**, which teaches the judgment that the tool's help can't — condition *polarity*, error-field paths, iteration scope.

> This file is also a ready-made template: copy it into your project's `AGENTS.md` if you want dmtool guidance always in context (not only when the skill is invoked).

## Using the tool

The CLI **describes itself** — start there rather than guessing:

```
dmtool --help                     # the verb tree
dmtool manifest                   # every verb + params, as JSON
dmtool operators                  # the A12 DSL operator catalog
dmtool schema <target> <op>       # a verb's input/output shape
```

If `dmtool` is **`command not found`**, the per-session hook hasn't run in this session yet — most often because the plugin was just installed here, and the hook only runs when a session starts or resumes. **Don't try to download, build, or install it yourself.** Tell the user to start a fresh session or resume this one (resuming keeps the conversation); the hook delivers the binary then. If `dmtool` *does* run but a specific call can't find it on PATH, the binary also sits at `$PLUGIN_DATA/bin/dmtool` — invoke it by that path.

A rule's condition is **true on a violation** (the error scenario), so to *enforce* a requirement you write its violation. Validate a candidate against the real kernel before persisting:

```
dmtool -m model.dm.json rule check --field /Order/DeliveryDate \
  --condition 'FieldNotFilled(DeliveryDate)' --code DELIVERY_REQUIRED
```

The native binary covers authoring / checking / structure / read. The runtime-evaluation verbs (`model eval`, `rule test`, `model compute`) need a JVM and are **not** in the native binary.

> Not an official A12 / mgm artifact. `dmtool` is an independent tool built against the public A12 Kernel.
