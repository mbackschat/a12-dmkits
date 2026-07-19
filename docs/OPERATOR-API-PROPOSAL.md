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

# Proposal: Direct A12 value operators for TypeScript from Kotlin Multiplatform

## Status and recommendation

Expose A12 value-level operators as ordinary typed functions over authentic A12 values. A developer should be able to wrap a native value, call `operators.dateTime.differenceInHours` or another operator, and unwrap the result without creating a model, document, interpreter, session, expression, parser, or AST.

```ts
declare const operators: A12Operators;

const utc: A12TimeZone = A12TimeZone.utc();
const start: A12DateTime = A12DateTime.parseLocal("2026-07-19T08:00:00", utc);
const end: A12DateTime = A12DateTime.parseLocal("2026-07-19T10:30:00", utc);
const hours: A12Integer = operators.dateTime.differenceInHours(start, end);
const nativeHours: number = hours.toSafeInteger();
```

TypeScript is the primary product surface. The semantics live once in Kotlin Multiplatform `commonMain`; Kotlin/JS provides the TypeScript API, and the JVM target provides a secondary Java-friendly projection over the same implementation.

The base implementation and both projections are clean-room, kernel-free, and MIT-licensed. They do not import, link, reflect over, or bundle the licensed A12 Kernel.

## Motivation

TypeScript applications frequently need one A12 operation rather than a full evaluator:

- calculate an A12 date-time difference;
- shift a date using A12 end-of-month rules;
- round an exact decimal using A12 behavior;
- apply A12 string indexing;
- compare dates, numbers, times, or ranges;
- reduce a supplied array of A12 values;
- reuse the same operation in a form, import, service, or application function.

Native JavaScript values are not a sufficient semantic boundary. A JavaScript `number` cannot represent arbitrary business decimals exactly, a JavaScript `Date` is an instant rather than an A12 date-only value, and host string, regex, time-zone, and arithmetic behavior can differ from A12.

The API therefore makes conversion explicit while keeping direct calls small.

## Scope

An operation belongs in this API when its result depends only on supplied A12 values and explicit scalar arguments inherent to the operation.

| Required | Examples |
|---|---|
| A12 scalar values | string, Boolean, Confirm, number, integer refinement, date, time, date-time, date range |
| Exact wrapping and unwrapping | decimal text, `bigint`, ISO date/time, structured date-time and range values |
| Direct single-value operators | date shifts, date-time differences, string length, numeric rounding |
| Comparisons and truth | typed comparison and three-state `A12Truth` |
| Value collections | sums, extrema, counts, membership, range overlap |
| Eager composition | nesting, native `map`, and result/evaluation combinators |
| Catalog accountability | every catalog capability has a direct, interpreter, authoring, or parity-gated disposition |

| Required but separately packaged compatibility deliverable | Purpose |
|---|---|
| Interpreter Kernel compatibility adapters | Allow projects to reuse existing Kernel Custom Field Type and Custom Condition implementations without adding a Kernel dependency to the Operator or Interpreter core |

Applications that use only license-free native registries do not install the Kernel adapters. The adapters are nevertheless a required compatibility deliverable for existing A12 projects and are not part of the direct Operator API.

| Optional and separately packaged TypeScript convenience | Purpose |
|---|---|
| Zod adapter | Compose exact A12 codecs with Zod at a TypeScript boundary |
| Family import paths | Improve browser tree shaking if Kotlin/JS measurements justify them |

| Excluded | Reason |
|---|---|
| Models, documents, field paths, repetitions, rules, computations, and expression evaluation | They require interpreter context |
| Empty, malformed, absent, or suppressed field state | These are document states, not scalar values |
| Custom Field Type and Custom Condition registries | They are model-bound Interpreter capabilities |
| Custom operators | The operator set remains closed and catalog-accountable |
| Arbitrary JSON structures | Structured document handling belongs to the Interpreter API |
| A fluent expression or validation DSL | Direct operators are eager functions, not an expression graph |

See the [Interpreter API proposal](INTERPRETER-API-PROPOSAL.md) for model-bound behavior and the [Zod integration proposal](ZOD-INTEGRATION-PROPOSAL.md) for the optional TypeScript adapter.

## Public API

### A12 values

The direct API exposes immutable semantic values:

| Type | Meaning |
|---|---|
| `A12String` | A supplied text value |
| `A12Boolean` | A wrapped two-state Boolean value |
| `A12Confirm` | The distinct filled confirmation value; there is no false Confirm |
| `A12Number` | An arbitrary-precision A12 decimal |
| `A12Integer` | A checked integral refinement of `A12Number`; its runtime kind remains `"number"` |
| `A12Date` | A complete valid calendar date |
| `A12Time` | A valid time of day |
| `A12TimeZone` | An explicitly supported evaluation-zone identity |
| `A12DateTime` | An exact instant paired with explicit direct-API evaluation-zone context |
| `A12DateRange` | An inclusive pair of complete dates |
| `A12Value` | The closed union of storable scalar values above |

`A12Integer` is not a new kernel value kind. It makes counts, component values, indexes, scales, and offsets type-safe while remaining usable wherever an `A12Number` is accepted.

Support values remain outside `A12Value`:

| Type | Purpose |
|---|---|
| `A12EvaluationTime` | One explicit instant resolved in one supported zone for `Today` and `Now` |
| `A12BaseYear` | An explicit base year with number, start-date, and full-year-range projections |
| `A12Tolerance` | The closed `ONE`, `TWO`, `FIVE`, or `TEN` tolerance set |
| `A12Pattern` | A compiled pattern utility; the entire type remains absent until regex parity is exact |
| `A12Truth` | A three-state condition result |
| `A12Evaluation<T>` | A value or legitimate semantic non-evaluation |
| `A12Option<T>` | A present or absent result for utilities without an empty identity |
| `A12Result<T, E>` | A success or typed failure |

