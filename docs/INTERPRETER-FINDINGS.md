# Interpreter findings — design & treatment record

The numbered **design-findings** for the `:interpreter` module (the kernel-free, native-safe + Kotlin/JS rule evaluator) — the *why* behind how it reproduces kernel behavior. This is the interpreter's peer of [API-FINDINGS.md](API-FINDINGS.md) (the rulekit/modelkit library's findings): a **rationale record, not a status surface**. What's *built* is shown by the differential tests (`adapter/…/laws/*DiffTest.kt`) and [INTERPRETER-BUILDOUT.md](INTERPRETER-BUILDOUT.md)'s coverage map.

**Where a fact lives (the routing rule the owner set, 2026-06-23):**
- a **kernel-behavior** truth → [KERNEL-FINDINGS.md](KERNEL-FINDINGS.md) (write), cross-checked against the consult-only `../a12-kernel-demo/KERNEL-SEMANTICS.md` (read);
- an **interpreter design / treatment** finding (how the interpreter mirrors the kernel, an idiom, a deliberate divergence) → **here** (write).

Correctness throughout = **agrees with the real kernel** (the `RuntimeLaws` differential oracle). Each finding cites the lock.

---

### IF0 — Clean-room reimplementation is a LICENSE boundary, not a style choice (decided 2026-06-23)

Why the interpreter reimplements rather than delegates, and why it derives from behavior rather than source: the A12 kernel is **EUPL-1.2 (copyleft) / commercial**. A dmtool runtime that *used* the kernel (linked, shipped, or called it) would be a combined work the EUPL copyleft reaches → it would have to ship as EUPL (or under the commercial license). The interpreter exists to give the **native runtime a kernel-free engine**, so that runtime carries **no kernel code and is free of the EUPL** — and, independently, the kernel's Groovy codegen can't compile into a native image, so delegation fails the native goal regardless. Two consequences that govern every operator:

- **No delegation** — the interpreter never calls the kernel (purity-lint-enforced: no `com.mgmtp.*` in any source set). The `--kernel` flag is the only kernel-touching path, JVM-only, refused in native; that JVM distribution is where EUPL/commercial applies.
- **Clean-room from behavior, not a source port** — each operator is derived from the **differential oracle** (the kernel as a *black box*) + the public spec, and written as original Kotlin. The kernel source may be **read freely for *understanding*** — scan the **operator *and* generator sources** proactively to learn the real semantics and surface *surprising special cases* you'd never think to probe blind (not only to audit a known divergence). But a fix is **never a transliteration** of kernel source (a line-by-line port can be a copyright derivative that re-attracts the EUPL): the loop is *read source → write original Kotlin → lock with the differential*. Copy the mechanism/behavior/special-case (not protected); never the expression. This is the [[license-safe rule]] applied to the runtime engine. Detail: `CLAUDE.md` "Interpreter … #1 rule".

---

### IF1 — Operator dispatch is enum-keyed by the kernel construct name; the AST stays string-typed

The evaluator dispatches presence predicates and value functions on the `PredicateOp` / `FunctionOp` enums (each carrying its kernel construct name, e.g. `FieldFilled`, `Sum`), not on raw strings. But the AST node (`Cond.Pred.name` / `Expr.Func.name`) keeps the name as a **string** on purpose: the kernel vocabulary is open and growing (109+ constructs), and an *unmodeled* operator must round-trip far enough to be **reported** (`validate` returns it as `unsupported`), not fail at parse. So the parser emits the string; the evaluator decodes the handled subset via `byName`, and an unknown name → `null` → `UnsupportedConstructException` (caught and reported). The enums double as the handled-operator registry that operator-coverage growth extends. *(Locked: every `*DiffTest`; the registry is `Operators.kt`.)*

### IF2 — Value-list membership reuses the typed `==` path, so empty→UNKNOWN falls out for free

`FieldValueIncludedInValueList` is evaluated by `evalExpr(field)` then the same typed `eq` used by `==`, against each member expression. This means the kernel's two value-list facts (KERNEL-FINDINGS §8) are reproduced *without special-casing*: an empty string/enum operand evaluates to `Val.Unknown` → the predicate is `Tri.UNKNOWN` → neither the included nor the `Not` form fires; and members compare with the field's type (numbers numerically, strings by text). Reusing the existing 3VL machinery — rather than a bespoke string-contains — is why the differential passed on the empty and number cases. *(Locked: `adapter.laws.ValueListDiffTest`.)*

### IF3 — `$field$` and formal-error text: faithful structure, own prose

Both message tiers reproduce the kernel's **structural** channels exactly but diverge deliberately on free text, to keep the publishable `:interpreter` artifact clear of kernel-copyrighted strings:
- **`$field$`** resolves to the field's label-for-locale else the field NAME (`ModelDef.labelOf`), mirroring the kernel under a label provider — see KERNEL-FINDINGS §3 and the cross-engine treatment in [KERNEL-INTEGRATION-NOTES.md](KERNEL-INTEGRATION-NOTES.md).
- **Formal errors** emit the kernel's verbatim `FormalErrorCode.id` + `formalePruefung` + pointer + ERROR/VALUE, but the human text is the interpreter's **own wording (Option A)**; the byte-identical-kernel-text path is the deferred Option B (KERNEL-INTEGRATION-NOTES "Formal-error message text"). *(Locked: `adapter.laws.DisplayNameDiffTest`, `FormalErrorMessageDiffTest`.)*

### IF5 — Field-list quantifier polarity is computed, not tabled

