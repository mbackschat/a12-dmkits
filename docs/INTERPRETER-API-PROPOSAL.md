**Proposal-writing guideline — simple mode**

Keep this document focused on the requested API proposal, not an implementation or rollout plan.

- Prefer the smallest viable design; do not expand the scope or add speculative features.
- Use short sections, tables, bullets, and fully typed minimal examples instead of prose walls.
- Keep paragraphs to at most three sentences and explain each concept once.
- Include only types needed to understand the public API; do not catalogue basic language types.
- Clearly separate required, optional, and excluded functionality.
- Preserve semantic and performance requirements without repeating them.
- Surface only issues that materially affect correctness or feasibility.
- Delete anything that does not help a developer understand or use the proposal.

Preferred structure: motivation, scope, public API, typed examples, semantics and guarantees, packaging and adapters, explicit exclusions.

# Proposal: TypeScript-first API for the Kotlin Multiplatform interpreter

## Status

> **End-state vision capture.** This is the full destination assembled from interview Q&A and accumulated wishes, kept deliberately complete so no requirement is lost — not a scoped build order. The smallest buildable first increment (§12.1's minimum transport + ownership foundation, then the validate/compute + SPI increment) is extracted to [TS-API-MVP-PROPOSAL](TS-API-MVP-PROPOSAL.md); publication is gated on IG85.
>
> **Why this layer, and how much:** [TS-API-STUDY](TS-API-STUDY.md) §5 reads this proposal as a cheap, grounded *core* (load → document → validate/compute → SPIs) wrapped in a speculative service/editor/candidate *product bet* — build the core, design-but-defer the rest until a consumer justifies its shape.

This proposal defines a TypeScript-first public API for the Kotlin Multiplatform A12 interpreter. It covers model preparation, lossless documents, direct storage access, validation, computations, customization, prepared programs, repeated-model services, and platform ownership.

TypeScript on Node is the primary product surface. Java/JVM is a secondary projection of the same Kotlin `commonMain` implementation. Browser use is an important future form-UI scenario, not a current support claim.

The base Kotlin Multiplatform interpreter and its TypeScript/JVM façades are clean-room, kernel-free, MIT-licensed artifacts. They do not import, link, reflect over, or bundle an A12 Kernel artifact.

The direct [Operator API proposal](OPERATOR-API-PROPOSAL.md) remains independent: calling `operators.dateTime.differenceInHours(start, end)` never requires an interpreter. This proposal is the model/document layer for behavior that genuinely needs paths, repetitions, formal state, relevance, rules, computations, or runtime customization.

API and package names are proposed contracts. Where a contract requires engine work beyond the present evaluator, the relevant section states that implementation dependency because kernel exactness and performance evidence are acceptance conditions.

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
| Supply custom field types and custom conditions | evaluator factory hooks | core kernel-free registries and SPIs |
| Evaluate an arbitrary symbolic expression | parser/evaluator internals only | optional advanced API, not core v1 |
| Prepare an ad hoc computation | no complete capability | deferred |
| Build or consistency-check a model | modelkit/kernel concerns | outside the interpreter API |

The core should be complete for “load model → create/read document → configure custom types/conditions → validate/compute → inspect results.” Candidate programs, service registries, batch helpers, and browser adapters are optional layers.

The primary direct verbs remain deliberately familiar: `validateFull`, `validatePart`, and `compute` offer the corresponding kernel capabilities over kernel-free types. Richer reports, frozen snapshots, support inspection, and compute-and-apply are additive; they do not rename or weaken the established validation/computation jobs. `PreparedModel` is the kernel-free prepare-once abstraction and does not reproduce the kernel’s strategy-specific dynamic-code cache API.

## 3. Prepared model, interpreter, and ownership

### 3.1 Core types and primary TypeScript shape

| Type | Meaning and ownership |
|---|---|
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

Host-language values never substitute for document values: native `number` is limited to safe-integer API metadata and coordinates, document decimals use `A12Number`, document Booleans use `A12Boolean`, and `undefined` does not mean absent or present-empty storage. TypeScript brands prevent accidental assignment only; Kotlin-owned runtime tokens enforce authenticity.

#### Preparation and core errors

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

| Boundary | Contract |
|---|---|
| Model loading | `loadExpandedJson` returns `PreparedModel` or throws `ModelPreparationError`; `tryLoadExpandedJson` returns the same diagnostics through `ModelPreparationResult` |
| Owner mismatch | `ModelOwnerMismatchError` covers foreign snapshots, pointers, and prepared-program handles; `expected` and `actual` are opaque correlation identities, not model content |
| Model lookup | `UnknownModelPathError` reports the requested `ModelEntityKind` and path |
| Document input | `DocumentInputError` aggregates portable placement diagnostics; every item has an envelope `inputPath` and may separately carry the attempted A12 `modelPath` |
| Diagnostic details | The discriminated `details` member carries expected/received type, version, count, limit, property, duplicate, or conflict data; consumers never parse presentation-only `message` |
| Placement location | `placementIndex`, when present, is zero-based into the corresponding input array |
| Coordinates | `DocumentCoordinateError` reports the exact model path, expected coordinate count, supplied coordinates, and whether arity or positive-safe-integer validation failed |
| Row scope | `DocumentRowScopeError` carries the requested group path and supplied ancestor `GroupPointer` |
| Typed write | `DocumentValueWriteError` reports kind mismatch or lossy representation without changing the value |
| Candidate preparation | `ProgramPreparationError` reports invalid candidate-rule or expression source without classifying the whole model as invalid |

The default configuration uses a system clock sampled once per evaluation transaction, model-carried time zone/declared base year/charset, the kernel-compatible date-completion base-year default, the BMP fallback charset, built-in predefined types, and empty custom registries. Missing custom constructs remain explicit in evaluation results as unsupported; they are never silently ignored.

#### Runtime configuration and customization

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

`PreparedModel.createInterpreter(configuration)` calls `createBindings` exactly once and never shares the result with another interpreter. Reusing one configuration is safe only because each call returns a fresh exclusive binding set; those clocks and registries need not be thread-safe.

Concurrent `createInterpreter` calls may invoke the factory concurrently, so the factory itself must be share-safe or application-serialized. Direct bindings have caller-owned external resource lifetime and no interpreter disposal callback; service-owned creation and cleanup use `InterpreterConfigurationRegistration` in §8.

#### Customization semantics and lifecycle

The public customization surface is a P0 compatibility contract, not an optional plug-in afterthought. The kernel-free interpreter defines Kotlin Multiplatform SPIs for Custom Field Types and Custom Conditions with the same observable jobs and callback data.

A new portable customization can be implemented once in Kotlin `commonMain` and projected to TypeScript and JVM when its dependencies are multiplatform; host-specific implementations can target the corresponding projection directly. The core SPI remains useful without any kernel artifact.

| Custom Field Type aspect | Contract |
|---|---|
| Validation input | `value` is native stored text; `parameters` carries message locale and optional `minLength`/`maxLength`; `isDisplayValue` states whether conversion is required |
| Core storage | `DocumentSnapshot` stores internal form, so direct core validation passes `isDisplayValue: false`; form/import code converts display text before writing |
| Validation result | Acceptance fabricates no value; rejection preserves the consumer-defined `errorKey` and message |
| Conversion result | Both directions return converted text or the retained input plus its conversion error; identity conversions are valid |
| Invocation ownership | The evaluator owns the field pointer and model constraints; they are not invented callback inputs |

`CustomCondition.check` has no expression arguments. Its invocation exposes the complete model-owned snapshot, model metadata/navigation, lossless stored values, effective values including call-scoped overrides, instantiated rows, and the authored `documentModelId`.

| Custom Condition input | Contract |
|---|---|
| Full relevance | `relevantEntities` is `undefined` |
| Partial relevance | Preserve the caller selection after entity-identity conversion/deduplication and global-field inclusion; do not pre-expand group selectors, and keep wildcard forms |
| Empty relevance | A truly empty effective set returns before callback invocation; an originally empty array reaches callbacks only if source normalization such as global-field inclusion makes it non-empty |
| Formal errors | Preserve the exact callback set after pointer conversion |
| Error pointer | Preserve the containing rule’s error field, including partially known repetitions |
| Overrides | Preserve call-scoped effective-value overrides; use `undefined` when absent |

The callback returns native `boolean`; `true` means the error condition is met and the containing rule fires. It returns `false` itself when required input is irrelevant, formally incorrect, or does not satisfy the project condition.

The interpreter must not substitute `A12Truth`, a generic refinement, expression arguments, locale, or evaluation time. Conformance laws lock set membership, duplicates, order exposure, short-circuiting, and pointer/wildcard conversion exactly.

| Registry operation | Contract |
|---|---|
| `of` | Create an immutable explicit-name registry; reject empty or duplicate names with exact `CustomizationRegistrationError` reasons |
| `fromMap` | Accept own enumerable keys only |
| `fromFactory` | Adapt a name resolver whose `create` returns `undefined`, without throwing, for unsupported names |
| Lane binding | Enumerate names used by the prepared model, create each native implementation once per lane/configuration, and store direct slots |
| Hot path | Perform no registry scan, name lookup, reflection, or provider discovery per field or AST node |
| Missing name | Report it through `supportReport()` and the ordinary unsupported result channel |

`CustomConditionDocumentView` is only the typed observable subset conditions require, not a recreation of an arbitrary document API. If a production callback observes another required fact, add an explicit typed member rather than a generic map.

Conformance laws preserve invocation order/cardinality, repetition scope, partial relevance, formal-error filtering, partially known error pointers, overrides, normalization, length semantics, `isDisplayValue`, conversion, error identity/message, rejection or false versus exception, and cleanup after partial failure.

For native core callbacks, an exception is `InterpreterIntegrationError`; it never becomes ordinary rejection, unsupported semantics, or `false`.

There is no Custom Operator SDK or operator registration. Operators remain the fixed, catalog-governed surface from the [Operator API proposal](OPERATOR-API-PROPOSAL.md).

All callbacks are synchronous. A runtime thenable from plain JavaScript or unsafe TypeScript fails at the callback boundary with `InterpreterIntegrationError`, is never awaited, and retires the lane.

Callback sets belong exclusively to one active lane unless a future contract proves sharing safe. Direct callers retain external resource ownership; service registrations receive the exactly-once disposal contract in §8.

An active `Interpreter` is not recursively reentrant. A same-lane validation, computation, inspection, or custom-field conversion from a callback fails with reason `"reentrant-evaluation"` before time sampling, cache mutation, or callback-context replacement.

A callback may use a separately acquired lane under normal pool rules. Tests cover direct reentry, nested failure, and `finally` cleanup so an outer callback context cannot be overwritten.

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

#### Ownership and callable semantics

| Concept | Contract |
|---|---|
| `ModelOwned` | Carries exact local model-version and owner identities |
| Static brand | Prevents accidental object-literal assignment in checked TypeScript but is bypassable through `any` or assertions |
| Runtime authenticity | Every public operation validates Kotlin-owned owner tokens; equal model versions remain incompatible when owners differ |
| `PreparedDocumentApi` | Authoritative model-bound document codec and builder factory |
| `PreparedModel` | Owns one local domain; neither it nor its prepared representation is required to be concurrently shareable |
| `Interpreter` | Configured, initially thread-confined evaluator with model-validated string paths |
| Calls | Synchronous, with complete option defaults, returning detached results or frozen snapshots |

#### Model metadata semantics

| Metadata aspect | Contract |
|---|---|
| `documentModelId` | Preserve the exact identity supplied to TypeScript Custom Conditions; never derive it from a service alias or fingerprint |
| Flat traversal | `fields()` and `groups()` return deterministic model-order views |
| Structural traversal | Root methods begin traversal; each group exposes direct fields, child groups, and optional index field |
| Index field | Identifies the direct-child row key; non-empty values are automatically checked for uniqueness within each parent-row scope |
| Metadata objects | Field, group, rule, and computation metadata are immutable descriptions |
| `FieldKind` | String, enumeration, and custom declarations project to `"string"`; date and date-fragment declarations project to `"date"`; other declarations retain their semantic kind |
| `FieldDeclaration` | Preserves the distinct authored enumeration, custom, date-fragment, and optional-date cases needed before values exist |

| Requiredness value | Contract |
|---|---|
| Declared | Exactly the authored setting |
| Effective index-field rule | Authored `"not-required"` becomes `"relative-to-parent"`; explicit requiredness remains explicit |
| `"absolute"` | Always required without repeatable ancestors; otherwise required once the lowest repeatable-ancestor row exists |
| `"relative-to-parent"` | Required when the immediate parent is filled; an explicitly created empty repeatable row counts as filled |
| `"not-required"` | No mandatory check after index-field expansion |
| `global` | Model’s global-relevance flag |

| Field or constraint | Contract |
|---|---|
| String/number constraints | Expose the exact typed bounds for those declarations |
| Custom constraints | Carry only optional length hints; custom fields do not inherit built-in string pattern, newline, or `valueValidationEnabled`, and their validator owns length acceptance |
| Number scale | Required single effective maximum fractional-digit count; strict preparation supplies `0` when legal source omits it, with no duplicate top-level scale property |
| `FieldFormat` | Native model format text |
| Repeatability | Lists repeatable ancestors in path order; empty means non-repeatable; each optional maximum is a positive safe integer |

| Navigation or value | Contract |
|---|---|
| Refs | `GroupRef`, `GroupRowRef`, and `FieldInstanceRef` are validated navigation handles |
| Concrete pointers | `DocumentPointer` identifies one entity and narrows to field/group; `CustomFieldPointer` proves a custom declaration and carries its type name |
| Pointer authenticity | TypeScript brands prevent accidental relabeling; runtime operations verify Kotlin-owned pointer/owner tokens |
| Relevance pointers | May carry nominal `ALL` wildcard coordinates |
| Report pointers | May carry concrete, `ALL`, or `UNKNOWN_REPETITION` coordinates; findings never fabricate concrete cells |
| Date fragments | Component variants are explicit and use `A12Integer`, not native numbers |
| Typed writes | `DocumentCellValue` combines ordinary operator values with model-owned incomplete-date values that are not honest `A12Value` instances |
| Numeric bounds | Coordinates and metadata bounds are safe integers; repetition indexes are positive and length/digit/scale bounds non-negative |
| Localized maps | `Readonly<Record<LocaleCode, string>>` maps locale codes to immutable text |

`ModelMetadata.declaredBaseYear` exists only when authored and controls whether `BaseYear` and other declaration-dependent expressions are legal. `effectiveDateCompletionBaseYear` always exists and completes date fragments and partial ranges using the declared year or kernel-compatible default `2000`.

The completion default never makes `BaseYear` legal when `declaredBaseYear` is absent.

| Document API | Contract |
|---|---|
| Snapshot storage/rows | Read frozen placements directly without formal checks or custom validators |
| Inspection | Detached model-owned index from one complete formal pass; lookup never reevaluates or reinvokes callbacks |
| Builders/editors | Single-owner mutable helpers whose `freeze()` returns a new snapshot; typed removal restores absence or removes a complete subtree |
| `isSnapshot` | Authenticates a snapshot from the exact prepared owner without relying only on structure or `instanceof` |
| Placement codec | `decodePlacements` is authoritative unknown ingress; `tryDecodePlacements` is non-throwing; `readPlacements` is the validated-DTO fast path; `encodePlacements` is canonical output |
| Partial dates | Day-optional and date-fragment constructors produce exact same-owner model values |
| Optional extensions | Persistent editing and prepared programs use separate capability types and capability-specific loaders/adapters; core `PreparedModel` promises neither |

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

The prepared definition may record unique custom names, but only `Interpreter` creation resolves them against lane registries. The bound runtime stores direct validator/condition slots beside relevant metadata and condition nodes.

When a source factory must observably create callbacks more often, a provider slot may instead hold a constant-name lifecycle-preserving provider. It still performs no registry search or factory discovery in the AST.

This separation allows two interpreters over one model to use different project implementations without hot-path name lookup.

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

`captureEvaluationTime()` samples the configured provider once and returns an `A12EvaluationTime` projected into the model zone. Supplying an explicit evaluation time avoids another clock read but still performs instant-preserving model-zone projection.

This normalization is specific to model evaluation. Direct point-in-time operators use the sample’s own explicit zone as defined by the [Operator API proposal](OPERATOR-API-PROPOSAL.md).

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

Passing the same immutable `A12EvaluationTime` lets several calls form one deterministic application transaction. A fixed clock remains available for tests. A live clock provider and per-call sample are new API/refactoring work; the current `DmInterpreter` captures a fixed `EvalClock`.

Locale and evaluation time are per-call data. They are never written into a prepared model, warm lane, process-global setting, model alias, or registry key. A request that omits time samples its lane’s configured clock once at transaction entry; a request that supplies time bypasses the clock read. A request that omits locale uses the documented API default without mutating any global locale.

Strict preparation rejects a model time zone that the current multiplatform runtime cannot implement with exact kernel semantics. It must not fall back to UTC, the host zone, or a partly compatible named-zone implementation. Closing a named-zone parity gap expands the accepted `A12TimeZone` set without changing this evaluation contract.

## 4. Model input, diagnostics, and introspection

### 4.1 Prepared input

The standalone boundary initially accepts only expanded, self-contained, name-path-keyed DM-JSON. It rejects unresolved includes, unresolved type definitions, opaque identifiers that require an external resolver, and other preparation residue.

`ModelLoader.load` is not yet that strict boundary: an unresolved type definition can currently degrade to a string kind. `PreparedModel.loadExpandedJson` must add a real residual/type/include guard and throw `ModelPreparationError` with structured diagnostics rather than accepting a semantically different model. `tryLoadExpandedJson` exposes the same failure without exceptions.

V1 date precision supports only `"full"` and `"day-optional"`. Strict preparation rejects month-optional or year-optional declarations with a stable diagnostic such as `"UNSUPPORTED_DATE_PRECISION"`.

It does not coerce them, collapse them to fragments, or defer them as unsupported cells. Once implemented and differentially verified, metadata and inspection unions can grow without weakening strict input.

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

| Metadata detail | Contract |
|---|---|
| Number scale | `FieldDeclaration.scale` is the single effective scale and is `0` when legal source omits it |
| Traversal | `fields()` and `groups()` are flat model-order views; root methods begin structural traversal; group methods expose direct children |
| Requiredness | Declared requiredness preserves authored configuration; effective requiredness includes index-field auto-requiredness |
| Base year | `declaredBaseYear` is optional; `effectiveDateCompletionBaseYear` is always present for yearless fragments and partial ranges |
| Programs | Rule and computation metadata arrays are immutable descriptions, not evaluable handles |

`ModelMetadata` is a read-only prepared-definition view. Field, group, rule, and computation metadata are immutable descriptions rather than evaluable handles.

`FieldKind`, `FieldFormat`, `Repeatability`, `CommonFieldConstraints`, and `FieldDeclaration` preserve semantic kind, lexical format, repetition, authored/effective rules, and declaration-specific constraints. Required lookup throws `UnknownModelPathError`; nullable lookup supports probing.

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

Registry implementations key by the runtime-authenticated owner-aware handle, not by a path or mutable model alias. The same path in a newer model version is a different key. There is no process-global application metadata registry, and model eviction does not delete caller metadata unless the caller explicitly couples those lifecycles.

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

`PlacementDocumentInputV1` is the portable, behavior-free DTO. `GroupPlacementInput` creates one concrete row; `FieldPlacementInput` stores one raw cell.

`path` is an absolute model path validated during decoding. `coordinates` contains one positive, 1-based repetition index per path part, including the field part, and `raw` preserves exact lexical text.

Omission means absent, `raw: ""` means present-empty, and malformed text remains present for formal validation. Explicit group placements preserve empty and sparse rows.

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

`decodePlacements` is authoritative public ingress: validate runtime shape, reject unknown canonical-v1 keys, enforce resource limits, then perform model-aware path/coordinate checks and aggregate failures in `DocumentInputError`.

`tryDecodePlacements` performs the same work through `A12Result<DocumentSnapshot, DocumentInputError>`. `readPlacements` is the fast path for data already validated by a trusted decoder; a TypeScript annotation alone is not evidence of validation.

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

`DocumentBuilder` is a mutable, single-owner helper bound to one prepared model. Mutations accept validated pointers rather than duplicate raw path/coordinate overloads.

`GroupRef.row` and `ModelMetadata.pointer` reject wrong arity or non-positive/non-safe-integer coordinates with `DocumentCoordinateError`. Coordinates above a repetition maximum remain representable for formal validation, and repeated `addGroup` is idempotent.

#### Exact typed storage

`putValue` is the field-aware typed insertion contract. `DocumentCellValue` includes ordinary scalar values plus `OptionalDateCellValue` and `DateFragmentCellValue`.

A complete value in a day-optional field remains `A12Date`; `dayOptionalDate` creates only the absent-day case. Partial values must share the prepared owner, and fragment shape must match the target declaration.

Each field declaration has a storage-representation codec independent of formal validation.

| Codec aspect | Contract |
|---|---|
| No implicit loss | Never round, truncate, normalize stored line breaks, or drop date components |
| Exactness | Value kind must match and render-then-read must preserve semantics |
| Date fragment | Compare explicit components and `resolvedValue` |
| Date-time | Compare the same exact instant after required model-zone projection |
| Evaluation normalization | CRLF-to-LF and other evaluation-only transformations are outside the storage round trip |
| Number rendering | Remove insignificant trailing fractional zeros, reject natural scale above field scale, pad to `minFractionalDigits`, and emit plain decimal without exponent |
| Invalid number declaration | Strict preparation rejects `minFractionalDigits > scale` |
| Caller correction | For example, scale-2 rejects `12.345`; callers explicitly round or project before retrying |

Built-in string, enumeration, and custom declarations store `A12String` exactly; custom fields use internal text. Formal validation supplies that string, `isDisplayValue: false`, locale, and optional length hints.

Display text first uses the bound conversion API.

#### Custom display/internal conversion

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

Only the `"converted"` result supplies internal text suitable for `A12String.of` and a typed document write; `"rejected"` retains the supplied text and message for the UI. `convertCustomFieldInternalToDisplay` provides the inverse operation over canonical internal text. Neither conversion performs whole-document validation.

#### Mutation, removal, and freezing

| Mutation case | Contract |
|---|---|
| Non-representation constraint | Store the exactly rendered value; ordinary validation reports min/max, requiredness, enumeration, pattern, custom, and similar violations |
| Kind mismatch | Throw `DocumentValueWriteError` with `"field-kind-mismatch"` |
| Inexact representation | Throw with `"value-not-exactly-representable"` |
| Failed mutation | `addGroup`, `removeGroup`, `putRaw`, `putValue`, and `removeField` are atomic |
| Raw write | Preserve arbitrary, including malformed, lexical text for formal validation |
| Bulk input | Placement decoding aggregates structural failures in `DocumentInputError` |

- `removeField` idempotently restores exact field absence without deleting an explicitly stored containing row.
- `removeGroup` idempotently removes the group placement and descendant subtree while preserving siblings and ancestors.
- `putRaw(pointer, "")` creates present-empty storage and is intentionally different from `removeField`.
- Both construction helpers expose these operations for undo and persistent editing without rebuilding placement DTOs.

Builders are not evaluable. `freeze()` produces a stable request document whose stored placements no longer mutate. The initial service contract keeps it and its evaluation view confined to one request/runtime lease; it does not need to be concurrently shareable.

#### Optional persistent editing

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

`PreparedDocumentEditingApi` adds `edit` without adding that method to the core `PreparedDocumentApi`. `PersistentDocumentEditor` is a single-owner editor with the same mutation contracts as `DocumentBuilder`; it should use structural sharing so one keystroke does not copy an entire large document. It cannot become supported until its cache invalidation and update-to-validation allocation/latency contracts are proven; the validation-service core does not wait for it.

### 5.3 Pointers and typed inspection

`DocumentPointer` is a tagged model-validated value containing path plus canonical repetition coordinates. Wildcard `ALL` is a tagged coordinate, not an internal sentinel integer.

#### Pointer construction

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

Every pointer carries its entity kind, path, coordinates, model-version identity, and owner identity. `GroupRef.row` checks arity and positive-safe-integer coordinates without requiring that the row already exists. The lossless transport retains complete canonical coordinates, so ordinary callers do not hand-author internal coordinate arrays.

#### Instantiated-row enumeration

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

`instantiatedRows` includes explicit empty rows and rows implied by fields, preserves sparse positive indexes, supports nested repetitions, and returns canonical ascending complete coordinates.

Omitting `within` searches all parent contexts; supplying it restricts results to descendants. Foreign owners raise `ModelOwnerMismatchError`, non-ancestor same-model rows raise `DocumentRowScopeError`, and enumeration reads placements directly without serialization.

#### Storage and formal inspection

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

`DocumentSnapshot.storage` is the direct storage boundary.

| Storage behavior | Contract |
|---|---|
| No validation | Do not run requiredness, format, range, pattern, uniqueness, charset, custom-field, rule, or computation logic |
| No callbacks/time | Invoke no callback and sample no clock |
| Bulk lookup | Preserve input order and duplicates |
| Ownership | Check owner identity before reading |
| Result | Preserve absent/empty/present raw state and decoded `CellStorageValue`; malformed present text is `"unavailable"` |
| Use cases | Form rendering, debugging, persistence mapping, and on-keyup reads without a surprising full pass |

`CellInspection` correlates four dimensions so impossible combinations do not type-check:

| Dimension/type | Contract |
|---|---|
| Identity | Pointer plus model-version and owner identities |
| Storage | Absent, present-empty, or present non-empty raw text |
| `NonEmptyRawString` | Library-issued output-only brand that cannot overlap present-empty |
| Storage value | None, ordinary `A12Value`, model-owned optional date/date fragment, or unavailable undecodable text |
| `NonStringA12Value` | Every ordinary value variant except `A12String` |
| Evaluation value | Concrete interned subtype seen by programs, or exact non-value reason |

| Formal state | Contract |
|---|---|
| Valid | No suppressing automatic diagnostic and configured implementation available |
| Invalid | One or more formal diagnostics |
| Unsupported | Present custom-field string whose configured type is unavailable |
| Invalid and unsupported | Preserve both reachable facts, such as illegal charset plus unregistered custom type |
| Unsupported detail | Identify custom type and stable reason separately from program-level `UnsupportedProgram` |
| Unions | `CellFormalFailure` and `CellFormalValidity` retain the complete state algebra |

`CellInspectionCase<S, V, F, E, P>` binds storage, decoded value, formal/support state, evaluation value, and pointer type; named aliases keep the exported union readable.

| Correlation | Contract |
|---|---|
| Empty storage | May be valid or invalid, never unsupported |
| Unsupported custom field | Requires `CustomFieldPointer`; reason type name equals `pointer.customTypeName` |
| Unavailable built-in text | Always formally invalid, not “missing custom semantics” |
| Optional absent | Valid but not interned because no value is stored |
| Required absent/empty | Invalid and not interned because formal validity suppresses reading |
| Decoded but invalid | A number can decode yet violate a minimum; it cannot simultaneously expose an evaluator value |

#### Complete formal inspection

`inspectDocument` runs the complete automatic formal oracle once with all model entities relevant. It covers lexical format, declared constraints, effective mandatory checks, duplicate non-empty indexes, over-repetition, legal characters, and configured custom validation.

Locale defaults to `"en_US"` and is passed exactly to custom validators. Authored business rules remain in `ValidationReport`, and each custom validator runs at most once per concrete visited field.

The returned index is detached and immutable. Single/bulk lookup does not revalidate, bulk lookup preserves order and duplicates, and foreign pointers raise `ModelOwnerMismatchError`.

No implicit inspection cache crosses snapshots or interpreters; a newly frozen snapshot needs a new pass, and D1/D2 interleaving cannot reuse request state. A call-owned index may have bounded lifetime but cannot retain application callback resources.

| Diagnostic rule | Contract |
|---|---|
| Ordering | Retain every automatic finding in deterministic evaluator precedence after mutual suppression |
| Effective suppression | Identify the one diagnostic that produces unknown/suppressed expression behavior |
| Invalid and unsupported | Formal invalidity has evaluation precedence, while `ValidationReport.unsupported` retains the same pointer, type name, reason code, and detail |
| Partial relevance | Complete inspection does not describe `validatePart`; any future relevance-aware form needs explicit relevance input |

#### String storage and evaluation

Storage preserves exact text, including CRLF and lone CR. Evaluator ingestion converts each CRLF pair to LF exactly once while retaining lone CR; pattern/length checks, programs, and message interpolation see this normalized `A12String`, while stored forms remain untouched.

A `noValueValidation` string can be formally valid and storage-decoded yet remain `{kind: "not-interned", reason: "raw-type"}` for value operations. Presence checks still use storage state.

The other non-interned reasons are exact: `"no-stored-value"` for an optional absent/empty cell, `"formally-invalid"` when formal state suppresses value reading, `"incomplete-date"` for a day-optional value that requires explicit boundary resolution, and `"unsupported"` when configured semantics are unavailable. Expression-specific empty substitution remains inside the interpreter; a non-interned cell is not converted into a misleading direct `A12Value`.

#### Partial dates and date fragments

| Optional-date aspect | Contract |
|---|---|
| Ownership | Model-owned because construction, insertion, and completion belong to one prepared definition |
| Representation | Day-optional with exact `A12Integer` year/month; no fabricated day or field-specific lexical format |
| Construction | Validate components and throw `A12ConversionError`; target formatting occurs only in `putValue` |
| Complete date | Decode as ordinary `A12Date`; the special type appears only when day is omitted |
| Resolution | Not an `A12Value`; resolve with explicit first/last boundary and reject foreign ownership |
| Unsupported precision | Strictly reject month-optional/year-optional models until implemented and differentially verified |

| Date-fragment aspect | Contract |
|---|---|
| Shape/components | Declaration has four format/granularity pairs; caller input uses matching exact `A12Integer` components |
| Stored value | Preserve format, granularity, supplied components, and earliest-completed `resolvedValue` |
| Construction | Require same-owner date-fragment metadata and a matching component variant |
| Failure | Wrong declaration/shape/components raise `A12ConversionError`; foreign metadata raises `ModelOwnerMismatchError` |
| Non-throwing form | `tryDateFragment` converts only expected conversion failure to `A12Result`; ownership misuse still throws |

`"MM"` and `"MM-dd"` use the effective completion year; `"yyyy"` and `"yyyy-MM"` retain their supplied year. A fragment writes only to a matching field, and an ordinary `A12Date` is rejected rather than losing components.

The completion default does not make `BaseYear` legal when no base year is declared.

Partial ranges decode directly to complete `A12DateRange` values. Missing years use the effective completion year; missing start components complete earliest, and missing end components complete latest, so a year-only range spans January 1 through December 31.

| Diagnostic/result | Contract |
|---|---|
| `InspectionDiagnostic` | Locale-independent discriminant with structured fields |
| Custom rejection | Retain exact `errorKey` and rendered callback message without replacement |
| `FormalSuppression` | Detached field pointer and formal-error code preventing rule/expression evaluation |
| `UnsupportedProgram` | Separate program-level result for missing custom or unsupported semantics |

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

The example keeps callback-free storage, configured formal state, evaluator-visible value, optional-date resolution, and fragment components distinct. A concrete evaluator value exists only in the `"value"` branch and may include evaluation normalization such as CRLF-to-LF.

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

`LocaleCode` names a message locale such as `"en_US"` or `"de_DE"`. Program evaluation options carry per-call locale/time; inspection carries only observable custom-validator locale because it runs no authored time-dependent program.

Omitting locale is exactly equivalent to `"en_US"`.

Locale lookup is intentionally explicit and matches the current evaluator:

- An authored stored-rule message uses an exact `Readonly<Record<LocaleCode, string>>` key lookup. There is no language-only or model-default fallback. If a firing rule has no entry for the requested locale, `ValidationFinding.message` is the required native `string` value `""`.
- A field-label placeholder uses the exact requested locale and falls back to the field’s final path segment when that label is absent or blank.
- Interpreter-owned formal, mandatory, uniqueness, custom-type, and over-repetition messages use German when `locale.startsWith("de")`; every other locale receives the interpreter’s English wording.

`ValidationFinding` is detached stable result data. `FormalValidationFinding` carries no invented rule identity; `AuthoredRuleValidationFinding` alone has the required authored `rulePath`. `ReportPointer` preserves concrete coordinates, authored wildcards, and partially known repetition coordinates. A projection may map those pointers to UI paths, but it cannot replace them with fabricated concrete `DocumentPointer` values.

| Unsupported/result type | Contract |
|---|---|
| Base | Optional semantic path plus stable reason and detail |
| Executable | Rules, computations, expressions, and custom conditions |
| Custom field | Exact `CustomFieldPointer` and custom type name |
| Closed enums | Severity, polarity, and built-in unsupported reasons remain exhaustive |
| Custom rejection | Preserve project `errorKey` in the formal finding without opening all built-in codes to arbitrary strings |

`ValidationReport` is detached and owns readonly arrays.

| Report member | Contract |
|---|---|
| `noErrorOccurred` | Exact kernel ERROR-severity predicate; warnings and information do not make it false |
| `fullySupported` | True only when no requested work was omitted for implementation/configuration support |
| Independence | Unsupported work fabricates no ERROR; a fully supported report may still contain errors |
| Empty message | A missing exact stored-rule locale yields `""`, which is not a missing finding |

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

`supportReport()` checks the prepared model against bound types, conditions, charset, time zones, operators, and program forms without reading a document or invoking validation callbacks. Services can therefore fail readiness before accepting traffic.

`fullySupported` means no known capability is unsupported. `fullyResolved` additionally means none remains deferred to a source factory whose invocation cardinality prevents eager probing.

`validateFull` is the primary complete-document verb; `validatePart` is its relevance-aware counterpart. Both return kernel-free `ValidationReport`.

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

The relevance array is the complete kernel-style field/group set with concrete or tagged `ALL` coordinates, not merely changed fields. Partial validation changes operand visibility and three-valued suppression; it is not full validation followed by filtering.

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

`ComputationOutcome` is detached data for one field target; computations never target groups.

| Outcome data | Contract |
|---|---|
| Channels | Value, explicit clear, errored attempted value, or formal operand suppression |
| Errored value | Always retain attempted raw text; include typed value only when conversion succeeded |
| Application | State exact effect, including already-equal values, absent/already-empty clears, and ancestor creation |

`ComputationReport.delta` contains only actual storage changes, in kernel application order. `changed` is exactly `delta.length > 0`. `noErrorOccurred` preserves the kernel computation-result error predicate and is false for the kernel channels classified as errored or formal-error; unsupported coverage remains independent. `fullySupported` is true exactly when `unsupported` is empty. Consumers do not infer any of these facts from array lengths or messages.

| Outcome projection | Established capability |
|---|---|
| `"value"` | Computed without errors |
| `"value"` plus `"write"` | Computed with changes |
| `"errored"` | Computed with errors |
| `"cleared"` | Cleared fields |
| `"formal-error"` reasons | Formal errors in operands |

`noErrorOccurred` is the corresponding aggregate.

| Application case | Contract |
|---|---|
| Unsupported program | Prevent every write and preserve the original snapshot |
| Value | Create missing target and required ancestors, then store `storedRaw` |
| Clear/error on existing target | Make it present-empty |
| Clear/error on absent target | Keep absent and create no ancestor solely to clear |
| Formal error | Perform no write |
| Ordinary error with full support | Still return `"applied"`; report errors are not atomic-application failures |

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

Empty compute options use the configured clock and `"en_US"` locale. Locale remains observable when computation invokes formal/custom-field validation.

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

`computeAndValidate` computes once, applies the same placement rules as `computeAndApply`, then validates the fully updated snapshot.

If any program is unsupported, it returns `computationsNotApplied` with the unchanged input and no misleading validation report. This is a convenience over the same engines, not a second execution strategy.

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

`CandidateRuleDefinition` is caller-owned source. `PreparedRule` is a nominal parse-once model-owned handle, not a rulekit AST.

Candidate results are explicitly preview data and reuse row-preserving `RuleOutcome`. Core `PreparedModel` and `Interpreter` do not promise this optional capability.

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

`NumberExpressionDefinition` combines number-only source with its reference base; `PreparedNumberExpression` is a nominal parse-once model-owned handle. Evaluation supplies a concrete group row.

The value case contains exactly `A12Number`. The result also preserves domain non-evaluation such as division by zero, formal suppression, and unsupported semantics; expression non-evaluation is distinct because it need not involve a formally invalid field.

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

#### Concurrency and lifecycle

| Concern | Contract |
|---|---|
| JVM safety | Service, registries, and handles are thread-safe |
| Linearizability | Registration, alias activation, pins/releases, retirement, admission, drain, close, and metrics transitions are linearizable |
| Publication | Prepared objects are safely published before requests observe them |
| Runtime lease | At most one exclusive lease per runtime; concurrent same-model work uses several runtimes |
| Locking | Never concurrently enter one `Interpreter` or serialize evaluation behind a global/per-model lock |

The lifecycle is one-way: `open → draining → closed`.

| Lifecycle concept | Contract |
|---|---|
| Admitted work | Model/config registration, alias operations, support, validation, and warmup |
| Admission | Atomically require `open` and increment `inFlightOperations` before releasing coordination |
| Completion | Decrement only after parsing, binding/runtime creation, publication or rollback, result detachment, and owned cleanup |
| Separate gauges | Waiting acquisitions and active leases remain admitted work but are also exposed separately |
| External disposal | Disposal outside admitted work increments `disposingRuntimes` |
| `drain()` | Enter draining, reject new work, wake queued acquisition with `ValidationServiceDrainingError`, wait for all in-flight/waiting/active/disposal counts, and never resume |
| `close()` | Quiesce, retire idle runtimes, await disposal, invalidate tokens, clear registries, then publish closed |
| Cleanup failure | Attempt all cleanup, then throw the first integration error and summarize the rest |
| Postcondition | Nothing can publish or leak after close returns |

#### Reentrancy and repeated lifecycle calls

Same-service synchronous `drain`, `close`, or configuration release from `createBindings`, evaluation callback, or `dispose` throws `ReentrantServiceLifecycleError` before state change or waiting. This includes releasing configuration B from a callback created for A, preventing cross-configuration wait cycles.

The error records attempted operation and active create/evaluate/dispose phase. Uncaught reentrancy is wrapped as `ValidationServiceIntegrationError`; model/alias release stays non-blocking under the admitted temporary pin, and calls from another thread follow normal quiescence.

| Repeated call | Contract |
|---|---|
| Second drain | Join current wait or return when already quiescent/closed |
| Concurrent close | One caller cleans up; all callers observe the same completion/error |
| Close after drain | Join drain, then close |
| Drain during close | Join close; a drain ordered before close may return at drained quiescence |
| Already closed | Drain, close, token release, and metrics are harmless; other operations fail |

#### Error precedence and cancellation

Lifecycle calls enforce reentrancy before observing or mutating state. Ordinary work checks, in order:

1. closed, then draining state;
2. handle ownership;
3. released, then retired token state;
4. input, capacity, then pool saturation.

Request handle order is deterministic: model before configuration; alias creation checks initial model; activation checks alias before next model. Even several invalid handles therefore select one stable `handleKind`.

Admission pins the selected model/configuration, so later release, retirement, or alias activation cannot affect that operation. Drain/close cancel only validation waiting for a lease or warmup waiting for capacity; both roll back pins and in-flight count with `ValidationServiceDrainingError`.

Canceled validation performs no evaluation. Canceled warmup retires request-created runtimes and publishes no partial warm result; an operation that already owns all required resources finishes before quiescence.

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

Registered-model tokens expose identity, fingerprint, and lifecycle—not model internals or evaluator methods. Variable names remain application code only.

`service.models.register` enforces source limits, strictly prepares the model, and computes its fingerprint. Invalid input throws `ModelPreparationError`; invalid input or capacity failure creates neither entry nor pin.

Concurrent JVM registrations of identical bytes and preparation ABI are single-flight but return separate pins to one version identity. Releasing one pin does not invalidate others, and different bytes remain different versions even when semantically equivalent.

The portable readonly placement DTO may cross a network, queue, JVM thread, or Node Worker boundary. The request member is deliberately `unknown`, so the service still performs authoritative shape and model-aware decoding rather than trusting a compile-time annotation. The DTO contains no cache and is not a model-bound `DocumentSnapshot`.

| Service limit | Contract |
|---|---|
| Model/config/runtime/document counts | Positive safe integers |
| Source bytes and acquisition timeout | Non-negative safe integers |
| Configuration count | Includes built-in default |
| Per-model/config runtimes | Bounds one exact pool key |
| Per-model runtimes | Bounds all configurations of one version |
| Total runtimes | Bounds the whole service |
| Pre-object transport | Enforce request-byte and JSON-depth limits before constructing the unknown JS value |

Omitting `configuration` selects built-in predefined types, empty custom registries, BMP fallback charset, and a system clock sampled once per request. Model zone, base-year semantics, and charset still come from the model; locale and explicit time remain per-call options.

Fixed/custom clocks are sampled outside the service and passed as immutable time, so time never becomes pooled state.

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

For one `RegisteredInterpreterConfiguration`, the service never invokes `createBindings` concurrently; each returned `InterpreterBindings` belongs to exactly one active interpreter instance. `dispose` is optional, is called exactly once with exclusive access after that instance retires, and is also called after partial construction/evaluation failure when bindings were created. The registered token is thread-safe even though its produced callbacks need not be.

Binding prepares customization dispatch:

| Binding case | Contract |
|---|---|
| Used name | Bind each model-used custom field/condition name to one indexed slot |
| Resolved factory implementation | Store the created implementation |
| Missing name | Store one missing sentinel used by readiness reporting |
| Observable source cardinality | Store a direct constant-name provider; never force a singleton or readiness probe |
| Runtime-resolved capability | Keep that status until normal evaluation invokes the source factory |
| Hot path | Metadata/AST point directly to slots; no service registry, configuration token, string map, reflection, or coordination lock |

`tenantConfiguration.release()` is idempotent: reject future use, retire idle runtimes immediately, finish active leases, then dispose their bindings. Same-service release from creation/evaluation/disposal follows the reentrancy error rule.

`maxInterpreterConfigurations` bounds configuration metadata and model×configuration pool growth.

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

The request selects the registered version and asks for four bounded runtime instances under the default configuration. The returned report is a detached count of instances and stored programs actually prepared.

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

The two registered-model tokens identify immutable prepared versions. The alias is a nominal logical-name token whose active target the service switches atomically.

`activate` is atomic: requests that already acquired V2 finish on V2, while later acquisitions through `ordersAlias` use V3. A live `RegisteredModel` or alias pins its target. `release()` is idempotent, invalidates that token for future calls, and makes the entry evictable only after aliases and active leases stop pinning it. `retire` prevents new acquisition before those active leases finish.

Drain and close include preparation, warmup, binding creation, publication/rollback, active leases, and disposal—not only queued validation. Runtime-owning requests finish; queued requests acquire no lease after draining begins.

Close invalidates service tokens, retires/disposes runtimes, releases service pins, and clears registries before publishing closed. Later token release is harmless, and direct prepared/interpreter objects remain unaffected.

Every race follows linearization order. Earlier admitted work keeps its pinned version/configuration and finishes if it already owns resources; work still waiting rolls back under the sole draining cancellation rule.

Later work sees the new alias target or corresponding lifecycle error before evaluation. Eviction never beats a temporary pin, and disposal occurs only after the last exclusive lease.

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

The coordinator resolves an alias to one exact portable version, pins it, and only then creates the validation message. Later alias activation cannot change the admitted request.

Registration broadcasts model source; each Worker recomputes and verifies the fingerprint, creates local version/configuration/owner identities, and acknowledges readiness before alias activation. Portable routing strings never substitute for local identities.

In one Worker, the default is one `WarmInterpreterLane` per `(PortableModelVersionId, PortableConfigurationId)` because synchronous parsing, decode, and evaluation cannot overlap on that JavaScript thread. A second same-key lane requires a measured reentrancy or ownership reason. D1 and D2 retain separate entries and lanes, so `D1, D2, D1, D2` does not reparse either model.

`WorkerPreparedResource` supports shared-owner and lane-owned strategies. Sharing requires certification of the plan, lazy caches, and codec; lane-owned mode constructs an independent owner/codec per lane.

`isSnapshot` authenticates only that lane’s owner domain. Decode and optional model-bound adapter lookup occur after lane selection, and one lane’s codec never creates snapshots for another owner.

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

`ValidatePlacementsRequest.Builder` is the Java projection of the TypeScript readonly request. Its optional configuration method selects a custom registration; omission uses the service default.

The method is synchronous and concurrently callable from JVM threads, with a distinct exclusive interpreter lease per request.

The service exposes typed `ValidationServiceMetrics` for lifecycle state, admitted/disposal work, model/configuration counts, pool saturation, wait/rejection, active/idle runtimes, preparation, and eviction. Direct callers remain free to retain their own active instances without any registry or pool.

## 9. Configuration, errors, and concurrency

The stable configuration must represent every evaluator customization the kernel honors:

- model-carried time zone, declared/effective base-year semantics, and supported characters through `PreparedModel`;
- fallback legal charset;
- predefined and custom field types;
- custom conditions;
- clock provider on a direct `Interpreter`, or explicit `A12EvaluationTime` per service call;
- locale per call.

Callbacks are synchronous; static types exclude `Promise`, and runtime thenables are rejected, never awaited, as integration failures. In the JVM pool, callbacks belong to one confined interpreter and need not support concurrent invocation.

A future reentrant configuration may require explicitly share-safe callbacks. Callback lifetime and exception conversion remain façade contracts.

Kernel-free registries are created per lane and are never process-global. Returned callbacks preserve source creation cardinality rather than being silently shared, and registry lookup stays outside evaluator hot loops.

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
```

The TypeScript façade is required product work: explicit exports, plain declarations, JS-safe errors, arrays/readonly DTOs, and strict TypeScript consumer tests. The ordinary JVM façade adapts Java collections and uses builders where justified. Neither façade contains evaluator logic or imports a kernel type.

Optional TypeScript boundary adapters, including [Zod integration](ZOD-INTEGRATION-PROPOSAL.md), may validate portable DTOs and project reports. They delegate model-aware decode, authenticate with the exact owner, encode canonically, and preserve `ReportPointer`.

They never enter `commonMain`, customization SPIs, parsing, AST traversal, warm lanes, or JVM APIs, and never become a second evaluator.

Node is the verified JavaScript host. Browser qualification requires a real browser build, bundle smoke test, conformance run, callback contract, and browser performance evidence. A fake async wrapper that still blocks the Node event loop is not useful; worker-thread/browser-worker adapters may be separate later.

### 10.1 Reusing every A12 Kernel evaluator extension

The compatibility requirement is narrow and complete:

> Existing project implementations of every A12 Kernel extension point used during validation or computation must be callable by the kernel-free interpreter.

For A12 Kernel 30.8.1, `DocumentRtCustomExtensionService` exposes exactly `registerCustomFieldTypes(ICustomFieldTypeFactory)` and `registerCustomConditions(ICustomConditionFactory)`. The complete evaluator extension inventory is therefore:

| Kernel extension family | Kernel-free target |
|---|---|
| `ICustomFieldTypeFactory` and its validators | `PredefinedTypeRegistry`, `CustomFieldTypeFactory`, `CustomFieldTypeRegistry`, and `CustomFieldValidator` |
| `ICustomConditionFactory` and its conditions | `CustomConditionFactory`, `CustomConditionRegistry`, and `CustomCondition` |

The Kernel exposes no custom-operator, custom-function, or custom-computation registration point. This proposal therefore defines none.

`ICustomMigrationCode` is a document/model migration hook, not an evaluator extension. It belongs in a future migration API and adapter rather than the Interpreter API.

Legal charset, locale, model time zone, and clock are interpreter configuration inputs rather than project extension interfaces. The core supports them directly; they require no Kernel compatibility adapter.

#### Required artifacts and boundary

Existing extensions are reused through two required, separately packaged artifacts:

| Artifact | Purpose |
|---|---|
| `@a12/interpreter-kernel-compat` | adapt existing TypeScript Kernel field-type and condition factories |
| `a12-interpreter-kernel-compat-jvm` | adapt existing JVM Kernel field-type and condition factories |

The base Kotlin Multiplatform interpreter defines its own license-free extension interfaces. The adapter artifacts may depend on the corresponding Kernel API, so their licensing and distribution are separate from the base interpreter.

```text
existing Kernel extension factories
                 │
                 ▼
       platform compatibility adapter
                 │
                 ▼
       kernel-free KMP registries
                 │
                 ▼
       kernel-free Interpreter
```

The adapters translate callbacks only. They do not expose a second interpreter, accept Kernel documents as validation input, invoke Kernel validation or computation, or adapt computation results back into Kernel documents.

Applications that implement the KMP SPI directly need no Kernel dependency or compatibility artifact. Applications that reuse implementations compiled against Kernel interfaces install the matching adapter.

#### Adapter surface

The adapter accepts existing factories and returns ordinary kernel-free registries:

```ts
interface KernelCustomFieldMessageRenderer {
  render(
    errorText: readonly Localizable[],
    locale: LocaleCode,
  ): string;
}

interface KernelCustomFieldTypeExtensions {
  readonly factory:
    ICustomFieldTypeFactory;
  readonly messages:
    KernelCustomFieldMessageRenderer;
}

interface KernelExtensionFactories {
  readonly customFieldTypes?:
    KernelCustomFieldTypeExtensions;
  readonly customConditions?:
    ICustomConditionFactory;
}

interface AdaptedKernelExtensions {
  readonly predefinedTypes:
    PredefinedTypeRegistry;
  readonly customFieldTypes:
    CustomFieldTypeRegistry;
  readonly customConditions:
    CustomConditionRegistry;
}

declare function adaptKernelExtensions(
  factories: KernelExtensionFactories,
): AdaptedKernelExtensions;

declare const kernelFieldTypes:
  ICustomFieldTypeFactory;
declare const kernelFieldTypeMessages:
  KernelCustomFieldMessageRenderer;
declare const kernelConditions:
  ICustomConditionFactory;

const extensionFactories:
  KernelExtensionFactories = {
    customFieldTypes: {
      factory: kernelFieldTypes,
      messages: kernelFieldTypeMessages,
    },
    customConditions: kernelConditions,
  };
const extensions: AdaptedKernelExtensions =
  adaptKernelExtensions(extensionFactories);
```

The returned registries are supplied through the normal `InterpreterConfiguration.createBindings` contract. Validation and computation continue to accept `DocumentSnapshot` and return the ordinary kernel-free reports.

The TypeScript message renderer turns the Kernel validator’s `Localizable[]` into the core result message for the validation call’s locale. The JVM artifact uses its corresponding source message representation and returns the same KMP registry projections.

Neither artifact adds `validateFull(DocumentV2)`, `validatePart(DocumentV2, ...)`, or `compute(DocumentV2)`.

#### Exact compatibility contract

The adapters preserve every observable extension behavior:

| Extension | Required preservation |
|---|---|
| Custom Field Type | factory lookup, validator lifecycle, declared custom-field validation, predefined-type `Valid`/`Invalid` checks, stored/display mode, effective length bounds, display/internal conversion, rejection identity, message, and localization |
| Custom Condition | factory lookup, invocation order and count, full/partial relevance, formal-error entities, error pointer, repetition coordinates, effective value overrides where the source API exposes them, `true`-means-rule-fires polarity, and exception behavior |

For declared custom fields, validation remains full delegation: the adapter must not add generic length validation around the project validator. Omitted bounds reach the callback as the Kernel defaults `1` and `999`, stored-document validation passes `isDisplayValue=false`, and one relevant value is validated once per formal pass.

TypeScript and the classic JVM interface use the Kernel’s `IData`/`IIdentifier`-shaped condition contract. The adapter supplies a read-only compatibility view backed by the current `DocumentSnapshot`; it does not copy the document or call a Kernel evaluator.

Modern JVM conditions that require `DocumentV2` need a JVM-only compatibility view. Supporting that callback shape does not make `DocumentV2` a core document type or a public interpreter input.

An adapter must support existing extension implementations unchanged. Similar-looking replacement interfaces are insufficient evidence; compatibility is established with real project implementations and differential tests.

#### Lifecycle and performance

- Resolve every model-used extension name while binding an interpreter lane.
- Create extension instances with the same factory cardinality and lifecycle as the source Kernel contract.
- Store direct indexed callback slots in prepared executable nodes.
- Keep callback instances lane-confined unless their source contract explicitly guarantees safe sharing.
- Perform no reflection, registry search, adapter discovery, Kernel class loading, document copying, or wrapper allocation per AST visit.
- Keep the base no-customization path free of adapter code and dependencies.

Each supported Kernel release has an extension-inventory check. If a later Kernel adds another evaluator extension point, the matching core SPI and platform adapters are required before that release can claim extension compatibility.

## 11. Correctness and performance

The interpreter remains a clean-room, kernel-free implementation and must match the applicable kernel behavior exactly. The stable façade delegates to the existing evaluator; it does not create a parallel execution path.

API completeness is guarded by source-backed capability ledgers, not by a short hand-authored exception list.

The audited universes include:

- the model/meta-model schema;
- the document/configuration API and public extension SPIs;
- legal-charset, custom-type, and custom-condition SPIs;
- validation and computation result interfaces;
- the live operator catalog for contextual operator use.

For each family, the ledger records the kernel version, source universe, evidence, public API disposition, exact gap when parity-gated, and kernel-upgrade trigger. A family may claim completeness only when that universe and its guard are explicit.

Stored-program performance keeps the current invariants:

- stored programs parse at most once per active runtime, or once per certified shared immutable plan, and remain lazy by default;
- repeated evaluation never reparses;
- the optimized AST and traversal remain unchanged;
- custom field-type declarations and custom-condition AST nodes hold pre-bound indexed dispatch slots rather than searching registries during evaluation;
- the empty-customization path has no registry scan, wrapper allocation, pointer mapping, or platform class loading;
- model-static indexes/scopes are shared or copied only where semantics and measured cost permit;
- unknown-transport validation, model-aware document decode, registry wait, evaluation, encoding, and optional projection cost are measured separately;
- no process-global initialization or validation-result cache is introduced.

Model reuse needs separate sequential and concurrent gates:

1. Retain warm active instances for D1 and D2, alternate `D1-A, D2-A, D1-A, D2-A`, and prove both remain warm with isolated-baseline-equivalent throughput and p95.
2. Retain D1 instances with different clocks, custom registries, and fallback charsets; prove correct different results with no configuration contamination.
3. Drive multiple concurrent requests for the same hot model through several pooled instances, plus D1/D2 interleaving, and prove scaling without concurrent entry into a confined instance.
4. Drive the same D1/D2 pattern through a bounded Node Worker pool and prove that every Worker retains one warm lane per hot local key, resolves portable versions to local identities correctly, and never transports runtime objects across Workers.

Customization adds separate release gates rather than hiding its cost inside one aggregate benchmark:

1. Compare a no-customization model with the current interpreter baseline and require no measurable regression in parser cost, AST traversal, startup, warmup, steady-state latency, throughput, allocation, or retained memory.
2. Measure native custom field validators and native custom conditions independently on callback-heavy documents, including full validation, partial validation, and computation paths.
3. Prove registry lookup occurs only while binding and the callback hot path uses direct precomputed slots without reflection, coordination locks, or per-node configuration allocation.
4. Repeat cold/warm D1/D2 interleaving and concurrent JVM-lane/Node-Worker tests with distinct custom configurations, proving no callback, override, locale, or cache crosses a lane or request.

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

The next customization-bearing increment contains:

- `validateFull`, `validatePart`, and `compute`;
- the native Custom Field Type and Custom Condition SPIs;
- exact callback laws;
- pre-bound lane dispatch.

Zod scalar integration may proceed independently after the Operator API. Zod transport work needs only the minimum transport/ownership foundation.

### 12.2 Complete product boundary

| Core contract | Optional after the core | Deliberately outside/deferred |
|---|---|---|
| strict expanded-model preparation | bounded model registry/runtime pool | process-global/unbounded cache |
| prepared owner versus exclusive active interpreter split | explicit eager pool warmup | mandatory shared/reentrant evaluator |
| read-only model introspection and caller-owned owner-aware application metadata | nested document-JSON adapter | dynamic model/rule authoring |
| strict `unknown` decode, typed fast path, authenticity guard, and versioned lossless encode | document patch conveniences | Kernel document types in the core |
| frozen request document/field-aware `DocumentCellValue` builder | persistent structural-sharing form editor | promise-returning callbacks |
| direct callback-free storage access plus separate correlated formal inspection | candidate-rule preview | candidate computations |
| kernel-free custom field-type/condition SPIs, immutable registries, and pre-bound lane slots | — | kernel types or process-global registries in the core |
| full/relevant-set validation, lossless report axes/pointers, and configuration support report | number expression observer | arbitrary context-free expression claim |
| stored-rule row outcomes | tracing/explanation if demanded | kernel consistency certification in TypeScript |
| lossless compute, compute-field, compute-and-apply outcomes and deltas | browser after qualification | browser support by assumption |

This product is feasible, but it is not a rename exercise. The evaluator-backed validation/computation core is medium productization work; the definition/active-instance split and bounded registry/pool require explicit ownership but do not require one shared reentrant evaluator; a performant immutable editable document surface is a distinct substantial capability.

The customization architecture is also feasible. Custom Field Types are primarily scalar validation and conversion; Custom Conditions require the complete, exact document/relevance/formal-state callback view.

Before publication, the following contracts need implementation and evidence:

- strict `unknown` ingress and resource limits;
- lossless placement round trips and owner-aware authenticity;
- direct callback-free storage reads;
- exact field-aware typed rendering and custom-field conversion;
- request-document cache confinement and strict prepared-input rejection;
- model-zone clock sampling;
- structured finding and computation channels;
- complete custom field-type and custom-condition SPIs;
- pre-bound customization slots and support reporting;
- repeated-row rule outcomes and interpreter-binding factories;
- Worker-local rollout and ownership;
- JVM pool backpressure and lifecycle races;
- the exact Node package ABI.

Persistent structural sharing, candidate programs, and browser support should not hold the core validation-service API hostage.
