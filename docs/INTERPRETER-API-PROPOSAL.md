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

This proposal defines the public API for the clean-room Kotlin Multiplatform A12 interpreter. TypeScript on Node is the primary surface; Java/JVM is a secondary projection of the same Kotlin implementation.

The base Interpreter and [Operator API](OPERATOR-API-PROPOSAL.md) artifacts are kernel-free and MIT-licensed. They do not import, link, reflect over, bundle, or invoke an A12 Kernel artifact.

Two separately packaged kernel-compatibility adapters are required product artifacts because existing projects already implement Custom Field Types and Custom Conditions against the TypeScript and JVM kernel interfaces. Those adapters depend on the applicable kernel API and therefore have their own licensing and distribution boundary.

## Motivation

TypeScript and JVM applications need to load A12 models, preserve real document states, validate and compute repeatedly, and reuse project-specific Custom Field Types and Custom Conditions without embedding the licensed kernel in the base runtime.

The API must therefore satisfy four needs together:

| Need | Rationale |
|---|---|
| Exact A12 behavior | Existing models and project extensions must behave like the production kernel |
| License-free core | Browser, Node, and ordinary JVM consumers must not inherit a kernel dependency |
| Familiar project integration | Required compatibility adapters let existing TypeScript and JVM customization libraries continue to work |
| Fast repeated evaluation | Services and form UIs must reuse prepared work across `D1, D2, D1, D2` and concurrent requests |

## 1. Recommendation

Expose four core concepts:

| Concept | Responsibility |
|---|---|
| `PreparedModel` | Strictly load one expanded model and retain model-only indexes and prepared programs |
| `Interpreter` | Bind one runtime configuration and evaluate documents |
| `DocumentSnapshot` | Preserve one immutable model-owned document, including malformed and repeated state |
| `ValidationService` | Optionally coordinate registered models and exclusive warm interpreter lanes for backend use |

```text
expanded model ──► PreparedModel ──► Interpreter lane A ──► D1, D2, D1
                        │
                        └──────────► Interpreter lane B ──► concurrent request
```

The core supports full validation, partial validation, computations, model inspection, direct storage inspection, Custom Field Types, and Custom Conditions. Direct scalar operations remain in the independent Operator API and require no interpreter.

## 2. Scope

### Required core surface

- strict expanded-model preparation;
- immutable model metadata and model identity;
- lossless document decode, encode, construction, and storage inspection;
- full and partial validation;
- stored computation, application, and support reporting;
- kernel-free Custom Field Type and Custom Condition SPIs;
- exclusive active-interpreter ownership with fast repeated and interleaved evaluation;
- TypeScript and Java projections from one Kotlin `commonMain` implementation.

### Required compatibility surface

- TypeScript adapter for existing `Document`, `GeneratedCodeRtConfig`, `ICustomFieldTypeFactory`, and `ICustomConditionFactory` implementations;
- JVM adapter for existing `DocumentV2`, `DocumentProcessingConfig`, and modern JVM customization factories;
- exact source-interface callback inputs, lifecycle, factory cardinality, and result semantics;
- no kernel validation or computation delegation.

### Separate optional surface

- Zod transport and scalar adapters from the [Zod integration proposal](ZOD-INTEGRATION-PROPOSAL.md);
- bounded registry/pool convenience when an application serves many models;
- persistent structural-sharing document editing;
- candidate-rule and symbolic-expression preview.

### Excluded

- model authoring or consistency checking;
- a `DocumentV2` dependency in the core;
- browser support before browser qualification;
- asynchronous callbacks;
- a Custom Operator SDK;
- process-global model, locale, clock, callback, or document state.

## 3. Semantic and licensing boundary

The A12 kernel is the semantic oracle. The interpreter must match the normative dynamic-Groovy behavior exactly and match static-Java code generation wherever the two agree; a legal split is recorded and dynamic-Groovy remains normative.

The implementation remains clean-room:

```text
kernel behavior and differential evidence
                  │
                  ▼
       original Kotlin implementation
                  │
          ┌───────┴────────┐
          ▼                ▼
    TypeScript API      Java API
```

Kernel source may be studied to understand behavior, but source expressions are not copied or transliterated. The base evaluator never calls the kernel at runtime.

## 4. Ownership model

### 4.1 Model and owner identities

Model-owned objects carry two opaque identities:

| Identity | Meaning |
|---|---|
| `ModelVersionIdentity` | One exact locally prepared model version |
| `ModelOwnerIdentity` | The runtime domain allowed to create and consume snapshots, pointers, and prepared handles |

Equal model text, fingerprints, paths, or version labels do not make objects from different owners interoperable. A foreign snapshot or pointer fails with `ModelOwnerMismatchError` before evaluation.

```ts
declare const modelVersionIdentityBrand: unique symbol;
declare const modelOwnerIdentityBrand: unique symbol;

type ModelVersionIdentity = {
  readonly [modelVersionIdentityBrand]: never;
};

type ModelOwnerIdentity = {
  readonly [modelOwnerIdentityBrand]: never;
};

interface ModelOwned {
  readonly modelVersion: ModelVersionIdentity;
  readonly owner: ModelOwnerIdentity;
}

interface ModelOwnerMismatchError extends Error {
  readonly code: "A12_MODEL_OWNER_MISMATCH";
  readonly expected: ModelOwnerIdentity;
  readonly actual: ModelOwnerIdentity;
}
```

TypeScript brands prevent accidental structural assignment only. Runtime checks use library-owned identity tokens and do not rely on `instanceof`.

### 4.2 Prepared and active state

`PreparedModel` contains only model-derived state:

- parsed model structure;
- paths, repetition scopes, and metadata;
- stored rule and computation plans;
- model-declared time zone, base year, and charset;
- model-only indexes and lazy parse caches.

An `Interpreter` contains configuration-bound state:

- custom type and condition bindings;
- fallback charset and predefined types;
- clock provider;
- evaluation caches and callback context.

Documents, locale, sampled time, results, and per-call overlays never become prepared-model state.

```ts
type ExpandedModelJson = string;
type ModelFingerprint = string;

interface PreparedModel extends ModelOwned {
  readonly fingerprint: ModelFingerprint;
  readonly model: ModelMetadata;
  readonly documents: PreparedDocumentApi;

  createInterpreter(
    configuration?: InterpreterConfiguration,
  ): Interpreter;
}

interface PreparedModelConstructor {
  loadExpandedJson(source: ExpandedModelJson): PreparedModel;
  tryLoadExpandedJson(
    source: ExpandedModelJson,
  ): A12Result<PreparedModel, ModelPreparationError>;
}

declare const PreparedModel: PreparedModelConstructor;
```

