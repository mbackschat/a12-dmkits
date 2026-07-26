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
          ├── placement document codec
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

## 4. Placement document transport

### 4.1 Versioned lossless envelope

`PlacementDocumentInputV1` is the canonical portable document boundary:

```ts
interface PlacementDocumentInputV1 {
  readonly version: 1;
  readonly groups: readonly GroupPlacementInput[];
  readonly fields: readonly FieldPlacementInput[];
}

interface GroupPlacementInput {
  readonly path: string;
  readonly coordinates: readonly number[];
}

interface FieldPlacementInput {
  readonly path: string;
  readonly coordinates: readonly number[];
  readonly raw: string;
}
```

The flat placements preserve:

- absent versus present-empty fields;
- malformed but present raw field text;
- explicit empty and sparse group rows;
- one coordinate per path part;
- source placement order, with canonical ancestor rows made explicit by encoding.

Decode followed by encode reaches a fixed point after the first encode because field insertion materializes its required ancestor group rows.

### 4.2 Strict decoding

`PreparedModel.documents.decodePlacements` accepts `unknown` on TypeScript and validates the complete envelope before constructing a `DocumentSnapshot`. `readPlacements` is the typed fast path over the same validator; it is not a trust bypass.

Decoding:

- checks the version and rejects unknown members;
- applies hard-capped `DocumentDecodeLimits` before expensive allocation;
- checks known model paths, entity kind, coordinate arity, the portable coordinate range, and the field-terminal coordinate rule;
- rejects duplicate and conflicting placements;
- aggregates model-aware input diagnostics;
- performs no partial document mutation when validation fails.

`DocumentInputError` carries structured input paths plus optional model paths and placement/coordinate positions. `tryDecodePlacements` returns the same failure through `A12Result`.

`encodePlacements` checks owner identity and emits the lossless V1 representation. `isSnapshot` recognizes only authentic snapshots owned by that prepared model.

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

Browser support is qualified by packed-package semantic execution plus the owner-ratified first-answer release bar (IF224/IF225). Anonymous public-GitHub delivery remains IG85.

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
