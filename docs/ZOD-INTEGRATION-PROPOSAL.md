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

# Proposal: Optional Zod integration for A12 TypeScript APIs

## Status

This proposal defines optional Zod adapters for the TypeScript projections of the A12 Operator API and Interpreter API.

The [Operator API proposal](OPERATOR-API-PROPOSAL.md) owns scalar values, conversions, codecs, operator outcomes, and operator semantics. The [Interpreter API proposal](INTERPRETER-API-PROPOSAL.md) owns models, documents, ownership, validation, computation, customization, concurrency, and lifecycle.

The Zod integration does not redefine either API:

- `@a12/operators-zod` adapts native or wire values to authentic A12 scalar values.
- `@a12/interpreter-zod` validates portable Interpreter transport DTOs and projects canonical reports for TypeScript form libraries.

Both packages are optional, TypeScript-only boundary packages. Zod is never required by Kotlin, Java, the evaluator, or direct operator execution.

Node is the initial qualified JavaScript host, matching the base packages. Browser support is claimed only after the base Operator and Interpreter packages and these adapters pass browser packaging, conformance, and performance qualification.

## Motivation

TypeScript applications already use Zod to validate `unknown` HTTP, queue, form, storage, and Worker input. Without a standard adapter, every application would repeat native-to-A12 conversion, nested error mapping, document-envelope validation, and form-report projection.

The integration makes those boundaries familiar and composable while leaving every A12 semantic decision in the Operator and Interpreter cores. Applications that do not use Zod pay no dependency or runtime cost.

## 1. Design rule

> Zod is the ergonomics oracle; the A12 kernel is the semantic oracle.

Zod contributes familiar TypeScript boundary ergonomics:

- parsing starts from `unknown`;
- input and output types are explicit;
- codecs compose through objects and arrays;
- failures carry nested paths;
- throwing and non-throwing parse forms are available.

The A12 core remains authoritative for:

- exact decimal, date, time, date-time, and range semantics;
- authentic A12 wrapper construction and encoding;
- model-aware document decoding and ownership;
- validation, computation, relevance, suppression, and unsupported semantics;
- Custom Field Types and Custom Conditions.

Every Zod transform delegates semantic work to a public Kotlin-backed core operation. It must not reproduce A12 rules with TypeScript regexes, coercions, refinements, or arithmetic.

## 2. Scope

| Capability | Decision |
|---|---|
| Scalar native/wire values to authentic A12 values | Required |
| Canonical scalar encoding where conversion is bidirectionally exact | Required |
| Object and array composition | Required |
| Structured A12 conversion failures as nested Zod issues | Required |
| Strict `PlacementDocumentInputV1` transport validation | Required |
| Model-aware placement decoding | Delegated to the Interpreter core |
| Canonical report-to-form issue projection | Required |
| Prepared-owner-bound snapshot adapter | Optional convenience |
| Model-generated Zod schemas | Excluded from the initial integration |
| Operator wrapping with `z.function()` | Excluded |
| Zod-based A12 rules or custom conditions | Excluded |
| Java Zod API | Excluded |

## 3. Package boundary

```text
@a12/operators-zod
    ├── peer ──> @a12/operators
    └── peer ──> zod

@a12/interpreter-zod
    ├── peer ──> @a12/interpreter
    ├── peer ──> @a12/operators-zod
    └── peer ──> zod
```

The adapter packages:

- are optional TypeScript packages;
- use the application’s existing A12 and Zod package instances;
- contain no duplicated evaluator or operator semantics;
- have no direct or transitive dependency on an A12 Kernel API;
- add no code, startup, allocation, or runtime cost when they are not installed.

Target Zod 4.1 or a later explicitly qualified compatible range as a peer dependency. Ready-made codecs may use ordinary Zod APIs; internals that accept caller-provided schemas should type against `zod/v4/core` where that materially improves compatibility with Zod and Zod Mini.

Package and bundler tests must prove that one physical `@a12/operators` instance exists per JavaScript realm. A private adapter copy would create foreign wrappers that the core must reject.

Kernel-compatibility adapters for existing project Custom Field Types and Custom Conditions are separate ecosystem artifacts defined by the [Interpreter API proposal](INTERPRETER-API-PROPOSAL.md). An application may use those adapters and Zod together, but neither depends on the other.

