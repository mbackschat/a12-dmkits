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

## Status

This proposal defines how A12 value-level operators become directly callable from TypeScript without a model, document, interpreter session, expression string, parser, AST, or rule.

The declarations use the proposed public TypeScript names consistently; catalog mappings identify the corresponding kernel operation where one exists. TypeScript is the primary design and acceptance surface. Kotlin Multiplatform is the implementation strategy, and a secondary JVM projection reuses the same Kotlin implementation.

The base Kotlin Multiplatform implementation and its TypeScript/JVM projections are clean-room, kernel-free, MIT-licensed artifacts. They do not import, link, reflect over, or bundle any A12 Kernel API or runtime artifact. Kernel source and differential execution are semantic evidence only, never a shipped or runtime dependency.

Model-bound validation, documents, symbolic expressions, rules, computations, Custom Field Types, and Custom Conditions are intentionally outside this proposal. They belong to the [Interpreter API proposal](INTERPRETER-API-PROPOSAL.md); its required, separately packaged Kernel compatibility adapters do not extend or participate in direct operators.

## 1. Recommendation

Expose immutable A12 semantic values and ordinary typed operator functions:

```ts
declare const operators: A12Operators;
declare const start: A12DateTime;
declare const end: A12DateTime;

const hours: A12Integer =
  operators.dateTime.differenceInHours(start, end);
```

The call is synchronous and deterministic. There is no setup, lifecycle, model, document, parser, AST, or manufactured evaluation context.

For example:

```ts
const utc: A12TimeZone = A12TimeZone.utc();
const start: A12DateTime =
  A12DateTime.parseLocal("2024-03-31T01:30:00", utc);
const end: A12DateTime =
  A12DateTime.parseLocal("2024-03-31T03:30:00", utc);

const hours: A12Integer =
  operators.dateTime.differenceInHours(start, end);
const nativeHours: number = hours.toSafeInteger();
```

This proposal exposes the complete **value-level A12 semantics** boundary. Catalog entries that inspect fields, models, repetitions, or rules remain interpreter operations; catalog variants that differ only by operand source collapse to one direct function.

## 2. Motivation

TypeScript applications often need one piece of A12 behavior, not an evaluator. Examples include:

- calculate the kernel-equivalent difference between two date-times;
- add a month using A12 end-of-month rules;
- round a decimal using A12 accounting behavior;
- count or slice a string using A12 indexing semantics;
- test two date ranges for overlap;
- reuse the same operator in a form mapper, import pipeline, service, array transformation, or application function.

Requiring an interpreter session for those jobs creates accidental complexity. The caller already has the operands. A model, document, field path, parser, and prepared program add no information.

Native JavaScript values are not authoritative operands: `number` cannot represent arbitrary decimals exactly; `Date` is an instant rather than a date-only or local date-time value; and A12 time-zone, UTF-16 indexing, comparison, and precision rules differ from host defaults.

The right boundary is therefore explicit but lightweight: wrap native input as an A12 value, call operators, and unwrap the result deliberately.

## 3. Scope

An operation belongs in this API when its result is a function of:

1. supplied, already-valid A12 values; and
2. an explicit scalar argument that is inherent to the operation, such as a rounding scale.

| In scope | Examples |
|---|---|
| Value construction and conversion | decimal text, safe integer, ISO date, local date-time plus zone |
| Direct value operators | date differences, date shifts, string length, rounding, comparison |
| Value collections | sum supplied numbers, concatenate supplied strings, overlap supplied ranges |
| Immediate composition | nesting, ordinary functions, native `map`/`filter` |
| Semantic-result composition | `A12Result` and `A12Evaluation` `map`, `flatMap`, `zipWith`, `match`, and fallback operations |

Field state and declarations, paths and repetition context, document aggregates and predicates, implicit `Today`/`Now`/`BaseYear`, rule findings and suppression, computation orchestration, and symbolic expressions all require information outside supplied values. They are interpreter concerns, not hidden parameters to a direct operator.

The catalog maps to this boundary as follows:

| Catalog family | Direct API treatment |
|---|---|
| arithmetic, comparison, rounding, strings, dates, times, date-times, date ranges | typed scalar functions |
| `True`/`False`, `Date`, `Time`, `DateTime`, `DateRange` | value constructors |
| `Valid`/`Invalid` over constructed dates | interpreter-owned candidate-expression semantics; strict direct construction produces only a valid `A12Date` |
| `Abs`/`AbsValue`, `Round*`/`Round*Value` | one function per semantic operation; field-source variants do not duplicate it |
| `Sum`, `Min`, `Max`, `NumberOfDifferentValues`, `NumberOfValueInFields`, `SumOfProducts` | collection functions over supplied values |
| `Today`, `Now`, `BaseYear` | available only through explicit time/base-year inputs; never host globals |
| partial date fragments and format-driven completion | interpreter resolves the field’s format/base-year context and then exposes a complete `A12Date` |
| field/group fill predicates, field coercions, enum categories, semantic indexes, repetition operators, `Having`, Custom Conditions, Custom Field Types | interpreter-only because their operands are model/document state; customization uses native registries and required, separately packaged Kernel compatibility adapters |
| pattern operations, named time zones, and affected decimal operations | direct in principle but absent until cross-platform parity is exact |

This classification is represented by a generated or CI-checked capability matrix against the live operator catalog, so every catalog entry has an explicit direct, interpreter, authoring, or parity-gated disposition and a new entry cannot disappear from the TypeScript surface unnoticed.

## 4. A12 value types

### 4.1 Value family

The public surface should have a small immutable value family:

| Type | Meaning |
|---|---|
| `A12String` | a supplied text value |
| `A12Boolean` | a wrapped boolean data value |
| `A12Confirm` | the distinct filled confirmation marker; it has no `false` value |
| `A12Number` | an arbitrary-precision A12 decimal |
| `A12Integer` | a checked integral refinement of `A12Number`, also used for counts and offsets; its runtime kind remains `"number"` |
| `A12Date` | a complete calendar date |
| `A12Time` | a time of day |
| `A12TimeZone` | an explicitly supported A12/model time-zone identity |
| `A12DateTime` | an exact instant paired with explicit direct-API time-zone evaluation context; the zone is not claimed to be intrinsic kernel scalar identity |
| `A12DateRange` | an inclusive pair of complete dates |
| `A12Pattern` | a validated operator utility; gated and absent until the Java/JavaScript regex profile is exact, and deliberately not an `A12Value` |
| `A12Value` | the closed discriminated union of storable scalar values: string, Boolean, Confirm, number, date, time, date-time, and date range |
| `A12Truth` | a three-state condition result: true, false, or not evaluated |
| `A12Evaluation<T>` | a value-producing operator result: value `T` or legitimate kernel non-evaluation |
| `A12NonEvaluationReason` | a closed structured reason for legitimate value non-evaluation |
| `A12Option<T>` | an explicit optional result with `some` and `none` cases |
| `A12Result<T, E>` | a success value `T` or a typed error `E` |
| `A12Codec<Input, Output>` | a dependency-free exact, synchronous, bidirectional conversion contract |
| `A12Codecs` | canonical scalar codecs and array-codec composition |
| `A12EvaluationTime` | one exact instant resolved in one supported time zone |
| `A12BaseYear` | an explicit base year with number, start-date, and date-range projections |
| `A12Tolerance` | the closed `ONE`, `TWO`, `FIVE`, or `TEN` tolerance set |
| `A12ScalarIssue` | the closed structured union of scalar conversion and operator issues |
| `A12Error` | the stable JS-safe base for typed scalar and array-conversion failures |
| `A12ErrorCode` | the closed conversion/operator/array-conversion error-family discriminator |
| `A12ConversionError` | invalid native-to-A12 construction or checked unwrapping |
| `A12OperatorError` | an actual direct-API precondition violation such as an invalid range, scale, collection shape, evaluation-zone pairing, or foreign wrapper; never legal kernel non-evaluation |
| `A12ErrorGuards` | runtime `A12Errors` value for safely narrowing a caught `unknown` value to an A12 error subtype |
| `A12ResultCompanion` | runtime `A12Result` value providing typed result combinators |
| `A12NativeScalar` | supported native scalar union: `string`, `boolean`, `number`, `bigint`, or `Date` |
| `A12ArrayConversionResult<T>` | a bulk-conversion result whose failure contains indexed conversion errors |
| `A12ArrayConversionError` | one bulk-conversion failure containing every indexed scalar error |
| `A12IndexedConversionError` | one scalar conversion failure plus its source array index |
| `A12Operators` | the stateless exported `operators` namespace and its typed operator families |
| `A12ArrayAdapters` | typed contract of the stateless `A12Arrays` bulk wrapping/unwrapping value |

Invalid dates, invalid times, unresolved daylight-saving gaps, and malformed numbers do not become values. The throwing construction form either returns a valid immutable value or throws `A12ConversionError`; its `try…` counterpart returns the same failure through `A12Result`.

The value boundary has these invariants:

- `A12Value` is the exported discriminated union of concrete storable scalar types.
- `A12Integer` refines `A12Number`; it does not add an `"integer"` discriminant or a second kernel value kind.
- `A12Pattern`, truth/evaluation types, context values, results, codecs, and errors are support types rather than document values.
- Every `A12Value` has a stable `kind`, an `unknown`-accepting guard, and its applicable canonical conversions.
- A `kind` check narrows an already-authenticated `A12Value`; it does not authenticate an arbitrary object.
- Semantic equality comes from typed comparison operators, never wrapper identity.
- Confirm has no false/native projection, and `operators.dateRange` exposes range endpoints.
- A general JSON document/value codec is not part of this API.

Condition-producing operators return `A12Truth`: `true`, `false`, or `notEvaluated`. Value-producing operators that are legal but partial return `A12Evaluation<T>`: `value` or `notEvaluated` with a structured reason.

These channels must not collapse into `false`, `undefined`, an exception, or a conversion failure. `A12Boolean.asTruth()` produces a known truth value.

The generic `A12Result<T, E>` leaves `E` unbounded so other layers can carry their own typed failures without pretending they are scalar errors. `A12ArrayConversionResult<T>` specializes it with indexed conversion failures, while `A12Codec<Input, Output>` describes exact synchronous conversion rather than a schema or expression language.

### 4.2 Complete TypeScript declarations

The complete value, result, truth, error, constructor, and codec contracts are:

