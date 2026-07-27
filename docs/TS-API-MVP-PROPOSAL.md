# Proposal: Minimum first increment for the A12 TypeScript APIs (MVP)

## Status

Extracted from the three end-state vision proposals — [OPERATOR-API-PROPOSAL](OPERATOR-API-PROPOSAL.md), the [archived INTERPRETER-API-PROPOSAL](archived/INTERPRETER-API-PROPOSAL.md), and [ZOD-INTEGRATION-PROPOSAL](ZOD-INTEGRATION-PROPOSAL.md). This doc names the **smallest complete, publishable slice to build first**. The implemented interpreter contract has since graduated into [INTERPRETER-API-SPEC](INTERPRETER-API-SPEC.md), and every unbuilt interpreter capability is retained with a re-open trigger in [INTERPRETER-API-GAPS](INTERPRETER-API-GAPS.md).

The developer-library wave has started. [INTERPRETER-RELEASE-PROPOSAL](INTERPRETER-RELEASE-PROPOSAL.md) activated IG68, IG85, and IG86; portable workspace preparation and browser first-answer qualification have since completed as IF224/IF225, while public delivery remains under IG85. Its first delivery is Track B plus browser/package qualification. Owner decision 2026-07-26: Track A is a separate artifact that comes much later, and Track C follows its owning core only if selected.

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
| prepare-once boundary | public `PreparedModel` | implemented and ABI-locked |
| full/partial validation, computations | `DmInterpreter.validateFull` / `validatePart` / `compute` | thin public façade over kernel-free types |
| customization | `CustomCondition`, `CustomFieldType`, `LegalCharset` SPIs | kernel-free public registries + factories |
| expanded model load | `load/ModelLoader.load` | stricter prepared-input fast path |
| raw workspace preparation | portable `ModelWorkspace` plus JVM-side parity oracle `dm.core.ModelReader` | implemented with include/typedef/id/remap parity across JVM, Node, Chrome, and dmtool (IF224) |

## 3. In scope — the selected interpreter increment

**Track B — `@mbackschat/a12-interpreter` minimum foundation** ([INTERPRETER-API-SPEC](INTERPRETER-API-SPEC.md)). First the workspace/preparation foundation: snapshot-scoped `ModelSourceProvider`, platform-projected bounded `ModelWorkspace.fromSources`/`collect`, integrity-verified immutable closure capture, cancellation and typed acquisition failures, transitive include and typedef resolution, inline effective types, id normalization, reference-aware include mounts, structured preparation diagnostics, `PreparedModel`, and the explicit expanded-input fast path. Next the transport + ownership foundation: `DocumentInputV1`, `DocumentDecodeLimits`, structured `DocumentInputDiagnostic`/`Error`, `ModelOwnerIdentity`, `tryDecodeDocument`/typed `readDocument`/`encodeDocument`, `isSnapshot`, and the model-owned `DocumentSnapshot`. Then the customization-bearing increment: `validateFull`, `validatePart`, `compute`, the Custom Field Type / Custom Condition SPIs, and the configuration support report.

The Track-B public release also qualifies a real `browser()` target and a packed-package showcase. A host adapter performs bounded asynchronous fetch/storage access through `ModelSourceProvider`; collection pins and verifies the discovered closure before the synchronous common preparation/evaluation pipeline. The showcase adds Cache Storage for immutable offline reload, while editable persistence remains a later concrete IndexedDB adapter.

## 4. Deferred to later increments (kept in the vision, not built now)

- `A12Pattern`, named zones beyond `{UTC, GMT, Europe/Berlin}`, and the `A12OperatorsWithExactArithmetic`/`A12OperatorsWithPatterns` extension types — graduate once their cross-platform TS projection laws pass (Operator §10.1; wider zones = IG83).
- The separate **`@mbackschat/a12-operators`** product ([OPERATOR-API §4–§9](OPERATOR-API-PROPOSAL.md)): immutable values, exact codecs, direct operators, and its capability matrix. It comes much later and is not an interpreter dependency.
- **`@mbackschat/a12-operators-zod`** and other Zod adapters ([ZOD](ZOD-INTEGRATION-PROPOSAL.md)) follow only after their owning core package is selected.
- The repeated-model **validation-service layer**: model registry, runtime pool, Node Worker coordinator, and JVM concurrency ([INTERPRETER-API-GAPS IAG5](INTERPRETER-API-GAPS.md#iag5--registered-services-pools-version-switching-and-worker-coordination)).
- **Prepared candidate programs**, symbolic expressions, and candidate computations ([INTERPRETER-API-GAPS IAG4](INTERPRETER-API-GAPS.md#iag4--stored-targeted-and-candidate-program-apis)).
- The **persistent structural-sharing form editor** ([INTERPRETER-API-GAPS IAG3](INTERPRETER-API-GAPS.md#iag3--richer-document-values-storage-inspection-and-persistent-editing)).
- `@mbackschat/a12-interpreter-zod` transport / snapshot / report adapters (Zod §7–§9) — ride Track B stabilizing.
- Advisory **model-derived Zod schemas** and build-time model-specific TS generation (Zod §10).
- A **Java/JVM** façade beyond what falls out of `commonMain` (secondary surface; never gates the TS-first primary).

## 5. Build order

1. Freeze the minimum interpreter-owned exported value boundary; do not require the full direct Operator product.
2. Implement async provider collection plus commonMain workspace preparation and lock JVM/Node/browser parity.
3. Implement the Track-B transport + ownership foundation.
4. Add `validateFull`/`validatePart`/`compute`, custom SPIs, and support reporting.
5. Pack and qualify the browser/Node TypeScript artifact and showcase.

Everything in §4 then follows, consumer-driven.

## 6. Exit

When this increment ships, its pieces are marked done in the interpreter vision proposal (its contract is the durable record), and the corresponding rows here drain. Then archive this sequencing doc; the later Operator and Zod products remain in their own proposals. The interpreter release carries completed IF224/IF225 and resolves IG85.
