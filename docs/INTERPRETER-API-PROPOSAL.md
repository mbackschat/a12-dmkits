# Proposal: TypeScript-first API for the Kotlin Multiplatform interpreter

## Status

This proposal defines a TypeScript-first public API for the Kotlin Multiplatform A12 interpreter. It covers model preparation, lossless documents, direct storage access, validation, computations, customization, prepared programs, repeated-model services, and platform ownership.

TypeScript on Node is the primary product surface. Java/JVM is a secondary projection of the same Kotlin `commonMain` implementation. Browser use is an important future form-UI scenario, not a current support claim.

The base Kotlin Multiplatform interpreter, its TypeScript façade, and its ordinary JVM façade are clean-room, kernel-free, MIT-licensed artifacts. They do not import, link, reflect over, or bundle any A12 Kernel API or runtime artifact. Compatibility with existing kernel-interface implementations is supplied only by separately installed platform adapters whose own licensing follows their kernel dependency and distribution mode.

The direct [Operator API proposal](OPERATOR-API-PROPOSAL.md) remains independent: calling `operators.dateTime.differenceInHours(start, end)` never requires an interpreter. This proposal is the model/document layer for behavior that genuinely needs paths, repetitions, formal state, relevance, rules, computations, or runtime customization.

API and package names are proposed contracts. Where a contract requires engine work beyond the present evaluator, the relevant section states that implementation dependency because kernel exactness and performance evidence are acceptance conditions.

Every example spells out input, variable, callback-parameter, callback-return, and result types. `declare const name: Type` marks an externally supplied immutable input, `const name: Type` marks a local immutable value, and `let name: Type` marks deliberately mutable local state. The type annotation beside each variable is authoritative; the nearest type table or explanatory paragraph defines that type once so examples do not rely on TypeScript inference or repeat the same definition at every call site.

## 1. Verdict and boundary

The interpreter can credibly serve multi-threaded validation services, import pipelines, rule preview tools, dynamic forms, and JVM applications. The correct core distinguishes a prepared model definition or factory from each active evaluator:

```text
expanded model ── prepare once ──► PreparedModel
                                         │
                 ┌───────────────────────┼───────────────────────┐
                 ▼                       ▼                       ▼
          Interpreter A-1         Interpreter A-2         Interpreter B-1
          worker-confined         worker-confined       different config
                 │                       │                       │
                 └──────────── evaluate document snapshots ─────┘
```

- `PreparedModel` represents one prepared owner domain and creates active interpreters within that domain. It may be immutable and share-safe, worker-confined, or independently instantiated per runtime lane; the public API does not mandate one implementation strategy.
- Each `Interpreter` binds runtime customization and may be single-thread/worker-confined.
- `DocumentSnapshot` owns one model instance, including absent, empty, malformed, and repeated cells.
- Each evaluation call owns its sampled time, temporary caches, and result objects.

This split supports several simultaneous active states for D1, fast `D1, D2, D1, D2` interleaving, and different configurations over the same definition. Clocks, callbacks, documents, and results do not become model-definition state.

Concurrent validation is a core service outcome; making one `PreparedModel`, `DocumentSnapshot`, or `Interpreter` concurrently callable is not. A service may use several warm, thread-confined interpreters per model, including fully independent prepared owners when sharing cannot be proven without a warmup or throughput regression. The implementation must not serialize all requests behind one global or per-model evaluation lock.

An optional caller-owned registry plus runtime pool may retain multiple registered model versions and several active interpreters per model. It belongs above the core; it is not a hidden global inside the interpreter.

## 2. What developers can reasonably expect

The stable façade should cover the evaluator’s actual jobs and make the status of each one honest:

| Developer job | Engine today | Proposed product status |
|---|---|---|
| Load expanded DM-JSON | `ModelLoader.load` | core façade plus a stricter prepared-input guard |
| Reuse one model | retain one `DmInterpreter`; its stored ASTs parse lazily once | core `PreparedModel`/`Interpreter` boundary |
| Reuse several models and concurrent instances | retain several `DmInterpreter` instances | core instance factory; optional bounded registry/runtime pool |
| Inspect model structure | data exists on `ModelDef` | core stable read-only model metadata |
| Create a request document programmatically | mutable `Document` exists | core lossless transport plus frozen request document/builder |
| Persistently edit a document for form use | no safe immutable editor boundary | optional form layer with structural-sharing/cache gates |
| Decode/encode a common instance JSON contract | no stable common codec | core strict `unknown` ingress, typed fast path, owner guard, and lossless encoding |
| Read stored raw/decoded field values without validation | internal document storage exists | core `DocumentSnapshot.storage` boundary with no callback invocation |
| Inspect configured formal/evaluation state | internal document/evaluation conversion exists | separate model-aware `DocumentInspection` boundary |
| Check model/configuration support at startup | unsupported constructs surface during evaluation | core configuration-aware `InterpreterSupportReport` |
| Validate a complete document | `validateFull` | core with the familiar kernel verb |
| Validate a caller-defined relevant subset | `validatePart` | core, with exact relevance semantics |
| Evaluate one stored rule | `testRule` | core after repeated-row result shape is fixed |
| Evaluate an ad hoc rule | `evalCandidate`, reparsed per call | optional preview API; prepared handle is new work |
| Compute all stored computations | `compute` | core |
| Compute one target | `computeField` | core |
| Apply computations | `applyComputations` | core, refined to return outcomes and snapshot without double evaluation |
| Supply native custom field types and custom conditions | `DocumentProcessingConfig` factories | core kernel-free registries and SPIs |
| Reuse existing JVM or TypeScript kernel-interface implementations | platform-specific kernel factories and documents | optional separately packaged compatibility adapters |
| Evaluate an arbitrary symbolic expression | parser/evaluator internals only | optional advanced API, not core v1 |
| Prepare an ad hoc computation | no complete capability | deferred |
| Build or consistency-check a model | modelkit/kernel concerns | outside the interpreter API |
| Adapt `DocumentV2` | kernel-linked JVM type | optional JVM compatibility adapter |

The core should be complete for “load model → create/read document → configure custom types/conditions → validate/compute → inspect results.” Candidate programs, service registries, batch helpers, browser adapters, and kernel-interface compatibility adapters are optional layers over that core. Customization semantics are not optional; only the dependency on a particular existing platform interface is.

The primary direct verbs remain deliberately familiar: `validateFull`, `validatePart`, and `compute` offer the corresponding kernel capabilities over kernel-free types. Richer reports, frozen snapshots, support inspection, and compute-and-apply are additive; they do not rename or weaken the established validation/computation jobs. `PreparedModel` is the kernel-free prepare-once abstraction and does not reproduce the kernel’s strategy-specific dynamic-code cache API.

## 3. Prepared model, interpreter, and ownership

### 3.1 Core types and primary TypeScript shape

| Type | Meaning and ownership |
|---|---|
| `string` | native TypeScript text; expanded DM-JSON enters through this type |
| `ExpandedModelJson` | a `string` known to contain expanded, self-contained, name-path-keyed DM-JSON; the loader still validates that claim |
| `ModelFingerprint` | a library-computed digest string over exact model input bytes plus preparation-format/ABI identity |
| `ModelPreparationDiagnostic` | one stable preparation code, optional model path, and corrective message |
| `ModelPreparationError` | typed JS-safe exception containing every preparation diagnostic |
| `ModelPreparationResult` | non-throwing success/failure union for model preparation |
| `UnknownModelPathError` | typed lookup exception containing the requested model-entity kind and path |
| `DocumentInputError` | typed decoding exception containing every invalid transport/model placement diagnostic, with separate input and model paths |
| `DocumentCoordinateError` | typed pointer-construction exception for the wrong coordinate count or a coordinate that is not a positive safe integer |
| `DocumentRowScopeError` | typed row-enumeration exception when the supplied ancestor row cannot contain the requested group |
| `DateFragmentComponents` | closed component input union for explicit year-month, year, month, or month-day fragment construction |
| `DocumentCellValue` | the closed union accepted by typed document writes: an ordinary `A12Value`, a model-owned day-optional date, or a model-owned date fragment |
| `DocumentValueWriteError` | typed field-aware write exception carrying the target field, supplied `DocumentCellValue` kind, and exact failure reason |
| `ProgramPreparationError` | typed candidate-rule/expression preparation exception |
| `number` | a bounded native integer used for sizes, repetition coordinates, timeouts, and metadata such as scale; never a document decimal value |
| `boolean` | a native API flag; document Boolean values use `A12Boolean` |
| `undefined` | an optional API member is absent; it is not an empty document cell |
| `Error` | the standard JavaScript exception base extended by typed preparation, input, ownership, and service failures |
| `void` | a function or callback completes without returning a value |
| `unique symbol` | a declaration-only nominal-brand key that prevents accidental structural assignment at compile time; runtime authenticity comes from Kotlin-owned owner tokens and guards |
| `readonly T[]` | an immutable-view native array whose element type is the generic type parameter `T` |
| `Readonly<Record<K, V>>` | an immutable-view native key/value map with key type `K` and value type `V` |
| `Exclude<T, U>` | the built-in TypeScript type operation that removes every member assignable to `U` from union `T` |
| `A12Value` | the direct Operator API’s discriminated union of valid immutable A12 scalar values |
| `A12Result<T, E>` | the Operator API’s non-throwing success-`T`/error-`E` union |
| `A12Truth` | the Operator API’s evaluated-kernel-Boolean or explicit not-evaluated condition result; it is never silently collapsed to native `boolean` |
| `A12String` | an immutable supplied A12 text value, also used for stored enumeration codes |
| `A12Boolean` | an immutable wrapped two-state Boolean document value |
| `A12Confirm` | the distinct immutable filled-confirmation value; it is not Boolean false/true |
| `A12Number` | an exact arbitrary-precision A12 decimal; document amounts never use native `number` |
| `A12Integer` | an integral A12 number used for exact counts and offsets |
| `A12Date` | a complete calendar date |
| `A12Time` | a complete time of day |
| `A12DateTime` | a resolved local date-time retaining its supported time zone and exact instant |
| `A12DateRange` | an inclusive pair of complete `A12Date` endpoints |
| `A12TimeZone` | an explicitly supported model/operator time-zone identity |
| `A12BaseYear` | a validated base-year value with integer/date/range projections |
| `A12ConversionError` | the Operator API’s typed conversion exception, used when semantic value construction receives invalid components |
| `A12Operators` | the stateless direct value-operator namespaces from the Operator API |
| `PreparedModel` | one prepared owner domain containing or seeding model-only indexes; it contains no request document or caller callback instance and is not required to be shareable across threads or Workers |
| `PreparedModelConstructor` | the typed `PreparedModel.loadExpandedJson(source)` export used to create a prepared definition |
| `ModelVersionIdentity` | an opaque process/Worker-local identity for one exact registered or directly loaded model version; it is not a portable routing value |
| `ModelOwnerIdentity` | an opaque process/Worker-local authenticity domain shared only by objects that may safely interoperate; separate runtime lanes may use different owners for the same model version |
| `ModelOwnerMismatchError` | typed programmer error raised when two same-version or different-version model-owned runtime objects come from incompatible owner domains |
| `InterpreterConfiguration` | reusable direct-interpreter factory definition whose `createBindings` call returns one exclusive callback/registry set per active interpreter |
| `DirectInterpreterBindings` | callbacks and registries owned exclusively by one directly created `Interpreter`; the application retains external resource ownership |
| `InterpreterIntegrationPhase` | core `"bind"`/`"evaluate"` phase discriminator for binding/callback failures |
| `InterpreterIntegrationReason` | closed binding, thrown-callback, runtime-thenable, or same-lane-reentry reason |
| `InterpreterIntegrationError` | core error preserving the integration phase, structured detail, and original cause without depending on the optional service layer |
| `EvaluationClockProvider` | supplies the clock sample taken once at the start of an evaluation transaction |
| `LegalCharset` | opaque fallback legal-character policy used when the model does not provide a stricter policy |
| `PredefinedTypeRegistry` | opaque registry of supported predefined field-type implementations |
| `CustomFieldValidationParameters` | one custom-field invocation’s locale and optional model-declared minimum/maximum lengths |
| `CustomFieldValidationResult` | accepted or rejected result retaining the project error key and message |
| `CustomFieldConversionResult` | successful converted text or rejected retained text plus its conversion error message |
| `CustomFieldValidator` | synchronous validation and display/internal conversion contract for one custom field type |
| `CustomFieldTypeFactory` | name-resolving source of custom field validators; an unknown name returns `undefined` |
| `CustomFieldTypeRegistry` | immutable interpreter-owned resolver built from explicit entries or a `CustomFieldTypeFactory` |
| `CustomConditionValueOverride` | one field/raw-value override visible while a condition is evaluated, preserving the TypeScript kernel callback capability |
| `CustomConditionDocumentView` | read-only model-owned document, effective-value, and instantiated-row surface for a custom condition |
| `CustomConditionInvocation` | exact document/model identity, full-versus-partial relevance, formal-error, error-pointer, and value-override inputs for one condition call |
| `CustomCondition` | synchronous document-aware Boolean error-condition contract over a `CustomConditionInvocation` |
| `CustomConditionFactory` | name-resolving source of custom conditions; an unknown name returns `undefined` |
| `CustomConditionRegistry` | immutable interpreter-owned resolver built from explicit entries or a `CustomConditionFactory` |
| `CustomizationRegistrationError` | typed empty/duplicate-name failure raised while constructing an explicit customization registry |
| `Interpreter` | one configured active evaluator; the initial contract permits thread/worker confinement |
| `DocumentSnapshot` | a frozen document instance bound to one model owner; initially request/lane-confined |
| `DocumentStorage` | direct lossless storage access that performs no formal validation and invokes no customization callback |
| `DocumentInspection` | detached result of one full automatic-formal pass over a snapshot, with O(1)-style typed field queries and no repeated callbacks |
| `DocumentPointer` | a model-owned immutable path/coordinate pointer with an explicit field/group entity kind |
| `FieldPointer` / `GroupPointer` | narrowed document pointers accepted by field-only or group-only operations |
| `RelevantEntityPointer` | model-owned input selection pointer supporting concrete coordinates and explicit `ALL` repetition selection |
| `ReportPointer` | model-owned result pointer supporting concrete, wildcard, and partially known repetition coordinates without fabricating a cell |
| `A12EvaluationTime` | the Operator API’s immutable exact-instant/time-zone sample; interpreter calls reproject its instant into the prepared model’s zone |
| `ComputeOptions` | per-call computation options, including a message locale and an optional explicit `A12EvaluationTime` |
| `ComputeAndApplyResult` | applied/not-applied result with detached outcomes; only unsupported computation prevents the atomic update |
| `ProgramEvaluationOptions` | shared per-call locale and optional explicit evaluation time for rules/expressions |
| `ValidationOptions` | validation alias of `ProgramEvaluationOptions` |
| `InterpreterSupportReport` | configuration-aware, document-independent report of supported, unsupported, and source-factory-runtime-resolved model constructs |
| `ValidationReport` | detached findings, unsupported-program data, and independent error/support axes; it contains no live interpreter state |

The zero-customization path has no configuration setup:

```ts
type ExpandedModelJson = string;
type ModelFingerprint = string;

declare const modelVersionIdentityBrand: unique symbol;
declare const modelOwnerIdentityBrand: unique symbol;

type ModelVersionIdentity = {
  readonly [modelVersionIdentityBrand]: never;
};

type ModelOwnerIdentity = {
  readonly [modelOwnerIdentityBrand]: never;
};

interface ModelPreparationDiagnostic {
  readonly code: string;
  readonly path?: string;
  readonly message: string;
}

interface ModelPreparationError extends Error {
  readonly code: "A12_MODEL_PREPARATION_FAILED";
  readonly diagnostics:
    readonly ModelPreparationDiagnostic[];
}

type ModelPreparationResult =
  | {readonly ok: true; readonly model: PreparedModel}
  | {readonly ok: false; readonly error: ModelPreparationError};

interface ModelOwnerMismatchError extends Error {
  readonly code: "A12_MODEL_OWNER_MISMATCH";
  readonly expected: ModelOwnerIdentity;
  readonly actual: ModelOwnerIdentity;
}

type ModelEntityKind =
  | "field"
  | "group"
  | "rule"
  | "computation";

interface UnknownModelPathError extends Error {
  readonly code: "A12_UNKNOWN_MODEL_PATH";
  readonly entityKind: ModelEntityKind;
  readonly path: string;
}

type DocumentPlacementKind = "group" | "field";

type DocumentInputPathSegment = string | number;

type DocumentInputValueKind =
  | "object"
  | "array"
  | "string"
  | "number"
  | "boolean"
  | "null"
  | "undefined"
  | "other";

type DocumentInputResourceLimit =
  | "group-placements"
  | "field-placements"
  | "coordinates-per-placement"
  | "path-code-units"
  | "raw-code-units-per-field"
  | "total-raw-code-units"
  | "model-nesting-depth";

interface DocumentInputDiagnosticBase {
  readonly inputPath: readonly DocumentInputPathSegment[];
  readonly modelPath?: string;
  readonly placementKind?: DocumentPlacementKind;
  readonly placementIndex?: number;
  readonly message: string;
}

type DocumentInputDiagnostic =
  | DocumentInputDiagnosticBase & {
      readonly reasonCode: "invalid-native-type";
      readonly details: {
        readonly expected:
          readonly DocumentInputValueKind[];
        readonly received: DocumentInputValueKind;
      };
    }
  | DocumentInputDiagnosticBase & {
      readonly reasonCode: "unknown-property";
      readonly details: {
        readonly property: string;
        readonly allowedProperties:
          readonly string[];
      };
    }
  | DocumentInputDiagnosticBase & {
      readonly reasonCode: "missing-property";
      readonly details: {
        readonly property: string;
      };
    }
  | DocumentInputDiagnosticBase & {
      readonly reasonCode: "unsupported-version";
      readonly details: {
        readonly expected: 1;
        readonly actual:
          string | number | boolean | null;
      };
    }
  | DocumentInputDiagnosticBase & {
      readonly reasonCode: "invalid-coordinate";
      readonly details: {
        readonly coordinateIndex: number;
        readonly reason:
          | "not-number"
          | "not-safe-integer"
          | "not-positive";
      };
    }
  | DocumentInputDiagnosticBase & {
      readonly reasonCode: "resource-limit-exceeded";
      readonly details: {
        readonly limit: DocumentInputResourceLimit;
        readonly maximum: number;
        readonly actual: number;
      };
    }
  | DocumentInputDiagnosticBase & {
      readonly reasonCode: "unknown-model-path";
      readonly details: {
        readonly entityKind: DocumentPlacementKind;
        readonly attemptedPath: string;
      };
    }
  | DocumentInputDiagnosticBase & {
      readonly reasonCode: "coordinate-count-mismatch";
      readonly details: {
        readonly expectedCount: number;
        readonly actualCount: number;
      };
    }
  | DocumentInputDiagnosticBase & {
      readonly reasonCode: "duplicate-placement";
      readonly details: {
        readonly firstInputPath:
          readonly DocumentInputPathSegment[];
      };
    }
  | DocumentInputDiagnosticBase & {
      readonly reasonCode: "conflicting-placement";
      readonly details: {
        readonly conflictingInputPath:
          readonly DocumentInputPathSegment[];
        readonly conflict:
          | "field-values-differ"
          | "field-group-kind-mismatch";
      };
    };

type DocumentInputReasonCode =
  DocumentInputDiagnostic["reasonCode"];

interface DocumentInputError extends Error {
  readonly code: "A12_DOCUMENT_INPUT_INVALID";
  readonly diagnostics: readonly DocumentInputDiagnostic[];
}

type DocumentCoordinateReasonCode =
  | "coordinate-count-mismatch"
  | "coordinate-not-positive-safe-integer";

interface DocumentCoordinateError extends Error {
  readonly code: "A12_DOCUMENT_COORDINATES_INVALID";
  readonly reasonCode: DocumentCoordinateReasonCode;
  readonly path: string;
  readonly expectedCount: number;
  readonly coordinates: readonly number[];
}

interface DocumentRowScopeError extends Error {
  readonly code: "A12_DOCUMENT_ROW_SCOPE_INVALID";
  readonly groupPath: string;
  readonly within: GroupPointer;
}

type DocumentValueWriteReasonCode =
  | "field-kind-mismatch"
  | "value-not-exactly-representable";

interface DocumentValueWriteError extends Error {
  readonly code: "A12_DOCUMENT_VALUE_WRITE_FAILED";
  readonly reasonCode: DocumentValueWriteReasonCode;
  readonly pointer: FieldPointer;
  readonly fieldKind: FieldKind;
  readonly valueKind: DocumentCellValue["kind"];
}

interface ProgramPreparationError extends Error {
  readonly code: "A12_PROGRAM_PREPARATION_FAILED";
  readonly diagnostics:
    readonly ModelPreparationDiagnostic[];
}

interface PreparedModelConstructor {
  loadExpandedJson(source: ExpandedModelJson): PreparedModel;
  tryLoadExpandedJson(
    source: ExpandedModelJson,
  ): ModelPreparationResult;
}

declare const PreparedModel: PreparedModelConstructor;
declare const expandedModelJson: ExpandedModelJson;

const prepared: PreparedModel =
  PreparedModel.loadExpandedJson(expandedModelJson);
const interpreter: Interpreter =
  prepared.createInterpreter();
```

`expandedModelJson` has type `ExpandedModelJson`, the caller-supplied expanded model text. `PreparedModel` is the runtime constructor value whose TypeScript type is `PreparedModelConstructor`. `prepared` has type `PreparedModel`, the immutable model definition returned by the loader, and `interpreter` has type `Interpreter`, one active default-configured evaluator created from it. The declaration-only `modelVersionIdentityBrand` variable has type `unique symbol`; it exists only in generated typings to make `ModelVersionIdentity` nominal and has no application runtime value.

`loadExpandedJson` returns `PreparedModel` or throws `ModelPreparationError`; `tryLoadExpandedJson` returns the same diagnostics through `ModelPreparationResult` when model input is expected user data. `ModelOwnerMismatchError` is the structured core failure raised when a snapshot, pointer, or prepared-program handle belongs to a different owner domain; `expected` and `actual` are opaque `ModelOwnerIdentity` values for correlation, not model content. `UnknownModelPathError` reports the requested `ModelEntityKind` and path. `DocumentInputError` aggregates portable placement diagnostics. Every diagnostic carries `inputPath`, a path into the untrusted envelope, and may additionally carry `modelPath`, the attempted A12 path once it is identifiable; those two locations are never overloaded. Its discriminated `details` member carries the applicable expected/received type, version, count, limit, property, duplicate, or conflict data, so consumers and optional adapters never parse `message`. `message` is presentation only. `placementIndex` is zero-based into the corresponding input array when present. `DocumentCoordinateError` reports the exact model path, expected coordinate count, supplied native-number coordinates, and whether arity or a non-positive/non-safe-integer coordinate failed. `DocumentRowScopeError` carries the requested group path and the supplied ancestor’s `GroupPointer`. `DocumentValueWriteError` reports a field/value kind mismatch or a lossy field representation without silently changing the value. `ProgramPreparationError` reports invalid candidate-rule or expression source without misclassifying the whole model as invalid.

The default configuration uses a system clock sampled once per evaluation transaction, model-carried time zone/declared base year/charset, the kernel-compatible date-completion base-year default, the BMP fallback charset, built-in predefined types, and empty custom registries. Missing custom constructs remain explicit in evaluation results as unsupported; they are never silently ignored.

Applications that need customization pass a typed configuration:

```ts
interface EvaluationClockProvider {
  capture(): A12EvaluationTime;
}

type InterpreterIntegrationPhase =
  | "bind"
  | "evaluate";

type InterpreterIntegrationReason =
  | "binding-failed"
  | "callback-threw"
  | "thenable-returned"
  | "reentrant-evaluation";

interface InterpreterIntegrationError
  extends Error {
  readonly code:
    "A12_INTERPRETER_INTEGRATION_FAILED";
  readonly phase: InterpreterIntegrationPhase;
  readonly reason: InterpreterIntegrationReason;
  readonly detail: string;
  readonly cause?: unknown;
}

interface DirectInterpreterBindings {
  readonly clock?: EvaluationClockProvider;
  readonly predefinedTypes?: PredefinedTypeRegistry;
  readonly customFieldTypes?: CustomFieldTypeRegistry;
  readonly customConditions?: CustomConditionRegistry;
}

interface InterpreterConfiguration {
  readonly fallbackLegalCharset?: LegalCharset;
  readonly createBindings:
    () => DirectInterpreterBindings;
}

declare const legalCharsetBrand: unique symbol;
declare const predefinedTypeRegistryBrand: unique symbol;
declare const customFieldTypeRegistryBrand: unique symbol;
declare const customConditionRegistryBrand: unique symbol;

interface LegalCharset {
  readonly [legalCharsetBrand]: never;
  readonly id: string;
}

interface PredefinedTypeRegistry {
  readonly [predefinedTypeRegistryBrand]: never;
  readonly names: readonly string[];
}

interface CustomFieldValidationParameters {
  readonly locale: LocaleCode;
  readonly minLength?: number;
  readonly maxLength?: number;
}

type CustomFieldValidationResult =
  | {
      readonly kind: "accepted";
    }
  | {
      readonly kind: "rejected";
      readonly errorKey: string;
      readonly message: string;
    };

type CustomFieldConversionResult =
  | {
      readonly kind: "converted";
      readonly value: string;
    }
  | {
      readonly kind: "rejected";
      readonly value: string;
      readonly message: string;
    };

interface CustomFieldValidator {
  validate(
    value: string,
    parameters: CustomFieldValidationParameters,
    isDisplayValue: boolean,
  ): CustomFieldValidationResult;
  convertDisplayToInternal(
    displayValue: string,
  ): CustomFieldConversionResult;
  convertInternalToDisplay(
    internalValue: string,
  ): CustomFieldConversionResult;
}

interface CustomFieldTypeFactory {
  create(
    customFieldTypeName: string,
  ): CustomFieldValidator | undefined;
}

interface CustomFieldTypeRegistry {
  readonly [customFieldTypeRegistryBrand]: never;
  create(
    customFieldTypeName: string,
  ): CustomFieldValidator | undefined;
}

interface CustomConditionValueOverride {
  readonly pointer: FieldPointer;
  readonly raw: string;
}

interface CustomConditionDocumentView
  extends ModelOwned {
  readonly snapshot: DocumentSnapshot;
  readonly model: ModelMetadata;
  stored(
    pointer: FieldPointer,
  ): StoredCellInspection;
  effectiveValue(
    pointer: FieldPointer,
  ): CellEvaluationValue;
  instantiatedRows(
    group: GroupRef,
    within?: GroupRowRef,
  ): readonly GroupRowRef[];
}

interface CustomConditionInvocation {
  readonly document: CustomConditionDocumentView;
  readonly documentModelId: string;
  readonly relevantEntities:
    readonly RelevantEntityPointer[] | undefined;
  readonly formallyIncorrectEntities:
    readonly DocumentPointer[];
  readonly errorPointer: ReportPointer;
  readonly valueOverrides:
    readonly CustomConditionValueOverride[] | undefined;
}

interface CustomCondition {
  check(invocation: CustomConditionInvocation): boolean;
}

interface CustomConditionFactory {
  create(
    customConditionName: string,
  ): CustomCondition | undefined;
}

interface CustomConditionRegistry {
  readonly [customConditionRegistryBrand]: never;
  create(
    customConditionName: string,
  ): CustomCondition | undefined;
}

interface CustomFieldTypeRegistryConstructor {
  of(
    entries: readonly {
      readonly name: string;
      readonly validator: CustomFieldValidator;
    }[],
  ): CustomFieldTypeRegistry;
  fromMap(
    validators:
      Readonly<Record<string, CustomFieldValidator>>,
  ): CustomFieldTypeRegistry;
  fromFactory(
    factory: CustomFieldTypeFactory,
  ): CustomFieldTypeRegistry;
}

interface CustomConditionRegistryConstructor {
  of(
    entries: readonly {
      readonly name: string;
      readonly condition: CustomCondition;
    }[],
  ): CustomConditionRegistry;
  fromMap(
    conditions:
      Readonly<Record<string, CustomCondition>>,
  ): CustomConditionRegistry;
  fromFactory(
    factory: CustomConditionFactory,
  ): CustomConditionRegistry;
}

declare const CustomFieldTypes:
  CustomFieldTypeRegistryConstructor;
declare const CustomConditions:
  CustomConditionRegistryConstructor;

interface CustomizationRegistrationError
  extends Error {
  readonly code: "A12_CUSTOMIZATION_REGISTRATION";
  readonly customizationKind:
    | "predefined-field-type"
    | "custom-field-type"
    | "custom-condition";
  readonly reason:
    | "empty-name"
    | "duplicate-name";
  readonly name: string;
}

declare function systemClock(): EvaluationClockProvider;

declare const fallbackLegalCharset: LegalCharset;
declare const createPredefinedTypes:
  () => PredefinedTypeRegistry;
declare const createCustomFieldTypes:
  () => CustomFieldTypeRegistry;
declare const createCustomConditions:
  () => CustomConditionRegistry;

const configuration: InterpreterConfiguration = {
  fallbackLegalCharset,
  createBindings: (): DirectInterpreterBindings => {
    const clock: EvaluationClockProvider = systemClock();
    const predefinedTypes: PredefinedTypeRegistry =
      createPredefinedTypes();
    const customFieldTypes: CustomFieldTypeRegistry =
      createCustomFieldTypes();
    const customConditions: CustomConditionRegistry =
      createCustomConditions();
    const bindings: DirectInterpreterBindings = {
      clock,
      predefinedTypes,
      customFieldTypes,
      customConditions,
    };
    return bindings;
  },
};
const customizedInterpreter: Interpreter =
  prepared.createInterpreter(configuration);
```

