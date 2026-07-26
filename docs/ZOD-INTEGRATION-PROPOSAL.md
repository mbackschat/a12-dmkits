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

> **End-state vision capture.** This is the full destination assembled from interview Q&A and accumulated wishes, kept deliberately complete so no requirement is lost — not a scoped build order. The smallest buildable first increment (`@mbackschat/a12-operators-zod` scalar codecs + error bridge, §16 steps 1–2) is extracted to [TS-API-MVP-PROPOSAL](TS-API-MVP-PROPOSAL.md). The active interpreter publication does not by itself select a Zod package for release.
>
> **Why, and how much — the motivation this doc lacks:** the before/after worked study (the money-float and end-of-month bugs), the two-directional argument (Zod↔A12), and the open scope reading live in [TS-API-STUDY](TS-API-STUDY.md) §6 — the study that weighs this proposal alongside the Operator and Interpreter APIs as one family.

This proposal defines optional Zod adapters for the TypeScript projections of the A12 Operator API and Interpreter API.

The [Operator API proposal](OPERATOR-API-PROPOSAL.md) owns A12 scalar values, exact conversions, codecs, operator outcomes, structured scalar errors, and direct operator semantics. The [Interpreter API proposal](INTERPRETER-API-PROPOSAL.md) owns models, documents, snapshots, reports, ownership, active interpreters, registries, pooling, concurrency, and lifecycle. This proposal does not redefine those contracts.

The decision is to provide two optional TypeScript packages:

- `@mbackschat/a12-operators-zod` for exact native/wire-to-A12 scalar adapters and Zod composition.
- `@mbackschat/a12-interpreter-zod` for strict portable DTO schemas, an optional model-owner-bound snapshot adapter, and lossless validation-report projections.

Zod remains outside Kotlin `commonMain`, the base `@mbackschat/a12-operators` and `@mbackschat/a12-interpreter` JavaScript artifacts, the interpreter evaluator, and Java APIs. Applications that do not install and call the adapters pay no Zod dependency, bundle, startup, allocation, or runtime cost.

The initial supported JavaScript host follows the base packages’ qualified surface: Node. Browser and form examples describe intended integration behavior, but they become a support claim only after the underlying Operator/Interpreter packages pass browser packaging, conformance, and performance qualification.

## 1. Motivation

> Zod is the ergonomics oracle; the A12 kernel is the semantic oracle.

TypeScript applications commonly receive values as `unknown` from HTTP, queues, browser storage, configuration, plugins, and Worker messages. The A12 APIs deliberately operate on authentic A12 values and model-owned document objects rather than JavaScript primitives or structurally similar objects. An adapter is therefore useful at the point where untrusted or portable data crosses into those APIs.

Zod is a good integration target because it provides:

- runtime validation beginning at `unknown`;
- distinct input and output types;
- bidirectional codecs;
- object and array composition;
- indexed and nested issue paths;
- throwing and non-throwing parsing;
- established use in browser and Node applications.

The adapter must remain thin. It validates JavaScript and transport structure, delegates every A12 conversion or model-aware operation to the Kotlin-backed API, and translates expected A12 errors into Zod issues. It does not implement decimal, date, time-zone, operator, document, validation, rule, computation, or customization semantics in TypeScript.

## 2. Scope

| Integration | Included |
|---|---|
| Exact scalar wire/native values to authentic A12 values | Yes |
| Canonical encoding of authentic A12 values | Yes, where the core conversion is bidirectionally exact |
| Safe native integer number to `A12Integer` | Decode only |
| Zod object and array composition over encode-safe A12 codecs | Yes |
| Structured A12 conversion errors to nested Zod issues | Yes |
| Strict `PlacementDocumentInputV1` transport schema | Yes |
| Configurable transport resource limits | Yes, through a schema factory or surrounding ingress layer |
| Model-owner-bound placement decode plus core snapshot encode adapter | Optional |
| Validation report to form/Zod-style issue projection | Yes, without replacing or truncating the report |
| Advisory model-derived form/input schemas | Optional and explicitly non-authoritative |
| Standard Schema interoperability | Optional integration glue only |
| Node backend and Worker guidance | Yes, only for correct adapter placement and ownership |

## 3. Non-goals

The integration does not:

- make Zod a dependency of the Kotlin semantic core;
- add Zod to direct operator execution, expression parsing, AST traversal, model preparation, validation, or computation;
- accept native JavaScript values directly in operator functions;
- wrap every operator with `z.function()`;
- reproduce A12 parsing or validation through Zod regexes, coercion, transforms, or refinements;
- turn Zod into an A12 document model, rule language, custom-condition API, or second interpreter;
- adapt or register A12 runtime customization interfaces;
- treat `undefined`, `null`, defaults, catch values, or coercion as A12 document-state semantics;
- make a structurally similar JavaScript object an authentic A12 wrapper or `DocumentSnapshot`;
- replace A12 model/version ownership with TypeScript brands or `instanceof`;
- generate authoritative A12 validation from a Zod schema;
- promise compile-time model-specific TypeScript types for a model loaded at runtime;
- define the validation service, model registry, active-instance pool, Worker coordinator, or JVM concurrency model;
- provide a Java Zod adapter.

## 4. Package and dependency boundaries

```text
@mbackschat/a12-operators-zod
    ├── peer/singleton ──> @mbackschat/a12-operators
    └── peer ────────────> zod

@mbackschat/a12-interpreter-zod
    ├── peer/singleton ──> @mbackschat/a12-interpreter
    ├── peer/singleton ──> @mbackschat/a12-operators
    ├── peer/singleton ──> @mbackschat/a12-operators-zod
    └── peer ────────────> zod
```

Both Zod packages depend only on Zod and the kernel-free A12 base packages shown above. They remain kernel-free and MIT-licensed and have no direct or transitive dependency on an A12 Kernel API or runtime artifact.

