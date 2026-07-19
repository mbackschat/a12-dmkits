# Topic 1 proposal: TypeScript-first access to A12 operator semantics from Kotlin Multiplatform

## Status

Fresh proposal based on the current interpreter, rulekit, adapter, catalog, tests, and documented gaps, followed by an independent adversarial review. The API names are illustrative. This document proposes a direction; it does not authorize implementation.

**Product priority:** TypeScript is the primary consumer, API-design target, documentation language, packaging target, and acceptance surface. Kotlin Multiplatform is the implementation strategy. Java/JVM support is desirable and should come from the same Kotlin implementation through a thin secondary façade; it must not become the center of Topic 1 or cause a second operator implementation.

## 1. Executive recommendation

Expose the A12 **evaluation language**, not a flat list of “kernel operator” methods.

Build it once in Kotlin Multiplatform:

- semantic model, parser/preparation, document policy, operator dispatch, context/iteration rules, and evaluation orchestration live in `commonMain`;
- unavoidable platform primitives implement common contracts through `expect`/`actual` and must pass shared parity laws;
- a deliberately designed Kotlin/JS export adapter publishes the primary npm/TypeScript API;
- the same common implementation compiles to JVM, with a thin Java-friendly façade and Maven publication where feasible;
- `jsMain` and `jvmMain` may adapt DTOs, callbacks, exceptions, and language ergonomics, but they must not implement operator semantics;
- Java-only licensed integrations remain separate optional artifacts and do not shape the TypeScript API.

The intended outcome is **one Kotlin implementation supporting both TypeScript and Java, with TypeScript first**. This is not two client libraries that happen to share a specification.

The recommended product is a model-owned, prepare-once evaluation session over immutable document snapshots:

```text
expanded model + configuration
             │
             ▼
     EvaluationSession
        │          │
        │ prepare  │ read/freeze
        ▼          ▼
 PreparedProgram  DocumentSnapshot
        │          │
        └──── evaluate many ────► versioned boundary results
```

The first TypeScript delivery should expose capabilities whose semantics already exist and whose public contract can be made precise:

1. A published npm package with reviewed TypeScript declarations, consumer tests, and explicit Node/browser support claims.
2. A frozen, model-checked document snapshot, dynamic document builder/editor, and a precisely specified instance-JSON format.
3. Full and partial validation, stored-rule evaluation, stored computations, and single stored computation through a stable TypeScript façade.
4. A bounded prepared **numeric expression** for calculation previews such as the `Sum(...)` example below.
5. A prepared **candidate rule** that is parsed once, evaluated through the existing rule machinery, derives its natural iteration contexts, and reports every located firing or suppression.
6. Interpreter-owned A12 value types at scalar boundaries; never JavaScript floating-point or `Date` objects as semantic operands.
7. A secondary Java/JVM façade compiled from the same KMP implementation, with no duplicated evaluation code.

A prepared free-standing value expression is valuable, including the example:

```text
Sum(Lines*/Amount) * {1 - [DiscountPercent] / 100}
```

For a TypeScript-first product, this bounded expression observer belongs in the first useful release. The current interpreter has no general public expression result type, no general expression-kind inference, and no public context object capable of representing every parallel/correlated iteration. The first expression API must therefore be **number-only**, support validation-style observation at a simple model location, and reject shapes whose result or context cannot be represented faithfully.

The candidate-rule AST seam may be implemented first internally because it has a safer existing semantic context. That implementation order must not be confused with product priority: the TypeScript release is not complete for the stated operator-access use case until the bounded numeric observer is available.

Do not put ad hoc candidate computations in the first release. A computation is not just an expression with a different enum value: it has a target field, guards and alternatives, dependency ordering, cascade overlays, poison behavior, target formatting, implicit validation, and apply semantics. Candidate computation deserves a separate feasibility and contract increment.

Do not expose 110 one-method-per-catalog-entry calls, a stringly `evaluate(operator, operands)` dispatcher, the current `Expr`/`Cond`/`Val` tree, or the current mutable `Document` builder as the product API.

Keep `DocumentV2` outside the kernel-free multiplatform core. It is an optional Java-only appendix, not a TypeScript product requirement and not part of Topic 1’s primary delivery estimate. A later adapter may snapshot a matching `DocumentV2` into the public document representation, subject to a licensing/publication decision and typed-value conversion tests.

This proposal is independent of [Topic 2](RULEKIT-INTERPRETER-BRIDGE-PROPOSAL.md). [Topic 2](RULEKIT-INTERPRETER-BRIDGE-PROPOSAL.md) may compile rulekit-authored programs into the prepared boundary defined here; Topic 1 does not require a rulekit migration.

## 2. Motivation

### 2.1 The real customer need

TypeScript applications are the primary consumers. They need to apply A12 behavior without reimplementing it, especially in browser and Node workflows. Representative jobs are:

- previewing a calculation in a browser or TypeScript application;
- evaluating a candidate validation rule against current form data;
- validating full or partial documents;
- computing an existing computed field;
- evaluating many instances against one fixed model;
- constructing a model dynamically, freezing it, and evaluating instances against it;
- using a small pure scalar operation where no document semantics are involved.

Java applications should be able to perform the same jobs through the KMP JVM target when feasible, but Java ergonomics and Java-only kernel objects are secondary concerns. These are evaluation jobs. “Call operator X” is only sometimes the right abstraction.

### 2.2 Why ordinary native values are insufficient

A12 numeric behavior is exact decimal behavior, not JavaScript `number`. A12 dates, times, date-times, and date ranges are model-formatted semantic values, not JavaScript `Date` objects. The secondary Java projection likewise does not use `double` or `java.time` as authoritative semantic operands. A12 strings can be subject to configured character sets and field formats.

More importantly, many language constructs do not operate on values alone. Their result depends on:

- whether a field instance is absent, present-empty, filled, or formally invalid;
- the field kind, scale, signedness, format, and model time zone;
- three-valued logic and formal-error suppression;
- partial-validation relevance;
- rule error field and VALUE/OMISSION polarity;
- natural, starred, nested, parallel, or `$`-correlated iteration;
- computation target, guard tier, dependency closure, poison, and storage formatting;
- configured clock, custom conditions, predefined types, custom field types, legal charset, and locale.

A direct scalar method cannot honestly represent those semantics.

### 2.3 What the repository already has

The Kotlin Multiplatform interpreter is the repository’s kernel-free runtime evaluation engine. It already implements full and partial validation, stored and injected candidate-rule evaluation, stored computations, compute application, and single stored-computation evaluation over its model and document representations.

Stored model rules and computations are parsed and cached inside a `DmInterpreter`. The evaluator uses indexed document structures and specialized iteration machinery.

JavaScript runs the shared common algorithm and portable conformance corpus and is the product target of this proposal. Its current `big.js` actual differs from the kernel’s `MathContext(50)` behavior for multiplication beyond 50 significant digits and for bounded division/power, and JavaScript regex is a different dialect. The JVM build remains the real-kernel differential oracle; that verification role does not make Java the primary product. Standalone JavaScript also requires expanded models because include expansion is not in `commonMain`.

The current interpreter also has a known legal-input divergence for pre-1996 `Europe/Berlin` date/date-time history. Modern 1996+ Berlin behavior is covered; earlier tzdb transitions are not. Rare included-model reference shapes are loudly refused by the existing host expander, but include expansion is outside the initial already-expanded input boundary proposed here.

### 2.4 What is actually missing

The missing work is not operator implementation. It is product-boundary and orchestration work:

- a stable npm/TypeScript API rather than exposing raw Kotlin/JS implementation types;
- a KMP-owned common boundary that can also support a thin Java/JVM projection;
- immutable evaluation inputs;
- prepare-once candidate programs;
- precise preparation and failure contracts;
- a public value/result representation;
- TypeScript exports plus configuration and callback adapters;
- exact instance-JSON semantics;
- performance and footprint certification for the new paths.

Some of this requires interpreter refactoring. It is not merely packaging.

## 3. What “kernel operators” means

The public library should reproduce A12 language semantics without importing proprietary kernel classes. Its public types are therefore **interpreter-owned A12 semantic types**, not `com.mgmtp.*` types.

The operator catalog is heterogeneous:

| Catalog family | Examples | Truthful public route |
|---|---|---|
| Arithmetic and value functions | arithmetic, rounding, date shifts, aggregates | prepared expression or optional scalar API |
| Conditions | comparisons, fill predicates, patterns, tolerances | prepared candidate/stored rule; bounded prepared condition where meaningful |
| Logical syntax | `And`, `Or`, grouping | parser plus prepared rule |
| Path and iteration syntax | `*`, `Having`, `$`, semantic index | model-owned parser and evaluation machinery |
| Rule-only behavior | error field, suppression, polarity | candidate/stored rule |
| Dedicated full-validation behavior | `RepetitionNotUnique` special handling | stored/full validation; later prepared candidate after evaluator unification |
| Computation-only behavior | guards, alternatives, poison, cascade, formatting | stored computation; later candidate-computation feature |
| Constants and configuration | `Today`, `Now`, `BaseYear`, custom conditions/types | configured session |

“Catalog-wide access” means every legal construct remains reachable through a semantically complete route. It does not mean each catalog row becomes a scalar method.

Do not create a second hand-maintained classification registry in KMP. The table above is a design classification. If discovery metadata becomes a product, derive or generate it from one owned source and guard coverage mechanically.

## 4. Design invariants

### 4.1 TypeScript-first product, Kotlin-owned semantics

The normative public examples, API review, consumer compilation tests, package acceptance, and usability work are TypeScript-first. The implementation remains Kotlin Multiplatform so the evaluator and orchestration are written once.

The shared semantic policy belongs in `commonMain`: operator definitions and dispatch, parsing/preparation, paths, contexts, iteration, formal-state rules, computation policy, snapshots, and result meaning. A `jsMain` export layer may compensate for Kotlin/JS export restrictions, and a `jvmMain` layer may improve Java ergonomics. Those façade layers are allowed to perform mechanical boundary conversion only. Any conditional operator result, path rule, iteration decision, formal-state rule, or computation behavior in a platform façade is a duplication defect.

Unavoidable platform primitives are the narrow exception. Decimal, regex, and similar `expect`/`actual` implementations may live in platform source sets, but they implement one common contract and must pass shared JVM/JavaScript parity and kernel-differential laws. This is platform implementation, not permission to duplicate higher-level operator policy.

If TypeScript and Java ergonomics conflict, keep one common semantic contract and use thin platform adapters. Do not distort the TypeScript API into Java builder conventions, and do not fork the runtime to make either language look native.

### 4.2 One runtime evaluator

All document evaluation continues to execute in the existing interpreter runtime. TypeScript and Java façades may translate inputs and outputs, but they do not decide operator results.

### 4.3 One engine owner

The product façade must delegate to exactly one `DmInterpreter` instance and its configuration. It must not create a second model, clock, registry set, cache hierarchy, or computation engine.

An `EvaluationSession` name is used below to describe a stable boundary façade. Internally it owns one `DmInterpreter`; it is not a competing evaluator.

### 4.4 Model provenance

Every session is tied to one expanded model and a stable model fingerprint. Every prepared handle and every `DocumentSnapshot` carries an unforgeable owner/model token.

A handle or snapshot from one session must be rejected by another session even if paths happen to have the same spelling. This prevents a rule or a document configured for one model from silently running against a model with a changed kind, scale, format, or repetition structure.

### 4.5 Valid-model precondition

The interpreter is an evaluation engine, not the complete kernel consistency/MVK checker.

Preparation may perform syntax parsing, reference normalization, and checks the evaluator actually owns. It must not claim to prove full model/rule validity, infer every expression kind, or reproduce the kernel’s consistency gate.

The exactness statement is:

> For an input accepted by an explicitly defined and mechanically enforced capability/platform profile, the public façade must produce the same evaluation outcome as the existing interpreter and the applicable kernel differential oracle.

Kernel legality is necessary but not sufficient while known interpreter/platform gaps remain. The initial profile must state at least:

- input models are already expanded and contain no unresolved include shell;
- prepared candidates exclude constructs, such as `RepetitionNotUnique`, that the prepared seam does not yet execute faithfully;
- `Europe/Berlin` models are rejected from the exact profile unless the historical-timezone gap is closed and differential-locked; a narrower 1996+ profile is acceptable only if a complete guard proves that parsed values and temporal operations cannot enter the earlier range;
- JavaScript is not called kernel-exact until multiplication, division, and exponentiation reproduce the kernel/JVM 50-significant-digit policy, or each differing case is excluded by an enforceable and tested bound;
- JavaScript regex is either implemented with kernel-compatible semantics or restricted by a checked common-subset profile.

An out-of-profile input returns a structured unsupported-profile result. It is never silently evaluated under a weaker behavior and then labeled exact. Merely scanning initial document values is not a sufficient Berlin guard because date arithmetic can cross the boundary. The preferred release path is to close the Berlin and JavaScript arithmetic gaps rather than preserve awkward restrictions.

Preparation returns an explicit validity status. A candidate prepared for preview is `UNCERTIFIED`; it may be evaluated, but its result is an interpreter preview rather than evidence that the kernel would accept the candidate in a model.

`UNCERTIFIED` local evaluation is the baseline TypeScript capability. It requires no JVM process and no Java SDK. Remote certification is an optional extension for consumers that need an MVK-accepted candidate; it must not block delivery of the local TypeScript preview/evaluation API.

A production candidate is `CERTIFIED` only when a kernel-linked JVM consistency service has checked that exact candidate against that exact expanded-model fingerprint and issued a certificate over both. This service invokes the kernel consistency/MVK path, never kernel evaluation. The certificate is input to strict preparation; merely validating the base model does not certify a newly added candidate.

The trust boundary must be real rather than a caller-provided `certified: true` field:

- the primary TypeScript integration requires a versioned signed attestation, or an equivalently authenticated trusted channel, covering at least the certificate format version, model fingerprint, canonical candidate fingerprint, kernel version/validation profile, and issuer identity;
- a secondary in-process Java integration may use a non-serializable, opaque `CandidateCertificate` returned directly by the trusted consistency service;
- the TypeScript session accepts only configured remote issuers and verifies the signature plus exact model-and-candidate fingerprints; the secondary JVM path accepts only its unforgeable in-process certificate type;
- if that attestation protocol is not approved and implemented, TypeScript supports `UNCERTIFIED` preview only.

A kernel-free TypeScript client cannot issue an MVK certificate itself. Receiving and verifying a signed attestation does not put kernel code in the TypeScript evaluator.

### 4.6 Prepare once, evaluate many

Preparation is explicit and owner-bounded. There is no global string cache and no unbounded registry.

The handle is an opaque reference to a parsed execution form. It is reusable across snapshots of the same model and configuration.

### 4.7 Stable inputs during evaluation

The current interpreter `Document` is mutable during construction and owns caches. The public contract must not expose it as concurrent live form state.