```ts
type A12Tolerance = "ONE" | "TWO" | "FIVE" | "TEN";
```

The public values expose stable discriminants and guards that accept `unknown`:

```ts
type A12Value =
  | A12String
  | A12Boolean
  | A12Confirm
  | A12Number
  | A12Date
  | A12Time
  | A12DateTime
  | A12DateRange;

interface A12Number {
  readonly kind: "number";
  toDecimalString(): string;
  toNumberLossy(): number;
}

interface A12Integer extends A12Number {
  toBigInt(): bigint;
  toSafeInteger(): number;
  tryToSafeInteger(): A12Result<number, A12ConversionError>;
}

interface A12Date {
  readonly kind: "date";
  toIsoString(): string;
}

interface A12DateTime {
  readonly kind: "dateTime";
  readonly evaluationTimeZone: A12TimeZone;
  toInstantIsoString(): string;
  toLocalIsoString(): string;
  toJsInstantDate(): Date;
}

interface A12ValueGuard<T extends A12Value> {
  is(value: unknown): value is T;
}
```

Each runtime companion, such as `A12Date`, combines construction with `A12ValueGuard<A12Date>`. Guards validate an authentic library-issued wrapper, not merely an object with a matching `kind`.

### Construction and exact conversion

The shortest path remains a named constructor or parser:

```ts
declare const nativeText: string;
declare const nativeFlag: boolean;
declare const nativeInteger: bigint;

const text: A12String = A12String.of(nativeText);
const flag: A12Boolean = A12Boolean.of(nativeFlag);
const confirmed: A12Confirm = A12Confirm.confirmed();
const amount: A12Number = A12Number.parse("19.95");
const count: A12Integer = A12Integer.fromBigInt(nativeInteger);
const date: A12Date = A12Date.parseIso("2026-07-19");
const time: A12Time = A12Time.parseIso("14:30:00");

const nativeTextAgain: string = text.toNative();
const nativeFlagAgain: boolean = flag.toNative();
const nativeAmount: string = amount.toDecimalString();
const nativeCount: bigint = count.toBigInt();
const nativeDate: string = date.toIsoString();
const nativeTime: string = time.toIsoString();
```

Exact decimal text is the primary numeric boundary. JavaScript `number` conversion is visibly lossy and never appears as an arithmetic overload:

```ts
declare const nativeApproximation: number;
declare const amount: A12Number;

const approximateA12Value: A12Number = A12Number.fromNumberLossy(nativeApproximation);
const approximateNativeValue: number = amount.toNumberLossy();
```

Safe native integers and `bigint` remain exact:

```ts
const smallCount: A12Integer = A12Integer.fromSafeInteger(42);
const nativeSmallCount: number = smallCount.toSafeInteger();
```

A JavaScript `Date` is accepted only as an instant and only with an explicit evaluation zone:

```ts
declare const nativeInstant: Date;

const utc: A12TimeZone = A12TimeZone.utc();
const dateTime: A12DateTime = A12DateTime.fromJsDate(nativeInstant, utc);
const nativeInstantAgain: Date = dateTime.toJsInstantDate();
```

There is no implicit `Date` to `A12Date` conversion. `fromJsDate` rejects sub-second precision unless the caller uses a separately named rounding or truncation conversion.

Structured values use explicit constructors:

```ts
const year: A12Integer = A12Integer.fromSafeInteger(2026);
const month: A12Integer = A12Integer.fromSafeInteger(7);
const day: A12Integer = A12Integer.fromSafeInteger(19);
const hours: A12Integer = A12Integer.fromSafeInteger(14);
const minutes: A12Integer = A12Integer.fromSafeInteger(30);
const seconds: A12Integer = A12Integer.fromSafeInteger(0);

const date: A12Date = A12Date.of(year, month, day);
const time: A12Time = A12Time.of(hours, minutes, seconds);
const dateTime: A12DateTime = A12DateTime.combine(date, time, A12TimeZone.utc());
const range: A12DateRange = A12DateRange.of(date, operators.date.addDays(date, A12Integer.fromSafeInteger(7)));
```

Strict construction produces a valid value or a conversion error. The interpreter-only expression behavior that preserves an unreal `Date(...)` candidate for `Valid(...)` or `Invalid(...)` does not weaken `A12Date`.

### Exact codecs

Constructors are convenient for one value. A small dependency-free codec contract enables transports, arrays, and optional schema-library adapters without introducing a schema language:

```ts
interface A12Codec<Input, Output> {
  parse(input: unknown): Output;
  tryParse(input: unknown): A12Result<Output, A12ConversionError>;
  decode(input: Input): Output;
  tryDecode(input: Input): A12Result<Output, A12ConversionError>;
  encode(output: Output): Input;
  tryEncode(output: Output): A12Result<Input, A12ConversionError>;
}
```

The canonical codecs are:

| Codec | Exact representation |
|---|---|
| `A12Codecs.string` | `string` ↔ `A12String` |
| `A12Codecs.boolean` | `boolean` ↔ `A12Boolean` |
| `A12Codecs.confirmed` | literal `true` ↔ `A12Confirm` |
| `A12Codecs.decimalString` | decimal `string` ↔ `A12Number` |
| `A12Codecs.bigint` | `bigint` ↔ `A12Integer` |
| `A12Codecs.isoDate` | ISO date `string` ↔ `A12Date` |
| `A12Codecs.isoTime` | ISO time `string` ↔ `A12Time` |
| `A12Codecs.dateRange` | `{start, end}` ↔ `A12DateRange` |
| `A12Codecs.dateTime` | `{instant, timeZone}` ↔ `A12DateTime` for the supported zone set |

