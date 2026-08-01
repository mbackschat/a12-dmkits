# Interpreter public API — gaps

> **What this is.** This is the `-GAPS` tracker for the public interpreter product family. The stable implemented contract lives in [INTERPRETER-API-SPEC](INTERPRETER-API-SPEC.md); evaluator semantics and implementation gaps remain in [INTERPRETER-SPEC](INTERPRETER-SPEC.md) and [INTERPRETER-GAPS](INTERPRETER-GAPS.md). This ledger records only open, deferred, and explicitly excluded public-API work.
>
> **Lifecycle.** A shipped gap is deleted. Its stable contract graduates into [INTERPRETER-API-SPEC](INTERPRETER-API-SPEC.md) in the same commit, while git and an appropriate numbered finding retain the history and rationale.

> **Current state.** The public v1 surface, browser qualification, anonymous publication, and computation diagnostics are complete as IF224–IF226 and IF241. §1 carries three rows, all on the same seam: whether the published pointer surface is A12's *exact* type or its *partially-known* one (IAG10), whether the house rendering earns a published renderer (IAG11), and what `A12PointerFormat`'s accepted repetition domain actually is (IAG12) — the last of which gates npm publication. §2 tracks later API products.

## 1. Open

### IAG10 — a typed pointer on the MESSAGE channel must model A12's partially-known type, not our exact one <a id="iag10"></a>

