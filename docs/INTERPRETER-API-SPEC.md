# Interpreter public API spec

## Status and ownership

This is the stable, implemented contract for the public Kotlin Multiplatform interpreter API and its authored TypeScript façade. The evaluator design and semantic boundary live in [INTERPRETER-SPEC](INTERPRETER-SPEC.md); implementation structure lives in [INTERPRETER-ARCHITECTURE](INTERPRETER-ARCHITECTURE.md); API work that is not built lives only in [INTERPRETER-API-GAPS](INTERPRETER-API-GAPS.md).

The exact published declarations are mechanically locked by the reviewed ABI files:

- [Kotlin/JVM ABI](../interpreter/api/interpreter.api)
- [Kotlin/JS KLIB ABI](../interpreter/api/interpreter.klib.api)
- [TypeScript ABI](../interpreter/api/typescript.api)

Those files own the complete member inventory. This spec owns the semantic contract: what the objects mean, which boundaries are strict, and which guarantees a consumer may rely on.

The base artifacts are clean-room, kernel-free, and MIT-licensed. They do not import, link, reflect over, execute, or bundle an A12 Kernel artifact.

## 1. Product boundary

The public API implements this complete flow:

```text
raw DM-JSON sources
    │
    ├── ModelWorkspace.expand(entry) ──► self-contained expanded DM-JSON
    │
    └── ModelWorkspace.prepare(entry)
              │
expanded DM-JSON ── PreparedModel.loadExpandedJson
              │
              ▼
        PreparedModel
          ├── model metadata and pointers
          ├── Document codec
          └── configured Interpreter
                    ├── validateFull
                    ├── validatePart
                    └── compute
```

TypeScript and browser/Node JavaScript use `@mbackschat/a12-interpreter`. Kotlin/JVM and Kotlin/JS consume the corresponding Kotlin Multiplatform publications. The projections share one `commonMain` preparation and evaluation implementation; platform façades adapt host asynchronous and collection types but do not implement evaluator semantics.

The public API does not perform filesystem, URL, credential, database, or implicit network I/O. Callers supply model text through a finite source collection or a `ModelSourceProvider`.

## 2. Workspaces and model preparation

### 2.1 Finite sources

`ModelWorkspace.fromSources` admits a finite immutable collection of `WorkspaceModelSource` values. Each source contains raw DM-JSON plus optional expected SHA-256 integrity and a sanitized diagnostic label.

Admission:

- parses strict UTF-8 JSON objects;
- indexes sources by exact `header.id`;
- rejects duplicate ids rather than selecting by source order;
- independently enforces model-count, per-model byte, aggregate-byte, structure, graph, and prepared-output limits;
- verifies an expected digest before model content can steer dependency discovery;
- retains no caller-owned mutable source collection.

The workspace is an immutable snapshot. Repeated `prepare` or `expand` calls perform preparation again; the workspace does not hide a cache, provider, virtual filesystem, or mutable resolver.

### 2.2 Provider collection

`ModelWorkspace.collect` starts with one exact entry id, asks a caller-owned `ModelSourceProvider` for discovered dependencies, and freezes the collected closure into the same finite workspace used by `fromSources`.

Collection guarantees:

- deterministic sequential discovery;
- at most one provider load per exact model id;
- only references that preparation would follow are requested;
- every call receives the remaining byte ceiling and cancellation signal;
- provider results are independently checked for size, integrity, and requested-versus-returned model identity before their edges are followed;
- no implicit retry, batching, caching, write, watch, or provider lifecycle;
- typed failures retain a stable reason and cause without copying provider messages or URLs into public diagnostics;
- `snapshotRevision` is opaque provenance and does not affect prepared-model identity.

TypeScript projects collection as `Promise<ModelWorkspace>` with `AbortSignal`. Kotlin uses suspension without a coroutine-library dependency. Java uses a `CompletionStage` projection whose cancellation reaches the common collection signal and the in-flight provider stage.

### 2.3 Expansion versus evaluator preparation

`expand` runs admission, typedef/import resolution, include mounting, exclusion handling, id normalization, and reference remapping, then returns the self-contained DM-JSON. It deliberately stops before evaluator-only support checks.

`prepare` runs the same assembler and then loads the result through the strict evaluator boundary. It may therefore refuse a model that is structurally expandable but uses an evaluator capability outside the supported set.