`fallbackLegalCharset` has type `LegalCharset`, the opaque immutable fallback policy. `createPredefinedTypes`, `createCustomFieldTypes`, and `createCustomConditions` are externally supplied zero-argument binding-factory variables whose explicit return types are `PredefinedTypeRegistry`, `CustomFieldTypeRegistry`, and `CustomConditionRegistry`; they are distinct from `CustomFieldTypeFactory` and `CustomConditionFactory`, which resolve one implementation by its model name. `configuration` has type `InterpreterConfiguration`, a reusable factory definition. Its `createBindings` callback has the explicit return type `DirectInterpreterBindings`. `clock`, `predefinedTypes`, `customFieldTypes`, and `customConditions` are freshly created, explicitly typed values assembled into the `bindings` result; `customizedInterpreter` is the one active `Interpreter` that exclusively owns that result. The four `...Brand` declarations have type `unique symbol` and exist only to make the four opaque interfaces nominal; applications do not read runtime values from them.

`PreparedModel.createInterpreter(configuration)` calls `createBindings` exactly once and never shares its returned objects with another interpreter. Reusing one configuration is safe only because each call must return a fresh exclusive binding set; those returned clocks/registries need not be thread-safe. Concurrent `createInterpreter` calls may invoke the factory concurrently, so the factory itself must be share-safe or the application must serialize creation. Direct bindings have caller-owned external resource lifetime and no interpreter disposal callback; applications needing service-owned creation and cleanup use `InterpreterConfigurationRegistration` in §8.

The public customization surface is a P0 compatibility contract, not an optional plug-in afterthought. Existing projects already own substantial Custom Field Type and Custom Condition libraries, often implemented separately for JVM and TypeScript. The kernel-free interpreter therefore defines its own Kotlin Multiplatform SPI with the same observable jobs and callback data, while optional platform compatibility artifacts adapt existing kernel implementations to it. A new portable customization can be implemented once in Kotlin `commonMain` and projected to TypeScript and JVM when its own dependencies are multiplatform; host-specific implementations can target the corresponding projection directly. The core SPI must remain useful without any kernel artifact.

`CustomFieldValidator.validate` deliberately mirrors the modern callback shape: `value` is the native stored text, `parameters` carries the requested message locale plus optional `minLength` and `maxLength`, and `isDisplayValue` states whether conversion is required before checking. The portable `DocumentSnapshot` contract stores custom values in internal form, so direct core validation passes `false`; form/import code converts display text through `convertCustomFieldDisplayToInternal` before writing. A compatibility adapter may pass `true` only when its audited native presentation state requires that exact callback input. Accepted validation returns no fabricated value; rejection preserves both the consumer-defined `errorKey` and message. `convertDisplayToInternal` and `convertInternalToDisplay` return either the converted native string or the retained input plus its conversion error. Identity conversions remain valid implementations. The evaluator owns the field pointer and model constraints around the invocation; they are not invented callback inputs.

`CustomCondition.check` deliberately has no expression arguments. Its `CustomConditionInvocation.document` exposes the complete model-owned snapshot, model metadata/navigation, lossless stored values, effective values including any call-scoped override, and instantiated rows; `documentModelId` preserves the authored model identity exposed by the TypeScript kernel callback. `relevantEntities` is `undefined` for full validation. For TypeScript `validatePart`, it is the exact callback view produced from the caller’s array after entity-identity conversion/deduplication and global-field inclusion; group selectors are not pre-expanded into descendants, and wildcard forms remain present. A truly empty effective relevance set returns before invoking a Custom Condition, so the adapter must not invent an empty-array callback when the source façade would short-circuit; a caller-provided empty array can reach callbacks only when source-defined normalization, such as global-field inclusion, makes the effective set non-empty. The JVM adapter has its own source-backed set-conversion and empty-set short-circuit laws rather than inheriting the TypeScript rule by assumption. `formallyIncorrectEntities` contains the exact source callback set after pointer conversion; its admissible pointer variants are fixed by the platform audit rather than narrowed to fields or groups by assumption. `errorPointer` is the containing rule’s error field and may contain a partially known repetition. `valueOverrides` preserves the TypeScript kernel façade’s call-scoped entity-value mapping; it is `undefined` when no mapping exists. The callback returns native `boolean`; `true` means the error condition is met and the containing rule fires. A callback must return `false` itself when required input is irrelevant, formally incorrect, or does not satisfy the project condition. The interpreter must not replace this observable contract with `A12Truth`, a generic refinement callback, an argument list, a locale, or an evaluation-time parameter. Platform adapter laws lock set membership, duplicates, order exposure, short-circuiting, and pointer/wildcard conversion exactly.

`CustomFieldTypes.of` and `CustomConditions.of` create immutable name registries from explicit entries and reject an empty or duplicate name with `CustomizationRegistrationError` and its exact `"empty-name"` or `"duplicate-name"` reason. `fromMap` is the object-map convenience and accepts own enumerable keys only. `fromFactory` adapts a name-resolving factory whose `create` method returns `undefined` for an unsupported name without throwing. When a native interpreter lane is created, it enumerates the custom names used by the prepared model, creates each required implementation once for that lane/configuration, and stores direct implementation slots in its prepared runtime state. Evaluation performs no registry scan, name lookup, reflection, or adapter discovery at a field or AST node. Missing native names appear in `supportReport()` and in the normal unsupported result channel.

Before freezing the ABI, every member and lifecycle rule of `CustomFieldValidator`, `CustomCondition`, both factories, both registries, and `CustomConditionDocumentView` must be audited against the modern kernel 30.8.1 JVM interfaces and the target TypeScript kernel interfaces used by real projects. Java and TypeScript are separate compatibility audits because their existing APIs are not structurally identical: JVM document-model conditions receive `DocumentV2`, nullable pointer sets, and `PartiallyKnownDocumentMultiPointer`; the TypeScript document-model façade receives its native `Document`, a model ID, arrays, and an entity-value mapping; static generated-runtime conditions over `IData`/`IIdentifier` are a third distinct JVM surface and are not implicitly covered by the `DocumentV2` adapter. Field-type factory names, validation parameters, presentation information, and localized error representations also differ. The initial `CustomConditionDocumentView` is the typed observable subset supported by the current audit, not a claim that arbitrary native document APIs have been recreated; production audits may require additional model navigation or storage queries before compatibility can be declared. The laws must preserve exact invocation order and cardinality, repetition scope, partial-relevance input, formal-error filtering, partially known error pointers, value overrides, value normalization, length semantics, `isDisplayValue`, conversion behavior, error identity/message, ordinary rejection or false versus callback exception, and cleanup after partial failure. If a production callback can observe another required fact, the core SPI gains an explicit typed member rather than a generic map.

For native core callbacks, a callback exception is `InterpreterIntegrationError`; it is never converted into ordinary rejection, unsupported semantics, or false. Each kernel-compatibility adapter must separately differential-test the source platform’s exception behavior and preserve it at that adapter’s public boundary instead of claiming the core mapping is automatically identical. There is no Custom Operator SDK or operator-registration mechanism in this proposal. Operators remain the fixed, catalog-governed semantic surface from the [Operator API proposal](OPERATOR-API-PROPOSAL.md).

All callbacks are synchronous. Their TypeScript signatures exclude `Promise`; plain JavaScript or unsafe TypeScript that nevertheless returns a thenable fails at the actual callback boundary with `InterpreterIntegrationError`, is never awaited, and retires the affected lane. Callback sets belong exclusively to one active interpreter lane unless a future explicitly share-safe configuration contract is introduced. Direct callers retain external resource ownership; service registrations receive the exactly-once disposal contract in §8.

An active `Interpreter` is not recursively reentrant. If a custom callback calls `validateFull`, `validatePart`, `compute`, inspection, or custom-field conversion on the same lane, the inner operation fails with `InterpreterIntegrationError` reason `"reentrant-evaluation"` before sampling time, mutating caches, or pushing/replacing native adapter call context. A callback may call a separately acquired lane subject to ordinary service/pool rules. Compatibility tests cover direct reentry, nested failure, and `finally` cleanup so an outer original document or value mapping cannot be overwritten.

The two-step API is intentional. `openExpandedModel(json, configuration)` may exist as a convenience, but it must not hide the reusable prepared-model boundary.

Direct prepared/interpreter objects own memory only and need no per-evaluation open/close lifecycle. Scoped acquire/release exists only in the optional pooled service layer.

#### Collected core callable contract

The following interfaces collect the methods used throughout this proposal so their parameters, optionality, and return types do not have to be inferred from examples:

```ts
declare const modelOwnedBrand: unique symbol;

interface ModelOwned {
  readonly [modelOwnedBrand]: never;
  readonly owner: ModelOwnerIdentity;
  readonly modelVersion: ModelVersionIdentity;
}

interface PreparedModel extends ModelOwned {
  readonly fingerprint: ModelFingerprint;
  readonly model: ModelMetadata;
  readonly documents: PreparedDocumentApi;

  createInterpreter(
    configuration?: InterpreterConfiguration,
  ): Interpreter;
}

type FieldKind =
  | "string"
  | "number"
  | "boolean"
  | "confirm"
  | "date"
  | "time"
  | "date-time"
  | "date-range";

type FieldFormat = string;

interface RepetitionLevel {
  readonly path: string;
  readonly maximum?: number;
}

interface Repeatability {
  readonly levels: readonly RepetitionLevel[];
}

type Requiredness =
  | "not-required"
  | "absolute"
  | "relative-to-parent";

interface CommonFieldConstraints {
  readonly declaredRequiredness: Requiredness;
  readonly effectiveRequiredness: Requiredness;
  readonly global: boolean;
}

interface StringFieldConstraints {
  readonly minLength?: number;
  readonly maxLength?: number;
  readonly pattern?: string;
  readonly lineBreaksPermitted: boolean;
  readonly valueValidationEnabled: boolean;
}

interface CustomFieldConstraints {
  readonly minLengthHint?: number;
  readonly maxLengthHint?: number;
}

interface NumberFieldConstraints {
  readonly minValue?: A12Number;
  readonly maxValue?: A12Number;
  readonly minFractionalDigits?: number;
  readonly maxIntegerDigits?: number;
  readonly minLength?: number;
  readonly maxLength?: number;
  readonly signed: boolean;
  readonly leadingZerosAllowed: boolean;
  readonly zeroAllowed: boolean;
}

type DateFieldPrecision = "full" | "day-optional";

type FieldDeclaration =
  | {
      readonly kind: "string";
      readonly constraints: StringFieldConstraints;
    }
  | {
      readonly kind: "enumeration";
      readonly values: readonly string[];
      readonly categories:
        Readonly<Record<string, readonly string[]>>;
      readonly constraints: StringFieldConstraints;
    }
  | {
      readonly kind: "custom";
      readonly typeName: string;
      readonly constraints: CustomFieldConstraints;
    }
  | {
      readonly kind: "number";
      readonly scale: number;
      readonly constraints: NumberFieldConstraints;
    }
  | {readonly kind: "boolean"}
  | {readonly kind: "confirm"}
  | {
      readonly kind: "date";
      readonly format: FieldFormat;
      readonly precision: DateFieldPrecision;
    }
  | {
      readonly kind: "date-fragment";
      readonly shape: DateFragmentShape;
    }
  | {
      readonly kind: "time";
      readonly format: FieldFormat;
    }
  | {
      readonly kind: "date-time";
      readonly format: FieldFormat;
    }
  | {
      readonly kind: "date-range";
      readonly format: FieldFormat;
      readonly separator: string;
    };

interface ModelMetadata extends ModelOwned {
  readonly documentModelId: string;
  readonly timeZone: A12TimeZone;
  readonly declaredBaseYear?: A12BaseYear;
  readonly effectiveDateCompletionBaseYear: A12BaseYear;

  requireField(path: string): FieldMetadata;
  findField(path: string): FieldMetadata | undefined;
  requireGroup(path: string): GroupRef;
  findGroup(path: string): GroupRef | undefined;

  fields(): readonly FieldMetadata[];
  groups(): readonly GroupMetadata[];
  rootFields(): readonly FieldMetadata[];
  rootGroups(): readonly GroupMetadata[];
  rules(): readonly RuleMetadata[];
  computations(): readonly ComputationMetadata[];

  pointer(
    path: string,
    coordinates: readonly number[],
  ): DocumentPointer;
  relevantPointer(
    path: string,
    coordinates: readonly RelevantCoordinate[],
  ): RelevantEntityPointer;
}

interface FieldMetadata extends ModelOwned {
  readonly path: string;
  readonly kind: FieldKind;
  readonly format?: FieldFormat;
  readonly repeatability: Repeatability;
  readonly computed: boolean;
  readonly commonConstraints: CommonFieldConstraints;
  readonly declaration: FieldDeclaration;
  readonly labels:
    Readonly<Record<LocaleCode, string>>;
}

interface GroupMetadata extends ModelOwned {
  readonly path: string;
  readonly repeatability: Repeatability;
  readonly indexField?: FieldMetadata;

  fields(): readonly FieldMetadata[];
  childGroups(): readonly GroupMetadata[];
}

interface RuleMetadata extends ModelOwned {
  readonly path: string;
  readonly group: string;
  readonly code: string;
  readonly severity: ValidationSeverity;
  readonly messages:
    Readonly<Record<LocaleCode, string>>;
}

interface ComputationMetadata extends ModelOwned {
  readonly targetPath: string;
  readonly group: string;
  readonly name?: string;
}

interface GroupRef extends GroupMetadata {
  row(...coordinates: number[]): GroupRowRef;
}

interface GroupRowRef extends ModelOwned {
  readonly pointer: GroupPointer;
  requireField(
    relativePath: string,
  ): FieldInstanceRef;
  findField(
    relativePath: string,
  ): FieldInstanceRef | undefined;
}

interface FieldInstanceRef extends ModelOwned {
  readonly metadata: FieldMetadata;
  readonly pointer: FieldPointer;
}

interface DocumentPointerBase extends ModelOwned {
  readonly kind: "document-pointer";
  readonly path: string;
  readonly coordinates: readonly number[];
}

interface GroupPointer extends DocumentPointerBase {
  readonly entityKind: "group";
}

interface FieldPointer extends DocumentPointerBase {
  readonly entityKind: "field";
}

declare const customFieldPointerBrand: unique symbol;

interface CustomFieldPointer extends FieldPointer {
  readonly [customFieldPointerBrand]: never;
  readonly declarationKind: "custom";
  readonly customTypeName: string;
}

type DocumentPointer = GroupPointer | FieldPointer;

declare const allRepetitionsBrand: unique symbol;

interface AllRepetitions {
  readonly [allRepetitionsBrand]: never;
  readonly kind: "all";
}

declare const ALL: AllRepetitions;

type RelevantCoordinate = number | AllRepetitions;

interface RelevantSelectionPointer extends ModelOwned {
  readonly kind: "relevant-selection-pointer";
  readonly entityKind: DocumentPlacementKind;
  readonly path: string;
  readonly coordinates: readonly RelevantCoordinate[];
}

type RelevantEntityPointer =
  | DocumentPointer
  | RelevantSelectionPointer;

declare const unknownRepetitionBrand: unique symbol;

interface UnknownRepetition {
  readonly [unknownRepetitionBrand]: never;
  readonly kind: "unknown";
}

declare const UNKNOWN_REPETITION: UnknownRepetition;

type ReportCoordinate =
  | number
  | AllRepetitions
  | UnknownRepetition;

interface ReportSelectionPointer extends ModelOwned {
  readonly kind: "report-selection-pointer";
  readonly entityKind: DocumentPlacementKind;
  readonly path: string;
  readonly coordinates:
    readonly ReportCoordinate[];
}

type ReportPointer =
  | DocumentPointer
  | ReportSelectionPointer;

type DateFragmentComponents =
  | {
      readonly granularity: "year-month";
      readonly year: A12Integer;
      readonly month: A12Integer;
    }
  | {
      readonly granularity: "year";
      readonly year: A12Integer;
    }
  | {
      readonly granularity: "month";
      readonly month: A12Integer;
    }
  | {
      readonly granularity: "month-day";
      readonly month: A12Integer;
      readonly day: A12Integer;
    };

type DocumentCellValue =
  | A12Value
  | OptionalDateCellValue
  | DateFragmentCellValue;

interface DocumentSnapshot extends ModelOwned {
  readonly storage: DocumentStorage;
  instantiatedRows(
    group: GroupRef,
    within?: GroupRowRef,
  ): readonly GroupRowRef[];
}

interface DocumentStorage extends ModelOwned {
  cell(pointer: FieldPointer): StoredCellInspection;
  cells(
    pointers: readonly FieldPointer[],
  ): readonly StoredCellInspection[];
}

interface DocumentInspection extends ModelOwned {
  cell(pointer: FieldPointer): CellInspection;
  cells(
    pointers: readonly FieldPointer[],
  ): readonly CellInspection[];
}

interface PreparedDocumentApi extends ModelOwned {
  isSnapshot(value: unknown): value is DocumentSnapshot;
  decodePlacements(
    input: unknown,
    options?: DocumentDecodeOptions,
  ): DocumentSnapshot;
  tryDecodePlacements(
    input: unknown,
    options?: DocumentDecodeOptions,
  ): A12Result<DocumentSnapshot, DocumentInputError>;
  readPlacements(
    input: PlacementDocumentInputV1,
    options?: DocumentDecodeOptions,
  ): DocumentSnapshot;
  encodePlacements(
    snapshot: DocumentSnapshot,
  ): PlacementDocumentInputV1;
  create(): DocumentBuilder;
  dayOptionalDate(
    year: A12Integer,
    month: A12Integer,
  ): OptionalDateCellValue;
  tryDayOptionalDate(
    year: A12Integer,
    month: A12Integer,
  ): A12Result<OptionalDateCellValue, A12ConversionError>;
  dateFragment(
    field: FieldMetadata,
    components: DateFragmentComponents,
  ): DateFragmentCellValue;
  tryDateFragment(
    field: FieldMetadata,
    components: DateFragmentComponents,
  ): A12Result<DateFragmentCellValue, A12ConversionError>;
}

interface PreparedDocumentEditingApi
  extends PreparedDocumentApi {
  edit(
    snapshot: DocumentSnapshot,
  ): PersistentDocumentEditor;
}

interface PreparedModelWithDocumentEditing
  extends PreparedModel {
  readonly documents: PreparedDocumentEditingApi;
}

interface DocumentBuilder extends ModelOwned {
  addGroup(pointer: GroupPointer): void;
  removeGroup(pointer: GroupPointer): void;
  putRaw(
    pointer: FieldPointer,
    raw: string,
  ): void;
  putValue(
    pointer: FieldPointer,
    value: DocumentCellValue,
  ): void;
  removeField(pointer: FieldPointer): void;
  freeze(): DocumentSnapshot;
}

interface PersistentDocumentEditor extends ModelOwned {
  addGroup(pointer: GroupPointer): void;
  removeGroup(pointer: GroupPointer): void;
  putRaw(
    pointer: FieldPointer,
    raw: string,
  ): void;
  putValue(
    pointer: FieldPointer,
    value: DocumentCellValue,
  ): void;
  removeField(pointer: FieldPointer): void;
  freeze(): DocumentSnapshot;
}

interface Interpreter extends ModelOwned {
  captureEvaluationTime(): A12EvaluationTime;
  supportReport(): InterpreterSupportReport;
  convertCustomFieldDisplayToInternal(
    pointer: CustomFieldPointer,
    displayValue: string,
  ): CustomFieldConversionResult;
  convertCustomFieldInternalToDisplay(
    pointer: CustomFieldPointer,
    internalValue: string,
  ): CustomFieldConversionResult;
  inspectDocument(
    snapshot: DocumentSnapshot,
    options?: InspectionOptions,
  ): DocumentInspection;
  resolveOptionalDate(
    value: OptionalDateCellValue,
    boundary: OptionalDateBoundary,
  ): A12Date;

  validateFull(
    snapshot: DocumentSnapshot,
    options?: ValidationOptions,
  ): ValidationReport;
  validatePart(
    snapshot: DocumentSnapshot,
    relevant: readonly RelevantEntityPointer[],
    options?: ValidationOptions,
  ): ValidationReport;
  evaluateStoredRule(
    rulePath: string,
    snapshot: DocumentSnapshot,
    options?: ProgramEvaluationOptions,
  ): StoredRuleEvaluation;

  compute(
    snapshot: DocumentSnapshot,
    options?: ComputeOptions,
  ): ComputationReport;
  computeField(
    fieldPath: string,
    snapshot: DocumentSnapshot,
    options?: ComputeOptions,
  ): ComputationReport;
  computeAndApply(
    snapshot: DocumentSnapshot,
    options?: ComputeOptions,
  ): ComputeAndApplyResult;
  computeAndValidate(
    snapshot: DocumentSnapshot,
    options?: ComputeAndValidateOptions,
  ): ComputeAndValidateResult;
}

interface PreparedProgramModel extends PreparedModel {
  createInterpreter(
    configuration?: InterpreterConfiguration,
  ): PreparedProgramInterpreter;
  prepareRule(
    definition: CandidateRuleDefinition,
  ): PreparedRule;
  prepareNumberExpression(
    definition: NumberExpressionDefinition,
  ): PreparedNumberExpression;
}

interface PreparedProgramInterpreter
  extends Interpreter {
  evaluateRule(
    rule: PreparedRule,
    snapshot: DocumentSnapshot,
    options?: ProgramEvaluationOptions,
  ): CandidateRuleEvaluation;
  evaluateExpression(
    expression: PreparedNumberExpression,
    snapshot: DocumentSnapshot,
    location: EvaluationLocation,
    options?: ProgramEvaluationOptions,
  ): ExpressionEvaluation<A12Number>;
}
```

`ModelOwned` is a nominal library-issued marker carrying both the exact local model version and the owner domain that controls authenticity. Its module-private brand prevents accidental object-literal assignment in checked TypeScript, but `any` and type assertions can bypass it. Every public operation therefore validates Kotlin-owned runtime owner tokens; static brands are never the security boundary. Objects with the same `modelVersion` are still incompatible when their `owner` values differ. `PreparedDocumentApi` is the authoritative model-bound document codec and builder factory. `PreparedModel` owns one local owner domain, but neither that object nor its internal prepared representation is required to be concurrently shareable. `Interpreter` is the configured, initially thread-confined core evaluator. Its path parameters are native `string` values validated against that prepared model; options are optional because their documented defaults are complete. All methods are synchronous and return detached results or frozen snapshots.

`ModelMetadata` is the read-only definition view. Its `documentModelId` is the exact model identity supplied to the existing TypeScript Custom Condition callback; strict preparation must preserve it rather than derive it from a service alias or fingerprint. `fields()` and `groups()` return flat, deterministic model-order views; `rootFields()` and `rootGroups()` start structural traversal. Each `GroupMetadata` exposes its direct `fields()`, direct `childGroups()`, and optional configured `indexField`. That index-field relationship tells a dynamic client which direct child keys the group’s rows; the interpreter also auto-checks its non-empty values for uniqueness within each parent-row scope. `FieldMetadata`, `GroupMetadata`, `RuleMetadata`, and `ComputationMetadata` are immutable descriptions. `FieldKind` is the closed evaluator-value projection: string, enumeration, and custom declarations project to `"string"`; date and date-fragment declarations project to `"date"`; every other declaration projects to its same-named semantic kind. `FieldDeclaration` is the separate discriminated declared type a dynamic form or mapper needs before any value exists, so its enumeration, custom, date-fragment, and optional-date cases are not collapsed.

`CommonFieldConstraints.declaredRequiredness` is exactly the field’s authored setting. `effectiveRequiredness` includes the kernel’s generated index-field rule: when an index field is authored as `"not-required"`, its effective value is `"relative-to-parent"`; an explicitly required index field retains that explicit mode. `"absolute"` means always required when there is no repeatable ancestor and required once the lowest repeatable-ancestor row exists otherwise. `"relative-to-parent"` means required when the immediate parent group is filled; a created repeatable parent row counts as filled even when it has no valued cell. `"not-required"` means no mandatory check after index-field expansion. `global` is the model’s global-relevance flag.