A fresh `DocumentBuilder` and a snapshot-derived `DocumentEditor` have deliberately different mutation contracts. The builder adds placements and rejects duplicates. The editor uses explicit put/remove/empty operations against a private copy-on-write state. `freeze()` produces a `DocumentSnapshot`; neither construction object remains usable afterward. A snapshot is never mutated during or between evaluations. Thread-safe concurrent snapshot reuse is promised only after it is tested.

### 4.8 Native values are transport, not semantics

Model and instance JSON necessarily cross the platform boundary as text. That does not make `String` the semantic arithmetic/date type.

Scalar APIs accept interpreter-owned types such as `A12Number` and `A12Date`. JavaScript `number` and `Date` are not accepted as semantic operands. The secondary Java projection follows the same rule for `double` and platform date objects.

## 5. Scope and non-scope

### 5.1 Version-one scope

- published npm package with a stable, reviewed TypeScript façade;
- declared and tested Node/browser targets rather than inferring browser support from Node;
- Kotlin Multiplatform `commonMain` ownership of semantic policy and orchestration, with platform primitives constrained by common parity laws;
- versioned document snapshot, builder/editor, and result DTOs;
- prepared numeric expressions for bounded validation-style observation;
- prepared candidate rules;
- explicit `UNCERTIFIED` local TypeScript baseline, with optional signed-attestation support for `CERTIFIED`;
- model fingerprint plus handle/snapshot ownership;
- expanded-model input;
- TypeScript-safe configuration/callback adapters for every interpreter customization point;
- secondary Java/JVM support from the same KMP implementation, without semantic duplication;
- correctness, startup, throughput, allocation, footprint, and consumer-compilation gates.

### 5.2 Secondary Java support

The same KMP project should publish a JVM/Maven artifact if the Kotlin/JVM surface can be made cleanly consumable from Java. A thin `jvmMain` façade may add Java builders, Java collection views, and exception adapters while delegating every semantic operation to `commonMain`.

Java support is a secondary deliverable, not the design center. Failure to provide a particular Java convenience must not trigger duplicated semantics or delay the primary TypeScript package. The proposal still aims to support both languages from one implementation.

### 5.3 Separate feasibility increments

- ad hoc candidate computations;
- optional scalar utility families;
- `DocumentV2` snapshot import;
- include expansion in common code.

### 5.4 Non-goals

- a remote untrusted multi-tenant evaluation service;
- publishing internal AST/context/value classes as stable ABI;
- implementing operator definitions, dispatch, context, iteration, or formal/computation policy in `jsMain`, `jvmMain`, Java, or TypeScript instead of `commonMain`;
- making Java-only APIs or licensed kernel types part of the common/TypeScript contract;
- reproducing the full MVK consistency checker;
- accepting arbitrary native objects and guessing their A12 meaning;
- a rulekit migration.

## 6. Proposed architecture

```text
                      expanded model JSON
                              │
                              ▼
                   common ModelLoader/ModelDef
                              │
                              ▼
                  one internal DmInterpreter
                              │
                 ┌────────────┴────────────┐
                 │ stable façade/session   │
                 └────────────┬────────────┘
                              │
       ┌──────────────────────┼──────────────────────┐
       │                      │                      │
 prepare candidate      parse/freeze instance   stored operations
       │                      │                      │
       ▼                      ▼                      ▼
 PreparedRule          DocumentSnapshot      validate/compute
       │                      │
       └──────── evaluate ────┘
                              │
                              ▼
                    versioned public DTOs
```

Platform and publication layer:

```text
                         Kotlin commonMain
                    semantic API + evaluator services
                              │
                 ┌────────────┴────────────┐
                 │                         │
                 ▼                         ▼
        jsMain export adapter      jvmMain Java adapter
        DTO/callback glue only     DTO/ergonomic glue only
                 │                         │
                 ▼                         ▼
        npm + reviewed .d.ts       Maven/JVM artifact
        TypeScript — PRIMARY       Java — SECONDARY
```

The TypeScript façade does not expose `ModelDef`, `Document`, `Expr`, `Cond`, `Val`, `EvalContext`, Kotlin collections, or Kotlin sealed implementation types. The Java façade should avoid freezing those execution internals as supported ABI as well.

Kotlin/JS export limitations make some platform glue inevitable. That glue is not considered duplicated implementation when it only converts stable common DTOs, callbacks, errors, and collection shapes. It becomes duplication the moment it interprets an operator or reconstructs evaluation context.

### 6.1 Primary TypeScript publication boundary

The canonical product boundary is designed from TypeScript constraints first:

- plain exported classes or readonly object shapes;
- arrays rather than Kotlin collections;
- tagged unions/results rather than leaked Kotlin sealed implementations;
- explicit lifecycle methods for session, builder, editor, snapshot, and prepared handles;
- synchronous callback interfaces matching the synchronous evaluator;
- stable JavaScript exceptions/error DTOs rather than raw Kotlin throwable shapes;
- generated and reviewed `.d.ts` plus handwritten TypeScript consumer tests.

The same semantic implementation does not imply identical source syntax or ABI in TypeScript and Java. TypeScript may use readonly option objects and tagged unions while the secondary Java façade uses builders and sealed DTO projections. Both delegate to the same common objects and behavior.

Platform `expect`/`actual` implementations are allowed only for genuine primitives such as decimal/regex/platform support. Every actual must obey shared parity laws. A platform actual is not permission to weaken or fork operator semantics.

The primary client journey is TypeScript end to end:

```ts
import {EvaluationSession} from "@a12/interpreter";

const session = EvaluationSession.open(expandedModelJson, {
  clock: {kind: "fixed", instant: "2026-07-19T12:00:00Z"},
});

const document = session.readInstanceJson(instanceJson);
const expression = session.prepareNumberExpression({
  text: "Sum(Lines*/Amount) * {1 - [DiscountPercent] / 100}",
  baseGroup: "/Order",
}).orThrow();

const observation = session.evaluateNumberAt(
  expression,
  document,
  {group: "/Order", repetitions: [1]},
);

if (observation.kind === "value") {
  const total: A12Number = observation.value;
  console.log(total.canonicalText());
}
```

No Java object, JVM process, `DocumentV2`, or native JavaScript number/date is involved in this local evaluation path.

Those Kotlin declarations are technically public today because of default visibility. This proposal merely avoids adding them to the supported product contract. Making them internal would be a separate compatibility change.

## 7. Candidate-rule preparation

### 7.1 Why the candidate-rule seam may land first internally

A candidate rule has the context that condition semantics need:

- declaring group;
- error field;
- stable synthetic name/path;
- code and severity;
- localized messages;
- natural iteration scope;
- formal suppression;
- message polarity;
- custom-condition context.

That makes it a much safer first internal preparation seam than a context-free `PreparedCondition`. It does not make candidate rules more important than numeric-expression previews to the TypeScript product.

### 7.2 Illustrative TypeScript specification

Use an idiomatic readonly options object rather than importing Java builder conventions into TypeScript:

```ts
const candidate: CandidateRuleSpec = {
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
```

The façade returns a structured preparation result:

```ts
const preparation = session.prepareRule(candidate);
```

Preparation failures include syntax errors, unresolved paths, owner/model mismatch, and a construct that the prepared-rule route explicitly excludes. Missing external callbacks may be rejected during preparation when detectable; callback failure at runtime is a distinct evaluation error.

