# `:cli` — `dmtool`, the command-line front end

**Ships as a native binary, not a library.** Package `io.github.mbackschat.a12.dm.cli`.

One self-describing executable over everything else in this repository: authoring and reading a model's rules *and* structure, JSON Schema and OpenAPI exchange, semantic review, safety-gated refactors, and running rules against real documents. Every edit is confirmed by the **embedded real A12 Kernel** before it is written, so *valid* means the engine accepted it.

It is built **agent-first** — the primary use is inside a coding-agent session — which is why discoverability lives in the tool rather than in its documentation: `manifest` lists every verb × parameter with a worked example, and `operators` / `patterns` / `diagnostics` / `schema` expose the DSL vocabulary, the validation idioms, each diagnostic code and the exact I/O shape, all from the binary itself.

**Verb families** (each a package under `src/main/java/io/github/mbackschat/a12/dm/cli/`): `model`, `rule`, `computation`, `field`, `group`, `typedef`, `include`, `config`, `workspace`, `profile`, `interp`. Plus the cross-cutting `apply` (ordered surgery, one gate, one write, atomic rollback), `batch` (independent dispatch, no rollback) and `mcp` (the same verbs as MCP tools).

**Two engines, and the one you ask for is the one that answers.** `model check` and `rule check` take `--engine`: `kernel` runs the real A12 Kernel, `portable` runs the kernel-free [`:consistency`](../consistency) checker, which answers a narrower question and never reports a model valid. Neither ever falls back to the other, and the result envelope names which engine executed. Runtime evaluation (`model eval`, `rule eval`, `model compute`, `model seed`) always runs on the kernel-free [`:interpreter`](../interpreter) — that is what lets those verbs work in the native binary, where the kernel's Groovy codegen cannot go.

## Building and running

```sh
./build.sh :cli:installDist    # the dev launcher — bin/dmtool resolves to it
./build.sh :cli:check          # NOT :cli:test — publicContractTest hangs off check
```

Use [`../bin/dmtool`](../bin) at a call site rather than naming a packaging artifact; it resolves to the fastest implementation present. The GraalVM native image is release-only and never in the dev inner loop — see [`docs/NATIVE-IMAGE-GRAALVM.md`](../docs/NATIVE-IMAGE-GRAALVM.md).

Contract: [`docs/CLI-SPEC.md`](../docs/CLI-SPEC.md) · conformance: [`docs/CLI-CONFORMANCE-SPEC.md`](../docs/CLI-CONFORMANCE-SPEC.md) · MCP: [`docs/MCP-SPEC.md`](../docs/MCP-SPEC.md) · runnable walkthroughs: [`examples/`](../examples).
