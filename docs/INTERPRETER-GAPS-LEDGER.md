# interpreter — gaps & open-issues ledger

> **What this is** — the interpreter's peer of [`DEV-API-GAPS-LEDGER.md`](DEV-API-GAPS-LEDGER.md) and [`FEATURE-GAPS-LEDGER.md`](FEATURE-GAPS-LEDGER.md): the single tracking surface for **open `:interpreter` gaps, deferred items, and to-fix issues**. It exists so open work is easy to **track, report, and pick up** — and so [`INTERPRETER-FINDINGS.md`](INTERPRETER-FINDINGS.md) can be what a findings doc should be: **settled, verified knowhow** you read to warm up and not re-investigate (like `KERNEL-FINDINGS.md`), never a backlog of dispositions ("open / to fix / deferred"). Per the CLAUDE.md *"rationale is timeless; a disposition is status"* rule — the findings hold the timeless fact, this ledger holds the live status.
>
> **The lifecycle.** A gap is born here with an `IG<n>` code under the section matching its status (§1 open / §2 deferred / §3 out-of-scope). When it ships, three things happen **in the fix's own commit**: (1) its **rationale graduates to a numbered `IF<n>` finding** in [`INTERPRETER-FINDINGS.md`](INTERPRETER-FINDINGS.md) — that finding notes it "graduated from IGn"; (2) any code/test comment citing `IGn` is **repointed to `IFn`**; (3) the gap's row is **deleted from this ledger**. **There is no "fixed/shipped" section — we don't record fixed gaps.** A fixed gap is no longer a gap; **git is the history of *when/what* was fixed, and the `IFn` finding is the *why*** — a hand-kept "shipped" list would only duplicate both and rot. So this ledger only ever lists **live, unfinished work** (the already-shipped IF20-per-cell / IF21 / IF23 / IF24 / IF25 live as findings, not here). A **deferred** gap stays in §2 with its re-open trigger; the technical detail of each gap lives in its cited `IF<n>` finding (the knowhow), this ledger holds only *what's left to do and why*.
>
> **Section invariant (the placement rule):** every section holds exactly one **live** status — **§1 = open, §2 = deferred, §3 = out-of-scope**. A FIXED/graduated entry belongs in **none** of them: shipping a fix *deletes* its row (see the lifecycle). So §1 reads "(none)" whenever nothing is open, and a FIXED entry **anywhere** in this ledger is a bug in *this doc* — git already records the fix.
>
> **Scope.** `:interpreter` only — the kernel-free Kotlin evaluator (parser, evaluation semantics, model loader, the differential/perf harnesses' interpreter side). dmkit (rulekit/modelkit/edit) authoring gaps go to [`DEV-API-GAPS-LEDGER.md`](DEV-API-GAPS-LEDGER.md); kernel-coverage classification stays in the MVK / RT-semantics / grammar ledgers.

---

## 1. Open (to fix)

A systematic **7-surface gap audit (2026-06-26, kernel-source-verified)** found the interpreter **materially short of full kernel parity beyond `validateFull`** — across the parser, `compute()`, the model loader, date construction, and partial validation (method: enumerate every surface where a divergence can hide — disposition prose, `unsupported`/throw census, entry-point × parameter dimensions, construct × entry-point matrix, loader-property diff, kernel ledgers cross-checked, differential-coverage holes — then verify each candidate against the kernel source). The bulk of the audit's confirmed divergences have since **shipped** (each graduated to a numbered `IF` finding + a differential lock and was **deleted** from here — git is the record). What remains below is the **residual/narrowed** set: one partial-validation probe (the `uniqueIndex`/subset case the aggregate-cell fix didn't cover) and one narrowed compute residual (IG13). Each row is a kernel-source-verified divergence; rows tagged *(probe)* need a runtime differential to pin the exact kernel behavior before the fix. (Closed 2026-06-26: IG11 global-field auto-relevance → IF37; IG10 CustomCondition relevant-set propagation → IF38; IG19's DATE_TIME residual as **parity by construction** → IF33.)

### Partial validation (the relevant-set dimension)
| code | item | status / action | detail |
|---|---|---|---|
| | partial: `uniqueIndex`/`RepetitionNotUnique` under subset-relevant rows *(probe — sibling of IG9)* | open — interpreter scans both rows; kernel may require the duplicate *partner* row relevant to flag the collision. Probe `firedCodesPart` with one row relevant. | `DmInterpreter.kt:255-356` |

### compute()
| code | item | status / action | detail |
|---|---|---|---|
| **IG13** | `compute()` **cascade-skip on an invalid dependency** *(residual)* | open (narrowed 2026-06-26) — the out-of-range ERRORED bucket shipped (IF34: a formally-invalid computed value → ERRORED, value returned); empty-operand→0 is parity. What remains: the kernel SKIPS a computation that depends (directly/indirectly) on an errored computed field, while the interpreter still overlays the errored value — so a *dependent* computation diverges. | `DmInterpreter.kt` compute overlay (line ~444) |

**The lock to build:** a **partial-validation FUZZ differential** (today only a handful of curated partial cases, no fuzz) — it would turn the remaining partial items (IG10/IG11 + the `uniqueIndex`-subset probe) into hard differential failures instead of reasoned claims.

Deferred items are in §2; a fixed gap is deleted from here (git + its `IFn` finding are the record).

---

## 2. Deferred (recorded, with a re-open trigger; detail in [`INTERPRETER-FINDINGS.md`](INTERPRETER-FINDINGS.md))

| code | item | status / re-open trigger | detail in |
|---|---|---|---|
| **IG2** | `uniqueValue` per-field constraint | deferred — probe-confirmed **not DM-JSON-deserializable** (`Field.unique`/`StringType.unique` don't load; `IField` has no `unique` accessor); it rides the USB/declared-unique machinery, not a field property. No O(N²) risk outstanding (the one risk is unauthorable). Re-open if a real model authors it. | IF20 |
| **IG4** | `Dec` number literal compares by **identity** (no value `equals`/`hashCode`) | deferred — whole-AST equality is unreliable wherever a number literal appears (tests use field-comparison forms to avoid it). A cross-platform + BigDecimal scale-sensitivity decision; a dedicated follow-up. | IF24 |
| **IG5** | runtime-semantics extraction (generator-readiness) | tracked refactor, **not** a prerequisite — migrate the comparison/3VL/aggregate logic still inside `Interpreter` into the pure runtime layer **as touched**; `DateMath`/`Kleene`/`quantifierFires`/the op registries already moved. | IF10 |
| **IG6** | formal-error message **text** = interpreter's own wording (Option A) | deferred — the byte-identical-kernel-text path (Option B) is not built (German-only kernel text, IF3 license boundary); the *code*/pointer/severity already match the kernel. Re-open only if a consumer needs verbatim kernel message text. | IF (formal errors) / KERNEL-INTEGRATION-NOTES |

---

## 3. Out of scope (won't build unless the trigger flips; recorded so it's not re-investigated)

- **string charset** (`zeichenNichtImZeichensatz`) — previously recorded as "not DM-JSON-declarable (no serialized key)"; **this reasoning is now SUSPECT** — the sibling `maxNumberOfDigitsBeforeDecimalPlace` claim was found WRONG by the 2026-06-26 audit (it *does* serialize; shipped as IF30), so the "no serialized key" basis must be re-verified against `FieldtypeSerializer`/`StringTypeMixin` before trusting it. Until re-verified, treat as **needs-recheck**, not confirmed out-of-scope. Detail: IF20/IF22.
- **`mandatoryGroup`** (`FEHLER_PFLICHTKONTEXT`) — **source-confirmed unreachable in 30.8.1**: the code is defined but **never emitted**, and **no DM-JSON property declares a group mandatory**, so it can't fire on any loadable model (parity by construction). Was the deferred IG3; re-check on a kernel upgrade. Detail: KERNEL-FINDINGS "mandatoryGroup is a DEAD code in 30.8.1".
- **custom-ref error-entity attribution** (`IRule.getCustomRefErrorEntitiesIfFilled/IfNotFilled`) — **source-confirmed unwired in 30.8.1**: the md-model rule mapping returns empty pending A12K-2281/2361, so the kernel always uses its default error-entity attribution (which the interpreter matches; `CustomConditionDiffTest` locks the CustomCondition error field). Was the deferred IG8; re-check on a kernel upgrade. Detail: KERNEL-FINDINGS "getCustomRefErrorEntities… is UNWIRED in 30.8.1".
- **`getRoundingMode`** on a computation — inert in the runtime (rounding is explicit via the round operators). Detail: IF22.