## 4. Operator integration

### 4.1 Public codec shape

Bidirectional codecs are distinct from decode-only schemas because not every accepted native input representation can encode every A12 value.

```ts
import type * as z from "zod";

declare const a12ZodCodecBrand: unique symbol;

interface A12ZodCodecMarker {
  readonly [a12ZodCodecBrand]: {
    readonly input: unknown;
    readonly output: unknown;
  };
}

type A12ZodCodec<Input, Output> =
  z.ZodType<Output, Input> &
  A12ZodCodecMarker & {
    readonly [a12ZodCodecBrand]: {
      readonly input: Input;
      readonly output: Output;
    };
  };

type A12ZodDecoder<Input, Output> =
  z.ZodType<Output, Input>;

type A12ZodObjectShape =
  Readonly<Record<string, A12ZodCodecMarker>>;

type A12ZodObjectInput<
  Shape extends A12ZodObjectShape,
> = {
  readonly [Key in keyof Shape]:
    Shape[Key][typeof a12ZodCodecBrand]["input"];
};

type A12ZodObjectOutput<
  Shape extends A12ZodObjectShape,
> = {
  readonly [Key in keyof Shape]:
    Shape[Key][typeof a12ZodCodecBrand]["output"];
};

interface A12ZodComposition {
  object<
    Shape extends A12ZodObjectShape,
  >(
    shape: Shape,
  ): A12ZodCodec<
    A12ZodObjectInput<Shape>,
    A12ZodObjectOutput<Shape>
  >;

  array<Input, Output>(
    item: A12ZodCodec<Input, Output>,
  ): A12ZodCodec<
    readonly Input[],
    readonly Output[]
  >;
}
```

`A12ZodCodec<Input, Output>` is issued only for a verified encode-safe conversion. `A12ZodDecoder<Input, Output>` supports parsing but makes no encoding promise.

The brand prevents ordinary accidental misuse in TypeScript. Adapter-issued runtime metadata prevents an asserted ordinary schema from entering encode-safe composition, while the A12 core guard and package-instance ownership contract authenticate the output value.

Every codec preserves the core laws: decoding an encoded authentic value preserves its A12 semantic value, while encoding a decoded input may canonicalize its lexical representation. A lossy or incomplete conversion is never presented as a codec.

| Operation | Boundary |
|---|---|
| `schema.parse(input)` / `schema.safeParse(input)` | Starts from `unknown` |
| `z.decode(codec, input)` / `z.safeDecode(codec, input)` | Starts from the codec’s statically known input type |
| `z.encode(codec, output)` / `z.safeEncode(codec, output)` | Starts from an authentic A12 output type |

Ordinary `z.object(...)` and `z.array(...)` remain valid for parse-only composition. The adapter’s `object(...)` and `array(...)` helpers accept only adapter-issued encode-safe codecs; object composition is strict about unknown keys.

Every output schema uses the exact concrete core guard, such as `A12Integer.is(value: unknown)` rather than the wider `A12Number.is`. A wrapper from a duplicate bundle, Worker, iframe realm, structured clone, persistence layer, or network is foreign; encode it to its portable representation and decode a new local wrapper.

Calling Zod’s encode operation on `A12ZodDecoder` is unsupported. The adapter does not disguise a one-way transform as a bidirectional conversion.

### 4.2 Scalar catalog

| Export | Input | Output | Direction |
|---|---|---|---|
| `a12Zod.string.fromString` | `string` | `A12String` | codec |
| `a12Zod.boolean.fromBoolean` | `boolean` | `A12Boolean` | codec |
| `a12Zod.confirm.fromTrue` | literal `true` | `A12Confirm` | codec |
| `a12Zod.number.fromDecimalString` | `string` | `A12Number` | codec |
| `a12Zod.integer.fromBigInt` | `bigint` | `A12Integer` | codec |
| `a12Zod.integer.fromSafeNumber` | safe integral `number` | `A12Integer` | decode only |
| `a12Zod.date.fromIsoString` | ISO `string` | `A12Date` | codec |
| `a12Zod.time.fromIsoString` | ISO `string` | `A12Time` | codec |
| `a12Zod.dateRange.fromIsoObject` | `A12DateRangeWire` | `A12DateRange` | codec |
| `a12Zod.dateTime.fromInstantAndZone` | `A12DateTimeWire` | `A12DateTime` | codec where the core supports the supplied zone exactly |

