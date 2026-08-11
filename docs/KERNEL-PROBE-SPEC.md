# Kernel-runtime observation probe

The JVM-only, non-shipped route by which an external consumer asks the **real A12 kernel** a *runtime* question — what fires on this document, with what polarity, at what coordinates — and gets back a deterministic JSON artifact it can pin by SHA-256 and replay as evidence. It exists so a runtime question does not cost a purpose-written Kotlin test.

```
./gradlew :adapter:kernelProbe -Prequest=<request.json> -Pout=<artifact.json>
```

*(`./build.sh` is the same thing with the isolated Gradle home + JDK 21 pinned — see [BUILD-ENVIRONMENT](BUILD-ENVIRONMENT.md).)*

| | |
|---|---|
| Request shape | [`adapter/src/test/resources/kernel-probe/request.schema.json`](../adapter/src/test/resources/kernel-probe/request.schema.json) |
| Artifact shape | [`adapter/src/test/resources/kernel-probe/artifact.schema.json`](../adapter/src/test/resources/kernel-probe/artifact.schema.json) |
| Implementation | `adapter/src/test/java/…/adapter/laws/KernelRuntimeProbe.java` + `KernelProbeArtifact.java` |
| Locks | `adapter.laws.KernelRuntimeProbeTest` (kernel-backed), `adapter.laws.KernelProbeArtifactTest` (shape), `GateTierWiringTest` (the task stays a measurement) |

## 1. Why a Gradle task and not a verb

Kernel runtime evaluation does **Groovy runtime code generation** and is not AOT-compilable. That is the whole reason dmtool's runtime verbs (`model eval`, `model compute`, `rule eval`) run the kernel-free `:interpreter` as the sole runtime eval engine, `Cli.JVM_ONLY_VERBS` is empty, Groovy is excluded from `nativeImageClasspath`, and the `--kernel` eval option was retired in Set V ([ARCHITECTURE](ARCHITECTURE.md) "The Groovy wall").

A runtime *verb* would undo all of that. A **test-scoped Gradle task** touches none of it: the probe's classpath is `sourceSets.test.runtimeClasspath`, so it cannot reach a published jar or the native image, and no gate depends on it. `GateTierWiringTest.theKernelRuntimeProbeIsAMeasurementAndNotAGate` is where that stays true.

Linkage was never the obstacle — `adapter/src/main/java` already links the kernel, which is what makes `rule check` a real kernel verdict. What was missing was a way to *reach* the runtime oracle without authoring Kotlin.

## 2. What it observes

`IDocumentRtService.validateFull` over the supplied document, on **both** kernel codegen strategies:

| `engine` | what it is |
|---|---|
| `kernel-groovy-dynamic` | the production-normative anchor — the generated/cached Groovy md-runtime service |
| `kernel-java-static` | the kernel's own validator generated as Java and compiled in memory, run through the static rt-service |

Only `validateFull`. Not `compute`, not `validatePart` — a probe that guessed at more surface than it was asked for would be inventing contract.

**A strategy split is the finding, so it is never merged away.** Each row keeps one block per strategy and `enginesAgree` is *derived* from them; no caller can author it. On a legal-input split the ratified oracle is dynamic Groovy and the static-Java result is characterized, never adopted (INTERPRETER-FINDINGS IF136, [CONFORMANCE-CORPUS-SPEC](CONFORMANCE-CORPUS-SPEC.md) §11).

## 3. The request

One document model plus one or more named documents. **Authoring stays on dmtool's structured verbs** — the probe grows no second authoring surface, so a rule or computation reaches the probe by being in the model file, added with `dmtool rule add` / `computation add`. The model is read exactly as the kernel's own deserializer reads it, with no reference resolution, so it must be self-contained (`dmtool model expand`).

Documents are **A12's canonical Document JSON** — the same shape `dmtool model seed` writes and `model eval --instance` reads ([`document-instance.schema.json`](../cli/src/main/resources/schema/document-instance.schema.json)) — read through A12's own `IDocumentV2Serializer`. So one document file feeds the interpreter and this probe alike, and a difference between them is a real difference rather than an ingress artifact.