The public contract does not require one sharing strategy. A prepared plan may be immutable and shared, Worker-confined, or duplicated per interpreter lane when sharing would harm exactness, startup, or throughput.

## 5. Model metadata and pointers

Metadata is immutable and does not expose internal evaluator classes:

```ts
type LocaleCode = string;
type FieldKind =
  | "string"
  | "number"
  | "boolean"
  | "confirm"
  | "date"
  | "time"
  | "date-time"
  | "date-range";

type Requiredness =
  | "absolute"
  | "relative-to-parent"
  | "not-required";

interface CommonFieldConstraints {
  readonly declaredRequiredness: Requiredness;
  readonly effectiveRequiredness: Requiredness;
  readonly global: boolean;
}

type FieldDeclaration =
  | {
      readonly kind: "string";
      readonly minLength?: number;
      readonly maxLength?: number;
      readonly pattern?: string;
      readonly valueValidationEnabled: boolean;
    }
  | {
      readonly kind: "number";
      readonly scale: number;
      readonly minimum?: A12Number;
      readonly maximum?: A12Number;
    }
  | {
      readonly kind: "enumeration";
      readonly valueCodes: readonly string[];
    }
  | {
      readonly kind: "custom";
      readonly typeName: string;
      readonly minLength?: number;
      readonly maxLength?: number;
    }
  | {
      readonly kind:
        | "boolean"
        | "confirm"
        | "date"
        | "date-fragment"
        | "optional-date"
        | "time"
        | "date-time"
        | "date-range";
    };

interface RepetitionLevel {
  readonly groupPath: string;
  readonly maximum?: number;
}

interface ModelMetadata extends ModelOwned {
  readonly documentModelId: string;
  readonly timeZone: A12TimeZone;
  readonly declaredBaseYear?: A12BaseYear;
  readonly effectiveDateCompletionBaseYear: A12BaseYear;

  requireField(path: string): FieldMetadata;
  requireGroup(path: string): GroupMetadata;
  fields(): readonly FieldMetadata[];
  groups(): readonly GroupMetadata[];
}

interface FieldMetadata extends ModelOwned {
  readonly path: string;
  readonly kind: FieldKind;
  readonly declaration: FieldDeclaration;
  readonly constraints: CommonFieldConstraints;
  readonly repetition: readonly RepetitionLevel[];
  readonly customTypeName?: string;
  readonly computed: boolean;
}

interface GroupMetadata extends ModelOwned {
  readonly path: string;
  readonly repeatable: boolean;
  readonly maximum?: number;
  readonly indexField?: FieldMetadata;
}
```

`documentModelId` is the exact authored/generated model identity supplied to existing TypeScript Custom Conditions. It is not a service alias, fingerprint, or caller-invented routing value.

Concrete document pointers and report pointers remain different:

```ts
interface DocumentPointerBase extends ModelOwned {
  readonly path: string;
  readonly coordinates: readonly number[];
}

interface FieldPointer extends DocumentPointerBase {
  readonly entityKind: "field";
}

interface CustomFieldPointer extends FieldPointer {
  readonly customTypeName: string;
}

interface GroupPointer extends DocumentPointerBase {
  readonly entityKind: "group";
}

type DocumentPointer = FieldPointer | GroupPointer;

interface RelevantEntityPointer extends ModelOwned {
  readonly path: string;
  readonly entityKind: "field" | "group";
  readonly coordinates:
    readonly (number | "all")[];
}

interface ReportPointer extends ModelOwned {
  readonly path: string;
  readonly entityKind: "field" | "group";
  readonly coordinates:
    readonly (number | "all" | "unknown")[];
}
```

`ReportPointer` preserves wildcard and partially known repetitions. Results must not fabricate concrete coordinates when the evaluator does not know them.

### 5.1 Model preparation and metadata invariants

| Invariant or trap | Contract |
|---|---|
| Prepared input | V1 accepts expanded, self-contained, name-path-keyed DM-JSON only; unresolved includes, type definitions, external identifiers, and preparation residue are rejected |
| Preparation failure | `ModelPreparationError` contains stable codes, model paths where known, and corrective messages; `tryLoadExpandedJson` returns the same diagnostics without throwing |
| Fingerprint | The library hashes exact source bytes plus preparation-format/ABI identity; semantically equivalent JSON may miss the cache, but different source must never collide through caller-controlled identity |
| Document model ID | `documentModelId` is preserved from the model and is distinct from the fingerprint, owner identity, service alias, and portable routing ID |
| Model time zone | Unsupported zones reject preparation; there is no silent UTC, host-zone, or partially compatible fallback |
| Field kind versus declaration | Enumeration and Custom Field Type declarations may evaluate as string-shaped values, but metadata must retain their declared enumeration/custom identity and constraints |
| Requiredness | Metadata distinguishes authored and effective requiredness; an index field authored as not required becomes relative-to-parent, while explicit requiredness remains explicit |
| Requiredness meaning | Absolute requiredness starts at the lowest instantiated repeatable ancestor; relative requiredness starts when the immediate parent is filled, and an explicitly created empty parent row counts as filled |
| Repetition metadata | Every repeatable ancestor and declared maximum remains visible; absence of a maximum is not converted into an arbitrary implementation limit |
| Base year | `declaredBaseYear` controls the `BaseYear` expression; a separate effective completion year uses the kernel default `2000` for yearless date fragments and must not make `BaseYear` legal |
| Date precision | Unsupported month-optional or year-optional `DateType` precision is rejected rather than coerced to a complete or day-optional date |
| Model metadata | Public metadata is immutable and traversable; internal `ModelDef`, maps, parse nodes, and evaluator caches are not ABI |

## 6. Documents

### 6.1 Canonical transport

The canonical portable document is an explicit placement envelope:

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

This representation preserves:

- an absent field by omitting its placement;
- a present-empty field with `raw: ""`;
- malformed raw text without coercion;
- explicit empty group rows;
- sparse and nested repetitions.

### 6.2 Snapshot API