The TypeScript call above produces an `UNCERTIFIED` preview handle. If the optional certified-TypeScript extension ships, strict preparation may additionally accept a verified remote attestation bound to the session’s expanded-model fingerprint and canonical candidate content. An opaque in-process `CandidateCertificate` is exclusively a secondary JVM mechanism and is not accepted by the TypeScript API.

Preparation does **not** certify kernel consistency. The separate kernel-linked JVM consistency service does, and it calls the kernel only for consistency/MVK validation.

Version one explicitly rejects `RepetitionNotUnique` as a prepared candidate. Full/stored validation already routes that construct through a dedicated `RepetitionNotUniqueEvaluator` rather than ordinary `rowOutcomes`, and the current single-candidate API does not reproduce that special route. Supporting it later requires a unified prepared-rule outcome seam that covers both evaluators and preserves located findings/suppressions; it is not achieved by passing an AST to `rowOutcomes`.

### 7.3 Required implementation refactor

The current injected candidate path is not already prepared: it reaches the private rule path and obtains/parses the AST through `ParsedModel`. The implementation must refactor the rule-evaluation seam to accept a supplied parsed AST.

Conceptually:

```kotlin
rowOutcomes(rule, preparedAst, pass)
```

Stored rules continue to call the same seam with their current cached AST. Prepared candidates call it with their owner-bound AST.

This can preserve the execution representation and traversal after preparation, but it is an internal refactor and must be proven behavior- and performance-neutral. The proposal does not claim the execution path is literally untouched.

### 7.4 Result shape

Do not reuse current Kotlin `RuleResult` as the promised TypeScript API. Define a versioned, JS-safe boundary DTO in the common contract, with a secondary Java projection if needed.

Repeated candidate rules can fire in more than one row. The result should therefore retain located findings:

```text
CandidateRuleEvaluation
  overall: FIRED | PASSED | SUPPRESSED | UNSUPPORTED
  findings: [code, rulePath, errorPointer, severity, polarity, text, referenced, fillToFix]
  suppressions: [fieldPointer, formalErrorCode]
```

`FIRED` means at least one finding. `SUPPRESSED` means no finding and at least one natural row could not be evaluated due to formal state. The exact mixed-row aggregation rule must be locked against existing behavior; callers that need detail receive the located lists rather than only the aggregate.

Natural iteration is derived by the interpreter. The client does not manufacture parallel join rows or `UNKNOWN_REP` sentinels.

## 8. Prepared numeric expression: bounded first-release TypeScript capability

### 8.1 Intended use

This TypeScript-first capability supports previews and intermediate-value inspection, for example:

```text
Sum(Lines*/Amount) * {1 - [DiscountPercent] / 100}
```

### 8.2 Honest semantic scope

Free-standing expressions use **validation-style observation only**. There is no targetless “computation mode.”

True computation semantics require a target field and use the computation route.

The first expression specification includes:

```text
text
parse base group
simple evaluation location
```

Only expressions that produce the runtime’s numeric `Val.Num` are accepted. A non-numeric result is an explicit unsupported-result error.

This restriction is necessary because the current execution `Val.Txt` does not distinguish text, date, time, and date-time, and the interpreter has no general static expression-kind inference pass. Typed textual/temporal expression observation requires a later bounded type checker or a different `TEXTUAL` boundary; it is not guessed from caller input.

The first implementation may support a base group row plus aggregates underneath it. It must reject, during preparation, any expression whose required context involves a parallel outer join, an unrepresentable correlation frame, a rule-only construct, or another unsupported shape.

This is capability-bounded, not silently approximate.

### 8.3 Illustrative TypeScript use

```ts
const prepared = session.prepareNumberExpression({
  text: "Sum(Lines*/Amount) * {1 - [DiscountPercent] / 100}",
  baseGroup: "/Order",
}).orThrow();

const observation = session.evaluateNumberAt(
  prepared,
  orderSnapshot,
  {group: "/Order", repetitions: [1]},
);
```

`SimpleLocation` is deliberately not advertised as a universal `EvalContext`. It is accepted only when preparation proved that the expression’s context fits it.

### 8.4 Result values

The result value is `A12Number`. Its text is the evaluator’s canonical semantic representation, not a promise to retain the lexical scale of an input literal. For example, semantic evaluation may canonicalize `49.90` to `49.9`.

Return:

```text
NumberObservation = VALUE(A12Number) | NOT_EVALUATED
```

A non-numeric result, malformed conversion, unsupported context, parse failure, and external callback failure are structured API errors. They are not collapsed into `NOT_EVALUATED`.

Do not promise causal unknown tracing until the execution value actually carries that provenance.

## 9. A12 scalar values and optional utilities

### 9.1 Boundary types

If a scalar API is added, the primary TypeScript surface uses project-owned semantic wrappers:

```ts
const amount = A12Number.parse("19.955");
const rounded = A12Numbers.roundAccounting(amount, 2);
const canonical = rounded.canonicalText();

const date = A12Date.parseIso("2024-01-31");
const shifted = A12Dates.addMonths(date, A12Integer.of(1));
```

The string passed to `parse` is a serialization boundary. The operation itself receives an `A12Number`, not a JavaScript number.

The secondary Java façade exposes the same semantic value concepts from the common implementation; it does not substitute `double` or platform date objects.

Date-only `addMonths` does not invent a time-zone parameter. Date-time operations that depend on model time zone belong to a configured session or an explicitly time-zone-aware semantic API.

### 9.2 Scope

Scalar utilities operate only on supplied, already valid, filled values. They do not model:

- absent or present-empty fields;
- formal validity;
- field fillability or signedness;
- row-content gating;
- partial relevance;
- iteration;
- rule polarity;
- computation target/storage behavior.

Each scalar utility needs a concrete adopter and a model-wrapped differential proof. This is optional work, not part of the first release.

## 10. Document snapshot contract

### 10.1 Builder and freeze

```ts
const builder = session.newDocument();
builder.addEntity("/Order", [1]);
builder.addText("/Order/CustomerName", [1, 1], "Acme");
builder.addRaw("/Order/DiscountPercent", [1, 1], "not-a-number");
const snapshot = builder.freeze();
```

The fresh builder has add-only placement semantics. Entity instances are added explicitly with `addEntity`; adding a field requires its owning group/row ancestry to exist. The builder rejects duplicate placements, missing ancestry, unknown paths, wrong group/field shapes, coordinate lists with the wrong arity, non-positive coordinates, and use after freeze.

It deliberately retains malformed lexical field values so formal validation can report them. It also permits a positive repetition index beyond the model’s declared maximum so the evaluator can emit the kernel-equivalent over-repetition error; the declared maximum is a validation rule, not an input-parser limit.

Editing an existing snapshot uses a distinct `DocumentEditor`:

```ts
const editor = session.edit(snapshot);
editor.putRaw("/Order/DiscountPercent", [1, 1], "25.00");
editor.empty("/Order/CustomerName", [1, 1]);
editor.remove("/Order/ObsoleteNote", [1, 1]);
editor.addEntity("/Order/Lines", [1, 3]);
editor.removeEntity("/Order/Lines", [1, 2]);
const next = editor.freeze();
```

`putRaw` creates or replaces exactly one field placement, but only under existing ancestry. `empty` creates or replaces it as present-but-empty. `remove` makes a field absent. `addEntity` explicitly instantiates a group/row. `removeEntity` atomically removes that entity and its complete descendant subtree; refusing to cascade would leave an unrepresentable orphan tree. All operations are model-checked, and changing a child never silently invents absent ancestry. The editor never changes the source snapshot and preserves malformed lexical values and positive over-limit repetition coordinates for formal validation.

