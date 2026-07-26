# Study: is the A12 TypeScript API family worth building — and how much?

> **Decision-support with a selected first increment (2026-07-22; updated 2026-07-26).** This study weighs the three end-state visions — the [Operator API](OPERATOR-API-PROPOSAL.md), the [archived Interpreter API proposal](archived/INTERPRETER-API-PROPOSAL.md), and the [Zod integration](ZOD-INTEGRATION-PROPOSAL.md) — as one family, and asks the question the proposals themselves do not: *why would anyone reach for this, and which parts earn their place?* The owner selected the independently consumable interpreter workspace/core/browser slice first; that core is now implemented and ABI-locked in [INTERPRETER-API-SPEC](INTERPRETER-API-SPEC.md), while the direct Operator, Zod, and higher interpreter products remain design-stage.

## 1. The question

The three proposals are exhaustive *mechanism* — value families, ownership brands, codecs, service pools, issue bridges — and almost no *motivation*. A reader can see precisely how each surface is shaped and never learn why a TypeScript developer would want it, or which layers are cheap and proven versus a product bet waiting for a customer. This study supplies the missing motivation and a scope reading for each layer.

## 2. The one seam everything rests on

> **Native JavaScript values are not authoritative A12 operands.** `number` is a binary float that cannot hold an arbitrary exact decimal; `Date` is an instant, not a calendar date; JS string indexing, comparison, time-zone, and precision rules differ from the kernel's. The moment your app *computes* on a native value, it silently diverges from whatever A12 engine your backend trusts.

That single fact motivates the entire family, at three altitudes:

| Because native values aren't authoritative… | you need | which is the |
|---|---|---|
| a lone value computation (a date diff, a decimal round, a range overlap) must match the kernel | authentic A12 **values + operators**, no model | **Operator API** |
| a *document* is more than a shape — it has paths, repetitions, formal state, relevance, rules, computations | a model-bound **interpreter** over model-owned documents | **Interpreter API** |
| untrusted `unknown` input must cross into authentic A12 values with real error paths | a thin **boundary adapter** in the validation idiom you already use | **Zod adapters** |

One seam, three layers. The rest of this study takes each in turn, then reads scope across all three.

## 3. The family at a glance

```text
        unknown / wire input
                 │
     ┌───────────▼───────────┐   optional, in the Zod idiom
     │   @mbackschat/a12-operators-zod   │   @mbackschat/a12-interpreter-zod
     └───────────┬───────────┘
                 ▼
   @mbackschat/a12-operators  ──────►  @mbackschat/a12-interpreter
   (values + operators)     (model + document + validate/compute)
        one kernel-free Kotlin commonMain, projected TS-first (+ secondary JVM)
```

| Layer | Package | What it is | Grounded or new? | Consumer today |
|---|---|---|---|---|
| Operator core | `@mbackschat/a12-operators` | immutable A12 value family, exact codecs, direct operators | **grounded** — kernel-exact semantics already ship in `interpreter/src` (IF163/IF164/IF155); MVP is *extraction + projection* | no direct-operator consumer selected yet; interpreter publication is separate |
| Interpreter core | `@mbackschat/a12-interpreter` | prepare-once model → interpreter → model-owned document; validate/compute; custom SPIs | **grounded** — thin façade over the existing `DmInterpreter` plus the completed IF224 workspace/provider preparation boundary | GitHub Pages showcase selected 2026-07-26 |
| Interpreter layers | (same) | service registry + runtime pool, Worker coordinator, candidate programs, persistent form editor | **new** — the proposal itself calls these "new work" / a "distinct substantial capability" | none yet |
| Zod adapters | `@mbackschat/a12-operators-zod`, `@mbackschat/a12-interpreter-zod` | boundary codecs + error bridge; strict transport schema; report projection | **thin** — delegates all semantics | none yet |

The unifying thesis: **kernel-exact A12 semantics, clean-room and kernel-free, projected TypeScript-first from one Kotlin `commonMain`, MIT-licensed** — the same decoupling thrust as [KERNEL-DECOUPLING-PROPOSAL](KERNEL-DECOUPLING-PROPOSAL.md), aimed at the JS/TS ecosystem.

## 4. Operator API — the strongest, cheapest layer

### 4.1 Why it exists (the value bug, shown)

A checkout that sums line totals and computes a net-30 due date, with *no interpreter* — just wrap, operate, unwrap:

```ts
import { operators, A12Number, A12Integer, A12Date } from "@mbackschat/a12-operators";

const lineTotals: readonly A12Number[] =
  ["0.10", "0.20", "0.30"].map(A12Number.parse);

const grandTotal: A12Number = operators.number.sumAll(lineTotals);
//  exactly 0.60   ✅        (native reduce → 0.6000000000000001  ❌ IEEE-754 float)

const invoiceDate: A12Date = A12Date.parse("2008-01-31");
const dueDate: A12Date = operators.date.addMonths(invoiceDate, A12Integer.fromSafeInteger(1));
//  2008-02-29   ✅          (native Date.setMonth → 2008-03-02  ❌ Feb overflow)
```

