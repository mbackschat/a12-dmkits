# A12 Interpreter developer guide

This guide covers the public TypeScript, Kotlin, and Java API for preparing A12 Document Models and evaluating Documents. Every complete language example below is compiled and executed by a standalone consumer of the packed npm or staged Maven artifact.

## 1. Choose a workspace boundary

Use `ModelWorkspace.fromSources` when the complete source set is already available. Sources are indexed by exact `header.id`; duplicate ids, failed integrity checks, unresolved dependencies, cycles, and configured resource limits produce structured `ModelPreparationError` diagnostics.

Use `ModelWorkspace.collect` when models should be obtained on demand. The provider owns the I/O policy. Collection is deterministic, requests each exact id at most once, forwards a remaining byte ceiling and cancellation signal, and freezes the discovered closure into the same finite workspace.

<!-- snippet: interpreter-typescript-provider -->
```ts
import {
  ModelWorkspace,
  type ModelSourceProvider,
  type WorkspaceModelSource,
} from "@mbackschat/a12-interpreter";

export async function collectWorkspace(
  entryModelId: string,
  sourcesById: ReadonlyMap<string, WorkspaceModelSource>,
): Promise<ModelWorkspace> {
  const provider: ModelSourceProvider = {
    snapshotRevision: "example-v1",
    async load(modelId, {signal}) {
      if (signal.aborted) {
        throw new DOMException("Collection cancelled", "AbortError");
      }
      return sourcesById.get(modelId);
    },
  };
  return ModelWorkspace.collect(entryModelId, provider);
}
```

Providers may read an application file map, browser-local storage, a server endpoint, a Node.js filesystem, or another application-owned store. The interpreter does not add a virtual filesystem, retry loop, cache, watcher, or provider lifecycle.

## 2. Expand or prepare

`workspace.expand(entryModelId)` resolves imported type definitions, mounts includes, applies exclusions, normalizes ids, and remaps references. It returns self-contained DM-JSON and stops before evaluator-only support checks.

`workspace.prepare(entryModelId)` performs the same expansion and then builds a reusable evaluator model. A model can therefore expand successfully but fail preparation when it uses an unsupported evaluator capability. `PreparedModel.loadExpandedJson` is the equivalent fast path when the application already has self-contained DM-JSON.

Repeated `expand` or `prepare` calls rerun preparation; `ModelWorkspace` has no hidden cache. Retain the resulting `PreparedModel` when evaluating many Documents against the same model.

## 3. Read and evaluate a Document

The public Document JSON distinguishes absent fields, present-empty fields, malformed raw values, explicit group rows, and sparse repetitions. Decoding validates the complete input before constructing an immutable `DocumentSnapshot`.

When the caller does not yet know the model's tree, start with `prepared.documents.seedDocument()`. It returns a deterministic, best-effort candidate containing every declared group and field and one row per repeatable group. Editable source fields receive useful local values; computation-owned targets are present-empty for the engine to fill. Encode that snapshot to display or edit canonical A12 Document JSON. The seed respects local field constraints where possible but does not guarantee that cross-field business rules pass. This is the same generator as `dmtool model seed`; `profile` describes model performance shape and is unrelated to Document authoring.

<!-- snippet: interpreter-typescript-start -->
```ts
import {
  type DocumentSnapshot,
  type Interpreter,
  ModelWorkspace,
  type A12Document,
} from "@mbackschat/a12-interpreter";

export function runInterpreter(rawModelJson: string) {
  const workspace = ModelWorkspace.fromSources([{json: rawModelJson}]);
  const prepared = workspace.prepare("permit-basic");
  const starter = prepared.documents.encodeDocument(
    prepared.documents.seedDocument(),
  );
  const input: A12Document = {
    Permit: {ApplicationNo: "P-42", RequestedArea: 120},
  };
  const document = prepared.documents.readDocument(input);
  const interpreter = prepared.createInterpreter();
  const relevant = prepared.model.relevantPointer(
    "/Permit/RequestedArea",
    [1, 1],
  );

  return {
    full: interpreter.validateFull(document),
    partial: interpreter.validatePart(document, [relevant]),
    computation: interpreter.compute(document),
    starter,
    document,
    prepared,
  };
}
```