```ts
interface PreparedDocumentApi extends ModelOwned {
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

  isSnapshot(
    value: unknown,
  ): value is DocumentSnapshot;

  create(): DocumentBuilder;

  dayOptionalDate(
    field: FieldMetadata,
    year: A12Integer,
    month: A12Integer,
  ): OptionalDateCellValue;

  dateFragment(
    field: FieldMetadata,
    components: DateFragmentComponents,
  ): DateFragmentCellValue;
}

interface DocumentSnapshot extends ModelOwned {
  readonly storage: DocumentStorage;

  instantiatedRows(
    group: GroupMetadata,
    within?: GroupPointer,
  ): readonly GroupPointer[];
}

interface OptionalDateCellValue extends ModelOwned {
  readonly kind: "optional-date";
  readonly year: A12Integer;
  readonly month: A12Integer;
}

type DateFragmentComponents =
  | {
      readonly kind: "year-month";
      readonly year: A12Integer;
      readonly month: A12Integer;
    }
  | {
      readonly kind: "year";
      readonly year: A12Integer;
    }
  | {
      readonly kind: "month";
      readonly month: A12Integer;
    }
  | {
      readonly kind: "month-day";
      readonly month: A12Integer;
      readonly day: A12Integer;
    };

interface DateFragmentCellValue extends ModelOwned {
  readonly kind: "date-fragment";
  readonly granularity:
    | "year-month"
    | "year"
    | "month"
    | "month-day";
  readonly resolvedValue: A12Date;
}

type DocumentCellValue =
  | A12Value
  | OptionalDateCellValue
  | DateFragmentCellValue;

interface DocumentBuilder extends ModelOwned {
  addGroup(pointer: GroupPointer): void;
  removeGroup(pointer: GroupPointer): void;
  putRaw(pointer: FieldPointer, raw: string): void;
  putValue(
    pointer: FieldPointer,
    value: DocumentCellValue,
  ): void;
  removeField(pointer: FieldPointer): void;
  freeze(): DocumentSnapshot;
}
```

`decodePlacements` is the authoritative `unknown` boundary and aggregates structural and model-aware input errors. `readPlacements` is the fast path for an already validated DTO; a TypeScript annotation alone does not establish trust.

### 6.3 Direct storage inspection

Reading stored data is not validation:

```ts
type CellStorage =
  | {readonly kind: "absent"}
  | {readonly kind: "empty"; readonly raw: ""}
  | {readonly kind: "present"; readonly raw: string};

type StoredValue =
  | {readonly kind: "none"}
  | {readonly kind: "value"; readonly value: A12Value}
  | {readonly kind: "unavailable"};

interface StoredCellInspection {
  readonly pointer: FieldPointer;
  readonly storage: CellStorage;
  readonly value: StoredValue;
}

interface DocumentStorage extends ModelOwned {
  cell(pointer: FieldPointer): StoredCellInspection;
  cells(
    pointers: readonly FieldPointer[],
  ): readonly StoredCellInspection[];
}

type CellEvaluationValue =
  | {readonly kind: "value"; readonly value: A12Value}
  | {
      readonly kind: "not-interned";
      readonly reason:
        | "no-stored-value"
        | "formally-invalid"
        | "raw-type"
        | "incomplete-date"
        | "unsupported";
    };

interface InspectionDiagnostic {
  readonly code: string;
  readonly message?: string;
  readonly errorKey?: string;
}

interface CellInspection extends StoredCellInspection {
  readonly formalValidity:
    | {readonly kind: "valid"}
    | {
        readonly kind: "invalid";
        readonly diagnostics:
          readonly InspectionDiagnostic[];
      }
    | {
        readonly kind: "unsupported";
        readonly typeName: string;
      }
    | {
        readonly kind: "invalid-and-unsupported";
        readonly diagnostics:
          readonly InspectionDiagnostic[];
        readonly typeName: string;
      };
  readonly evaluationValue: CellEvaluationValue;
}

interface DocumentInspection extends ModelOwned {
  cell(pointer: FieldPointer): CellInspection;
  cells(
    pointers: readonly FieldPointer[],
  ): readonly CellInspection[];
}
```

`DocumentStorage` invokes no Custom Field Type, Custom Condition, rule, or computation and samples no clock. Model-aware formal inspection is a separate `Interpreter.inspectDocument` call.

### 6.4 Typed use

```ts
declare const expandedModelJson: ExpandedModelJson;
declare const requestBody: unknown;
declare const amountPointer: FieldPointer;

const prepared: PreparedModel =
  PreparedModel.loadExpandedJson(expandedModelJson);

const limits: DocumentDecodeLimits = {
  maxGroupPlacements: 10_000,
  maxFieldPlacements: 100_000,
  maxCoordinatesPerPlacement: 16,
  maxPathCodeUnits: 1_024,
  maxRawCodeUnitsPerField: 1_000_000,
  maxTotalRawCodeUnits: 16_000_000,
  maxModelNestingDepth: 32,
};

const snapshot: DocumentSnapshot =
  prepared.documents.decodePlacements(
    requestBody,
    {limits},
  );

const stored: StoredCellInspection =
  snapshot.storage.cell(amountPointer);
```

`expandedModelJson` is the caller-supplied expanded model, `requestBody` is untrusted input, and `amountPointer` must belong to `prepared`. Every local variable has its explicit public type.

### 6.5 Document semantic invariants and gotchas

| Invariant or trap | Contract |
|---|---|
| Absent, empty, malformed | Absent has no field placement, present-empty has `raw: ""`, and malformed non-empty text remains stored; none is translated to `undefined`, `null`, a default, or a valid A12 scalar |
| Empty rows | Explicit group placements preserve instantiated empty and sparse rows even when they contain no field value |
| Coordinates | Repetition coordinates are positive safe integers with exact arity; a coordinate above the declared maximum is retained so formal validation can report over-repetition |
| Unknown transport keys | Canonical V1 decode rejects unknown properties rather than silently stripping misspellings |
| Input locations | Every input diagnostic keeps its transport path and, when known, a separate attempted model path; the two are never conflated |
| Resource limits | Request-byte and JSON-depth limits run before or during parsing; placement, coordinate, path, raw-text, total-text, and model-depth limits run before evaluator hot paths |
| Raw write | `putRaw` preserves lexical text exactly, including values that cannot be decoded |
| Typed write | `putValue` stores only an exactly representable field value; it never rounds, truncates, drops date components, or silently changes kind |
| Number write | Natural decimal scale must fit the field scale; insignificant trailing zeros may canonicalize, but excess significant fractional digits are rejected |
| Partial dates | A day-optional date and a date fragment remain model-owned non-`A12Value` types; no fictitious day is created |
| Date fragments | Missing years use the effective completion year; `"yyyy"`/`"yyyy-MM"` keep their year, while `"MM"`/`"MM-dd"` use the declared year or `2000` |
| Partial date ranges | Missing start components complete to the earliest endpoint and missing end components to the latest endpoint |
| Storage versus inspection | `snapshot.storage` never validates or calls customization; `inspectDocument` runs one complete configured formal pass and returns an indexed detached result |
| Inspection callback count | One inspection invokes a configured Custom Field Type at most once per concrete visited cell; later `DocumentInspection.cell` calls never reinvoke it |
| Invalid and unsupported | A cell can be both formally invalid and unsupported; inspection retains both facts, while evaluation treats the formal invalidity as the immediate operand-suppression reason |
| String storage versus evaluation | Storage preserves CRLF and lone CR; evaluator ingestion converts each CRLF pair to LF exactly once and leaves lone CR unchanged |
| Raw/no-value fields | A formally valid no-value-validation string can remain `raw-type` and unavailable to value operators while still participating in presence checks |
| Snapshot caches | Formal, overlay, and computation caches never cross snapshots; `D1, D2, D1, D2` cannot leak request state |
| Round trip | `encodePlacements(decodePlacements(input))` preserves every accepted document state modulo documented canonical ordering |

