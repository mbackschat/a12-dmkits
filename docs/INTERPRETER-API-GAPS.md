# Interpreter public API — gaps

> **What this is.** This is the `-GAPS` tracker for the public interpreter product family. The stable implemented contract lives in [INTERPRETER-API-SPEC](INTERPRETER-API-SPEC.md); evaluator semantics and implementation gaps remain in [INTERPRETER-SPEC](INTERPRETER-SPEC.md) and [INTERPRETER-GAPS](INTERPRETER-GAPS.md). This ledger records only open, deferred, and explicitly excluded public-API work.
>
> **Lifecycle.** A shipped gap is deleted. Its stable contract graduates into [INTERPRETER-API-SPEC](INTERPRETER-API-SPEC.md) in the same commit, while git and an appropriate numbered finding retain the history and rationale.

> **Current state.** The public v1 surface, browser qualification, anonymous publication, and computation diagnostics are complete as IF224–IF226 and IF241. §1 carries one row on the pointer seam: whether the interpreter's house rendering earns a published renderer (IAG11). Which A12 type each addressing concept corresponds to was decided on 2026-08-01, and the message channel now carries it — the contract is in [INTERPRETER-API-SPEC](INTERPRETER-API-SPEC.md) "The two pointer types". §2 tracks later API products.

## 1. Open

### IAG11 — the house pointer form has no published renderer <a id="iag11"></a>

The reports emit the interpreter's house pointer form in `ValidationFinding.errorPath`, `referenced`, and `fillToFix` (`/Order[1]/Items[2]/Count`), and the conformance corpus and the `:adapter` differential use that same string as their shared comparison key. Nothing renders it: `eval.formatPointer` is in an engine package the ABI filter excludes, so no target — JVM, Kotlin/JS, or npm — publishes a way to *produce* it from a `path` plus `coordinates`. `A12PointerFormat` renders A12's canonical form, which omits `[1]`, and therefore cannot.

**What it blocks, concretely:** `consumer-tests/browser/src/qualification.ts` replays the committed corpus through the *published* surface only, so it must build the corpus key itself. Exporting `A12PointerFormat` to npm removed the parsing and the legality checks from that local helper — `corpusPointerKey` now derives both from the published utility — but the final join is still local, because no published call renders it. This was originally read as "the clone reproduces the published utility"; it does not. The clone reproduces the house form, which is a *different* rendering that happens to be unpublished.

**Why it is not simply "publish it":** the two renderings' convergence is an open owner decision ([CG5](CLI-CONFORMANCE-GAPS.md#cg5)). Adding a second published renderer commits the released package to the house form as a first-class exported concept, and if the forms converge it becomes dead API on a shipped surface. The cheap alternative — canonicalizing both sides of the corpus comparison — was rejected because it would make the browser harness's key differ from the JVM/Node harnesses' and print pointers that no longer match the corpus files a reader is diffing against.

**What would settle it:** the convergence decision. If the forms converge, this gap closes with them. If they stay separate, the house form earns a published renderer beside `A12PointerFormat` on all three surfaces, and the browser helper is deleted. Opened 2026-08-01 while exporting `A12PointerFormat` to npm, as **IAG10**; renumbered to IAG11 the same day after a review found that id already taken — see "Retired ids" below.

## Retired ids

Every `IAG<n>` ever issued that has no live row above, so a citation of one resolves here and **no number is ever reused**. Reuse is what made this section necessary — `IAG9` named two unrelated gaps at different times, and six live citations across the repo could not be told apart.

> **Not yet enforced, and this section is in the wrong place.** An earlier revision claimed `GapLedgerTemplateGuardTest` checks that a live id never appears below and that a new number exceeds every issued one. It checks neither — its five tests cover template shape, open-section population, and the *presence* of an identifier, so a second live `IAG9` would pass all of them. Writing an enforcement claim for a guard that did not exist is the same defect this project keeps finding, committed one commit after proposing a measure against it. Separately, a shipped-history list inside a live ledger contradicts the `-GAPS` lifecycle ("No shipped list, ever" — [docs/CLAUDE.md](CLAUDE.md)). Both are tracked together: the registry moves to its own surface and acquires the guard that makes the claim true.

| id | what it meant | disposition |
|---|---|---|
| IAG9 | the native public computation report should retain the Kernel's two explanatory error channels while keeping validation's public finding projection | shipped 2026-07-31 → [IF241](INTERPRETER-FINDINGS.md#if241); locked by `adapter … laws/PublicComputationDiagnosticsDiffTest` |
| IAG9 *(reused — the defect this section exists to prevent)* | `A12PointerFormat` was published on Kotlin/JVM and Kotlin/JS but not exported from the npm package | shipped 2026-08-01; the export, its bridge, and `typescript.api` landed together. Cited as "IAG9" in `INTERPRETER-API-SPEC` and `CLI-CONFORMANCE-GAPS` until both were corrected to name the work instead of the number |
| IAG10 | which A12 type each addressing concept corresponds to — the exact `DocumentPointer` or the partially-known `PartiallyKnownDocumentMultiPointer` — and the hybrid the one exported type had become | **decided 2026-08-01, shipped in two parts.** The owner decision: both, as two nominally distinct types on every target. The types landed 2026-08-01 (`A12Pointer` exact, carrying A12's `List<PathPart>`; `A12PartiallyKnownMultiPointer` carrying `fullName` + `repetitionIndexes`) and were **corrected 2026-08-02** after an eight-row counterexample audit — per-entry-point domains, the invented partially-known string form deleted, JVM construction closed, TypeScript brands, root-conversion parity. The message channel was wired to the type in that same correction, so `ValidationFinding.errorPath`, `referenced` and `fillToFix` carry `A12PartiallyKnownMultiPointer` on every published target; an intermediate revision of this row claimed the wiring was done before it was, and a later one opened a gap for it that never shipped. Contract in [INTERPRETER-API-SPEC](INTERPRETER-API-SPEC.md) "The two pointer types" → [IF242](INTERPRETER-FINDINGS.md#if242); every entry point measured against its own kernel factory by `adapter … laws/A12PointerKernelDomainDiffTest`, recorded as [kf171](KERNEL-FINDINGS.md#kf171) |

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