The adapter packages should require Zod 4.1 or a later explicitly qualified compatible range as a peer dependency. They must not install private copies of Zod or the A12 base packages. Package-manager and bundler qualification must verify one physical `@mbackschat/a12-operators` instance per JavaScript realm, because the core authenticity contract deliberately rejects wrappers from duplicate package copies.

`@mbackschat/a12-interpreter-zod` uses `@mbackschat/a12-operators-zod` for the encode-safe codec marker and dependency-neutral Zod issue-context types, while core scalar codecs and conversion-issue types continue to come from `@mbackschat/a12-operators`. It does not duplicate those adapter mechanisms. The dependency does not put Zod into either base A12 package.

Ready-made public codecs may use ordinary Zod APIs for the simplest consumer experience. Adapter internals that accept caller-provided Zod definitions should follow [Zod’s library-author guidance](https://zod.dev/library-authors) and type against `zod/v4/core` where doing so materially improves compatibility with Zod and Zod Mini.

The TypeScript adapter code may contain:

- Zod input shapes;
- calls into dependency-free A12 codecs and model-aware document APIs;
- expected-error-to-Zod-issue translation;
- encode-safe composition metadata;
- form/report projections.

It may not contain an independent implementation of A12 semantics.

## 5. Shared adapter conventions

### 5.1 Parse unknown; decode known input; encode authentic output

The adapter follows the distinction made by [Zod codecs](https://zod.dev/codecs):

- `.parse(input: unknown)` and `.safeParse(input: unknown)` establish trust at an untyped boundary.
- `z.decode(codec, input)` and `z.safeDecode(codec, input)` accept the codec’s statically known input representation.
- `z.encode(codec, output)` and `z.safeEncode(codec, output)` accept the codec’s authentic A12 output representation.

The core A12 API remains usable without Zod and remains authoritative for construction, expected errors, and canonical encoding.

### 5.2 Encode-safe codecs need an adapter-owned type

Not every `z.ZodType<Output, Input>` is safely encodable. A schema containing a one-way transform can parse successfully but fail when traversed in the encode direction. The adapter therefore distinguishes its own verified, bidirectional codecs from arbitrary Zod schemas.

```ts
import * as z from "zod";

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
```

`A12ZodCodec<Input, Output>` is issued only by `@mbackschat/a12-operators-zod` or by its composition helpers. The hidden TypeScript brand communicates the encode-safe contract, while runtime adapter metadata lets the composition factory reject an ordinary schema that was only asserted to the branded type. This metadata is an accidental-misuse guard, not a security boundary against hostile JavaScript.

Every scalar codec uses the exact core companion guard for its output schema: `A12Integer.is(value: unknown)` for integer output rather than the wider `A12Number.is`, and the corresponding concrete guard for every other value kind. The settled core policy accepts wrappers only from the same physical `@mbackschat/a12-operators` package instance and JavaScript realm.

A wrapper from a duplicate bundle, Worker, iframe realm, structured clone, persistence layer, or network is foreign. The application encodes it to the exact portable representation and decodes a new local wrapper. Each encode transform delegates to the corresponding core `tryEncode` operation after the output guard succeeds.

`A12ZodDecoder<Input, Output>` deliberately carries no encode-safe brand. It is appropriate for one-way conversions such as safe JavaScript integer input to an `A12Integer` whose full output range cannot be encoded back to a safe JavaScript number.

It may participate in ordinary parse-only Zod object/array composition, but `a12Zod.object` and `a12Zod.array` reject it. Zod exposes an encode operation on ordinary schemas; calling that operation on this decoder is unsupported and throws for the underlying unidirectional transform rather than encoding a value.

### 5.3 Composition preserves encode safety

```ts
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

`a12Zod.object(...)` accepts only adapter-issued encode-safe members. Its input and output types are derived from the same exact key set so independent generic arguments cannot claim fields the schema does not contain. It uses strict unknown-key behavior rather than silently stripping misspelled members.

`a12Zod.array(...)` preserves element order, duplicates, and indexed paths. Its public TypeScript result is a `readonly` view consistent with `A12Codecs.array`; it does not promise a frozen runtime array. The resulting array is accepted directly by A12 collection operators.

Applications remain free to use ordinary `z.object(...)` and `z.array(...)` when they need parsing only and make no encoding promise.

### 5.4 Expected A12 errors become Zod issues

Zod does not automatically convert an exception thrown by a codec transform into a `ZodError`. Every adapter transform therefore calls the core non-throwing A12 operation, calls `ctx.addIssue(...)` once for every mapped expected failure, and returns `z.NEVER`.

Expected A12 failures are never thrown from the transform. The narrow explanatory context types below may model `ctx.addIssue(...)` as an issue collector internally, but the observable Zod behavior is the same.

```ts
type A12ZodIssuePathSegment = string | number;

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

interface A12ZodDocumentInputIssueMetadata
  extends A12ZodIssueMetadataBase {
  readonly family: "documentInput";
  readonly errorCode:
    "A12_DOCUMENT_INPUT_INVALID";
  readonly diagnostic:
    DocumentInputDiagnostic;
}

interface A12ZodRawIssue<
  Metadata extends
    A12ZodIssueMetadataBase =
      A12ZodIssueMetadataBase,
> {
  readonly code: "custom";
  readonly path?:
    readonly A12ZodIssuePathSegment[];
  readonly message: string;
  readonly params: {
    readonly a12:
      Metadata;
  };
}

interface A12ZodIssueCollector {
  push(issue: A12ZodRawIssue): number;
}

interface A12ZodTransformContext {
  readonly issues:
    A12ZodIssueCollector;
}

interface A12ZodConversionIssueBridge {
  addConversionIssues(
    context: A12ZodTransformContext,
    error: A12ConversionError,
  ): void;
}

interface A12InterpreterZodIssueBridge {
  addDocumentInputIssues(
    context: A12ZodTransformContext,
    error: DocumentInputError,
  ): void;
}

declare const a12ZodIssueBridge:
  A12ZodConversionIssueBridge;
declare const a12InterpreterZodIssueBridge:
  A12InterpreterZodIssueBridge;
```

`@mbackschat/a12-operators-zod` exports `A12ZodIssueMetadataBase`, `A12ZodConversionIssueMetadata`, the common raw-issue/context types, and `a12ZodIssueBridge`. `@mbackschat/a12-interpreter-zod` imports those dependency-neutral adapter types, defines `A12ZodDocumentInputIssueMetadata`, and exports `a12InterpreterZodIssueBridge`.

The operator adapter never imports Interpreter types. The public declarations may expose Zod’s concrete context and issue types rather than these narrow explanatory interfaces. The observable contract is:

- one or more A12 diagnostics become one or more Zod issues;
- every `A12ConversionError.issues` member becomes one Zod issue, and every `DocumentInputError.diagnostics` member becomes one Zod issue;
- conversion `issue.path` or document `diagnostic.inputPath` is appended exactly once to the surrounding Zod object/array path;
- each issue retains the original typed `A12ConversionIssue` or `DocumentInputDiagnostic` under adapter-owned metadata;
- `DocumentInputDiagnostic.modelPath` remains metadata and is never substituted for the Zod/transport issue path;
- the Zod message is presentation, not the machine-readable identity;
- raw input is not copied into the issue by default;
- only expected A12 conversion or document-input failures are translated;
- unexpected foreign exceptions, invariant failures, and callback failures propagate through their original integration channel.

## 6. Operator adapter

### 6.1 Scalar catalog

The initial adapter catalog is:

| Export | Core source | Input | Output | Direction | Reason |
|---|---|---|---|---|---|
| `a12Zod.string.fromString` | `A12Codecs.string` | `string` | `A12String` | codec | Exact |
| `a12Zod.boolean.fromBoolean` | `A12Codecs.boolean` | `boolean` | `A12Boolean` | codec | Exact |
| `a12Zod.confirm.fromTrue` | `A12Codecs.confirmed` | literal `true` | `A12Confirm` | codec | Exact; Confirm has no filled false value |
| `a12Zod.number.fromDecimalString` | `A12Codecs.decimalString` | `string` | `A12Number` | codec | Preserves exact decimal value |
| `a12Zod.integer.fromBigInt` | `A12Codecs.bigint` | `bigint` | `A12Integer` | codec | Exact integral representation |
| `a12Zod.integer.fromSafeNumber` | `A12Integer.tryFromSafeInteger` | safe integral `number` | `A12Integer` | decoder only | Every accepted input is exact, but not every `A12Integer` fits a safe JS number |
| `a12Zod.date.fromIsoString` | `A12Codecs.isoDate` | `string` | `A12Date` | codec | Canonical complete calendar date |
| `a12Zod.time.fromIsoString` | `A12Codecs.isoTime` | `string` | `A12Time` | codec | Canonical complete local time |
| `a12Zod.dateRange.fromIsoObject` | `A12Codecs.dateRange` | `A12DateRangeWire` | `A12DateRange` | codec | Explicit endpoints |
| `a12Zod.dateTime.fromInstantAndZone` | `A12Codecs.dateTime` | `A12DateTimeWire` | `A12DateTime` | codec for the exact zone set supported by the core | Explicit instant and evaluation zone |

The adapter catalog mirrors the exact conversions in the Operator API. If the core does not support a conversion exactly, the Zod adapter must not manufacture it.

There is no bidirectional `number` ↔ `A12Number` codec. Native JavaScript `number` cannot represent arbitrary exact A12 decimals.

There is no generic `Date` ↔ `A12Date` codec. JavaScript `Date` represents an instant; `A12Date` represents a calendar date.

There is no zone-less local-date-time codec. Daylight-saving overlap and gap behavior requires explicit A12 time-zone semantics.

### 6.2 Public adapter shape

```ts
import type {
  A12DateRangeWire,
  A12DateTimeWire,
} from "@mbackschat/a12-operators";

interface A12ZodAdapters
  extends A12ZodComposition {
  readonly string: {
    readonly fromString:
      A12ZodCodec<string, A12String>;
  };

  readonly boolean: {
    readonly fromBoolean:
      A12ZodCodec<boolean, A12Boolean>;
  };

  readonly confirm: {
    readonly fromTrue:
      A12ZodCodec<true, A12Confirm>;
  };

  readonly number: {
    readonly fromDecimalString:
      A12ZodCodec<string, A12Number>;
  };

  readonly integer: {
    readonly fromBigInt:
      A12ZodCodec<bigint, A12Integer>;

    readonly fromSafeNumber:
      A12ZodDecoder<number, A12Integer>;
  };

  readonly date: {
    readonly fromIsoString:
      A12ZodCodec<string, A12Date>;
  };

  readonly time: {
    readonly fromIsoString:
      A12ZodCodec<string, A12Time>;
  };

  readonly dateRange: {
    readonly fromIsoObject:
      A12ZodCodec<
        A12DateRangeWire,
        A12DateRange
      >;
  };

  readonly dateTime: {
    readonly fromInstantAndZone:
      A12ZodCodec<
        A12DateTimeWire,
        A12DateTime
      >;
  };
}

declare const a12Zod:
  A12ZodAdapters;
```

`fromSafeNumber` first requires a finite, safe, integral JavaScript number and then delegates construction to the Kotlin-backed integer conversion. It must not round, truncate, clamp, or encode an arbitrary `A12Integer` back to `number`.

`fromInstantAndZone` delegates exact-instant parsing, precision checks, supported-zone recognition and history, evaluation-context attachment, and canonical encoding to `A12Codecs.dateTime`. Its wire value already contains an instant, so daylight-saving overlap/gap resolution is not part of this conversion; that belongs to explicit local-date-time construction.

The initial accepted direct-API zone set is UTC only, and the adapter widens it only when the Operator API’s named-zone parity gate passes. Zod validates only the object shape.

### 6.3 Composed object example

```ts
import * as z from "zod";
import {
  A12Integer,
  operators,
} from "@mbackschat/a12-operators";
import type {
  A12Date,
  A12Number,
} from "@mbackschat/a12-operators";
import {
  a12Zod,
} from "@mbackschat/a12-operators-zod";
import type {
  A12ZodCodec,
} from "@mbackschat/a12-operators-zod";

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
    amount:
      a12Zod.number.fromDecimalString,
    dueDate:
      a12Zod.date.fromIsoString,
  });