## 7. Interpreter configuration and customization

### 7.1 Configuration

```ts
declare const legalCharsetBrand: unique symbol;
declare const predefinedTypeRegistryBrand:
  unique symbol;

interface LegalCharset {
  readonly [legalCharsetBrand]: never;
}

interface PredefinedTypeRegistry {
  readonly [predefinedTypeRegistryBrand]: never;
}

interface EvaluationClockProvider {
  capture(): A12EvaluationTime;
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
```

`createBindings` runs once per active interpreter and must return an exclusive binding set. The zero-customization path needs no configuration and uses built-in predefined types, empty custom registries, the model charset or BMP fallback, and a system clock sampled once per evaluation.

### 7.2 Custom Field Types

```ts
interface CustomFieldValidationParameters {
  readonly locale: LocaleCode;
  readonly minLength?: number;
  readonly maxLength?: number;
}

type CustomFieldValidationResult =
  | {readonly kind: "accepted"}
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
  create(
    customFieldTypeName: string,
  ): CustomFieldValidator | undefined;
}
```

Snapshots store custom values in internal form. UI or import code explicitly calls the conversion method before writing display text.

### 7.3 Custom Conditions

```ts
interface CustomConditionValueOverride {
  readonly pointer: FieldPointer;
  readonly raw: string;
}

interface CustomConditionDocumentView extends ModelOwned {
  readonly snapshot: DocumentSnapshot;
  readonly model: ModelMetadata;

  stored(pointer: FieldPointer): StoredCellInspection;
  effectiveValue(pointer: FieldPointer): CellEvaluationValue;
  instantiatedRows(
    group: GroupMetadata,
    within?: GroupPointer,
  ): readonly GroupPointer[];
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
  create(
    customConditionName: string,
  ): CustomCondition | undefined;
}
```

`true` means the error condition is met and the containing rule fires. Full validation supplies `relevantEntities: undefined`; partial validation preserves the platform’s exact relevance, wildcard, global-field, formal-error, and empty-set behavior.

The view exposes effective values so TypeScript value overrides affect formal checks, rules, message interpolation, and Custom Conditions consistently. It does not expose an untyped context map.

### 7.4 Callback guarantees

- callbacks are synchronous and Promise-returning callbacks fail as integration errors;
- a callback exception is not ordinary rejection, `false`, or unsupported semantics;
- one callback set belongs to one exclusive interpreter lane unless a future contract explicitly proves sharing safe;
- recursive evaluation on the same lane fails before changing evaluation state;
- native registry names bind to direct slots outside AST traversal;
- adapters preserve the source factory’s observable invocation cardinality instead of forcing every factory into a singleton;
- there is no Custom Operator registration mechanism.

### 7.5 Customization invariants and gotchas

| Invariant or trap | Contract |
|---|---|
| Core ownership | The kernel-free SPI is authoritative inside the interpreter; platform kernel interfaces appear only in the required compatibility artifacts |
| Registry construction | Explicit registries reject empty and duplicate names; a factory returns `undefined` for an unsupported name instead of throwing |
| Resolution time | Native names used by the model resolve when a lane binds and become indexed direct slots; evaluation performs no name-map scan |
| Source factory cardinality | If an existing platform factory creates per use, the adapter slot stores a constant-name provider and preserves that cardinality; readiness must not probe the factory and accidentally change behavior |
| Custom value form | Canonical snapshots store internal form; display text is converted explicitly, and failed conversion retains the supplied text plus its error message |
| Validation result | Normal Custom Field Type rejection retains the project `errorKey` and message; it is distinct from callback failure |
| Validation parameters | Locale, minimum length, maximum length, and `isDisplayValue` follow the exact target-platform law; Java and TypeScript adapters are audited separately |
| TypeScript localization | Existing TypeScript validators return `Localizable[]`; canonical string reports require an explicit renderer and locale, while an exact native result requires a sidecar retaining the original objects |
| Custom Condition polarity | `true` fires the containing rule; the callback is not an arbitrary refinement and does not return `A12Truth` |
| Custom Condition inputs | The callback receives no invented expression arguments, locale, or time; it receives the document view, model ID, relevance, formally incorrect entities, error pointer, and overrides |
| Full relevance | TypeScript full validation passes `undefined`, not an empty array |
| Partial relevance | TypeScript preserves the caller’s entities after identity conversion/deduplication and global-field inclusion; group selectors are not pre-expanded for the callback and wildcard forms remain visible |
| Empty partial set | A truly empty effective relevance set short-circuits before invoking a Custom Condition; an originally empty set can become non-empty through global-field inclusion |
| JVM relevance | JVM set conversion and empty-set behavior have their own source-backed laws and are not inferred from TypeScript |
| Formal-error pointers | The adapter preserves the platform’s exact formally incorrect set and partially known error pointer rather than narrowing them to convenient concrete fields |
| Overrides | TypeScript conditions receive the unchanged original `Document` and original `EntityInstanceValueMapping`; they remain responsible for consulting that mapping as their source interface requires |
| Async result | A runtime thenable is rejected at the callback boundary, never awaited, and retires the affected lane |
| Exception | A thrown callback becomes `InterpreterIntegrationError`, preserves its cause, and never becomes rejection, `false`, or unsupported |
| Reentrancy | Same-lane validation, computation, inspection, or conversion from inside a callback fails before time sampling, cache mutation, or adapter-context replacement |
| Cleanup | Adapter native document, mappings, presentation state, and overlays are cleared in `finally`, including nested failure |