A recurring suggestion for the pointer-form question ([CG5](CLI-CONFORMANCE-GAPS.md#cg5)) is to stop publishing a string and expose a **typed** pointer that can render either spelling on demand. Reaching for the type we already have would break the project's #1 interop rule, and this row exists so that is decided before someone does it.

`A12Pointer` (`interpreter … A12PointerFormat.kt`) documents itself as **exact** — "This type models the EXACT pointer only, and the distinction is load-bearing" — and its position rule for `[0]` is the exact pointer's. A12's message channel is a **different type**: kernel `IMessage` returns `PartiallyKnownDocumentMultiPointer`, carrying `fullName()` plus `repetitionIndexes()` and admitting wildcard/unknown indices, and A12 publishes that structure rather than two string properties. So a typed message pointer is `PartiallyKnownDocumentMultiPointer`-shaped, not `A12Pointer`-shaped, and reusing the exact type would be inventing a shape where A12 has one — precisely what CLAUDE.md's first interop rule forbids, and the same class as the retired `DocumentInputV1` placement transport.

**The live tension that makes this concrete, and it is ours:** `A12Pointer` calls itself exact yet accepts `UNKNOWN_REPETITION` (`-5`, `IIdentifier.UNKNOWN`) **at any part**, because the kernel renders such pointers itself (`…/Demand[-5]/Headcount`) and we must read them back. That acceptance is deliberate and source-verified against kernel 30.8.1 — it is *not* a defect in current behaviour — but it means the type already straddles exact and partially-known, which is exactly the seam a typed-message proposal has to resolve rather than paper over. Note the near-miss history: this file previously called `0` "the wildcard" while enforcing the exact rule, and that vocabulary borrow led a findings entry to declare a correct, test-locked refusal a defect.

**What settles it:** before any typed-pointer surface is designed, state which A12 type each published channel corresponds to — exact `DocumentPointer` for model/instance addressing, `PartiallyKnownDocumentMultiPointer` for message addressing — and cite the kernel counterpart for each. Only then is the rendering question answerable. Raised 2026-08-01 by a review of the pointer-form decision.

  **Widened 2026-08-01 — no longer hypothetical, and it now GATES npm publication.** The row was written about a *future* typed pointer; the same day, `A12Pointer` was exported from the npm package, so the undecided type is on a published surface. A second review executed the packed consumer and read the kernel source: the interface's own doc comment says **exact**, yet `parse("Order/Demand[-5]/Headcount")` yields `{names, repetitions:[1,-5,1]}` — a value A12's exact `DocumentPointer` constructor would refuse, while A12's message-channel owner (`PartiallyKnownDocumentMultiPointer`) models the same information as `fullName()` plus `repetitionIndexes()` and admits both the wildcard `0` and the unknown `-5`. So the exported shape currently matches **neither** A12 type exactly, which is the interop rule's failure mode rather than a naming quibble. **Decide before the package is published**: either the surface is exact (and `-5` needs its own channel or type) or it is partially-known (and it should carry A12's structure and be named for it). The npm export is committed but unpublished, so this is still cheap; after a publish it is a breaking change to a released package. Note what is *not* at issue: the accepted domain is enforced identically on both entry points — the question is whether that domain is the right one to publish, not whether it is applied consistently.

### IAG11 — the house pointer form has no published renderer <a id="iag11"></a>

The reports emit the interpreter's house pointer form in `ValidationFinding.errorPath`, `referenced`, and `fillToFix` (`/Order[1]/Items[2]/Count`), and the conformance corpus and the `:adapter` differential use that same string as their shared comparison key. Nothing renders it: `eval.formatPointer` is in an engine package the ABI filter excludes, so no target — JVM, Kotlin/JS, or npm — publishes a way to *produce* it from a `path` plus `coordinates`. `A12PointerFormat` renders A12's canonical form, which omits `[1]`, and therefore cannot.

**What it blocks, concretely:** `consumer-tests/browser/src/qualification.ts` replays the committed corpus through the *published* surface only, so it must build the corpus key itself. Exporting `A12PointerFormat` to npm removed the parsing and the legality checks from that local helper — `corpusPointerKey` now derives both from the published utility — but the final join is still local, because no published call renders it. This was originally read as "the clone reproduces the published utility"; it does not. The clone reproduces the house form, which is a *different* rendering that happens to be unpublished.

**Why it is not simply "publish it":** the two renderings' convergence is an open owner decision ([CG5](CLI-CONFORMANCE-GAPS.md#cg5)). Adding a second published renderer commits the released package to the house form as a first-class exported concept, and if the forms converge it becomes dead API on a shipped surface. The cheap alternative — canonicalizing both sides of the corpus comparison — was rejected because it would make the browser harness's key differ from the JVM/Node harnesses' and print pointers that no longer match the corpus files a reader is diffing against.

**What would settle it:** the convergence decision. If the forms converge, this gap closes with them. If they stay separate, the house form earns a published renderer beside `A12PointerFormat` on all three surfaces, and the browser helper is deleted. Opened 2026-08-01 while exporting `A12PointerFormat` to npm, as **IAG10**; renumbered to IAG11 the same day after a review found that id already taken by the row above — see "Retired ids" below.

## Retired ids

Every `IAG<n>` ever issued that has no live row above, so a citation of one resolves here and **no number is ever reused**. `GapLedgerTemplateGuardTest` enforces both halves: a live id may not appear in this list, and a new row's number must exceed every number in either list. Reuse is what made this section necessary — `IAG9` named two unrelated gaps at different times, and six live citations across the repo could not be told apart.

| id | what it meant | disposition |
|---|---|---|
| IAG9 | the native public computation report should retain the Kernel's two explanatory error channels while keeping validation's public finding projection | shipped 2026-07-31 → [IF241](INTERPRETER-FINDINGS.md#if241); locked by `adapter … laws/PublicComputationDiagnosticsDiffTest` |
| IAG9 *(reused — the defect this section exists to prevent)* | `A12PointerFormat` was published on Kotlin/JVM and Kotlin/JS but not exported from the npm package | shipped 2026-08-01; the export, its bridge, and `typescript.api` landed together. Cited as "IAG9" in `INTERPRETER-API-SPEC` and `CLI-CONFORMANCE-GAPS` until both were corrected to name the work instead of the number |

## 2. Deferred — with re-open triggers

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