Each adapter calls the corresponding core codec or non-throwing constructor for both decode and encode.

Three restrictions are essential:

- Exact decimals use decimal strings, never `z.number()`.
- Calendar dates use ISO date strings, never JavaScript `Date`.
- Date-time input includes an exact instant and supported time zone; Zod does not fill time-zone parity gaps.

`fromSafeNumber` rejects non-finite, fractional, and unsafe values. It is decode-only because an arbitrary `A12Integer` may not fit a safe JavaScript number.

`fromInstantAndZone` delegates instant parsing, precision, supported-zone recognition, zone history, and canonical encoding to the core. The adapter accepts exactly the core’s qualified zone set and adds no named zone until the corresponding Operator API parity gate passes.

### 4.3 Typed object example

```ts
import * as z from "zod";
import {
  A12Integer,
  operators,
} from "@a12/operators";
import type {
  A12Date,
  A12Number,
} from "@a12/operators";
import {
  a12Zod,
} from "@a12/operators-zod";
import type {
  A12ZodCodec,
} from "@a12/operators-zod";

interface OrderWire {
  readonly amount: string;
  readonly dueDate: string;
}

interface OrderValues {
  readonly amount: A12Number;
  readonly dueDate: A12Date;
}

const orderCodec:
  A12ZodCodec<OrderWire, OrderValues> =
  a12Zod.object({
    amount: a12Zod.number.fromDecimalString,
    dueDate: a12Zod.date.fromIsoString,
  });

declare const requestBody: unknown;

const order: OrderValues =
  orderCodec.parse(requestBody);

const reminderOffset: A12Integer =
  A12Integer.fromSafeInteger(7);

const reminderDate: A12Date =
  operators.date.addDays(
    order.dueDate,
    reminderOffset,
  );

const encoded: OrderWire =
  z.encode(orderCodec, order);
```

| Variable | Type | Meaning |
|---|---|---|
| `requestBody` | `unknown` | Untrusted boundary input |
| `orderCodec` | `A12ZodCodec<OrderWire, OrderValues>` | Encode-safe object codec |
| `order` | `OrderValues` | Authentic Kotlin-backed A12 values |
| `reminderOffset` | `A12Integer` | Exact operator argument |
| `reminderDate` | `A12Date` | Direct operator result |
| `encoded` | `OrderWire` | Canonical portable representation |

Zod is used only before and after the operator call. The operator itself remains a direct typed function over A12 values.

### 4.4 Typed array example

```ts
import type {
  A12Number,
} from "@a12/operators";
import {
  operators,
} from "@a12/operators";
import {
  a12Zod,
} from "@a12/operators-zod";
import type {
  A12ZodCodec,
} from "@a12/operators-zod";

const amountsCodec:
  A12ZodCodec<
    readonly string[],
    readonly A12Number[]
  > =
  a12Zod.array(
    a12Zod.number.fromDecimalString,
  );

declare const amountPayload: unknown;

const amounts: readonly A12Number[] =
  amountsCodec.parse(amountPayload);

const total: A12Number =
  operators.number.sumAll(amounts);
```

Array composition preserves order, duplicates, and element indexes in diagnostics. It does not introduce an A12-specific list model.

The returned array is a readonly TypeScript view, not a promise that the JavaScript array is frozen. It can be passed directly to A12 collection operators.

### 4.5 Error translation

```ts
import type {
  A12ConversionIssue,
} from "@a12/operators";

interface A12ZodIssueMetadataBase {
  readonly source: "a12";
  readonly family: string;
}

interface A12ZodConversionIssueMetadata
  extends A12ZodIssueMetadataBase {
  readonly family: "conversion";
  readonly errorCode:
    "A12_CONVERSION_ERROR";
  readonly issue: A12ConversionIssue;
}

interface A12ZodCustomIssue<
  Metadata extends A12ZodIssueMetadataBase,
> {
  readonly code: "custom";
  readonly path:
    readonly (string | number)[];
  readonly message: string;
  readonly params: {
    readonly a12: Metadata;
  };
}
```