## 8. Interpreter API

```ts
interface ValidationOptions {
  readonly locale?: LocaleCode;
  readonly evaluationTime?: A12EvaluationTime;
}

interface ComputeOptions {
  readonly locale?: LocaleCode;
  readonly evaluationTime?: A12EvaluationTime;
}

interface InspectionOptions {
  readonly locale?: LocaleCode;
}

type OptionalDateBoundary = "earliest" | "latest";

interface Interpreter extends ModelOwned {
  supportReport(): InterpreterSupportReport;
  captureEvaluationTime(): A12EvaluationTime;

  inspectDocument(
    snapshot: DocumentSnapshot,
    options?: InspectionOptions,
  ): DocumentInspection;

  resolveOptionalDate(
    value: OptionalDateCellValue,
    boundary: OptionalDateBoundary,
  ): A12Date;

  convertCustomFieldDisplayToInternal(
    pointer: CustomFieldPointer,
    displayValue: string,
  ): CustomFieldConversionResult;

  convertCustomFieldInternalToDisplay(
    pointer: CustomFieldPointer,
    internalValue: string,
  ): CustomFieldConversionResult;

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
    options?: ValidationOptions,
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
}
```

All methods are synchronous. Results are detached and immutable; no result contains live interpreter state.

### 8.1 Validation example

```ts
declare const prepared: PreparedModel;
declare const configuration: InterpreterConfiguration;
declare const snapshot: DocumentSnapshot;
declare const relevant:
  readonly RelevantEntityPointer[];

const interpreter: Interpreter =
  prepared.createInterpreter(configuration);

const options: ValidationOptions = {
  locale: "en_US",
};

const fullReport: ValidationReport =
  interpreter.validateFull(snapshot, options);

const partialReport: ValidationReport =
  interpreter.validatePart(
    snapshot,
    relevant,
    options,
  );
```

Partial validation changes operand visibility and suppression. It is not full validation followed by message filtering, and its input is the complete relevance set rather than only the fields changed since the previous call.

### 8.2 Time

The clock is sampled once at public evaluation entry. Every rule and computation in that call observes the same instant, projected into the prepared model’s time zone.

An explicit `A12EvaluationTime` makes several calls one deterministic application transaction:

```ts
declare const interpreter: Interpreter;
declare const snapshot: DocumentSnapshot;

const evaluationTime: A12EvaluationTime =
  interpreter.captureEvaluationTime();

const computeOptions: ComputeOptions = {
  locale: "en_US",
  evaluationTime,
};

const validationOptions: ValidationOptions = {
  locale: "en_US",
  evaluationTime,
};

const computation: ComputeAndApplyResult =
  interpreter.computeAndApply(
    snapshot,
    computeOptions,
  );

if (computation.kind === "applied") {
  const report: ValidationReport =
    interpreter.validateFull(
      computation.snapshot,
      validationOptions,
    );
}
```

No model, lane, or global cache freezes `Today` or `Now`.

### 8.3 Time invariants and gotchas

| Invariant or trap | Contract |
|---|---|
| One sample | A public validation, rule, computation, or combined transaction samples its clock exactly once |
| Model projection | A supplied instant is projected into the prepared model’s time zone; its attached zone is not treated as an instruction to override the model zone |
| Interleaved models | The same instant may produce different local dates for D1 and D2 when their model zones differ |
| Explicit time | `options.evaluationTime` bypasses the configured clock and can be reused across computation and validation |
| Unsupported zone | Preparation rejects an unsupported model zone; evaluation never silently uses UTC or the host zone |
| Base year | Model-zone time and date-fragment completion do not make an undeclared `BaseYear` expression legal |
| Locale | Locale is per call and affects messages, not time-zone choice; no process-global locale exists |
| Compatibility clock | A platform test-date facility such as TypeScript `currentDateForTest` maps to this exact call-scoped law unless an explicit evaluation time wins |

## 9. Result semantics

### 9.1 Validation

```ts
type ValidationSeverity = "error" | "warning" | "info";
type FindingPolarity = "value" | "omission";

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

interface UnsupportedExecutableProgram {
  readonly kind:
    | "rule"
    | "computation"
    | "expression"
    | "custom-condition";
  readonly path?: string;
  readonly reasonCode: UnsupportedReasonCode;
  readonly detail: string;
}

interface UnsupportedCustomFieldProgram {
  readonly kind: "custom-field-type";
  readonly path?: string;
  readonly reasonCode: "missing-custom-field-type";
  readonly detail: string;
  readonly pointer: CustomFieldPointer;
  readonly typeName: string;
}

type UnsupportedProgram =
  | UnsupportedExecutableProgram
  | UnsupportedCustomFieldProgram;

interface ValidationReport {
  readonly findings: readonly ValidationFinding[];
  readonly unsupported: readonly UnsupportedProgram[];
  readonly noErrorOccurred: boolean;
  readonly fullySupported: boolean;
}
```

`noErrorOccurred` answers the kernel severity question: warnings and information do not make it false. `fullySupported` independently states whether every requested program and configured capability was evaluated.

A complete clean result requires both:

```ts
declare const report: ValidationReport;

const accepted: boolean =
  report.noErrorOccurred &&
  report.fullySupported;
```

Formal findings do not receive invented authored rule paths. Messages are presentation; consumers use stable codes and structured pointers.

Locale handling is exact:

| Message source | Lookup |
|---|---|
| Authored rule | Exact locale-key lookup; a missing entry produces `message: ""`, not a language or model-default fallback |
| Field-label placeholder | Exact locale key, then the field’s final path segment when absent or blank |
| Interpreter-owned formal message | German when the locale starts with `de`; otherwise English |

Formal and authored findings are a discriminated union so a formal finding cannot accidentally acquire a fabricated `rulePath`. A custom-field rejection retains its project `errorKey` in `diagnostic`; built-in reason codes remain a closed interpreter vocabulary.

### 9.2 Stored-rule evaluation