```ts
declare const a12SemanticValueBrand: unique symbol;
declare const a12IntegerBrand: unique symbol;
declare const a12OperatorCatalogIdBrand: unique symbol;

type A12ValueKind =
  | "string"
  | "boolean"
  | "confirm"
  | "number"
  | "date"
  | "time"
  | "dateTime"
  | "dateRange";

type A12WrapperKind =
  | A12ValueKind
  | "timeZone"
  | "evaluationTime"
  | "baseYear"
  | "pattern";

interface A12SemanticValue<
  K extends A12WrapperKind,
> {
  readonly [a12SemanticValueBrand]: never;
  readonly kind: K;
}

interface A12String extends A12SemanticValue<"string"> {
  toNative(): string;
}

interface A12Boolean extends A12SemanticValue<"boolean"> {
  toNative(): boolean;
  asTruth(): A12Truth;
}

interface A12Confirm extends A12SemanticValue<"confirm"> {}

interface A12Number
  extends A12SemanticValue<"number"> {
  toDecimalString(): string;
  toNumberLossy(): number;
}

interface A12Integer extends A12Number {
  readonly [a12IntegerBrand]: never;
  toSafeInteger(): number;
  tryToSafeInteger():
    A12Result<number, A12ConversionError>;
  toBigInt(): bigint;
}

interface A12Date extends A12SemanticValue<"date"> {
  toIsoString(): string;
}

interface A12Time extends A12SemanticValue<"time"> {
  toIsoString(): string;
}

interface A12TimeZone
  extends A12SemanticValue<"timeZone"> {
  readonly id: string;
}

interface A12DateTime
  extends A12SemanticValue<"dateTime"> {
  readonly evaluationTimeZone: A12TimeZone;
  toLocalIsoString(): string;
  toInstantIsoString(): string;
  toJsInstantDate(): Date;
  tryToJsInstantDate():
    A12Result<Date, A12ConversionError>;
}

interface A12DateRange
  extends A12SemanticValue<"dateRange"> {}

interface A12EvaluationTime
  extends A12SemanticValue<"evaluationTime"> {
  readonly timeZone: A12TimeZone;
}

interface A12BaseYear
  extends A12SemanticValue<"baseYear"> {
  asInteger(): A12Integer;
  asStartDate(): A12Date;
  asDateRange(): A12DateRange;
}

type A12Tolerance =
  | "ONE"
  | "TWO"
  | "FIVE"
  | "TEN";

interface A12Pattern
  extends A12SemanticValue<"pattern"> {
  readonly source: string;
}

type A12Value =
  | A12String
  | A12Boolean
  | A12Confirm
  | A12Number
  | A12Date
  | A12Time
  | A12DateTime
  | A12DateRange;

type A12Option<T> =
  | {readonly kind: "some"; readonly value: T}
  | {readonly kind: "none"};

type A12Truth =
  | {readonly kind: "true"}
  | {readonly kind: "false"}
  | {readonly kind: "notEvaluated"};

type A12NonEvaluationReason =
  | {
      readonly code: "divisionByZero";
    }
  | {
      readonly code: "powerDomain";
      readonly detail:
        | "exponentOutOfRange"
        | "zeroToNegativeExponent";
    };

type A12Evaluation<T> =
  | {
      readonly kind: "value";
      readonly value: T;
    }
  | {
      readonly kind: "notEvaluated";
      readonly reason: A12NonEvaluationReason;
    };

type A12OperatorCatalogId =
  string & {
    readonly [a12OperatorCatalogIdBrand]: never;
  };

type A12OperationIdentity =
  | {
      readonly kind: "catalog";
      readonly catalogId:
        A12OperatorCatalogId;
    }
  | {
      readonly kind: "directUtility";
      readonly utilityId: string;
    };

type A12IssuePathSegment = string | number;

type A12NativeInputKind =
  | "string"
  | "boolean"
  | "number"
  | "bigint"
  | "javascriptDate"
  | "object"
  | "array"
  | "a12Value";

type A12ReceivedInputKind =
  | A12NativeInputKind
  | "null"
  | "undefined"
  | "function"
  | "symbol"
  | "foreignA12Value";

type A12ConversionIssue =
  | {
      readonly code: "invalidNativeType";
      readonly expected: A12NativeInputKind;
      readonly received: A12ReceivedInputKind;
      readonly path: readonly A12IssuePathSegment[];
      readonly message: string;
    }
  | {
      readonly code: "invalidDecimal";
      readonly reason:
        | "empty"
        | "invalidSyntax"
        | "notFinite"
        | "outsideSupportedRange";
      readonly path: readonly A12IssuePathSegment[];
      readonly message: string;
    }
  | {
      readonly code: "invalidInteger";
      readonly reason:
        | "notIntegral"
        | "unsafeNativeNumber"
        | "outsideSupportedRange";
      readonly path: readonly A12IssuePathSegment[];
      readonly message: string;
    }
  | {
      readonly code: "invalidTemporalValue";
      readonly valueKind:
        | "date"
        | "time"
        | "dateTime";
      readonly reason:
        | "empty"
        | "invalidSyntax"
        | "invalidComponent";
      readonly path: readonly A12IssuePathSegment[];
      readonly message: string;
    }
  | {
      readonly code: "reversedDateRange";
      readonly path: readonly A12IssuePathSegment[];
      readonly message: string;
    }
  | {
      readonly code: "unsupportedTimeZone";
      readonly timeZone: string;
      readonly path: readonly A12IssuePathSegment[];
      readonly message: string;
    }
  | {
      readonly code: "nonexistentLocalTime";
      readonly timeZone: string;
      readonly path: readonly A12IssuePathSegment[];
      readonly message: string;
    }
  | {
      readonly code: "temporalPrecisionLoss";
      readonly source: "javascriptDate";
      readonly unsupportedUnit: "milliseconds";
      readonly path: readonly A12IssuePathSegment[];
      readonly message: string;
    }
  | {
      readonly code: "invalidPattern";
      readonly reason:
        | "invalidSyntax"
        | "unsupportedFeature";
      readonly position?: number;
      readonly path: readonly A12IssuePathSegment[];
      readonly message: string;
    }
  | {
      readonly code: "invalidA12Wrapper";
      readonly expectedKind: A12WrapperKind;
      readonly received: A12ReceivedInputKind;
      readonly path: readonly A12IssuePathSegment[];
      readonly message: string;
    };

type A12OperatorIssue =
  | {
      readonly code: "operatorArgumentOutOfRange";
      readonly operation: A12OperationIdentity;
      readonly argument: string;
      readonly minimum?: string;
      readonly maximum?: string;
      readonly actual: string;
      readonly path: readonly A12IssuePathSegment[];
      readonly message: string;
    }
  | {
      readonly code: "incompatibleTimeZones";
      readonly operation: A12OperationIdentity;
      readonly leftTimeZone: string;
      readonly rightTimeZone: string;
      readonly path: readonly A12IssuePathSegment[];
      readonly message: string;
    }
  | {
      readonly code: "incompatibleCollectionLengths";
      readonly operation: A12OperationIdentity;
      readonly leftLength: number;
      readonly rightLength: number;
      readonly path: readonly A12IssuePathSegment[];
      readonly message: string;
    }
  | {
      readonly code: "invalidOperatorOperand";
      readonly operation: A12OperationIdentity;
      readonly argument: string;
      readonly expectedKind: A12WrapperKind;
      readonly received: A12ReceivedInputKind;
      readonly path: readonly A12IssuePathSegment[];
      readonly message: string;
    };

type A12ScalarIssue =
  | A12ConversionIssue
  | A12OperatorIssue;

type A12ErrorCode =
  | "A12_CONVERSION_ERROR"
  | "A12_OPERATOR_ERROR"
  | "A12_ARRAY_CONVERSION_ERROR";

interface A12Error<
  Issue extends A12ScalarIssue = A12ScalarIssue,
> extends Error {
  readonly kind:
    | "conversion"
    | "operator"
    | "array-conversion";
  readonly code: A12ErrorCode;
  readonly issues: readonly [
    Issue,
    ...Issue[],
  ];
  readonly message: string;
}

interface A12ConversionError
  extends A12Error<A12ConversionIssue> {
  readonly kind: "conversion";
  readonly code: "A12_CONVERSION_ERROR";
}

interface A12OperatorError
  extends A12Error<A12OperatorIssue> {
  readonly kind: "operator";
  readonly code: "A12_OPERATOR_ERROR";
}

type A12Result<T, E = A12Error> =
  | {readonly ok: true; readonly value: T}
  | {readonly ok: false; readonly error: E};

interface A12IndexedConversionError<
  E extends A12ConversionError = A12ConversionError,
> {
  readonly index: number;
  readonly error: E;
}

interface A12ArrayConversionError<
  E extends A12ConversionError = A12ConversionError,
> extends A12Error<A12ConversionIssue> {
  readonly kind: "array-conversion";
  readonly code: "A12_ARRAY_CONVERSION_ERROR";
  readonly items: readonly [
    A12IndexedConversionError<E>,
    ...A12IndexedConversionError<E>[],
  ];
}

interface A12ErrorGuards {
  is(value: unknown): value is A12Error;
  isConversion(
    value: unknown,
  ): value is A12ConversionError;
  isOperator(
    value: unknown,
  ): value is A12OperatorError;
  isArrayConversion(
    value: unknown,
  ): value is A12ArrayConversionError;
}

type A12ArrayConversionResult<
  T,
  E extends A12ConversionError = A12ConversionError,
> = A12Result<readonly T[], A12ArrayConversionError<E>>;

interface A12ResultCompanion {
  map<T, U, E>(
    result: A12Result<T, E>,
    transform: (value: T) => U,
  ): A12Result<U, E>;

  flatMap<
    T,
    U,
    E1,
    E2,
  >(
    result: A12Result<T, E1>,
    transform: (value: T) => A12Result<U, E2>,
  ): A12Result<U, E1 | E2>;

  zipWith<
    A,
    B,
    C,
    E1,
    E2,
  >(
    first: A12Result<A, E1>,
    second: A12Result<B, E2>,
    combine: (first: A, second: B) => C,
  ): A12Result<C, E1 | E2>;

  match<T, E, S, F>(
    result: A12Result<T, E>,
    branches: {
      readonly ok: (value: T) => S;
      readonly error: (error: E) => F;
    },
  ): S | F;

  getOrElse<T, E>(
    result: A12Result<T, E>,
    fallback: (error: E) => T,
  ): T;

  capture<T>(
    operation: () => T,
  ): A12Result<T, A12OperatorError>;
}

interface A12EvaluationCompanion {
  map<T, U>(
    evaluation: A12Evaluation<T>,
    transform: (value: T) => U,
  ): A12Evaluation<U>;

  flatMap<T, U>(
    evaluation: A12Evaluation<T>,
    transform:
      (value: T) => A12Evaluation<U>,
  ): A12Evaluation<U>;

  zipWith<A, B, C>(
    first: A12Evaluation<A>,
    second: A12Evaluation<B>,
    combine: (first: A, second: B) => C,
  ): A12Evaluation<C>;

  match<T, V, N>(
    evaluation: A12Evaluation<T>,
    branches: {
      readonly value: (value: T) => V;
      readonly notEvaluated:
        (reason: A12NonEvaluationReason) => N;
    },
  ): V | N;

  getOrElse<T>(
    evaluation: A12Evaluation<T>,
    fallback:
      (reason: A12NonEvaluationReason) => T,
  ): T;
}

interface A12Codec<Input, Output> {
  parse(input: unknown): Output;

  tryParse(
    input: unknown,
  ): A12Result<Output, A12ConversionError>;

  decode(input: Input): Output;

  tryDecode(
    input: Input,
  ): A12Result<Output, A12ConversionError>;

  encode(output: Output): Input;

  tryEncode(
    output: Output,
  ): A12Result<Input, A12ConversionError>;
}

declare const A12Result: A12ResultCompanion;
declare const A12Evaluation:
  A12EvaluationCompanion;
declare const A12Errors: A12ErrorGuards;

interface A12OptionCompanion {
  readonly none: {readonly kind: "none"};
  some<T>(value: T): A12Option<T>;
}

interface A12StringCompanion {
  of(value: string): A12String;
  is(value: unknown): value is A12String;
}

interface A12BooleanCompanion {
  of(value: boolean): A12Boolean;
  is(value: unknown): value is A12Boolean;
}

interface A12ConfirmCompanion {
  confirmed(): A12Confirm;
  is(value: unknown): value is A12Confirm;
}

interface A12NumberCompanion {
  parse(value: string): A12Number;
  tryParse(
    value: string,
  ): A12Result<A12Number, A12ConversionError>;
  fromNumberLossy(value: number): A12Number;
  tryFromNumberLossy(
    value: number,
  ): A12Result<A12Number, A12ConversionError>;
  is(value: unknown): value is A12Number;
}

interface A12IntegerCompanion {
  fromSafeInteger(value: number): A12Integer;
  tryFromSafeInteger(
    value: number,
  ): A12Result<A12Integer, A12ConversionError>;
  fromBigInt(value: bigint): A12Integer;
  is(value: unknown): value is A12Integer;
}

interface A12DateCompanion {
  parseIso(value: string): A12Date;
  tryParseIso(
    value: string,
  ): A12Result<A12Date, A12ConversionError>;
  of(
    year: A12Integer,
    month: A12Integer,
    day: A12Integer,
  ): A12Date;
  tryOf(
    year: A12Integer,
    month: A12Integer,
    day: A12Integer,
  ): A12Result<A12Date, A12ConversionError>;
  is(value: unknown): value is A12Date;
}

interface A12TimeCompanion {
  parseIso(value: string): A12Time;
  tryParseIso(
    value: string,
  ): A12Result<A12Time, A12ConversionError>;
  of(
    hours: A12Integer,
    minutes: A12Integer,
    seconds: A12Integer,
  ): A12Time;
  tryOf(
    hours: A12Integer,
    minutes: A12Integer,
    seconds: A12Integer,
  ): A12Result<A12Time, A12ConversionError>;
  is(value: unknown): value is A12Time;
}

interface A12TimeZoneCompanion {
  utc(): A12TimeZone;
  is(value: unknown): value is A12TimeZone;
}

interface A12DateTimeCompanion {
  parseLocal(
    localIso: string,
    evaluationTimeZone: A12TimeZone,
  ): A12DateTime;
  tryParseLocal(
    localIso: string,
    evaluationTimeZone: A12TimeZone,
  ): A12Result<A12DateTime, A12ConversionError>;
  parseInstant(
    instantIso: string,
    evaluationTimeZone: A12TimeZone,
  ): A12DateTime;
  tryParseInstant(
    instantIso: string,
    evaluationTimeZone: A12TimeZone,
  ): A12Result<A12DateTime, A12ConversionError>;
  fromJsDate(
    instant: Date,
    evaluationTimeZone: A12TimeZone,
  ): A12DateTime;
  tryFromJsDate(
    instant: Date,
    evaluationTimeZone: A12TimeZone,
  ): A12Result<A12DateTime, A12ConversionError>;
  combine(
    date: A12Date,
    time: A12Time,
    evaluationTimeZone: A12TimeZone,
  ): A12DateTime;
  tryCombine(
    date: A12Date,
    time: A12Time,
    evaluationTimeZone: A12TimeZone,
  ): A12Result<A12DateTime, A12ConversionError>;
  is(value: unknown): value is A12DateTime;
}

interface A12DateRangeCompanion {
  of(start: A12Date, end: A12Date): A12DateRange;
  tryOf(
    start: A12Date,
    end: A12Date,
  ): A12Result<A12DateRange, A12ConversionError>;
  is(value: unknown): value is A12DateRange;
}

interface A12EvaluationTimeCompanion {
  fromJsDate(
    instant: Date,
    timeZone: A12TimeZone,
  ): A12EvaluationTime;
  tryFromJsDate(
    instant: Date,
    timeZone: A12TimeZone,
  ): A12Result<A12EvaluationTime, A12ConversionError>;
  is(value: unknown): value is A12EvaluationTime;
}

interface A12BaseYearCompanion {
  fromSafeInteger(value: number): A12BaseYear;
  tryFromSafeInteger(
    value: number,
  ): A12Result<A12BaseYear, A12ConversionError>;
  is(value: unknown): value is A12BaseYear;
}

interface A12ToleranceCompanion {
  readonly ONE: "ONE";
  readonly TWO: "TWO";
  readonly FIVE: "FIVE";
  readonly TEN: "TEN";
}

interface A12PatternCompanion {
  compile(source: string): A12Pattern;
  tryCompile(
    source: string,
  ): A12Result<A12Pattern, A12ConversionError>;
  is(value: unknown): value is A12Pattern;
}

interface A12DateRangeWire {
  readonly start: string;
  readonly end: string;
}

interface A12DateTimeWire {
  readonly instant: string;
  readonly timeZone: string;
}

interface A12CodecCompanion {
  readonly string:
    A12Codec<string, A12String>;
  readonly boolean:
    A12Codec<boolean, A12Boolean>;
  readonly confirmed:
    A12Codec<true, A12Confirm>;
  readonly decimalString:
    A12Codec<string, A12Number>;
  readonly bigint:
    A12Codec<bigint, A12Integer>;
  readonly isoDate:
    A12Codec<string, A12Date>;
  readonly isoTime:
    A12Codec<string, A12Time>;
  readonly dateRange:
    A12Codec<A12DateRangeWire, A12DateRange>;
  readonly dateTime:
    A12Codec<A12DateTimeWire, A12DateTime>;

  array<Input, Output>(
    item: A12Codec<Input, Output>,
  ): A12Codec<
    readonly Input[],
    readonly Output[]
  >;
}

declare const A12Option: A12OptionCompanion;
declare const A12String: A12StringCompanion;
declare const A12Boolean: A12BooleanCompanion;
declare const A12Confirm: A12ConfirmCompanion;
declare const A12Number: A12NumberCompanion;
declare const A12Integer: A12IntegerCompanion;
declare const A12Date: A12DateCompanion;
declare const A12Time: A12TimeCompanion;
declare const A12TimeZone: A12TimeZoneCompanion;
declare const A12DateTime: A12DateTimeCompanion;
declare const A12DateRange: A12DateRangeCompanion;
declare const A12EvaluationTime:
  A12EvaluationTimeCompanion;
declare const A12BaseYear: A12BaseYearCompanion;
declare const A12Tolerance: A12ToleranceCompanion;
declare const A12Pattern: A12PatternCompanion;
declare const A12Codecs: A12CodecCompanion;
```