`StringFieldConstraints` and `NumberFieldConstraints` expose the exact typed bounds applicable to those declarations. `CustomFieldConstraints` carries only the model’s optional minimum/maximum-length hints for a custom validator; a custom declaration does not inherit the built-in string type’s pattern, newline, or `valueValidationEnabled` contract, and its validator owns length acceptance. A number declaration’s required `scale: number` is its single effective maximum fractional-digit count; strict preparation supplies `0` when the legal source omits scale. It is not repeated as an optional top-level field property or a second `maxFractionalDigits` constraint. `FieldFormat` is the model’s native format text. `Repeatability.levels` lists each repeatable ancestor in path order; an empty array means non-repeatable, and `RepetitionLevel.maximum` is the optional positive safe-integer upper bound. `GroupRef`, `GroupRowRef`, and `FieldInstanceRef` are validated navigation handles. `DocumentPointer` identifies one concrete entity instance and is narrowed by `FieldPointer`/`GroupPointer`; `CustomFieldPointer` is the nominal library-issued `FieldPointer` subtype whose declaration is provably custom and whose `customTypeName` identifies that declaration. `customFieldPointerBrand` prevents accidental structural relabeling in checked TypeScript; runtime operations still verify the Kotlin-owned pointer/owner token. `RelevantSelectionPointer` may additionally carry `ALL`, the nominal typed wildcard value, and `RelevantEntityPointer` accepts either form. `ReportPointer` separately permits concrete coordinates, `ALL`, and `UNKNOWN_REPETITION`, because a kernel finding may identify an authored wildcard or a partially known repetition rather than one fabricated concrete cell. `DateFragmentComponents` makes every supported fragment component set explicit and uses `A12Integer` rather than native numbers. `DocumentCellValue` is the typed-write union: ordinary direct-operator values plus model-owned incomplete date representations that cannot honestly be represented as an `A12Value`. Every numeric coordinate and numeric metadata bound is a safe integer, with positive repetition indexes and non-negative length/digit/scale limits. `Readonly<Record<LocaleCode, string>>` is an immutable locale-code-to-text map.

`ModelMetadata.declaredBaseYear` is present only when the model declares `modelInfo.baseYear`; it controls whether the expression constant `BaseYear` and other expression forms that require a declared base year are legal. `effectiveDateCompletionBaseYear` is always present and completes missing years in `DateFragmentType` values and partial `DateRangeType` endpoints; it equals the declared year or the kernel-compatible default year `2000`. Absence of `declaredBaseYear` must therefore never make those expressions legal by borrowing this completion default.

`DocumentSnapshot` is a frozen instance. Its `storage` member and row enumeration read stored placements directly without running formal checks or invoking custom validators. `DocumentInspection` is the distinct detached, model-owned index produced by one complete automatic-formal pass; its `cell` and `cells` queries never reevaluate the document or reinvoke a callback. `DocumentBuilder` and `PersistentDocumentEditor` are single-owner mutable construction helpers whose `freeze()` methods return a new snapshot. Their typed removal operations restore field absence or remove a complete group subtree without exposing placement internals. `PreparedDocumentApi.isSnapshot(value: unknown)` authenticates a snapshot issued by this exact prepared owner and must not rely on structural typing or `instanceof` alone. `decodePlacements` is the authoritative `unknown` ingress, `tryDecodePlacements` returns the same diagnostics without throwing, `readPlacements` is the already-validated-DTO fast path, and public `encodePlacements` is the canonical portable output. `PreparedDocumentApi.dayOptionalDate`/`tryDayOptionalDate` construct the supported absent-day value, while `dateFragment`/`tryDateFragment` construct an explicit fragment for one same-model `FieldMetadata` declaration. Optional capabilities are separate types rather than mandatory core members: `PreparedModelWithDocumentEditing` adds the persistent editor, while `PreparedProgramModel` and `PreparedProgramInterpreter` add candidate-rule and expression preparation/evaluation. A package that ships either optional capability must expose a capability-specific loader or adapter returning that extension type; the core `PreparedModel` constructor continues to promise only the core interface.

### 3.2 Static versus configured state

One prepared owner may own or seed only state derived from the expanded model:

- the loaded model definition;
- model-declared time zone, declared base year, effective date-completion base year, supported characters, field kinds, constraints, and reference policy;
- stored rule and computation parse plans/caches;
- iteration scopes and model-only indexes;
- computation dependency information that is independent of runtime callbacks;
- Kotlin-owned runtime model-version and owner identities, with declaration-only brands for accidental-misuse prevention.

The current `DmInterpreter` does not have this split. Its `ParsedModel` and iteration scopes are model-static, while `AutoChecks` depends on the effective charset/type registries and `ComputationEngine` captures the clock and type registries. Introducing `PreparedModel` therefore requires a deliberate refactor: keep configured collaborators on each `Interpreter`.

The public contract deliberately permits three implementation strategies:

| Strategy | Owner relationship | Appropriate when |
|---|---|---|
| Shared immutable plan | several confined interpreters share one `PreparedModel` and `ModelOwnerIdentity` | safe publication, lazy parse, and every cache are proven concurrency-safe without warmup or traversal regression |
| Worker-confined prepared model | one Node Worker or fixed JVM worker owns one `PreparedModel` and its warm lanes | lock-free local reuse is more valuable than cross-worker sharing |
| Lane-owned prepared model | every exclusive runtime lane has its own `PreparedModel`, owner identity, AST/index caches, and document codec | isolation is simpler or sharing would compromise exactness, startup, warmup, or throughput |

Sharing is an optimization, not a public prerequisite. Independent copies cost memory and aggregate warmup; sharing requires safe publication and concurrency-proof lazy initialization. The selected implementation must be decided by correctness, D1/D2 reuse, cold/warm latency, allocation, retained memory, and concurrent-throughput evidence.

Clock providers, locale, fallback charset, predefined/custom field-type implementations, custom-condition implementations, documents, evaluation results, and per-call caches never belong to `PreparedModel`.

The prepared definition may record the unique custom names used by the model, but only `Interpreter` creation resolves them against that lane’s registries. The bound runtime then stores direct validator/condition slots beside the applicable field metadata and condition nodes. A compatibility adapter may instead put a constant-name lifecycle-preserving provider in such a slot when the audited source factory is observably required to create a fresh callback more often; it still performs no registry search or dynamic adapter discovery in the AST. This separation lets two interpreters over one model use different project implementations without reintroducing name lookup into evaluation.

### 3.3 Ownership

Two local identities answer different questions:

- `ModelVersionIdentity` says which exact local model version an object describes.
- `ModelOwnerIdentity` says which prepared owner may authenticate and consume the runtime object.

Snapshots, pointers, metadata handles, and prepared program handles bind to both. Equal fingerprints, equal source bytes, equal paths, or equal model-version routing tokens do not make objects from different owners interoperable. Direct independent loads receive distinct local identities even for equal bytes.

Two interpreters created by the same `PreparedModel` may reuse one snapshot sequentially with different clocks or registries. Concurrent snapshot reuse is allowed only if that snapshot implementation is explicitly certified share-safe; the v1 service keeps request documents confined to one active lane.

```ts
declare const prepared: PreparedModel;
declare const configurationA: InterpreterConfiguration;
declare const configurationB: InterpreterConfiguration;
declare const snapshot: DocumentSnapshot;

const interpreterA: Interpreter =
  prepared.createInterpreter(configurationA);
const interpreterB: Interpreter =
  prepared.createInterpreter(configurationB);

const reportA: ValidationReport =
  interpreterA.validateFull(snapshot);
const reportB: ValidationReport =
  interpreterB.validateFull(snapshot);
```

`prepared` has type `PreparedModel`, one local owner used sequentially in this example. `configurationA` and `configurationB` have type `InterpreterConfiguration`; each is a factory definition that supplies a fresh exclusive `DirectInterpreterBindings` set. `snapshot` has type `DocumentSnapshot`, one frozen instance owned by `prepared`. `interpreterA` and `interpreterB` are separate active `Interpreter` instances in the same owner domain, and `reportA`/`reportB` are detached `ValidationReport` results produced under their respective configurations.

A snapshot or handle carrying a different `ModelOwnerIdentity` fails with `ModelOwnerMismatchError` even when its model version and paths match. A service that uses lane-owned prepared models must therefore decode a portable placement DTO only after selecting the lane; it cannot decode once and pass that snapshot among lanes. A service may share snapshots or model-bound codecs across lanes only when those lanes deliberately and provably share one owner domain.

If preparation of an ad hoc rule depends on callback availability, either callback resolution must be deferred to evaluation or the handle must be explicitly interpreter-bound. The API must not claim both forms of ownership at once.

Creating or borrowing an interpreter over an existing definition must be cheap after the selected strategy is warm. A service can retain several active instances for one model and registered interpreter configuration; no instance is concurrently entered.

### 3.4 Concurrency choices

The API should not force one concurrency architecture:

| Option | Advantages | Costs |
|---|---|---|
| One reentrant interpreter shared by all workers | lowest instance count; maximal cache reuse | hardest proof; callbacks and mutable caches must be thread-safe; locks can destroy scaling |
| Pool of warm thread-confined interpreters per model | strong isolation; non-thread-safe callbacks are usable; bounded concurrency | several active instances; pool coordination and some duplicated state |
| One model/interpreter cache per worker | lock-free hot path; closest to current `DmInterpreter` | duplicated models/AST warmup; uneven worker caches; worker lifecycle management |
| Route each model to an actor/shard | simple confinement and warm ownership | queueing, load imbalance, async service architecture; several shards are needed for one hot model |

The recommended core contract is a warm, exclusive interpreter lane. On the JVM, a bounded pool can lease several lanes for one hot `(model version, configuration)` key concurrently. A fixed JVM worker may instead keep a bounded per-worker D1/D2 LRU. In one Node Worker, synchronous evaluation cannot overlap, so one warm lane per local `(model version, configuration)` key is the default; CPU throughput comes from more Workers, not several idle same-key lanes in one Worker.

The service façade can hide whether a lane shares a plan or owns an independent prepared model, but it cannot hide owner rules: model-owned snapshots and codecs stay in their owner domain. The choice follows workload measurements rather than becoming part of the evaluator ABI.

The implementation may share a truly immutable compiled plan between lanes when doing so passes the no-regression and concurrency gates. A fully reentrant interpreter can be offered later if it earns its simpler call site through evidence. Neither is required for the service API.

Node Workers do not share ordinary Kotlin/JS objects; each Worker owns its prepared owners, local identities, callbacks, document codecs, and runtime cache. The JVM service adapter is where a shared bounded registry plus exclusive-lane pool is most directly useful.

### 3.5 Time

A cached model must never freeze `Today` or `Now`. A system clock provider is sampled once at the start of each public evaluation transaction so every rule and computation in that transaction sees the same instant.

`A12EvaluationTime` carries one exact instant plus the time-zone projection in which it was captured. Before model evaluation, the interpreter reprojects that instant into `prepared.model.timeZone`; `Today` and `Now` are then derived in the model’s zone. The input sample’s zone is therefore not a request-time override of the model. The same sample passed to two models denotes the same instant, while their `Today` values may differ legitimately because their model zones differ.

`Interpreter.captureEvaluationTime()` captures the configured provider exactly once and returns an `A12EvaluationTime` already projected into that interpreter’s model zone. Supplying `ProgramEvaluationOptions.evaluationTime` or `ComputeOptions.evaluationTime` bypasses another clock read but still performs the same instant-preserving model-zone projection. This normalization is specific to model evaluation; direct `operators.pointInTime.today(sample)` and `operators.pointInTime.now(sample)` use the sample’s own explicit zone as specified by the [Operator API proposal](OPERATOR-API-PROPOSAL.md).

```ts
declare const interpreter: Interpreter;
declare const snapshot: DocumentSnapshot;

const evaluationTime: A12EvaluationTime =
  interpreter.captureEvaluationTime();
const computeOptions: ComputeOptions = {
  locale: "en_US",
  evaluationTime,
};
const computed: ComputeAndApplyResult =
  interpreter.computeAndApply(snapshot, computeOptions);

const validationOptions: ValidationOptions = {
  evaluationTime,
};

if (computed.kind === "applied") {
  const validation: ValidationReport =
    interpreter.validateFull(
      computed.snapshot,
      validationOptions,
    );
}
```

`interpreter` has type `Interpreter` and supplies both the model zone and the configured clock provider. `snapshot` has type `DocumentSnapshot` and is the frozen input document. `evaluationTime` has type `A12EvaluationTime` and identifies one immutable instant; `computeOptions` and `validationOptions` carry that same sample to two calls. `computed` has type `ComputeAndApplyResult`; only its `"applied"` case contains the updated snapshot. `validation` has type `ValidationReport`, the detached validation result for that updated snapshot.

Passing the same immutable `A12EvaluationTime` lets several calls form one deterministic application transaction. A fixed clock remains available for tests. A live clock provider and per-call sample are new API/refactoring work; the current `DmInterpreter` captures a fixed `EvalClock`.

Locale and evaluation time are per-call data. They are never written into a prepared model, warm lane, process-global setting, model alias, or registry key. A request that omits time samples its lane’s configured clock once at transaction entry; a request that supplies time bypasses the clock read. A request that omits locale uses the documented API default without mutating any global locale.

Strict preparation rejects a model time zone that the current multiplatform runtime cannot implement with exact kernel semantics. It must not fall back to UTC, the host zone, or a partly compatible named-zone implementation. Closing a named-zone parity gap expands the accepted `A12TimeZone` set without changing this evaluation contract.

## 4. Model input, diagnostics, and introspection

### 4.1 Prepared input

The standalone boundary initially accepts only expanded, self-contained, name-path-keyed DM-JSON. It rejects unresolved includes, unresolved type definitions, opaque identifiers that require an external resolver, and other preparation residue.

`ModelLoader.load` is not yet that strict boundary: an unresolved type definition can currently degrade to a string kind. `PreparedModel.loadExpandedJson` must add a real residual/type/include guard and throw `ModelPreparationError` with structured diagnostics rather than accepting a semantically different model. `tryLoadExpandedJson` exposes the same failure without exceptions.

The v1 `DateFieldPrecision` union can represent only `"full"` and `"day-optional"`. A model declaring month-optional or year-optional `DateType` precision is therefore rejected during strict preparation with a stable `ModelPreparationDiagnostic.code` such as `"UNSUPPORTED_DATE_PRECISION"`. It is not coerced to day-optional, collapsed to a date fragment, or accepted as an unsupported runtime cell. Once those precisions are implemented and differentially verified, the discriminated metadata and inspection unions can grow without weakening the strict boundary.

### 4.2 Model identity

`PreparedModel.fingerprint` is computed by the library, never trusted from a caller. When the API accepts a JavaScript string, the conservative initial identity is a digest of its exact UTF-8 encoding plus the prepared-input format and interpreter preparation ABI version; a byte-oriented overload may accept `Uint8Array`.

Semantically equivalent JSON may therefore occupy two cache entries; that is a harmless miss. Returning one prepared model for different source content is not harmless. Canonical semantic hashing should not be claimed until a canonicalization contract exists.

Every model-carried semantic input is already in the expanded bytes: ordered rules/computations, types and constraints, time zone, optional declared base year, model charset, and short-reference policy. The effective date-completion year is derived deterministically from those bytes by applying the `2000` fallback. Runtime configuration is excluded.

### 4.3 Read-only introspection

Dynamic forms, diagnostics, and document mappers need stable model metadata:

```ts
declare const prepared: PreparedModel;

const model: ModelMetadata = prepared.model;
const field: FieldMetadata =
  model.requireField("/Order/Total");

const fieldKind: FieldKind = field.kind;
const fieldFormat: FieldFormat | undefined = field.format;
const repeatability: Repeatability = field.repeatability;
const computed: boolean = field.computed;
const commonConstraints: CommonFieldConstraints =
  field.commonConstraints;
const declaration: FieldDeclaration =
  field.declaration;
const effectiveNumberScale: number | undefined =
  declaration.kind === "number"
    ? declaration.scale
    : undefined;

const fields: readonly FieldMetadata[] = model.fields();
const groups: readonly GroupMetadata[] = model.groups();
const rootFields: readonly FieldMetadata[] =
  model.rootFields();
const rootGroups: readonly GroupMetadata[] =
  model.rootGroups();
const orderGroup: GroupMetadata =
  model.requireGroup("/Order");
const orderFields: readonly FieldMetadata[] =
  orderGroup.fields();
const orderChildGroups: readonly GroupMetadata[] =
  orderGroup.childGroups();
const orderIndexField: FieldMetadata | undefined =
  orderGroup.indexField;
const declaredRequiredness: Requiredness =
  field.commonConstraints.declaredRequiredness;
const effectiveRequiredness: Requiredness =
  field.commonConstraints.effectiveRequiredness;
const declaredBaseYear: A12BaseYear | undefined =
  model.declaredBaseYear;
const effectiveDateCompletionBaseYear: A12BaseYear =
  model.effectiveDateCompletionBaseYear;
const rules: readonly RuleMetadata[] = model.rules();
const computations: readonly ComputationMetadata[] =
  model.computations();
```

`prepared` has type `PreparedModel`; `model` is its `ModelMetadata` view. `field` is one `FieldMetadata`; `fieldKind`, `fieldFormat`, `repeatability`, `computed`, `commonConstraints`, and `declaration` are typed projections of that declaration. `effectiveNumberScale` is a native `number` only for a number declaration and is `undefined` for every other field kind; the required `FieldDeclaration.scale` is the single effective number scale and defaults to zero when source JSON omits it. `fields` and `groups` are flat model-order views, while `rootFields` and `rootGroups` are the structural entry points. `orderGroup` is a `GroupMetadata`; `orderFields` and `orderChildGroups` contain only its direct children, and `orderIndexField` is either the configured `FieldMetadata` key or `undefined`. `declaredRequiredness` preserves authored configuration; `effectiveRequiredness` includes index-field auto-requiredness. `declaredBaseYear` is optional model configuration, whereas `effectiveDateCompletionBaseYear` is the always-present completion year for yearless fragments and partial ranges. `rules` and `computations` are immutable metadata arrays.

`ModelMetadata` is the read-only public view over one prepared definition. `FieldMetadata`, `GroupMetadata`, `RuleMetadata`, and `ComputationMetadata` are immutable descriptions, not evaluable handles. `FieldKind` is the closed semantic value kind, `FieldFormat` describes the model’s lexical format, `Repeatability` describes whether and where repetitions occur, `CommonFieldConstraints` carries authored/effective cross-kind field rules, and `FieldDeclaration` preserves the declared field type plus its kind-specific constraints. `requireField`/`requireGroup` throw `UnknownModelPathError`; nullable `findField`/`findGroup` variants support probing.

The first contract should expose evaluation-relevant facts: traversable structure, path, kind, format/effective scale, constraints, repetition, index-field relationships, labels/messages where available, computation status, model time zone, declared base year, and effective date-completion base year. It should not publish `ModelDef` or internal maps as ABI.

Preparing model JSON generated at runtime is supported. Authoring, editing, include resolution, and kernel-equivalent model consistency checking are not interpreter jobs; a future multiplatform modelkit may produce the expanded input without becoming part of this evaluator façade.

### 4.4 Caller-owned application metadata

Application concerns such as form widgets, help text, analytics tags, application routes, and presentation hints need a separate registry. They do not belong in `PreparedModel` and must never override A12 constraints, labels, rule semantics, time zones, or customization.

```ts
type ModelApplicationMetadataKey =
  | FieldMetadata
  | GroupMetadata
  | RuleMetadata
  | ComputationMetadata;

interface ApplicationMetadataRegistry<Metadata> {
  set(
    key: ModelApplicationMetadataKey,
    metadata: Metadata,
  ): void;
  get(
    key: ModelApplicationMetadataKey,
  ): Metadata | undefined;
  delete(key: ModelApplicationMetadataKey): void;
  clearOwner(owner: ModelOwnerIdentity): void;
}

interface OrderFieldPresentation {
  readonly widget: "text" | "decimal" | "date";
  readonly helpText?: string;
}

declare const applicationMetadata:
  ApplicationMetadataRegistry<OrderFieldPresentation>;
declare const prepared: PreparedModel;

const totalField: FieldMetadata =
  prepared.model.requireField("/Order/Total");
const totalPresentation: OrderFieldPresentation = {
  widget: "decimal",
  helpText: "Calculated order total.",
};

applicationMetadata.set(
  totalField,
  totalPresentation,
);
```

`applicationMetadata` has type `ApplicationMetadataRegistry<OrderFieldPresentation>` and is created, synchronized, and retired by the application. `prepared` has type `PreparedModel`; `totalField` is its model-owned `FieldMetadata`, and `totalPresentation` has type `OrderFieldPresentation`. Registry implementations key by the runtime-authenticated owner-aware handle, not by a path or mutable model alias. The same path in a newer model version is a different key. There is no process-global application metadata registry, and model eviction does not delete caller metadata unless the caller explicitly couples those lifecycles.

## 5. Documents

### 5.1 Canonical document boundary

The interpreter, not the direct operator package, owns document structures. Its transport must preserve:

- absent versus present-empty fields;
- malformed raw lexical values that validation must report;
- instantiated empty groups;
- repetition coordinates and empty rows;
- sparse repetitions;
- stable field and group pointers;
- model ownership and version.

A convenient nested JSON object cannot be the canonical contract until those states have exact representations. The safest initial transport is a versioned placement envelope containing explicit group rows and raw field cells. A nested-object adapter may be added later.

```ts
interface GroupPlacementInput {
  readonly path: string;
  readonly coordinates: readonly number[];
}

interface FieldPlacementInput {
  readonly path: string;
  readonly coordinates: readonly number[];
  readonly raw: string;
}

interface PlacementDocumentInputV1 {
  readonly version: 1;
  readonly groups: readonly GroupPlacementInput[];
  readonly fields: readonly FieldPlacementInput[];
}

interface DocumentDecodeLimits {
  readonly maxGroupPlacements: number;
  readonly maxFieldPlacements: number;
  readonly maxCoordinatesPerPlacement: number;
  readonly maxPathCodeUnits: number;
  readonly maxRawCodeUnitsPerField: number;
  readonly maxTotalRawCodeUnits: number;
  readonly maxModelNestingDepth: number;
}

interface DocumentDecodeOptions {
  readonly limits?: DocumentDecodeLimits;
}
```

`PlacementDocumentInputV1` is the portable, behavior-free input DTO. `GroupPlacementInput` creates one concrete group row. `FieldPlacementInput` stores one raw field cell. `path` is an absolute model path validated during decoding; `coordinates` contains one positive, 1-based repetition index per path part, including the field part; and `raw` preserves the exact lexical text. Omitting a field means absent, `raw: ""` means present-empty, and malformed text remains present for formal validation. Explicit group placements preserve empty rows and sparse repetitions.

```ts
declare const prepared: PreparedModel;
declare const requestBody: unknown;

const decodeLimits: DocumentDecodeLimits = {
  maxGroupPlacements: 10_000,
  maxFieldPlacements: 100_000,
  maxCoordinatesPerPlacement: 16,
  maxPathCodeUnits: 1_024,
  maxRawCodeUnitsPerField: 1_000_000,
  maxTotalRawCodeUnits: 16_000_000,
  maxModelNestingDepth: 32,
};
const decodeOptions: DocumentDecodeOptions = {
  limits: decodeLimits,
};

const snapshot: DocumentSnapshot =
  prepared.documents.decodePlacements(
    requestBody,
    decodeOptions,
  );
const canonicalDocument:
  PlacementDocumentInputV1 =
  prepared.documents.encodePlacements(snapshot);
```

`prepared` has type `PreparedModel`, the owner of the model-aware document codec. `requestBody` has type `unknown`, as an HTTP, queue, plugin, persistence, or Worker boundary actually does. `decodeLimits` has type `DocumentDecodeLimits`, `decodeOptions` has type `DocumentDecodeOptions`, `snapshot` has type `DocumentSnapshot`, and `canonicalDocument` has type `PlacementDocumentInputV1`.

`decodePlacements` is the authoritative public ingress. It validates the runtime shape before model-aware path/coordinate checks, rejects unknown object keys for the canonical v1 transport, enforces the supplied resource limits, and aggregates failures in `DocumentInputError`. `tryDecodePlacements` performs the same work and returns `A12Result<DocumentSnapshot, DocumentInputError>`. `readPlacements(input: PlacementDocumentInputV1, options?: DocumentDecodeOptions)` remains a typed fast boundary for data already validated by a trusted decoder; a TypeScript annotation alone is not evidence that data was validated.

`encodePlacements` writes a round-trippable `PlacementDocumentInputV1` after an owner check. A malformed number or date remains valid document input; it is not a valid direct `A12Number` or `A12Date`. Every diagnostic always carries its transport `inputPath`, such as `["fields", 2, "coordinates", 1]`, and separately carries the attempted `modelPath`, such as `"/Order/Lines/Amount"`, when known.

Raw request-byte limits and JSON-parser depth limits must run before or during JSON parsing, because an already-created JavaScript object no longer has an authoritative byte size. The A12 decoder then enforces placement, coordinate, path, raw-content, and model-depth limits before evaluator hot paths run. It rejects rather than truncates. Cooperative deadline checks inside every AST traversal are a separate performance-sensitive capability and are not silently introduced by this façade.

### 5.2 Build a frozen request document

```ts
declare const prepared: PreparedModel;

const discount: A12Number = A12Number.parse("12.50");
const year: A12Integer = A12Integer.fromSafeInteger(2026);
const month: A12Integer = A12Integer.fromSafeInteger(7);
const billingMonth: OptionalDateCellValue =
  prepared.documents.dayOptionalDate(year, month);

const orderGroup: GroupRef =
  prepared.model.requireGroup("/Order");
const orderRow: GroupRowRef = orderGroup.row(1);
const orderPointer: GroupPointer = orderRow.pointer;
const discountField: FieldInstanceRef =
  orderRow.requireField("DiscountPercent");
const discountPointer: FieldPointer =
  discountField.pointer;
const billingMonthField: FieldInstanceRef =
  orderRow.requireField("BillingMonth");
const billingMonthPointer: FieldPointer =
  billingMonthField.pointer;
const serviceMonthField: FieldInstanceRef =
  orderRow.requireField("ServiceMonth");
const serviceMonthPointer: FieldPointer =
  serviceMonthField.pointer;
const serviceMonthComponents: DateFragmentComponents = {
  granularity: "month",
  month,
};
const serviceMonth: DateFragmentCellValue =
  prepared.documents.dateFragment(
    serviceMonthField.metadata,
    serviceMonthComponents,
  );

const builder: DocumentBuilder = prepared.documents.create();
builder.addGroup(orderPointer);
builder.putValue(discountPointer, discount);
builder.putValue(billingMonthPointer, billingMonth);
builder.putValue(serviceMonthPointer, serviceMonth);

const snapshot: DocumentSnapshot = builder.freeze();
```

`discount` has type `A12Number`, one member of the `A12Value` union. `year` and `month` are exact `A12Integer` components, and `billingMonth` is the model-owned `OptionalDateCellValue` constructed from them without inventing a day or a raw format. `orderGroup` is the reusable `GroupRef`; `orderRow` is its coordinate-validated `GroupRowRef`; `orderPointer` is the group-only pointer accepted by `addGroup`. `discountField`, `billingMonthField`, and `serviceMonthField` are `FieldInstanceRef` handles in that row, while `discountPointer`, `billingMonthPointer`, and `serviceMonthPointer` are their field-only pointers. `serviceMonthComponents` has type `DateFragmentComponents` and explicitly selects a month-only fragment; `serviceMonth` has type `DateFragmentCellValue`, a model-owned fragment constructed against `serviceMonthField.metadata`. `builder` has type `DocumentBuilder`; `putValue` accepts the complete `DocumentCellValue` union. `snapshot` has type `DocumentSnapshot`, the immutable document produced by `freeze()`.

`DocumentBuilder` is a mutable, single-owner construction helper bound to one prepared model. Its mutation methods accept validated pointers instead of duplicating raw path/coordinate overloads. `GroupRef.row(...coordinates: number[]): GroupRowRef` and the dynamic `ModelMetadata.pointer` factory reject a wrong coordinate count or a coordinate that is not a positive safe integer with `DocumentCoordinateError`. A coordinate above a declared repetition maximum is accepted deliberately so imported over-repetition can be represented and reported by formal validation. Repeating `addGroup` for the same `GroupPointer` is idempotent.