`PreparedModel.loadExpandedJson` is the explicit fast path for already-expanded input. It never guesses through unresolved includes, typedef residue, opaque element identities, or malformed element structure.

Each workspace `expand`/`prepare` and expanded-input load operation has a non-throwing `try...` peer. TypeScript projects those peers as `A12Result`; Kotlin uses the corresponding typed preparation or expansion result family.

### 2.4 Diagnostics and identity

Preparation failures are `ModelPreparationError` values carrying one or more structured `ModelPreparationDiagnostic` records. Stable codes and structured locations are the contract; provider exception text, filesystem paths, URLs, and secrets are not.

A prepared model exposes:

- its exact entry model id;
- distinct resolved dependency edges in deterministic first-discovery order;
- a deterministic fingerprint over the selected entry, resolved source closure, preparation mode, and preparation-contract version.

The fingerprint identifies prepared content. It does not make process-local owner tokens portable, and caller provenance such as `snapshotRevision` does not enter it.

## 3. Model ownership and pointers

Each `PreparedModel` creates one unforgeable owner domain. Documents, model metadata, and pointers created by that owner may interoperate; objects from another preparation do not, even when both preparations have the same model id and fingerprint.

Passing a foreign or stale model-owned object fails before evaluation with `ModelOwnerMismatchError`. TypeScript brands may aid static checking, but Kotlin-owned runtime tokens enforce authenticity.

`ModelMetadata.pointer` creates a concrete `DocumentPointer` after checking that the path names a declared group or field and that the coordinate shape is valid. `relevantPointer` additionally accepts the explicit `"ALL"` repetition selector used by partial validation.

Coordinates are positive signed 32-bit integers. A field pointer's terminal coordinate is always `1`; this is a measured Kernel document-conversion boundary rather than a repetition maximum. Group coordinates above a declared maximum remain representable so formal validation can report them.

## 4. Document transport

### 4.1 A12's canonical Document JSON — the one shape

The boundary reads and writes **A12's own Document JSON**, the shape the Kernel's `IDocumentV2Serializer` produces and consumes. It is not a shape this project designed; the format is source-verified against Kernel 30.8.1 and recorded in [KERNEL-FINDINGS](KERNEL-FINDINGS.md) "The canonical Document JSON and `DocumentPointer` string". The standing rule is [CLAUDE.md](../CLAUDE.md) "NEVER invent a shape A12 already defines".

A document is a **nested object tree mirroring the model**:

```ts
type A12DocumentField = string | number | boolean | null;

interface A12DocumentGroup {
  readonly [name: string]:
    | A12DocumentField
    | A12DocumentGroup
    | readonly A12DocumentGroup[];
}

type A12Document = A12DocumentGroup;
```