`@a12/operators-zod` owns the conversion metadata and common generic issue shape. `@a12/interpreter-zod` adds the following metadata without making the operator package depend on Interpreter types:

```ts
import type {
  DocumentInputDiagnostic,
} from "@a12/interpreter";
import type {
  A12ZodIssueMetadataBase,
} from "@a12/operators-zod";

interface A12ZodDocumentInputIssueMetadata
  extends A12ZodIssueMetadataBase {
  readonly family: "documentInput";
  readonly errorCode:
    "A12_DOCUMENT_INPUT_INVALID";
  readonly diagnostic:
    DocumentInputDiagnostic;
}
```

Expected core conversion failures become Zod custom issues with:

- the surrounding object or array path plus the A12 issue path, appended exactly once;
- the stable A12 conversion code;
- the original structured A12 conversion issue as adapter metadata;
- a presentation message that consumers do not need to parse.

Unexpected exceptions, invariant failures, and callback failures remain their original failures. The adapter must not relabel them as invalid user input.

Each member of `A12ConversionError.issues` becomes one Zod issue. For Interpreter document input, each `DocumentInputError.diagnostics` member likewise becomes one issue; `diagnostic.inputPath` supplies the Zod path while `diagnostic.modelPath` remains separate metadata.

Raw input is not copied into issue metadata by default. Nested array failures retain their exact element index.

### 4.6 Generic values require a tag

An untagged string such as `"2026-07-19"` may represent either an A12 string or an A12 date. A generic portable value therefore requires an explicit discriminator:

```ts
import type {
  A12DateRangeWire,
  A12DateTimeWire,
} from "@a12/operators";

type A12TaggedValueWire =
  | {
      readonly kind: "string";
      readonly value: string;
    }
  | {
      readonly kind: "boolean";
      readonly value: boolean;
    }
  | {
      readonly kind: "confirm";
      readonly value: true;
    }
  | {
      readonly kind: "number";
      readonly value: string;
    }
  | {
      readonly kind: "date";
      readonly value: string;
    }
  | {
      readonly kind: "time";
      readonly value: string;
    }
  | {
      readonly kind: "dateRange";
      readonly value: A12DateRangeWire;
    }
  | {
      readonly kind: "dateTime";
      readonly value: A12DateTimeWire;
    };
```

Field-specific codecs remain the normal API. A tagged codec is useful only for generic plugin, persistence, or Worker protocols.

### 4.7 Predicates are not ordinary refinements

An A12 predicate returns `A12Truth`, including explicit non-evaluation. The integration must never silently convert non-evaluation to native `false`, a passed refinement, or a failed refinement.

A future refinement helper would need an explicit `"reportIssue"` or `"skipRefinement"` policy for non-evaluation. It is not part of the initial package; direct operator predicates remain preferred.

## 5. Interpreter integration

### 5.1 Two validation stages

The Interpreter owns the versioned placement DTO and model-aware document decoder. Zod validates the portable envelope before the core binds it to a prepared model.

| Stage | Responsibility |
|---|---|
| Zod transport validation | Version, object shape, arrays, native strings, coordinate integers, unknown properties, portable resource limits |
| Interpreter document decoding | Model paths, coordinate arity, repetition limits, duplicate/conflicting placements, ownership, stored raw values |
| Interpreter evaluation | Formal checks, custom types, rules, computations, relevance, suppression, time, locale, and unsupported semantics |

The Zod stage never validates raw A12 field semantics. Temporarily malformed field text remains valid transport input so the Interpreter can report it.

### 5.2 Transport schema

```ts
import type * as z from "zod";
import type {
  DocumentDecodeLimits,
  PlacementDocumentInputV1,
} from "@a12/interpreter";

declare const placementDocumentInputV1Schema:
  z.ZodType<PlacementDocumentInputV1>;

declare function
createPlacementDocumentInputV1Schema(
  limits: DocumentDecodeLimits,
): z.ZodType<PlacementDocumentInputV1>;
```

The exported schema is strict: unknown object properties are rejected rather than stripped.