```ts
interface A12DateRangeWire {
  readonly start: string;
  readonly end: string;
}

interface A12DateTimeWire {
  readonly instant: string;
  readonly timeZone: string;
}

declare const input: unknown;

const amountCodec: A12Codec<string, A12Number> = A12Codecs.decimalString;
const amount: A12Number = amountCodec.parse(input);
const canonicalAmount: string = amountCodec.encode(amount);

const dateTimeCodec: A12Codec<A12DateTimeWire, A12DateTime> = A12Codecs.dateTime;
```

Only exact bidirectional conversions are codecs. Decimal `number`, zone-less JavaScript `Date`, and zone-less local date-time conversions remain checked or explicitly lossy one-way operations.

Every codec obeys these laws:

- `decode(encode(value))` preserves the semantic A12 value and required direct-API context;
- `encode(decode(input))` may canonicalize spelling but preserves accepted semantics;
- JVM and JavaScript return the same value or structured issue for the same supported input;
- a lossy conversion is never presented as an exact codec.

### Arrays and lists

TypeScript uses ordinary homogeneous `readonly` arrays and Kotlin/JVM uses lists. There is no `A12List` value kind.

An array codec composes any exact scalar or structured codec:

```ts
const amountsCodec: A12Codec<readonly string[], readonly A12Number[]> = A12Codecs.array(A12Codecs.decimalString);
const nativeAmounts: readonly string[] = ["19.95", "7.50"];
const amounts: readonly A12Number[] = amountsCodec.decode(nativeAmounts);
const canonicalAmounts: readonly string[] = amountsCodec.encode(amounts);
```

Array conversion preserves order and duplicates. It rejects sparse holes, `null`, `undefined`, and wrong element kinds, and reports the zero-based index of each failing element.

Callers that already have scalar converters may use the lower-level adapters:

```ts
type A12NativeScalar = string | boolean | number | bigint | Date;

interface A12ArraysApi {
  wrapAll<Input extends A12NativeScalar, Output extends A12Value>(inputs: readonly Input[], wrap: (input: Input) => Output): readonly Output[];
  tryWrapAll<Input extends A12NativeScalar, Output extends A12Value, Failure extends A12ConversionError = A12ConversionError>(inputs: readonly Input[], wrap: (input: Input) => A12Result<Output, Failure>): A12ArrayConversionResult<Output, Failure>;
  unwrapAll<Input extends A12Value, Output extends A12NativeScalar>(values: readonly Input[], unwrap: (value: Input) => Output): readonly Output[];
  tryUnwrapAll<Input extends A12Value, Output extends A12NativeScalar, Failure extends A12ConversionError = A12ConversionError>(values: readonly Input[], unwrap: (value: Input) => A12Result<Output, Failure>): A12ArrayConversionResult<Output, Failure>;
}

declare const A12Arrays: A12ArraysApi;
declare const nativeValues: readonly string[];

const attemptedValues: A12Result<readonly A12Number[], A12ArrayConversionError> = A12Arrays.tryWrapAll(
  nativeValues,
  (value: string): A12Result<A12Number, A12ConversionError> => A12Number.tryParse(value),
);
```

`wrapAll` is fail-fast and turns a thrown `A12ConversionError` into one indexed `A12ArrayConversionError`; a foreign exception propagates unchanged. The `try…` forms evaluate every element, accumulate every indexed conversion failure, and retain both the flattened issue paths and the original scalar errors grouped by index.

Native `map` and `filter` remain the normal choice for element-wise work. The operator namespace adds only reductions and predicates with real A12 semantics.

### Operator namespaces

The primary export is a stateless, discoverable namespace:

```ts
interface A12Operators {
  readonly pointInTime: A12PointInTimeOperators;
  readonly boolean: A12BooleanOperators;
  readonly truth: A12TruthOperators;
  readonly date: A12DateOperators;
  readonly dateTime: A12DateTimeOperators;
  readonly temporal: A12TemporalOperators;
  readonly time: A12TimeOperators;
  readonly string: A12StringOperators;
  readonly number: A12NumberOperators;
  readonly compare: A12CompareOperators;
  readonly dateRange: A12DateRangeOperators;
  readonly values: A12ValueCollectionOperators;
  readonly stringValues: A12StringValueCollectionOperators;
}

declare const operators: A12Operators;
```

The required families are:

| Namespace | Direct operations |
|---|---|
| `pointInTime` | `today(sample)`, `now(sample)` with an explicit `A12EvaluationTime` |
| `boolean` | `and`, `or`, `not` over known `A12Boolean` values |
| `truth` | `and`, `or`, `not` over `A12Truth` |
| `date` | differences in days/months/years; add days/months/years; year/month/day/quarter; collection minimum/maximum |
| `dateTime` | differences in days/hours/minutes/seconds; add days/hours/minutes/seconds; date and time parts |
| `temporal` | mixed date/date-time day difference and temporal components where the catalog permits them |
| `time` | hour, minute, and second components |
| `string` | length, concatenate, A12 ranges, string equality/inequality, concatenate all |
| `number` | add, subtract, absolute value, minimum, maximum, rounding, tolerance, sum, collection extrema |
| `compare` | equality and ordering for compatible total scalar kinds |
| `dateRange` | endpoints, pair overlap, any-pair overlap, target overlap |
| `values` | membership, occurrence count, and distinct count for kinds with total equality |
| `stringValues` | explicitly exact supplied-string membership, count, and distinct count |

Catalog variants that differ only in where operands come from map to one direct function. For example, field-source and already-resolved variants of an absolute-value operator do not create duplicate TypeScript methods.

Representative family signatures make the typing contract concrete:

```ts
type A12TemporalValue = A12Date | A12DateTime;

interface A12DateOperators {
  differenceInDays(start: A12Date, end: A12Date): A12Integer;
  differenceInMonths(start: A12Date, end: A12Date): A12Integer;
  differenceInYears(start: A12Date, end: A12Date): A12Integer;
  addDays(value: A12Date, amount: A12Integer): A12Date;
  addMonths(value: A12Date, amount: A12Integer): A12Date;
  addYears(value: A12Date, amount: A12Integer): A12Date;
  year(value: A12Date): A12Integer;
  month(value: A12Date): A12Integer;
  day(value: A12Date): A12Integer;
  quarter(value: A12Date): A12Integer;
  minAll(values: readonly A12Date[]): A12Option<A12Date>;
  maxAll(values: readonly A12Date[]): A12Option<A12Date>;
}

interface A12DateTimeOperators {
  differenceInDays(start: A12DateTime, end: A12DateTime): A12Integer;
  differenceInHours(start: A12DateTime, end: A12DateTime): A12Integer;
  differenceInMinutes(start: A12DateTime, end: A12DateTime): A12Integer;
  differenceInSeconds(start: A12DateTime, end: A12DateTime): A12Integer;
  addDays(value: A12DateTime, amount: A12Integer): A12DateTime;
  addHours(value: A12DateTime, amount: A12Integer): A12DateTime;
  addMinutes(value: A12DateTime, amount: A12Integer): A12DateTime;
  addSeconds(value: A12DateTime, amount: A12Integer): A12DateTime;
  datePart(value: A12DateTime): A12Date;
  timePart(value: A12DateTime): A12Time;
}

interface A12StringOperators {
  length(value: A12String): A12Integer;
  concat(first: A12String, second: A12String, ...rest: A12String[]): A12String;
  rangeAsString(value: A12String, startInclusive: A12Integer, endInclusive: A12Integer): A12String;
  rangeAsNumber(value: A12String, startInclusive: A12Integer, endInclusive: A12Integer): A12Number;
  equal(left: A12String, right: A12String): A12Truth;
  notEqual(left: A12String, right: A12String): A12Truth;
  concatAll(values: readonly A12String[]): A12String;
}

interface A12NumberOperators {
  add(left: A12Number, right: A12Number): A12Number;
  subtract(left: A12Number, right: A12Number): A12Number;
  abs(value: A12Number): A12Number;
  min(left: A12Number, right: A12Number): A12Number;
  max(left: A12Number, right: A12Number): A12Number;
  roundDown(value: A12Number, scale: A12Integer): A12Number;
  roundUp(value: A12Number, scale: A12Integer): A12Number;
  roundAccounting(value: A12Number, scale: A12Integer): A12Number;
  differsByMoreThan(left: A12Number, right: A12Number, tolerance: A12Tolerance): A12Truth;
  sumAll(values: readonly A12Number[]): A12Number;
  minAll(values: readonly A12Number[]): A12Number;
  maxAll(values: readonly A12Number[]): A12Number;
}

interface A12DateRangeOperators {
  start(value: A12DateRange): A12Date;
  end(value: A12DateRange): A12Date;
  overlaps(left: A12DateRange, right: A12DateRange): A12Truth;
  anyPairOverlaps(values: readonly A12DateRange[]): A12Truth;
  overlapsAny(target: A12DateRange, values: readonly A12DateRange[]): A12Truth;
}
```

All other family signatures follow the same rule: authentic A12 inputs, explicit context where required, and an A12 value, `A12Truth`, `A12Option`, or `A12Evaluation` result.

## Typed usage examples

### Direct date and date-time operations

```ts
declare const invoiceDate: A12Date;
declare const firstInstant: A12DateTime;
declare const secondInstant: A12DateTime;

const oneMonth: A12Integer = A12Integer.fromSafeInteger(1);
const nextMonth: A12Date = operators.date.addMonths(invoiceDate, oneMonth);
const hours: A12Integer = operators.dateTime.differenceInHours(firstInstant, secondInstant);
```

Date shifts preserve A12 leap-year and end-of-month behavior. Sub-day date-time differences use exact instants and truncate toward zero.

### Strings

```ts
declare const source: A12String;

const firstIndex: A12Integer = A12Integer.fromSafeInteger(1);
const fourthIndex: A12Integer = A12Integer.fromSafeInteger(4);
const length: A12Integer = operators.string.length(source);
const prefix: A12String = operators.string.rangeAsString(source, firstIndex, fourthIndex);
```

A12 string ranges use one-based inclusive indexes and the kernel’s UTF-16 semantics. Native `substring` is not an implementation substitute.

### Exact numbers and collection operators

```ts
const amounts: readonly A12Number[] = [
  A12Number.parse("19.95"),
  A12Number.parse("7.50"),
];
const total: A12Number = operators.number.sumAll(amounts);
const roundedTotal: A12Number = operators.number.roundAccounting(total, A12Integer.fromSafeInteger(2));
const nativeTotal: string = roundedTotal.toDecimalString();
```

Collection functions operate on supplied values only. They do not inherit document behavior for absent, empty, malformed, filtered, or repeated cells.

### Native array composition

```ts
declare const amounts: readonly A12Number[];

const scale: A12Integer = A12Integer.fromSafeInteger(2);
const roundedAmounts: readonly A12Number[] = amounts.map(
  (amount: A12Number): A12Number => operators.number.roundAccounting(amount, scale),
);
```

Operators are normal functions and require no special pipeline.

### Explicit clock input

```ts
declare const nativeNow: Date;

const sample: A12EvaluationTime = A12EvaluationTime.fromJsDate(nativeNow, A12TimeZone.utc());
const today: A12Date = operators.pointInTime.today(sample);
const now: A12DateTime = operators.pointInTime.now(sample);
```

`Today` and `Now` never read a process-global clock or zone.

## Results, truth, and errors

### Three distinct failure channels

The API preserves three concepts that must not be collapsed:

| Situation | Public result |
|---|---|
| Invalid native input or checked conversion | `A12ConversionError`, thrown or returned through `A12Result` |
| Invalid direct-API precondition | `A12OperatorError` |
| Legal kernel operands that produce no value | `A12Evaluation.notEvaluated` |