`validateFull` evaluates the whole Document. `validatePart` evaluates the model-defined effects of the supplied concrete or `"ALL"` relevance pointers; a passing partial result does not imply that full validation passes. `compute` returns an immutable retained result and does not mutate the snapshot.

Each report has an explicit `unsupported` collection and `fullySupported` flag. Validation and computation both separate `noErrorOccurred` from support: a report can contain no error and still be incomplete because a program is unsupported.

### Retain once, apply to the same or another Document

`ComputationReport.results` reports every computed cell. An ERRORED result carries a target-owned `diagnostic` with the same `ValidationFinding` shape as validation. `formalErrorsInOperands` separately explains selected inputs that were already invalid, including selected empty or duplicate index fields and computation domain failures. `noErrorOccurred` is true exactly when neither channel contains an error; `unsupported` and `fullySupported` remain independent.

`ComputationReport.actions` is the source-relative subset retained for application: changed VALUE, source-filled CLEARED, and ERRORED outcomes. `report.applyTo(destination)` applies that fixed subset without recomputing against the destination. VALUE and CLEARED create the addressed target and any missing repeatable ancestry; ERRORED clears only an existing target; no action leaves the destination unchanged.

The source and destination may be different immutable snapshots but must belong to the same `PreparedModel` owner. A foreign owner fails with `ModelOwnerMismatchError` before application. This is an interpreter API safety boundary; Kernel model compatibility is a caller precondition. If computation is unsupported, the report has no complete action set and applying it returns the destination unchanged.