declare const requestBody: unknown;

const orderValues: OrderValues =
  orderCodec.parse(requestBody);

const sevenDays: A12Integer =
  A12Integer.fromSafeInteger(7);

const reminderDate: A12Date =
  operators.date.addDays(
    orderValues.dueDate,
    sevenDays,
  );

const canonicalOrder: OrderWire =
  z.encode(orderCodec, orderValues);
```

### 6.4 Array example

```ts
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

If the third input element is invalid, the translated issue path includes index `2` and retains the nested A12 conversion code.

### 6.5 Generic values require a tag

An untagged string such as `"2026-07-19"` is ambiguous: it may be an A12 string or an A12 date. A future generic portable codec must therefore use an explicit discriminator.

```ts
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
      readonly value:
        A12DateRangeWire;
    }
  | {
      readonly kind: "dateTime";
      readonly value:
        A12DateTimeWire;
    };
```

Field-specific codecs should ship first. A tagged generic codec is useful for portable plugin or Worker protocols, not as the normal business-object API.

### 6.6 Predicates are not automatically Zod refinements

An A12 predicate returns `A12Truth`, whose third state is explicit non-evaluation rather than native `false`. The adapter must not silently coerce that state into a failed or passed Zod refinement.

If predicate-refinement helpers are added later, every call must select an explicit policy:

```ts
type A12TruthNotEvaluatedRefinementPolicy =
  | "reportIssue"
  | "skipRefinement";

interface A12RefinementOptions {
  readonly onNotEvaluated:
    A12TruthNotEvaluatedRefinementPolicy;
}
```

This is not required for the initial adapter. A future helper must accept an `A12Truth` result explicitly.

`A12Evaluation<T>`, including division and power outcomes, is a value-result algebra and is never treated as a Zod predicate or refinement. Direct operator calls remain the preferred API.

## 7. Interpreter transport adapter

### 7.1 Canonical portable input

The Interpreter API defines and owns the canonical placement DTO. The Zod adapter imports those public types without redeclaring their property contracts:

```ts
import type {
  FieldPlacementInput,
  GroupPlacementInput,
  PlacementDocumentInputV1,
} from "@mbackschat/a12-interpreter";
```

`GroupPlacementInput` is the Interpreter-owned type for one concrete group-row placement. `FieldPlacementInput` is the Interpreter-owned type for one concrete raw field placement. `PlacementDocumentInputV1` is the Interpreter-owned versioned envelope containing those readonly collections. Their exact property definitions remain single-sourced in the base package and the [Interpreter API proposal](INTERPRETER-API-PROPOSAL.md).

The Zod adapter validates this portable structure. It does not decode A12 field values or determine whether a path and coordinate vector are valid for a particular model.

### 7.2 Strict static schema

```ts
import * as z from "zod";
import type {
  FieldPlacementInput,
  GroupPlacementInput,
  PlacementDocumentInputV1,
} from "@mbackschat/a12-interpreter";

const placementCoordinateSchema:
  z.ZodNumber =
  z.int()
    .positive();

const groupPlacementInputSchema:
  z.ZodType<GroupPlacementInput> =
  z.strictObject({
    path: z.string(),
    coordinates:
      z.array(
        placementCoordinateSchema,
      ),
  });

const fieldPlacementInputSchema:
  z.ZodType<FieldPlacementInput> =
  z.strictObject({
    path: z.string(),
    coordinates:
      z.array(
        placementCoordinateSchema,
      ),
    raw: z.string(),
  });

const placementDocumentInputV1Schema:
  z.ZodType<PlacementDocumentInputV1> =
  z.strictObject({
    version: z.literal(1),
    groups:
      z.array(
        groupPlacementInputSchema,
      ),
    fields:
      z.array(
        fieldPlacementInputSchema,
      ),
  });
```

| Variable | Type | Meaning |
|---|---|---|
| `placementCoordinateSchema` | `z.ZodNumber` | positive safe-integer transport coordinate |
| `groupPlacementInputSchema` | `z.ZodType<GroupPlacementInput>` | strict group-placement object shape |
| `fieldPlacementInputSchema` | `z.ZodType<FieldPlacementInput>` | strict raw field-placement object shape |
| `placementDocumentInputV1Schema` | `z.ZodType<PlacementDocumentInputV1>` | strict versioned placement envelope |

The exported schemas reject unknown object members. They validate:

- the exact version literal;
- required object members;
- array placement;
- native strings;
- positive safe-integer coordinates;
- fixed version-level structural invariants.

They do not validate:

- whether a path exists in the selected model;
- whether coordinates have the correct arity for that path;
- repetition maxima;
- duplicate or conflicting placements;
- whether raw text is a valid field value;
- requiredness, uniqueness, legal character sets, or custom field types;
- rules, computations, relevance, suppression, or custom conditions;
- base-year, locale, or time-zone behavior.

Those are model-aware Interpreter responsibilities.

The module-constant schema establishes shape only. It is suitable for tests and already resource-bounded application values; an untrusted service boundary uses the configured factory in §7.3.

