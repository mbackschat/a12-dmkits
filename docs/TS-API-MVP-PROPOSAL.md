# Proposal: Minimum first increment for the A12 TypeScript APIs (MVP)

## Status

Extracted from the three end-state vision proposals — [OPERATOR-API-PROPOSAL](OPERATOR-API-PROPOSAL.md), [INTERPRETER-API-PROPOSAL](INTERPRETER-API-PROPOSAL.md), [ZOD-INTEGRATION-PROPOSAL](ZOD-INTEGRATION-PROPOSAL.md). Those describe the full destination and drop nothing; this doc names the **smallest complete, publishable slice to build first**. It *sequences* the vision, it does not shrink it — every capability parked here still lives in the owning proposal.

The developer-library wave has started. [INTERPRETER-RELEASE-PROPOSAL](INTERPRETER-RELEASE-PROPOSAL.md) activates IG68, IG85, and IG86; its first delivery is Track B plus browser/package qualification. Tracks A and C remain independently valuable, but the complete direct Operator API is not a prerequisite for publishing the interpreter package.

The decision-support behind *why this family and how much of it* — the motivation, worked before/after, and per-layer scope reading that this slice sequences — is [TS-API-STUDY](TS-API-STUDY.md).

## 1. Why a slice, not the whole vision

The three proposals are deliberate wish-captures of the end state. Building all of it at once is neither necessary nor safe. The browser showcase is now the first external consumer and justifies the workspace, package, and browser layers; the service/pooling/editor layers still wait for a consumer that determines their shape.

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
| expanded model load | `load/ModelLoader.load` | stricter prepared-input fast path |
| raw workspace preparation | JVM-side `dm.core.ModelReader` | original commonMain assembler with include/typedef/id/remap parity (IG68) |

## 3. In scope — the minimum

**Track A — `@a12/operators` core** ([OPERATOR-API §4–§9](OPERATOR-API-PROPOSAL.md)). The core immutable value family (`A12String`/`A12Boolean`/`A12Confirm`/`A12Number`/`A12Integer`/`A12Date`/`A12Time`/`A12DateRange`/`A12DateTime`@UTC), the exact codecs + structured conversion errors + `unknown`-accepting guards, and the operator families whose semantics are already exact: construction/logic, dates, date-times@UTC, strings, numbers (incl. `× ÷ ^`), comparisons, date ranges, and collection reductions. The capability/parity matrix (§8) ships from day one so scope is visible.

**Track B — `@a12/interpreter` minimum foundation** ([INTERPRETER-API §12.1](INTERPRETER-API-PROPOSAL.md)). First the workspace/preparation foundation: bounded immutable `ModelWorkspace`, transitive include and typedef resolution, inline effective types, id normalization, reference-aware include mounts, structured preparation diagnostics, `PreparedModel`, and the explicit expanded-input fast path. Next the transport + ownership foundation: `PlacementDocumentInputV1`, `DocumentDecodeLimits`, structured `DocumentInputDiagnostic`/`Error`, `ModelOwnerIdentity`, `tryDecodePlacements`/typed `readPlacements`/`encodePlacements`, `isSnapshot`, and the model-owned `DocumentSnapshot`. Then the customization-bearing increment: `validateFull`, `validatePart`, `compute`, the Custom Field Type / Custom Condition SPIs, and the configuration support report.

**Track C — `@a12/operators-zod`** ([ZOD §16 steps 1–2](ZOD-INTEGRATION-PROPOSAL.md), optional). Scalar codecs backed by the Track-A codecs plus the conversion-error → Zod-issue bridge. It depends only on `@a12/operators` and Zod, so it can ship as soon as Track A is stable — it never waits on the interpreter.

The Track-B public release also qualifies a real `browser()` target and a packed-package showcase. Browser acquisition adapters fetch/preload workspace sources outside the synchronous common API.

## 4. Deferred to later increments (kept in the vision, not built now)

- `A12Pattern`, named zones beyond `{UTC, GMT, Europe/Berlin}`, and the `A12OperatorsWithExactArithmetic`/`A12OperatorsWithPatterns` extension types — graduate once their cross-platform TS projection laws pass (Operator §10.1; wider zones = IG83).
- The repeated-model **validation-service layer**: model registry, runtime pool, Node Worker coordinator, JVM concurrency (Interpreter §8).
- **Prepared candidate programs**, symbolic expressions, candidate computations (Interpreter §7).
- The **persistent structural-sharing form editor** (Interpreter §5.1, optional layer).
- `@a12/interpreter-zod` transport / snapshot / report adapters (Zod §7–§9) — ride Track B stabilizing.
- Advisory **model-derived Zod schemas** and build-time model-specific TS generation (Zod §10).
- A **Java/JVM** façade beyond what falls out of `commonMain` (secondary surface; never gates the TS-first primary).

## 5. Build order

1. Freeze the minimum exported value boundary Track B needs; do not require the full direct Operator product.
2. Implement commonMain workspace preparation and lock its JVM/Node/browser parity.
3. Implement the Track-B transport + ownership foundation.
4. Add `validateFull`/`validatePart`/`compute`, custom SPIs, and support reporting.
5. Pack and qualify the browser/Node TypeScript artifact and showcase.
6. Publish `@a12/operators` and `@a12/operators-zod` independently when Track A is selected.

Everything in §4 then follows, consumer-driven.

## 6. Exit

When this increment ships, its pieces are marked done in the three vision proposals (their contracts are the durable record), and the corresponding rows here drain. When empty, archive this doc. The interpreter release resolves IG68, IG85, and IG86 together.