The schema factory applies portable limits such as placement counts, coordinate-vector length, path length, and raw-text size. Model nesting and model-specific constraints remain core decoder responsibilities.

### 5.3 Typed ingress and validation example

```ts
import type * as z from "zod";
import type {
  DocumentDecodeLimits,
  DocumentDecodeOptions,
  DocumentSnapshot,
  Interpreter,
  PlacementDocumentInputV1,
  PreparedModel,
  ValidationOptions,
  ValidationReport,
} from "@a12/interpreter";
import {
  createPlacementDocumentInputV1Schema,
} from "@a12/interpreter-zod";

declare const requestBody: unknown;
declare const prepared: PreparedModel;

const limits: DocumentDecodeLimits = {
  maxGroupPlacements: 10_000,
  maxFieldPlacements: 100_000,
  maxCoordinatesPerPlacement: 16,
  maxPathCodeUnits: 1_024,
  maxRawCodeUnitsPerField: 100_000,
  maxTotalRawCodeUnits: 5_000_000,
  maxModelNestingDepth: 64,
};

const schema:
  z.ZodType<PlacementDocumentInputV1> =
  createPlacementDocumentInputV1Schema(limits);

const input: PlacementDocumentInputV1 =
  schema.parse(requestBody);

const decodeOptions: DocumentDecodeOptions = {
  limits,
};

const snapshot: DocumentSnapshot =
  prepared.documents.readPlacements(
    input,
    decodeOptions,
  );

const interpreter: Interpreter =
  prepared.createInterpreter();

const validationOptions: ValidationOptions = {
  locale: "en_US",
};

const report: ValidationReport =
  interpreter.validateFull(
    snapshot,
    validationOptions,
  );
```

The same immutable `limits` object governs both stages. Raw request-byte limits and JSON-parser limits are enforced before this example begins.

The dependency-free Interpreter retains its own `decodePlacements(input: unknown)` and `tryDecodePlacements(input: unknown)` boundary. Zod is an alternative for applications that already standardize on Zod, not the only safe ingress path.

### 5.4 Optional prepared-owner adapter

A convenience adapter may combine the strict placement codec with one exact prepared owner:

```ts
import type {
  DocumentDecodeLimits,
  DocumentSnapshot,
  ModelOwnerIdentity,
  ModelVersionIdentity,
  PlacementDocumentInputV1,
  PreparedModel,
} from "@a12/interpreter";
import type {
  A12ZodDecoder,
} from "@a12/operators-zod";

interface PreparedZodDocumentAdapter {
  readonly owner: ModelOwnerIdentity;
  readonly modelVersion: ModelVersionIdentity;
  readonly decodeSchema:
    A12ZodDecoder<
      PlacementDocumentInputV1,
      DocumentSnapshot
    >;
  encode(
    snapshot: DocumentSnapshot,
  ): PlacementDocumentInputV1;
}

declare function createPreparedDocumentAdapter(
  prepared: PreparedModel,
  limits: DocumentDecodeLimits,
): PreparedZodDocumentAdapter;
```

The adapter delegates `decodeSchema` to `prepared.documents.tryDecodePlacements`, authenticates successful output through `prepared.documents.isSnapshot(value: unknown)`, and delegates `encode` to `prepared.documents.encodePlacements`. It never authenticates a snapshot by shape, model alias, fingerprint, or `instanceof`.

This is deliberately not one generic bidirectional `A12ZodCodec`. Encoding a foreign-owner snapshot is a programmer error (`ModelOwnerMismatchError`), not invalid transport input, and generic Zod encode semantics would risk collapsing that distinction into an ordinary schema issue.

This helper is optional because lane-owned services may need to select an owner before decoding. In that architecture, validate the static transport schema first and then call the selected lane’s core decoder.

An owner-bound adapter may capture only its exact prepared owner, immutable schema definitions, and that owner’s decoder, encoder, and guard. It must not capture a request document, active lease, locale, clock, tenant state, report, or mutable callback.

### 5.5 Report projection

`ValidationReport` remains the authoritative result. Zod-style or form-style issues cannot fully represent model-owned pointers, concrete/wildcard/partially known repetition coordinates, polarity, severity, authored versus formal origin, suppression, unsupported programs, and ownership.