Condition-producing operators return three-state truth:

```ts
type A12Truth =
  | { readonly kind: "true" }
  | { readonly kind: "false" }
  | { readonly kind: "notEvaluated" };

type A12NonEvaluationReason =
  | { readonly code: "divisionByZero" }
  | {
      readonly code: "powerDomain";
      readonly detail: "exponentOutOfRange" | "zeroToNegativeExponent";
    };

type A12Evaluation<T> =
  | { readonly kind: "value"; readonly value: T }
  | { readonly kind: "notEvaluated"; readonly reason: A12NonEvaluationReason };

type A12Option<T> =
  | { readonly kind: "some"; readonly value: T }
  | { readonly kind: "none" };

type A12Result<T, E = A12Error> =
  | { readonly ok: true; readonly value: T }
  | { readonly ok: false; readonly error: E };
```

Division by zero and invalid power domains are legitimate non-evaluation, not exceptions, `undefined`, `NaN`, infinity, or conversion errors. String equality also preserves A12 non-evaluation for an explicit empty string through `A12Truth`.

### Structured errors

Errors are normal JavaScript `Error` subtypes with stable machine-readable discriminants and structured issues:

```ts
type A12IssuePathSegment = string | number;

type A12OperationIdentity =
  | { readonly kind: "catalog"; readonly catalogId: string }
  | { readonly kind: "directUtility"; readonly utilityId: string };

type A12NativeInputKind = "string" | "boolean" | "number" | "bigint" | "javascriptDate" | "object" | "array" | "a12Value";
type A12ReceivedInputKind = A12NativeInputKind | "null" | "undefined" | "function" | "symbol" | "foreignA12Value";
type A12WrapperKind = "string" | "boolean" | "confirm" | "number" | "date" | "time" | "dateTime" | "dateRange" | "timeZone" | "evaluationTime" | "baseYear" | "pattern";

interface A12IssueBase<Code extends string> {
  readonly code: Code;
  readonly path: readonly A12IssuePathSegment[];
  readonly message: string;
}

type A12ConversionIssue =
  | (A12IssueBase<"invalidNativeType"> & {
      readonly expected: A12NativeInputKind;
      readonly received: A12ReceivedInputKind;
    })
  | (A12IssueBase<"invalidDecimal"> & {
      readonly reason: "empty" | "invalidSyntax" | "notFinite" | "outsideSupportedRange";
    })
  | (A12IssueBase<"invalidInteger"> & {
      readonly reason: "notIntegral" | "unsafeNativeNumber" | "outsideSupportedRange";
    })
  | (A12IssueBase<"invalidTemporalValue"> & {
      readonly valueKind: "date" | "time" | "dateTime";
      readonly reason: "empty" | "invalidSyntax" | "invalidComponent";
    })
  | A12IssueBase<"reversedDateRange">
  | (A12IssueBase<"unsupportedTimeZone"> & {
      readonly timeZone: string;
    })
  | (A12IssueBase<"nonexistentLocalTime"> & {
      readonly timeZone: string;
    })
  | (A12IssueBase<"temporalPrecisionLoss"> & {
      readonly source: "javascriptDate";
      readonly unsupportedUnit: "milliseconds";
    })
  | (A12IssueBase<"invalidPattern"> & {
      readonly reason: "invalidSyntax" | "unsupportedFeature";
      readonly position?: number;
    })
  | (A12IssueBase<"invalidA12Wrapper"> & {
      readonly expectedKind: A12WrapperKind;
      readonly received: A12ReceivedInputKind;
    });

type A12OperatorIssue =
  | (A12IssueBase<"operatorArgumentOutOfRange"> & {
      readonly operation: A12OperationIdentity;
      readonly argument: string;
      readonly minimum?: string;
      readonly maximum?: string;
      readonly actual: string;
    })
  | (A12IssueBase<"incompatibleTimeZones"> & {
      readonly operation: A12OperationIdentity;
      readonly leftTimeZone: string;
      readonly rightTimeZone: string;
    })
  | (A12IssueBase<"incompatibleCollectionLengths"> & {
      readonly operation: A12OperationIdentity;
      readonly leftLength: number;
      readonly rightLength: number;
    })
  | (A12IssueBase<"invalidOperatorOperand"> & {
      readonly operation: A12OperationIdentity;
      readonly argument: string;
      readonly expectedKind: A12WrapperKind;
      readonly received: A12ReceivedInputKind;
    });

type A12ScalarIssue = A12ConversionIssue | A12OperatorIssue;
type A12ErrorCode = "A12_CONVERSION_ERROR" | "A12_OPERATOR_ERROR" | "A12_ARRAY_CONVERSION_ERROR";

interface A12Error<Issue extends A12ScalarIssue = A12ScalarIssue> extends Error {
  readonly kind: "conversion" | "operator" | "array-conversion";
  readonly code: A12ErrorCode;
  readonly issues: readonly [Issue, ...Issue[]];
  readonly message: string;
}

interface A12ConversionError extends A12Error<A12ConversionIssue> {
  readonly kind: "conversion";
  readonly code: "A12_CONVERSION_ERROR";
}

interface A12OperatorError extends A12Error<A12OperatorIssue> {
  readonly kind: "operator";
  readonly code: "A12_OPERATOR_ERROR";
}

interface A12IndexedConversionError<Failure extends A12ConversionError = A12ConversionError> {
  readonly index: number;
  readonly error: Failure;
}

interface A12ArrayConversionError<Failure extends A12ConversionError = A12ConversionError> extends A12Error<A12ConversionIssue> {
  readonly kind: "array-conversion";
  readonly code: "A12_ARRAY_CONVERSION_ERROR";
  readonly items: readonly [A12IndexedConversionError<Failure>, ...A12IndexedConversionError<Failure>[]];
}

type A12ArrayConversionResult<T, Failure extends A12ConversionError = A12ConversionError> = A12Result<readonly T[], A12ArrayConversionError<Failure>>;

interface A12ErrorGuards {
  is(value: unknown): value is A12Error;
  isConversion(value: unknown): value is A12ConversionError;
  isOperator(value: unknown): value is A12OperatorError;
  isArrayConversion(value: unknown): value is A12ArrayConversionError;
}

declare const A12Errors: A12ErrorGuards;
```