- a **non-repeating group** is a nested object; a **repeating group is an array** whose index *is* the repetition;
- an **unfilled row** inside that array is `{}`, and a present-but-empty group is `{}`;
- an **omitted entity is a missing key**; a **`null`** keeps the field present with no value (the Kernel's `FieldInstanceV2.ofValue(null)`);
- a field carries **native JSON for number and boolean**, a **model-format string** for the date family, and the **S-value** for an enumeration — the reader stringifies every value and then parses model-driven, because only the model disambiguates a string from a date;
- a value that fails conversion is **kept, not dropped** (the Kernel's `[A12K-3638]` pass-through), so malformed input is representable;
- a root-level `id` is the serializer's document identity, not a model field; it is read into `DocumentSnapshot.documentId` and written back as text.

Kotlin, Java, and native callers pass the JSON **text**; TypeScript accepts either a plain object or its text.

### 4.2 Reading is as permissive as A12's reader; writing is canonical

`DocumentV2JsonReader.repetitionsFromJson` never consults repeatability, so **read** accepts an object at a repeating group (one row at repetition 1) and an array at a *non-repeating* group (rows 1..n) — which keeps an over-repetition readable so formal validation can report it. **Write** is model-driven: array versus object comes from *declared* repeatability, never from what happens to be populated, and direct fields precede subgroups with each group sorted by name (the Kernel writer's key order).

One encode reaches the canonical fixed point: `encode(read(encode(read(x)))) == encode(read(x))`. Every row, empty cell, and unconverted raw value survives the round trip. The one document state A12's writer has no shape for — a non-repeatable group row above 1 — raises `DocumentEncodeError` naming the canonical pointer rather than silently dropping the row.

### 4.2a Reading is VALUE-NORMALIZING, exactly as A12's is

A12's document stores `String | Boolean | BigDecimal | Instant | InstantRange` and re-renders every consumer's text from that stored **value**, so an authored spelling does not survive ingestion. Reading reproduces that on the stored text, per kind:

| kind | authored | stored after `readDocument` |
|---|---|---|
| NUMBER | `1e5`, `1.5e1`, `"+5"`, `"007"`, `-0.0` | `100000`, `15`, `5`, `7`, `0.0` — `BigDecimal.toPlainString()`, over A12's own converter grammar (broader than JSON's: a leading `+`, a bare `.5`, leading zeros) |
| NUMBER, `leadingZerosAllowed` | `"007"` | `007` — A12's one number case that keeps the authored text |
| BOOLEAN | `"TRUE"`, `"False"` | `true`, `false` (`equalsIgnoreCase`) |
| CONFIRM | `"TRUE"` / anything else | `true` / verbatim — a confirm has no false, so even a native JSON `false` is a pass-through |
| DATE, DATE_TIME, TIME, DATE_RANGE | any | verbatim — A12 parses non-leniently, so a non-canonical spelling *fails* conversion and a spelling that parses already is the model format |
| STRING, Enumeration | any | verbatim |
| any typed kind | `""` | the **empty cell**, written back as `null`; a STRING keeps its `""` |

A value the converter cannot parse stays verbatim and is written back as a JSON string — A12's own `[A12K-3638]` pass-through, which also guarantees a malformed value can never produce invalid JSON. One deliberate difference: an absurd exponent (`1e999999999`) is *not* expanded — A12's `toPlainString` has no ceiling, and at a public ingress that is a twelve-character denial of service, so such a value takes the pass-through instead.

The measurement is `adapter … A12JsonIngressKernelTest` (the kernel's own ingress) and `A12JsonIngressDiffTest` (ours against it, with the exhaustive characterized residual).

### 4.3 Strict reading

`PreparedModel.documents.readDocument` validates the complete text before any document state exists:

- applies hard-capped `DocumentDecodeLimits` (document text size first, then group rows, field cells, path length, per-field and total raw text, model nesting depth) before expensive allocation;
- rejects unparseable JSON and a non-object root;
- checks that every key names a declared field or group and that its JSON node type can carry that entity;
- aggregates model-aware diagnostics and performs no partial document mutation when validation fails.

Every input defect A12 reports as an error-ranked notification is **fatal** here; the Kernel's report-and-continue is a notification policy of its caller, not a difference in the shape. `DocumentInputError` diagnostics locate each defect by its canonical A12 `DocumentPointer` string plus the model path, the declared entity kind, and the JSON node type found. `tryReadDocument` returns the same failure through `A12Result` on TypeScript.

`encodeDocument` checks owner identity before writing. `isSnapshot` recognizes only authentic snapshots owned by that prepared model.

### 4.4 The model-level codec pair — for a consumer that holds a `ModelDef`

`PreparedModel.documents` is the **blessed** boundary: owner-bound, snapshot-returning, and the carrier of the root `id`. Beneath it, two functions expose the same codec one level down, at the level of the already public `ModelDef.newDocument()`:

```kotlin
fun ModelDef.readDocumentJson(json: String, limits: DocumentDecodeLimits = DocumentDecodeLimits.defaults()): Document
fun Document.toDocumentJson(model: ModelDef): String
```

Same shape, same value normalization, same ceilings, same diagnostics — it is one implementation, not a second one. The pair carries **no document identity**: a root `id` is still *validated* on read (never reported as an unknown path) but nothing at this level can keep it, so a consumer that needs it uses the owner-bound surface. `toDocumentJson` emits **compact** JSON deliberately, because A12 itself takes the pretty-printer as caller configuration (`DocumentSerializationConfig.prettyJsonPrinter`) rather than as part of the format.

It exists for a consumer that already works in `ModelDef`/`Document` — `dmtool`'s runtime verbs and `model seed` do — so that consumer is not tempted to write its own reader or writer for a shape A12 defines. Adding it removed three such duplicates (dmtool's instance walker and its seed sink, plus a near-identical sink in the adapter's performance-fixture guard). Locked by `interpreter … DocumentJsonCodecTest`; the CLI end of it by `cli … DocumentInstanceIngressTest`.

## 5. Interpreter lifecycle and evaluation

`PreparedModel.createInterpreter` creates an active evaluator with an optional immutable `InterpreterConfiguration`. A prepared model contains no request document or callback instance. A document snapshot is immutable from the public surface.

Callers may retain one prepared model and interpreter and validate successive document snapshots, which is the ordinary dynamic-form use case. Model parsing, executable preparation, and bounded model-owned caches are reused; each validation or computation call owns its transient evaluation state and detached result.

The v1 API provides:

- `supportReport()` for configuration-aware model support without evaluating a document;
- `validateFull(snapshot, options?)`;
- `validatePart(snapshot, relevant, options?)`;
- `compute(snapshot, options?)`.

Validation returns detached `ValidationFinding` records with code, rule path, error path, severity, value/omission type, rendered text, referenced paths, and fill-to-fix paths. `noErrorOccurred` depends on error severity, while `fullySupported` is independent and is false whenever an admitted program could not be evaluated.

Computation returns detached field-pointer outcomes of `"value"`, `"cleared"`, or `"errored"` plus the same explicit unsupported axis. The public convenience methods never silently discard unsupported programs.

Partial validation preserves the Kernel-grounded relevance contract:

- caller-selected concrete or `"ALL"` repetitions define the relevant subset;
- global fields are added by the runtime boundary;
- formal errors and three-valued relevance can suppress rule errors;
- a passing partial result does not imply that full validation passes.

## 6. Customization

`InterpreterConfiguration` accepts explicit name-to-callback maps for custom field types and custom conditions. Binding resolves every model-used custom name before evaluation and keeps callback instances scoped to that interpreter.

Custom field validators are synchronous and receive:

- the raw stored value;
- call locale;
- effective model length bounds;
- `isDisplayValue = false` for stored-document validation.

They return accepted or a project-owned error key plus optional message. The evaluator does not add a second generic validation around a declared custom type.

Custom conditions are synchronous Boolean error conditions: `true` means the owning rule fires. Each invocation receives a read-only document view, full-versus-partial relevance, formally incorrect fields, and the concrete error pointer.

Thrown callbacks become `InterpreterIntegrationError` with phase and stable reason. Recursive same-lane evaluation and thenable returns fail explicitly; the evaluator never implicitly awaits a callback or corrupts the active call.

The implemented core API is intentionally Kernel-shaped only in semantic jobs such as `validateFull`, `validatePart`, and `compute`. It does not currently export the A12 Kernel TypeScript interface names, nested `Document` shape, global registration services, or result objects. That later compatibility product is tracked in [INTERPRETER-API-GAPS](INTERPRETER-API-GAPS.md#iag1--kernel-shaped-typescript-import-replacement).

## 7. Compatibility and evidence

The interpreter matches the normative dynamic-Groovy Kernel strategy and also static Java wherever those strategies agree. Legal strategy splits follow the ratified oracle in [INTERPRETER-SPEC](INTERPRETER-SPEC.md): dynamic Groovy is normative and the static-Java result is recorded, never adopted.

The public boundary is locked by:

- common JVM and Node behavior tests;
- real-Kernel differential tests in `:adapter`;
- Kotlin/JVM, Kotlin/JS, and strict TypeScript external consumers;
- reviewed JVM, KLIB, and TypeScript ABI goldens;
- package-content, dependency, source-map, secret, and checksum guards;
- packed-package execution in Node and system Chrome.

Browser support is qualified by packed-package semantic execution plus the owner-ratified first-answer release bar (IF224/IF225). IF226 records anonymous public-GitHub delivery.

## 8. Explicit exclusions from the implemented API

The v1 public API does not include:

- persistent document editing or a stateful form session;
- direct typed A12 scalar/operator namespaces;
- candidate rule, expression, or computation preparation;
- a model-version registry, interpreter pool, Worker coordinator, or service lifecycle;
- implicit filesystem/network providers, a virtual filesystem, retry/backoff, watches, or batch loading;
- Kernel-generated JavaScript execution;
- Kernel interface types or a Kernel drop-in compatibility claim.

These are not hidden implementation promises. Feasible later products and their re-open triggers are recorded in [INTERPRETER-API-GAPS](INTERPRETER-API-GAPS.md); the historical end-state interview record is preserved in the [archived API proposal](archived/INTERPRETER-API-PROPOSAL.md).