```ts
interface FormalSuppression {
  readonly pointer: FieldPointer;
  readonly code: string;
}

type RuleOutcome =
  | {
      readonly kind: "fired";
      readonly location: GroupPointer;
      readonly findings:
        readonly ValidationFinding[];
    }
  | {
      readonly kind: "passed";
      readonly location: GroupPointer;
    }
  | {
      readonly kind: "suppressed";
      readonly location: GroupPointer;
      readonly reasons:
        readonly FormalSuppression[];
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

One stored rule can run once per repeated rule-group row. `evaluateStoredRule` preserves every fired, passed, suppressed, or unsupported row outcome instead of collapsing the result into one Boolean.

### 9.3 Support reporting

```ts
type SupportCapability =
  | {
      readonly status: "supported";
      readonly kind: string;
      readonly id: string;
    }
  | {
      readonly status: "runtime-resolved";
      readonly kind:
        | "custom-field-type"
        | "custom-condition";
      readonly id: string;
      readonly detail: string;
    }
  | {
      readonly status: "unsupported";
      readonly kind: string;
      readonly id: string;
      readonly reasonCode: UnsupportedReasonCode;
      readonly detail: string;
    };

interface InterpreterSupportReport {
  readonly capabilities: readonly SupportCapability[];
  readonly fullySupported: boolean;
  readonly fullyResolved: boolean;
}
```

`supportReport()` does not read a document or invoke validation callbacks. A source factory remains `runtime-resolved` when probing it early would change observable factory cardinality.

### 9.4 Non-evaluation and integration failure

Keep these channels distinct:

| Situation | Result channel |
|---|---|
| malformed document text | formal finding plus preserved raw storage |
| legitimate expression domain failure | explicit `A12Evaluation.notEvaluated` |
| formally invalid operand | formal suppression |
| missing capability | `unsupported` plus `fullySupported: false` |
| callback throws or returns a thenable | `InterpreterIntegrationError` |
| foreign model-owned object | `ModelOwnerMismatchError` |
| internal invariant failure | exception, never relabeled unsupported |

One generic `success` Boolean would erase necessary A12 semantics.

Evaluation also preserves these boundaries:

| Boundary | Contract |
|---|---|
| Full versus partial | Full validation is the safe form default; partial validation changes relevance, operand visibility, callback inputs, and suppression and is never implemented as report filtering |
| Formal versus authored | Automatic formal findings and authored rule findings may coexist and retain their distinct identities |
| Formal versus unsupported | Formal invalidity and a missing custom capability may coexist; neither erases the other |
| Inspection versus validation | `inspectDocument` performs one complete formal pass only; it is not a partial-validation or stored-rule entry point |
| Readiness versus execution | `supportReport` reports statically known capability without invoking application callbacks; factory-backed capabilities may remain runtime-resolved until real evaluation |

### 9.5 Computations

```ts
type ComputationOutcome =
  | {
      readonly kind: "value";
      readonly target: FieldPointer;
      readonly value: A12Value;
      readonly storedRaw: string;
      readonly changed: boolean;
    }
  | {
      readonly kind: "cleared";
      readonly target: FieldPointer;
    }
  | {
      readonly kind: "errored";
      readonly target: FieldPointer;
      readonly attemptedRaw: string;
      readonly diagnostics: readonly InspectionDiagnostic[];
    }
  | {
      readonly kind: "formal-error";
      readonly target: FieldPointer;
      readonly reasons: readonly FormalSuppression[];
    };

interface ComputationDeltaEntry {
  readonly kind: "write" | "clear";
  readonly target: FieldPointer;
  readonly previousRaw?: string;
  readonly nextRaw: string;
}

interface ComputationReport {
  readonly outcomes: readonly ComputationOutcome[];
  readonly unsupported: readonly UnsupportedProgram[];
  readonly delta: readonly ComputationDeltaEntry[];
  readonly changed: boolean;
  readonly noErrorOccurred: boolean;
  readonly fullySupported: boolean;
}

type ComputeAndApplyResult =
  | {
      readonly kind: "applied";
      readonly computations: ComputationReport;
      readonly snapshot: DocumentSnapshot;
    }
  | {
      readonly kind: "notApplied";
      readonly reason: "unsupported";
      readonly computations: ComputationReport;
      readonly snapshot: DocumentSnapshot;
    };
```

`computeAndApply` evaluates once. It applies the captured delta atomically when fully supported and returns the unchanged snapshot when any computation is unsupported.

Ordinary computation errors remain report data: successful values are written, errored or explicitly cleared existing targets become present-empty according to kernel rules, and formal-error outcomes do not write. A call does not recompute just to apply its result.

| Computation case | Application contract |
|---|---|
| Value for absent target | Create the target and required ancestor placements, then store `storedRaw` |
| Equal value | Report the outcome but omit it from `delta`; `changed` reflects actual storage change |
| Clear/error for existing target | Preserve the placement and store present-empty `raw: ""` |
| Clear/error for absent target | Leave it absent and create no ancestor merely to clear it |
| Formal error | Retain every suppression reason and perform no write |
| Any unsupported computation | Apply none of the computation outcomes and return the original snapshot |
| Ordinary error with full support | Return `kind: "applied"` after the kernel-defined clear behavior even though `noErrorOccurred` is false |

`delta` contains only actual storage changes in kernel application order. A compatibility adapter does not infer native application from this detached delta: its authenticated source-bound result retains the original document and the source platform’s exact changed, errored, and cleared classifications.

## 10. Repeated models and concurrency

### 10.1 Core rule

An active `Interpreter` is an exclusive lane. The initial contract does not require one interpreter or callback set to be concurrently callable.

Concurrent service use is provided by several warm lanes:

```text
pool key = (ModelVersionIdentity, InterpreterConfigurationIdentity)

D1/config-A ──► lane 1
             └► lane 2
D2/config-A ──► lane 3
D1/config-B ──► lane 4
```

This allows `D1, D2, D1, D2` without reparsing either model and allows several simultaneous requests for one hot model. No global or per-model evaluation lock may serialize all evaluation.

### 10.2 Valid ownership strategies

| Strategy | Use |
|---|---|
| Shared immutable prepared plan with confined interpreters | When safe publication and lazy caches are proven |
| Worker-confined prepared model | Default fit for Node Workers |
| Lane-owned prepared model | When isolation is safer or faster than sharing |

Sharing is an implementation optimization, not a public prerequisite. A lane-owned strategy decodes the portable document after selecting the lane because snapshots cannot cross owner domains.

### 10.3 Minimal service façade

```ts
declare const interpreterConfigurationIdentityBrand:
  unique symbol;