### 4.3 Declaration guarantees

| Area | Guarantee |
|---|---|
| Wrapper identity | `A12SemanticValue<K>` gives every immutable wrapper its exact literal `kind`; a module-private brand prevents accidental structural assignment, while runtime checks enforce authenticity |
| Value kinds | `A12ValueKind` contains only storable scalar discriminants; support values remain outside `A12Value` |
| Integer refinement | `A12Integer` retains `kind: "number"` and adds a private refinement brand, exact `bigint` conversion, and checked safe-integer conversion |
| Runtime exports | Each `…Companion` is the runtime export with the same name as its value type |
| Fallibility | Every fallible `of`/`parse`/`from…` method throws `A12ConversionError` and has an adjacent `try…` result form |
| Total construction | `A12String.of`, `A12Boolean.of`, `A12Confirm.confirmed`, `A12Integer.fromBigInt`, and `A12TimeZone.utc` need no artificial try-form |
| Guards | Every wrapper guard accepts `unknown`, never throws, and verifies an authentic library-issued wrapper; a literal `kind` switch narrows only after authentication or a trusted typed boundary |
| Options | `A12Option.some<T>` preserves `T`; the immutable `none` object is assignable to every `A12Option<T>` |
| Tolerance | `A12Tolerance` is a closed string-literal union with four correctly typed companion constants |
| Patterns | `A12Pattern` and its companion are omitted from the package until the supported regex profile is exact |
| Truth and evaluation | `A12Truth` is the exact three-state union; `A12Evaluation<T>` is the corresponding value-or-non-evaluation algebra |
| Issues | Closed conversion/operator issue unions provide stable codes, paths, structured details, and an exact catalog or direct-utility operation identity |
| Error safety | Developers never parse `message`; guards narrow `unknown` without cross-module `instanceof`; errors do not retain rejected raw input |
| Indexed failures | Each indexed failure retains its zero-based array index and original scalar error without retaining the source value |
| Results | `map`, `flatMap`, and `zipWith` propagate failures without catching callback exceptions; `zipWith` returns the left failure first |
| Result folding | `match` folds both result branches, `getOrElse` computes a fallback, and `capture` catches only `A12OperatorError` |
| Evaluation composition | Callbacks do not run for `notEvaluated`; `zipWith` returns the first non-evaluation left-to-right; `match` and `getOrElse` require explicit handling |
| Execution | Result and evaluation combinators are eager and construct no expression graph |
| Codecs | `parse` starts at `unknown`, `decode` at typed input, and `encode` returns the portable representation; each has its declared result form |
| Codec scope | `A12Codecs` contains exact codecs and array composition, not schemas, defaults, refinements, validation graphs, or document models |

The remaining support types are explicit values rather than hidden context:

- `A12EvaluationTime` is one exact instant resolved in one supported time zone;
- `A12BaseYear` exposes the configured year as integer, start date, or full-year range;
- `A12Tolerance` is the closed `ONE | TWO | FIVE | TEN` set;
- `A12Integer` is accepted everywhere an `A12NumericValue` operand is accepted.

The common field domains project predictably: number/amount → `A12Number`, integral counts/offsets → `A12Integer`, boolean → `A12Boolean`, a filled confirm → `A12Confirm`, string and stored enum code → `A12String`, and temporal/range kinds → their matching A12 values. An unset confirm remains document state; `false` is rejected and Confirm never compares with Boolean. Enum categories, partial-date completion, and field formatting remain model/interpreter behavior.

