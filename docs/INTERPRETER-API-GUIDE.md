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

### Serve concurrent requests from a cache of hot models

A backend service holds many models and serves concurrent requests. The split that makes this work is that a
**`PreparedModel` is shareable and an `Interpreter` is not.**

A prepared model owns everything derived from the model alone — condition and operation ASTs, iteration scopes,
`RepetitionNotUnique` plans, the path index, the computation dependency order — memoized in thread-safe lazy state.
So one cache entry serves every tenant and every concurrent request for that model, whatever their configuration,
and a second interpreter over it performs no model-static work at all.

An interpreter is per request. That is a **safety** requirement, not a cost preference: it carries a
snapshot-scoped guard that the custom-condition adapter reads, so sharing one across threads can hand a callback
another thread's Document. Concurrent entry is now detected and refused with
`InterpreterIntegrationReason.CONCURRENT_USE`, which is best-effort detection rather than thread safety: it
turns the misuse into a clear failure instead of a silent one.

Five rules follow, each with a real failure mode:

1. **Resolve the model once per request** and hold that reference for the whole request. Never re-resolve midway.
2. **Key the cache by a cheap identity you already have** — the database's `(modelId, version)` or an etag — and
   fetch the model body only on a miss. Neither a digest of the body nor `PreparedModel.fingerprint` can be the
   lookup key: hashing requires fetching, and reading the fingerprint requires the preparation a hit exists to
   skip. The fingerprint's job is to *verify* an entry's content identity.
3. **Single-flight the miss.** Without a per-key guard, concurrent misses each build a full prepared model.
4. **Give every request its own interpreter.**
5. **Eviction only drops the cache's reference.** An in-flight request's own reference keeps its entry alive, so
   eviction is safe by construction.

<!-- snippet: interpreter-kotlin-service-cache -->
```kotlin
/** A bounded, content-keyed cache of prepared models with single-flight loading. */
class PreparedModelCache(private val capacity: Int) {
    private val entries = ConcurrentHashMap<String, PreparedModel>()
    private val order = ArrayDeque<String>()
    val preparations = AtomicInteger()
    val evictions = AtomicInteger()

    /**
     * Rule 2 + rule 3: one entry per model version, built at most once even under a concurrent miss.
     *
     * [key] is the cheap content identity the service ALREADY has before touching the model body — the
     * database's `(modelId, version)` or an etag. [loadExpandedJson] runs only on a miss, so a hit costs no
     * database read, no digest, no parse and no preparation. Keying on a digest of the body would defeat that:
     * you cannot hash what you have not fetched. The prepared model's own `fingerprint` is unusable as a
     * lookup key for the same reason, one layer worse — obtaining it requires the preparation being avoided.
     *
     * `computeIfAbsent` is the single-flight: competing threads await the winner instead of each preparing.
     */
    fun resolve(key: String, loadExpandedJson: () -> String): PreparedModel {
        val prepared = entries.computeIfAbsent(key) {
            preparations.incrementAndGet()
            PreparedModel.loadExpandedJson(loadExpandedJson())
        }
        remember(key)
        return prepared
    }

    /** Rule 5: evicting removes only this reference; a request holding the entry keeps using it safely. */
    private fun remember(key: String) {
        synchronized(order) {
            order.remove(key)
            order.addLast(key)
            while (order.size > capacity) {
                entries.remove(order.removeFirst())
                evictions.incrementAndGet()
            }
        }
    }
}

/** One request: resolve once, take an OWN interpreter, answer. Rules 1 and 4. */
fun serveRequest(
    cache: PreparedModelCache,
    modelKey: String,
    loadExpandedJson: () -> String,
    documentJson: String,
): Int {
    // rule 1: resolved once, and the instance is held for the rest of the request
    val prepared = cache.resolve(modelKey, loadExpandedJson)
    val document = prepared.documents.readDocument(documentJson)
    val interpreter = prepared.createInterpreter()        // rule 4: never shared across threads
    return interpreter.validateFull(document).findings.size
}
```