The static `PlacementDocumentInputV1` schema is fixture-tested with accepted and rejected examples. Fixtures cover the version discriminator, required and unknown members, placement arrays, native string fields, positive safe-integer coordinates, and representative structural failures.

### 7.3 Configurable ingress limits

One module-constant schema cannot encode every service’s resource policy. The package should provide a factory that consumes the core Interpreter limit contract directly:

```ts
declare function
createPlacementDocumentInputV1Schema(
  limits: DocumentDecodeLimits,
): z.ZodType<PlacementDocumentInputV1>;
```

`limits` has type `DocumentDecodeLimits`. Every member is a positive safe native configuration integer, not an A12 numeric value.

The factory validates the limits once and applies the six portable-shape limits for group placements, field placements, coordinate-vector length, path code units, raw code units per field, and total raw code units. It accepts the full core type so the exact same immutable object can be passed to model-aware decoding; `maxModelNestingDepth` is enforced there rather than by the static Zod schema.

Raw HTTP or message byte limits must be applied before JSON parsing. Zod is not a CPU, memory, queue, or request-deadline governor.

### 7.4 Static ingress followed by model-aware decode

```ts
import * as z from "zod";
import type {
  DocumentDecodeLimits,
  DocumentDecodeOptions,
  DocumentSnapshot,
  Interpreter,
  PlacementDocumentInputV1,
  PreparedModel,
  ValidationOptions,
  ValidationReport,
} from "@mbackschat/a12-interpreter";
import {
  createPlacementDocumentInputV1Schema,
} from "@mbackschat/a12-interpreter-zod";

declare const requestBody: unknown;
declare const prepared: PreparedModel;

const interpreter: Interpreter =
  prepared.createInterpreter();

const decodeLimits: DocumentDecodeLimits = {
  maxGroupPlacements: 10_000,
  maxFieldPlacements: 100_000,
  maxCoordinatesPerPlacement: 16,
  maxPathCodeUnits: 1_024,
  maxRawCodeUnitsPerField: 100_000,
  maxTotalRawCodeUnits: 5_000_000,
  maxModelNestingDepth: 64,
};

const placementDocumentInputV1Schema:
  z.ZodType<PlacementDocumentInputV1> =
  createPlacementDocumentInputV1Schema(
    decodeLimits,
  );

const documentInput:
  PlacementDocumentInputV1 =
  placementDocumentInputV1Schema.parse(
    requestBody,
  );

const decodeOptions: DocumentDecodeOptions = {
  limits: decodeLimits,
};

const snapshot: DocumentSnapshot =
  prepared.documents.readPlacements(
    documentInput,
    decodeOptions,
  );

const validationOptions:
  ValidationOptions = {
    locale: "en_US",
};

const validationReport:
  ValidationReport =
  interpreter.validateFull(
    snapshot,
    validationOptions,
  );
```

The numeric limits are an illustrative service policy, not library defaults. A deployment chooses them from its payload and model constraints and keeps them outside evaluator hot paths.

The two stages answer different questions:

1. Zod establishes that the external value has the versioned transport shape.
2. `readPlacements` establishes model paths, coordinate arity, model ownership, and A12 document storage semantics.

Zod transport diagnostics and model-aware Interpreter diagnostics remain separate channels. Within `DocumentInputDiagnostic`, `inputPath` identifies the portable transport location while `modelPath` identifies the attempted A12 model location; projection never substitutes `modelPath` for `inputPath`.

The dependency-free Interpreter API retains its own safe `unknown` decoding boundary. Zod is an optional alternative for applications already using Zod, not the only way to process untrusted input.

## 8. Optional model-owner-bound snapshot adapter

### 8.1 Purpose

Applications that repeatedly decode placement documents inside larger Zod request schemas may benefit from an owner-bound adapter:

```text
PlacementDocumentInputV1
    ↔ model-owned DocumentSnapshot
```

The two directions remain thin but deliberately use different public methods:

- `decodeSchema` delegates to the owner’s non-throwing `tryDecodePlacements` with one complete `DocumentDecodeLimits`;
- `encode` delegates directly to the same owner’s `encodePlacements`;
- expected `DocumentInputError` values become Zod issues;
- decoded output is authenticated with `isSnapshot(value: unknown)`;
- encode-side foreign ownership remains the core `ModelOwnerMismatchError` programmer-error channel rather than becoming a document-input Zod issue;
- foreign-owner or structurally similar objects are rejected.

This is not advertised as one generic `A12ZodCodec<PlacementDocumentInputV1, DocumentSnapshot>`. Generic Zod encode/safe-encode semantics would turn an encode-side owner guard into an ordinary schema issue and erase the Interpreter API’s programmer-error distinction.

### 8.2 Prepared-owner factory

```ts
interface PreparedZodDocumentAdapter {
  readonly owner:
    ModelOwnerIdentity;
  readonly modelVersion:
    ModelVersionIdentity;
  readonly decodeSchema:
    A12ZodDecoder<
      PlacementDocumentInputV1,
      DocumentSnapshot
    >;
  encode(
    snapshot: DocumentSnapshot,
  ): PlacementDocumentInputV1;
}

declare function
createPreparedDocumentAdapter(
  prepared: PreparedModel,
  limits: DocumentDecodeLimits,
): PreparedZodDocumentAdapter;
```

`prepared` has type `PreparedModel` and supplies the exact model owner plus its authoritative document decoder and encoder. `limits` has type `DocumentDecodeLimits` and is captured as immutable adapter configuration. The returned `PreparedZodDocumentAdapter.owner` is the same opaque `ModelOwnerIdentity`, while `modelVersion` identifies the exact local model version. Callers derive neither identity from a model alias, path, source string, or fingerprint.