The direct layer deliberately excludes field-state wrappers such as “empty” or “formally invalid.” An empty field’s operator behavior is model/evaluation semantics. It is not the same thing as a direct call with an `A12Date`.

### 4.4 Runtime authenticity and realm policy

TypeScript brands prevent accidental misuse only at compile time. The runtime boundary is a Kotlin/JS-owned authenticity marker and invariant check that is not reproducible by a plain object with the same public properties. `instanceof` alone is insufficient because duplicate bundles and realms create different constructor identities.

The initial realm policy is explicit:

- wrappers are accepted only from the same physical package instance and JavaScript realm;
- duplicate-package, Worker, iframe, and structured-cloned wrappers are foreign and fail every `is(value: unknown)` guard;
- an unsafe foreign operand produces structured `A12OperatorError`, never a leaked Kotlin cast failure;
- cross-realm, process, persistence, and network transfer uses the exact codec representation and reconstructs a local wrapper;
- the policy avoids a forgeable global symbol registry and widens only with a proven interoperable ABI.

`A12Number.is(value)` accepts every authentic number wrapper, including an `A12Integer`; `A12Integer.is(value)` additionally proves the integral refinement. Neither guard trusts a public `kind: "number"` property by itself.

### 4.5 Date-time identity

`A12DateTime` cannot be a decorated string. Kernel sub-day differences are instant-based, and chained shifts must preserve the exact instant through daylight-saving overlaps.

The kernel scalar’s effective zone comes from model/runtime context. The direct wrapper pairs the instant with explicit evaluation context for deterministic direct calls without presenting that zone as intrinsic kernel storage identity.

Construction resolves a local date-time using an explicit supported zone:

```ts
const value: A12DateTime = A12DateTime.parseLocal(
  "2024-10-27T02:30:00",
  A12TimeZone.utc(),
);
```

The value retains enough evaluation context to make later calls context-free. UTC is the initial guaranteed zone and therefore the only initially accepted `A12DateTimeWire.timeZone`.

Named IANA zones such as `Europe/Berlin` join only after their supported history is kernel-exact. When they ship, a spring gap is a conversion error, a fall-back ambiguity follows the exact kernel resolution rule, and binary operations reject incompatible zones unless an explicit conversion exists.

## 5. Wrapping and unwrapping native values

Wrapping and unwrapping are core API, not an appendix.

### 5.1 Strings and booleans

Native strings and booleans convert without loss through `A12String.of`/`toNative` and `A12Boolean.of`/`toNative`:

```ts
declare const nativeText: string;
declare const nativeFlag: boolean;

const text: A12String = A12String.of(nativeText);
const nativeTextAgain: string = text.toNative();
const flag: A12Boolean = A12Boolean.of(nativeFlag);
const nativeFlagAgain: boolean = flag.toNative();
const confirmed: A12Confirm = A12Confirm.confirmed();
```

`A12Confirm.confirmed()` constructs the only filled confirmation value. There is deliberately no `A12Confirm.of(false)` or native `boolean` unwrapping for Confirm.

### 5.2 Numbers

Exact decimal text is the primary numeric boundary:

```ts
const amount: A12Number = A12Number.parse("19.955");
const canonical: string = amount.toDecimalString();
```

`toDecimalString()` is value-exact and canonical, not a promise to preserve source spelling: parsing `"19.950"` may produce `"19.95"`. Stored field lexical rendering such as `FieldValueAsString` remains document/interpreter behavior.

Safe integers and `bigint` have exact adapters:

```ts
declare const nativeBigInt: bigint;

const count: A12Integer = A12Integer.fromSafeInteger(42);
const large: A12Integer = A12Integer.fromBigInt(nativeBigInt);

const nativeCount: number = count.toSafeInteger();
const nativeLarge: bigint = large.toBigInt();
```

A JavaScript `number` conversion must never pretend to recover a business decimal exactly. If a convenience adapter is supported, loss is visible in the name:

```ts
declare const nativeNumber: number;
declare const amount: A12Number;

const wrappedApproximate: A12Number =
  A12Number.fromNumberLossy(nativeNumber);
const nativeApproximate: number = amount.toNumberLossy();
```

There is no silent `number` overload on arithmetic operators.

### 5.3 Dates and times

Canonical text is the unambiguous boundary:

```ts
const date: A12Date = A12Date.parseIso("2026-07-19");
const time: A12Time = A12Time.parseIso("14:30:00");

const dateText: string = date.toIsoString();
const timeText: string = time.toIsoString();
```

A JavaScript `Date` is an instant. It can convert to an `A12DateTime` only with an explicit zone:

```ts
declare const nativeDate: Date;
declare const utc: A12TimeZone;

const value: A12DateTime =
  A12DateTime.fromJsDate(nativeDate, utc);
const nativeInstant: Date = value.toJsInstantDate();

const local: string = value.toLocalIsoString();
const instant: string = value.toInstantIsoString();
const evaluationTimeZone: A12TimeZone =
  value.evaluationTimeZone;

const fromNativeInstant: A12DateTime =
  A12DateTime.fromJsDate(
    nativeInstant,
    evaluationTimeZone,
  );
const fromInstantText: A12DateTime =
  A12DateTime.parseInstant(
    instant,
    evaluationTimeZone,
  );
```

The A12 boundary has second precision. `fromJsDate` rejects a non-zero millisecond component unless the caller chooses an explicitly named rounding/truncation conversion.

The date-time projections have distinct contracts:

- `toJsInstantDate()` preserves the instant but cannot carry evaluation-zone context;
- `fromJsDate(nativeInstant, evaluationTimeZone)` is its exact inverse at A12 second precision;
- `toInstantIsoString()` emits an offset/UTC instant string;
- `parseInstant(instant, evaluationTimeZone)` is its exact textual inverse;
- local canonical text is only a display projection because an overlap can map one local spelling to two instants;
- JavaScript `Date` has no default conversion to `A12Date`, because a calendar date requires a zone.

### 5.4 Exact codecs and conversion classification

Constructors remain the shortest scalar API. Codecs add one dependency-free composition boundary for transports, arrays, and future adapters:

```ts
declare const unknownAmount: unknown;

const amountCodec: A12Codec<string, A12Number> =
  A12Codecs.decimalString;
const amount: A12Number =
  amountCodec.parse(unknownAmount);
const canonicalAmount: string =
  amountCodec.encode(amount);

const amountsCodec: A12Codec<
  readonly string[],
  readonly A12Number[]
> = A12Codecs.array(
  A12Codecs.decimalString,
);

const nativeAmounts: readonly string[] = [
  "19.95",
  "7.50",
];
const amounts: readonly A12Number[] =
  amountsCodec.decode(nativeAmounts);
```

`A12Codecs.array(item)` preserves order and duplicates. It rejects sparse holes, `null`, `undefined`, and wrong native element kinds, prefixing every scalar issue path with the failing zero-based index.

Only exact bidirectional conversions are codecs:

| Conversion | Exact codec | Rationale |
|---|---:|---|
| `string` ↔ `A12String` | yes | exact |
| `boolean` ↔ `A12Boolean` | yes | exact |
| literal `true` ↔ `A12Confirm` | yes | Confirm has no false filled value |
| decimal string ↔ `A12Number` | yes | exact decimal representation |
| `bigint` ↔ `A12Integer` | yes | exact integral representation |
| ISO calendar-date string ↔ `A12Date` | yes | exact after canonicalization |
| ISO local-time string ↔ `A12Time` | yes | exact after canonicalization |
| `{start, end}` ↔ `A12DateRange` | yes | structured and unambiguous |
| `{instant, timeZone}` ↔ `A12DateTime` | yes for the exactly supported context set; initially UTC | preserves the instant plus explicit direct-API evaluation context |
| JavaScript `number` ↔ `A12Number` | no | may lose decimal information |
| every `A12Integer` ↔ safe JavaScript `number` | no | only a checked subset fits |
| JavaScript `Date` ↔ `A12Date` | no | instant and calendar date are different concepts |
| JavaScript `Date` ↔ `A12DateTime` without a zone | no | the instant does not select evaluation-zone context |
| zone-less local date-time ↔ `A12DateTime` | no | a daylight-saving overlap or gap can be ambiguous or invalid |

`A12Integer.fromSafeInteger` and `tryToSafeInteger` remain checked one-way conversions rather than a misleading codec over the whole integer domain. `A12Number.fromNumberLossy` and `toNumberLossy` remain visibly lossy methods. `A12DateTime.fromJsDate` is exact only because it receives the explicit zone context and rejects precision loss.

Every exact codec is law-tested on JVM and JavaScript:

1. `decode(encode(value))` preserves the semantic A12 value and required direct-API context.
2. `encode(decode(input))` may canonicalize lexical input but preserves accepted semantics.
3. Supported platforms produce the same A12 value or the same structured issue for the same input.
4. No lossy or partial conversion is presented as an exact codec.
5. Array composition preserves order and duplicates and reports every failing index without retaining raw inputs.

### 5.5 Parse results

User input needs both throwing and composable forms:

```ts
declare const input: string;

const date: A12Date = A12Date.parseIso(input);
const attempted: A12Result<A12Date, A12ConversionError> =
  A12Date.tryParseIso(input);
```

Throwing and result forms expose the same typed conversion failure, and the result’s `ok` discriminant narrows both branches. Raw Kotlin exceptions and sealed implementation types do not leak into TypeScript.

Throwing forms have a runtime-safe recognition path:

```ts
declare const input: string;

try {
  const date: A12Date = A12Date.parseIso(input);
} catch (caught: unknown) {
  if (A12Errors.isConversion(caught)) {
    const conversionError: A12ConversionError = caught;
    const firstIssue: A12ConversionIssue =
      conversionError.issues[0];
    const issueCode: A12ConversionIssue["code"] =
      firstIssue.code;
  }
}
```

`A12Errors.isConversion` narrows caught `unknown` values to `A12ConversionError`; its issues are exhaustively narrowable by code. `A12Errors.is`, `isOperator`, and `isArrayConversion` provide the corresponding base, operator, and bulk guards.

## 6. Operator surface

Operator names use idiomatic lower camel case while documenting their operator-catalog identity, such as `catalog:DifferenceInHours`. Namespaces make the API discoverable without a stringly dispatcher.

### 6.1 Complete namespace contract

The baseline callable surface is fully typed:

```ts
type A12NumericValue = A12Number;
type A12TemporalValue = A12Date | A12DateTime;

type A12TotalEqualityOperands =
  | [left: A12Boolean, right: A12Boolean]
  | [left: A12Confirm, right: A12Confirm]
  | [left: A12NumericValue, right: A12NumericValue]
  | [left: A12Date, right: A12Date]
  | [left: A12Time, right: A12Time]
  | [left: A12DateTime, right: A12DateTime]
  | [left: A12DateRange, right: A12DateRange];

type A12OrderedOperands =
  | [left: A12NumericValue, right: A12NumericValue]
  | [left: A12Date, right: A12Date]
  | [left: A12Time, right: A12Time]
  | [left: A12DateTime, right: A12DateTime];

type A12TotalEqualityCollection =
  | readonly A12Boolean[]
  | readonly A12Confirm[]
  | readonly A12NumericValue[]
  | readonly A12Date[]
  | readonly A12Time[]
  | readonly A12DateTime[]
  | readonly A12DateRange[];

type A12TotalEqualityCollectionOperands =
  | [
      values: readonly A12Boolean[],
      target: A12Boolean,
    ]
  | [
      values: readonly A12Confirm[],
      target: A12Confirm,
    ]
  | [
      values: readonly A12NumericValue[],
      target: A12NumericValue,
    ]
  | [
      values: readonly A12Date[],
      target: A12Date,
    ]
  | [
      values: readonly A12Time[],
      target: A12Time,
    ]
  | [
      values: readonly A12DateTime[],
      target: A12DateTime,
    ]
  | [
      values: readonly A12DateRange[],
      target: A12DateRange,
    ];

interface A12PointInTimeOperators {
  today(sample: A12EvaluationTime): A12Date;
  now(sample: A12EvaluationTime): A12DateTime;
}

interface A12BooleanOperators {
  and(
    left: A12Boolean,
    right: A12Boolean,
  ): A12Boolean;
  or(
    left: A12Boolean,
    right: A12Boolean,
  ): A12Boolean;
  not(value: A12Boolean): A12Boolean;
}

interface A12TruthOperators {
  and(left: A12Truth, right: A12Truth): A12Truth;
  or(left: A12Truth, right: A12Truth): A12Truth;
  not(value: A12Truth): A12Truth;
}

interface A12DateOperators {
  differenceInDays(
    start: A12Date,
    end: A12Date,
  ): A12Integer;
  differenceInMonths(
    start: A12Date,
    end: A12Date,
  ): A12Integer;
  differenceInYears(
    start: A12Date,
    end: A12Date,
  ): A12Integer;
  addDays(
    value: A12Date,
    amount: A12Integer,
  ): A12Date;
  addMonths(
    value: A12Date,
    amount: A12Integer,
  ): A12Date;
  addYears(
    value: A12Date,
    amount: A12Integer,
  ): A12Date;
  year(value: A12Date): A12Integer;
  month(value: A12Date): A12Integer;
  day(value: A12Date): A12Integer;
  quarter(value: A12Date): A12Integer;
  minAll(
    values: readonly A12Date[],
  ): A12Option<A12Date>;
  maxAll(
    values: readonly A12Date[],
  ): A12Option<A12Date>;
}

interface A12DateTimeOperators {
  differenceInDays(
    start: A12DateTime,
    end: A12DateTime,
  ): A12Integer;
  differenceInHours(
    start: A12DateTime,
    end: A12DateTime,
  ): A12Integer;
  differenceInMinutes(
    start: A12DateTime,
    end: A12DateTime,
  ): A12Integer;
  differenceInSeconds(
    start: A12DateTime,
    end: A12DateTime,
  ): A12Integer;
  addDays(
    value: A12DateTime,
    amount: A12Integer,
  ): A12DateTime;
  addHours(
    value: A12DateTime,
    amount: A12Integer,
  ): A12DateTime;
  addMinutes(
    value: A12DateTime,
    amount: A12Integer,
  ): A12DateTime;
  addSeconds(
    value: A12DateTime,
    amount: A12Integer,
  ): A12DateTime;
  datePart(value: A12DateTime): A12Date;
  timePart(value: A12DateTime): A12Time;
}

interface A12TemporalOperators {
  differenceInDays(
    start: A12TemporalValue,
    end: A12TemporalValue,
  ): A12Integer;
  year(value: A12TemporalValue): A12Integer;
  month(value: A12TemporalValue): A12Integer;
  day(value: A12TemporalValue): A12Integer;
  quarter(value: A12TemporalValue): A12Integer;
}

interface A12TimeOperators {
  hours(value: A12Time): A12Integer;
  minutes(value: A12Time): A12Integer;
  seconds(value: A12Time): A12Integer;
}

interface A12StringOperators {
  length(value: A12String): A12Integer;
  concat(
    first: A12String,
    second: A12String,
    ...rest: A12String[]
  ): A12String;
  rangeAsString(
    value: A12String,
    startInclusive: A12Integer,
    endInclusive: A12Integer,
  ): A12String;
  rangeAsNumber(
    value: A12String,
    startInclusive: A12Integer,
    endInclusive: A12Integer,
  ): A12Number;
  equal(
    left: A12String,
    right: A12String,
  ): A12Truth;
  notEqual(
    left: A12String,
    right: A12String,
  ): A12Truth;
  concatAll(
    values: readonly A12String[],
  ): A12String;
}

interface A12NumberOperators {
  add(
    left: A12NumericValue,
    right: A12NumericValue,
  ): A12Number;
  subtract(
    left: A12NumericValue,
    right: A12NumericValue,
  ): A12Number;
  abs(value: A12NumericValue): A12Number;
  min(
    left: A12NumericValue,
    right: A12NumericValue,
  ): A12Number;
  max(
    left: A12NumericValue,
    right: A12NumericValue,
  ): A12Number;
  roundDown(
    value: A12NumericValue,
    scale: A12Integer,
  ): A12Number;
  roundUp(
    value: A12NumericValue,
    scale: A12Integer,
  ): A12Number;
  roundAccounting(
    value: A12NumericValue,
    scale: A12Integer,
  ): A12Number;
  differsByMoreThan(
    left: A12NumericValue,
    right: A12NumericValue,
    tolerance: A12Tolerance,
  ): A12Truth;
  sumAll(
    values: readonly A12NumericValue[],
  ): A12Number;
  minAll(
    values: readonly A12NumericValue[],
  ): A12Number;
  maxAll(
    values: readonly A12NumericValue[],
  ): A12Number;
}

interface A12CompareOperators {
  equal(
    ...operands: A12TotalEqualityOperands
  ): A12Truth;
  notEqual(
    ...operands: A12TotalEqualityOperands
  ): A12Truth;
  lessThan(
    ...operands: A12OrderedOperands
  ): A12Truth;
  lessThanOrEqual(
    ...operands: A12OrderedOperands
  ): A12Truth;
  greaterThan(
    ...operands: A12OrderedOperands
  ): A12Truth;
  greaterThanOrEqual(
    ...operands: A12OrderedOperands
  ): A12Truth;
  order(
    ...operands: A12OrderedOperands
  ): A12Integer;
}

interface A12DateRangeOperators {
  start(value: A12DateRange): A12Date;
  end(value: A12DateRange): A12Date;
  overlaps(
    left: A12DateRange,
    right: A12DateRange,
  ): A12Truth;
  anyPairOverlaps(
    values: readonly A12DateRange[],
  ): A12Truth;
  overlapsAny(
    target: A12DateRange,
    values: readonly A12DateRange[],
  ): A12Truth;
}

interface A12ValueCollectionOperators {
  includes(
    ...operands: A12TotalEqualityCollectionOperands
  ): A12Truth;
  count(
    ...operands: A12TotalEqualityCollectionOperands
  ): A12Integer;
  distinctCount(
    values: A12TotalEqualityCollection,
  ): A12Integer;
}

interface A12StringValueCollectionOperators {
  includesExact(
    values: readonly A12String[],
    target: A12String,
  ): A12Truth;
  countExact(
    values: readonly A12String[],
    target: A12String,
  ): A12Integer;
  distinctCountExact(
    values: readonly A12String[],
  ): A12Integer;
}

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
  readonly stringValues:
    A12StringValueCollectionOperators;
}

declare const operators: A12Operators;
```

The namespace types enforce the semantic domains:

- `A12NumericValue` names the one kernel number domain, and the `A12Integer` refinement is assignable without a second runtime kind;
- `A12TemporalValue` is the intentional mixed date/date-time domain;
- operand-tuple unions reject strings from generic comparison, date ranges from ordering, and every unrelated pairing;
- collection unions keep arrays homogeneous and restrict generic membership/counting to kinds with total equality;
- each family interface is the callable namespace used below.

Gated features extend the baseline type only when their exact semantics ship:

```ts
interface A12PatternStringOperators
  extends A12StringOperators {
  matches(
    value: A12String,
    pattern: A12Pattern,
  ): A12Truth;
  violates(
    value: A12String,
    pattern: A12Pattern,
  ): A12Truth;
}

interface A12OperatorsWithPatterns
  extends A12Operators {
  readonly string: A12PatternStringOperators;
}

interface A12ExactArithmeticNumberOperators
  extends A12NumberOperators {
  multiply(
    left: A12NumericValue,
    right: A12NumericValue,
  ): A12Number;
  divide(
    left: A12NumericValue,
    right: A12NumericValue,
  ): A12Evaluation<A12Number>;
  power(
    value: A12NumericValue,
    exponent: A12Integer,
  ): A12Evaluation<A12Number>;
  sumOfProducts(
    left: readonly A12NumericValue[],
    right: readonly A12NumericValue[],
  ): A12Number;
}

interface A12OperatorsWithExactArithmetic
  extends A12Operators {
  readonly number:
    A12ExactArithmeticNumberOperators;
}
```

`A12OperatorsWithPatterns` adds only the exact supported pattern profile. `A12OperatorsWithExactArithmetic` adds the operations that depend on the exact JavaScript 50-significant-digit arithmetic context. These extension types prevent examples or declarations from pretending gated methods already exist.

### 6.2 Construction, constants, and logic

Strict constructors create already-valid direct values without parsing an expression:

```ts
declare const year: A12Integer;
declare const month: A12Integer;
declare const day: A12Integer;
declare const hours: A12Integer;
declare const minutes: A12Integer;
declare const seconds: A12Integer;
declare const zone: A12TimeZone;
declare const start: A12Date;
declare const end: A12Date;

const date: A12Date = A12Date.of(year, month, day);
const time: A12Time = A12Time.of(hours, minutes, seconds);
const dateTime: A12DateTime =
  A12DateTime.combine(date, time, zone);
const range: A12DateRange = A12DateRange.of(start, end);
```

All numeric components are `A12Integer`. `A12Date.of` and `A12Date.tryOf` are strict value boundaries: an impossible combination such as 31 February produces `A12ConversionError` rather than an invalid `A12Date`.

That strict contract does not replace the catalog expression semantics of `Date(...)` observed by `Valid(Date(...))` and `Invalid(Date(...))`. The interpreter may retain an unreal construction candidate so those conditions can inspect contextual empty or malformed components and polarity.

Such a candidate is not an `A12Date` and is not exposed here. A future direct inspection API would require a separately named `A12DateCandidate` with an explicit state algebra; it must never weaken `A12Date`.

Point-in-time constants require an immutable sample captured once by the caller:

```ts
declare const nativeNow: Date;
declare const zone: A12TimeZone;

const sample: A12EvaluationTime =
  A12EvaluationTime.fromJsDate(nativeNow, zone);
const today: A12Date = operators.pointInTime.today(sample);
const now: A12DateTime = operators.pointInTime.now(sample);

const baseYear: A12BaseYear =
  A12BaseYear.fromSafeInteger(2026);
const baseYearNumber: A12Integer = baseYear.asInteger();
const baseYearStart: A12Date = baseYear.asStartDate();
const baseYearRange: A12DateRange = baseYear.asDateRange();
```

There is no implicit read of the host clock. `A12BaseYear` makes the catalog’s number/date/range projections explicit rather than relying on expression context.

Fully known boolean values and three-valued condition results have distinct combinators:

```ts
declare const firstBoolean: A12Boolean;
declare const secondBoolean: A12Boolean;
declare const firstTruth: A12Truth;
declare const secondTruth: A12Truth;

const booleanAnd: A12Boolean =
  operators.boolean.and(firstBoolean, secondBoolean);
const booleanOr: A12Boolean =
  operators.boolean.or(firstBoolean, secondBoolean);
const booleanNot: A12Boolean =
  operators.boolean.not(firstBoolean);

const truthAnd: A12Truth =
  operators.truth.and(firstTruth, secondTruth);
const truthOr: A12Truth =
  operators.truth.or(firstTruth, secondTruth);
const truthNot: A12Truth =
  operators.truth.not(firstTruth);
```

`operators.truth` uses exact strong Kleene logic: false decides `and`, true decides `or`, otherwise an operand that is `notEvaluated` propagates; `not(notEvaluated)` remains `notEvaluated`. The total boolean functions and `not` are classified as value/combinator conveniences.

### 6.3 Dates

```ts
declare const start: A12Date;
declare const end: A12Date;
declare const date: A12Date;
declare const amount: A12Integer;

const dayDifference: A12Integer =
  operators.date.differenceInDays(start, end);
const monthDifference: A12Integer =
  operators.date.differenceInMonths(start, end);
const yearDifference: A12Integer =
  operators.date.differenceInYears(start, end);

const dayShift: A12Date =
  operators.date.addDays(date, amount);
const monthShift: A12Date =
  operators.date.addMonths(date, amount);
const yearShift: A12Date =
  operators.date.addYears(date, amount);

const yearPart: A12Integer = operators.date.year(date);
const monthPart: A12Integer = operators.date.month(date);
const dayPart: A12Integer = operators.date.day(date);
const quarterPart: A12Integer = operators.date.quarter(date);
```

These functions preserve A12 rules such as whole-period differences, end-of-month clamping, and leap-year behavior. Shift offsets are `A12Integer`; their result types are `A12Integer` or `A12Date`, never native numbers or dates.

### 6.4 Date-times and times

```ts
declare const startDateTime: A12DateTime;
declare const endDateTime: A12DateTime;
declare const dateTime: A12DateTime;
declare const amount: A12Integer;
declare const temporalStart: A12Date | A12DateTime;
declare const temporalEnd: A12Date | A12DateTime;
declare const temporalValue: A12Date | A12DateTime;
declare const time: A12Time;

const dateTimeDays: A12Integer =
  operators.dateTime.differenceInDays(
    startDateTime,
    endDateTime,
  );
const hours: A12Integer =
  operators.dateTime.differenceInHours(
    startDateTime,
    endDateTime,
  );
const minutes: A12Integer =
  operators.dateTime.differenceInMinutes(
    startDateTime,
    endDateTime,
  );
const seconds: A12Integer =
  operators.dateTime.differenceInSeconds(
    startDateTime,
    endDateTime,
  );

const shiftedDays: A12DateTime =
  operators.dateTime.addDays(dateTime, amount);
const shiftedHours: A12DateTime =
  operators.dateTime.addHours(dateTime, amount);
const shiftedMinutes: A12DateTime =
  operators.dateTime.addMinutes(dateTime, amount);
const shiftedSeconds: A12DateTime =
  operators.dateTime.addSeconds(dateTime, amount);

const datePart: A12Date =
  operators.dateTime.datePart(dateTime);
const timePart: A12Time =
  operators.dateTime.timePart(dateTime);

const temporalDays: A12Integer =
  operators.temporal.differenceInDays(
    temporalStart,
    temporalEnd,
  );
const temporalYear: A12Integer =
  operators.temporal.year(temporalValue);
const temporalMonth: A12Integer =
  operators.temporal.month(temporalValue);
const temporalDay: A12Integer =
  operators.temporal.day(temporalValue);
const temporalQuarter: A12Integer =
  operators.temporal.quarter(temporalValue);

const timeHours: A12Integer = operators.time.hours(time);
const timeMinutes: A12Integer = operators.time.minutes(time);
const timeSeconds: A12Integer = operators.time.seconds(time);
```

Sub-day differences use the resolved instants stored by `A12DateTime` and truncate toward zero: a reverse difference of negative five-and-a-half hours is `-5`. `addHours`/`addMinutes`/`addSeconds` shift the instant.

`dateTime.addDays` is the wall-calendar operation: it preserves local time and applies the kernel’s directional daylight-saving landing rule. `dateTime.differenceInDays` handles two date-times.

The wider `temporal.differenceInDays` accepts `A12Date | A12DateTime` on either side, counts whole wall-clock days including time of day, and truncates toward zero. In a mixed pair, the date is midnight in the date-time’s zone; incompatible date-time zones are an operator error.

Month/year differences and shifts remain date-only because DATE_TIME operands are not legal for those catalog operations. The temporal component extractors accept either a date or date-time.

### 6.5 Strings

```ts
declare const value: A12String;
declare const first: A12String;
declare const second: A12String;
declare const third: A12String;
declare const startInclusive: A12Integer;
declare const endInclusive: A12Integer;

const length: A12Integer = operators.string.length(value);
const concatenated: A12String =
  operators.string.concat(first, second, third);
const selectedText: A12String = operators.string.rangeAsString(
  value,
  startInclusive,
  endInclusive,
);
const selectedNumber: A12Number = operators.string.rangeAsNumber(
  value,
  startInclusive,
  endInclusive,
);
```

The `A12Integer` range bounds follow A12’s UTF-16 and one-based inclusive semantics. A start below one or after the end is an operator error.

An end beyond the string returns an empty `A12String`; `rangeAsNumber` returns zero unless the selection is a non-empty digit run. Native `substring` and numeric coercion are not implementation substitutes.

Given an explicitly typed `declare const numericText: A12String`, direct numeric conversion remains `A12Number.parse(numericText.toNative())`; `FieldValueAsNumber` has field, enum, empty, and formal-state semantics and is not presented as a pure string operator.

Empty strings make kernel string comparison non-evaluable for both equality and inequality. The direct string-specific comparison therefore preserves that state:

```ts
declare const first: A12String;
declare const second: A12String;

const equal: A12Truth =
  operators.string.equal(first, second);
const notEqual: A12Truth =
  operators.string.notEqual(first, second);

const outcome: A12Truth["kind"] = equal.kind;
```

The kernel permits string equality and inequality only; string ordering is not a legal comparison. The API must not falsely collapse `notEvaluated` to `false` or make generic equality claim a total string contract.

Pattern operators are expected but are gated by Java/JavaScript regex parity:

```ts
// Exposed only after the supported A12 pattern profile is exact.
declare const operators: A12OperatorsWithPatterns;
declare const value: A12String;
declare const pattern: A12Pattern;

const matches: A12Truth =
  operators.string.matches(value, pattern);
const violates: A12Truth =
  operators.string.violates(value, pattern);
```

Patterns are compiled to `A12Pattern` before matching so syntax errors are conversion errors rather than repeated operator failures. These functions are absent, not approximate, until the regex contract is implemented or mechanically restricted.

### 6.6 Numbers

```ts
declare const a: A12Number;
declare const b: A12Number;
declare const value: A12Number;
declare const scale: A12Integer;

const sum: A12Number = operators.number.add(a, b);
const difference: A12Number = operators.number.subtract(a, b);

const absolute: A12Number = operators.number.abs(value);
const minimum: A12Number = operators.number.min(a, b);
const maximum: A12Number = operators.number.max(a, b);

const roundedDown: A12Number =
  operators.number.roundDown(value, scale);
const roundedUp: A12Number =
  operators.number.roundUp(value, scale);
const roundedAccounting: A12Number =
  operators.number.roundAccounting(value, scale);
```

Each scale is an `A12Integer` in the catalog-supported range `0..14`.

Multiplication, division, and power are expected but are gated by the existing JavaScript decimal-context gap:

```ts
// Exposed only after JavaScript matches the A12 50-significant-digit contract.
declare const operators:
  A12OperatorsWithExactArithmetic;
declare const a: A12Number;
declare const b: A12Number;
declare const exponent: A12Integer;

const product: A12Number = operators.number.multiply(a, b);
const quotient: A12Evaluation<A12Number> =
  operators.number.divide(a, b);
const power: A12Evaluation<A12Number> =
  operators.number.power(a, exponent);
```

`exponent` is an `A12Integer`, and multiplication returns `A12Number`. Division and power return `A12Evaluation<A12Number>` because division by zero produces `divisionByZero` and invalid power domains produce structured `powerDomain` non-evaluation.

They never return `NaN` or infinity or relabel legitimate non-evaluation as `A12OperatorError`. Wrong wrappers and other API-precondition violations remain operator errors, while gated functions stay absent until their arithmetic semantics are exact.

### 6.7 Comparisons

Semantic comparison must not be confused with wrapper identity:

```ts
declare const a: A12Number;
declare const b: A12Number;

const equal: A12Truth = operators.compare.equal(a, b);
const notEqual: A12Truth = operators.compare.notEqual(a, b);
const lessThan: A12Truth = operators.compare.lessThan(a, b);
const lessThanOrEqual: A12Truth =
  operators.compare.lessThanOrEqual(a, b);
const greaterThan: A12Truth =
  operators.compare.greaterThan(a, b);
const greaterThanOrEqual: A12Truth =
  operators.compare.greaterThanOrEqual(a, b);
const order: A12Integer = operators.compare.order(a, b);
```

The six predicates return `A12Truth`; for total compatible operands it is always known. `order` returns an `A12Integer` whose value is `-1`, `0`, or `1` and exists only for ordered kinds.

| Operand kind | Equality | Ordering |
|---|---|---|
| `A12Boolean` | yes | no |
| `A12Confirm` | only with `A12Confirm` | no |
| `A12Number`, including the `A12Integer` refinement | yes | yes |
| `A12Date` with `A12Date` | yes | yes |
| `A12Time` with `A12Time` | yes | yes |
| compatible `A12DateTime` values | yes | yes |
| `A12DateRange` with `A12DateRange` | yes | no |
| `A12String` with `A12String` | string-specific `A12Truth` API | no |