**Content identity is not instance interchangeability.** Two prepared models over identical bytes share a
fingerprint, yet each owns its objects: a snapshot from one is refused by an interpreter from the other with
`ModelOwnerMismatchError`. That is why rule 1 matters, and it is also why the cache is necessarily
**in-process**. A prepared model is a live instance with a per-instance owner identity and no serialization, so
it cannot go in Redis or be shared between service instances. Only the expanded DM-JSON can live in a shared
tier, with each JVM preparing its own instances from it.

#### What it costs, so you can decide

A `PreparedModel` is **larger than the DM-JSON it came from**, and it grows on two axes rather than tracking file
size. These are real models measured on one machine — a rough guide for sizing, not a specification, and not
updated automatically.

**Measured 2026-08-07 on JDK 21, retained bytes per instance.** `cold` is a prepared model that has never served
a request; **`warm`** is the same after one `createInterpreter()` + `validateFull`, which is what a cache entry holds
once it has served anything — budget with that column.

| model | fields | rules | expanded DM-JSON | cold | warm | warm ÷ DM-JSON |
|---|--:|--:|--:|--:|--:|--:|
| type definitions only | 26 | 0 | 4,726 | 32,839 | 45,892 | 9.7× |
| small order + aggregates | 15 | 6 | 5,197 | 24,808 | 38,751 | 7.5× |
| order with one rule | 29 | 1 | 5,397 | 36,487 | 52,425 | 9.7× |
| rule-heavy | 5 | 48 | 14,504 | 45,347 | 88,407 | 6.1× |
| rule-heavy | 5 | 192 | 53,779 | 154,894 | 313,946 | 5.8× |

Reading it:

- **Budget from structure, with a formula.** Across six models spanning 5 to 79 fields, 3 to 29 groups and 0 to 192
  rules, a warm prepared model costs

  > **≈ 1.4 kB × fields + 1.55 kB × rules + 2.3 kB × groups**

  to within **4%** on every one of them, and to within 2.4% against the independently measured table above. Use it
  in preference to any ratio, because the ratio itself moves between 5.8× and 9.7× with shape. A rule's share tracks
  its condition size rather than its mere existence (about 0.8 kB per boolean operand), so a model of unusually
  complex rules runs above the per-rule term and a model of one-operand rules below it.
- **About 90% of the per-field cost is the parsed model, not the preparation.** On a fixture of 26 minimal fields and
  no rules, the parsed model retains ~1.25 kB per field, wrapping it in a `PreparedModel` adds nothing measurable,
  and serving a request adds ~0.5 kB per field of indexes and plans. So the space-for-time trade is smaller than the
  total suggests: most of what a cache entry holds is the model itself.
- **Do not size by DM-JSON bytes and do not size by entry count.** A field-dense 5 kB model costs more than a
  rule-dense one of the same size, and the smallest and largest entries here differ by 8×. DM-JSON bytes are an
  especially poor guide for real models, whose size is dominated by localized label and message text.
- **Budget `warm`, not `cold`.** A served entry costs 1.4× to 1.9× an untouched one.
- **A retained interpreter adds only 2–5%** on top of `warm`, because model-static work lives on the prepared model.
  So holding an interpreter is cheap; what you are really budgeting is the prepared model.

#### How that compares to the A12 Kernel

If you are sizing a service that used to hold A12 Kernel runtime services, here are six models measured the
same way. The last is derived from a **real** A12 model's shape, so the table is not only synthetic fixtures.
**Total resident memory per model, in kB (1024 bytes) — heap plus class metadata, which is the whole budget:**

