# Study: where Zod ends and A12 begins

> **Decision-support, not a decision (2026-07-22).** This study exists to answer one question honestly — *is the [Zod integration](ZOD-INTEGRATION-PROPOSAL.md) worth building, and if so how much of it?* — with a concrete before/after and arguments in both directions. The scope reading in §6 is a recommendation, not a settled build order; the scope call is still open. The API names below follow the [Operator API](OPERATOR-API-PROPOSAL.md) and [Zod integration](ZOD-INTEGRATION-PROPOSAL.md) proposals and are **design-stage — not yet runnable**. The A12 *semantics* they rely on are the parts that are real and locked (§7).

## 1. The question

The proposal documents the adapter *mechanism* — codecs, encode-safe brands, issue bridges, ownership guards — in full. What it never does is show the bug the adapter removes, so it reads as plumbing with no motivation. This study supplies the missing motivation and then weighs how much of the plumbing actually earns its place.

## 2. The seam (the one idea to hold)

> **Zod validates the *shape* of untrusted input. A12 owns the *semantics* of a value once it is past the boundary. They are layers, not competitors.**

Zod operates on JavaScript primitives — `string`, `number`, `boolean`, `Date`. That is exactly right for "is this `unknown` shaped the way I expect?". But the instant you *compute* on a Zod-parsed `number` or `Date`, you have left Zod's remit and are trusting JavaScript's value semantics — which do **not** match the A12 kernel your backend evaluates rules on:

| You parse with Zod, then compute… | JavaScript hands you | The A12 kernel computes |
|---|---|---|
| sum of `["0.10","0.20","0.30"]` | `0.6000000000000001` (IEEE-754 binary float) | exactly `0.60` (arbitrary precision, `MathContext(50)`, HALF_UP) |
| `2008-01-31` **+ 1 month** | `2008-03-02` (`Date` overflows a 31st into March) | `2008-02-29` (A12 end-of-month **clamp** — never carries into the next month) |
| a yes/no predicate | `boolean` — two states | `A12Truth` — true / false / **not-evaluated** (three states) |

None of these is exotic. They are money totals, due dates, and conditional logic — the everyday content of a business form. The divergence does not fail at the boundary; it surfaces later as a one-cent reconciliation mismatch or an off-by-one date, in production, far from where it was introduced.

## 3. What the current proposal is missing

The proposal states the payoff abstractly (§1: *"Zod is the ergonomics oracle; the A12 kernel is the semantic oracle"*) but never demonstrates it. A reader cannot feel the difference between "type-correct" and "semantically correct relative to the authoritative engine". §4 below is that demonstration, and it is what §1 of the proposal should lead with.

## 4. Worked study — a checkout endpoint

A service receives a cart, sums the line totals, computes a net-30-day due date, and echoes a canonical response.

### 4.1 Today, with Zod alone

```ts
import * as z from "zod";

const cartSchema = z.object({
  lineTotals: z.array(z.coerce.number()),  // ← binary floats
  invoiceDate: z.iso.date(),               // ← a string; you hand-roll the math
});

const cart = cartSchema.parse(requestBody);

const grandTotal = cart.lineTotals.reduce((a, b) => a + b, 0);
//  ["0.10","0.20","0.30"]  →  0.6000000000000001   ❌  disagrees with the server

const due = new Date(cart.invoiceDate);
due.setMonth(due.getMonth() + 1);
//  2008-01-31  →  2008-03-02   ❌  the server's rule engine says 2008-02-29
```

Both results are *shape-valid* and *type-correct* — Zod is perfectly happy. They are simply **wrong relative to the engine the rest of the system trusts**. The usual fix is to reach for `decimal.js` and a date library — but then you must *independently re-implement* the kernel's `MathContext(50)`/HALF_UP rounding, its end-of-month clamp, and its DST rules, and keep them in sync forever. That re-matching is the real cost, and it is invisible until it is wrong.

### 4.2 With `@a12/operators-zod`

```ts
import { operators, A12Integer } from "@a12/operators";
import type { A12Number, A12Date } from "@a12/operators";
import { a12Zod } from "@a12/operators-zod";
import type { A12ZodCodec } from "@a12/operators-zod";
import * as z from "zod";

interface CartWire   { readonly lineTotals: readonly string[];   readonly invoiceDate: string; }
interface CartValues { readonly lineTotals: readonly A12Number[]; readonly invoiceDate: A12Date; }

const cartCodec: A12ZodCodec<CartWire, CartValues> = a12Zod.object({
  lineTotals: a12Zod.array(a12Zod.number.fromDecimalString),
  invoiceDate: a12Zod.date.fromIsoString,
});

const cart = cartCodec.parse(requestBody);      // the same .parse you already use

const grandTotal: A12Number =
  operators.number.sumAll(cart.lineTotals);     // exactly 0.60   ✅  identical to the kernel

const due: A12Date =
  operators.date.addMonths(cart.invoiceDate, A12Integer.fromSafeInteger(1));
//  2008-01-31  →  2008-02-29   ✅  A12 end-of-month clamp, identical to the server rule engine

const response: CartWire =                      // canonical wire back out
  z.encode(cartCodec, { lineTotals: cart.lineTotals, invoiceDate: due });
```

There is nothing to re-match. The value you hold after `.parse` **is** the value the kernel evaluates rules on, so client/server parity stops being a discipline you enforce and becomes a type you hold.

### 4.3 Errors stay in the Zod world