The decode transform calls `prepared.documents.tryDecodePlacements(input, {limits})`, translates only its failed `DocumentInputError` result into Zod issues, and returns the authentic snapshot on success. Its output schema verifies that success through `prepared.documents.isSnapshot(value: unknown)` without structural typing or `instanceof`.

`encode(snapshot)` calls the core encoder. A foreign-owner snapshot therefore propagates `ModelOwnerMismatchError` and is never relabeled as invalid portable input.

### 8.3 Prepared-owner and lane-owned services

The public factory applies only to a local `PreparedModel` owner:

- If active lanes share that exact prepared owner and its `ModelOwnerIdentity` under a proven concurrency contract, one prepared-owner adapter may be cached with it.
- If each active lane has a distinct owner, the service uses the shared static Zod transport schema first, selects the lane, and then calls that lane’s core `PreparedDocumentApi.tryDecodePlacements` directly. It does not manufacture a public adapter from a service token or a structurally similar owner record.
- A model fingerprint or portable model-version identifier may locate an entry, but it is never sufficient to authenticate a snapshot.
- An alias is routing state and must never be the adapter cache key.

`RegisteredModel` is a routing/pinning token rather than a prepared owner and is never passed to `createPreparedDocumentAdapter`.

### 8.4 Cache rules

A model-bound adapter may capture only:

- its exact prepared owner;
- immutable schema definitions;
- the owner’s document decoder, encoder, and guard.

It must not capture:

- a request document;
- an active evaluation lease;
- validation options;
- locale, clock, or tenant state;
- a validation or computation report;
- mutable application callbacks.

The adapter is retired with its owner. Request parse results and snapshots are not schema-cache entries.

## 9. Validation-report and form projection

### 9.1 Canonical result remains `ValidationReport`

Zod issues cannot naturally preserve every A12 report property, including model-owned pointers, repetition coordinates, wildcard or partially known references, severity, polarity, authored/formal origin, suppression, and unsupported programs.

The adapter therefore provides a projection that always retains the original `ValidationReport`.

```ts
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
  readonly report:
    ValidationReport;
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

The caller provides `mapPointer` because application form paths are not A12 model paths. A finding or unsupported program that cannot be mapped remains visible in the corresponding `unmapped...` collection.

Findings map through their `errorPointer`. An unsupported custom field can map through its concrete `pointer`; an unsupported executable that exposes only a program path remains in `unmappedUnsupported` because a program path is not a document/form pointer. A later application-specific projection may add a separate program-path mapper without weakening this default.

### 9.2 Projection example

```ts
declare const validationReport:
  ValidationReport;
declare const mapApplicationFormPath:
  (
    pointer: ReportPointer,
  ) =>
    | readonly FormPathSegment[]
    | undefined;

const projectionOptions:
  A12FormProjectionOptions = {
  mapPointer: (
    pointer: ReportPointer,
  ):
    | readonly FormPathSegment[]
    | undefined =>
    mapApplicationFormPath(pointer),
};

const formProjection:
  A12FormIssueProjection =
  projectValidationReport(
    validationReport,
    projectionOptions,
  );
```

`mapApplicationFormPath` is an application-owned semantic mapping from an A12 report location to a form-library path. It must not rely on wrapper object identity.

The projection must:

- perform no document reevaluation;
- retain the canonical report by reference;
- retain the original finding or unsupported record on every mapped issue;
- preserve severity and stable A12 identity;
- leave unmappable records visible;
- avoid implying full support merely because `issues` is empty;
- avoid fabricating authored rule paths for formal findings.

An additional helper may convert `A12ProjectedFormIssue` values into ordinary Zod custom issues for a particular form library. That lossy view is presentation-only and must remain traceable to the lossless projection.

## 10. Model-derived Zod schemas

Model-derived schemas may be useful for:

- form hints;
- labels and descriptions;
- select options;
- basic scalar input widgets;
- portable path helpers;
- application metadata;
- build-time generated client types.

They are not authoritative for:

- formal A12 validation;
- absent, present-empty, and malformed raw document states;
- repetitions and correlated rows;
- relevance and suppression;
- rules and computations;
- custom field types and custom conditions;
- model-owned pointer authenticity;
- complete A12 validation or computation outcomes.

A model loaded at runtime yields dynamic metadata and generic TypeScript types. Precise per-model compile-time types require a separate build-time generator.

Rich Zod codecs containing transforms are not always faithfully representable as JSON Schema. If OpenAPI or JSON Schema output is required, the product should expose a separate plain wire-input schema and document that it does not perform A12 semantic decoding. See [Zod’s JSON Schema documentation](https://zod.dev/json-schema).

Model-derived schema caches must use an exact immutable model version or verified fingerprint appropriate to the derived portable artifact. A schema that creates or authenticates a `DocumentSnapshot` must additionally remain bound to the local prepared owner described in §8.

## 11. Standard Schema interoperability

If an A12 application-facing adapter later accepts caller-provided validation libraries, the [Standard Schema interface](https://standardschema.dev/schema) is preferable to exposing Zod-specific types at that generic boundary. Zod implements Standard Schema.

Standard Schema neither implements the interpreter’s native customization SPI nor adapts existing Kernel extensions. That contract has one owner: [Reusing every A12 Kernel evaluator extension](INTERPRETER-API-PROPOSAL.md#101-reusing-every-a12-kernel-evaluator-extension).

Any Standard Schema integration with the synchronous Interpreter path must:

- accept only synchronous results;
- reject Promise-returning validation;
- treat normal schema rejection separately from a thrown integration failure;
- adapt through the complete A12 customization contract rather than replace it;
- remain optional and outside Kotlin `commonMain`.

The initial Zod packages do not need Standard Schema support to ship their built-in scalar and transport adapters.

## 12. Backend and Worker integration guidance

### 12.1 Placement in a service

Zod belongs at untrusted transport ingress:

```text
HTTP, queue, or Worker message
    │
    ├── byte and resource limits
    ▼