Construction call order is not semantic. On `freeze()`, the boundary builds every repeated group in ascending one-based coordinate order independently within each concrete parent coordinate tuple. Holes remain holes; normalization never compacts them. The evaluator receives that normalized representation, so no sort is added to the evaluation hot path. The JSON loader, fresh builder, snapshot editor, and `DocumentV2` adapter must all converge on this same ordering, locked against the kernel for order-sensitive constructs.

### 10.2 Versioned instance JSON

The public schema should formalize the existing nested shape:

```json
{
  "fields": {
    "Order": {
      "CustomerName": "Acme",
      "Lines": [
        { "Amount": "10.50" },
        { "Amount": null }
      ]
    }
  }
}
```

Required decisions:

- the stable API requires the top-level `{"fields": ...}` wrapper;
- the canonical writer always emits that wrapper;
- the current bare-root alias is not accepted by the stable loader; a separately named legacy-import helper may support it without weakening the canonical contract;
- a non-repeatable group is an object;
- a repeatable group is a dense array of row objects;
- a field is a string or `null`;
- a non-empty field string is the field’s stored lexical value; temporal values follow the model’s declared format/time zone rather than an unconditional ISO rule;
- `null` is present-but-empty;
- `""` is accepted as present-but-empty for compatibility, while the canonical writer emits `null`;
- an absent field key means no field instance;
- an absent group key means no group instance;
- `{}` means an instantiated empty non-repeatable group or row;
- `null` array rows and non-object rows are rejected;
- native JSON numbers and booleans are rejected rather than stringified;
- unknown paths and array/object shape mismatches are rejected against the model;
- a dense array has no sparse “hole” semantics; empty row objects are still instantiated rows;
- array position defines the one-based repetition coordinate, and the loader/writer use ascending numeric coordinate order within each parent row.

An internal snapshot may nevertheless contain a sparse repetition after explicit coordinate-based construction or `DocumentV2` conversion. For each concrete parent coordinate tuple independently, the canonical writer must refuse with a structured `SPARSE_REPETITION_NOT_REPRESENTABLE` error when an absent coordinate precedes a later present coordinate. It must not compact rows or synthesize `{}` placeholders, because either operation changes repetition identity or instantiated-row semantics. A later version may add a separate versioned placement-list format if portable sparse serialization becomes a requirement; it must not silently overload the dense JSON contract.

Expanded, self-contained model input is the hard cross-platform contract for the initial product. Under that contract, the missing JavaScript include expander is an input-coverage limitation, not an operator-semantic divergence.

The existing broad JSON schema is not by itself enough; model-aware runtime validation must enforce the actual field/group structure.

### 10.3 Partial-validation pointers

Partial validation uses a public model-checked pointer, not the interpreter’s numeric wildcard sentinel:

```text
RelevantPointer
  path: absolute model path
  coordinates: one coordinate per path segment

Coordinate = INDEX(positive integer) | ALL
```

`ALL` is accepted at any path position, matching `ALL_SEMANTIC` and the current `anyRep(path)` behavior. For a concrete coordinate, non-repeatable group and field-own positions require `INDEX(1)`, while repeatable group positions accept any positive index. `ALL` retains its semantic meaning as a wildcard; it is not expanded into currently present rows.

The TypeScript façade uses a tagged union such as `{kind: "index", value: 2}` or `{kind: "all"}` rather than exposing an internal magic integer. The common KMP contract owns the closed coordinate meaning; a secondary Java façade may project it as a sealed DTO.

The session validates path existence, coordinate arity, and whether wildcard/concrete coordinates are legal for each model segment before partial evaluation.

## 11. Scenarios and client examples

### 11.1 Native application values: inject, evaluate, extract

Use lexical field setters or A12 semantic wrappers at the boundary:

```ts
const snapshot = session
  .newDocument()
  .addEntity("/Order", [1])
  .addNumberText("/Order/DiscountPercent", [1, 1], "12.50")
  .freeze();

const result = session.evaluateRule(
  preparedRule,
  snapshot,
  "en_US",
);
```

Extraction returns versioned findings, computations, or an approved A12 semantic value such as `A12Number`; it never returns a JavaScript `number` or `Date` as the authoritative value.

### 11.2 Existing JSON documents

```ts
const session = EvaluationSession.open(expandedModelJson, options);
const snapshot = session.readInstanceJson(instanceJson);
const report = session.validateFull(snapshot, "en_US");
```

### 11.3 Dynamic document instances

A form application may build a new snapshot for each edit:

```ts
const next = session
  .edit(previousSnapshot)
  .putNumberText("/Order/Lines/Amount", [1, 2, 1], "25.00")
  .freeze();

const report = session.validatePartial(next, relevantPointers);
```

The API does not mutate `previousSnapshot`, and it does not reuse value-dependent caches across snapshots without a proven invalidation design.

### 11.4 Dynamic models

A TypeScript client initially receives a validated, expanded DM-JSON model from a build step or model service, then freezes it into a session:

```ts
const expandedModelJson = await modelProvider.getExpandedModel(modelId);
const session = EvaluationSession.open(expandedModelJson, options);
```

Changing the model creates a new session and invalidates all old prepared handles and snapshots. A live mutable model is not an evaluator input.

Fully local TypeScript construction of raw models with includes requires a common KMP model builder/expander and is the separate IG68 capability. Topic 1 must not smuggle a JVM dependency into the browser to obtain it. Until that capability ships, accepting an already-expanded model is the explicit TypeScript contract.

### 11.5 Existing stored computations

```ts
const values = session.computeField("/Order/Total", snapshot);
```

This uses the model’s stored computation, dependency closure, target formatting, and poison behavior.

### 11.6 Secondary JVM interoperability: rulekit-authored candidates

[Topic 2](RULEKIT-INTERPRETER-BRIDGE-PROPOSAL.md) supplies an optional JVM bridge. This is not the primary TypeScript authoring path and does not determine Topic 1’s public API:

```java
RulekitEvaluationProject project =
    RulekitEvaluationProject.open(validatedExpandedModelJson, options);
PreparedRule prepared =
    project.prepare(authoredRule).orThrow();

CandidateRuleEvaluation result =
    project.session().evaluate(prepared, snapshot, "en_US");
```

The bridge must prove that the rulekit model and evaluation model have the same provenance.

### 11.7 Batch/server reuse

One session and a bounded set of prepared handles may evaluate many immutable snapshots. Session, handle, and snapshot thread-safety must be tested before concurrent use is promised.

### 11.8 Custom project behavior

At session creation, TypeScript clients may supply the interpreter’s session-scoped configuration: the fixed `EvalClock`, predefined types, custom field types, custom conditions, and legal charset fallback. Locale remains an argument to each validation/evaluation call. Model time zone comes from the expanded `ModelDef`; it is not a client override. The secondary Java façade maps to the same common configuration.

The TypeScript delivery must define callback shape, lifetime, and exception/error conversion. Version one callbacks are synchronous because the evaluator is synchronous; Promise-returning/asynchronous callbacks are rejected. Current Kotlin fun interfaces are not automatically a usable `@JsExport` TypeScript SPI.

## 12. Candidate computations: separate feasibility increment

Do not implement `prepareCandidateComputation(text, mode)` as if it were expression preparation.

Before approval, define:

- whether the candidate inserts, shadows, or replaces a stored computation for the same target;
- how name and target collisions are handled;
- common guard and alternative ordering;
- dependency graph integration and cycle rejection;
- whether stored computations can feed the candidate and whether the candidate can feed stored computations;
- overlay/cascade visibility during one compute run;
- iteration at the write target;
- target kind, scale, format, date/time configuration, and formal storage checks;
- poison behavior;
- implicit validation rule behavior;
- preview versus apply-to-snapshot semantics;
- report-all versus delta result semantics.

The current computation engine is model-owned and built from stored computations. Extending it is engine orchestration work and needs its own red/green design, differential tests, and performance gates.

Until then, evaluate stored computations with `computeField`, or build and validate a new model containing the proposed computation and open a new session.

## 13. Optional Java-only appendix: `DocumentV2` adapter

### 13.1 Placement

This appendix is not part of the primary Topic 1 release, roadmap, or effort. The adapter belongs in an optional Java/JVM module that imports the licensed kernel. It must not enter the kernel-free interpreter artifact or the TypeScript implementation.

Publication requires an explicit EUPL/commercial licensing and distribution decision.

### 13.2 Required input

`DocumentV2` alone is insufficient: it carries a model identifier, not the complete interpreter model or its fingerprint. The adapter also requires the exact matching expanded session.

The adapter can check the document model ID against the session model ID, but that does not prove an exact model version/fingerprint match. Exact provenance remains a caller precondition or must be supplied through an external attestation.

### 13.3 Snapshot conversion

`DocumentV2` exposes typed values such as decimals and instants rather than necessarily preserving the original lexical input. The adapter therefore cannot promise raw-text preservation.

It must canonically convert typed values using the matching model’s kind, format, scale, and model-time-zone rules, while preserving:

- group/row order;
- one-based repetition coordinates;
- sparse repetition holes: iterate the source coordinate range and never compact later non-null rows when `get(i)` is absent;
- instantiated empty rows;
- absent versus present-empty fields;
- absent entity instances without inventing them.

Snapshots containing preserved sparse holes remain evaluable, but they are not writable through the dense canonical instance JSON format. The canonical writer must return `SPARSE_REPETITION_NOT_REPRESENTABLE`; it must never compact the source coordinates. Consumers that require a portable sparse round trip need the separately designed placement-list format, not an adapter-specific JSON exception.

Differential tests must cover at least number scale/rendering, `FieldValueAsString`, date/time/date-range formats, DST-sensitive date-time values, empty/absent state, nested repetitions, and index fields.

Snapshot conversion is the default. A lazy live `EvalContext` view is not a thin adapter; it would reproduce document lookup, iteration, formal state, correlation, and cache semantics.

## 14. Detailed TypeScript product contract

The current repository proves common code through Kotlin/JS Node tests. That is not a published TypeScript product. Topic 1 exists primarily to turn the KMP evaluator into that product.

Required delivery work:

- a JS-safe export façade;
- stable plain DTOs and arrays;
- generated and reviewed `.d.ts`;
- Node packaging and actual browser packaging when browser support is claimed;
- npm publication metadata;
- TypeScript consumer compile and runtime tests;
- JS-safe session options for the fixed clock and legal-charset/registry configuration;
- synchronous callback bridges for custom conditions and code-based custom/predefined types;
- callback lifetime and error behavior;
- no leakage of Kotlin collections, `ModelDef`, `Document`, `Dec`, `Val`, `Expr`, or `Cond`.

The npm/TypeScript surface is the release-defining contract. The Java/Maven projection is tested separately as a secondary consumer of the same KMP implementation.

Compatibility must be stated precisely:

- JVM is the real-kernel differential target;
- JavaScript reuses the common algorithm and portable conformance cases;
- current JavaScript multiplication is exact beyond the point where the kernel/JVM rounds to 50 significant digits, while division and exponentiation use `big.js` decimal-place precision rather than the kernel/JVM significant-digit policy;
- JavaScript regex uses the JavaScript dialect rather than Java regex;
- expanded models are required initially, and unresolved include shells are rejected at session creation;
- rare included-reference relativization shapes are outside this initial boundary because the product session does not expand models.

Before calling the TypeScript artifact “kernel-exact,” reproduce the kernel/JVM 50-significant-digit behavior for multiplication, division, and exponentiation and either implement kernel-compatible regex semantics or enforce a mechanically checked common subset. A merely documented difference is not exactness. If a bounded profile is chosen instead, every exclusion must be detected before evaluation and returned as unsupported; prose-only bounds are insufficient.

The cross-platform profile must reject `Europe/Berlin` models until historical behavior is implemented and differential-locked, unless a complete and performance-neutral 1996+ guard covers both parsed and operation-produced temporal values. Do not use an unresolved runtime-versus-codegen precedence contradiction as marketing language; settle that repository contract first.

### 14.1 Existing interpreter gaps versus Topic 1 work

Topic 1 must not misclassify existing interpreter defects as work introduced by the TypeScript API. The authoritative source is the [interpreter gap ledger](INTERPRETER-GAPS.md).

| Gap | Ownership | Relationship to Topic 1 |
|---|---|---|
| IG83 — time-zone domain/history | pre-existing interpreter correctness/capability gap | release dependency for unrestricted temporal parity; not Topic 1 implementation |
| IG89 — Kotlin/JS decimal context/rendering | pre-existing interpreter correctness gap | release dependency for exact TypeScript arithmetic; not Topic 1 implementation |
| IG90 — Java/JavaScript regex semantics | pre-existing interpreter correctness/performance gap | release dependency for exact TypeScript pattern behavior; not Topic 1 implementation |
| IG85 — standalone npm publication/ABI | interpreter delivery gap activated by a library consumer | directly closed by Topic 1 packaging |
| IG86→IF138 — browser deferred | interpreter delivery gap | re-opens if Topic 1 / a consumer promises browser support |
| IG68 — common model assembly | pre-existing interpreter capability gap | required only if TypeScript must accept raw models/includes rather than expanded input |
| IG91 — enforced prepared-model boundary | pre-existing preparation defect | Topic 1 must consume/enforce the resulting boundary, not create another one |

Gap closure may run in parallel, but cost attribution remains separate. Topic 1 owns the TypeScript API, KMP boundary, npm delivery, prepared programs, snapshots, callbacks, and consumer experience. It depends on interpreter semantics; it does not silently absorb their backlog.

## 15. Errors and unsupported behavior

Keep four classes distinct:

| Class | Example | Public treatment |
|---|---|---|
| Invalid input/program | syntax error, unknown path, wrong instance shape | structured preparation/input error |
| Consistency not certified | candidate has not passed MVK validation | `UNCERTIFIED` preview status, not a preparation failure |
| Unsupported external/configuration | missing custom condition/type callback | unsupported/configuration result or documented exception |
| Semantic non-evaluation | formally invalid operand leads to unknown | `SUPPRESSED` or `NOT_EVALUATED` semantic result |

The current parser/evaluator is not uniformly tolerant: some unknown shapes fail at parse time, some at evaluation, and whole-model validation catches unsupported rules differently from candidate evaluation. The façade must normalize this deliberately; it must not imply that current behavior already supplies one uniform envelope.

## 16. Correctness and compatibility gates

### 16.1 Prepared candidate rules

For ordinary certified candidates inside the declared capability/platform profile, complete located row outcomes from:

```text
prepare once + evaluate N times
```

must equal:

```text
candidate injected with ModelDef.withRule(...) + full validation
```

for every located finding, plus the relevant kernel message differential. Located suppressions are compared with the shared internal `rowOutcomes` seam because whole-model validation emits findings rather than a per-row suppression report.