| model | fields | groups | rules | interpreter | Kernel model graph | + generated Java | + generated Groovy |
|---|--:|--:|--:|--:|--:|--:|--:|
| type definitions only | 26 | 4 | 0 | 45 | 20 | 412 | 1,091 |
| small order + aggregates | 15 | 4 | 6 | 37 | 18 | 308 | 1,363 |
| order with one rule | 29 | 4 | 1 | 51 | 23 | 296 | 1,118 |
| rule-heavy | 5 | 3 | 48 | 86 | 49 | 444 | 3,535 |
| rule-heavy | 5 | 3 | 192 | 305 | 181 | 1,166 | 11,729 |
| **real-model shape** | 79 | 29 | 89 | 309 | 152 | 1,254 | 6,924 |

**Measured 2026-08-07 on JDK 21**, twelve resident instances per arm, after the three engines were confirmed to
produce the same message-code multiset over the same document. A resident entry is **3.8× to 9.2×** smaller than the
generated-Java equivalent and **22× to 41×** smaller than the dynamic-Groovy one. The Java column has one
measurement artifact removed: the harness that compiles the generated Java keeps each class's bytecode on the heap,
which a deployment loading those classes from a jar does not, so 56 to 271 kB per model is subtracted. The Groovy
column gets no such adjustment, because there the retained generated text is the Kernel's own design rather than the
harness's.

Reading it:

- **The generated rule and computation code is the whole difference, not the model.** The Kernel's own expanded
  Document Model is the sixth column — 18 to 181 kB, comparable to a prepared model and *smaller* than it on the
  rule-heavy shapes. Everything above that line is generated code and the machinery that loads it.
- **A fifth to a half of the Kernel figures is invisible to a heap profiler.** Generated classes live in
  Metaspace, not the heap: 19% to 50% of the totals above, which is 85 to 298 kB for Java and 286 kB to 4.8 MB for
  Groovy. A prepared model generates and loads **no classes at all**, so for the interpreter column heap *is* the
  total and a heap budget is the whole budget. On the Groovy path the generated source text is resident too, beside
  the classes parsed from it.
- **Groovy's class count grows per rule; Java's does not.** It tracks `≈ 34 + 3.2 × rules` across all six models
  (34 classes at no rules, 639 at 192), against 12 to 19 for Java at every size. The generated *source* volume is
  near-identical between the two (740 kB vs 736 kB at 192 rules), so what differs is what compiling it produces.
- **The Kernel side has one usable formula.** Dynamic Groovy fits `≈ 0.75 MB + 14 kB × fields + 58 kB × rules`
  within 7%. Generated Java does *not* fit these predictors — rule count is the wrong axis for it, since the
  89-rule real-model shape costs more than the 192-rule fixture (varied rules over 79 fields and 29 groups compile to
  more program than near-identical ones over 5 fields) — so use its measured range rather than a slope.
- **The Groovy engine defers nearly all of its cost to the first request**, exactly as a prepared model defers its
  plans: creating the runtime service costs almost nothing over the model graph, and the code generation happens on
  first evaluation. Budget the served figure on either engine.

Against that cost, the cache buys you: model-static work once per model; one entry per model rather than per
(model × configuration); safe sharing across concurrent requests; and content-addressed invalidation, since an
edited model yields a new fingerprint.

**An undersized cache is not slightly worse, it is no cache plus eviction overhead.** In the shipped example, 12
requests over 3 models cost **3** preparations when the cache fits and **12** preparations with 10 evictions when it
is one entry too small.

Eviction policy is yours. The library deliberately ships no cache, because that would mean global mutable state in a
module whose determinism is enforced.

A runnable version of all of this, executed against the published artifact on every release verification, is
`ServiceCacheExample` in the Kotlin/JVM consumer example.

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

The versioned Kotlin and TypeScript references contain the complete published declaration inventory. Deferred compatibility adapters and richer stateful products are not part of the v0.12.0 public artifacts.

## 10. Generated references

The version-matched API documentation asset `dm-interpreter-0.12.0-api-docs.zip` contains this tested guide, a Dokka Kotlin reference, a TypeScript reference generated from the declaration file inside the packed npm tarball, and `llms.txt`. Its immutable release layout starts at `api/v0.12.0/`; the public mirror adds `api/latest/` only after that release has passed the complete gate.