`putValue` is the single field-aware typed insertion contract. `DocumentCellValue` contains the ordinary `A12String`, `A12Boolean`, `A12Confirm`, `A12Number`/`A12Integer`, `A12Date`, `A12Time`, `A12DateTime`, and `A12DateRange` values plus `OptionalDateCellValue` and `DateFragmentCellValue`. A complete date written to a day-optional date field remains an ordinary `A12Date`; `dayOptionalDate` creates only the absent-day case. Model-owned partial values must belong to the same prepared owner, and a date-fragment value’s shape must match the target declaration.

Each field declaration has a representation codec used only to store the supplied semantic value. It is independent of formal validation. The codec never rounds, truncates, normalizes line breaks in storage, or drops date components implicitly. It succeeds only when the value kind matches the field and render-then-storage-read represents the same semantic value; a date-fragment read compares both its explicit components and its `resolvedValue`, and an `A12DateTime` compares the same exact instant after the required projection into the model zone. Evaluation-only transformations such as CRLF-to-LF are outside this round-trip check. For a number, the codec strips insignificant trailing fractional zeros to obtain the natural scale, rejects a natural scale greater than the field’s effective `scale`, pads fractional zeros up to `minFractionalDigits` when necessary, and emits plain decimal text without an exponent. Strict preparation rejects a number declaration whose `minFractionalDigits` exceeds `scale`. For example, `12.345` is not exactly representable in a scale-2 field. Callers explicitly invoke a rounding or projection operator before retrying.

Built-in string, enumeration, and custom declarations store an `A12String` exactly. The canonical snapshot stores custom-field internal text. During formal validation, its configured `CustomFieldValidator` receives that native string, `isDisplayValue: false`, the requested locale, and the declaration’s optional `CustomFieldConstraints` length hints. Display-form input must first use the bound conversion API; a kernel compatibility adapter derives the equivalent behavior from its native document/presentation contract rather than changing canonical storage.

```ts
declare const interpreter: Interpreter;
declare const taxIdentifier: CustomFieldPointer;
declare const displayValue: string;

const conversion: CustomFieldConversionResult =
  interpreter.convertCustomFieldDisplayToInternal(
    taxIdentifier,
    displayValue,
  );
```

`interpreter` has type `Interpreter`, the active evaluator holding the custom-field binding. `taxIdentifier` has type `CustomFieldPointer`, a model-owned pointer whose declaration names the validator. `displayValue` is native UI text. `conversion` has type `CustomFieldConversionResult`; only its `"converted"` case supplies internal text suitable for `A12String.of` and a typed document write, while its `"rejected"` case retains the supplied text and message for the UI. `convertCustomFieldInternalToDisplay` provides the inverse operation over canonical internal text. Neither conversion performs whole-document validation.

Field constraints that do not affect exact storage representation—minimum/maximum, requiredness, allowed enumeration values, string pattern, custom validation, and similar rules—are not silently enforced by the builder. The exactly rendered value is stored and normal validation reports those violations. A kind mismatch throws `DocumentValueWriteError` with `reasonCode: "field-kind-mismatch"`; an inexact representation uses `"value-not-exactly-representable"`. Every failed `addGroup`, `removeGroup`, `putRaw`, `putValue`, or `removeField` call is atomic and leaves the builder/editor unchanged. `putRaw` intentionally preserves arbitrary user input, including malformed lexical text, for formal validation. Raw `PlacementDocumentInputV1` decoding remains the bulk-input path and aggregates structural problems in `DocumentInputError`.

`removeField(pointer: FieldPointer): void` removes that exact field placement and restores the `"absent"` state; it is idempotent and does not remove an explicitly stored containing group row. `removeGroup(pointer: GroupPointer): void` removes that exact group placement and every descendant group/field placement in its subtree while leaving siblings and ancestors unchanged; it is also idempotent. `putRaw(pointer, "")` remains a present-empty field and is therefore intentionally different from `removeField(pointer)`. Both construction helpers expose the same operations so an application can undo an in-progress build or persistently edit an existing snapshot without reconstructing placement DTOs.

Builders are not evaluable. `freeze()` produces a stable request document whose stored placements no longer mutate. The initial service contract keeps it and its evaluation view confined to one request/runtime lease; it does not need to be concurrently shareable.

A persistent form editor is an optional additional surface:

```ts
declare const prepared:
  PreparedModelWithDocumentEditing;
declare const currentSnapshot: DocumentSnapshot;
declare const fieldPointer: FieldPointer;
declare const inputText: string;

const editor: PersistentDocumentEditor =
  prepared.documents.edit(currentSnapshot);
editor.putRaw(fieldPointer, inputText);

const nextSnapshot: DocumentSnapshot = editor.freeze();
```

`prepared` has type `PreparedModelWithDocumentEditing`, `currentSnapshot` and `nextSnapshot` have type `DocumentSnapshot`, `fieldPointer` is a model-validated `FieldPointer`, `inputText` is native `string`, and `editor` has type `PersistentDocumentEditor`. `PreparedDocumentEditingApi` adds `edit` without adding that method to the core `PreparedDocumentApi`. `PersistentDocumentEditor` is a single-owner editor that starts from one frozen snapshot and produces another; its `putValue`, `removeField`, `addGroup`, and `removeGroup` methods have the same contracts as the corresponding `DocumentBuilder` methods. It should use structural sharing so one keystroke does not copy an entire large document. It cannot become a supported form API until its cache invalidation contract and update-to-validation allocation/latency are proven. The validation-service core does not wait for it.

### 5.3 Pointers and typed inspection

`DocumentPointer` is a tagged model-validated value containing path plus canonical repetition coordinates. Wildcard `ALL` is a tagged coordinate, not an internal sentinel integer.

Ordinary client code should use model refs and row handles that derive non-repeatable coordinates:

```ts
declare const prepared: PreparedModel;

const orderGroup: GroupRef =
  prepared.model.requireGroup("/Order");
const orderRow: GroupRowRef = orderGroup.row(1);
const startedAtField: FieldInstanceRef =
  orderRow.requireField("StartedAt");
const startedAtPointer: FieldPointer =
  startedAtField.pointer;
```

`prepared` has type `PreparedModel`, the owner that issues every returned handle. `orderGroup` has type `GroupRef`, a model-validated reusable group handle. `orderRow` has type `GroupRowRef` and binds that group to concrete repetition coordinates. `startedAtField` has type `FieldInstanceRef` and identifies one field instance within the row. `startedAtPointer` has type `FieldPointer`, the field-specialized `DocumentPointer`; `GroupPointer` is the corresponding group specialization. Every pointer carries an explicit `entityKind`, immutable path, coordinates, model-version identity, and owner identity. `GroupRef.row` checks arity and positive-safe-integer coordinates and throws `DocumentCoordinateError` on failure; it does not require that the row already exists. The lossless transport retains complete canonical coordinates, so developers do not hand-author internal coordinate arrays for routine access.

Dynamic forms and mappers also need to enumerate rows that are actually instantiated in a snapshot:

```ts
declare const prepared: PreparedModel;
declare const snapshot: DocumentSnapshot;

const orderGroup: GroupRef =
  prepared.model.requireGroup("/Order");
const lineGroup: GroupRef =
  prepared.model.requireGroup("/Order/Lines");
const taxGroup: GroupRef =
  prepared.model.requireGroup("/Order/Lines/Taxes");
const firstOrderRow: GroupRowRef = orderGroup.row(1);

const allLineRows: readonly GroupRowRef[] =
  snapshot.instantiatedRows(lineGroup);
const firstOrderLineRows: readonly GroupRowRef[] =
  snapshot.instantiatedRows(lineGroup, firstOrderRow);
const taxRowsByLine: readonly (readonly GroupRowRef[])[] =
  firstOrderLineRows.map(
    (lineRow: GroupRowRef): readonly GroupRowRef[] =>
      snapshot.instantiatedRows(taxGroup, lineRow),
  );
```

`prepared` is the `PreparedModel` that owns every handle, and `snapshot` is one frozen `DocumentSnapshot` for that model. `orderGroup`, `lineGroup`, and `taxGroup` are reusable `GroupRef` definitions at three nesting levels. `firstOrderRow` is a coordinate-validated `GroupRowRef`, whether or not that row is present. `allLineRows` is an immutable array of every instantiated line row across all parent contexts. `firstOrderLineRows` narrows the result to descendants of the supplied order row. `taxRowsByLine` is an immutable outer array whose element type is another immutable `GroupRowRef` array; its callback parameter `lineRow` and callback result are both explicitly typed.

`DocumentSnapshot.instantiatedRows(group: GroupRef, within?: GroupRowRef): readonly GroupRowRef[]` includes explicit empty rows and rows implied by field placements, preserves sparse positive indexes, supports nested repeated groups, and returns canonical ascending coordinate order with complete coordinates in every row’s `GroupPointer`. Omitting `within` searches all parent contexts. Supplying it restricts the result to descendants of that ancestor row; a handle from another prepared owner raises `ModelOwnerMismatchError`, and a same-model row that is not an ancestor of `group` raises `DocumentRowScopeError`. Enumeration reads the snapshot’s placement structure directly and does not serialize or rebuild a `PlacementDocumentInputV1`.

Stored state and configured validity are different:

```ts
interface CellInspectionIdentity<
  P extends FieldPointer = FieldPointer,
> extends ModelOwned {
  readonly pointer: P;
}

interface AbsentCellStorage {
  readonly kind: "absent";
}

interface EmptyCellStorage {
  readonly kind: "empty";
  readonly raw: "";
}

declare const nonEmptyRawStringBrand: unique symbol;

type NonEmptyRawString = string & {
  readonly [nonEmptyRawStringBrand]: never;
};

interface PresentCellStorage {
  readonly kind: "present";
  readonly raw: NonEmptyRawString;
}

type CellStorageInspection =
  | AbsentCellStorage
  | EmptyCellStorage
  | PresentCellStorage;

type OptionalDatePrecision = "day-optional";

interface OptionalDateCellValue extends ModelOwned {
  readonly kind: "optional-date";
  readonly precision: OptionalDatePrecision;
  readonly year: A12Integer;
  readonly month: A12Integer;
}

type DateFragmentShape =
  | {
      readonly format: "yyyy-MM";
      readonly granularity: "year-month";
    }
  | {
      readonly format: "yyyy";
      readonly granularity: "year";
    }
  | {
      readonly format: "MM";
      readonly granularity: "month";
    }
  | {
      readonly format: "MM-dd";
      readonly granularity: "month-day";
    };

type DateFragmentCellValue = ModelOwned & (
  | {
      readonly kind: "date-fragment";
      readonly format: "yyyy-MM";
      readonly granularity: "year-month";
      readonly year: A12Integer;
      readonly month: A12Integer;
      readonly resolvedValue: A12Date;
    }
  | {
      readonly kind: "date-fragment";
      readonly format: "yyyy";
      readonly granularity: "year";
      readonly year: A12Integer;
      readonly resolvedValue: A12Date;
    }
  | {
      readonly kind: "date-fragment";
      readonly format: "MM";
      readonly granularity: "month";
      readonly month: A12Integer;
      readonly resolvedValue: A12Date;
    }
  | {
      readonly kind: "date-fragment";
      readonly format: "MM-dd";
      readonly granularity: "month-day";
      readonly month: A12Integer;
      readonly day: A12Integer;
      readonly resolvedValue: A12Date;
    }
);

interface NoCellStorageValue {
  readonly kind: "none";
}

interface A12StringCellStorageValue {
  readonly kind: "value";
  readonly value: A12String;
}

type NonStringA12Value = Exclude<A12Value, A12String>;

interface NonStringA12CellStorageValue {
  readonly kind: "value";
  readonly value: NonStringA12Value;
}

type A12CellStorageValue =
  | A12StringCellStorageValue
  | NonStringA12CellStorageValue;

interface OptionalDateCellStorageValue {
  readonly kind: "optional-date";
  readonly value: OptionalDateCellValue;
}

interface DateFragmentCellStorageValue {
  readonly kind: "date-fragment";
  readonly value: DateFragmentCellValue;
}

interface UnavailableCellStorageValue {
  readonly kind: "unavailable";
}

type CellStorageValue =
  | NoCellStorageValue
  | A12CellStorageValue
  | OptionalDateCellStorageValue
  | DateFragmentCellStorageValue
  | UnavailableCellStorageValue;

interface StoredCellInspection extends ModelOwned {
  readonly pointer: FieldPointer;
  readonly storage: CellStorageInspection;
  readonly storageValue: CellStorageValue;
}

type CellEvaluationNonValueReason =
  | "no-stored-value"
  | "formally-invalid"
  | "raw-type"
  | "incomplete-date"
  | "unsupported";

interface InternedCellEvaluationValue<
  T extends A12Value = A12Value,
> {
  readonly kind: "value";
  readonly value: T;
}

interface NonInternedCellEvaluationValue<
  R extends CellEvaluationNonValueReason =
    CellEvaluationNonValueReason,
> {
  readonly kind: "not-interned";
  readonly reason: R;
}

type CellEvaluationValue =
  | InternedCellEvaluationValue
  | NonInternedCellEvaluationValue;

type InspectionDiagnostic =
  | {
      readonly code: "required-value-missing";
      readonly requiredness: Requiredness;
    }
  | {
      readonly code: "invalid-lexical-format";
      readonly expectedFormat: FieldFormat;
      readonly invalidCodeUnitIndex?: number;
    }
  | {
      readonly code: "value-out-of-range";
      readonly minimum?: A12Number;
      readonly maximum?: A12Number;
    }
  | {
      readonly code: "length-out-of-range";
      readonly minimum?: number;
      readonly maximum?: number;
      readonly actual: number;
    }
  | {
      readonly code: "illegal-character";
      readonly codePoint: number;
      readonly codeUnitIndex: number;
    }
  | {
      readonly code: "duplicate-index-value";
      readonly conflictingPointers:
        readonly FieldPointer[];
    }
  | {
      readonly code: "repetition-limit-exceeded";
      readonly maximum: number;
      readonly actual: number;
    }
  | {
      readonly code: "custom-field-rejected";
      readonly typeName: string;
      readonly errorKey: string;
      readonly message: string;
    };

interface ValidCellFormalState {
  readonly kind: "valid";
}

interface InvalidCellFormalState {
  readonly kind: "invalid";
  readonly diagnostics: readonly [
    InspectionDiagnostic,
    ...InspectionDiagnostic[],
  ];
  readonly effectiveSuppression: InspectionDiagnostic;
}

interface UnsupportedFieldInspection {
  readonly kind: "custom-field-type";
  readonly typeName: string;
  readonly reasonCode:
    "missing-custom-field-type";
  readonly detail: string;
}

interface UnsupportedCellFormalState {
  readonly kind: "unsupported";
  readonly reason: UnsupportedFieldInspection;
}

interface InvalidUnsupportedCellFormalState {
  readonly kind: "invalid-and-unsupported";
  readonly diagnostics: readonly [
    InspectionDiagnostic,
    ...InspectionDiagnostic[],
  ];
  readonly effectiveSuppression: InspectionDiagnostic;
  readonly reason: UnsupportedFieldInspection;
}

type CellFormalFailure =
  | InvalidCellFormalState
  | UnsupportedCellFormalState
  | InvalidUnsupportedCellFormalState;

type CellFormalValidity =
  | ValidCellFormalState
  | CellFormalFailure;

type CellInspectionCase<
  S extends CellStorageInspection,
  V extends CellStorageValue,
  F extends CellFormalValidity,
  E extends CellEvaluationValue,
  P extends FieldPointer = FieldPointer,
> = CellInspectionIdentity<P> & {
  readonly storage: S;
  readonly storageValue: V;
  readonly formalValidity: F;
  readonly evaluationValue: E;
};

type EmptyStorageCellInspection =
  | CellInspectionCase<
      AbsentCellStorage | EmptyCellStorage,
      NoCellStorageValue,
      ValidCellFormalState,
      NonInternedCellEvaluationValue<"no-stored-value">
    >
  | CellInspectionCase<
      AbsentCellStorage | EmptyCellStorage,
      NoCellStorageValue,
      InvalidCellFormalState,
      NonInternedCellEvaluationValue<"formally-invalid">
    >;

type ValidNonStringCellInspection<
  T extends NonStringA12Value = NonStringA12Value,
> = T extends NonStringA12Value
  ? CellInspectionCase<
      PresentCellStorage,
      {readonly kind: "value"; readonly value: T},
      ValidCellFormalState,
      InternedCellEvaluationValue<T>
    >
  : never;

type ValidStringCellInspection = CellInspectionCase<
  PresentCellStorage,
  A12StringCellStorageValue,
  ValidCellFormalState,
  | InternedCellEvaluationValue<A12String>
  | NonInternedCellEvaluationValue<"raw-type">
>;

type InvalidDecodedCellInspection = CellInspectionCase<
  PresentCellStorage,
  | A12CellStorageValue
  | OptionalDateCellStorageValue
  | DateFragmentCellStorageValue,
  InvalidCellFormalState,
  NonInternedCellEvaluationValue<"formally-invalid">
>;

type UnsupportedCustomFieldCellInspection = CellInspectionCase<
  PresentCellStorage,
  A12StringCellStorageValue,
  UnsupportedCellFormalState,
  NonInternedCellEvaluationValue<"unsupported">,
  CustomFieldPointer
>;

type InvalidUnsupportedCustomFieldCellInspection =
  CellInspectionCase<
    PresentCellStorage,
    A12StringCellStorageValue,
    InvalidUnsupportedCellFormalState,
    NonInternedCellEvaluationValue<"formally-invalid">,
    CustomFieldPointer
  >;

type ValidOptionalDateCellInspection = CellInspectionCase<
  PresentCellStorage,
  OptionalDateCellStorageValue,
  ValidCellFormalState,
  NonInternedCellEvaluationValue<"incomplete-date">
>;

type ValidDateFragmentCellInspection = CellInspectionCase<
  PresentCellStorage,
  DateFragmentCellStorageValue,
  ValidCellFormalState,
  InternedCellEvaluationValue<A12Date>
>;

type UnavailableCellInspection = CellInspectionCase<
  PresentCellStorage,
  UnavailableCellStorageValue,
  InvalidCellFormalState,
  NonInternedCellEvaluationValue<"formally-invalid">
>;

type CellInspection =
  | EmptyStorageCellInspection
  | ValidNonStringCellInspection
  | ValidStringCellInspection
  | ValidOptionalDateCellInspection
  | ValidDateFragmentCellInspection
  | InvalidDecodedCellInspection
  | UnsupportedCustomFieldCellInspection
  | InvalidUnsupportedCustomFieldCellInspection
  | UnavailableCellInspection;

type OptionalDateBoundary = "first" | "last";

interface FormalSuppression {
  readonly pointer: FieldPointer;
  readonly formalErrorCode: string;
}
```

`DocumentSnapshot.storage` is the direct storage boundary. `DocumentStorage.cell(pointer)` and `cells(pointers)` return `StoredCellInspection` without running requiredness, format, range, pattern, uniqueness, legal-character, custom-field, rule, or computation logic. They do not invoke callbacks and do not sample the clock. `cells` preserves input order and duplicates, and both methods enforce owner identity before reading. The result preserves the raw absent/empty/present state and, when model-aware lexical decoding is possible without formal acceptance, the corresponding `CellStorageValue`; malformed text remains present with `storageValue.kind === "unavailable"`. This API is suitable for form rendering, debugging, persistence mapping, and on-keyup access where a full formal pass would be both semantically surprising and unnecessarily expensive.

`CellInspection` reports four distinct dimensions—raw storage, storage-decoded value, evaluator-visible value, and configured formal/support state—and its union correlates them so impossible combinations do not type-check. `CellInspectionIdentity` supplies the `FieldPointer`, model-version identity, and owner identity shared by every case. `CellStorageInspection` distinguishes absent, present-empty, and present non-empty raw text. `NonEmptyRawString` is a library-issued output-only branded subtype of native `string`; callers never manufacture it, and it prevents `PresentCellStorage` from overlapping the dedicated `EmptyCellStorage` case. `CellStorageValue` is `"none"` for absent/empty storage, one ordinary `A12Value`, one model-owned optional-date or date-fragment value, or `"unavailable"` when present text cannot be storage-decoded. `NonStringA12Value` uses TypeScript’s `Exclude<A12Value, A12String>` utility to retain every ordinary value variant except `A12String`. `InternedCellEvaluationValue<T>` carries the concrete `A12Value` subtype `T` seen by rules and computations; `NonInternedCellEvaluationValue<R>` carries the exact non-value reason subtype `R`. `CellEvaluationValue` is their public union.

`ValidCellFormalState` means the automatic formal pass found no suppressing diagnostic and the configured field implementation is available. `InvalidCellFormalState` carries one or more formal diagnostics. `UnsupportedCellFormalState` is restricted to a present custom-field string whose configured type implementation is unavailable. `InvalidUnsupportedCellFormalState` preserves the reachable combination in which that same cell is both formally invalid and unsupported—for example, its raw text violates the legal charset while its custom field type is unregistered. `UnsupportedFieldInspection` identifies that custom type and its stable reason without reusing the program-level `UnsupportedProgram` type. `CellFormalFailure` and `CellFormalValidity` are the corresponding failure and complete-state unions.

`CellInspectionCase<S, V, F, E, P>` is an internal declaration helper that binds the storage type `S`, storage-value type `V`, formal/support-state type `F`, evaluation-value type `E`, and pointer type `P`; `P` defaults to `FieldPointer`. The named case aliases make the exported `CellInspection` union readable. `EmptyStorageCellInspection` can be valid or invalid but cannot be unsupported. `UnsupportedCustomFieldCellInspection` and `InvalidUnsupportedCustomFieldCellInspection` require `CustomFieldPointer`, so an ordinary string/enumeration pointer cannot type-check in either unsupported case; their `reason.typeName` must equal `pointer.customTypeName`. `UnavailableCellInspection` is always formally invalid, because undecodable built-in text is a lexical formal error rather than missing custom semantics. An absent optional field is valid but not interned because it has no stored value. An absent required or required present-empty field is invalid and not interned because formal validity suppresses value reading. A present number may storage-decode to `A12Number` and still be invalid because it violates a minimum; it cannot simultaneously claim a concrete evaluator value.

`Interpreter.inspectDocument(snapshot: DocumentSnapshot, options?: InspectionOptions): DocumentInspection` is deliberately different: it runs the full-document automatic formal oracle exactly once with all model entities relevant. It covers field lexical format and declared constraints, effective mandatory checks, duplicate non-empty index-field values, over-repetition, legal-character checks, and configured custom-field validation. `InspectionOptions.locale` supplies the exact locale passed to custom-field validators and defaults to `"en_US"`; inspection cannot omit that observable callback input. It excludes authored business-rule findings, which belong to `ValidationReport`. Each configured custom-field validator is invoked at most once for each concrete field occurrence visited by that pass. The returned `DocumentInspection` is detached and immutable: `cell(pointer)` performs one indexed lookup, while `cells(pointers)` preserves input order and duplicates without repeating any validation or callback. Both methods reject a pointer owned by another prepared owner with `ModelOwnerMismatchError`.

No implicit inspection cache crosses snapshots or interpreter instances. Editing and freezing a new `DocumentSnapshot` requires one new `inspectDocument` call; interleaving `D1, D2, D1, D2` cannot reuse request state from another snapshot. Implementations may retain a call-owned field index inside `DocumentInspection`, but it must have bounded lifetime and must not retain application callback resources. `InvalidCellFormalState.diagnostics` and `InvalidUnsupportedCellFormalState.diagnostics` contain every automatic finding attached to that field in deterministic evaluator precedence after the formal checks’ mutual-suppression rules. Their `effectiveSuppression` is the one member the evaluator uses to produce unknown/suppressed expression behavior. In an `"invalid-and-unsupported"` state, formal suppression has evaluation precedence, so `evaluationValue.reason` is `"formally-invalid"`; the corresponding `ValidationReport.unsupported` entry is an `UnsupportedCustomFieldProgram` with the cell’s `pointer` and the same `typeName`, `reasonCode`, and `detail` as its `UnsupportedFieldInspection`, so validation loses neither fact. Partial relevance can change effective mandatory and uniqueness outcomes, so `inspectDocument` does not pretend to describe `validatePart`; a future relevance-aware inspection method would need an explicit `readonly RelevantEntityPointer[]` input.

For string-shaped fields, `storageValue` preserves the stored text exactly, including `"\r\n"` and a lone `"\r"`. A formally valid ordinary string’s `evaluationValue` applies the kernel ingestion rule exactly once: each CRLF pair becomes one LF, while a lone CR remains unchanged. Pattern/length checks, rules, message value interpolation, and computations all see that normalized `A12String`; `storage.raw` and the storage-decoded `A12String` remain untouched. A `noValueValidation` string is different: it can be formally valid and has an exact storage-decoded `A12String`, but its `evaluationValue` is `{kind: "not-interned", reason: "raw-type"}` because legal value operations cannot read it. Presence checks still use its storage state.

The other non-interned reasons are exact: `"no-stored-value"` for an optional absent/empty cell, `"formally-invalid"` when formal state suppresses value reading, `"incomplete-date"` for a day-optional value that requires explicit boundary resolution, and `"unsupported"` when configured semantics are unavailable. Expression-specific empty substitution remains inside the interpreter; a non-interned cell is not converted into a misleading direct `A12Value`.

`OptionalDateCellValue` is model-owned because its creation, insertion, and completion belong to one prepared definition. It represents the currently supported incomplete `DateType`: a day-optional date with explicit `year: A12Integer` and `month: A12Integer` components and no fabricated day or field-specific lexical format. `PreparedDocumentApi.dayOptionalDate(year: A12Integer, month: A12Integer): OptionalDateCellValue` validates the supported calendar components and throws `A12ConversionError` for an invalid year/month; target-specific formatting occurs only in `putValue`. A complete value in the same field decodes as an ordinary `A12Date`; this special type appears only when the day is omitted. It is deliberately not an `A12Value`; `Interpreter.resolveOptionalDate(value: OptionalDateCellValue, boundary: OptionalDateBoundary): A12Date` resolves its actual year/month with an explicit first/last boundary and rejects a value from a different prepared definition. Month-optional and year-optional precisions are not exposed until the common loader/evaluator implements and differentially verifies them; strict preparation rejects models that declare either precision.

`DateFragmentShape` is the declaration-side union of the four supported format/granularity pairs. `DateFragmentComponents` is the matching caller-input union with exact `A12Integer` components. `DateFragmentCellValue` is the model-owned output union that preserves the selected `format`, `granularity`, and supplied components while also carrying `resolvedValue`, the concrete `A12Date` produced by earliest-completion semantics. `PreparedDocumentApi.dateFragment(field: FieldMetadata, components: DateFragmentComponents): DateFragmentCellValue` requires a same-owner `DateFragmentType` field and a component variant matching that field’s declared shape. It validates the integer component ranges and calendar combination, throws `A12ConversionError` for a wrong declaration/shape/component set, and throws `ModelOwnerMismatchError` for foreign metadata; `tryDateFragment` converts only the former expected conversion failure to `A12Result` and leaves ownership misuse as an exception.