All other pairs are rejected by TypeScript/Kotlin typing. Comparison follows the A12 contract, including numeric precision, instead of delegating to JavaScript object or floating-point equality.

The generic comparison surface excludes `A12String`; its non-total comparison is the string-specific `A12Truth` API above.

Tolerance checks are value-level number operators:

```ts
declare const a: A12Number;
declare const b: A12Number;

const differs: A12Truth = operators.number.differsByMoreThan(
  a,
  b,
  A12Tolerance.FIVE,
);
```

The tolerance predicate also returns `A12Truth`. `A12Tolerance` does not invent arbitrary tolerance semantics beyond the closed catalog set.

### 6.8 Date ranges

```ts
declare const start: A12Date;
declare const end: A12Date;
declare const first: A12DateRange;
declare const second: A12DateRange;
declare const target: A12DateRange;
declare const ranges: readonly A12DateRange[];

const range: A12DateRange = A12DateRange.of(start, end);

const rangeStart: A12Date = operators.dateRange.start(range);
const rangeEnd: A12Date = operators.dateRange.end(range);
const overlaps: A12Truth =
  operators.dateRange.overlaps(first, second);
const anyPairOverlaps: A12Truth =
  operators.dateRange.anyPairOverlaps(ranges);
const targetOverlapsAny: A12Truth =
  operators.dateRange.overlapsAny(target, ranges);
```

The overlap contract is inclusive at both endpoints. `A12DateRange.of` rejects `start > end`.

Overlap predicates return known `A12Truth` results. `anyPairOverlaps` tests distinct occurrences with `i < j`; one element returns false, while two equal range occurrences overlap.

`overlapsAny(target, ranges)` compares the distinguished target with each supplied range. These names avoid accidentally inheriting catalog field-list/self-pair behavior.

### 6.9 Value collections

Collection operators receive actual values, not field paths:

```ts
declare const operators:
  A12OperatorsWithExactArithmetic;
declare const amounts: readonly A12Number[];
declare const left: readonly A12Number[];
declare const right: readonly A12Number[];
declare const dates: readonly A12Date[];
declare const parts: readonly A12String[];
declare const candidates: readonly A12Number[];
declare const numberTarget: A12Number;
declare const strings: readonly A12String[];
declare const stringTarget: A12String;
declare const ranges: readonly A12DateRange[];

const total: A12Number = operators.number.sumAll(amounts);
const numericMinimum: A12Number =
  operators.number.minAll(amounts);
const numericMaximum: A12Number =
  operators.number.maxAll(amounts);
const dotProduct: A12Number =
  operators.number.sumOfProducts(left, right);

const earliest: A12Option<A12Date> =
  operators.date.minAll(dates);
const latest: A12Option<A12Date> =
  operators.date.maxAll(dates);

const text: A12String = operators.string.concatAll(parts);
const included: A12Truth =
  operators.values.includes(candidates, numberTarget);
const occurrences: A12Integer =
  operators.values.count(candidates, numberTarget);
const differentValues: A12Integer =
  operators.values.distinctCount(candidates);
const stringIncluded: A12Truth =
  operators.stringValues.includesExact(strings, stringTarget);
const stringOccurrences: A12Integer =
  operators.stringValues.countExact(strings, stringTarget);
const differentStrings: A12Integer =
  operators.stringValues.distinctCountExact(strings);
const overlappingRanges: A12Truth =
  operators.dateRange.anyPairOverlaps(ranges);
```

`sumAll([])`, numeric `minAll([])`/`maxAll([])`, and `sumOfProducts([], [])` return zero. `concatAll([])` returns an empty string; counts return zero; membership and overlap predicates return known false.

Date extrema are collection utilities and return `A12Option.none` for an empty list. `sumOfProducts` requires equal lengths and remains absent while multiplication parity is gated.

Arrays are homogeneous. These functions operate on supplied, already-valid values and do not inherit field-aggregate behavior for absent, empty, malformed, filtered, or starred document cells.

Collection predicates return `A12Truth`. The catalog-semantic `operators.values` membership/count functions accept only kinds with total equality; they do not accept strings.

The explicitly named `stringValues.*Exact` functions are collection utilities, not catalog field-list operators. They compare the supplied text values exactly, treat an explicit empty `A12String` as an ordinary array value, and return known results. Catalog string membership, where an empty field/value can make the condition non-evaluated, remains interpreter-owned.

TypeScript accepts homogeneous `readonly` arrays; Kotlin accepts `List<A12Number>`. The façade converts mechanically and preserves order without exposing a mutable collection to common semantics.

There is no `A12List` wrapper or mixed JSON-object collection model. Native `map`/`filter` handle element-wise work; this namespace contains only reductions and predicates with A12 semantics.

A document repetition such as `Lines[{Amount, TaxCode}]`, including its missing cells and row coordinates, belongs to the interpreter. After the interpreter extracts `readonly A12Number[]`, those values can be passed directly to this API.

## 7. Combinators

### 7.1 Eager value composition

Direct total operators are ordinary functions. They can be nested, passed to application code, and composed with ordinary TypeScript functions without a library-specific pipeline:

```ts
declare const invoiceDate: A12Date;

const oneMonth: A12Integer =
  A12Integer.fromSafeInteger(1);
const minusOneDay: A12Integer =
  A12Integer.fromSafeInteger(-1);
const nextMonth: A12Date =
  operators.date.addMonths(
    invoiceDate,
    oneMonth,
  );
const dueDate: A12Date =
  operators.date.addDays(
    nextMonth,
    minusOneDay,
  );
```

Calls are eager and construct no expression, deferred pipeline, or hidden state.

### 7.2 Partial evaluation

Value-producing partial operators compose through `A12Evaluation`, not exceptions or a fixed-arity generic `pipe`:

```ts
declare const operators:
  A12OperatorsWithExactArithmetic;
declare const numerator: A12Number;
declare const denominator: A12Number;

const quotient: A12Evaluation<A12Number> =
  operators.number.divide(
    numerator,
    denominator,
  );
const roundedQuotient:
  A12Evaluation<A12Number> =
  A12Evaluation.map(
    quotient,
    (value: A12Number): A12Number =>
      operators.number.roundAccounting(
        value,
        A12Integer.fromSafeInteger(2),
      ),
  );
const displayText: string =
  A12Evaluation.match(
    roundedQuotient,
    {
      value: (value: A12Number): string =>
        value.toDecimalString(),
      notEvaluated: (
        reason: A12NonEvaluationReason,
      ): string => reason.code,
    },
  );
```

`quotient` preserves legal kernel non-evaluation. Rounding runs only when a quotient exists, and `match` handles both outcomes.

`flatMap` supports arbitrary-length chains, while `zipWith` combines independent evaluations; the API is not capped at two stages.

### 7.3 Native collection composition

Operators must also work with native collection combinators:

```ts
declare const amounts: readonly A12Number[];

const rounded: readonly A12Number[] = amounts.map(
  (amount: A12Number): A12Number =>
    operators.number.roundAccounting(
      amount,
      A12Integer.fromSafeInteger(2),
    ),
);
```

### 7.4 Result composition

Parse results need only a small conventional combinator set:

```ts
declare const nativeStart: string;
declare const nativeEnd: string;

const difference: A12Result<
  A12Integer,
  A12ConversionError
> = A12Result.zipWith(
  A12Date.tryParseIso(nativeStart),
  A12Date.tryParseIso(nativeEnd),
  (start: A12Date, end: A12Date): A12Integer =>
    operators.date.differenceInDays(start, end),
);
```

The result operations are `map`, `flatMap`, `zipWith`, `match`, and `getOrElse`. Together with the corresponding `A12Evaluation` operations, they cover the semantic result channels without creating a custom expression DSL or general-purpose effect system.

### 7.5 Bulk conversion adapters

The exact `A12Codecs.array(item)` composition in §5.4 is the primary array boundary and supports both scalar and structured wire types. `A12Arrays` remains an optional lower-level callback adapter for callers that already have scalar constructor or projection functions:

```ts
type A12NativeScalar =
  | string
  | boolean
  | number
  | bigint
  | Date;

interface A12ArrayAdapters {
  wrapAll<
    I extends A12NativeScalar,
    O extends A12Value,
  >(
    inputs: readonly I[],
    wrap: (input: I) => O,
  ): readonly O[];

  tryWrapAll<
    I extends A12NativeScalar,
    O extends A12Value,
    E extends A12ConversionError =
      A12ConversionError,
  >(
    inputs: readonly I[],
    wrap: (input: I) => A12Result<O, E>,
  ): A12ArrayConversionResult<O, E>;

  unwrapAll<
    I extends A12Value,
    O extends A12NativeScalar,
  >(
    values: readonly I[],
    unwrap: (value: I) => O,
  ): readonly O[];

  tryUnwrapAll<
    I extends A12Value,
    O extends A12NativeScalar,
    E extends A12ConversionError =
      A12ConversionError,
  >(
    values: readonly I[],
    unwrap: (value: I) => A12Result<O, E>,
  ): A12ArrayConversionResult<O, E>;
}

declare const A12Arrays: A12ArrayAdapters;
```

| Adapter behavior | Contract |
|---|---|
| Native domain | `A12NativeScalar` is exactly `string`, `boolean`, `number`, `bigint`, or `Date` |
| Generic bounds | Wrapping maps a native scalar subtype to an `A12Value` subtype; unwrapping reverses those bounds |
| Error subtype | `E` is the exact `A12ConversionError` subtype returned by a result callback |
| `wrapAll` | Fail-fast; a thrown conversion error becomes one indexed `A12ArrayConversionError`, while foreign exceptions propagate |
| `tryWrapAll` / `tryUnwrapAll` | Evaluate every element and accumulate every indexed conversion failure |
| `unwrapAll` | Returns a fresh readonly array and neither catches nor relabels callback failures; exactness is the selected converter’s contract |
| Error views | `issues` is flattened with each zero-based index prefixed to its path; `items` groups the original errors by source element |
| Input shape | Preserve order and reject sparse holes, `null`, `undefined`, and foreign element kinds |
| Native alternative | Ordinary `map` remains valid when indexed accumulation is unnecessary |

```ts
declare const nativeAmounts: readonly string[];
declare const counts: readonly A12Integer[];

const attempted: A12ArrayConversionResult<A12Number> =
  A12Arrays.tryWrapAll(
    nativeAmounts,
    (
      input: string,
    ): A12Result<A12Number, A12ConversionError> =>
      A12Number.tryParse(input),
  );

const attemptedNativeCounts: A12ArrayConversionResult<number> =
  A12Arrays.tryUnwrapAll(
    counts,
    (
      value: A12Integer,
    ): A12Result<number, A12ConversionError> =>
      value.tryToSafeInteger(),
  );

if (!attempted.ok) {
  const errors: readonly A12IndexedConversionError[] =
    attempted.error.items;
}

if (attempted.ok) {
  const amounts: readonly A12Number[] = attempted.value;
  const nativeAgain: readonly string[] = A12Arrays.unwrapAll(
    amounts,
    (value: A12Number): string => value.toDecimalString(),
  );
}
```

