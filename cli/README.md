# `:cli` — `dmtool`, the front end

## The problem

The kits solve model authoring for someone writing Java. That is not who mostly needs it.

**A modeller or BA** wants to change a rule, not install a JDK and start a project. **A coding agent** — the primary user here — has a shell and no ability to add a Maven dependency mid-session. Both need the whole capability behind a command.

And an agent has a second problem the kits cannot have: **it does not know what the tool can do.** A library consumer has an IDE, javadoc and a compiler. An agent has whatever it can find out by running something. Point one at an undiscoverable CLI and it guesses flags, invents verb names, and writes conditions with the polarity inverted — confidently, because nothing contradicted it.

## What this module does about it

**One self-describing native binary**, with no Java toolchain to install, over everything else in the repository: authoring and reading a model's rules *and* structure, JSON Schema and OpenAPI exchange, semantic diff, safety-gated refactors, and running rules against real documents. Every edit goes through the **embedded real A12 Kernel** before it is written, so *valid* means the engine accepted it.

**Discoverability lives in the tool, not in its documentation.** That is a deliberate inversion: `manifest` lists every verb × parameter with a worked example, and `operators` / `patterns` / `diagnostics` / `schema` expose the DSL vocabulary, the validation idioms, each diagnostic code and the exact I/O shape — all served from the binary. A cold agent given only the executable can find the verbs, their parameters and each output's shape without any external file. The bundled [skill](../skill) then carries only what a help text cannot: the judgment calls.

**Safe by construction, because an agent will try everything.** Deletes and structural refactors are **gated** — a referenced field will not silently vanish, and the refusal names the referrers. A multi-op `apply` session is **atomic**, rolling back on any failure. Envelope-producing operations share one result shape, and the raw artifact and text modes are explicit in the operational profile rather than discovered by surprise.

## Two engines, and the one you ask for is the one that answers

`model check` and `rule check` take `--engine`:

```sh
dmtool -m order.dm.json model check --engine kernel     # the real A12 Kernel: can answer "valid"
dmtool -m order.dm.json model check --engine portable   # the kernel-free checker: never answers "valid"
```

The portable engine is [`:consistency`](../consistency), and it answers a **narrower** question — it can reject a model or report that it concluded nothing, and exits 3 for the latter. **Neither engine ever falls back to the other**, and the envelope names `engineRequested` and `engineExecuted` so a receipt cannot be ambiguous about which one spoke.

Runtime evaluation — `model eval`, `rule eval`, `model compute`, `model seed` — always runs on the kernel-free [`:interpreter`](../interpreter). That is what lets those verbs work in the native binary at all: the interpreter needs no code generation, where the kernel's Groovy codegen cannot go native.

## Verb families

Each is a package under `src/main/java/io/github/mbackschat/a12/dm/cli/`: `model`, `rule`, `computation`, `field`, `group`, `typedef`, `include`, `config`, `workspace`, `profile`, `interp` — plus the cross-cutting `apply` (one gate, one write, rollback), `batch` (independent dispatch, no rollback) and `mcp`, which serves the same verbs as MCP tools for a host that cannot execute a binary.

## Building and running

```sh
./build.sh :cli:installDist    # the dev launcher
./build.sh :cli:check          # NOT :cli:test — publicContractTest hangs off check
./bin/dmtool manifest          # the shim finds whichever build exists
```

Call [`../bin/dmtool`](../bin) rather than naming a packaging artifact; it resolves to the launcher, or to the native binary with `RK_NATIVE=1`. The GraalVM native image is release-only and never in the dev inner loop.

Contract: [`docs/CLI-SPEC.md`](../docs/CLI-SPEC.md) · conformance: [`docs/CLI-CONFORMANCE-SPEC.md`](../docs/CLI-CONFORMANCE-SPEC.md) · MCP: [`docs/MCP-SPEC.md`](../docs/MCP-SPEC.md) · native image: [`docs/NATIVE-IMAGE-GRAALVM.md`](../docs/NATIVE-IMAGE-GRAALVM.md) · runnable walkthroughs: [`examples/`](../examples).