The `"MM"` and `"MM-dd"` shapes obtain their missing year from `ModelMetadata.effectiveDateCompletionBaseYear`, which is the declared base year or `2000`; the `"yyyy"` and `"yyyy-MM"` shapes already carry their year. A fragment value is written only to a matching date-fragment field. Supplying an ordinary `A12Date` to that field is rejected rather than silently dropping components; a developer must explicitly construct the intended fragment. This completion default does not make the expression constant `BaseYear` legal when `declaredBaseYear` is absent.

A partial `DateRangeType` decodes directly to a complete `A12DateRange` in the ordinary `A12CellStorageValue` case. Missing years on either endpoint use `effectiveDateCompletionBaseYear`. Other missing start components complete to the earliest endpoint (month/day `1`), while missing end components complete to the latest endpoint (month `12`, day the last valid day of that month). For example, a `yyyy` range stores only endpoint years but reads as January 1 of the start year through December 31 of the end year. `InspectionDiagnostic` has a locale-independent discriminant and structured fields. A `"custom-field-rejected"` diagnostic additionally retains the callback’s exact `errorKey` and rendered `message`; inspection passes through that message and does not synthesize a replacement. `FormalSuppression` is the detached rule/expression result type identifying the `FieldPointer` and formal-error code that prevented evaluation. `UnsupportedProgram` remains the separate rule/computation-level result for a custom or unsupported semantic construct.

```ts
declare const interpreter: Interpreter;
declare const snapshot: DocumentSnapshot;
declare const startedAtPointer: FieldPointer;
const inspectionOptions: InspectionOptions = {
  locale: "en_US",
};

const storedCell: StoredCellInspection =
  snapshot.storage.cell(startedAtPointer);
const storedState: CellStorageInspection =
  storedCell.storage;
const decodedStoredValue: CellStorageValue =
  storedCell.storageValue;

const documentInspection: DocumentInspection =
  interpreter.inspectDocument(
    snapshot,
    inspectionOptions,
  );
const cell: CellInspection =
  documentInspection.cell(startedAtPointer);
const storage: CellStorageInspection = cell.storage;
const formalValidity: CellFormalValidity =
  cell.formalValidity;
const storageValue: CellStorageValue =
  cell.storageValue;
const evaluationValue: CellEvaluationValue =
  cell.evaluationValue;

switch (storageValue.kind) {
  case "value": {
    const value: A12Value = storageValue.value;
    if (value.kind === "dateTime") {
      const startedAt: A12DateTime = value;
    }
    break;
  }
  case "optional-date": {
    const optionalDate: OptionalDateCellValue =
      storageValue.value;
    const optionalYear: A12Integer =
      optionalDate.year;
    const optionalMonth: A12Integer =
      optionalDate.month;
    const boundary: OptionalDateBoundary = "first";
    const resolvedDate: A12Date =
      interpreter.resolveOptionalDate(
        optionalDate,
        boundary,
      );
    break;
  }
  case "date-fragment": {
    const fragment: DateFragmentCellValue =
      storageValue.value;
    const fragmentDate: A12Date =
      fragment.resolvedValue;
    if (fragment.granularity === "month") {
      const fragmentMonth: A12Integer = fragment.month;
    }
    break;
  }
  case "none":
  case "unavailable":
    break;
}

if (evaluationValue.kind === "value") {
  const interpreterVisibleValue: A12Value =
    evaluationValue.value;
}
```

`interpreter` has type `Interpreter`, `snapshot` has type `DocumentSnapshot`, `startedAtPointer` has type `FieldPointer`, and `inspectionOptions` has type `InspectionOptions` with the custom-field message locale made explicit. `storedCell` has type `StoredCellInspection`; `storedState` and `decodedStoredValue` expose its callback-free storage result. `documentInspection` has type `DocumentInspection`, the detached index produced by one complete automatic-formal pass. `cell` is the complete `CellInspection`; `storage`, `storageValue`, `evaluationValue`, and `formalValidity` expose its four axes without inference. `value` is the storage-decoded `A12Value`; after its `kind` check, `startedAt` has the narrowed `A12DateTime` type. `optionalDate` is the model-owned incomplete value, and `optionalYear`/`optionalMonth` are its exact `A12Integer` components. `boundary` has type `OptionalDateBoundary` and chooses first-day completion; `resolvedDate` has type `A12Date`. `fragment` has type `DateFragmentCellValue`, `fragmentDate` is its complete `A12Date` projection, and the discriminant-narrowed `fragmentMonth` is the exact `A12Integer` month component. `interpreterVisibleValue` is present only when the evaluator actually interned a concrete `A12Value` and therefore carries normalization such as CRLF-to-LF. The configured interpreter performs this authoritative inspection because formal validity and evaluator availability may depend on fallback charset or custom field-type registries.

Successfully extracted values are ordinary values from the [Operator API proposal](OPERATOR-API-PROPOSAL.md). `A12Operators` is that proposal’s stateless type for the exported `operators` namespace:

```ts
declare const operators: A12Operators;
declare const startedAt: A12DateTime;
declare const completedAt: A12DateTime;

const hours: A12Integer =
  operators.dateTime.differenceInHours(
  startedAt,
  completedAt,
);
```

`operators` has type `A12Operators`, the stateless direct-operator namespace imported from the Operator API. `startedAt` and `completedAt` have type `A12DateTime`, and `hours` has type `A12Integer`, the exact whole-hour difference returned by that operator.

## 6. Evaluation API

### 6.1 Full validation

```ts
type LocaleCode = string;
type ValidationSeverity = "error" | "warning" | "info";
type FindingPolarity = "value" | "omission";

interface ProgramEvaluationOptions {
  readonly locale?: LocaleCode;
  readonly evaluationTime?: A12EvaluationTime;
}

type ValidationOptions = ProgramEvaluationOptions;

interface InspectionOptions {
  readonly locale?: LocaleCode;
}

interface ValidationFindingBase {
  readonly code: string;
  readonly errorPointer: ReportPointer;
  readonly severity: ValidationSeverity;
  readonly polarity: FindingPolarity;
  readonly message: string;
  readonly referencedPointers: readonly ReportPointer[];
  readonly fillToFixPointers: readonly ReportPointer[];
}

interface FormalValidationFinding
  extends ValidationFindingBase {
  readonly origin: "formal";
  readonly code: InspectionDiagnostic["code"];
  readonly diagnostic: InspectionDiagnostic;
}

interface AuthoredRuleValidationFinding
  extends ValidationFindingBase {
  readonly origin: "authored-rule";
  readonly rulePath: string;
}

type ValidationFinding =
  | FormalValidationFinding
  | AuthoredRuleValidationFinding;

type UnsupportedReasonCode =
  | "missing-predefined-field-type"
  | "missing-custom-field-type"
  | "missing-custom-condition"
  | "unsupported-legal-charset"
  | "unsupported-operator"
  | "unsupported-time-zone"
  | "unsupported-program-form";

interface UnsupportedProgramBase {
  readonly path?: string;
  readonly reasonCode: UnsupportedReasonCode;
  readonly detail: string;
}

interface UnsupportedExecutableProgram
  extends UnsupportedProgramBase {
  readonly kind:
    | "rule"
    | "computation"
    | "expression"
    | "custom-condition";
}

interface UnsupportedCustomFieldProgram
  extends UnsupportedProgramBase {
  readonly kind: "custom-field-type";
  readonly reasonCode:
    "missing-custom-field-type";
  readonly pointer: CustomFieldPointer;
  readonly typeName: string;
}

type UnsupportedProgram =
  | UnsupportedExecutableProgram
  | UnsupportedCustomFieldProgram;

type SupportCapabilityKind =
  | "operator"
  | "time-zone"
  | "legal-charset"
  | "predefined-field-type"
  | "custom-field-type"
  | "custom-condition"
  | "program-form";

type SupportCapability =
  | {
      readonly status: "supported";
      readonly kind: SupportCapabilityKind;
      readonly id: string;
      readonly modelPaths: readonly string[];
    }
  | {
      readonly status: "runtime-resolved";
      readonly kind:
        | "custom-field-type"
        | "custom-condition";
      readonly id: string;
      readonly modelPaths: readonly string[];
      readonly detail: string;
    }
  | {
      readonly status: "unsupported";
      readonly kind: SupportCapabilityKind;
      readonly id: string;
      readonly modelPaths: readonly string[];
      readonly reasonCode: UnsupportedReasonCode;
      readonly detail: string;
    };

interface InterpreterSupportReport {
  readonly capabilities: readonly SupportCapability[];
  readonly fullySupported: boolean;
  readonly fullyResolved: boolean;
}

interface ValidationReport {
  readonly findings: readonly ValidationFinding[];
  readonly unsupported: readonly UnsupportedProgram[];
  readonly noErrorOccurred: boolean;
  readonly fullySupported: boolean;
}
```

`LocaleCode` is a native `string` naming the requested message locale, conventionally a model locale such as `"en_US"` or `"de_DE"`. `ProgramEvaluationOptions` contains per-call locale/time data only, and `ValidationOptions` is its validation alias. `InspectionOptions` carries only the locale because inspection performs no authored time-dependent program evaluation, but configured custom-field validators can observe their message locale. Omitting `locale` is exactly equivalent to passing `"en_US"`.

Locale lookup is intentionally explicit and matches the current evaluator:

- An authored stored-rule message uses an exact `Readonly<Record<LocaleCode, string>>` key lookup. There is no language-only or model-default fallback. If a firing rule has no entry for the requested locale, `ValidationFinding.message` is the required native `string` value `""`.
- A field-label placeholder uses the exact requested locale and falls back to the field’s final path segment when that label is absent or blank.
- Interpreter-owned formal, mandatory, uniqueness, custom-type, and over-repetition messages use German when `locale.startsWith("de")`; every other locale receives the interpreter’s English wording.

`ValidationFinding` is detached stable result data. `FormalValidationFinding` carries no invented rule identity; `AuthoredRuleValidationFinding` alone has the required authored `rulePath`. `ReportPointer` preserves concrete coordinates, authored wildcards, and partially known repetition coordinates. A projection may map those pointers to UI paths, but it cannot replace them with fabricated concrete `DocumentPointer` values.

`UnsupportedProgramBase` supplies the optional semantic path plus stable reason/detail shared by unsupported results. `UnsupportedExecutableProgram` covers rules, computations, expressions, and custom conditions. `UnsupportedCustomFieldProgram` additionally carries the exact `CustomFieldPointer` and custom `typeName`; `UnsupportedProgram` is their closed union. `ValidationSeverity`, `FindingPolarity`, and built-in `UnsupportedReasonCode` are closed enums. A custom-field rejection retains its project-defined `errorKey` in the appropriate formal finding rather than turning all built-in codes into arbitrary strings.

`ValidationReport` owns readonly arrays and no evaluator state. `noErrorOccurred` exactly preserves the kernel’s ERROR-severity predicate: warnings and information findings do not make it false. `fullySupported` is true exactly when no requested validation work was omitted for unsupported implementation or configuration semantics. These are independent axes; unsupported work does not fabricate an ERROR finding, and a fully supported report can still contain errors. The required `message: string` member may be empty for a stored rule with no exact locale entry; empty text is not the same as a missing finding.

```ts
declare const interpreter: Interpreter;
declare const snapshot: DocumentSnapshot;

const support: InterpreterSupportReport =
  interpreter.supportReport();
const options: ValidationOptions = {
  locale: "en_US",
};
const report: ValidationReport =
  interpreter.validateFull(snapshot, options);
```

`interpreter` has type `Interpreter`, `snapshot` has type `DocumentSnapshot`, `support` has type `InterpreterSupportReport`, `options` has type `ValidationOptions`, and `report` has type `ValidationReport`. `supportReport()` scans the prepared model against this interpreter’s bound native types, conditions, charset, time-zone implementation, operators, and program forms without reading a request document or invoking application validation callbacks. It lets a native service fail readiness before accepting traffic rather than discovering the same missing capability on every request. `fullySupported` means no known capability is unsupported; `fullyResolved` additionally means none is deferred to a source-platform factory whose invocation cardinality must be preserved. The explicit `"en_US"` value documents the requested message locale even though it is also the omission default.

`validateFull` is the primary complete-document verb rather than a façade-specific `validate` synonym. `validatePart` is its partial-relevance counterpart. Both return the richer kernel-free `ValidationReport`. Pure portable projections may derive from that detached report without reevaluating the document, but an exact native result projection is possible only when the canonical report retains every native datum or the adapter captures an authenticated sidecar during the same evaluation. In particular, the TypeScript kernel’s `Localizable[]` cannot be reconstructed from `message: string`; §10 therefore does not claim a drop-in `DocumentValidationResult` projection from a bare report. A native entry point that promises complete configured behavior requires both `supportReport().fullySupported` and `supportReport().fullyResolved`. A source-exact compatibility adapter does not add an eager factory probe; it preserves the source platform’s normal missing-factory behavior during evaluation unless that factory is explicitly certified safe to preflight.

`report.noErrorOccurred` answers only the kernel error-severity question. A caller that requires a complete clean result checks both `report.noErrorOccurred` and `report.fullySupported`. A messages-only projection that silently drops unsupported programs is unsafe.

Each finding exposes stable data the evaluator can support: origin, code, report pointer, severity, value/omission polarity, localized text, and any verified referenced/fill-to-fix report pointers. Authored findings additionally expose the rule path. Kotlin implementation types do not cross the boundary.

### 6.2 Partial validation

```ts
declare const interpreter: Interpreter;
declare const snapshot: DocumentSnapshot;
declare const relevantPointers:
  readonly RelevantEntityPointer[];

const options: ValidationOptions = {
  locale: "en_US",
};
const report: ValidationReport = interpreter.validatePart(
  snapshot,
  relevantPointers,
  options,
);
```

`interpreter` has type `Interpreter`, and `snapshot` has type `DocumentSnapshot`. `relevantPointers` is a readonly `RelevantEntityPointer` array; each element is a model-owned field/group identity plus concrete or tagged `ALL` repetition coordinates, and the array is the complete kernel-style relevance set for this validation rather than merely the fields changed since the previous call. `options` has type `ValidationOptions`, and `report` has type `ValidationReport`, the detached partial-validation result. Partial validation changes operand visibility and three-valued suppression; it is not “run full validation and filter messages.”

For an on-change form, full validation is the safe default. A form that already owns the current relevance set may call `validatePart`. A future `validateChanges(previous, next, changed)` helper must not be added until its dependency/relevance semantics are designed and proven.

### 6.3 Stored rules

```ts
type RuleOutcome =
  | {
      readonly kind: "fired";
      readonly location: GroupPointer;
      readonly findings: readonly ValidationFinding[];
    }
  | {
      readonly kind: "passed";
      readonly location: GroupPointer;
    }
  | {
      readonly kind: "suppressed";
      readonly location: GroupPointer;
      readonly reasons: readonly FormalSuppression[];
    }
  | {
      readonly kind: "unsupported";
      readonly location?: GroupPointer;
      readonly reason: UnsupportedProgram;
    };

interface StoredRuleEvaluation {
  readonly rulePath: string;
  readonly outcomes: readonly RuleOutcome[];
}
```

`FormalSuppression.pointer` is the `FieldPointer` of the formally invalid operand that prevented one row from evaluating. `RuleOutcome.location` is the `GroupPointer` of that evaluated rule-group row; the unsupported case omits it only when the program cannot be prepared far enough to establish an iteration location. `StoredRuleEvaluation` retains every row outcome instead of collapsing them.

```ts
declare const interpreter: Interpreter;
declare const snapshot: DocumentSnapshot;

const options: ValidationOptions = {
  locale: "en_US",
};
const result: StoredRuleEvaluation =
  interpreter.evaluateStoredRule(
  "/Order/rules/DiscountLimit",
  snapshot,
  options,
);
```

`interpreter` has type `Interpreter`, and `snapshot` has type `DocumentSnapshot`. `options` has type `ValidationOptions` and selects the message locale. `result` has type `StoredRuleEvaluation`, the detached row-preserving outcome set for the requested stored rule path.

The current single-rule API collapses repeated rows, so this result shape is new boundary/engine work rather than a rename.

### 6.4 Computations

```ts
type ComputationWriteEffect =
  | {
      readonly kind: "write";
      readonly previousRaw?: string;
      readonly nextRaw: string;
      readonly createsAncestors: boolean;
    }
  | {
      readonly kind: "none";
      readonly reason: "already-equal";
    };

type ComputationClearEffect =
  | {
      readonly kind: "clear";
      readonly previousRaw: string;
      readonly nextRaw: "";
    }
  | {
      readonly kind: "none";
      readonly reason:
        | "target-absent"
        | "target-already-empty";
    };

type ComputationOutcome =
  | {
      readonly kind: "value";
      readonly target: FieldPointer;
      readonly value: A12Value;
      readonly storedRaw: string;
      readonly application: ComputationWriteEffect;
    }
  | {
      readonly kind: "cleared";
      readonly target: FieldPointer;
      readonly application: ComputationClearEffect;
    }
  | {
      readonly kind: "errored";
      readonly target: FieldPointer;
      readonly attemptedRaw: string;
      readonly attemptedValue?: A12Value;
      readonly diagnostics: readonly InspectionDiagnostic[];
      readonly application: ComputationClearEffect;
    }
  | {
      readonly kind: "formal-error";
      readonly target: FieldPointer;
      readonly reasons: readonly FormalSuppression[];
      readonly application: {
        readonly kind: "none";
        readonly reason: "formal-error";
      };
    };

type ComputationDeltaEntry =
  | {
      readonly kind: "write";
      readonly target: FieldPointer;
      readonly previousRaw?: string;
      readonly nextRaw: string;
      readonly createsAncestors: boolean;
    }
  | {
      readonly kind: "clear";
      readonly target: FieldPointer;
      readonly previousRaw: string;
      readonly nextRaw: "";
      readonly createsAncestors: false;
    };

interface ComputeOptions {
  readonly locale?: LocaleCode;
  readonly evaluationTime?: A12EvaluationTime;
}

interface ComputationReport {
  readonly outcomes: readonly ComputationOutcome[];
  readonly unsupported: readonly UnsupportedProgram[];
  readonly delta: readonly ComputationDeltaEntry[];
  readonly changed: boolean;
  readonly noErrorOccurred: boolean;
  readonly fullySupported: boolean;
}

type ComputationApplicationFailure =
  "unsupported";

type ComputeAndApplyResult =
  | {
      readonly kind: "applied";
      readonly computations: ComputationReport;
      readonly snapshot: DocumentSnapshot;
    }
  | {
      readonly kind: "notApplied";
      readonly reason: ComputationApplicationFailure;
      readonly computations: ComputationReport;
      readonly snapshot: DocumentSnapshot;
    };
```

`ComputationOutcome` is detached result data for one `FieldPointer` target; a computation cannot target a group. It preserves four distinct result channels: a value, an explicit clear, an errored attempted value, and formal operand suppression. Every errored outcome retains its attempted raw storage text; `attemptedValue` exists only when typed conversion succeeded. `application` states the exact proposed storage effect, including a successful value that is already equal, a clear/error against an absent or already-empty target, and whether a value write must create ancestor placements.

`ComputationReport.delta` contains only actual storage changes, in kernel application order. `changed` is exactly `delta.length > 0`. `noErrorOccurred` preserves the kernel computation-result error predicate and is false for the kernel channels classified as errored or formal-error; unsupported coverage remains independent. `fullySupported` is true exactly when `unsupported` is empty. Consumers do not infer any of these facts from array lengths or messages.

The richer outcome algebra must project losslessly to the established computation capabilities: `"value"` outcomes form the computed-without-errors collection, value outcomes with a `"write"` application form its with-changes subset, `"errored"` outcomes form computed-with-errors, `"cleared"` outcomes form cleared fields, and `"formal-error"` reasons form formal-errors-in-operands. `noErrorOccurred` is already the corresponding aggregate. A compatibility adapter exposes those familiar collections when required. Kernel-style application is owned by the adapter’s authenticated source-bound computation result, which retains the original native document and exact application classification; a bare detached `ComputationReport` is intentionally not accepted as an application handle.

`ComputationApplicationFailure` has only the value `"unsupported"`: an incomplete or unsupported computation program prevents every application and preserves the original snapshot. When all programs are supported, application exactly follows kernel placement behavior. A `value` outcome creates missing target placement and required ancestor placements as necessary, then stores `storedRaw`. A `cleared` or `errored` outcome makes an existing target placement present-empty (`raw: ""`), but an absent target remains absent and no ancestor is created solely to clear it. A formal-error outcome does not write. The result is `kind: "applied"` even when ordinary errored/formal channels make `noErrorOccurred` false; these are report data rather than atomic-application failures.

```ts
declare const interpreter: Interpreter;
declare const snapshot: DocumentSnapshot;

const options: ComputeOptions = {};
const report: ComputationReport =
  interpreter.compute(snapshot, options);
const totals: ComputationReport = interpreter.computeField(
  "/Order/Total",
  snapshot,
  options,
);

const application: ComputeAndApplyResult =
  interpreter.computeAndApply(snapshot, options);
const appliedComputations: ComputationReport =
  application.computations;

if (application.kind === "applied") {
  const computedSnapshot: DocumentSnapshot =
    application.snapshot;
}
```

`interpreter` has type `Interpreter`, and `snapshot` has type `DocumentSnapshot`. `options` has type `ComputeOptions`; the empty object requests the configured clock and `"en_US"` message-locale defaults. The locale is observable when computation performs formal/custom-field validation and emits diagnostics. `report` and `totals` have type `ComputationReport`: the former covers every stored computation and the latter only the target path. `application` has type `ComputeAndApplyResult`, while `appliedComputations` is its detached `ComputationReport`. In the discriminant-narrowed applied branch, `computedSnapshot` has type `DocumentSnapshot` and is the atomically updated frozen document.

Each computation outcome identifies its target pointer and is one of value, cleared, errored, or formal-error. An errored result carries attempted raw storage text and an optional typed value only when conversion succeeded; it must not falsely promise an invalid attempt is an `A12Value`. A formal-error result retains every exact suppressing field pointer/code rather than collapsing it into a generic error.

`computeAndApply` performs one computation run and returns its outcomes plus either the atomically updated snapshot or, only when an unsupported program prevents complete execution, the unchanged input snapshot. The applied snapshot realizes exactly `ComputationReport.delta`; it does not derive a second answer. Cells not named by that delta retain their exact raw storage. Calling `compute` and then an `applyComputations` method that recomputes everything would be surprising and wasteful.

For workflows that need a shared time and no intermediate orchestration, a combined transaction is feasible:

```ts
type ComputeAndValidateOptions = ValidationOptions;

type ComputeAndValidateResult =
  | {
      readonly kind: "completed";
      readonly computations: ComputationReport;
      readonly validation: ValidationReport;
      readonly snapshot: DocumentSnapshot;
    }
  | {
      readonly kind: "computationsNotApplied";
      readonly reason: ComputationApplicationFailure;
      readonly computations: ComputationReport;
      readonly snapshot: DocumentSnapshot;
    };

declare const interpreter: Interpreter;
declare const snapshot: DocumentSnapshot;

const options: ComputeAndValidateOptions = {
  locale: "en_US",
};
const result: ComputeAndValidateResult =
  interpreter.computeAndValidate(snapshot, options);
```

`interpreter` has type `Interpreter`, and `snapshot` has type `DocumentSnapshot`. `options` has type `ComputeAndValidateOptions`, the validation-compatible per-call time/locale input, and `result` has type `ComputeAndValidateResult`, the discriminated completed/not-applied transaction result. The method computes once and, when all programs are supported, applies every outcome using the same placement rules as `computeAndApply`: values create and store their targets; clear/error outcomes empty existing targets but do not create absent ones. It then validates that fully updated snapshot. If any program is unsupported, `kind` is `computationsNotApplied`, `reason` is `"unsupported"`, `snapshot` is the unchanged input, and no misleading validation report is present. This is a convenience over the same computation and validation engines, not a second execution strategy.

### 6.5 Interactive and service use

A form retains one prepared model and configured interpreter for its lifetime:

```ts
declare const prepared:
  PreparedModelWithDocumentEditing;
declare const configuration: InterpreterConfiguration;
declare const initialDocument: PlacementDocumentInputV1;
declare const render:
  (report: ValidationReport) => void;

const interpreter: Interpreter =
  prepared.createInterpreter(configuration);

let snapshot: DocumentSnapshot =
  prepared.documents.readPlacements(initialDocument);

function onValueChanged(
  pointer: FieldPointer,
  raw: string,
): void {
  const editor: PersistentDocumentEditor =
    prepared.documents.edit(snapshot);
  editor.putRaw(pointer, raw);
  snapshot = editor.freeze();

  const report: ValidationReport =
    interpreter.validateFull(snapshot);
  render(report);
}
```

`prepared` has type `PreparedModelWithDocumentEditing`, the optional editing-capable prepared definition; `configuration` has type `InterpreterConfiguration`; `initialDocument` has type `PlacementDocumentInputV1`; and `render` is a caller-provided synchronous `(ValidationReport) => void` callback. `interpreter` has type `Interpreter`, while mutable `snapshot` has type `DocumentSnapshot`. `onValueChanged` is an explicitly `void`-returning function whose `pointer: FieldPointer` identifies one concrete model-owned cell and whose `raw: string` is native input text. Inside it, `editor` has type `PersistentDocumentEditor`, and `report` has type `ValidationReport`, the detached result passed to `render`.

This host-neutral example describes the optional persistent-editor behavior without claiming current browser qualification. When that form layer ships, every change creates a structurally shared snapshot; it does not reload the model, reparse stored programs, or rebuild static indexes.

Native `map` is sufficient for small batches. A `validateMany` convenience is justified only if profiling shows meaningful JavaScript boundary or scheduling savings; it must not hide unbounded parallelism.

## 7. Optional prepared programs

### 7.1 Candidate rules

Rule-preview tools reasonably expect prepare-once evaluation:

```ts
interface CandidateRuleDefinition {
  readonly group: string;
  readonly name: string;
  readonly errorField: string;
  readonly condition: string;
  readonly code: string;
  readonly severity: ValidationSeverity;
  readonly messages:
    Readonly<Record<LocaleCode, string>>;
}

interface PreparedRule extends ModelOwned {
  readonly name: string;
}

interface CandidateRuleEvaluation {
  readonly certification: "preview";
  readonly outcomes: readonly RuleOutcome[];
}

declare const prepared: PreparedProgramModel;
declare const interpreter: PreparedProgramInterpreter;
declare const snapshot: DocumentSnapshot;

const options: ProgramEvaluationOptions = {
  locale: "en_US",
  evaluationTime: interpreter.captureEvaluationTime(),
};
const definition: CandidateRuleDefinition = {
  group: "/Order",
  name: "CandidateDiscountLimit",
  errorField: "/Order/DiscountPercent",
  condition: "FieldFilled(DiscountPercent) And [DiscountPercent] > 50",
  code: "DISCOUNT_TOO_HIGH",
  severity: "error",
  messages: {
    en_US: "Discount must not exceed 50%.",
  },
};

const candidate: PreparedRule =
  prepared.prepareRule(definition);
const result: CandidateRuleEvaluation =
  interpreter.evaluateRule(
  candidate,
  snapshot,
  options,
);
```