The issue unions are closed and exhaustively narrowable. Their variants retain the details developers need without parsing a message:

| Issue | Structured details |
|---|---|
| `invalidNativeType` | expected and received native kinds |
| `invalidDecimal` | empty, syntax, finite, or supported-range reason |
| `invalidInteger` | non-integral, unsafe native number, or supported-range reason |
| `invalidTemporalValue` | date/time/date-time kind and syntax/component reason |
| `reversedDateRange` | offending path |
| `unsupportedTimeZone`, `nonexistentLocalTime` | zone identity |
| `temporalPrecisionLoss` | JavaScript `Date` and unsupported millisecond unit |
| `invalidPattern` | syntax/unsupported-feature reason and optional position |
| `invalidA12Wrapper` | expected wrapper kind and received/foreign kind |
| `operatorArgumentOutOfRange` | operation identity, argument, bounds, and actual value |
| `incompatibleTimeZones` | operation and both zones |
| `incompatibleCollectionLengths` | operation and both lengths |
| `invalidOperatorOperand` | operation, argument, expected kind, and received kind |

`message` is explanatory and never the machine contract. Errors do not copy rejected raw input by default, error guards do not rely on cross-module `instanceof`, and an operator issue retains either its exact catalog ID or the stable ID of a classified direct utility.

Throwing constructors have adjacent result forms:

```ts
declare const input: string;

const date: A12Date = A12Date.parseIso(input);
const attemptedDate: A12Result<A12Date, A12ConversionError> = A12Date.tryParseIso(input);
```

### Combinators

Total operators compose by ordinary function calls. `A12Result` and `A12Evaluation` provide `map`, `flatMap`, `zipWith`, `match`, and `getOrElse` for their explicit channels.

```ts
declare const nativeStart: string;
declare const nativeEnd: string;

const difference: A12Result<A12Integer, A12ConversionError> = A12Result.zipWith(
  A12Date.tryParseIso(nativeStart),
  A12Date.tryParseIso(nativeEnd),
  (start: A12Date, end: A12Date): A12Integer => operators.date.differenceInDays(start, end),
);
```

Partial numeric operations use `A12Evaluation`:

```ts
interface A12ExactArithmeticNumberOperators extends A12NumberOperators {
  multiply(left: A12Number, right: A12Number): A12Number;
  divide(left: A12Number, right: A12Number): A12Evaluation<A12Number>;
  power(value: A12Number, exponent: A12Integer): A12Evaluation<A12Number>;
  sumOfProducts(left: readonly A12Number[], right: readonly A12Number[]): A12Number;
}

declare const exactNumberOperators: A12ExactArithmeticNumberOperators;
declare const numerator: A12Number;
declare const denominator: A12Number;

const quotient: A12Evaluation<A12Number> = exactNumberOperators.divide(numerator, denominator);
const display: string = A12Evaluation.match(
  quotient,
  {
    value: (value: A12Number): string => value.toDecimalString(),
    notEvaluated: (reason: A12NonEvaluationReason): string => reason.code,
  },
);
```

These combinators are eager and construct no expression graph. They do not catch exceptions thrown by application callbacks.

Their propagation rules are deterministic:

| Combinator | Rule |
|---|---|
| `A12Result.map` / `flatMap` | Do not invoke the callback for a failure |
| `A12Result.zipWith` | Return the left failure first when both inputs fail |
| `A12Result.capture` | Catch only `A12OperatorError`; every foreign thrown value propagates |
| `A12Evaluation.map` / `flatMap` | Do not invoke the callback for `notEvaluated` |
| `A12Evaluation.zipWith` | Return the first non-evaluation from left to right |
| `match` | Force handling of both result branches |
| `getOrElse` | Require an explicit error- or reason-aware fallback |

## Semantic invariants and gotchas

These are observable A12 contracts, not implementation suggestions.

### Value and conversion invariants

| Invariant | Consequence |
|---|---|
| `A12Integer` is a checked refinement of `A12Number` | It retains runtime `kind: "number"` and is not a new storable kernel kind |
| Confirm is not Boolean | `A12Confirm.confirmed()` is the only filled Confirm value; false is rejected, an unset Confirm is document state, and Confirm compares only with Confirm |
| TypeScript brands are static only | Runtime guards accept `unknown` and verify a Kotlin/JS-issued authentic wrapper |
| `instanceof` is not the cross-realm contract | Duplicate bundles, workers, iframes, and structured clones are foreign; transfer the exact codec representation and reconstruct locally |
| A public `kind` property is insufficient | `A12Number.is(value)` accepts authentic integers, while `A12Integer.is(value)` additionally proves integrality |
| Exact and lossy conversion are visibly different | Decimal strings and `bigint` are exact; JavaScript decimal `number` conversion is explicitly named `Lossy` |
| Canonical output need not preserve source spelling | For example, `"19.950"` may encode as `"19.95"` without changing the value |
| Construction and operation failures differ | Reversed range endpoints are a conversion error; an out-of-range operator scale or index is an operator error |
| Checked unwrapping never silently truncates | A value outside JavaScript’s safe-integer domain returns a conversion result or throws the declared conversion error |
| Guards and errors are JavaScript-safe | A foreign wrapper produces a structured `A12OperatorError`, never a raw Kotlin cast failure |