The contract is the same in TypeScript, Kotlin, and Java: retain with `val retained = interpreter.compute(source)` (or TypeScript/Java's equivalent), apply separately with `retained.applyTo(destination)`, or use `interpreter.applyComputations(source)` for the same-document route.

`applyComputations(source)` is exactly the one-call convenience for `compute(source).applyTo(source)`. All routes leave both input snapshots unchanged.

Validating before computation checks the Document exactly as supplied. Because each computation contributes an implicit consistency rule, a filled calculated target that differs from its expected value can appear as a validation finding. The ordinary form-engine pass computes first, applies the retained actions, and then validates the resulting Document.

<!-- snippet: interpreter-typescript-form-pass -->
```ts
export function computeApplyValidate(
  interpreter: Interpreter,
  document: DocumentSnapshot,
) {
  const computation = interpreter.compute(document);
  const applied = computation.applyTo(document);
  const validation = interpreter.validateFull(applied);
  return {computation, applied, validation};
}
```

This is intentionally composition rather than a different evaluator mode. The Kernel also keeps `compute`, result application, and `validateFull` separate; the final `applied` snapshot is the Document a form retains.

### Identify a repeated row: `A12PointerFormat`, and A12's message address: `A12PartiallyKnownMultiPointer`

**There are two pointer types because A12 has two, and they are not interchangeable.** An exact `DocumentPointer` addresses one resolved cell: every repetition is `>= 1`, except a `0` on the final part, which addresses A12's `RepetitionsV2` slot. A `PartiallyKnownDocumentMultiPointer` is what A12's own `IMessage` returns from `getErrorFieldPointer()` and its two sibling accessors: it admits a wildcard at every part and the unknown index a parallel iteration leaves on a level that matched no row.

A computation inside a repeatable group runs once per row, so `ComputationReport.results` can contain several results whose `pointer.path` is the *same* string. The per-row identity is in `pointer.coordinates`, and `A12PointerFormat` joins the two into one addressable **exact** pointer: `format` emits A12's canonical rendering — parts joined by `/`, `[n]` only where the repetition is not 1 (`Permit/SiteSections[2]/SurveyCharge`) — and `parse` also accepts non-canonical leading slashes and explicit `[1]` indexes.

**`A12PartiallyKnownMultiPointer` has no string form**, because A12 defines none for it — no parser, no renderer. So it is built from `fullName` plus its indexes, carries `WILDCARD`/`UNKNOWN`, and converts through `toExactPointer`, which returns `null` when the value names a set or an unresolved level rather than one cell.

**Each entry point has its own domain**, mirroring A12's: `A12PathPart` accepts any non-empty name, `A12Pointer.of` adds only the position rule, and the `[\w_-]+` grammar belongs to the string codec — what may be *written*, so that it parses back to the same pointer. A malformed argument raises a `TypeError` on any of them; it is a caller contract violation, not a model diagnostic, so it is none of this package's named `Error` subclasses.

<!-- snippet: interpreter-typescript-pointer -->
```ts
// A computation inside a repeatable group reports one result per row, all under the SAME `pointer.path`.
// `A12PointerFormat.format` joins path and coordinates into A12's canonical EXACT pointer, so each row becomes
// addressable. A12's message addressing is a different type — `PartiallyKnownDocumentMultiPointer`, which may
// carry a wildcard at any part or the unknown index — and a validation finding CARRIES that type: `errorPath`,
// `referenced` and `fillToFix` are values, not strings, so nothing has been rendered away. It converts to an
// exact pointer only when it really names one cell.
const identifiedRows = perRowCharges.map(({pointer, value}) =>
  `${A12PointerFormat.format(pointer.path, pointer.coordinates)}=${value}`);
const errorCell = reportedFinding.errorPath;
const errorCellExact = A12PartiallyKnownMultiPointer.toExactPointer(errorCell);
```

Both are published on Kotlin/JVM and Kotlin/JS with the same members. On Kotlin the exact pointer carries A12's own state — `pathParts` (a list of `A12PathPart`) with `fullName` and `repetitionIndexes` derived — and both value types are built through a validating factory rather than a constructor, so no JVM caller can side-step the checks. In TypeScript the two are **nominally** distinct: each carries a compile-time brand, so neither is assignable where the other is required — including a pointer that arrives through a finding.

**These are the message-channel types.** A `ValidationFinding` types `errorPath` as `A12PartiallyKnownMultiPointer` and `referenced`/`fillToFix` as lists of it, matching what `IMessage` returns on all three accessors. The engine retains the pointer, so nothing has to be recovered from text — which matters because the interpreter's house rendering omits the terminal repetition, and both of A12's message sentinels (the wildcard `0` and the unknown `-5`) sit exactly where it is dropped. Every published pointer and repetition list is frozen.

Rendering is the consumer's call. `A12PartiallyKnownMultiPointer.toExactPointer` gives A12's exact pointer when the address names one cell and `null` when it does not, and `A12PointerFormat` turns that into A12's canonical string. There is deliberately no string form for a non-exact address: A12 defines none, and inventing one would publish a spelling A12 cannot read back.

## 4. Reuse for form input

Keep one `PreparedModel` and one configured `Interpreter` for a form or request scope. After an input change, decode the new Document state and validate the resulting snapshot against the retained interpreter. Model parsing, executable preparation, and bounded model-owned caches are reused; each evaluation call owns its transient state and immutable result.

This is optional state reuse at the application boundary, not a mutable editing session. The public Document snapshot remains immutable, and a snapshot or pointer from another preparation fails with `ModelOwnerMismatchError`.

## 5. Add project callbacks

Custom field validators and custom conditions are registered while creating an interpreter. They are synchronous and scoped to that interpreter. A custom condition returns the rule's error condition: `true` means the rule fires.

<!-- snippet: interpreter-typescript-customization -->
```ts
import {
  PreparedModel,
  type A12Document,
} from "@mbackschat/a12-interpreter";

export function validateWithProjectCondition(
  expandedModelJson: string,
  input: A12Document,
) {
  const prepared = PreparedModel.loadExpandedJson(expandedModelJson);
  const document = prepared.documents.readDocument(input);
  const interpreter = prepared.createInterpreter({
    customConditions: {
      ExternalEligibility({document: view, errorPointer}) {
        return view.value(errorPointer) === "ACME";
      },
    },
  });
  return interpreter.validateFull(document);
}
```

A thrown, recursively re-entered, or thenable-returning callback becomes `InterpreterIntegrationError`. The evaluator never implicitly awaits a customization callback.

## 6. Kotlin

The Kotlin Multiplatform API owns the common implementation. Kotlin/JVM and Kotlin/JS use the same workspace, Document, model-ownership, validation, and computation contracts.

<!-- snippet: interpreter-kotlin-start -->
```kotlin
import io.github.mbackschat.a12.dm.interpreter.ComputationReport
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace
import io.github.mbackschat.a12.dm.interpreter.ValidationReport
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource

data class InterpreterResult(
    val starterJson: String,
    val validation: ValidationReport,
    val computation: ComputationReport,
)

fun runInterpreter(modelJson: String): InterpreterResult {
    val source = WorkspaceModelSource.builder().json(modelJson).build()
    val prepared = ModelWorkspace.fromSources(listOf(source)).prepare("permit-basic")
    val starterJson = prepared.documents.encodeDocument(prepared.documents.seedDocument())
    val document = prepared.documents.readDocument(
        """{"Permit":{"ApplicationNo":"P-42","RequestedArea":120}}""",
    )
    val interpreter = prepared.createInterpreter()
    return InterpreterResult(
        starterJson = starterJson,
        validation = interpreter.validateFull(document),
        computation = interpreter.compute(document),
    )
}
```

Kotlin provider collection is a `suspend` function without a coroutine-library type in the public contract. The provider context carries the portable cancellation signal.

## 7. Java

The JVM publication exposes ordinary Java-callable preparation and evaluation methods. On-demand collection is projected through `ModelWorkspaceJava.collect` and `CompletionStage`.

<!-- snippet: interpreter-java-start -->
```java
import io.github.mbackschat.a12.dm.interpreter.ComputationReport;
import io.github.mbackschat.a12.dm.interpreter.JavaModelSourceProvider;
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace;
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspaceJava;
import io.github.mbackschat.a12.dm.interpreter.ValidationReport;
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class JavaGuideExample {
    public record Result(String starterJson, ValidationReport validation, ComputationReport computation) {}

    public static Result run(String modelJson) {
        var source = WorkspaceModelSource.builder().json(modelJson).build();
        var prepared = ModelWorkspace.fromSources(List.of(source)).prepare("permit-basic");
        var starterJson = prepared.getDocuments().encodeDocument(
                prepared.getDocuments().seedDocument());
        var document = prepared.getDocuments().readDocument("""
                {"Permit":{"ApplicationNo":"P-42","RequestedArea":120}}""");
        var interpreter = prepared.createInterpreter();
        return new Result(
                starterJson,
                interpreter.validateFull(document),
                interpreter.compute(document));
    }

    public static CompletionStage<String> collectEntryModelId(String modelJson) {
        var source = WorkspaceModelSource.builder().json(modelJson).build();
        JavaModelSourceProvider provider =
                (modelId, context) -> CompletableFuture.completedFuture(source);
        return ModelWorkspaceJava.collect("permit-basic", provider)
                .thenApply(workspace -> workspace.prepare("permit-basic").getEntryModelId());
    }
}
```

## 8. Failures and limits

Use the throwing methods when invalid model or Document input is exceptional. Their `try...` peers return the same typed errors through the platform result projection.

Preparation diagnostics identify the stable failure code, model ids, dependency chain, resource, integrity values, or safe diagnostic labels that apply. Document diagnostics identify the input path and, where applicable, the model path, group or field entry, and coordinate. Do not parse human messages as an API.

Default and caller-supplied limits bound source count and bytes, prepared output, reference depth and edges, model structure, Document group and field entries, coordinate count, path length, and raw value size. Provider collection additionally receives the remaining byte ceiling and cancellation signal.

## 9. Runtime scope

The base artifacts are clean-room, kernel-free, and MIT-licensed. They do not link or bundle the A12 Kernel.

The supported model time zones are `UTC`, `GMT`, and `Europe/Berlin`. All four A12 Date precisions are supported. The support report and each evaluation report make program-level unsupported constructs visible.

The versioned Kotlin and TypeScript references contain the complete published declaration inventory. Deferred compatibility adapters and richer stateful products are not part of the v0.11.0 public artifacts.

## 10. Generated references

The version-matched API documentation asset `dm-interpreter-0.11.0-api-docs.zip` contains this tested guide, a Dokka Kotlin reference, a TypeScript reference generated from the declaration file inside the packed npm tarball, and `llms.txt`. Its immutable release layout starts at `api/v0.11.0/`; the public mirror adds `api/latest/` only after that release has passed the complete gate.