`prepared` has type `PreparedProgramModel`, and `interpreter` has type `PreparedProgramInterpreter`; those are the optional preparation/evaluation capability interfaces. `snapshot` has type `DocumentSnapshot`. `options` has type `ProgramEvaluationOptions`, `definition` has type `CandidateRuleDefinition`, `candidate` has type `PreparedRule`, and `result` has type `CandidateRuleEvaluation`. `CandidateRuleDefinition` is caller-owned source data. `PreparedRule` is a nominal, parse-once `ModelOwned` handle, not a rulekit AST. `CandidateRuleEvaluation` is explicitly marked as preview data and uses the same row-preserving `RuleOutcome` family; core `PreparedModel` and `Interpreter` do not promise these methods.

The current `evalCandidate` reparses per call and does not share every stored-rule special path. A public prepared candidate requires syntax and model-reference validation, parse-once reuse, owner checks, and rejection whenever it would use weaker semantics.

Local preparation is not kernel model-consistency certification. Unless a real certification service is designed, candidate evaluation is explicitly a preview.

### 7.2 Symbolic expressions

Developers may want to observe a calculation against a document:

```ts
interface NumberExpressionDefinition {
  readonly text: string;
  readonly baseGroup: string;
}

interface PreparedNumberExpression extends ModelOwned {}

interface EvaluationLocation {
  readonly group: string;
  readonly coordinates: readonly number[];
}

type ExpressionEvaluation<T extends A12Value> =
  | {readonly kind: "value"; readonly value: T}
  | {
      readonly kind: "notEvaluated";
      readonly reason: ExpressionNonEvaluation;
    }
  | {
      readonly kind: "suppressed";
      readonly reasons: readonly FormalSuppression[];
    }
  | {
      readonly kind: "unsupported";
      readonly reason: UnsupportedProgram;
    };

interface ExpressionNonEvaluation {
  readonly code: string;
  readonly detail: string;
}

declare const prepared: PreparedProgramModel;
declare const interpreter: PreparedProgramInterpreter;
declare const snapshot: DocumentSnapshot;

const options: ProgramEvaluationOptions = {
  evaluationTime: interpreter.captureEvaluationTime(),
};
const definition: NumberExpressionDefinition = {
  text: "Sum(Lines*/Amount) * {1 - [DiscountPercent] / 100}",
  baseGroup: "/Order",
};
const expression: PreparedNumberExpression =
  prepared.prepareNumberExpression(definition);

const evaluationLocation: EvaluationLocation = {
  group: "/Order",
  coordinates: [1],
};
const result: ExpressionEvaluation<A12Number> =
  interpreter.evaluateExpression(
  expression,
  snapshot,
  evaluationLocation,
  options,
);
```

`prepared` has type `PreparedProgramModel`, `interpreter` has type `PreparedProgramInterpreter`, and `snapshot` has type `DocumentSnapshot`. `options` has type `ProgramEvaluationOptions`. `definition` has type `NumberExpressionDefinition`, the number-only source plus its reference base, while `expression` has type `PreparedNumberExpression`, a nominal parse-once `ModelOwned` handle. `evaluationLocation` has type `EvaluationLocation` and supplies the concrete group row. `result` has type `ExpressionEvaluation<A12Number>`, so its value case contains exactly `A12Number`. `ExpressionEvaluation<T>` also preserves a domain/no-value `notEvaluated` result such as division by zero, formal suppression, or unsupported semantics. `ExpressionNonEvaluation` is distinct from `FormalSuppression` because no formally invalid field need be involved.

This is not the direct operator API: the expression contains paths and evaluates later against document/repetition context. The current engine has no stable generic public expression result or context type. A number-only, simple-location API is the first plausible subset; parallel/correlated contexts and a typed symbolic builder need separate design.

### 7.3 Candidate computations

An ad hoc computation has a target, iteration scope, alternatives, guards, dependency closure, cycle handling, poison behavior, target formatting, and preview/apply semantics. It must not be reduced to a targetless expression. Candidate computations remain deferred until that complete contract exists.

## 8. Concrete repeated-model validation-service API

### 8.1 When the service layer is useful

A form, command, or single worker with one known model should retain one `PreparedModel` and one `Interpreter` directly. The service layer is for applications that need at least one of these capabilities:

- many registered model versions selected per request;
- fast interleaving such as `D1, D2, D1, D2`;
- several simultaneous active interpreter instances for one hot model;
- atomic routing from a logical model alias to a new version;
- bounded ownership, eviction, backpressure, metrics, and shutdown.

The service does not make `PreparedModel`, `Interpreter`, callbacks, or document snapshots concurrently callable. It coordinates immutable tokens and leases one active interpreter instance exclusively for each request.

Four ownership domains must remain distinct:

| Domain | Owner | Contents and lifecycle |
|---|---|---|
| A12 model registry | service or Worker | immutable exact versions, fingerprints, aliases, source bounds, preparation, pins, retirement |
| A12 configuration/runtime registry | service or Worker | configuration identities, exclusive callback bindings, warm lanes, backpressure, disposal |
| Optional model-derived adapter cache | exact prepared owner or exact immutable model entry | portable DTO schema/codec helpers; a snapshot-producing codec is owner-bound and retires with that owner |
| Application metadata registry | application | widgets, help, application paths, analytics, and presentation data keyed by owner-aware model handles |

An alias is routing state, never an immutable cache identity. No schema/codec cache owns model versions, no A12 registry silently owns application metadata, and no request document, locale, clock sample, callback, or result belongs in any model-definition cache.

### 8.2 Service types

```ts
declare const modelAliasIdentityBrand: unique symbol;
declare const interpreterConfigurationIdentityBrand:
  unique symbol;

type ModelAliasIdentity = {
  readonly [modelAliasIdentityBrand]: never;
};

type InterpreterConfigurationIdentity = {
  readonly [interpreterConfigurationIdentityBrand]: never;
};

interface ValidationServiceOptions {
  readonly maxModels: number;
  readonly maxInterpreterConfigurations: number;
  readonly maxModelSourceBytes: number;
  readonly maxTotalModelSourceBytes: number;
  readonly documentDecodeLimits: DocumentDecodeLimits;
  readonly maxRuntimesPerModelConfiguration: number;
  readonly maxRuntimesPerModel: number;
  readonly maxTotalRuntimes: number;
  readonly runtimeAcquireTimeoutMs: number;
}

declare class RegisteredModel {
  private constructor();
  private readonly __registeredModelBrand: void;
  readonly modelVersion: ModelVersionIdentity;
  readonly fingerprint: ModelFingerprint;
  release(): void;
}

declare class RegisteredModelAlias {
  private constructor();
  private readonly __registeredModelAliasBrand: void;
  readonly identity: ModelAliasIdentity;
  readonly name: string;
  release(): void;
}

interface InterpreterBindings {
  readonly predefinedTypes: PredefinedTypeRegistry;
  readonly customFieldTypes: CustomFieldTypeRegistry;
  readonly customConditions: CustomConditionRegistry;
  readonly dispose?: () => void;
}

interface InterpreterConfigurationRegistration {
  readonly fallbackLegalCharset?: LegalCharset;
  readonly createBindings: () => InterpreterBindings;
}

declare class RegisteredInterpreterConfiguration {
  private constructor();
  private readonly __registeredConfigurationBrand: void;
  readonly identity: InterpreterConfigurationIdentity;
  release(): void;
}

interface ValidatePlacementsRequest {
  readonly model: RegisteredModel | RegisteredModelAlias;
  readonly document: unknown;
  readonly options?: ValidationOptions;
  readonly configuration?:
    RegisteredInterpreterConfiguration;
}

interface SupportModelRequest {
  readonly model: RegisteredModel | RegisteredModelAlias;
  readonly configuration?:
    RegisteredInterpreterConfiguration;
}

interface WarmModelRequest {
  readonly model: RegisteredModel | RegisteredModelAlias;
  readonly configuration?:
    RegisteredInterpreterConfiguration;
  readonly instances: number;
}

interface WarmModelReport {
  readonly warmedInstances: number;
  readonly preparedProgramsPerInstance: number;
}

interface ValidationServiceMetrics {
  readonly state: ValidationServiceState;
  readonly registeredModels: number;
  readonly registeredInterpreterConfigurations: number;
  readonly idleRuntimes: number;
  readonly activeRuntimes: number;
  readonly inFlightOperations: number;
  readonly disposingRuntimes: number;
  readonly waitingRequests: number;
  readonly rejectedRequests: number;
  readonly modelCacheHits: number;
  readonly modelCacheMisses: number;
  readonly modelPreparations: number;
  readonly modelEvictions: number;
  readonly runtimeCreations: number;
  readonly runtimeEvictions: number;
}

type ServiceHandleKind =
  | "model"
  | "model-alias"
  | "interpreter-configuration";

type RetirableServiceHandleKind =
  | "model"
  | "model-alias";

type ValidationServiceState =
  | "open"
  | "draining"
  | "closed";

type ValidationServiceCapacityKind =
  | "models"
  | "interpreter-configurations"
  | "model-source-bytes"
  | "total-model-source-bytes"
  | "runtimes-per-model-configuration"
  | "runtimes-per-model"
  | "total-runtimes";

type ValidationServiceIntegrationPhase =
  | "create"
  | "evaluate"
  | "dispose";

type ReentrantLifecycleOperation =
  | "drain"
  | "close"
  | "release-interpreter-configuration";

type ValidationServiceErrorCode =
  | "A12_RUNTIME_POOL_SATURATED"
  | "A12_VALIDATION_SERVICE_DRAINING"
  | "A12_VALIDATION_SERVICE_CLOSED"
  | "A12_SERVICE_HANDLE_RELEASED"
  | "A12_SERVICE_HANDLE_RETIRED"
  | "A12_FOREIGN_SERVICE_HANDLE"
  | "A12_VALIDATION_SERVICE_CAPACITY"
  | "A12_REENTRANT_SERVICE_LIFECYCLE"
  | "A12_VALIDATION_SERVICE_INTEGRATION_FAILED";

interface ValidationServiceError extends Error {
  readonly code: ValidationServiceErrorCode;
}

interface RuntimePoolSaturatedError
  extends ValidationServiceError {
  readonly code: "A12_RUNTIME_POOL_SATURATED";
  readonly modelVersion: ModelVersionIdentity;
  readonly timeoutMs: number;
}

interface ValidationServiceDrainingError
  extends ValidationServiceError {
  readonly code: "A12_VALIDATION_SERVICE_DRAINING";
}

interface ValidationServiceClosedError
  extends ValidationServiceError {
  readonly code: "A12_VALIDATION_SERVICE_CLOSED";
}

interface ReleasedServiceHandleError
  extends ValidationServiceError {
  readonly code: "A12_SERVICE_HANDLE_RELEASED";
  readonly handleKind: ServiceHandleKind;
}

interface RetiredServiceHandleError
  extends ValidationServiceError {
  readonly code: "A12_SERVICE_HANDLE_RETIRED";
  readonly handleKind: RetirableServiceHandleKind;
}

interface ForeignServiceHandleError
  extends ValidationServiceError {
  readonly code: "A12_FOREIGN_SERVICE_HANDLE";
  readonly handleKind: ServiceHandleKind;
}

interface ValidationServiceCapacityError
  extends ValidationServiceError {
  readonly code: "A12_VALIDATION_SERVICE_CAPACITY";
  readonly capacity: ValidationServiceCapacityKind;
  readonly requested: number;
  readonly maximum: number;
}

interface ValidationServiceIntegrationError
  extends ValidationServiceError {
  readonly code:
    "A12_VALIDATION_SERVICE_INTEGRATION_FAILED";
  readonly phase: ValidationServiceIntegrationPhase;
  readonly detail: string;
  readonly cause?: unknown;
}

interface ReentrantServiceLifecycleError
  extends ValidationServiceError {
  readonly code: "A12_REENTRANT_SERVICE_LIFECYCLE";
  readonly operation: ReentrantLifecycleOperation;
  readonly phase: ValidationServiceIntegrationPhase;
}

type KnownValidationServiceError =
  | RuntimePoolSaturatedError
  | ValidationServiceDrainingError
  | ValidationServiceClosedError
  | ReleasedServiceHandleError
  | RetiredServiceHandleError
  | ForeignServiceHandleError
  | ValidationServiceCapacityError
  | ReentrantServiceLifecycleError
  | ValidationServiceIntegrationError;
```

The types have deliberately narrow roles:

| Type | Meaning |
|---|---|
| `ValidationService` | caller-owned composite that coordinates model/configuration registries and exclusively leased interpreter instances |
| `ValidationServiceConstructor` | the typed `new ValidationService(options)` value exported to TypeScript |
| `ValidationServiceModelRegistry` | typed registration, alias activation, and retirement operations for model versions |
| `ValidationServiceConfigurationRegistry` | typed registration operation for custom interpreter configurations |
| `ValidationServiceOptions` | enforceable model, configuration, source-byte, document-decode, runtime, and wait bounds; every `number` is a safe integer and §8.3 defines which must be positive |
| `RegisteredModel` | service-issued immutable model-version token plus a caller pin; it is not a `PreparedModel` or active interpreter |
| `RegisteredModelAlias` | service-issued, runtime-owner-checked logical-name token whose target can switch atomically between immutable model versions |
| `InterpreterBindings` | one active interpreter instance’s predefined/custom implementations and optional exclusive cleanup callback |
| `InterpreterConfigurationRegistration` | factory definition used only when the application customizes interpreter semantics |
| `RegisteredInterpreterConfiguration` | service-issued immutable configuration token plus a caller pin; it is a pool-key dimension, not the callback instances themselves |
| `ValidatePlacementsRequest` | one complete validation request: model selector, untrusted portable placement input, per-call options, and optional custom configuration |
| `SupportModelRequest` | one model/configuration selector for readiness inspection without a document |
| `WarmModelRequest` / `WarmModelReport` | explicit eager warmup request and its measured result |
| `ValidationServiceMetrics` | detached lifecycle state plus gauges/counters for registrations, admitted work, disposal, pool use, cache hits/misses, preparation, creation, and eviction |
| `ValidationServiceError` | stable JS-safe base exception for service lifecycle, capacity, integration, and saturation failures |
| `ServiceHandleKind` | closed discriminator for model, model-alias, and interpreter-configuration handles |
| `RetirableServiceHandleKind` | closed model/model-alias subset accepted by the retirement API |
| `ValidationServiceState` | closed `open`/`draining`/`closed` lifecycle state exposed in metric snapshots |
| `ValidationServiceCapacityKind` | closed discriminator naming the exact configured bound that rejected an operation |
| `ValidationServiceIntegrationPhase` | closed `create`/`evaluate`/`dispose` phase discriminator for service-owned binding, callback, or cleanup failures |
| `ReentrantLifecycleOperation` | closed discriminator for a lifecycle operation that would wait for or dispose the callback currently invoking it |
| `ValidationServiceErrorCode` | closed set of service-error codes available for exhaustive consumer handling |
| `RuntimePoolSaturatedError` | service exception thrown when no exclusive runtime becomes available within the configured timeout |
| `ValidationServiceDrainingError` | service exception rejecting new work before admission and canceling already-admitted validation/warmup still queued for runtime resources when draining begins |
| `ValidationServiceClosedError` | service exception raised when an operation targets a closed service |
| `ReleasedServiceHandleError` | service exception raised when an operation receives an already released token; `handleKind` identifies it |
| `RetiredServiceHandleError` | service exception raised when a model or alias has been explicitly retired; `handleKind` identifies it |
| `ForeignServiceHandleError` | service exception raised when a token belongs to another `ValidationService`; `handleKind` identifies it |
| `ValidationServiceCapacityError` | service exception containing the rejected capacity dimension, requested count/bytes, and configured maximum |
| `ReentrantServiceLifecycleError` | service exception rejecting a same-service `drain`, `close`, or configuration release invoked synchronously from an integration callback |
| `ValidationServiceIntegrationError` | service exception identifying whether service-owned binding, evaluation, or disposal failed while preserving the original core/callback cause |
| `KnownValidationServiceError` | discriminated union of every service error above; unexpected invariant failures are not converted into this family |

`InterpreterConfigurationIdentity`, `ModelVersionIdentity`, and `ModelAliasIdentity` are opaque library identities. Their module-private `unique symbol` brands prevent accidental construction in checked TypeScript only. The generated declarations may instead expose wrapper classes with non-public constructors, but every service operation still authenticates the runtime token and owning service because `any`, assertions, duplicate bundles, and plain JavaScript can bypass static declarations.

`RegisteredModel`, `RegisteredModelAlias`, and `RegisteredInterpreterConfiguration` are nominal, non-constructible service tokens rather than structurally forgeable object shapes. Every token also carries a hidden owning-service identity. Each service operation checks that owner and the token’s live state before pinning; a token from another service fails with `ForeignServiceHandleError`. Java projects the same tokens as final `AutoCloseable` classes with non-public constructors.

The public service owns typed registries and operations with these signatures:

```ts
interface ValidationServiceModelRegistry {
  register(expandedModelJson: ExpandedModelJson): RegisteredModel;
  createAlias(
    name: string,
    initial: RegisteredModel,
  ): RegisteredModelAlias;
  activate(
    alias: RegisteredModelAlias,
    next: RegisteredModel,
  ): void;
  retire(
    model: RegisteredModel | RegisteredModelAlias,
  ): void;
}

interface ValidationServiceConfigurationRegistry {
  register(
    definition: InterpreterConfigurationRegistration,
  ): RegisteredInterpreterConfiguration;
}

interface ValidationService {
  readonly models: ValidationServiceModelRegistry;
  readonly interpreterConfigurations:
    ValidationServiceConfigurationRegistry;

  validatePlacements(
    request: ValidatePlacementsRequest,
  ): ValidationReport;
  support(
    request: SupportModelRequest,
  ): InterpreterSupportReport;
  warm(request: WarmModelRequest): WarmModelReport;
  metrics(): ValidationServiceMetrics;
  drain(): void;
  close(): void;
}

interface ValidationServiceConstructor {
  new(options: ValidationServiceOptions): ValidationService;
}

declare const ValidationService:
  ValidationServiceConstructor;
```

On the JVM, `ValidationService`, both registries, and every service-issued handle are safe for concurrent use. Public registration, alias activation, pin/release, retirement, admission, drain, close, and metric-snapshot transitions are linearizable, and prepared objects are safely published before any request can observe them. Each runtime has at most one exclusive lease; concurrent validation of one model uses multiple runtimes, never concurrent entry into one `Interpreter`, and no global or per-model evaluation lock may serialize evaluation.

The service has the one-way state machine `open → draining → closed`. Model/configuration registration, alias creation/activation/retirement, support inspection, validation, and warmup are **admitted work**. Each such operation atomically checks `state === "open"` and increments `inFlightOperations` before releasing lifecycle coordination; the count reaches zero only after all parsing, runtime/binding creation, publication or rollback, result detachment, and operation-owned cleanup has finished. Waiting acquisitions and active leases are included in admitted work and also exposed separately. Any disposal triggered outside admitted work increments `disposingRuntimes`.

`drain()` linearizes the transition from `open` to `draining`, rejects new work before admission, wakes queued runtime acquisitions with `ValidationServiceDrainingError`, and waits for `inFlightOperations`, `waitingRequests`, `activeRuntimes`, and `disposingRuntimes` all to reach zero. Work admitted before the transition may finish and publish normally; because drain waits for its complete commit or rollback, nothing remains half-published. `drain()` leaves the service in `draining`; there is no resume operation.

`close()` performs the same transition/quiescence when necessary, then retires every idle runtime, tracks and waits for every disposal, invalidates tokens, clears registries, and only then publishes `closed`. It attempts all cleanup even if one binding throws; after cleanup it throws the first `ValidationServiceIntegrationError`, with the remaining failures summarized in that error’s `detail`. Since `closed` is published only after admitted work and disposal are quiescent, no preparation, runtime creation, callback, commit, or cleanup can publish state or leak a resource after close returns.

Synchronous lifecycle reentrancy is rejected rather than allowed to self-deadlock. While the current JVM thread or Node isolate is executing `createBindings`, a custom field/condition callback, or `dispose` for this service, calling this same service’s `drain()` or `close()`, or releasing any `RegisteredInterpreterConfiguration` issued by this service, throws `ReentrantServiceLifecycleError` before changing service/token state or waiting. This applies even when a callback created for configuration A tries to release configuration B, because cross-configuration callbacks can otherwise wait on one another’s active leases/disposal. Its `operation` identifies the attempted call and `phase` identifies the active `"create"`, `"evaluate"`, or `"dispose"` integration frame. If the callback does not catch that exception, the service boundary wraps it as `ValidationServiceIntegrationError`; the reentrancy guard still prevents a wait cycle. Model/alias `release()` remains non-blocking and safe because admitted work already owns a temporary pin. Calls from another thread are not reentrant; they follow the normal quiescence contract and may wait until the callback returns.

Repeated lifecycle calls are deterministic. A second `drain()` joins the current quiescence wait or returns immediately when already quiescent/closed. Exactly one concurrent `close()` caller performs cleanup; other close callers join it and observe the same completion or cleanup error. A close concurrent with an earlier drain joins that drain and then closes. A drain ordered after close has begun joins through close completion; one ordered before close may return at drained quiescence while the later close performs final disposal. Once closed, `drain()`, `close()`, token `release()`, and `metrics()` remain harmless; all other operations fail.

Error selection uses one fixed precedence. A lifecycle operation first enforces the same-service reentrancy guard because it must not inspect or mutate lifecycle state before that check. An ordinary work operation first observes service state (`ValidationServiceClosedError`, otherwise `ValidationServiceDrainingError`), then performs an ownership pass over every handle (`ForeignServiceHandleError`), then a token-state pass (`ReleasedServiceHandleError` before `RetiredServiceHandleError`), and only then reports input, capacity, or pool-saturation failures. Within either handle pass, `ValidatePlacementsRequest`, `SupportModelRequest`, and `WarmModelRequest` check `model` before `configuration`; `createAlias` checks `initial`; and `activate` checks `alias` before `next`. Thus even two invalid handles select one deterministic `handleKind`.

Once admitted, a temporary pin fixes the selected model/configuration, so a later release, retirement, or alias activation cannot change that operation. Drain/close have one explicit cancellation point: an admitted validation still waiting for a runtime lease, or a warmup still waiting for runtime capacity, is woken with `ValidationServiceDrainingError` and rolls back its temporary pins and in-flight count. Validation performs no evaluation; warmup retires and disposes any runtime created solely by that still-incomplete request before returning the error, so no partial warm request is published. Once an operation owns every runtime resource needed to execute, it finishes normally before drain/close quiescence.

Registry/pool coordination locks are never held while parsing a model, evaluating a document, invoking application callbacks, or disposing bindings; only the state check, counters, pins, and publication step occur under coordination. These lifecycle and error-order rules are part of the contract and require race tests, not implementation commentary.

These are synchronous evaluator operations. On the JVM, `validatePlacements` is thread-safe and may wait up to `runtimeAcquireTimeoutMs` for an exclusive runtime before throwing `RuntimePoolSaturatedError`; no evaluation has begun in that case. In one Node isolate, calls are synchronous and non-overlapping; a separate worker coordinator may expose an asynchronous `Promise<ValidationReport>` API while each worker owns its own service and caches.

### 8.3 Default validation call

The ordinary case requires no interpreter-configuration registration:

```ts
declare const expandedModelJson: ExpandedModelJson;

const serviceOptions: ValidationServiceOptions = {
  maxModels: 64,
  maxInterpreterConfigurations: 16,
  maxModelSourceBytes: 4_000_000,
  maxTotalModelSourceBytes: 128_000_000,
  documentDecodeLimits: {
    maxGroupPlacements: 10_000,
    maxFieldPlacements: 100_000,
    maxCoordinatesPerPlacement: 16,
    maxPathCodeUnits: 1_024,
    maxRawCodeUnitsPerField: 1_000_000,
    maxTotalRawCodeUnits: 16_000_000,
    maxModelNestingDepth: 32,
  },
  maxRuntimesPerModelConfiguration: 4,
  maxRuntimesPerModel: 8,
  maxTotalRuntimes: 128,
  runtimeAcquireTimeoutMs: 250,
};
const service: ValidationService =
  new ValidationService(serviceOptions);

const ordersModelV2: RegisteredModel =
  service.models.register(expandedModelJson);

const supportRequest: SupportModelRequest = {
  model: ordersModelV2,
};
const supportReport: InterpreterSupportReport =
  service.support(supportRequest);

const orderDocumentInput: PlacementDocumentInputV1 = {
  version: 1,
  groups: [
    {path: "/Order", coordinates: [1]},
  ],
  fields: [
    {
      path: "/Order/DiscountPercent",
      coordinates: [1, 1],
      raw: "12.50",
    },
  ],
};

const validationOptions: ValidationOptions = {
  locale: "en_US",
};
const request: ValidatePlacementsRequest = {
  model: ordersModelV2,
  document: orderDocumentInput,
  options: validationOptions,
};
const report: ValidationReport =
  service.validatePlacements(request);
```

| Variable | Explicit type | Meaning |
|---|---|---|
| `expandedModelJson` | `ExpandedModelJson` | caller-supplied expanded DM-JSON text; strict preparation still validates it |
| `serviceOptions` | `ValidationServiceOptions` | bounded registry, source, runtime, and acquisition-wait limits |
| `service` | `ValidationService` | caller-owned, JVM-thread-safe registry and exclusive-runtime coordinator |
| `ordersModelV2` | `RegisteredModel` | service-issued, runtime-owner-checked token pinning one immutable registered model version |
| `supportRequest` | `SupportModelRequest` | exact model/default-configuration selector for readiness inspection |
| `supportReport` | `InterpreterSupportReport` | detached support coverage checked before accepting requests |
| `orderDocumentInput` | `PlacementDocumentInputV1` | portable raw placement DTO for one order document |
| `validationOptions` | `ValidationOptions` | per-call locale and optional immutable evaluation-time sample |
| `request` | `ValidatePlacementsRequest` | complete model/untrusted-document/options/configuration input for one validation |
| `report` | `ValidationReport` | detached readonly findings and unsupported-program results |

`ordersModelV2` is a `RegisteredModel`: a service-owned reference to one immutable registered model version. The name is application code only; the type carries the meaning. It exposes identity/fingerprint/lifecycle, not model internals or evaluator methods.

`service.models.register` first enforces the individual/aggregate source limits, strictly prepares the expanded model, and computes `ModelFingerprint`. It uses the throwing preparation form, so invalid input produces `ModelPreparationError`. The operation is atomic: invalid input or a capacity failure creates no entry and no pin. Concurrent JVM registrations of the same exact input bytes and preparation ABI are single-flight and return separate `RegisteredModel` pins to the same model-version identity; releasing one pin does not invalidate the others. Different bytes remain different versions even when their JSON is semantically equivalent.

`orderDocumentInput` is a `PlacementDocumentInputV1`, defined in §5.1. It is a portable readonly DTO and may cross a network, queue, JVM thread, or Node Worker boundary. The request member is deliberately typed `unknown`, so the service still performs authoritative shape and model-aware decoding rather than trusting the caller’s compile-time annotation. The DTO is not a `DocumentSnapshot`, contains no cache, and is not bound to a particular interpreter instance.