type InterpreterConfigurationIdentity = {
  readonly [interpreterConfigurationIdentityBrand]: never;
};

interface RegisteredModel {
  readonly modelVersion: ModelVersionIdentity;
  readonly fingerprint: ModelFingerprint;
  release(): void;
}

interface RegisteredInterpreterConfiguration {
  readonly identity:
    InterpreterConfigurationIdentity;
  release(): void;
}

interface ServiceInterpreterBindings
  extends DirectInterpreterBindings {
  readonly dispose?: () => void;
}

interface InterpreterConfigurationRegistration {
  readonly fallbackLegalCharset?: LegalCharset;
  readonly createBindings:
    () => ServiceInterpreterBindings;
}

interface ValidatePlacementsRequest {
  readonly model: RegisteredModel;
  readonly document: unknown;
  readonly configuration?:
    RegisteredInterpreterConfiguration;
  readonly options?: ValidationOptions;
}

interface WarmModelRequest {
  readonly model: RegisteredModel;
  readonly configuration?:
    RegisteredInterpreterConfiguration;
  readonly instances: number;
}

interface ValidationService {
  registerModel(
    source: ExpandedModelJson,
  ): RegisteredModel;

  registerConfiguration(
    registration:
      InterpreterConfigurationRegistration,
  ): RegisteredInterpreterConfiguration;

  validatePlacements(
    request: ValidatePlacementsRequest,
  ): ValidationReport;

  support(
    model: RegisteredModel,
    configuration?:
      RegisteredInterpreterConfiguration,
  ): InterpreterSupportReport;

  warm(request: WarmModelRequest): void;
  close(): void;
}
```

The service is a convenience above the core, not a hidden singleton. It must be bounded, own its handles and lanes, provide backpressure when all lanes are leased, and dispose configuration resources exactly once when a lane retires.

### 10.4 Registry and lane invariants

| Invariant or trap | Contract |
|---|---|
| Cache identity | Warm lanes are keyed by exact `ModelVersionIdentity` and `InterpreterConfigurationIdentity`; document, locale, sampled time, and logical alias are call/routing data, not cache keys |
| Configuration identity | Callback resources are identified by the registered handle, never structural equality, factory names, or caller strings |
| Exclusive lease | One request owns one lane until its detached result is complete; no callback or request context remains after release |
| Capacity | Model, configuration, and lane counts are bounded; saturation applies backpressure or a typed capacity failure instead of creating unbounded runtimes |
| Pinning and release | Active leases pin their model and configuration; release prevents new acquisition and retirement waits for existing leases without use-after-eviction |
| Locks | Registry coordination locks are not held during model parsing, evaluation, application callbacks, or callback disposal |
| Disposal | A lane that fails binding, returns a thenable, or encounters an integration failure is retired; each successfully created service binding is disposed exactly once |
| Shutdown | Drain stops new work and completes admitted work; close retires idle state and finishes only after leased work and disposal complete |
| Reentrant lifecycle | A callback may not synchronously drain, close, or release the configuration that owns its lane; fail before waiting or changing lifecycle state |

### 10.5 Platform concurrency

- JVM `ValidationService` and its registered handles are thread-safe.
- Each JVM request receives one exclusive lane; several lanes serve a hot model concurrently.
- One Node Worker owns its prepared objects, callbacks, codecs, and lane cache.
- Node CPU parallelism uses several Workers and exchanges only versioned plain DTOs.
- Kotlin/JS wrappers, snapshots, pointers, callbacks, and owner identities never cross Worker boundaries.

## 11. Required kernel-compatibility adapters

The core SPI is the license-free long-term contract. The adapters are required because existing projects must be able to reuse their current platform implementations without rewriting them first.

### 11.1 Artifact boundary

| Required artifact | Existing inputs | Dependency |
|---|---|---|
| `@a12/interpreter-kernel-compat` | TypeScript kernel `Document`, `GeneratedCodeRtConfig`, value mappings, custom factories, and `Localizable[]` messages | kernel-free TypeScript Interpreter plus the certified kernel TypeScript API |
| `a12-interpreter-kernel-compat-jvm` | `DocumentV2`, `DocumentProcessingConfig`, modern JVM custom factories, and certified legacy variants | kernel-free JVM Interpreter plus the certified kernel JVM API |

The TypeScript and JVM adapters are distinct because their native documents, pointer collections, factories, validation parameters, and message types differ. Neither adapter belongs in Kotlin `commonMain`, and neither base artifact depends back on an adapter.

Applications that implement the native core SPI remain completely kernel-free. Applications that reuse already compiled kernel-interface implementations install the corresponding adapter and accept that adapter’s kernel licensing boundary.

### 11.2 TypeScript adapter

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

type KernelComputationApplication =
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
  readonly report: ComputationReport;
  readonly sourceDocument: KernelDocument;

  appliedTo(
    document: KernelDocument,
  ): KernelComputationApplication;
}

interface KernelCompatibleInterpreter {
  readonly prepared: PreparedModel;

  supportReport(): InterpreterSupportReport;

  validateFull(
    document: KernelDocument,
    valueOverrides?: EntityInstanceValueMapping,
    options?: ValidationOptions,
  ): ValidationReport;

  validatePart(
    document: KernelDocument,
    relevant:
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
```

The required `documentModelId` is taken from the generated model artifact and must equal `prepared.model.documentModelId`. The adapter must not substitute a service alias or fingerprint merely to pass this check.

TypeScript Custom Field Type errors contain `Localizable[]`, while the canonical report contains rendered strings. The explicit renderer and the per-call validation/computation locale produce that canonical projection; an exact native `DocumentValidationResult` would additionally require an adapter-owned sidecar retaining the original localization objects.

`extensionResolution: "explicit-only"` means missing factories remain missing. The initial adapter never consults deprecated process-global registration.

Fully typed use:

```ts
declare const prepared: PreparedModel;
declare const generatedModelId: string;
declare const generatedRuntime: GeneratedCodeRtConfig;
declare const renderer:
  KernelCustomFieldMessageRenderer;
declare const document: KernelDocument;

const configuration:
  KernelCompatibilityConfiguration = {
  documentModelId: generatedModelId,
  generatedRuntime,
  extensionResolution: "explicit-only",
  customFieldMessageRenderer: renderer,
};

const interpreter: KernelCompatibleInterpreter =
  createKernelCompatibleInterpreter(
    prepared,
    configuration,
  );

const options: ValidationOptions = {
  locale: "en_US",
};

const report: ValidationReport =
  interpreter.validateFull(
    document,
    undefined,
    options,
  );
```