Both native results are type-correct and wrong relative to the kernel. The usual "fix" — `decimal.js` plus a date library — just relocates the problem: now *you* own reproducing the kernel's `MathContext(50)`/HALF_UP rounding, its end-of-month clamp, and its DST rules, and keeping them in sync. The Operator API delegates to the kernel's own semantics, so there is nothing to reproduce.

### 4.2 Assessment

- **Feasibility is high and the risk is low** because this is *extraction, not engine work*. The semantics already exist and are kernel-exact and differential-locked (`Dec`, `ModelTimeZone`, `JavaPatternToJs` — IF163/IF164/IF155); the MVP promotes them to pure value functions and projects them. This is the single best "worth it" argument in the whole family.
- **The capability manifest + parity-gate discipline (proposal §8/§10.1) is a real strength** — every catalog entry has an explicit disposition and nothing can silently vanish from the TS surface. It makes scope *honest*.
- **One "smallest-viable" flag:** the support surface is large — `A12Result`/`A12Evaluation` combinators (`map`/`flatMap`/`zipWith`/`match`), bulk conversion adapters, `A12Option`. The value family + codecs + core operators are the must-have; the result-algebra *combinators* are ergonomic sugar that could ride a real consumer rather than ship on day one. Worth deciding deliberately, not by default.

## 5. Interpreter API — a cheap core wrapped in a product bet

### 5.1 Why it exists (a document is not a shape)

The Operator API stops where *model/document* semantics begin. A rule like "if `coverageType = comprehensive`, then `deductible` is required, per repeated `vehicles[*]` row" cannot be expressed as a value computation — it needs the model's paths, the repetition coordinates, formal field state (absent / present-empty / malformed), relevance, and the rule engine. That is exactly what `@mbackschat/a12-interpreter` owns: `PreparedModel` (prepare once) → `Interpreter` (config-bound, worker-confined) → model-owned `DocumentSnapshot` → `validateFull`/`validatePart`/`compute`.

### 5.2 Assessment

- **The core boundary is right and largely grounded.** Prepare-once → per-config interpreter → model-owned snapshot mirrors the existing `DmInterpreter` reuse model; `validateFull`/`validatePart`/`compute` already exist. The core is a thin, high-value façade — the same low-risk extraction bet as the Operator API.
- **The ownership model is a genuine correctness win.** Splitting `ModelVersionIdentity` (which version) from `ModelOwnerIdentity` (which prepared owner may consume this object), with `ModelOwnerMismatchError` on a cross-owner snapshot, makes a whole class of "wrong-model snapshot" and cross-tenant bugs *unrepresentable* rather than merely discouraged.
- **But most of the 4,230 lines are a product waiting for a customer.** The service registry + runtime pool, the Node Worker coordinator, candidate programs, symbolic expressions, and the persistent structural-sharing form editor are — by the proposal's own words — "new work" and a "distinct substantial capability." They describe a *validation-service product* that no one has yet asked for. The proposal is admirably honest about this (its §2 status table, its §12 core-vs-optional split), but a reader drowns before reaching that verdict.
- **The reading:** treat the interpreter as **two things** — a cheap, proven *core* (load → document → validate/compute → inspect + custom SPIs, i.e. MVP Track B) and an expensive, speculative *service/editor/candidate layer* that must be strictly consumer-gated. Build the first; design-but-defer the second.

## 6. Zod adapters — a thin boundary in a familiar idiom

This is the layer this study originally covered; it is now one chapter of the family.

### 6.1 The seam, restated for the boundary

Zod validates the *shape* of `unknown`; A12 owns *value semantics* past the boundary. They are layers, not competitors. `z.number()` hands you a float and `z.iso.date()` a string/`Date` — the §4 bug — so the adapter's job is to turn `unknown` straight into authentic A12 values without you leaving the Zod idiom.

### 6.2 The boundary, shown

```ts
import { operators, A12Number, A12Date } from "@mbackschat/a12-operators";
import { a12Zod, A12ZodCodec } from "@mbackschat/a12-operators-zod";
import * as z from "zod";

interface CartWire   { readonly lineTotals: readonly string[];   readonly invoiceDate: string; }
interface CartValues { readonly lineTotals: readonly A12Number[]; readonly invoiceDate: A12Date; }

const cartCodec: A12ZodCodec<CartWire, CartValues> = a12Zod.object({
  lineTotals: a12Zod.array(a12Zod.number.fromDecimalString),
  invoiceDate: a12Zod.date.fromIsoString,
});

const cart = cartCodec.parse(requestBody);        // same .parse you already use → authentic A12 values
const total = operators.number.sumAll(cart.lineTotals);   // exact, per §4
const wire  = z.encode(cartCodec, cart);          // canonical wire back out

// errors stay native:
const bad = cartCodec.safeParse({ lineTotals: ["0.10", "12.3x"], invoiceDate: "2008-01-31" });
bad.error.issues[0].path;       // ["lineTotals", 1]   ← correct indexed path
bad.error.issues[0].params.a12; // { source: "a12", errorCode: "A12_CONVERSION_ERROR", issue: {…} }
```