### Truth and partial evaluation

| Invariant | Consequence |
|---|---|
| `A12Truth.notEvaluated` is not false | Callers must inspect or match the three-state result |
| Truth composition uses strong Kleene logic | False decides `and`, true decides `or`, otherwise non-evaluation propagates; `not(notEvaluated)` remains non-evaluated |
| Legal partial arithmetic is not exceptional | Division by zero and invalid power domains return `A12Evaluation.notEvaluated` |
| API misuse remains exceptional | A wrong wrapper, incompatible zone, unsupported argument range, or unequal dot-product length is an `A12OperatorError` |
| Results are never collapsed to host sentinels | No semantic outcome becomes `undefined`, `null`, `NaN`, infinity, or an untyped exception |

### Temporal invariants

| Invariant | Consequence |
|---|---|
| `A12Date` is a calendar date, not a JavaScript instant | A JavaScript `Date` cannot become an `A12Date` without an explicit calendar projection outside this exact API |
| `A12DateTime` preserves an exact instant plus evaluation-zone context | Chained sub-day operations survive daylight-saving overlaps without reducing the value to decorated local text |
| The direct zone is explicit context | It is not claimed to be intrinsic kernel scalar storage identity |
| Local text alone is not an exact date-time round trip | During an overlap, one local spelling can identify two instants; `{instant, timeZone}` is the exact wire form |
| JavaScript `Date` has finer precision | Non-zero milliseconds are rejected unless an explicitly named rounding/truncation conversion is chosen |
| UTC is the initial guaranteed zone | Named zones, including `Europe/Berlin`, remain absent until history, gaps, overlaps, and arithmetic are kernel-exact |
| Two date-times need compatible evaluation zones | Incompatible contexts produce `A12OperatorError` unless an explicit exact conversion exists |
| Sub-day differences are instant-based | Hours, minutes, and seconds truncate toward zero, including negative results |
| `dateTime.addDays` is a wall-calendar operation | It preserves local time and uses the kernel’s directional daylight-saving landing behavior |
| Mixed date/date-time day difference has an explicit rule | The date operand is midnight in the date-time operand’s zone; whole wall-clock days truncate toward zero |
| Month and year shifts remain date-only where required by the catalog | The API does not broaden legal operand domains for convenience |
| Date shifts retain A12 calendar behavior | End-of-month clamping, leap years, and whole-period differences must match exactly |
| `Today`, `Now`, and base year are explicit | One immutable `A12EvaluationTime` sample avoids clock drift; `A12BaseYear` exposes number, start-date, and full-year-range projections |

### Numeric, string, comparison, and range invariants

| Invariant | Consequence |
|---|---|
| Exact decimals do not use JavaScript floating point | Operator overloads never accept native decimal `number`; semantic arithmetic uses the A12 decimal implementation |
| Rounding scale is constrained | It is an `A12Integer` in the catalog-supported `0..14` range |
| Tolerance is a closed catalog domain | `A12Tolerance` is only `ONE`, `TWO`, `FIVE`, or `TEN` |
| String indexing follows A12 | It is UTF-16, one-based, and inclusive |
| String-range edge cases are not JavaScript substring behavior | A start below one or after the end is an operator error; an end beyond the string produces an empty `A12String` |
| `rangeAsNumber` is not native coercion | It returns the selected digit run as an A12 number and otherwise returns zero |
| Empty-string comparison is partial | String `equal` and `notEqual` may return `A12Truth.notEvaluated`; generic total comparison therefore excludes strings |
| Strings are not ordered by the catalog | No direct string less-than or greater-than operation is invented |
| Generic comparison is kind-safe | Boolean, Confirm, number, date, time, date-time, and date range equality use only legal compatible pairs; ordering is limited to number/date/time/date-time |
| Wrapper identity is not value equality | Callers use typed comparison operators rather than `===` |
| Date ranges are inclusive | Construction rejects `start > end`; overlap includes both endpoints |
| Pattern compilation precedes matching | Invalid or unsupported patterns are conversion failures, and `A12Pattern` plus match methods remain absent until the regex profile is exact |

### Collection invariants

| Invariant | Consequence |
|---|---|
| Arrays/lists are host containers | TypeScript uses `readonly T[]`, Kotlin/JVM uses `List<T>`, and no `A12List` kernel kind is invented |
| Collections are homogeneous | Mixed JSON-like value bags are outside this API |
| Order and duplicate occurrences are preserved | Counts, distinct counts, products, and pair overlap see the supplied occurrences |
| Array conversion errors retain indexes | `items` preserves each original scalar error with its zero-based source index; flattened issue paths carry the same index |
| Bulk conversion does not retain raw values | The caller already owns the input; this avoids duplication and accidental disclosure |
| Catalog reduction identities remain exact | Numeric `sumAll([])`, `minAll([])`, `maxAll([])`, and `sumOfProducts([], [])` return zero; `concatAll([])` returns the empty string |
| Predicate identities remain exact | Empty membership, count, distinct-count, and overlap inputs return their catalog-defined false/zero results |
| Date extrema are a direct utility | `date.minAll([])` and `date.maxAll([])` return `A12Option.none` rather than inventing a date |
| Dot product requires equal lengths | Unequal lengths produce `A12OperatorError`; the operation remains gated with multiplication parity |
| Any-pair range overlap compares occurrences with `i < j` | One occurrence is false; two equal range occurrences overlap |
| Exact supplied-string collections are explicitly utilities | Empty `A12String` is an ordinary supplied value in `stringValues`; contextual catalog string membership remains interpreter-owned |
| A document repetition is not a value array | Missing cells, row coordinates, filters, and starred paths are resolved by the Interpreter before extracted values enter this API |

### Direct versus interpreter semantics