```ts
import type {
  ReportPointer,
  UnsupportedProgram,
  ValidationFinding,
  ValidationReport,
  ValidationSeverity,
} from "@a12/interpreter";

type FormPathSegment =
  string | number;

type A12ProjectedFormIssue =
  | {
      readonly kind: "finding";
      readonly path:
        readonly FormPathSegment[];
      readonly code: string;
      readonly message: string;
      readonly severity:
        ValidationSeverity;
      readonly finding:
        ValidationFinding;
    }
  | {
      readonly kind: "unsupported";
      readonly path:
        readonly FormPathSegment[];
      readonly code:
        "a12UnsupportedCapability";
      readonly message: string;
      readonly unsupported:
        UnsupportedProgram;
    };

interface A12FormIssueProjection {
  readonly report: ValidationReport;
  readonly issues:
    readonly A12ProjectedFormIssue[];
  readonly unmappedFindings:
    readonly ValidationFinding[];
  readonly unmappedUnsupported:
    readonly UnsupportedProgram[];
}

interface A12FormProjectionOptions {
  readonly mapPointer:
    (
      pointer: ReportPointer,
    ) =>
      | readonly FormPathSegment[]
      | undefined;
}

declare function projectValidationReport(
  report: ValidationReport,
  options: A12FormProjectionOptions,
): A12FormIssueProjection;
```

Typed usage:

```ts
declare const report: ValidationReport;
declare const mapPointer:
  (
    pointer: ReportPointer,
  ) =>
    | readonly FormPathSegment[]
    | undefined;

const projectionOptions:
  A12FormProjectionOptions = {
  mapPointer,
};

const projection:
  A12FormIssueProjection =
  projectValidationReport(
    report,
    projectionOptions,
  );
```

The projection:

- performs no reevaluation;
- retains the original report;
- retains the original finding or unsupported record on every projected issue;
- preserves stable A12 codes and severity;
- keeps unmappable records visible;
- never treats an empty issue array as proof that evaluation was complete or error-free.

The caller supplies `mapPointer` because an application form path is not an A12 model path. Findings map through their report pointer; an unsupported executable that exposes only a program path remains unmapped rather than receiving a fabricated form or authored-rule location.

A convenience helper may convert projected records into ordinary Zod custom issues for a specific form library. That view is presentation-only and must remain traceable to the canonical report and original record.

### 5.6 Interactive forms

Do not parse and clone the entire document through Zod on every keypress.

Use Zod for initial hydration or transport ingress, preserve subsequent raw edits through the A12 document API, and let the Interpreter report temporarily malformed values. Apply scalar Zod codecs when a standalone field value is committed and must already be valid.

## 6. Related Zod boundaries

### 6.1 Model-derived schemas and JSON Schema

Model-derived Zod schemas may provide labels, select options, form hints, basic scalar input widgets, and application metadata. They are advisory and do not replace formal A12 validation, raw document states, repetitions, relevance, suppression, rules, computations, Custom Field Types, Custom Conditions, or pointer ownership.

A runtime-loaded model yields dynamic metadata and generic TypeScript types. Precise per-model compile-time types require a separate build-time generator.

Rich Zod codecs with transforms are not faithfully representable as JSON Schema in general. If OpenAPI or JSON Schema is required, expose a separate plain wire-input schema and state explicitly that it does not perform A12 semantic decoding.

### 6.2 Standard Schema

