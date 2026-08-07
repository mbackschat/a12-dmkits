# Interpreter public API — gaps

> **What this is.** This is the `-GAPS` tracker for the public interpreter product family. The stable implemented contract lives in [INTERPRETER-API-SPEC](INTERPRETER-API-SPEC.md); evaluator semantics and implementation gaps remain in [INTERPRETER-SPEC](INTERPRETER-SPEC.md) and [INTERPRETER-GAPS](INTERPRETER-GAPS.md). This ledger records only open, deferred, and explicitly excluded public-API work.
>
> **Lifecycle.** A shipped gap is deleted. Its stable contract graduates into [INTERPRETER-API-SPEC](INTERPRETER-API-SPEC.md) in the same commit, while git and an appropriate numbered finding retain the history and rationale.

> **Current state.** The public v1 surface, browser qualification, anonymous publication, computation diagnostics, and the two owner-shaped pointer types are complete as IF224–IF226 and IF241–IF242. The house spelling remains an internal tool/corpus projection rather than a second published A12 format. §1 is drained; §2 tracks later API products.

## 1. Open

_None open._

## 2. Deferred — with re-open triggers

### IAG14 — per-entry cost surface for bounding a model cache in bytes <a id="iag14"></a>

NFR-F3's numeric half is unquantified because a bounded cache cannot be sized by entry count: a warm entry measures
5.8×–9.7× its expanded DM-JSON across five real models and grows on both the field and rule axes at roughly
1.6 kB each, so the ratio moves with shape ([INTERPRETER-PERFORMANCE](INTERPRETER-PERFORMANCE.md) "Per-model
resident cost"). The demo makes the
consequence concrete: a cache one entry too small turned 12 requests into 12 preparations and 10 evictions, so an
undersized cache is not slightly worse but no cache at all.

**Shape under consideration:** expose the cost INPUTS a service can calibrate against — field, rule and
computation counts plus expanded source bytes — rather than a retained-byte estimate, which is not portable
across JVM and JS and would be worse than none if fabricated.

**Re-open trigger:** a real backend service needs to bound its hot-model cache, or NFR-F3's numeric half is put
up for certification.

### IAG1 — Kernel-shaped TypeScript import replacement <a id="iag1"></a>

An existing TypeScript application should be able to replace its A12 Kernel imports with a separately packaged interpreter adapter while retaining its document, validation, computation, result-handling, and custom-extension code.

The target source migration is:

```ts
// Before
import {
  DocumentRtServiceFactory,
  DocumentRtCustomExtensionService,
  type ICustomConditionFactory,
  type ICustomFieldTypeFactory,
} from "@com.mgmtp.a12.kernel/kernel-md-facade";

// After
import {
  DocumentRtServiceFactory,
  DocumentRtCustomExtensionService,
  type ICustomConditionFactory,
  type ICustomFieldTypeFactory,
} from "@mbackschat/a12-interpreter-kernel-compat";
```

The adapter must reproduce the pinned Kernel 30.8.1 public TypeScript shapes that form the candidate compatibility surface:

- `Document`, `EntityInstancePath`/`DocumentPath`, `DocumentRtService`, validation/computation results, and messages;
- `validateFull`, `validatePart`, `compute`, and computation application;
- `GeneratedCodeRtConfig`;
- `ICustomCondition`, `ICustomConditionFactory`, `ICustomFieldValidator`, and `ICustomFieldTypeFactory`;
- the helper factory functions;
- per-service custom factories;
- deprecated global `DocumentRtCustomExtensionService` registration, with first-registration-first lookup and per-service factories taking precedence.

Existing custom field and custom condition implementations must run unchanged. The adapter preserves the Kernel callback document/path shape, model id, relevance set, formal-error paths, value overrides, error pointer, synchronous lifecycle, and `true`-means-error polarity.

The construction seam cannot consume existing Kernel-generated validator JavaScript. That script contains compiled validator/calculator behavior, while its public metadata does not expose the original rule and computation expressions needed by the interpreter. Executing or translating the generated evaluator would violate the kernel-free clean-room boundary.

The migration therefore also supplies either the raw/expanded DM-JSON workspace or an interpreter-specific model bundle. The honest target is imports plus one model-initialization change; preserving `GeneratedCodeAccessorFactory.createScriptAccessor(kernelGeneratedJs)` by assigning unrelated semantics to it is excluded.

Package, host, and license: a separate EUPL-1.2 compatibility artifact, tentatively `@mbackschat/a12-interpreter-kernel-compat`, hosted as an npm-compatible tarball by the public interpreter release repository rather than npmjs.org. The MIT core neither imports nor depends on it.

**Current evidence boundary:** the interface and registration semantics above were verified from the authoritative sibling Kernel TypeScript source. The local checkout has no built `lib/` output, and the existing target application's actual import set has not yet been inventoried, so no source-compatibility or drop-in claim is made.

**Re-open trigger:** the MIT core is publicly available and the named existing TypeScript project is ready for substitution. Before implementation, inventory that project's actual top-level and deep imports and confirm whether runtime delivery includes raw DM-JSON, expanded DM-JSON, or only generated JavaScript.

**Acceptance:** changing imports plus the explicit model-initialization seam compiles the real project without modifying its document, validation/computation, result, or custom-extension implementations; behavior is differentially locked against the pinned Kernel contract on every used method; the compatibility package contains no Kernel implementation or generated-model bytecode.

### IAG2 — JVM `IDocumentRtService` drop-in <a id="iag2"></a>

A separate JVM-only EUPL-1.2 artifact implements the complete pinned Kernel 30.8.1 `IDocumentRtService` contract and delegates evaluation to the MIT interpreter core. It preserves `DocumentV2`, `DocumentProcessingConfig`, full/partial validation, computation/application, precompile/cache behavior, model-code identifiers, custom extensions, results, and required deprecated overloads.

The artifact is not the broad internal `:adapter`, contains no Kernel bytecode, and keeps Kernel API/runtime dependencies host-provided.

**Re-open trigger:** the core `v0.9.1` release is public and a real JVM host is available for unchanged substitution. The release-program R10 gate owns its build and publication sequence.

### IAG3 — richer document values, storage, inspection, and persistent editing <a id="iag3"></a>

The archived vision includes typed A12 scalar values, typed document writes, direct lossless storage reads, complete formal inspection, display/internal conversion, row enumeration, a mutable builder, and a persistent structural-sharing editor suitable for form state.

The A12 Document JSON transport and immutable `DocumentSnapshot` deliberately precede this layer. No stateful editor, hidden mutable session, or public cache contract is inferred from repeated validation.

**Re-open trigger:** a real form or import consumer needs programmatic editing or typed reads that cannot be expressed cleanly as Document read/encode. The first increment must identify one consumer workflow and prove that its structural sharing and cache retention improve repeated-edit validation without leaking owner state.

### IAG4 — stored, targeted, and candidate program APIs <a id="iag4"></a>

The evaluator internally supports stored single-rule/single-computation work, but the v1 public package exposes only full/partial validation and whole-model computation. Whole-model compute-and-apply is shipped as `ComputationReport.applyTo`/`Interpreter.applyComputations`, and an application composes compute → apply → validate directly. Stored-rule outcome details, targeted field computation, prepared candidate rules, symbolic expressions, candidate computations, and direct program handles remain unbuilt.

**Re-open trigger:** a rule preview, debugger, authoring tool, or expression consumer requires one of these operations. Implement only the demanded program kind and give it a model-owned prepared handle, typed preparation diagnostics, unsupported reporting, and exact differential evidence.

### IAG5 — registered services, pools, version switching, and Worker coordination <a id="iag5"></a>

The archived vision describes caller-owned model registration, aliases, version switching, bounded warm interpreter pools, backpressure, lifecycle, metrics, and a Node Worker coordinator. The core intentionally exposes prepared models and active interpreters instead of embedding that deployment policy.

**Re-open trigger:** a multi-model server or Worker application demonstrates that direct ownership is insufficient and supplies concrete concurrency, cancellation, capacity, and lifecycle requirements. Do not build a general registry or pool from hypothetical load.

### IAG6 — broader metadata and application-owned annotations <a id="iag6"></a>

The v1 `ModelMetadata` creates checked model-owned pointers but does not publish the complete traversable group/field/type/rule/computation graph or an application-metadata attachment layer.

**Re-open trigger:** a consumer needs model navigation or model-associated data without re-reading DM-JSON. Add only the metadata required by that consumer, keeping evaluation caches and caller metadata separate.

### IAG7 — broader evaluation configuration and conversion helpers <a id="iag7"></a>

The v1 configuration surface binds custom field and condition callbacks and accepts per-call locale. The archived vision additionally proposed an injected evaluation clock, legal-charset and predefined-type configuration, evaluation-time capture, custom field display/internal conversion, optional-date resolution, and direct formal document inspection.

Document inspection and typed value conversion overlap [IAG3](#iag3--richer-document-values-storage-inspection-and-persistent-editing); their implementation belongs to one document/value increment rather than parallel helper APIs. The clock is also the missing host input behind the one explicit-clock conformance case intentionally excluded from the current TypeScript browser façade.

**Re-open trigger:** a consumer must control time deterministically, reuse predefined types, or perform display/internal conversion through the public package. Implement only the required configuration input and its immediately consumed operation, then lock JVM, Node, and browser projection parity.

### IAG8 — callback-only Kernel extension compatibility adapters <a id="iag8"></a>

Separate TypeScript and JVM compatibility artifacts may adapt existing Kernel `ICustomFieldTypeFactory` and `ICustomConditionFactory` implementations into the interpreter's native registries without exposing Kernel documents or runtime-service interfaces. These are narrower than [IAG1](#iag1--kernel-shaped-typescript-import-replacement) and [IAG2](#iag2--jvm-idocumentrtservice-drop-in): they translate callbacks only and keep validation/computation on the MIT public API.

The adapters preserve factory lookup and lifecycle, stored/display mode, effective length bounds, custom-field localization and conversion, condition document/path views, relevance, formal-error entities, error pointer, value overrides where the source interface exposes them, exception behavior, and `true`-means-error polarity. They add no operator, function, computation, or migration extension point not present in the pinned Kernel evaluator contract.

**Re-open trigger:** a real TypeScript or JVM consumer wants the interpreter API but has existing Kernel-compiled custom field or condition implementations it cannot reasonably rewrite. Inventory the exact interfaces and implementations first; compile and differential evidence must use those real extensions unchanged.

**Acceptance:** the compatibility artifact is separately licensed EUPL-1.2, contains no Kernel implementation bytecode, performs no Kernel evaluation, adds no adapter work to the no-customization path, and preserves every used callback's observable invocation and result behavior.

## 3. Out of scope

- **Kernel-generated evaluator execution or translation.** The interpreter remains clean-room and kernel-free.
- **Implicit I/O or a virtual filesystem.** Hosts implement source acquisition through `ModelSourceProvider`; the core sees a finite immutable closure.
- **Automatic retry, backoff, watching, provider lifecycle, or parallel prefetch.** Add only against measured host requirements.
- **The direct Operator API.** It is an independently selected later product governed by [OPERATOR-API-PROPOSAL](OPERATOR-API-PROPOSAL.md), not an interpreter prerequisite.
- **Zod integration.** It remains an optional adapter product governed by [ZOD-INTEGRATION-PROPOSAL](ZOD-INTEGRATION-PROPOSAL.md).