The field-list quantifiers (`NoFieldFilled`/`AtLeastOneFieldFilled`/`NotAllFieldsFilled`/`MoreThanOneFieldFilled`/`NotExactlyOneFieldFilled`/`FieldsNotCollectivelyFilled`) share one firing law — a function of the filled-count `k` over `N` operands (`quantifierFires(op, k, n)`), reused by both `evalPred` and the polarity computation. Their **message polarity is data-dependent** (KERNEL-FINDINGS §1): rather than guess a static `MsgType` per operator (which is *wrong* for `NotExactlyOneFieldFilled`), `PredicateOp.polarity` is `null` for the quantifiers and `predType` computes it the way the kernel does — **OMISSION iff filling some empty operand (raising `k` toward `N`) could clear the firing, else VALUE**. One rule covers all six and the data-dependent case falls out for free. *(Locked: `adapter.laws.FieldListQuantifierDiffTest`.)*

### IF6 — The empty-row gate is condition-structural (`canFireEmpty`), not a data check

A source audit (clean-room: behavior confirmed via the differential) found my row gate was a **data** check — "the row carries any value" (`EvalContext.evaluated`) — which wrongly skipped a *truly-empty* row, so a mandatory-presence rule like `NoFieldFilled(A, B)` never fired on a blank instance (and my own quantifier test masked it by padding a sibling). The kernel's gate is **structural**: evaluate iff the row is non-empty **or** the condition `canFireEmpty`. `Interpreter.canFireEmpty` recurses the AST — a comparison/pattern can't (keeps empty-as-0 suppressed on a blank row), a presence predicate fires-on-empty iff its count-law holds at k=0 (`quantifierFires(op, 0, n)` / FieldNotFilled, GroupNotFilled), `And`=both, `Or`=either. The 3VL "leaf UNKNOWN leaks under `Not`/`Or`" the audit also noted is **not reachable** — the DSL/parser has no standalone `Not`, so an UNKNOWN never reaches a position where it differs observably from FALSE; left as-is (no speculative change). *(Locked: `adapter.laws.EmptyRowGateDiffTest`.)*

### IF7 — The clock constants read an INJECTED current date, not the platform clock

`Today` (the date-valued, no-operand current-date constant) resolves to `EvalClock.today`, **injected** on the `DmInterpreter` constructor — *not* read from a platform clock. Two reasons force injection: `commonMain` may not call `java.time` / `Date.now()` (the purity lint), and a deterministic value is what makes evaluation testable. The kernel reads "today" from its own runtime clock; the interpreter's contract is **"given this current date, `Today` = that date"** — the consumer supplies it (dmtool from the JVM clock; a JS consumer from `Date`). With **no clock** a `Today` rule cannot be evaluated → it is reported `unsupported` (tolerated, the [[IF1]] round-trip), never crashed.

This shapes the differential: the kernel side uses its **wall clock** and the interpreter side the **injected** date, so they could only be pinned to the same instant via a midnight-racy `LocalDate.now()` read. Instead the differential is made **wall-clock-independent** — it compares `OrderDate` against dates FAR from any plausible "today" (year 2000 / 2999), where both sides agree regardless of the exact current date; it locks that `Today` behaves as a *date* in a chronological comparison. The exact-value boundary (`== Today`) is locked **deterministically in `commonTest`** with a fixed injected clock (no kernel). AST-wise a no-operand constant is `Expr.Const`, dispatched via the `ConstantOp` enum (the [[IF1]] enum-keyed pattern); `Now` (date-time) and `BaseYear` (model base-year metadata, polymorphic) extend it as those types land. *(Locked: `adapter.laws.ClockDiffTest`, `InterpreterMultiplatformTest.todayConstantResolvesToInjectedClock`.)*

### IF8 — TIME is an ISO-text value (`Kind.TIME` → `Val.Txt`); its formal check is deferred

A `Kind.TIME` field is modeled as a `Val.Txt` holding the ISO `HH:mm:ss` string — **no new `Val` variant**, exactly as `Kind.DATE` is. The reason is the same one that makes date comparison free: a zero-padded ISO time string's **lexicographic order is its chronological order**, so the existing text `ORDER` comparator already compares times correctly, and the time-of-day extractors (`HoursFromTime`/`MinutesFromTime`/`SecondsFromTime`) just parse the `HH:mm:ss` parts (the `timeComponent` sibling of `dateComponent`). This is the pattern the upcoming `DATE_TIME` kind will follow too (`yyyy-MM-ddTHH:mm:ss`, still ISO-ordered). An empty TIME → `Unknown` (not evaluated), like DATE.

One deliberate gap: a TIME value is **not yet format-checked** (`formalError(Kind.TIME) == null`). The kernel formal-checks a malformed time and suppresses a rule referencing it; the interpreter would instead try to extract from it. This only diverges on **malformed** time input and needs the kernel's time formal-error code + a suppression differential — a separate increment, tracked in INTERPRETER-BUILDOUT.md's open list. *(Locked: `adapter.laws.TimeExtractDiffTest`.)*

### IF4 — `GroupFilled` is an `EvalContext` seam resolved only by the general `Document`

"Is this group filled?" needs the document's instance structure, which only the general `Document` models; the single-row / repetition spike contexts (`SingleRowDoc`/`RepDoc`, slated for consolidation) don't, and throw the same way `star` does on a flat doc. So `groupFilled` is an `EvalContext` method the general `Document` overrides (any valued field beneath the group, in the row's scope). The **repeatable**-group form is out of scope here: the kernel requires the `*` aggregate (`GroupFilled(Items*)`, KERNEL-FINDINGS §8), which parses to a `Star` operand and belongs to the `*`/aggregate family. *(Locked: `adapter.laws.GroupFilledDiffTest`.)*