If a future application-facing extension accepts caller-provided validation libraries, use the library-neutral [Standard Schema](https://standardschema.dev/schema) interface rather than a Zod-specific generic extension point. Zod implements Standard Schema.

Standard Schema is not an A12 Custom Field Type or Custom Condition SPI. It does not preserve condition firing polarity, full/partial relevance, formally incorrect entities, partially known error pointers, value overrides, conversion behavior, error identity, repetition scope, factory invocation order, exception semantics, or ownership.

Any Standard Schema bridge on the synchronous Interpreter path must reject Promise-returning results and distinguish normal schema rejection from thrown integration failure. The initial built-in Zod scalar and transport adapters do not require this bridge.

## 7. Kernel-compatibility adapters

Existing projects may need separate TypeScript or JVM adapters for Custom Field Types and Custom Conditions implemented against A12 Kernel interfaces. Those adapters are owned by the Interpreter ecosystem and remain independent of Zod.

These adapters are required Interpreter product deliverables for projects that must reuse existing kernel-interface implementations. Installation remains application-selective: a project that supplies native kernel-free customizations does not install them.

The boundaries are:

```text
unknown transport
    → optional Zod validation
    → kernel-free A12 document and Interpreter API
    → separately installed kernel-compatibility callback adapter
```

Zod does not load, register, wrap, or reinterpret kernel callbacks. It remains optional even when a project installs a kernel-compatibility adapter.

## 8. Integration invariants and gotchas

| Concern | Guarantee |
|---|---|
| Semantic construction | Every scalar transform calls a core A12 constructor or codec |
| Encode safety | Only adapter-issued, runtime-marked bidirectional codecs enter encode-safe composition; decode-only schemas stay decode-only |
| Exact decimal values | Decimal strings are used; native floating-point input is not advertised as exact |
| Calendar dates | ISO calendar strings are used; JavaScript `Date` is not treated as `A12Date` |
| Date-time and zones | The wire form contains an exact instant and explicit supported zone; the adapter never broadens the core zone set |
| Generic scalar values | A kind discriminator is required because an untagged string is ambiguous |
| Unknown input | Public transport parsing starts from `unknown` |
| Unknown properties | Canonical versioned DTO schemas reject them |
| Transport versus formal validation | Zod validates the portable shape; model decoding and evaluation remain Interpreter responsibilities |
| Malformed interactive values | Raw malformed text survives document ingress and on-keyup editing so the Interpreter can report it |
| Runtime authenticity | Core guards authenticate A12 wrappers and snapshots |
| Snapshot encoding | Foreign-owner encoding remains `ModelOwnerMismatchError`, not a Zod input issue |
| Worker/process transfer | Runtime objects are encoded to portable data and decoded in the destination realm |
| Expected failures | Each structured A12 diagnostic becomes one nested Zod issue with its stable typed metadata |
| Issue location | Transport `inputPath` becomes the Zod path; model path remains separate metadata; paths are appended once |
| Unexpected failures | They retain their original exception/integration channel |
| Document semantics | The Interpreter remains authoritative |
| Report semantics | The canonical A12 report and unmapped records are retained; projection never implies complete/error-free evaluation |
| Predicate outcomes | `A12Truth.notEvaluated` is never silently converted to native `false` or an ordinary refinement result |
| Schema caches | Static schemas are realm-local; an owner-bound adapter is cached only with its exact owner |
| Mutable Zod state | Global metadata/configuration is never changed per request or tenant |
| JSON Schema | Plain wire schemas are separate from transforming A12 codecs |
| Generic validation libraries | Standard Schema is an optional boundary, never a substitute for an A12 customization SPI |
| Raw data privacy | Raw input is not copied into issues by default |

The integration does not use Zod coercion, defaults, catch values, or `undefined`/`null` to imitate A12 absent, empty, malformed, or non-evaluated states.

## 9. Performance and backend use

### 9.1 Performance isolation

The performance contract is simple:

1. No-Zod applications load no Zod code and pay no Zod cost.
2. Direct operator calls never construct or execute a Zod schema.
3. Interpreter parsing, preparation, AST traversal, validation, and computation remain unchanged.
4. Schemas are built once per module, Worker, or exact prepared-owner cache entry.
5. Untrusted transport is parsed once at ingress.
6. Models and schemas are not rebuilt when requests alternate between D1 and D2.
7. Zod transport time is measured separately from model-aware decoding and evaluation.

A strict Zod transport pass followed by core document decoding traverses part of the input twice. That is acceptable only at an untrusted boundary; it must not be inserted around already-owned snapshots or warm validation calls.

On a Node backend, schemas may be shared within one Worker when they capture no request state. Kotlin/JS wrappers, snapshots, owner identities, and prepared-owner adapters remain Worker-local and cross Worker boundaries only through portable encoded DTOs.

### 9.2 Worker and cache placement

| Resource | Cache scope |
|---|---|
| Static transport schema | Module constant in one Worker/JavaScript realm |
| Prepared-owner adapter | Exact local prepared owner only |
| Lane-owned document decoding | Select the lane, then call that lane’s core decoder |
| Model D1 and D2 | Distinct owner/cache entries; aliases and fingerprints may locate entries but do not authenticate snapshots |
| Request parse result, snapshot, report | Request state, never a schema-cache entry |

Every Worker message is received as `unknown`. The complete versioned outer message envelope must be validated before routing or model lookup; validating only its nested placement document is insufficient.

Do not send schemas, codecs, Kotlin/JS wrappers, prepared models, interpreters, snapshots, pointers, callbacks, or owner identities through structured clone. Send versioned plain DTOs and encoded detached results.

### 9.3 Global configuration

Do not mutate `z.globalRegistry` or `z.config()` per request, tenant, model, or locale.

Use caller-owned metadata registries, set truly process-wide Zod configuration once at Worker startup, and pass presentation options explicitly. A schema is not request-safe merely because its definition is immutable if a transform closes over mutable request state.

Zod transforms and refinements are executable application code. Do not execute tenant-provided schemas or functions without a separate trusted-code or sandboxing design.

## 10. Recommended sequence

The Operator integration can begin before the Interpreter API is complete.

1. Stabilize the Operator API’s scalar codecs, runtime guards, structured conversion errors, and package-instance contract.
2. Implement `@a12/operators-zod`, including scalar codecs, object/array composition, and issue translation.
3. Stabilize the Interpreter’s minimum transport foundation: versioned placement DTO, resource limits, structured input diagnostics, owner-aware model decoder, canonical encoder, and snapshot guard.
4. Implement the strict transport schema in `@a12/interpreter-zod`.
5. Add the prepared-owner adapter only if it simplifies a real owner-sharing architecture.
6. Add report projection after the canonical `ValidationReport` shape is stable.

Kernel-compatibility adapters are required Interpreter product deliverables and proceed independently after the core customization SPIs exist. They are not prerequisites for scalar Zod integration or portable transport validation.

## 11. Feasibility and recommendation

| Integration | Feasibility | Main dependency |
|---|---:|---|
| Scalar codecs | High | Stable core codecs and guards |
| Object and array composition | High | Encode-safe codec marker |
| Conversion issue translation | High | Structured core conversion errors |
| Placement DTO schema | High | Stable portable DTO |
| Prepared-owner adapter | High | Public owner guard, decoder, and encoder |
| Report projection | High | Stable canonical report and pointer types |

The proposal is feasible and low risk because it adds a thin TypeScript boundary layer rather than another semantic engine.

The release gates are:

- every expected core conversion and document-input failure has a non-throwing structured form;
- issue details are stable enough that adapters never parse messages;
- concrete wrapper and snapshot guards accept `unknown`;
- peer/package tests prevent duplicate A12 runtime instances;
- the qualified Zod version matrix locks codec and issue-translation behavior;
- adapter benchmarks separate transport parsing, model-aware decoding, evaluation, and projection.

Ship `@a12/operators-zod` first. Add `@a12/interpreter-zod` when the Interpreter’s transport and ownership foundation is available, and keep direct operators and warm Interpreter evaluation completely Zod-free.

## 12. Explicit exclusions

The initial integration does not:

- put Zod into Kotlin `commonMain`, Java APIs, or base TypeScript artifacts;
- accept native values directly in operator functions;
- wrap operators with `z.function()`;
- implement A12 parsing with Zod regexes or refinements;
- replace Interpreter validation with a generated Zod schema;
- convert `A12Truth.notEvaluated` to native `false`;
- infer authoritative compile-time model types from runtime-loaded models;
- define model registries, pools, Worker coordinators, or service concurrency;
- provide a Custom Operator SDK;
- provide a Java Zod adapter.

## References

- [Zod basic usage](https://zod.dev/basics)
- [Zod codecs](https://zod.dev/codecs)
- [Zod error formatting](https://zod.dev/error-formatting)
- [Zod library-author guidance](https://zod.dev/library-authors)
- [Zod JSON Schema conversion](https://zod.dev/json-schema)
- [Standard Schema](https://standardschema.dev/schema)
- [Node.js Worker Threads](https://nodejs.org/api/worker_threads.html)