```jsonc
{
  "model": "order.dm.json",                 // relative to THIS file, or absolute
  "locale": "en_US",                        // optional, default en_US — but see below
  "clock": "2026-08-11T00:00:00Z",          // optional: pins currentDateForTest on both strategies
  "rows": [
    { "name": "billing-filled", "document": "billing-filled.json" },
    { "name": "empty-order",    "documentInline": { "Order": {} } }
  ]
}
```

**`locale` is not cosmetic.** The kernel refuses a locale the model has not *declared*, outright — measured 2026-08-11: probing a `de_DE`-only model at the default gives `IllegalArgumentException: Locale 'en_US' is not supported in the document model '…'`. So a German-only model is unobservable without this key rather than merely reported in English. Only the `message` channel is locale-dependent; `code`, `type`, `severity`, and every address are not. Both directions are locked by `KernelRuntimeProbeTest.theRequestPinsAnEvaluationLocaleBecauseTheKernelRefusesAnUndeclaredOne`.

## 4. The artifact

A header naming the producer, then one entry per row in request order, then one block per strategy carrying that strategy's complete message list. Each message uses the **key names of a `model eval` `messages[]` entry** — `code`, `field`, `severity`, `type`, `message`, `referenced`, `fillToFix` — so one parser reads dmtool's runtime output and this artifact alike.

Addresses keep dmtool's published **`a12-message-address`** contract, decided per address on the typed pointer: canonical A12 when it names exactly one cell (no leading slash, the implicit `[1]` omitted), and the interpreter house form when it does not, because A12 defines no pointer string for an address carrying a wildcard or an unresolved level ([CLI-SPEC](CLI-SPEC.md) §5). **Test the leading slash to tell them apart.** Nothing is lost: only A12's own implicit `[1]` goes unwritten, so every wildcard and every row above the first survives verbatim — which is what makes the probe able to answer a coordinate question at all (`/Order[1]/Items[0]/Count` from a starred group operand beside `Order/Billing/Street` from an unstarred one).

### Determinism is structural

Two runs over identical input and an identical source tree produce **byte-identical** artifacts, which is what lets a consumer pin one by SHA-256. It is arranged rather than hoped for:

- messages are put in a total order over their published channels, never kernel emission order;
- `referenced` and `fillToFix` are sorted — the channels are compared as sets everywhere they are compared, and an unstable order would break the pin;
- the header carries **no** clock reading, and a model whose rules read the current date needs `clock` in the request to reproduce;
- the renderer pins a `\n` indenter rather than inheriting the platform separator.

`producer.source.state` says whether the **revision** or the **worktree** produced the observation: `CLEAN` means the tree matched `revision` exactly, `DIRTY` that it did not and the artifact is therefore not reproducible from `revision` alone. It is measured by [`scripts/SourceProvenance.java`](../scripts/SourceProvenance.java), the same program that stamps dmtool's own version resource.

## 5. Refusal

A model the kernel's consistency oracle reports an `ERROR` on **fails the run with those diagnostics and writes nothing**. This is the load-bearing failure mode: an inconsistent model evaluates to no messages at all, and a written artifact full of empty message lists would read as "nothing fired" — a wrong answer that looks like an answer. The gate is `KernelConsistencyOracle`, the same oracle `rule check` reports `KERNEL_CONFIRMED` from, so anything the probe accepts is something `rule check` accepts.

## 6. Producer and consumer responsibility

a12-dmkits owns kernel invocation, engine identity, and the artifact schema and its `schemaVersion`. A consumer owns pinning the bytes and replaying them. An artifact carries its own version header, so artifacts already produced stay valid evidence if the capability is later retired — at which point a runtime question returns to a cross-project experiment request.

## 7. Scope: what it deliberately is not

- **Not a CLI verb**, and never a change to the native profile, `Cli.JVM_ONLY_VERBS`, the Groovy exclusion, or the retired `--kernel` flag (§1).
- **Not an authoring surface.** No inline rule or computation injection: that would be a second way to spell a rule beside the typed builder and `rule-add.schema.json`, and the probe's input is a finished model.
- **Not a gate.** It asserts nothing, is absent from `check`, and its outputs are never cached.
- **Not new semantics.** It reports what the kernel reports.
