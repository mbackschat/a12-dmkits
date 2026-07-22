# Proposal: Minimum first increment for the A12 TypeScript APIs (MVP)

## Status

Extracted from the three end-state vision proposals — [OPERATOR-API-PROPOSAL](OPERATOR-API-PROPOSAL.md), [INTERPRETER-API-PROPOSAL](INTERPRETER-API-PROPOSAL.md), [ZOD-INTEGRATION-PROPOSAL](ZOD-INTEGRATION-PROPOSAL.md). Those describe the full destination and drop nothing; this doc names the **smallest complete, publishable slice to build first**. It *sequences* the vision, it does not shrink it — every capability parked here still lives in the owning proposal.

The increment is gated on **IG85**'s re-open trigger (the developer-library publishing wave starts, or a non-repo consumer needs a released artifact). Until then it is the agreed starting shape, not active work.

The decision-support behind *why this family and how much of it* — the motivation, worked before/after, and per-layer scope reading that this slice sequences — is [TS-API-STUDY](TS-API-STUDY.md).

## 1. Why a slice, not the whole vision

The three proposals are deliberate wish-captures of the end state. Building all of it at once is neither necessary nor safe: there is **no external consumer yet** (publication is deferred — IG85), and a small foundation already unblocks real integration while the service/pooling/editor/browser layers wait for a consumer that justifies them. This increment is the base every later layer sits on, so it is the right first build.

## 2. The head-start (grounded in `interpreter/src`)

The MVP is mostly **extraction + projection of proven semantics**, not new engine work. What the exported surface needs already exists and is kernel-exact:

| Capability the API projects | In source today | MVP work |
|---|---|---|
| decimal incl. `× ÷ ^` at `MathContext(50)` HALF_UP, plain rendering | `Dec.{kt,jvm.kt,js.kt}` (IF163) | extract to pure value fns (§9) + public TS wrapper |
| ES2018 regex dialect + distinct-pattern registry | `regex/JavaPatternToJs`, `PatternRegistry` (IF164) | projection-gate per Operator §10.1 |
| timezone `{UTC, GMT, Europe/Berlin}`, Berlin full history | `model/ModelTimeZone` (IF155) | project UTC first, adopt the set as laws pass |
| prepare-once boundary | `PreparedModel` (currently `internal`) | promote to the public prepare-once type |
| full/partial validation, computations | `DmInterpreter.validateFull` / `validatePart` / `compute` | thin public façade over kernel-free types |
| customization | `CustomCondition`, `CustomFieldType`, `LegalCharset` SPIs | kernel-free public registries + factories |
| model load | `load/ModelLoader.load` | stricter prepared-input guard |

## 3. In scope — the minimum

**Track A — `@a12/operators` core** ([OPERATOR-API §4–§9](OPERATOR-API-PROPOSAL.md)). The core immutable value family (`A12String`/`A12Boolean`/`A12Confirm`/`A12Number`/`A12Integer`/`A12Date`/`A12Time`/`A12DateRange`/`A12DateTime`@UTC), the exact codecs + structured conversion errors + `unknown`-accepting guards, and the operator families whose semantics are already exact: construction/logic, dates, date-times@UTC, strings, numbers (incl. `× ÷ ^`), comparisons, date ranges, and collection reductions. The capability/parity matrix (§8) ships from day one so scope is visible.

**Track B — `@a12/interpreter` minimum foundation** ([INTERPRETER-API §12.1](INTERPRETER-API-PROPOSAL.md)). First the dependency-free transport + ownership foundation: `PlacementDocumentInputV1`, `DocumentDecodeLimits`, structured `DocumentInputDiagnostic`/`Error`, `PreparedModel` + `ModelOwnerIdentity`, `tryDecodePlacements`/typed `readPlacements`/`encodePlacements`, `isSnapshot`, and the model-owned `DocumentSnapshot`. Then the customization-bearing increment: `validateFull`, `validatePart`, `compute`, the Custom Field Type / Custom Condition SPIs, and the configuration support report.

**Track C — `@a12/operators-zod`** ([ZOD §16 steps 1–2](ZOD-INTEGRATION-PROPOSAL.md), optional). Scalar codecs backed by the Track-A codecs plus the conversion-error → Zod-issue bridge. It depends only on `@a12/operators` and Zod, so it can ship as soon as Track A is stable — it never waits on the interpreter.

## 4. Deferred to later increments (kept in the vision, not built now)

- `A12Pattern`, named zones beyond `{UTC, GMT, Europe/Berlin}`, and the `A12OperatorsWithExactArithmetic`/`A12OperatorsWithPatterns` extension types — graduate once their cross-platform TS projection laws pass (Operator §10.1; wider zones = IG83).
- The repeated-model **validation-service layer**: model registry, runtime pool, Node Worker coordinator, JVM concurrency (Interpreter §8).
- **Prepared candidate programs**, symbolic expressions, candidate computations (Interpreter §7).
- The **persistent structural-sharing form editor** (Interpreter §5.1, optional layer).
- `@a12/interpreter-zod` transport / snapshot / report adapters (Zod §7–§9) — ride Track B stabilizing.
- Advisory **model-derived Zod schemas** and build-time model-specific TS generation (Zod §10).
- **Browser** target (IG86 — design browser-ready, do not build).
- A **Java/JVM** façade beyond what falls out of `commonMain` (secondary surface; never gates the TS-first primary).

## 5. Build order

1. Extract the shared value-level semantics out of AST evaluation into pure functions (Operator §9 precondition — the one real engine refactor).
2. Publish `@a12/operators` core + the capability matrix.
3. `@a12/operators-zod` scalar codecs (optional, parallel to 4).
4. `@a12/interpreter` minimum transport + ownership foundation.
5. `validateFull`/`validatePart`/`compute` + custom SPIs + support report.

Everything in §4 then follows, consumer-driven.

## 6. Exit

When this increment ships, its pieces are marked done in the three vision proposals (their contracts are the durable record), and the corresponding rows here drain. When empty, archive this doc. Coordinate with IG85 (publication wiring + KMP ABI lock) and IG86 (browser stays deferred).