`maxModels`, `maxInterpreterConfigurations`, all runtime-count limits, and every `DocumentDecodeLimits` member must be positive safe integers; source-byte limits and `runtimeAcquireTimeoutMs` must be non-negative safe integers. `maxInterpreterConfigurations` includes the built-in default. `maxRuntimesPerModelConfiguration` bounds one exact pool key, `maxRuntimesPerModel` bounds all configurations of one model version, and `maxTotalRuntimes` bounds the whole service. A transport adapter still enforces request-byte and JSON-parser-depth limits before constructing the unknown JavaScript value passed to this service.

The missing `configuration` member is intentional. The service uses its built-in default interpreter configuration: built-in predefined types, empty custom registries, BMP fallback charset, and a system clock sampled once for the request. Model-carried time zone, declared/effective base-year semantics, and charset still come from `ordersModelV2`; locale and an explicit `A12EvaluationTime` remain per-call `ValidationOptions`. A caller that uses a fixed or custom clock samples it outside the service and supplies that immutable value, so time never becomes pooled interpreter state.

For each call, the service performs this exact ownership sequence:

1. Resolve and temporarily pin the requested model version and interpreter configuration.
2. Lease one warm `Interpreter` exclusively from the `(modelVersion, interpreterConfiguration)` pool.
3. Decode `request.document` through that lane owner’s authoritative `decodePlacements(..., {limits: serviceOptions.documentDecodeLimits})` into a request-confined `DocumentSnapshot`.
4. Invoke the leased lane’s `validateFull`, copy the result into a detached `ValidationReport`, and discard the request document/caches.
5. Return or retire the interpreter and release the temporary pins.

The service never invokes a model-dependent document codec outside its owner domain and never leaks a confined `DocumentSnapshot` after the call. If lanes have distinct owners, each lane owns its own codec; if a shared-owner strategy is proven, several exclusive interpreters may use the same owner-aware codec under its certified concurrency contract.

### 8.4 Why a registered interpreter configuration exists

Most callers do not need this type. Registration is required only when the application supplies semantic runtime customization that must remain isolated between pooled interpreters: a non-default fallback charset, custom predefined types, custom field types, custom conditions, or tenant-specific callback resources.

`InterpreterConfigurationRegistration` deliberately excludes a clock provider. The service samples its system clock by default, while fixed/custom time is an explicit `A12EvaluationTime` in `ValidationOptions`. This keeps time out of the pool key and gives every operation in one request exactly one caller-visible sample.

The term **profile** is deliberately not used. A “profile” could mean performance tuning or deployment topology. `RegisteredInterpreterConfiguration` says exactly what the object is: a service-registered semantic configuration with identity and lifecycle.

Passing raw configuration values on every request would be unsafe and slow. The pool must never return a runtime created for configuration A to a request for configuration B; structural equality or a caller-provided string is not a sufficient identity for callback factories/resources. Registration establishes the pool key once and gives the service a place to own and clean up every instance-specific binding.

```ts
declare const service: ValidationService;
declare const ordersModelV2: RegisteredModel;
declare const orderDocumentInput: PlacementDocumentInputV1;
declare const fallbackLegalCharset: LegalCharset;
declare const createPredefinedTypes:
  () => PredefinedTypeRegistry;
declare const createCustomFieldTypes:
  () => CustomFieldTypeRegistry;
declare const createCustomConditions:
  () => CustomConditionRegistry;
declare const disposeCustomResources:
  (
    customFieldTypes: CustomFieldTypeRegistry,
    customConditions: CustomConditionRegistry,
  ) => void;

const configurationDefinition:
  InterpreterConfigurationRegistration = {
    fallbackLegalCharset,
    createBindings: (): InterpreterBindings => {
      const predefinedTypes: PredefinedTypeRegistry =
        createPredefinedTypes();
      const customFieldTypes: CustomFieldTypeRegistry =
        createCustomFieldTypes();
      const customConditions: CustomConditionRegistry =
        createCustomConditions();

      const bindings: InterpreterBindings = {
        predefinedTypes,
        customFieldTypes,
        customConditions,
        dispose: (): void =>
          disposeCustomResources(
            customFieldTypes,
            customConditions,
          ),
      };
      return bindings;
    },
  };

const tenantConfiguration:
  RegisteredInterpreterConfiguration =
    service.interpreterConfigurations.register(
      configurationDefinition,
    );

const request: ValidatePlacementsRequest = {
  model: ordersModelV2,
  document: orderDocumentInput,
  configuration: tenantConfiguration,
};
const report: ValidationReport =
  service.validatePlacements(request);
```

`service` has type `ValidationService`, the caller-owned registry/pool coordinator. `ordersModelV2` has type `RegisteredModel`, the service-issued token selecting one immutable model version, and `orderDocumentInput` has type `PlacementDocumentInputV1`, the portable raw document DTO. `fallbackLegalCharset` has opaque type `LegalCharset`. `createPredefinedTypes`, `createCustomFieldTypes`, and `createCustomConditions` are explicitly typed zero-argument factory functions; each invocation returns a registry exclusively owned by one runtime. `disposeCustomResources` is a typed two-argument callback returning `void`.

`configurationDefinition` has type `InterpreterConfigurationRegistration`, the reusable service registration definition. Inside its factory, `predefinedTypes`, `customFieldTypes`, and `customConditions` have their corresponding registry types, while `bindings` has type `InterpreterBindings`, the complete per-runtime callback/resource set. `tenantConfiguration` has type `RegisteredInterpreterConfiguration`, the nominal service-issued pool-key token. `request` has type `ValidatePlacementsRequest`, and `report` has type `ValidationReport`, the detached result.

For one `RegisteredInterpreterConfiguration`, the service never invokes `createBindings` concurrently; each returned `InterpreterBindings` belongs to exactly one active interpreter instance. `dispose` is optional, is called exactly once with exclusive access after that instance retires, and is also called after partial construction/evaluation failure when bindings were created. The registered token is thread-safe even though its produced callbacks need not be.

Binding also prepares customization dispatch. The lane collects every custom field-type and condition name used by the model and binds each name to one indexed dispatch slot. For the native SPI and a compatibility factory whose lifecycle permits reuse, that slot contains the created implementation; an unresolved native name gets one prepared missing sentinel used by `supportReport()` rather than a repeated registry search. If a source factory’s creation cardinality is observable, the slot instead contains a direct constant-name provider that preserves it; the adapter must not silently turn a per-use factory into a singleton or probe it once merely for readiness. Such a capability is reported as `"runtime-resolved"` until normal evaluation invokes the source factory. Custom-field metadata and custom-condition AST nodes point directly at those slots, so a hot evaluation does not consult the service registry, the configuration token, a string-keyed map, reflection, or a coordination lock.

`tenantConfiguration.release()` is idempotent. Outside any active integration callback on that same service, it rejects future calls through that token, retires idle runtimes for the configuration immediately, lets active leases finish, and disposes their bindings on return. A synchronous release of any same-service configuration token from `createBindings`, a custom evaluation callback, or `dispose` is rejected by the `ReentrantServiceLifecycleError` rule in §8.2. `maxInterpreterConfigurations` prevents unbounded configuration metadata and model×configuration pool growth.

### 8.5 Interleaving, concurrency, and warming

The cache and pool key is exactly:

```text
(ModelVersionIdentity, InterpreterConfigurationIdentity)
```

Documents, locale, `A12EvaluationTime`, validation options, results, and tenant request data are never key members and are never cached as model/runtime state.

Returning to D1 after D2 must find D1’s retained runtime lanes; the service must not use one global “last model” slot. A stored program parses at most once per active runtime lane, or once in a certified shared immutable plan. It never reparses per validation call. The default remains lazy. Explicit `warm` prepares a requested bounded number of instances:

```ts
declare const service: ValidationService;
declare const ordersModelV2: RegisteredModel;

const warmRequest: WarmModelRequest = {
  model: ordersModelV2,
  instances: 4,
};
const warmReport: WarmModelReport =
  service.warm(warmRequest);
```

`service` has type `ValidationService`, and `ordersModelV2` has type `RegisteredModel`. `warmRequest` has type `WarmModelRequest`: it selects that registered version and requests four bounded runtime instances under the default configuration. `warmReport` has type `WarmModelReport`, the detached count of instances and stored programs actually prepared.

On the JVM, several calls for the same model/configuration may lease different interpreter instances simultaneously. No instance is entered concurrently, and no global or per-model evaluation lock serializes all work. When the bounded pool is exhausted, a call waits only for `runtimeAcquireTimeoutMs` and then throws `RuntimePoolSaturatedError`; it never creates unbounded instances.

Idle-runtime eviction is deterministic and never retires a leased instance. A retired leased instance finishes normally, is disposed on return, and is not made idle again. Model-source bounds and runtime-count bounds are separate because portable exact heap-size accounting is not credible.

### 8.6 Version switching and lifecycle

Logical aliases are optional. They let a validation service change the model used by future requests without mutating an existing version:

```ts
declare const service: ValidationService;
declare const ordersModelV2: RegisteredModel;
declare const ordersModelV3: RegisteredModel;

const ordersAlias: RegisteredModelAlias =
  service.models.createAlias("orders", ordersModelV2);

service.models.activate(ordersAlias, ordersModelV3);
```

`service` has type `ValidationService`. `ordersModelV2` and `ordersModelV3` have type `RegisteredModel` and identify two immutable prepared versions. `ordersAlias` has type `RegisteredModelAlias`, a nominal logical-name token whose active target the service switches atomically.

`activate` is atomic: requests that already acquired V2 finish on V2, while later acquisitions through `ordersAlias` use V3. A live `RegisteredModel` or alias pins its target. `release()` is idempotent, invalidates that token for future calls, and makes the entry evictable only after aliases and active leases stop pinning it. `retire` prevents new acquisition before those active leases finish.

`drain()` and `close()` follow the complete admission/quiescence state machine in §8.2, including in-flight preparation, warmup, binding creation, publication/rollback, active leases, and disposal—not only queued validation requests. A request that already owns a runtime finishes normally; no queued request may acquire a lease after draining starts. Close invalidates every alias/model/configuration token for future service use, retires and disposes every runtime, releases service-owned pins, and clears the registries before publishing `closed`. Calling `release()` on an already invalidated token remains harmless. Direct `PreparedModel`/`Interpreter` objects outside the service are unaffected.

Every race follows the operation’s linearization order. Work admitted before token release, retirement, or alias activation keeps its pinned version/configuration. If it owns all required runtime resources before drain/close, it finishes normally; if it is still waiting for a lease/capacity, the sole cancellation rule in §8.2 applies and it rolls back with `ValidationServiceDrainingError`. Work ordered after the relevant transition observes the new alias target or the corresponding released, retired, draining, or closed error before evaluation. Eviction never wins against an acquired temporary pin, and disposal can occur only after the last exclusive lease returns.

### 8.7 Node Worker coordinator and local ownership

Node CPU parallelism uses a bounded Worker/process pool. A coordinator owns logical aliases and scheduling; each Worker owns all Kotlin/JS runtime objects and local identities. Worker messages contain only versioned plain DTOs.

```ts
declare const portableModelVersionIdBrand:
  unique symbol;
declare const portableConfigurationIdBrand:
  unique symbol;

type PortableModelVersionId = string & {
  readonly [portableModelVersionIdBrand]: never;
};

type PortableConfigurationId = string & {
  readonly [portableConfigurationIdBrand]: never;
};

interface RegisterModelWorkerMessage {
  readonly kind: "register-model";
  readonly portableModelVersion:
    PortableModelVersionId;
  readonly expectedFingerprint:
    ModelFingerprint;
  readonly modelSource: ExpandedModelJson;
}

interface ModelReadyWorkerMessage {
  readonly kind: "model-ready";
  readonly portableModelVersion:
    PortableModelVersionId;
  readonly computedFingerprint:
    ModelFingerprint;
}

interface ValidateWorkerMessage {
  readonly kind: "validate";
  readonly requestId: string;
  readonly modelVersion:
    PortableModelVersionId;
  readonly configuration:
    PortableConfigurationId;
  readonly document: PlacementDocumentInputV1;
  readonly options: ValidationOptionsWireV1;
}

interface EvaluationTimeWireV1 {
  readonly instant: string;
  readonly timeZone: string;
}

interface ValidationOptionsWireV1 {
  readonly locale?: LocaleCode;
  readonly evaluationTime?: EvaluationTimeWireV1;
}

type ReportCoordinateWireV1 =
  | number
  | {readonly kind: "all"}
  | {readonly kind: "unknown"};

interface ReportPointerWireV1 {
  readonly entityKind: DocumentPlacementKind;
  readonly path: string;
  readonly coordinates:
    readonly ReportCoordinateWireV1[];
}

type InspectionDiagnosticWireV1 =
  | {
      readonly code: "required-value-missing";
      readonly requiredness: Requiredness;
    }
  | {
      readonly code: "invalid-lexical-format";
      readonly expectedFormat: FieldFormat;
      readonly invalidCodeUnitIndex?: number;
    }
  | {
      readonly code: "value-out-of-range";
      readonly minimum?: string;
      readonly maximum?: string;
    }
  | {
      readonly code: "length-out-of-range";
      readonly minimum?: number;
      readonly maximum?: number;
      readonly actual: number;
    }
  | {
      readonly code: "illegal-character";
      readonly codePoint: number;
      readonly codeUnitIndex: number;
    }
  | {
      readonly code: "duplicate-index-value";
      readonly conflictingPointers:
        readonly ReportPointerWireV1[];
    }
  | {
      readonly code: "repetition-limit-exceeded";
      readonly maximum: number;
      readonly actual: number;
    }
  | {
      readonly code: "custom-field-rejected";
      readonly typeName: string;
      readonly errorKey: string;
      readonly message: string;
    };

interface ValidationFindingWireBaseV1 {
  readonly code: string;
  readonly errorPointer: ReportPointerWireV1;
  readonly severity: ValidationSeverity;
  readonly polarity: FindingPolarity;
  readonly message: string;
  readonly referencedPointers:
    readonly ReportPointerWireV1[];
  readonly fillToFixPointers:
    readonly ReportPointerWireV1[];
}

type ValidationFindingWireV1 =
  | ValidationFindingWireBaseV1 & {
      readonly origin: "formal";
      readonly diagnostic:
        InspectionDiagnosticWireV1;
    }
  | ValidationFindingWireBaseV1 & {
      readonly origin: "authored-rule";
      readonly rulePath: string;
    };

type UnsupportedProgramWireV1 =
  | {
      readonly kind:
        | "rule"
        | "computation"
        | "expression"
        | "custom-condition";
      readonly path?: string;
      readonly reasonCode: UnsupportedReasonCode;
      readonly detail: string;
    }
  | {
      readonly kind: "custom-field-type";
      readonly path?: string;
      readonly reasonCode: UnsupportedReasonCode;
      readonly detail: string;
      readonly pointer: ReportPointerWireV1;
      readonly typeName: string;
    };

interface ValidationReportWireV1 {
  readonly version: 1;
  readonly noErrorOccurred: boolean;
  readonly fullySupported: boolean;
  readonly findings:
    readonly ValidationFindingWireV1[];
  readonly unsupported:
    readonly UnsupportedProgramWireV1[];
}

interface ValidationWorkerResult {
  readonly kind: "validation-result";
  readonly requestId: string;
  readonly report: ValidationReportWireV1;
}

interface WarmInterpreterLane {
  readonly owner: ModelOwnerIdentity;
  readonly modelVersion: ModelVersionIdentity;
  readonly configuration:
    InterpreterConfigurationIdentity;
  readonly interpreter: Interpreter;
  readonly documents: PreparedDocumentApi;
}

type WorkerPreparedResource =
  | {
      readonly kind: "shared-owner";
      readonly prepared: PreparedModel;
      readonly owner: ModelOwnerIdentity;
    }
  | {
      readonly kind: "lane-owned";
      createLane(
        configuration:
          InterpreterConfiguration,
      ): WarmInterpreterLane;
    };

interface WorkerModelEntry {
  readonly portableModelVersion:
    PortableModelVersionId;
  readonly fingerprint: ModelFingerprint;
  readonly source: ExpandedModelJson;
  readonly prepared: WorkerPreparedResource;
  readonly lanes:
    ReadonlyMap<
      PortableConfigurationId,
      WarmInterpreterLane
    >;
}

interface WorkerValidationCoordinator {
  validateAlias(
    alias: string,
    configuration:
      PortableConfigurationId,
    document:
      PlacementDocumentInputV1,
    options: ValidationOptionsWireV1,
  ): Promise<ValidationReportWireV1>;
}
```

`ValidationOptionsWireV1`, `ValidationFindingWireV1`, and `UnsupportedProgramWireV1` are versioned plain transport projections of the corresponding canonical option/result records. Exact instants, model time-zone IDs, decimals in diagnostic bounds, and every report-pointer coordinate kind use explicit wire representations. They contain no Kotlin/JS wrapper, function, prototype, local owner, or opaque service token.

The coordinator resolves `alias: string` to one exact `PortableModelVersionId`, temporarily pins that version, and only then creates `ValidateWorkerMessage`. An alias activation after that point cannot change the admitted request. Registration broadcasts `RegisterModelWorkerMessage`; each Worker recomputes `computedFingerprint`, rejects a mismatch, creates its own `ModelVersionIdentity`, `InterpreterConfigurationIdentity`, and one or more `ModelOwnerIdentity` values, and acknowledges readiness before the coordinator activates the alias. None of those local identities is derived from or substituted by the portable routing string.

In one Worker, the default is one `WarmInterpreterLane` per `(PortableModelVersionId, PortableConfigurationId)` because synchronous parsing, decode, and evaluation cannot overlap on that JavaScript thread. A second same-key lane requires a measured reentrancy or ownership reason. D1 and D2 retain separate entries and lanes, so `D1, D2, D1, D2` does not reparse either model.

`WorkerPreparedResource` preserves both viable owner strategies. A `"shared-owner"` entry may give all local lanes the same `prepared.owner` only when the prepared plan, lazy caches, and document codec are certified for that use. A `"lane-owned"` entry constructs an independent owner and codec for every lane. In both cases, `lane.documents.isSnapshot(value: unknown)` authenticates only snapshots in that lane’s owner domain. Decode and optional model-bound adapter lookup happen after lane selection; a codec cached for one lane must never create a snapshot consumed by another owner.

Workers exchange only portable model source/fingerprints, routing IDs, placement DTOs, per-call locale/time options, and encoded result DTOs. They never exchange `PreparedModel`, `Interpreter`, `DocumentSnapshot`, model-owned pointers, scalar wrapper objects, callbacks, registries, schema objects, or local identities.

Each Worker receives the structured-cloned message as `unknown`, validates the versioned message envelope, then passes its `document` through the selected lane’s authoritative `decodePlacements`. A TypeScript `ValidateWorkerMessage` annotation on the coordinator side is not a runtime trust boundary.

### 8.8 Explicitly typed JVM usage

The Java façade projects the same request types. `RegisteredModel`, `RegisteredModelAlias`, and `RegisteredInterpreterConfiguration` are thread-safe `AutoCloseable` tokens; the service keeps its leased `Interpreter` and request-confined `DocumentSnapshot` internal:

```java
static ValidationReport validateOne(
        ValidationService service,
        RegisteredModel ordersModelV2,
        PlacementDocumentInputV1 documentInput,
        ValidationOptions options) {
    ValidatePlacementsRequest request =
            ValidatePlacementsRequest.builder()
                    .model(ordersModelV2)
                    .document(documentInput)
                    .options(options)
                    .build();

    return service.validatePlacements(request);
}
```

`service` has Java type `ValidationService`, the caller-owned thread-safe coordinator. `ordersModelV2` has type `RegisteredModel`, the service-issued token for one immutable model version. `documentInput` has type `PlacementDocumentInputV1`, the portable raw-placement DTO. `options` has type `ValidationOptions`, the immutable per-call locale/time options. `request` has type `ValidatePlacementsRequest`, the complete typed input built from those values, and `validateOne` explicitly returns `ValidationReport`, the detached validation result. `ValidatePlacementsRequest.Builder` is the Java projection of the TypeScript readonly request object; its optional `configuration(RegisteredInterpreterConfiguration)` method selects a custom registration, while omitting it uses the service default. The method is synchronous. Multiple JVM threads may call it concurrently, but each request receives a different exclusive interpreter lease.

The service exposes typed `ValidationServiceMetrics` for lifecycle state, admitted/disposal work, model/configuration counts, pool saturation, wait/rejection, active/idle runtimes, preparation, and eviction. Direct callers remain free to retain their own active instances without any registry or pool.

## 9. Configuration, errors, and concurrency

The stable configuration must represent every evaluator customization the kernel honors:

- model-carried time zone, declared/effective base-year semantics, and supported characters through `PreparedModel`;
- fallback legal charset;
- predefined and custom field types;
- custom conditions;
- clock provider on a direct `Interpreter`, or explicit `A12EvaluationTime` per service call;
- locale per call.

Callbacks are synchronous because the evaluator is synchronous. Static callback types exclude `Promise`; a runtime thenable from plain JavaScript or unsafe TypeScript is rejected when observed, never awaited, and treated as an integration failure. In the recommended JVM pool, callbacks belong to one thread-confined interpreter instance and need not support concurrent invocation. A future configuration for a reentrant interpreter may require explicitly share-safe callbacks. Callback lifetime and exception conversion are part of the façade contract.

The kernel-free registries are created per interpreter lane through `createBindings`; they are not process-global. An optional compatibility adapter may share an existing factory only when that platform contract requires the factory itself to be thread-safe, but its dispatch wrapper and native call context remain bound to an exclusive lane. Callback instances returned by the source factory follow that platform’s exact creation cardinality rather than being silently shared. Registry lookup and adapter discovery occur outside every evaluator hot loop.

There is deliberately no universal “validation error” or `success: boolean`. The public algebra keeps these families distinct:

- model/program preparation diagnostics;
- transport/model-aware document-input diagnostics;
- direct storage state;
- automatic-formal inspection diagnostics;
- authored/formal validation findings;
- unsupported-capability records and startup support coverage;
- legitimate expression non-evaluation and formal suppression;
- complete computation outcomes/deltas;
- callback/integration failures;
- ownership and service-lifecycle programmer errors.

They share structured-code, path, privacy, and formatting conventions where useful, but never erase their different recovery behavior. Developers use discriminants and structured detail; localized messages are not a parsing API.

Public results and failures use arrays, readonly objects, discriminated unions, stable codes, and A12 values. They distinguish:

| Situation | Public treatment |
|---|---|
| invalid prepared input | `ModelPreparationError`, or failed `ModelPreparationResult` from the non-throwing form |
| invalid document structure | `DocumentInputError` with all diagnostics, each carrying `inputPath` and an optional separate `modelPath` |
| invalid pointer coordinate count/value | `DocumentCoordinateError` before mutation |
| row enumeration scoped by a non-ancestor | `DocumentRowScopeError` |
| invalid day-optional date component | Operator API `A12ConversionError`; no value is created |
| non-fragment field, mismatched fragment shape, or invalid fragment component | Operator API `A12ConversionError` from `dateFragment`, or a failed `A12Result` from `tryDateFragment`; no value is created |
| typed value cannot be represented exactly by its target field | `DocumentValueWriteError`; no cell is changed |
| unknown model path | `UnknownModelPathError` |
| malformed field text | retained raw state plus validation finding/suppression |
| unsupported legal construct/configuration | `InterpreterSupportReport` before serving and an explicit unsupported result for the requested evaluation |
| owner mismatch | `ModelOwnerMismatchError` |
| native compatibility binding/document model-ID mismatch | platform projection of `KernelCompatibilityInputError` before conversion or evaluation |
| invalid candidate | `ProgramPreparationError` |
| released service token | `ReleasedServiceHandleError` before evaluation |
| explicitly retired model or alias | `RetiredServiceHandleError` before evaluation |
| token issued by another service | `ForeignServiceHandleError` before evaluation |
| operation on a closed service | `ValidationServiceClosedError` |
| work is attempted after draining, or admitted validation/warmup is still queued for runtime resources | `ValidationServiceDrainingError`; a new attempt is not admitted, queued validation performs no evaluation, and queued warmup rolls back request-owned partial creation/publication |
| model/configuration/source bound exceeded | `ValidationServiceCapacityError`; no partial registration |
| no runtime before `runtimeAcquireTimeoutMs` | `RuntimePoolSaturatedError`; no evaluation and no unbounded runtime creation |
| same-service drain/close/configuration release invoked synchronously from an integration callback | `ReentrantServiceLifecycleError` before lifecycle/token mutation or waiting |
| direct interpreter binding/callback failure | core `InterpreterIntegrationError` with `"bind"` or `"evaluate"` phase |
| service-owned binding/evaluation/cleanup failure | `ValidationServiceIntegrationError` plus deterministic runtime retirement |
| internal invariant failure | exception; never relabeled unsupported |

The required JVM guarantee is safe concurrent service use through separate active interpreter instances. The initial interpreter contract may be thread-confined. Immutable snapshots can be safely published when their implementation and caches satisfy that contract, but a service may also keep each request’s snapshot confined.

Clock samples, locale, validation passes, formal-error caches, computation overlays, document views, and results are call/instance-confined. No process-global locale, clock, callback map, current model, or current document exists. The registry and pool are the thread-safe coordination layer. This requires real verification because the current public `Document` is mutable and cache-bearing.

## 10. Kotlin ownership and platform projections

All model loading policy, parsing, paths, repetition semantics, formal-state behavior, customization invocation, computation orchestration, and result meaning live once in Kotlin `commonMain`.

```text
                kernel-free Kotlin commonMain
       model + document + parser + interpreter semantics
                              │
                 ┌────────────┴────────────┐
                 ▼                         ▼
       TypeScript façade            JVM/Java façade
          PRIMARY                     SECONDARY
                 ▲                         ▲
                 │ optional                │ optional
      TypeScript kernel-compat       JVM kernel-compat
             adapter                    adapter
                 │                         │
       existing TS callbacks       existing JVM callbacks
```

The arrows point from an optional adapter to the base artifact it consumes. The base Interpreter and Operator artifacts never depend back on either adapter. The TypeScript façade is required product work: explicit exports, plain declarations, JS-safe errors, arrays/readonly DTOs, and strict TypeScript consumer tests. The ordinary JVM façade adapts Java collections and uses builders where justified. Neither façade contains evaluator logic or imports a kernel type.

Optional TypeScript boundary adapters, including the separate [Zod integration proposal](ZOD-INTEGRATION-PROPOSAL.md), may validate portable DTO shapes and project canonical reports. They must delegate model-aware decoding to `decodePlacements` or `tryDecodePlacements`, authenticate snapshots with the exact owner’s `isSnapshot(value: unknown)`, encode through `encodePlacements`, and preserve `ReportPointer`. They do not belong in `commonMain`, customization SPIs, interpreter parsing, AST traversal, warm lanes, or JVM APIs and must never become a second validation engine.

Node is the verified JavaScript host. Browser qualification requires a real browser build, bundle smoke test, conformance run, callback contract, and browser performance evidence. A fake async wrapper that still blocks the Node event loop is not useful; worker-thread/browser-worker adapters may be separate later.

### 10.1 Optional kernel-compatibility artifacts

Two platform-specific artifacts bridge existing project implementations without contaminating the core:

| Proposed artifact | Accepts | Depends on |
|---|---|---|
| `@a12/interpreter-kernel-compat` | the existing TypeScript kernel document, generated-runtime configuration, model ID, entity-value mapping, custom-field factory, and custom-condition factory | kernel-free TypeScript Interpreter façade and the certified TypeScript kernel API line |
| `a12-interpreter-kernel-compat-jvm` | `DocumentV2`, `DocumentProcessingConfig`, modern JVM custom-field and custom-condition factories, plus explicitly supported legacy variants | kernel-free JVM Interpreter façade and the certified A12 Kernel JVM API line |

