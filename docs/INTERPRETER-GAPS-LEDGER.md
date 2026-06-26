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

A systematic **7-surface gap audit (2026-06-26, kernel-source-verified)** found the interpreter **materially short of full kernel parity beyond `validateFull`** — across the parser, `compute()`, the model loader, date construction, and partial validation (method: enumerate every surface where a divergence can hide — disposition prose, `unsupported`/throw census, entry-point × parameter dimensions, construct × entry-point matrix, loader-property diff, kernel ledgers cross-checked, differential-coverage holes — then verify each candidate against the kernel source). The bulk of the audit's confirmed divergences have since **shipped** (each graduated to a numbered `IF` finding + a differential lock and was **deleted** from here — git is the record). **§1 is now empty: every audit-confirmed divergence across `validateFull` / `validatePart` / `compute` is closed.** partial validation matches the kernel (rule-gating + 3VL, subset aggregates IF35, per-operator starred-aggregate relevance IF42, global fields IF37, custom-condition relevant-set IF38, subset uniqueness IF39), and `compute()` is at parity (cascade, ERRORED bucket, errored-dependency skip). (Closed 2026-06-26: IG10→IF38, IG11→IF37, IG13→IF40, IG19→IF33 (parity-by-construction), the uniqueIndex/RepetitionNotUnique subset case→IF39, IG20→IF42.)

**(none open)**

**The partial-validation FUZZ differential is built** (`adapter/…/perf/PartialValidationFuzzDiffTest`, 2026-06-26): it sweeps random (document, relevant-subset) combinations and asserts the interpreter's `validatePart` matches the kernel's fired-message signatures across the WHOLE partial surface (including the starred aggregates — it surfaced IG20, now fixed as IF42 and un-excluded). It is the standing guard for the relevant-set dimension.

Deferred items are in §2; a fixed gap is deleted from here (git + its `IFn` finding are the record).

---

## 2. Deferred (recorded, with a re-open trigger; detail in [`INTERPRETER-FINDINGS.md`](INTERPRETER-FINDINGS.md))

| code | item | status / re-open trigger | detail in |
|---|---|---|---|
| **IG5** | runtime-semantics extraction (generator-readiness) | tracked refactor, **not** a prerequisite — migrate the comparison/3VL/aggregate logic still inside `Interpreter` into the pure runtime layer **as touched**; `DateMath`/`Kleene`/`quantifierFires`/the op registries/**`ValCompare`** (value comparison — `language` + `ORDER`) already moved. Remaining: the aggregate folds + the directional-fill polarity logic. | IF10 |

---

## 3. Out of scope (won't build unless the trigger flips; recorded so it's not re-investigated)

- **`mandatoryGroup`** (`FEHLER_PFLICHTKONTEXT`) — **source-confirmed unreachable in 30.8.1**: the code is defined but **never emitted**, and **no DM-JSON property declares a group mandatory**, so it can't fire on any loadable model (parity by construction). Was the deferred IG3; re-check on a kernel upgrade. Detail: KERNEL-FINDINGS "mandatoryGroup is a DEAD code in 30.8.1".
- **custom-ref error-entity attribution** (`IRule.getCustomRefErrorEntitiesIfFilled/IfNotFilled`) — **source-confirmed unwired in 30.8.1**: the md-model rule mapping returns empty pending A12K-2281/2361, so the kernel always uses its default error-entity attribution (which the interpreter matches; `CustomConditionDiffTest` locks the CustomCondition error field). Was the deferred IG8; re-check on a kernel upgrade. Detail: KERNEL-FINDINGS "getCustomRefErrorEntities… is UNWIRED in 30.8.1".
- **`getRoundingMode`** on a computation — inert in the runtime (rounding is explicit via the round operators). Detail: IF22.
- **`uniqueValue` (`FEHLER_MULTI_SELECT`)** — was the deferred IG2; **source-confirmed out-of-scope 2026-06-26** (re-verified parallel to the charset re-check): the code is a **multi-select GROUP**'s value-uniqueness (`md-model` `MultiSelectGroupRule` + the codegen `CodeGenRuleDefinition`), NOT a generic per-field `unique` flag (no `unique`/`isUnique` serializer on any field type or `IField`/`IStringType` in 30.8.1). The interpreter has no multi-select-group concept (single-valued fields only) and reports an unmodeled construct as **unsupported** (never silently wrong), so the code is unreachable — parity by construction. (Distinct from charset, which fired from a DEFAULT and so WAS a real gap → IF43; this one needs a genuinely-unmodeled construct.) Re-open trigger: multi-select-group support is added to the interpreter. Detail: IF20.
- **formal-error message TEXT** (byte-identical to the kernel's) — was the deferred IG6; **out-of-scope by the license boundary**: the kernel's formal-error text is German-only and **EUPL/commercial-licensed** (IF3) — it cannot be copied into the kernel-free interpreter. The interpreter emits its own English wording; the *code* / pointer / severity already match the kernel (the parts a consumer acts on). Re-open trigger: a consumer needs verbatim kernel text AND a license-clean source for it exists. Detail: IF (formal errors) / KERNEL-INTEGRATION-NOTES.