```ts
const r = cartCodec.safeParse({ lineTotals: ["0.10", "0.20", "12.3x"], invoiceDate: "2008-01-31" });

r.success;                    // false
r.error.issues[0].path;       // ["lineTotals", 2]           ← correct indexed path
r.error.issues[0].message;    // human-readable presentation
r.error.issues[0].params.a12; // { source: "a12", family: "conversion",
                              //   errorCode: "A12_CONVERSION_ERROR", issue: {…typed A12 issue…} }
```

An A12 conversion failure surfaces as a native `ZodError`, at the right indexed path, with the structured A12 code preserved under `params.a12` for when you want it. No second error model, no message-string parsing. This is the ergonomic payoff the proposal names in §5.4 but never shows.

## 5. The argument, in both directions

### 5.1 For a developer who lives in Zod today → why reach for A12

- You already trust Zod for *shape*. For *value* it hands you a binary float and an instant-based `Date`; past the boundary those silently diverge from your A12 backend. The adapter closes the gap with a **drop-in `z.ZodType`** — it composes inside `z.object`/`z.array` and works with `.parse`/`.safeParse`/`z.encode` and issue paths exactly as you expect.
- **Client/server value parity for free.** The parsed value is the kernel's own semantic value, so totals and dates compute the same on both ends *by construction*.
- **It pre-empts the obvious objection** — "I'll just use `decimal.js` + a date lib." You can, but then you own the job of reproducing the kernel's rounding, end-of-month, and DST rules and keeping them in sync. The adapter delegates to the kernel's own semantics, so there is nothing to reproduce.

### 5.2 For a developer who lives in A12 today → why reach for Zod

- Your HTTP / queue / Worker input arrives as `unknown`. The interpreter already has a safe decode boundary — but if your app is *already* a Zod shop (request schemas, `safeParse`, form-error rendering, OpenAPI generation), the A12 values fall out of the *same* pipeline your team already reads, instead of a second A12-specific decode call sitting beside your Zod schemas.
- **Composition.** The A12 payload becomes one member of a larger `z.object` request (auth header, idempotency key, pagination, the A12 body) — one parse, one error tree, one set of issue paths.
- **Interop.** Zod implements [Standard Schema](https://standardschema.dev/schema), so the codecs plug into any Standard-Schema-aware router or framework without leaking A12-specific types at that seam.

### 5.3 When Zod adds nothing (the honest limit)

Purely server-side code that builds A12 values from trusted database rows never touches `unknown` — it should call `A12Codecs` (or the interpreter's own decode) directly, and Zod there is pure overhead. The adapter is boundary sugar, not a semantic requirement. Saying this plainly is what makes the rest of the pitch credible.

## 6. Scope reading (recommendation — the call is still open)

The strong, cheap value lives entirely in `@a12/operators-zod`. `@a12/interpreter-zod` is real but thin, and one of its three parts is hard to justify.

| Package / piece | Reading | Why |
|---|---|---|
| **`@a12/operators-zod`** — scalar codecs + object/array composition + error bridge | **Strong — the whole convincing story** | Delivers the §4 payoff; no interpreter dependency; already sized small–medium in the MVP. This is where the value is. |
| `@a12/interpreter-zod` §7 — strict transport DTO schema | Thin, **consumer-gated** | Largely **redundant** with the interpreter's own `unknown` decode boundary — which the proposal itself concedes ([§7.4](ZOD-INTEGRATION-PROPOSAL.md): *"Zod is an optional alternative… not the only way to process untrusted input"*). Justified only to compose into a bigger Zod request schema or for Standard-Schema interop. |
| §9 — lossless report / form projection | Useful, **consumer-driven** | Genuinely helps form-library integration, but its `mapPointer` is application-owned, so it is consumer-driven by nature — build it when a real form integration asks. |
| §8 — owner-bound snapshot adapter | **Weakest** | The proposal itself flags it is *not* a performance win and openly questions whether it saves enough consumer code to ship ([§17.5](ZOD-INTEGRATION-PROPOSAL.md)). A candidate to defer out of a first cut. |

The through-line: **operators-first is the low-risk, high-value start**, and it is exactly what the [MVP proposal](TS-API-MVP-PROPOSAL.md)'s Track C already carves out. Whether `@a12/interpreter-zod` follows in full, in part, or not at all is the open decision this study is meant to inform — it is not decided here.

## 7. Provenance and caveats

- **Design-stage code.** The `@a12/operators` / `@a12/operators-zod` surface shown here does not exist yet; names follow the [Operator API](OPERATOR-API-PROPOSAL.md) and [Zod integration](ZOD-INTEGRATION-PROPOSAL.md) proposals and the Zod 4 codec API. The snippets are illustrative, not executed.
- **The A12 semantics are the locked part.** `AddMonths("31.01.2008", 1) = "29.02.2008"` (end-of-month clamp, never carries) is verified and locked by `DateArithmeticLawsTest` — see [KERNEL-SEMANTICS](KERNEL-SEMANTICS.md) and [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §6. The arbitrary-precision `MathContext(50)` / HALF_UP decimal behavior is the interpreter's `Dec` semantics (IF163). Three-valued `A12Truth` is defined in [OPERATOR-API-PROPOSAL](OPERATOR-API-PROPOSAL.md) §4.
- The JavaScript results (`0.6000000000000001`; `2008-01-31` + 1 month → `2008-03-02`) are stock IEEE-754 and `Date` overflow behavior.