TypeScript remains the primary product and compatibility surface; the JVM adapter is the secondary route for projects that already own Java implementations. The artifacts are separate because the JVM and TypeScript kernel interfaces differ observably. They do not pretend that `DocumentV2`, nullable Java sets, `PartiallyKnownDocumentMultiPointer`, the TypeScript document/model-ID/value-mapping callback, factory absence conventions, validation parameters, or localized custom-field messages are one structural API. Each adapter pins and certifies a specific kernel API line, initially 30.8.1 where applicable. The base artifacts remain MIT-licensed and kernel-free; a compatibility artifact’s license and distribution obligations follow its kernel linkage.

A deployment that must remain entirely kernel-free implements or recompiles its project customizations against the native KMP/TypeScript/JVM projection of the core SPI. Reusing an already compiled implementation whose public types are kernel interfaces necessarily selects the separately packaged compatibility artifact; packaging cannot erase that dependency by hiding it behind reflection or copying the interface definitions.

| Existing capability | Kernel-free core | Compatibility projection |
|---|---|---|
| full validation | `Interpreter.validateFull(DocumentSnapshot, ValidationOptions)` | `validateFull` over the native kernel document/config |
| partial validation | `Interpreter.validatePart(DocumentSnapshot, RelevantEntityPointer[], ValidationOptions)` | `validatePart` over native pointer collections |
| computations | `Interpreter.compute(DocumentSnapshot, ComputeOptions)` | `compute` over the native kernel document/config |
| apply computation results | `computeAndApply` returns a new snapshot from one evaluation | a source-bound adapter computation result exposes kernel-style `appliedTo` without reevaluation |
| custom field types | `CustomFieldTypeRegistry` / `CustomFieldValidator` | existing platform factory and validator interfaces |
| custom conditions | `CustomConditionRegistry` / `CustomCondition` | existing platform factory and condition interfaces with the original native document |

The host-neutral custom-field result keeps an `errorKey` and rendered `message`. The JVM adapter carries the existing string directly. The TypeScript callback instead returns `Localizable[]`, so the adapter configuration supplies an explicit renderer and the validation call supplies the requested locale before a canonical `ValidationReport` can be produced. That rendering is a deliberate projection, not a claim that the original localization objects survive in `message: string`. A future exact TypeScript `DocumentValidationResult` projection must retain the original `Localizable[]` in an adapter-owned sidecar during the same evaluation; it cannot reconstruct them from the detached canonical report.

The primary TypeScript compatibility contract retains the established verbs and parameters:

```ts
type KernelDocument = Document;
type KernelEntityInstancePath = EntityInstancePath;

interface KernelCustomFieldMessageRenderer {
  render(
    errorText: readonly Localizable[],
    locale: LocaleCode,
  ): string;
}

interface KernelCompatibilityConfiguration {
  readonly documentModelId: string;
  readonly generatedRuntime: GeneratedCodeRtConfig;
  readonly extensionResolution: "explicit-only";
  readonly customFieldMessageRenderer:
    KernelCustomFieldMessageRenderer;
}

interface KernelCompatibilityInputError
  extends Error {
  readonly code:
    "A12_KERNEL_COMPATIBILITY_INPUT_FAILED";
  readonly reason: "model-id-mismatch";
  readonly expectedDocumentModelId: string;
  readonly actualDocumentModelId?: string;
}

declare const kernelCompatibleComputationResultBrand:
  unique symbol;

type KernelCompatibleComputationApplication =
  | {
      readonly kind: "applied";
      readonly document: KernelDocument;
    }
  | {
      readonly kind: "notApplied";
      readonly reason: "unsupported";
      readonly document: KernelDocument;
    };

interface KernelCompatibleComputationResult {
  readonly [kernelCompatibleComputationResultBrand]:
    never;
  readonly prepared: PreparedModel;
  readonly report: ComputationReport;
  readonly document: KernelDocument;
  appliedTo(
    document: KernelDocument,
  ): KernelCompatibleComputationApplication;
}

interface KernelCompatibleInterpreter {
  readonly prepared: PreparedModel;
  supportReport(): InterpreterSupportReport;
  convertCustomFieldDisplayToInternal(
    pointer: CustomFieldPointer,
    displayValue: string,
  ): CustomFieldConversionResult;
  convertCustomFieldInternalToDisplay(
    pointer: CustomFieldPointer,
    internalValue: string,
  ): CustomFieldConversionResult;
  validateFull(
    document: KernelDocument,
    valueOverrides?: EntityInstanceValueMapping,
    options?: ValidationOptions,
  ): ValidationReport;
  validatePart(
    document: KernelDocument,
    relevantEntities:
      readonly KernelEntityInstancePath[],
    valueOverrides?: EntityInstanceValueMapping,
    options?: ValidationOptions,
  ): ValidationReport;
  compute(
    document: KernelDocument,
    options?: ComputeOptions,
  ): KernelCompatibleComputationResult;
  dispose(): void;
}

declare function createKernelCompatibleInterpreter(
  prepared: PreparedModel,
  configuration:
    KernelCompatibilityConfiguration,
): KernelCompatibleInterpreter;

declare const prepared: PreparedModel;
declare const generatedDocumentModelId: string;
declare const generatedRuntime:
  GeneratedCodeRtConfig;
declare const customFieldMessageRenderer:
  KernelCustomFieldMessageRenderer;
declare const document: KernelDocument;
declare const valueOverrides:
  EntityInstanceValueMapping | undefined;

const compatibilityConfiguration:
  KernelCompatibilityConfiguration = {
    documentModelId: generatedDocumentModelId,
    generatedRuntime,
    extensionResolution: "explicit-only",
    customFieldMessageRenderer,
  };
const compatible: KernelCompatibleInterpreter =
  createKernelCompatibleInterpreter(
    prepared,
    compatibilityConfiguration,
  );
const validationOptions: ValidationOptions = {
  locale: "en_US",
};
const compatibilityComputeOptions: ComputeOptions = {
  locale: "en_US",
};
const report: ValidationReport =
  compatible.validateFull(
    document,
    valueOverrides,
    validationOptions,
  );
const computation:
  KernelCompatibleComputationResult =
    compatible.compute(
      document,
      compatibilityComputeOptions,
    );
const computationApplication:
  KernelCompatibleComputationApplication =
    computation.appliedTo(document);
if (computationApplication.kind === "applied") {
  const updatedDocument: KernelDocument =
    computationApplication.document;
}
```

`KernelDocument` and `KernelEntityInstancePath` are adapter-imported aliases for the existing TypeScript kernel `Document` and `EntityInstancePath` types; the adapter does not reproduce their declarations. `Localizable` is the adapter-imported existing kernel localization-item type. `prepared` has type `PreparedModel`. `generatedDocumentModelId` is the exact `string` exported or otherwise authoritatively supplied by the generated model artifact, not a service alias or a value copied from `prepared` merely to satisfy the check. `generatedRuntime` has the existing `GeneratedCodeRtConfig` type and carries the custom factories, `currentDateForTest`, and `ignoreUnknownFields`. `customFieldMessageRenderer` has type `KernelCustomFieldMessageRenderer` and renders existing `Localizable[]` values for the requested locale. `compatibilityConfiguration` has type `KernelCompatibilityConfiguration`; its required `"explicit-only"` mode means an absent factory is absent and never triggers deprecated process-global registration. `document` is the original kernel document. `valueOverrides` has the existing `EntityInstanceValueMapping | undefined` type. `validationOptions` has type `ValidationOptions`; `compatibilityComputeOptions` has type `ComputeOptions` and supplies the computation diagnostic locale. `compatible` has the fully declared adapter type `KernelCompatibleInterpreter`, `report` has canonical type `ValidationReport`, `computation` has the opaque source-bound adapter result type, and `computationApplication` has the applied/not-applied adapter result type. Only its `"applied"` branch exposes `updatedDocument` with type `KernelDocument`.

Construction compares `compatibilityConfiguration.documentModelId` with `prepared.model.documentModelId` and throws `KernelCompatibilityInputError` before creating a lane when they differ. The generated TypeScript `Document` does not itself carry an authoritative model/version token, so a model-bound adapter handle plus strict structural decoding is the strongest available runtime check; callers and multi-model registries remain responsible for routing the document through the handle created from its exact generated model version. Where provenance must cross an untrusted boundary, the portable owner/version token from §8 must accompany the document instead of pretending the native object authenticates itself.

`supportReport` exposes the bound lane’s native and compatibility readiness; source factory-backed names remain `"runtime-resolved"` when preserving factory cardinality prevents an eager probe. The conversion methods invoke the existing custom-field conversion implementation through the same pre-bound slot. `validatePart` maps the native `EntityInstancePath` array to exact core relevance pointers before kernel-style normalization. The optional final `ValidationOptions` parameter is additive: omitting it retains the familiar native call shape and uses `"en_US"` for the canonical rendered report, while callers that need another canonical message locale state it explicitly. `ComputeOptions.locale` supplies the same required locale when formal/custom-field diagnostics arise during computation and likewise defaults to `"en_US"`. `compute` returns an authenticated `KernelCompatibleComputationResult`, not a bare detached report. Its `document` is the original source document and `report` is the canonical detached computation data. `appliedTo` never reevaluates and returns `"notApplied"` with the supplied document unchanged when `report.fullySupported` is false. Otherwise it copies the supplied native document, preserves unknown/native content, and returns `"applied"` after applying the exact source computation collections encoded in the handle—successful values classified as changed, errored values, and cleared values—rather than blindly replaying the original snapshot’s `delta`. `dispose` retires the exclusive adapter lane and is idempotent; a Worker-local cache or service pool owns it while retained. `GeneratedCodeRtConfig.currentDateForTest` maps to the call’s exact evaluation date/time law unless an explicit `ComputeOptions` or `ValidationOptions` evaluation time is supplied. `ignoreUnknownFields` controls native-document conversion exactly as in the source façade; unknown groups remain ignored, and override entries are not discarded merely because that flag is true.

The secondary JVM artifact exposes the same capability set over JVM kernel types:

```java
public enum KernelComputationApplicationFailure {
    UNSUPPORTED
}

public sealed interface
        KernelCompatibleComputationApplication
        permits AppliedKernelComputation,
                NotAppliedKernelComputation {
}

public record AppliedKernelComputation(
        DocumentV2 document)
        implements KernelCompatibleComputationApplication {
}

public record NotAppliedKernelComputation(
        KernelComputationApplicationFailure reason,
        DocumentV2 document)
        implements KernelCompatibleComputationApplication {
}

public interface KernelCompatibleComputationResult {
    PreparedModel prepared();

    ComputationReport report();

    DocumentV2 document();

    KernelCompatibleComputationApplication appliedTo(
            DocumentV2 document);
}

public interface KernelCompatibleInterpreter
        extends AutoCloseable {
    InterpreterSupportReport supportReport();

    CustomFieldConversionResult
            convertCustomFieldDisplayToInternal(
                    CustomFieldPointer pointer,
                    String displayValue);

    CustomFieldConversionResult
            convertCustomFieldInternalToDisplay(
                    CustomFieldPointer pointer,
                    String internalValue);

    ValidationReport validateFull(DocumentV2 document);

    ValidationReport validatePart(
            DocumentV2 document,
            Set<? extends DocumentMultiPointer> relevantEntities);

    KernelCompatibleComputationResult compute(
            DocumentV2 document);

    @Override
    void close();
}

public final class KernelInterpreterAdapters {
    public static KernelCompatibleInterpreter bind(
            PreparedModel preparedModel,
            DocumentProcessingConfig documentProcessingConfig);
}

static ValidationReport validateExistingDocument(
        PreparedModel preparedModel,
        DocumentProcessingConfig documentProcessingConfig,
        DocumentV2 document) {
    try (KernelCompatibleInterpreter compatibleInterpreter =
                 KernelInterpreterAdapters.bind(
                         preparedModel,
                         documentProcessingConfig)) {
        return compatibleInterpreter.validateFull(document);
    }
}
```

`preparedModel` has Java type `PreparedModel`, the kernel-free prepared model. `documentProcessingConfig` has the existing kernel type `DocumentProcessingConfig` and carries locale, current test date, custom factories, presentation information, and any explicitly supported legacy data. `document` has existing type `DocumentV2`. `compatibleInterpreter` has adapter type `KernelCompatibleInterpreter`; it owns one exclusive lane until `close`, while a service may retain/pool that lane instead. `relevantEntities` has type `Set<? extends DocumentMultiPointer>`. Validation returns the canonical kernel-free `ValidationReport`; computation returns a source-bound `KernelCompatibleComputationResult` whose `report()` is canonical and whose `appliedTo` returns the sealed `KernelCompatibleComputationApplication` without reevaluation. `AppliedKernelComputation.document()` is the updated document; `NotAppliedKernelComputation` carries enum reason `UNSUPPORTED` and the supplied document unchanged. The adapter may offer JVM-native result conveniences only where every native member can be derived losslessly; the TypeScript localization restriction does not disappear by analogy.

The shown JVM convenience binds one exact `DocumentProcessingConfig`. `bind` checks `DocumentV2.getDocumentModelId()` against `preparedModel.model().documentModelId()` before every document operation and throws the JVM projection of `KernelCompatibilityInputError` on a mismatch. A native model ID does not prove an exact model version when the source format has no version token, so the registry/binding that selected `preparedModel` remains responsible for exact-version routing. The multi-threaded service form accepts that existing config on each `validateFull`, `validatePart`, or `compute` call, treats locale/current-date inputs as call data, and selects a warm lane by the identity of the semantic factory bindings. It must not build a new interpreter for each locale or use structural equality/caller strings as callback-resource identity. Missing modern factories mean no explicit implementation; deprecated static-global fallback is not consulted by this initial entry point and, if certified later, receives a separately named legacy mode.

For each adapter operation, one exclusive lane performs this sequence:

1. Verify the native/bound model identity as far as that platform can authenticate it, then convert the native kernel document into one `DocumentSnapshot` once.
2. Convert TypeScript validation mappings and any native presentation information once into call-scoped immutable overlays. A value override wins over the document value—including malformed text and explicit `""`—and every formal check, field read, rule/operator input, message interpolation, and custom-condition document view reads that overlay. The presentation overlay supplies the source-exact `isDisplayValue` decision for each custom field.
3. Retain the original native document, exact `prepared.model.documentModelId`, original value mapping, and source presentation state in lane-local call context.
4. Invoke the Kotlin interpreter, whose pre-bound custom slots call adapter wrappers directly.
5. Let those wrappers call existing project implementations with the unchanged original native document/mapping and exact mapped relevance, formal-error, partially known error-pointer, locale, length, display-value, and conversion inputs.
6. Clear every overlay and native call-context value in `finally`, detach the canonical report or source-bound computation result, and release or retain the lane according to its owner.

The overlay is an evaluation view, not a rewritten transport document or persistent snapshot. The portable document contract stores custom values in internal form, so native core validation passes `isDisplayValue: false`; callers with display-form text use `convertCustomFieldDisplayToInternal` before writing. Compatibility adapters may pass `true` only when audited native presentation information requires it. Existing TypeScript conditions still receive the original `Document` and `EntityInstanceValueMapping` because their interface explicitly requires implementations to interpret that mapping. The adapter never reconstructs a kernel document for each callback, never calls kernel validation or computation, never stores native call state in a process-global registry or `ThreadLocal`, and never sends a callback/runtime object across a Node Worker boundary. Scalar custom-field validators can be wrapped directly. Custom conditions require the retained original document; a factory-only wrapper without a kernel-shaped validation entry point is insufficient.

An adapter computation result is detached from the active lane but remains runtime-authenticated to its prepared owner and retains the original native document plus the exact application classification needed by the source API. When `report.fullySupported` is false, `appliedTo` returns the not-applied variant with reason `"unsupported"`/`UNSUPPORTED`, performs no mutation, and preserves the supplied native document. Otherwise it copies that document, preserves unknown/native content, applies successful values only when the source result classified them as changed, and clears every source-classified errored or cleared field according to native placement rules. It does not infer application from `ComputationReport.delta`, because that delta legitimately omits already-absent clears and cannot reproduce application to a different supplied document by itself.

Modern factory variants are the primary compatibility target. Still-used deprecated variants are not copied into the core SPI; each is source-/binary-audited against SME and at least one additional production project, then either adapted in the compatibility artifact or reported as an explicit unsupported compatibility case. A JVM legacy mode may retain `DocumentProcessingConfig.additionalInformation` in adapter call context and invoke the corresponding deprecated validator overload, but that untyped map never enters `commonMain`. Deprecated static registrations may be snapshotted into an explicit lane registry only when their lifecycle and multi-tenant isolation are proven; the adapter must not make the core consult a process-global service. Static generated-runtime Custom Conditions over `IData`/`IIdentifier` require their own audited adapter variant or remain explicitly unsupported; success with the document-model callback proves nothing about that surface. “A similar native callback exists” is not evidence of drop-in compatibility. Existing project implementations must run unchanged through the claimed adapter path, with differential tests covering both evaluation strategies where applicable.

The compatibility layer adapts existing callback implementations, native documents, and the result shapes explicitly declared above. It does not make the core depend on the kernel, reconstruct TypeScript localization objects from strings, or promise that an application can replace an entire kernel service without changing assembly/configuration code.

## 11. Correctness and performance

The interpreter remains a clean-room, kernel-free implementation and must match the applicable kernel behavior exactly. The stable façade delegates to the existing evaluator; it does not create a parallel execution path.

API completeness is guarded by source-backed capability ledgers, not by a short hand-authored exception list. The audited universes include the model/meta-model schema, document API, `DocumentProcessingConfig`, legal-charset/type/condition SPIs, validation result interfaces, computation result interfaces, and the live operator catalog for contextual operator use. Each family records its kernel version, source universe, evidence, public API disposition, exact gap when parity-gated, and kernel-upgrade trigger. A family may claim completeness only when that universe and its guard are explicit.

Stored-program performance keeps the current invariants:

- stored programs parse at most once per active runtime, or once per certified shared immutable plan, and remain lazy by default;
- repeated evaluation never reparses;
- the optimized AST and traversal remain unchanged;
- custom field-type declarations and custom-condition AST nodes hold pre-bound indexed dispatch slots rather than searching registries during evaluation;
- the empty-customization path has no adapter branch, registry scan, wrapper allocation, pointer mapping, or kernel class loading;
- model-static indexes/scopes are shared or copied only where semantics and measured cost permit;
- unknown-transport validation, model-aware document decode, registry wait, evaluation, encoding, and optional projection cost are measured separately;
- no process-global initialization or validation-result cache is introduced.

Model reuse needs separate sequential and concurrent gates:

1. Retain warm active instances for D1 and D2, alternate `D1-A, D2-A, D1-A, D2-A`, and prove both remain warm with isolated-baseline-equivalent throughput and p95.
2. Retain D1 instances with different clocks, custom registries, and fallback charsets; prove correct different results with no configuration contamination.
3. Drive multiple concurrent requests for the same hot model through several pooled instances, plus D1/D2 interleaving, and prove scaling without concurrent entry into a confined instance.
4. Drive the same D1/D2 pattern through a bounded Node Worker pool and prove that every Worker retains one warm lane per hot local key, resolves portable versions to local identities correctly, and never transports runtime objects across Workers.

Customization and compatibility add separate release gates rather than hiding their cost inside one aggregate benchmark:

1. Compare a no-customization model with the current interpreter baseline and require no measurable regression in parser cost, AST traversal, startup, warmup, steady-state latency, throughput, allocation, or retained memory.
2. Measure native custom field validators and native custom conditions independently on callback-heavy documents, including full validation, partial validation, and computation paths.
3. Measure the TypeScript and JVM compatibility adapters separately from native callbacks. Report native-document conversion, pointer-map construction, callback time, evaluator time, and report projection as distinct measurements.
4. Prove each native document is converted at most once per public operation, core registry lookup and adapter discovery occur only while binding, and the callback hot path contains only the source-required factory invocation at its audited cardinality plus direct precomputed pointer/slot access. No registry search, reflection, coordination lock, or adapter/configuration allocation is permitted per field cell or AST visit.
5. Repeat cold/warm D1/D2 interleaving and concurrent JVM-lane/Node-Worker tests with distinct custom configurations, proving no callback, original native document, value override, locale, or pointer map crosses a lane or request.

Absence of a compatibility artifact has a strict zero-cost contract: no adapter code, branch, class loading, dependency, startup, or retained state exists in the base artifacts, and the no-customization benchmark above must remain statistically indistinguishable from its pinned pre-change baseline. When an adapter is selected, one `O(document)` native-to-snapshot conversion per public operation is unavoidable and is not marketed as zero overhead. The initial release budget compares the complete warm adapter call with the certified kernel 30.8.1 call over the same native document, model, custom implementations, result demand, process, and concurrency: the confidence interval for p50/p95 latency must not be worse, throughput must not be lower, and allocated bytes per operation must not be higher. The benchmark also publishes the delta against validation of an already-decoded native-core snapshot so the conversion cost cannot be hidden. A platform that misses any end-to-end budget does not ship as performance-compatible unless an explicit product decision revises that budget with measured evidence.

The multi-threaded gate races registry lookup, pool acquisition/release, warming, and eviction. It proves deterministic results, no double lease, no use-after-eviction, and no cross-model/configuration document, result, clock, callback, formal-check, or computation cache contamination.

Throughput must scale across representative JVM worker and per-model-instance counts without a registry, pool, or model-wide lock becoming the bottleneck. Single-thread latency remains a separate gate so concurrency machinery cannot degrade the ordinary path.

The benchmark compares the viable strategies—shared immutable plan plus confined instances, fully independent per-worker instances, and a reentrant interpreter only if implemented—on cold/warm latency, aggregate warmup, throughput, allocation, and retained memory. The public contract chooses the least complex strategy that meets the gates; it does not require sharing for its own sake.

The interactive path measures direct storage-read latency separately from formal inspection and validation, plus update-to-result p95 and allocation. Editing one field must scale with the changed path through structural sharing, not copy the whole document, and must invalidate every affected document-local cache correctly.

If introducing the prepared boundary or façade worsens parser cost, AST traversal, startup, warmup, throughput, scaling, allocation, or relative footprint, that part does not ship. Direct operators remain independently feasible.

Known decimal, regex, time-zone, preparation, and host gaps in [INTERPRETER-GAPS](INTERPRETER-GAPS.md), plus the new request-document/cache-confinement contract proposed here, constrain claims and must be closed or rejected explicitly.

## 12. Recommended product surface

### 12.1 Minimum transport and ownership foundation

The first implementable Interpreter increment should be a dependency-free transport and ownership foundation: `PlacementDocumentInputV1`, `DocumentDecodeLimits`, structured `DocumentInputDiagnostic` and `DocumentInputError` values, `PreparedModel` and `ModelOwnerIdentity`, `PreparedDocumentApi.tryDecodePlacements`, the typed `readPlacements` fast path, `encodePlacements`, `isSnapshot(value: unknown)`, and the resulting model-owned `DocumentSnapshot`.

This foundation is independently useful for HTTP, queue, persistence, plugin, and Worker boundaries. It establishes one authoritative A12-native document contract before optional framework adapters appear. It does not depend on the validation-service registry, runtime pooling, persistent editing, candidate programs, Zod, or browser support.

Once this foundation is stable, it is sufficient for the strict document-ingress and owner-bound snapshot portions of the optional [Zod integration proposal](ZOD-INTEGRATION-PROPOSAL.md). Validation-report projection still waits for the canonical `ValidationReport` contract.

The next compatibility-bearing increment is `validateFull`/`validatePart`/`compute` plus the native Custom Field Type and Custom Condition SPIs, exact callback laws, and pre-bound lane dispatch. The TypeScript kernel-compatibility adapter follows once that contract and the native document/report boundaries are stable; the secondary JVM adapter follows the same core contract. Zod scalar integration may proceed independently after the Operator API, and Zod transport work needs only the minimum transport/ownership foundation—neither Zod package waits for kernel-interface compatibility.

### 12.2 Complete product boundary

| Core contract | Optional after the core | Deliberately outside/deferred |
|---|---|---|
| strict expanded-model preparation | bounded model registry/runtime pool | process-global/unbounded cache |
| prepared owner versus exclusive active interpreter split | explicit eager pool warmup | mandatory shared/reentrant evaluator |
| read-only model introspection and caller-owned owner-aware application metadata | nested document-JSON adapter | dynamic model/rule authoring |
| strict `unknown` decode, typed fast path, authenticity guard, and versioned lossless encode | document patch conveniences | common `DocumentV2` dependency |
| frozen request document/field-aware `DocumentCellValue` builder | persistent structural-sharing form editor | promise-returning callbacks |
| direct callback-free storage access plus separate correlated formal inspection | candidate-rule preview | candidate computations |
| kernel-free custom field-type/condition SPIs, immutable registries, and pre-bound lane slots | separately installed TypeScript and JVM kernel-compatibility adapters | kernel types or deprecated global registries in the core |
| full/relevant-set validation, lossless report axes/pointers, and configuration support report | number expression observer | arbitrary context-free expression claim |
| stored-rule row outcomes | tracing/explanation if demanded | kernel consistency certification in TypeScript |
| lossless compute, compute-field, compute-and-apply outcomes and deltas | browser after qualification | browser support by assumption |

This product is feasible, but it is not a rename exercise. The evaluator-backed validation/computation core is medium productization work; the definition/active-instance split and bounded registry/pool require explicit ownership but do not require one shared reentrant evaluator; a performant immutable editable document surface is a distinct substantial capability.

The customization architecture is also feasible. Native Custom Field Types are comparatively direct because their validation callback is scalar, although display/internal conversion, presentation state, and TypeScript localization must remain explicit. Custom Condition reuse is moderate-complexity work because it requires lossless native-document retention, relevance/formal-error/error-pointer translation, TypeScript value-override handling, model-bound routing, and lifecycle-safe call context. Static `IData`/`IIdentifier` conditions and deprecated global/legacy variants are separate compatibility increments, not free consequences of the modern document adapter. None of this requires a kernel dependency or adapter branch in the core no-customization path. The main feasibility gates are unchanged real-project implementations, exact platform differential tests, and the end-to-end adapter performance budget; one native-document conversion per operation is unavoidable, so feasibility is high but conditional rather than a promise of literal zero adapter overhead.

Before publication, the specified contracts need implementation and evidence: strict `unknown` ingress and resource limits, lossless placement round trips, owner-aware authenticity, direct callback-free storage reads, exact field-aware typed rendering and custom-field conversion, request-document cache confinement, strict prepared-input rejection, model-zone clock sampling, structured finding/computation channels, complete custom field-type/condition SPIs, pre-bound customization slots, support reporting, repeated-row rule outcomes, interpreter-binding factories, Worker-local rollout/ownership, JVM pool backpressure/lifecycle races, and the exact Node package ABI. Kernel-interface compatibility is a separately packaged deliverable, but the native customization semantics it adapts are required core work. An adapter cannot claim compatibility until unchanged SME implementations and at least one additional production implementation pass source-/binary-compatibility tests, semantic differential tests, lifecycle tests, and the separate performance gates in §11. Persistent structural sharing, candidate programs, and browser support should not hold the core validation-service API hostage.