### 6.3 The argument, both directions

- **Zod shop → reach for A12:** you already trust Zod for shape; for *value* it hands you a float and an instant-`Date` that silently diverge from your A12 backend. The adapter is a drop-in `z.ZodType` that yields **client/server value parity for free**, and pre-empts "I'll just use decimal.js" — you'd then own re-matching the kernel's rules; the adapter delegates to them.
- **A12 shop → reach for Zod:** your `unknown` input can fall out of the *same* Zod pipeline your team already reads (`safeParse`, issue paths, OpenAPI), composed into a bigger request schema, with Standard-Schema interop — instead of a second A12-specific decode call.
- **When Zod adds nothing (honest limit):** purely server-side code building values from trusted DB rows never touches `unknown` — call `A12Codecs`/the interpreter's own decode directly; Zod there is pure overhead.

### 6.4 Scope reading

| Piece | Reading | Why |
|---|---|---|
| **`@mbackschat/a12-operators-zod`** (scalars + composition + error bridge) | **Strong — the whole convincing story** | delivers the §4/§6.2 payoff; no interpreter dependency; MVP-small |
| `@mbackschat/a12-interpreter-zod` §7 transport schema | thin, **consumer-gated** | largely redundant with the interpreter's own `unknown` boundary (proposal §7.4) — justified only to compose into a bigger Zod schema |
| §9 report/form projection | useful, **consumer-driven** | its `mapPointer` is app-owned by nature |
| §8 owner-bound snapshot adapter | **weakest** | the proposal itself flags it isn't a perf win and questions whether it saves enough code (§17.5) |

## 7. Scope reading across the family (recommendation — the call is open)

The family sorts cleanly into *build-now* and *design-but-defer* by one test: **is it extraction of proven kernel-exact semantics, or new engine/product work?**

| Verdict | Layers | Rationale |
|---|---|---|
| **Build now** (the [MVP](TS-API-MVP-PROPOSAL.md)) | `@mbackschat/a12-interpreter` core (workspace/provider + transport/ownership + validate/compute + custom SPIs) and browser qualification | the GitHub Pages showcase is the selected consumer; it activated the workspace/publication/browser slice, completed workspace preparation/browser qualification as IF224/IF225, and leaves IG85 active |
| **Design, defer to its selected follow-up** | JVM Kernel `IDocumentRtService` drop-in; interpreter service registry + pool, Worker coordinator, candidate programs, persistent form editor; `@mbackschat/a12-interpreter-zod` transport/report; wider zones and patterns | the drop-in has a concrete compatibility target but follows the portable core; the other layers still lack a consumer that determines their shape |
| **Drop from a first cut** | `@mbackschat/a12-interpreter-zod` §8 owner-bound snapshot adapter | the proposal's own open question; not a perf win |

**Status update, 2026-07-27:** the GitHub Pages showcase is the first external interpreter consumer. Its packed-package browser qualification completes the workspace/core and first-answer slice as IF224/IF225; public delivery remains under IG85. It does not justify the optional service registry, pooling, editor, candidate-program, Zod, or complete direct-operator products; those still wait for a consumer that determines their shape.

## 8. Provenance and caveats

- **Design-stage code.** The `@mbackschat/a12-operators` / `@mbackschat/a12-interpreter` / `-zod` surfaces do not exist yet; names follow the three proposals and the Zod 4 codec API. Snippets are illustrative, not executed. If this argument later needs to be airtight, the move is to build the MVP's operator slice and turn §4/§6.2 into executed snippets.
- **The A12 semantics are the locked part.** `AddMonths("31.01.2008", 1) = "29.02.2008"` (end-of-month clamp, never carries) is verified and locked by `DateArithmeticLawsTest` — see [KERNEL-SEMANTICS](KERNEL-SEMANTICS.md) / [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §6. Arbitrary-precision `MathContext(50)`/HALF_UP decimals are the interpreter's `Dec` semantics (IF163). Three-valued `A12Truth` is [OPERATOR-API-PROPOSAL](OPERATOR-API-PROPOSAL.md) §4.
- The native JS results (`0.6000000000000001`; `2008-01-31` + 1 month → `2008-03-02`) are stock IEEE-754 and `Date` overflow behavior.
- **The interpreter-first sequencing and release boundaries are decided.** The package name, strengthened provider contract, post-core placement of the JVM Kernel drop-in, and that adapter's EUPL-1.2 artifact policy are ratified in [INTERPRETER-RELEASE-PROPOSAL](INTERPRETER-RELEASE-PROPOSAL.md) §13.