strict Zod transport parse
    ▼
portable A12 DTO
    ▼
A12 model resolution and active-instance acquisition
    ▼
model-aware decode and evaluation
    ▼
canonical detached A12 report
    └── optional form or transport projection
```

The A12 service remains responsible for model versions, aliases, configuration identity, active instances, exclusivity, backpressure, lifecycle, and metrics as defined by the [Interpreter API proposal](INTERPRETER-API-PROPOSAL.md).

### 12.2 Worker-local schemas and adapters

Each Node Worker or process owns its own JavaScript realm. It should import or create its own:

- static Zod transport schemas;
- optional model-owner-bound snapshot adapters;
- A12 prepared resources and active interpreters, according to the Interpreter API;
- local metrics.

Do not send these values across a Worker boundary:

- Zod schema or codec instances;
- Kotlin/JS A12 wrappers;
- `PreparedModel`;
- `Interpreter`;
- `DocumentSnapshot`;
- model-owned pointer objects;
- callbacks, registries, or opaque local owner identities.

Send only versioned plain DTOs, model source or verified portable identifiers where the service contract permits them, and encoded detached result DTOs. Node Worker messages use structured cloning; functions cannot be cloned and class prototypes are not preserved. See the [Node.js Worker Threads documentation](https://nodejs.org/api/worker_threads.html).

Every Worker message payload is received as `unknown`. A Worker-facing service validates the complete versioned outer envelope—including message kind/version, request ID, portable model and configuration IDs, placement document, and portable validation options—before routing or model lookup.

`placementDocumentInputV1Schema` validates only the nested document member and is not sufficient as a Worker protocol schema. Replies carry the Interpreter API’s plain `ValidationReportWireV1` projection rather than Kotlin/JS result objects.

The exact coordinator envelope belongs to the Interpreter/service contract. An optional Zod schema may mirror that portable shape without owning its routing semantics.

### 12.3 Cache placement

- Static transport schemas are module constants in each Worker.
- A prepared-owner snapshot adapter is cached only with that exact local owner.
- A lane-owned service uses the static transport schema followed by its selected lane’s core decoder rather than caching a structurally constructed Zod owner adapter.
- Model aliases do not identify immutable schema or codec resources.
- Schemas never capture request state or active leases.
- Parse results, snapshots, and reports are never reused as schema-cache entries.

For interleaved use of model versions D1 and D2, each Worker retains distinct local schema/owner entries as needed. The Zod adapter must not cause model reparsing, schema rebuilding per request, or cross-version contamination.

### 12.4 Avoid mutable Zod globals

Zod exposes mutable global metadata and configuration facilities. A multi-model or multi-tenant service should not mutate `z.globalRegistry` or `z.config()` per request.

Recommendations:

- create caller-owned registries when metadata is needed;
- set any truly process-wide Zod configuration once at Worker startup;
- pass locale and presentation options explicitly;
- keep adapter transforms pure, synchronous, and request-independent;
- do not execute tenant-provided Zod functions unless a separate trusted-code or sandboxing product is designed.

Schema immutability does not make a callback safe if the callback closes over mutable request or tenant state.

### 12.5 On-keyup validation

Do not strictly parse and clone an entire placement document through Zod on every keypress. A form often needs to preserve temporarily malformed raw text so the Interpreter can report it.

The recommended flow is:

1. Use Zod for the outer transport or initial hydration boundary.
2. Preserve subsequent exact raw edits through the A12 document editing API.
3. Reuse the prepared model and appropriate active interpreter.
4. Let the Interpreter report formal and business findings.
5. Use strict scalar codecs when a field is committed or an application explicitly requires a valid standalone A12 value.

## 13. Security and privacy

The adapter packages must:

- reject unknown properties in canonical versioned transport DTOs;
- apply raw byte and JSON-parser nesting-depth limits before or during parsing;
- enforce positive-safe-integer limits for placement counts, coordinate-vector length (`maxCoordinatesPerPlacement`), path code units, per-field and total raw code units, and model source at the appropriate static/core ingress layer;
- enforce `maxModelNestingDepth` during model-aware Interpreter decoding rather than pretending it is a Zod or JSON nesting limit;
- avoid Zod coercion for A12 semantic values;
- avoid including raw input in issues by default;
- avoid logging whole document payloads or model source from generic error handlers;
- treat Zod transforms and refinements as trusted executable code;
- translate only known expected A12 failures into user-input issues;
- preserve unexpected exceptions as integration or invariant failures;
- authenticate A12 wrappers and snapshots through core runtime guards;
- encode runtime objects to plain DTOs before crossing Workers, processes, networks, persistence, or trust boundaries;
- keep locale, clock, time zone, and callbacks explicit and scoped.

The strict transport schema prevents a misspelled property such as `coordiantes` from being silently stripped and treated as a valid empty value.

## 14. Performance contract

The integration is acceptable only if:

1. Direct operators execute without any Zod schema, parse, clone, or issue translation.
2. Interpreter expression parsing, prepared programs, AST traversal, and evaluation are unchanged.
3. Zod schemas are constructed once per module, Worker, or exact owner-bound cache entry.
4. Untrusted transport input is parsed once at ingress, not once per operator or once per already-owned validation call.
5. No model or schema is prepared per validation request.
6. No-Zod applications load no Zod code and pay no Zod startup or runtime overhead.
7. The TypeScript adapter delegates all A12 semantics to Kotlin-backed operations.
8. D1/D2 interleaving retains separate warm A12 entries and does not rebuild schemas per switch.
9. Benchmarks report Zod transport parsing separately from A12 model-aware decoding, runtime waiting, evaluation, and projection.
10. A release benchmark gate shows no regression for no-Zod and already-owned-input paths in parser performance, prepared-AST reuse, AST traversal, warmup, or evaluation.

[Zod parsing](https://zod.dev/basics) traverses input and normally returns parsed output rather than blessing the original object. A Zod transport pass followed by model-aware A12 decoding can therefore traverse some structure twice. That cost is acceptable at an untrusted boundary only after measurement; it must not migrate into warm evaluation.

The optional prepared-owner adapter retains both traversals: Zod establishes the strict portable shape and the authoritative core decoder establishes model-aware document semantics. It must not be described or benchmarked as a one-pass optimization. A future shallow-envelope alternative could trade Zod’s detailed structural diagnostics for core `DocumentInputDiagnostic` values, but it requires its own measured contract and must never reproduce or bypass A12 semantics.

## 15. Feasibility and delivery shape

The integration is technically feasible once the core contracts in the sibling proposals are available.

| Work | Feasibility | Relative adapter effort |
|---|---:|---:|
| Scalar codecs backed by core A12 codecs | High | Small–medium |
| Encode-safe object and array composition | High | Small |
| A12 conversion-error bridge | High | Small–medium |
| Strict static placement DTO schema | High | Small |
| Configurable transport-limit schema factory | High | Small |
| Prepared-owner-bound snapshot adapter | High if the owner guard and encoder are public | Medium |
| Lane-owned static-schema plus core-decode integration | High | Small |
| Lossless report/form projection | High | Medium |
| Advisory model-derived schemas | Medium | Separate, consumer-driven increment |
| Build-time model-specific TypeScript generation | Medium | Separate product |

The principal integration risks are:

- a core conversion or document operation lacking a non-throwing expected-error form;
- incomplete stable error details, which would force the adapter to parse messages;
- failure to enforce the settled same-package-instance/same-realm wrapper policy through peer dependency resolution and exact output guards;
- a snapshot owner guard or canonical encoder not being available;
- Zod minor-version API or declaration changes affecting codec internals;
- consumers mistaking advisory generated schemas or projected issues for canonical A12 validation.

These risks should be resolved in the owning API proposal or isolated behind adapter-internal compatibility tests, not compensated for by duplicated TypeScript semantics.

## 16. Delivery sequence

`@mbackschat/a12-operators-zod` does not wait for Interpreter API development. It can ship as soon as the Operator API’s dependency-free scalar codecs, runtime guards, structured errors, and package-instance contract are stable.

Interpreter integration begins with the A12-native minimum transport and ownership foundation defined by the [Interpreter API proposal](INTERPRETER-API-PROPOSAL.md): the versioned placement DTO, decode limits, structured input diagnostics, prepared ownership, model-aware decode, typed fast read, canonical encode, and snapshot authenticity guard.

The strict Zod placement schema may be designed and tested earlier, but it should not be published before that authoritative core decoder exists. Otherwise the adapter risks becoming the accidental document specification or a second validator.

1. Publish and test the dependency-free A12 scalar codecs, runtime guards, and structured conversion errors from the Operator API.
2. Implement `@mbackschat/a12-operators-zod` scalar codecs and the error bridge.
3. Add branded encode-safe `object` and `array` composition with TypeScript declaration tests and runtime provenance checks.
4. Implement and stabilize the Interpreter API’s dependency-free minimum transport and ownership foundation.
5. Publish the strict `PlacementDocumentInputV1` schema and configurable transport-limit schema factory in `@mbackschat/a12-interpreter-zod`.
6. Add the prepared-owner-bound snapshot adapter using the stable core owner guard, model-aware decoder, and canonical encoder.
7. Add the lossless report/form projection only after complete canonical Interpreter reports are stable.
8. Add Worker-specific examples and conformance tests without embedding a Worker coordinator into the adapter package.
9. Evaluate advisory model-derived schemas and build-time generation as separate consumer-driven work.

## 17. Open integration decisions

The following decisions remain local to the optional adapters and do not change A12 semantics:

1. The exact supported Zod peer-version range and compatibility test matrix.
2. Whether ready-made packages support both ordinary Zod and Zod Mini in the first release.
3. The stable public location of A12 metadata inside a Zod custom issue.
4. Whether frozen output arrays provide enough value to justify a separate explicit helper; ordinary array composition makes no such promise.
5. Whether the prepared-owner-bound snapshot adapter saves enough consumer code to ship in the first Interpreter adapter release; it is not a traversal optimization.
6. Which real form-library integrations justify convenience projections beyond the lossless generic projection.
7. Whether advisory model-derived schemas and build-time model-specific generation become separate packages.

## 18. Recommendation

Ship optional Zod integration as a boundary layer over complete A12-native APIs.

Start with `@mbackschat/a12-operators-zod`: exact scalar codecs, safe-number decode-only support, encode-safe object/array composition, and structured issue translation. Add `@mbackschat/a12-interpreter-zod` for the strict placement DTO at the same boundary. Add the owner-bound snapshot adapter and form projections only where they preserve complete ownership, error channels, and report information.

Keep direct operators and warm Interpreter evaluation entirely Zod-free. The adapters should feel natural to TypeScript developers while remaining mechanically dependent on the semantics and ownership contracts defined by the [Operator API proposal](OPERATOR-API-PROPOSAL.md) and [Interpreter API proposal](INTERPRETER-API-PROPOSAL.md).

## References

- [Zod basic usage](https://zod.dev/basics)
- [Zod codecs](https://zod.dev/codecs)
- [Zod error customization](https://zod.dev/error-customization)
- [Zod error formatting](https://zod.dev/error-formatting)
- [Zod metadata and registries](https://zod.dev/metadata)
- [Zod library-author guidance](https://zod.dev/library-authors)
- [Zod Mini](https://zod.dev/packages/mini)
- [Zod JSON Schema conversion](https://zod.dev/json-schema)
- [Standard Schema](https://standardschema.dev/schema)
- [Node.js Worker Threads](https://nodejs.org/api/worker_threads.html)