The checked unwrapping form supports converters such as `A12Integer.tryToSafeInteger` and `A12DateTime.tryToJsInstantDate`.

### 7.6 Boundary

These combinators execute ordinary functions over actual values. They do not build, serialize, prepare, cache, or later interpret an expression graph.

Symbolic composition is a different interpreter/authoring API with AST, typing, preparation, and execution questions; it belongs in the [Interpreter API proposal](INTERPRETER-API-PROPOSAL.md) or a later authoring proposal.

## 8. Capability and parity coverage

The public surface needs a mechanically complete relationship to the live operator catalog. Prose grouped by family is not enough to prove that every catalog operator and every observable facet has a disposition.

### 8.1 Capability manifest

```ts
type A12ApiLayer =
  | "directOperator"
  | "interpreter"
  | "authoring";

type A12CapabilityAvailability =
  | {
      readonly kind: "available";
    }
  | {
      readonly kind: "parityGated";
      readonly gapId: string;
    };

type A12CapabilitySource =
  A12OperationIdentity;

type A12CapabilityClassification =
  | "kernelOperator"
  | "valueConstructor"
  | "exactConversion"
  | "collectionUtility"
  | "semanticCombinator";

interface A12CapabilityEntry {
  readonly capabilityId: string;
  readonly source: A12CapabilitySource;
  readonly classification:
    A12CapabilityClassification;
  readonly layers: readonly A12ApiLayer[];
  readonly availability:
    A12CapabilityAvailability;
  readonly directFunction?: string;
  readonly contextualFacets?:
    readonly string[];
}

declare const operatorCapabilities:
  readonly A12CapabilityEntry[];
```

`operatorCapabilities` is an opt-in generated manifest or build/CI artifact, not an eagerly initialized object in the primary bundle.

| Field | Contract |
|---|---|
| `source` | Carries the exact catalog ID or a stable direct-utility ID |
| `capabilityId` | Distinguishes semantic facets when one catalog entry crosses boundaries |
| `classification` | Separates kernel operators from constructors, exact conversions, collection utilities, and semantic combinators |
| `layers` | States whether each facet is direct, interpreter, or authoring API |
| `availability` | Separates available from parity-gated capability; every gate has an exact `gapId` |
| `directFunction` | Uses the actual public name, such as `operators.dateTime.differenceInHours` |
| `contextualFacets` | Identifies model/document behavior that remains interpreter-owned |

### 8.2 Mapping and coverage rules

One catalog entry may yield several entries. Strict resolved date construction can map to `A12Date.of`, while unreal construction candidates, empty/malformed components, `Valid(Date(...))`, and `Invalid(Date(...))` map to interpreter facets. Field-source variants of a value operator may map to the same direct function plus an interpreter operand-resolution facet.

The matrix is generated from or CI-checked against the live catalog and enforces all of the following:

- every catalog ID has at least one disposition;
- every direct function points to a catalog identity or is explicitly classified as a value, conversion, collection, or combinator utility;
- every gated entry points to one exact gap and is absent from the callable baseline type;
- no gap can be closed in prose while the platform parity laws still fail;
- a catalog addition or removal fails the guard until the matrix and public surface agree;
- JavaScript and JVM availability are recorded independently where a platform primitive is parity-gated.

The matrix is a coverage system, not a semantic source. The operator catalog and kernel evidence remain the source of semantic behavior. The matrix prevents omission, makes scope visible to TypeScript developers, and gives an exact release boundary for decimal, regex, time-zone, and future parity work.

## 9. Kotlin ownership and public projections

The semantic implementation lives once in the Kotlin Multiplatform runtime-semantics layer. The direct façade and interpreter both depend on it; TypeScript and JVM projections contain mechanical adaptation only.

The current interpreter already contains reusable pieces such as decimal behavior and date arithmetic. Other semantics still embedded in AST evaluation must be extracted into pure value-level functions before both callers can share them.

The direct API is therefore not a second implementation and not merely export annotations:

- `commonMain` owns every operator decision;
- the interpreter resolves fields/context and delegates its internal resolved values to the same semantic primitives;
- the public TypeScript façade authenticates wrappers and translates errors at its boundary, while the interpreter calls a trusted internal entry point and does not allocate public wrappers or run public guards/codecs per AST node;
- `jsMain` converts exports, arrays, errors, and declarations only;
- `jvmMain` provides Java-friendly host projections and conversions only and imports no kernel API;
- platform `expect`/`actual` primitives remain governed by common parity laws.

The semantic code should first be extracted in place. Before publication, bundle size, dependency direction, and npm/Maven ABI evidence must decide whether it remains an independent surface of `:interpreter` or moves into a lower-level `:operators` KMP artifact shared by the interpreter.

The dependency direction is one-way:

```text
kernel-free operator semantics
          ▲
          │
kernel-free interpreter
          ▲
          │ required product deliverables, platform-specific and separately packaged
kernel-compatibility adapters for Interpreter customization
```

The TypeScript and JVM Kernel compatibility adapters are required product deliverables for projects that reuse existing Kernel Custom Field Type or Custom Condition implementations. They are separately packaged and installed only where that reuse is needed.

Each adapter may depend on the kernel-free Interpreter and Operator artifacts plus its platform’s Kernel API. Neither base artifact may depend directly or transitively on an adapter or Kernel API, and the direct Operator package contains no customization registry, adapter discovery, or custom-operator extension mechanism.

### 9.1 TypeScript-first surface

TypeScript examples, names, declaration review, and consumer tests define the public API. The façade must not leak Kotlin collections, mangled overloads, `Dec`, `Val`, `Expr`, `Cond`, `EvalContext`, or other execution internals.

The exported operators must be normal callable functions so they can be passed to `map`, application code, or composition helpers.

The discoverable namespace, including `operators.dateTime.differenceInHours`, is the primary surface. Browser-oriented family subpaths such as `@a12/operators/date-time` may additionally re-export the same functions when emitted Kotlin/JS bundle measurements prove useful dead-code elimination. A subpath never carries a second TypeScript semantic implementation and never changes the function name or signature.

Current JavaScript evidence supports Node. This proposal does not promise a browser target until a real browser consumer triggers packaging and compatibility qualification.

Optional TypeScript boundary adapters, including the separate [Zod integration proposal](ZOD-INTEGRATION-PROPOSAL.md), may wrap `A12Codecs` and translate structured issues. They depend outward on this API, contain no A12 semantics, and are packaged separately. The primary operator package and every direct operator call remain free of Zod or another schema runtime.

### 9.2 Secondary JVM surface

The JVM target exposes the same values and operator semantics through a thin Java-friendly façade. It may adapt Java collections, exceptions, `BigDecimal`, or `java.time` explicitly, but it does not implement an operator again.

Java support is useful, but it does not determine TypeScript naming or delay the primary surface.

## 10. Exactness, errors, and release boundary

### 10.1 Parity gates

Every public operator must reproduce the applicable kernel behavior exactly through the clean-room differential/conformance approach. Each parity-gated capability remains visible in the generated manifest and the project’s exact gap ledger until its cross-platform laws pass.

| Gap | Release rule |
|---|---|
| JavaScript decimal rendering and mutable host-global decimal context | `A12Number` does not ship until construction, addition, subtraction, comparison, rounding, and canonical plain-decimal rendering are isolated and exact |
| JavaScript 50-significant-digit arithmetic context | multiplication, division, and power remain absent until exact |
| Java/JavaScript regex dialect | `A12Pattern` and pattern operators remain absent until the accepted pattern domain is exact and enforced before evaluation |
| Time-zone identifiers and history | UTC ships first; named zones remain absent until their supported history is exact |

Affected operations are fixed or excluded, never approximated with weaker host semantics.

### 10.2 Fallibility

Fallibility is uniform:

- every declaration-block method that can fail—`parse…`, date/time/range `of`, checked native-number/date conversions, local/instant date-time resolution, `combine`, base-year construction, checked `toSafeInteger`, and gated pattern compilation—throws the stable JS-safe `A12ConversionError`;
- each fallible constructor/converter has the explicitly declared adjacent `try…` form returning `A12Result<SuccessType, A12ConversionError>`;
- `A12String.of`, `A12Boolean.of`, `A12Confirm.confirmed`, `A12Integer.fromBigInt`, canonical text projections, `A12Integer.toBigInt`, lossy numeric projection of an already-valid A12 number, and `A12TimeZone.utc` are total for their typed inputs and do not receive meaningless try-forms;
- reversed endpoints passed to `A12DateRange.of` are a construction failure and therefore produce `A12ConversionError`;
- a total typed operator returns its value or `A12Truth` directly, while an actual API-precondition violation such as out-of-domain string bounds or scale, unequal dot-product lengths, incompatible evaluation zones, or a foreign wrapper supplied through unsafe JavaScript throws structured `A12OperatorError`;
- division by zero and invalid power domains are legal kernel operands and return `A12Evaluation.notEvaluated`; they are neither conversion errors nor operator errors;
- `A12Result.capture` converts an operator call to the result form without duplicating every method as `tryX`;
- unsupported platform semantics have no exported function until exact, and incompatible operand kinds fail at compile time.

Checked unwrapping never truncates silently. A visibly lossy conversion is a separate method. The indexed conversion forms preserve the same scalar errors and add each failing index.

Direct calls never return field suppression, pointers, or rule findings. The `A12Truth` and `A12Evaluation` `notEvaluated` variants are direct semantic outcomes, not hidden document state.

Each public export must either cite its catalog identity or be explicitly classified as an A12 value, conversion, collection, or combinator utility. Exact conversions preserve the represented value; canonical rendering need not preserve the source spelling. Potentially lossy native conversions are checked or visibly named.

### 10.3 Runtime and performance guarantees

Values are immutable and operators are stateless/reentrant. JVM calls are thread-safe; there is no mutable global decimal context, clock, regex engine state, or time-zone state. Platform projections defensively isolate any host library with mutable globals.

The semantic implementation lives once in Kotlin `commonMain`, and every published base Operator artifact and ordinary TypeScript/JVM projection remains kernel-free and MIT-licensed, with no direct or transitive Kernel dependency. Required Kernel compatibility deliverables are separately packaged and licensed, installed only for Kernel-interface reuse, and never participate in a direct operator call.

Direct calls allocate no session and perform no parsing, AST traversal, model lookup, document iteration, customization lookup, or adapter dispatch. Extracting shared semantics must not worsen the existing interpreter’s parsing, AST traversal, startup, warmup, throughput, scaling, allocation, or footprint.