The current public candidate API collapses repeated rows to one aggregate `RuleResult`; it is not an oracle for the new located lists. Separately assert that the new `overall` verdict equals the legacy `RuleResult` verdict wherever the legacy contract applies, and that an unprepared parse-per-call candidate produces the same aggregate.

Cover:

- flat, repeated, nested, starred, parallel, and correlated scopes;
- multiple row firings;
- formal suppression;
- partial relevance only if a prepared partial API is actually exposed;
- custom conditions;
- message interpolation and polarity;
- loud rejection of `RepetitionNotUnique` until the dedicated and ordinary evaluator paths are unified;
- unknown/external behavior.

### 16.2 Expression observation

For every accepted expression shape:

- model-wrapped expression behavior agrees with the current interpreter;
- only numeric runtime results are accepted;
- rejected context shapes fail loudly;
- canonical numeric output does not falsely promise lexical-scale preservation;
- JavaScript results agree with the JVM/kernel oracle inside the supported profile.

### 16.3 Public ABI

- TypeScript declaration compile tests and runtime consumer tests on every promised target;
- npm serialization and package-compatibility golden tests;
- serialization golden files for snapshots, values, errors, and results;
- explicit API versioning and deprecation policy.
- secondary Java source/binary consumer tests for the JVM projection, without making Java ABI the TypeScript design input.

### 16.4 Model validity

Kernel differential claims apply only to kernel-legal models/programs that are also inside the mechanically enforced capability/platform profile. Preparation equivalence is not a substitute for consistency validation, and kernel legality does not erase a documented interpreter/platform exclusion.

## 17. Performance, startup, and footprint

### 17.1 Required behavior

After preparation, a candidate must use the same execution AST shape, evaluator, document machinery, and runtime operator implementations as the current path.

That is a target and a testable invariant, not a claim that no internal method changes.

### 17.2 Do not freeze implementation details

Do not require that internal lookup, scope resolution, or cache implementation can never change. A correct snapshot or preparation seam may justify internal refactoring.

Freeze observable behavior and budgets:

- no repeated parsing after successful preparation;
- stored-model behavior unchanged;
- prepared and existing paths produce equal outcomes;
- no startup, warmup, throughput, allocation, scaling, or footprint regression outside approved bounds;
- caches have explicit owners and plateau;
- snapshots do not retain prior snapshots accidentally.

### 17.3 Measurements

Measure:

- npm import to first usable export;
- fresh session to first complete result;
- first and repeated numeric-expression observation;
- candidate preparation;
- first prepared evaluation;
- repeated prepared evaluation;
- current candidate evaluation as comparator;
- instance parsing and freezing;
- result conversion;
- Node and every promised browser target’s warm throughput;
- JavaScript allocation, retained-cache plateau, and bundle/dependency closure;
- JVM performance as the shared-common baseline, kernel oracle comparator, and regression guard;
- secondary Maven/JVM artifact closure if Java support ships.

The repository’s current NFR certification gaps mean comprehensive neutrality is not yet proven. Close or supplement them with executable SLA/non-inferiority gates. Noisy wall-clock measurements cannot prove mathematical identity, but they can enforce product budgets and catch complexity regressions.

### 17.4 Preparation cost

Preparation is not automatically cheap. Parsing, reference normalization, renderer/catalog initialization in [Topic 2](RULEKIT-INTERPRETER-BRIDGE-PROPOSAL.md), and boundary conversion all cost time. They are acceptable only because they are explicit, measured, and amortized over reuse.

## 18. Security

Version one evaluates trusted models, programs, callbacks, and instance data.

A remote or untrusted evaluator requires a separate design for parser depth, expression size, regex work, aggregate size, callback isolation, cancellation, timeouts, memory, and denial-of-service limits.

## 19. Licensing

The common interpreter and TypeScript artifact remain clean-room and kernel-free.

The common evaluator uses the real kernel only as a test oracle in existing permitted JVM test locations. It never calls the kernel to evaluate a rule or computation.

Two optional JVM integrations are kernel-linked at runtime and must remain outside the multiplatform evaluator:

1. The candidate-consistency issuer calls the kernel consistency/MVK path and issues an opaque local certificate or signed remote attestation. It does not call kernel evaluation.
2. The `DocumentV2` adapter calls kernel document APIs to read `DocumentV2`, then snapshots the data for the kernel-free evaluator. It does not delegate evaluation to the kernel.

Both integrations require explicit publication, deployment, and EUPL/commercial licensing rulings. Calling the consistency issuer a “service” does not erase that boundary: wherever it runs is a kernel-linked deployment. A remote TypeScript consumer receives only the signed attestation and kernel-free evaluation result types.

## 20. TypeScript-first delivery plan

### Phase 0: settle the TypeScript product and common contract

1. Resolve the repository’s runtime-versus-codegen precedence contradiction.
2. Define the validity precondition and model fingerprint.
3. Define JS-safe versioned instance, value, result, and error DTOs from TypeScript constraints.
4. Decide and name the supported Node/browser targets.
5. Define the enforced JavaScript compatibility profile.
6. Define session/handle/snapshot ownership and thread-safety promises.
7. Record IG83, IG89, and IG90 as parallel interpreter release dependencies rather than Topic 1 implementation.
8. Decide whether signed remote attestation is in the first TypeScript release; default to clearly labeled `UNCERTIFIED` local preview if not.

### Phase 1: Kotlin common boundary plus npm skeleton

1. Put the stable session, ownership, DTO, and document-boundary semantics in `commonMain` over one `DmInterpreter`.
2. Add the `jsMain` export adapter with no semantic logic.
3. Generate and review `.d.ts`.
4. Create the npm package and TypeScript consumer fixtures for every promised target.
5. Establish import, startup, bundle, allocation, and no-regression baselines before adding prepared features.

### Phase 2: TypeScript documents and existing capabilities

1. Add immutable snapshots, the add-only builder, and the copy-on-write editor with explicit entity-subtree removal.
2. Normalize repeated entities by coordinate at freeze and refuse sparse canonical serialization per parent scope.
3. Expose full/partial validation, stored rules, stored computations, and `computeField` through TypeScript DTOs.
4. Export every configuration/callback SPI through tested synchronous TypeScript interfaces.
5. Replay the public-boundary conformance suite on Node and every promised browser target.

### Phase 3: first-release prepared programs

1. Refactor preparation/evaluation to reuse the existing execution AST without changing traversal.
2. Add the bounded number-only expression observer and simple-location capability checker.
3. Add owner-bound prepared candidate rules with all located findings/suppressions.
4. Expose both through the npm/TypeScript API and prove no repeated parsing.
5. Keep local TypeScript results `UNCERTIFIED` unless verified remote attestation is configured.
6. Prove stored/candidate/expression behavior and performance remain inside the release gates.

The candidate-rule seam may land before the numeric observer in source control if that is the safest refactor. The first TypeScript product milestone still includes the numeric observer.

### Phase 4: secondary Java/JVM projection

1. Publish the same `commonMain` semantics through the KMP JVM target.
2. Add only the Java builders, collection views, and exception/DTO adapters justified by consumer tests.
3. Prove Java results are projections of the same common behavior.
4. Do not delay or reshape the TypeScript API for Java-only ergonomics.

### Phase 5: optional certification and Java-only integrations

1. Add signed-attestation verification for TypeScript if certified candidate evaluation is required.
2. Integrate an opaque in-process certificate only for secondary Java consumers that need it.
3. Evaluate scalar utilities, candidate computations, common include expansion, and `DocumentV2` import as independently approved increments.

## 21. Alternatives considered

### One method per catalog entry