| Case | Owner and rationale |
|---|---|
| Strict `A12Date.of(...)` | Direct API; it produces a valid date or conversion error |
| `Valid(Date(...))` / `Invalid(Date(...))` | Interpreter; the kernel deliberately keeps an unreal construction candidate observable, so failed strict construction cannot model it |
| Partial dates and format/base-year completion | Interpreter; completion depends on field declarations and model context |
| `FieldValueAsNumber` / `FieldValueAsString` | Interpreter; behavior depends on field kind, empty/malformed state, enum mapping, and formatting |
| Stored enum code | Directly representable as `A12String` after resolution |
| Enum category, fill predicate, repetition, semantic index, `Having` | Interpreter; each inspects model or document state |
| Custom Field Type or Custom Condition | Interpreter registry; required separate platform compatibility artifacts connect existing Kernel implementations without contaminating the license-free core |
| Direct operator | No customization registry or adapter dispatch participates |

Clock, base year, time zone, decimal context, regex state, locale, and other process globals never affect a direct call implicitly. Required context is an immutable typed value.

## Operator completeness and parity gates

The API must remain accountable to the live operator catalog. A generated or CI-checked capability matrix assigns every catalog entry and semantic facet to exactly one disposition:

| Disposition | Meaning |
|---|---|
| Direct operator | Operates only on supplied values and is exposed here |
| Interpreter | Requires model, document, field, repetition, rule, or customization context |
| Authoring | Constructs symbolic expressions or rules rather than evaluating supplied values |
| Parity-gated | Belongs in the direct API but is absent until Kotlin/JVM and Kotlin/JS behavior is exact |

Every public direct function records its catalog identity or is explicitly classified as a value constructor, exact conversion, collection utility, or semantic combinator. One catalog entry may have both a direct value facet and an interpreter-only contextual facet.

The matrix is a coverage guard, not a semantic source. Catalog additions fail CI until classified, and a parity-gated function is absent from the callable baseline type rather than implemented approximately.

Known release gates are:

| Capability | Gate |
|---|---|
| Core decimals | Construction, addition, subtraction, comparison, rounding, and canonical rendering must be exact before `A12Number` ships |
| Multiplication, division, and power | The JavaScript implementation must match the A12 50-significant-digit arithmetic context |
| Pattern operators | The accepted Java/JavaScript regex domain must be exact and mechanically enforced |
| Named time zones | Supported identifiers and history, including Berlin daylight-saving behavior, must match exactly |

The closed gaps belong to the same namespace. Until then, extension types or capability metadata may describe them, but the baseline API must not pretend they are available.

## Kotlin ownership and performance

The semantic implementation lives once in a kernel-free Kotlin Multiplatform layer:

```text
Kotlin commonMain operator semantics
        ├── Kotlin/JS TypeScript façade
        ├── JVM/Java-friendly façade
        └── kernel-free Interpreter resolved-value evaluator
```

`commonMain` owns semantic decisions. `jsMain` and `jvmMain` adapt exports, host values, arrays/lists, and errors only; neither reimplements an operator.

The interpreter resolves fields and context, then calls the same internal semantic primitives. It uses a trusted internal entry point and must not allocate public wrappers, run public authenticity guards, or translate public errors for every AST node.

Direct calls have no session allocation, parsing, AST traversal, model lookup, document iteration, customization lookup, registry lookup, or adapter dispatch. Values are immutable, operators are stateless and reentrant, and no mutable host-global decimal, clock, regex, or time-zone state is shared.

Extracting shared semantics is acceptable only if existing interpreter parsing, AST traversal, startup, warmup, throughput, interleaved-model scaling, allocation, and footprint do not regress. The existing interpreter benchmark and differential suite are release gates for that invariant.

## Packaging and adapters

The primary TypeScript package is a Kotlin/JS projection such as `@a12/operators`. It contains A12 values, exact codecs, errors, combinators, and the `operators` namespace with no interpreter, Zod, or kernel dependency.

The JVM artifact exposes the same semantics with Java-friendly conversions and collection projections. Java support is secondary and must not dictate TypeScript naming or produce a second semantic implementation.

An optional Zod package depends outward on the TypeScript package and translates codecs and structured issues. Zod never enters `commonMain`, the primary JavaScript bundle, direct operator calls, or the JVM API.

Kernel compatibility is relevant only when an application reuses existing Kernel implementations of model-bound Custom Field Types or Custom Conditions. Providing those separate platform-specific Interpreter artifacts is required for existing-project compatibility; installing them is unnecessary for applications that use only the license-free native registries.

Each compatibility artifact carries the applicable Kernel dependency and license. Neither the operator artifact nor the kernel-free Interpreter depends on it.

No adapter can register or override direct operators.

## Explicit exclusions

This proposal does not provide:

- `DocumentV2` or another document type in the direct API;
- an interpreter session or hidden default context;
- field-path or repetition operators over raw arrays;
- arbitrary object/JSON validation;
- symbolic expression construction;
- custom operator registration;
- native JavaScript `number` arithmetic presented as A12 arithmetic;
- implicit JavaScript `Date` conversion to an A12 calendar date;
- `undefined`, `null`, or exceptions as substitutes for A12 truth or semantic non-evaluation;
- approximate regex, decimal, or named-time-zone behavior;
- a runtime dependency on the licensed A12 Kernel.

## Feasibility

The proposal is feasible because the interpreter already needs the same clean-room scalar semantics and Kotlin Multiplatform can expose one implementation to both JavaScript and JVM. The main risks are semantic completeness, Kotlin/JS façade quality, and the known decimal, regex, and named-time-zone parity gaps, not the direct-call architecture.

The API can ship incrementally by operator family only when the capability matrix remains exhaustive and unavailable functions are visibly gated. The non-negotiable acceptance conditions are exact A12 behavior, an ergonomic TypeScript surface, no licensed runtime dependency, and no interpreter parsing, AST, warmup, throughput, or allocation regression.