### 11.3 JVM adapter

The JVM adapter exposes the same capabilities over JVM kernel types:

```java
public interface KernelCompatibleInterpreter
        extends AutoCloseable {
    InterpreterSupportReport supportReport();

    ValidationReport validateFull(
            DocumentV2 document);

    ValidationReport validatePart(
            DocumentV2 document,
            Set<? extends DocumentMultiPointer> relevant);

    KernelCompatibleComputationResult compute(
            DocumentV2 document);

    @Override
    void close();
}

public final class KernelInterpreterAdapters {
    public static KernelCompatibleInterpreter bind(
            PreparedModel preparedModel,
            DocumentProcessingConfig configuration);
}
```

`bind` checks `DocumentV2.getDocumentModelId()` against the prepared model before every document operation. `DocumentProcessingConfig` supplies locale, test date, presentation information, and modern factories.

Static generated-runtime Custom Conditions over `IData`/`IIdentifier` are a separate JVM surface. They require their own audited adapter variant or remain explicitly unsupported; compatibility with `DocumentV2` conditions does not imply compatibility with them.

### 11.4 Adapter execution contract

Each adapter operation:

1. verifies the bound/native model identity;
2. converts the native document to one `DocumentSnapshot` once;
3. converts native value and presentation mappings to one call-scoped overlay;
4. evaluates through the clean-room interpreter;
5. calls existing customization implementations with their original native document and exact source inputs;
6. clears every native context and overlay in `finally`;
7. returns a detached canonical report or source-bound computation result.

An override wins over the document value, including malformed text and explicit `""`. Every formal check, field read, operator input, message interpolation, and Custom Condition view observes the same overlay.

The adapter never:

- calls kernel validation or computation;
- reconstructs a native document for each callback;
- stores request context in a process-global registry or `ThreadLocal`;
- performs registry search, reflection, or adapter discovery per field or AST node;
- transports callbacks or runtime objects across a Worker boundary.

`KernelCompatibleComputationResult.appliedTo` never reevaluates. It returns `notApplied` with the supplied document unchanged when the computation report is unsupported; otherwise it applies the exact source-classified changed, errored, and cleared collections.

## 12. Diagnostics and failures

Public failure families remain distinct:

| Family | Example |
|---|---|
| preparation | invalid or residual expanded model |
| document input | invalid envelope, unknown path, wrong coordinate arity, duplicate placement |
| formal validation | malformed value, requiredness, range, length, charset, custom rejection |
| authored validation | firing stored rule |
| unsupported | missing customization, operator, time zone, charset, or program form |
| non-evaluation | legal operator domain with no value |
| integration | callback threw, returned a thenable, or re-entered the lane |
| ownership | foreign model-owned snapshot or pointer |
| service lifecycle | released handle, pool saturation, draining, or closed service |

Errors expose stable codes and structured fields. Human-readable messages are not a parsing API, and raw input is not copied into generic errors by default.

## 13. Correctness and performance guarantees

Exactness and performance are co-equal release requirements. API convenience must not add a second evaluator or weaken kernel behavior.

### 13.1 Base interpreter

- stored rules and computations parse at most once per warm lane, or once in a certified immutable shared plan;
- repeated evaluation never reparses;
- parser behavior and optimized AST traversal are unchanged;
- custom declarations and conditions use pre-bound indexed dispatch slots;
- no registry lookup, reflection, adapter discovery, or coordination lock occurs per AST node;
- document-specific formal, overlay, computation, and result state is call-confined;
- D1 and D2 retain separate warm entries during `D1, D2, D1, D2`;
- no global or per-model evaluation lock serializes concurrent lanes.

If the façade, prepared boundary, or shared runtime semantics worsens parsing, AST traversal, startup, warmup, throughput, scaling, allocation, or relative footprint, that design does not ship.

### 13.2 Compatibility adapters

- applications that do not install an adapter load no adapter or kernel code and execute no adapter branch;
- a native document is converted at most once per public operation;
- adapter binding and pointer mapping occur outside evaluator hot loops;
- source factories retain their audited invocation cardinality;
- native callback, TypeScript adapter, and JVM adapter costs are measured separately;
- no native document, override, locale, pointer map, or callback crosses a lane or request.

One `O(document)` native-to-snapshot conversion is an honest adapter cost. It must be measured separately from already-decoded core evaluation and compared with the equivalent kernel call.

### 13.3 Concurrency and interleaving evidence

Release evidence must cover:

1. warm sequential `D1, D2, D1, D2` with no reparse;
2. the same model under different customization configurations with no contamination;
3. concurrent JVM requests through several exclusive lanes;
4. a bounded Node Worker pool with Worker-local warm entries;
5. callback-heavy full validation, partial validation, and computation;
6. cold latency, warm p50/p95, throughput, allocation, and retained memory.

## 14. Feasibility and product boundary

The proposal is feasible.

| Area | Feasibility | Main constraint |
|---|---:|---|
| Core TypeScript/JVM façade | High | Stable KMP exports and typed errors |
| Prepared model and snapshots | High | Strict ownership and lossless transport |
| Validation and computations | High | Exact result channels and no double evaluation |
| Native customization SPI | High | Exact callback laws and pre-bound dispatch |
| TypeScript compatibility adapter | High | Native document retention, value overlays, and `Localizable[]` projection |
| JVM compatibility adapter | High | Exact `DocumentV2`/config/pointer behavior and legacy audit |
| Multi-model service | High | Bounded exclusive lanes, backpressure, and lifecycle |

The core is not a thin rename: it requires stable ownership, document, customization, and result contracts around the existing evaluator. No fundamental technical obstacle requires a kernel dependency in the core or a performance regression in parser/AST evaluation.

## 15. Final contract

The product consists of:

1. a clean-room, kernel-free Kotlin Multiplatform interpreter;
2. a TypeScript-first façade and secondary Java façade;
3. immutable prepared models, model-owned snapshots, direct storage access, exact validation, and exact computation;
4. kernel-free Custom Field Type and Custom Condition SPIs;
5. exclusive warm interpreter lanes that support fast model interleaving and concurrent services;
6. required separately packaged TypeScript and JVM kernel-compatibility adapters for existing project implementations.

The architecture preserves the two non-negotiable goals: exact A12 behavior and no interpreter parsing, AST traversal, warmup, or steady-state performance regression.