Rejected. The catalog is not a uniform function list, and many constructs require model/rule/computation context.

### Universal `evaluate(operator, operands, context)`

Rejected. The context would grow into a second public AST/evaluation engine and would still misrepresent iteration, formal state, polarity, and computation semantics.

### Expose current `Expr`, `Cond`, `Val`, `EvalContext`, and `Document`

Rejected as the supported product contract. They contain execution artifacts, weak public typing, mutable construction behavior, Kotlin/JS-unfriendly forms, and no ABI commitment.

### Targetless `ValueSemantics.COMPUTATION`

Rejected. Computation semantics are target-owned.

### Candidate computation in version one

Rejected as underdesigned. It is a separate engine-orchestration feature.

### Separate Java and TypeScript operator implementations

Rejected. It duplicates runtime evaluation and guarantees drift.

### `DocumentV2` in common core

Rejected. It breaks multiplatform purity and the licensing boundary.

### Live mutable document evaluation

Rejected until a correct snapshot/invalidation/concurrency design exists.

## 22. Risks and decisions

| Risk | Consequence | Required response |
|---|---|---|
| Preparation mistaken for MVK validation | invalid programs receive false exactness claims | explicit valid-model precondition; optional trusted remote attestation |
| Free expression context too weak | wrong row/parallel behavior | bounded capability checker; reject unsupported shapes |
| Public DTOs freeze internals | future implementation becomes constrained | versioned boundary types and one-way conversion |
| JS customization adapters are omitted | customization parity is false | design/export/test every configuration and callback SPI |
| A JVM-shaped API degrades TypeScript ergonomics | the primary product exposes builders, collections, or types that do not fit JavaScript | design the canonical boundary from TypeScript constraints; add thin Java adapters later |
| Kotlin/JS export limits cause business logic in the façade | semantic duplication and platform drift | mapping-only `jsMain`; common ownership tests and architecture guard |
| Platform actuals diverge | JavaScript and JVM return different operator results | shared parity laws plus kernel/JVM oracle cases for every actual |
| Candidate computation is underestimated | incorrect cascade/poison/store behavior | separate feasibility proposal |
| `DocumentV2` typed conversion loses lexical detail | `FieldValueAsString` or date behavior changes | matching model required and differential conversion suite |
| Prepared API changes cold path/artifact | startup or footprint regression | executable startup/size/allocation gates |
| Model provenance is absent | rule silently runs against wrong model | stable fingerprint and owner token |
| Snapshot holes are serialized as dense arrays | repetition coordinates or instantiated-row state changes | refuse canonical serialization; design a separate sparse format only if required |
| Construction order leaks into row iteration | freeze/build and JSON round trips evaluate in different order | normalize ascending coordinates per concrete parent scope before evaluation |
| A portable attestation is caller-forgeable | invalid candidate is mislabeled `CERTIFIED` | signed trusted attestation over exact fingerprints; opaque certificates remain JVM-only |
| Kernel-linked issuer is mistaken for core purity | licensing/deployment boundary is obscured | separate artifact/service and explicit publication ruling |
| Known platform divergence is hidden behind “kernel-legal” | legal input receives a falsely exact result | close the gap or reject it through a mechanically enforced platform profile |

## 23. Independent review verdict

The TypeScript-first idea is feasible because the evaluator already lives primarily in Kotlin Multiplatform common code. The main feature effort is a production-quality TypeScript boundary and npm product, not another operator engine.

The defensible core is:

- make npm/TypeScript the primary product and acceptance surface;
- keep semantic policy and orchestration in Kotlin `commonMain`, with only law-governed primitive actuals in platform source sets;
- use mapping-only Kotlin/JS and Kotlin/JVM façades;
- reuse one configured `DmInterpreter`;
- expose stable snapshot/result boundaries;
- prepare candidate rules once;
- let rules/computations derive their natural contexts;
- ship a bounded validation-style numeric-expression observer in the first useful TypeScript release;
- use interpreter-owned A12 value wrappers rather than native numeric/date types;
- treat candidate computation, scalar helpers, and `DocumentV2` as separate decisions.

Java support is feasible as a secondary KMP/JVM projection and should be low-to-moderate incremental work relative to the TypeScript product. It is not a second implementation and must not drive the common API. Java-only certification and `DocumentV2` concerns remain optional.

The work is not “just packaging.” Prepared programs require parser/evaluation seam refactoring. Stable TypeScript DTOs, snapshot semantics, model provenance, JavaScript callbacks, npm/browser delivery, and the platform compatibility profile are substantive API design.

No new operator-evaluation implementation is required for the recommended core. New type inference, MVK validation, a universal public context, or candidate-computation orchestration would be new semantic machinery; this proposal deliberately does not pretend those capabilities already exist.

## 24. Acceptance criteria for the first approved release

- one public session delegates to one configured `DmInterpreter`;
- TypeScript/npm is the primary documented and tested product surface;
- all operator definitions/dispatch, parsing/preparation, traversal/context policy, snapshot/capability policy, and result meaning live once in Kotlin `commonMain`;
- platform primitive actuals obey one common contract and shared parity/differential laws;
- TypeScript and Java façade code contains boundary/ergonomic glue only and no operator-semantic decisions;
- the npm package has reviewed `.d.ts` plus compile/runtime consumer tests on every promised Node/browser target;
- expanded model and stable fingerprint are fixed for the session lifetime;
- public document snapshots are immutable during evaluation;
- freeze normalizes repeated entities by ascending coordinate independently within every concrete parent scope, regardless of construction call order;
- instance JSON has precise, tested absence/empty/group/row semantics;
- the stable JSON loader requires the canonical `fields` wrapper;
- the canonical JSON writer refuses sparse snapshots rather than compacting or inventing rows;
- malformed field text and over-limit positive repetitions reach formal validation rather than being rejected by the builder;
- fresh builders are add-only, while snapshot editors have explicit put/empty/remove semantics and never mutate the source snapshot;
- partial relevance uses model-checked tagged coordinates, including an explicit `ALL` wildcard;
- native floating-point and platform date objects are not semantic operands;
- stable, versioned TypeScript DTOs hide Kotlin/interpreter internals;
- existing full/partial validation and stored computation capabilities are reachable;
- bounded numeric expressions can be prepared once and evaluated repeatedly through the TypeScript API;
- candidate rules can be prepared once and evaluated repeatedly without reparsing;
- repeated candidates report every located finding or suppression;
- prepared handles and document snapshots cannot cross models/sessions;
- local TypeScript preparation/evaluation is explicitly `UNCERTIFIED`; `CERTIFIED` is available only when the optional verified signed-attestation extension ships;
- for kernel-legal inputs accepted by the mechanically enforced capability/platform profile, public outcomes match the existing interpreter and applicable kernel oracle;
- `Europe/Berlin` is differential-exact across the reachable temporal range or excluded as a model zone; a narrower 1996+ profile requires a complete guard over parsed and operation-produced values;
- TypeScript exposes every required customization through tested configuration and callback adapters;
- JavaScript multiplication, division, exponentiation, and regex behavior is either kernel-compatible or excluded by executable profile checks before evaluation;
- startup, warmup, throughput, allocation, scaling, and footprint stay within executable budgets;
- the kernel-free artifact remains kernel-free;
- Java/JVM consumers, when shipped, use the same KMP semantic implementation through a thin secondary projection;
- the optional JVM consistency issuer and `DocumentV2` adapter are explicitly non-core, kernel-linked, and separately licensed/deployed;
- no rulekit migration is required.
