# Interpreter build-out — plan & coverage map

The working plan for growing the `:interpreter` module from the validated spike ([INTERPRETER-SPIKE-FINDINGS.md](INTERPRETER-SPIKE-FINDINGS.md)) into the full-blown evaluator the proposal commits to ([INTERPRETER-PROPOSAL.md](INTERPRETER-PROPOSAL.md) — built out **in-repo**, §8). This is the *method + architecture + order*; the **live coverage truth is the differential tests** (`adapter/…/laws/*DiffTest.kt`) plus a planned catalog-parity test, never a hand-maintained checklist here.

**Acceptance (both use-cases):**
1. **Native image** runs [`examples/cli-runtime.md`](../examples/cli-runtime.md) end-to-end (the `model eval` / `model compute` / `rule test` verbs, today `JVM_ONLY_VERBS`).
2. **Standalone** Kotlin/JVM + Kotlin/JS: full **and partial** validation, computations, and **single-rule / single-computation** eval over a document model.

Correctness throughout = **agrees with the real kernel** (the `RuntimeLaws` differential oracle).

## Session state & how to resume (2026-06-23, HEAD `e46777a2`)

Paused with a **clean tree**, all green (`./build.sh :interpreter:check :adapter:test :cli:test` + `./scripts/verify-demos.sh`). Resume from this list.

### ⭐ THE GOVERNING DECISION — read first (it reframed everything)

**Clean-room reimplementation is the interpreter's #1 rule, and it's a LICENSE boundary** (decided 2026-06-23; `CLAUDE.md` "Interpreter … #1 rule"; INTERPRETER-FINDINGS **IF0**). The kernel is **EUPL-1.2 (copyleft) / commercial**; a runtime that *uses* it inherits the copyleft. So the interpreter gives dmtool's **native runtime a kernel-free engine** → that runtime carries no kernel code and is **free of the EUPL**. Therefore:
- **Never delegate** to the kernel from the interpreter (purity-lint-enforced), and **never transliterate kernel source** (a line-by-line port can be a copyright derivative that re-attracts the EUPL).
- **Derive every operator from BEHAVIOR, clean-room** — the **differential oracle** (the real kernel as a *black box* via `RuntimeLaws`) + the public BA/KERNEL-SEMANTICS spec. Reading kernel source to *audit* a divergence is fine; the *fix* is original Kotlin verified against the differential. Copy the mechanism/behavior (not protected), never the expression.
- The user briefly said "delegate!!!"; that was **overridden** once the EUPL consequence was worked out — delegation would force the runtime to be EUPL *and* can't go native (Groovy). Do **not** revisit delegation.

### Done & committed this session (operators + the source-audit fixes)
- **Message fidelity:** rich message model (`field`/`referenced`/`fillToFix`), **`$field$`** display-name (label-or-name, both engines), **formal-error `formalePruefung`** messages (own text, Option A). `FormalErrorCode` enum.
- **Operators added** (each `*DiffTest`-locked): `GroupFilled`/`GroupNotFilled` (non-repeatable) · value-list `FieldValueIncludedInValueList`/`…NotIncluded…` · **field-list quantifiers** (`NoFieldFilled` `AtLeastOneFieldFilled` `MoreThanOneFieldFilled` `NotAllFieldsFilled` `NotExactlyOneFieldFilled` `FieldsNotCollectivelyFilled`) · **group-list quantifiers** (`AllGroupsFilled` `NoGroupFilled` `AtLeastOneGroupFilled` `NotAllGroupsFilled` `GroupsNotCollectivelyFilled`) · `NumberOfFilledFields`/`NumberOfFilledGroups` · date **extractors** (`YearFromDate`/`MonthFromDate`/`DayFromDate`/`QuarterFromDate`) · date **arithmetic** (`AddDays`/`AddMonths`/`AddYears`) · `DifferenceInMonths`/`DifferenceInYears`. (See the snapshot below for the full handled set, ~45 constructs.)
- **Dispatch is enum-based** (`PredicateOp`/`FunctionOp`, by kernel name); quantifier **polarity is computed** (fixable-by-filling → OMISSION, data-dependent — `NotExactlyOneFieldFilled` is OMISSION@k=0/VALUE@k=2).
- **Package restructure:** `commonMain` is now layered into `.model` / `.ast` / `.parse` / `.eval` (root = `DmInterpreter` façade + `Dec` + the exception). Consumers' imports remapped.
- **Source-audit fixes** (4 agents reviewed every operator vs kernel source; behavior re-verified via the oracle, fixed clean-room): **(a)** `AddYears` preserves end-of-February (Feb 28 non-leap +1yr → Feb 29) and `DifferenceIn{Months,Years}` are signed whole-period counts via repeated-add (backward pairs, Feb-28 boundary) — `DateArithmeticDiffTest`/`DateDifferenceDiffTest`; **(b)** the **empty-row gate is now structural `canFireEmpty`** (negative presence conditions fire on a blank row; comparisons stay suppressed) — `EmptyRowGateDiffTest`.

### Kernel-alignment review (2026-06-23) — findings

The bar is **match the kernel EXACTLY** (CLAUDE.md "#1 rules"; IF0). Reviewed the shipped operators against the kernel source (`BedingungsOperatorHelper`, `VkBigDecimal`, `NumberCombiner`):

**✅ Verified-matched (source-confirmed + differential-locked):** date comparison (kernel `Date.after/before/equals` ≡ ISO-lexicographic), date extractors (kernel returns `MONTH+1`), `DifferenceIn{Days,Months,Years}` (sign convention + whole-period counting + Feb-28), `Add{Days,Months,Years}` for **dates**, aggregates `Sum`/`Min`/`Max`/`MinValue`/`MaxValue` (empties **skipped** — kernel `setEmptyValueGiven`), empty-string compare (neither `==` nor `!=` fires — kernel `vergleicheSTRING`), the time-of-day extractors. **The 3VL combination is confirmed-matched** — `ValidierungsErgebnis.combineUND/combineODER` collapse UNKNOWN into `FALSE_OR_UNKNOWN` and fire on `isKnownAndTrue()`; worked against my Kleene `and`/`or` + "fire iff TRUE", the **firing decision is identical** (T∧U, T∨U, U∨U, … all agree), and the polarity combine matches (**AND → omission-wins**, **OR → value-wins among true branches** — my `evalTyped`/`orType`). The kernel has no `combineNOT` (consistent with IF6: no standalone `Not` in the DSL).

**✅ FIXED this review — directional + sign-aware LEAF polarity** (was the headline 🔧; subsumed the old "`Sum` sign" item). The kernel's `vergleiche` types a fired comparison OMISSION only when an operand can move *in the breaking direction*: GT/GE → `left.kannKleinerWerden || right.kannGroesserWerden`; LT/LE → mirror; EQ → any operand can move. **Exact rule (`RuntimeController.feldWertAlsZahl`, TS twin `BigDecimalUtils.ts:85`):** an empty number (→0) has `kannGroesserWerden = true` but `kannKleinerWerden = hatVorzeichen`; the sign trigger is **`NumberType.positivesOnly`** (NOT `minValue`). Implemented: `EvalContext.signed(path)` (default true) + `Document(…, unsignedFields)`; `Interpreter.leafType(op, …)` now reads a directional `(canGrow, canShrink)` per operand — **field** empty→`(true, signed)`/filled→`(false,false)`, **literal**→`(false,false)`, and **every non-field operand keeps the prior *symmetric* notion** so all operators reduce exactly to the old `leafOmission` (zero regression). Locked by `adapter.laws.DirectionalPolarityDiffTest` (`Quantity` signed vs new `StockOnHand` `positivesOnly` × GE/LE). **Still open (follow-ups):** (a) **aggregate** directional sign-awareness — a `Sum`/`Min`/`Max` over an unsigned field still uses the symmetric omitted-tail notion (signed-correct; unsigned-aggregate over-omits) — needs `starOmission` to go directional per combiner; (b) **CLI loader** must populate `Document` `unsignedFields` from `NumberType.positivesOnly` so dmtool's runtime gets the same typing (today it treats all fields signed).

**🔧 MUST-FIX to match exactly (real divergence — confirmed, now *required*):**
- **Number compare/equality must rescale both operands to scale 19 HALF_UP first** (`VkBigDecimal.DEFAULT_SCALE`); mine compares full-precision. **Observable**: an expression can yield >19 fractional digits (scale-10 × scale-10 → scale-20). (KERNEL-FINDINGS line 210; the multiply path makes it observable.)
- **Arithmetic `×`/`÷`/`^` must use `MathContext(50)` HALF_UP** (`VkBigDecimal.INTERNAL_PRECISION`); my `*` is full-precision, `÷`/`^` not yet built — and `BigDecimal.divide` **throws** without a MathContext on a non-terminating quotient, so it's mandatory there.
- The two precision items need the **cross-platform `Dec` layer** to apply identical rounding on JVM (MathContext/setScale) and JS (big.js precision) — a `Dec`-level change + new differentials. (Stale `Dec.jvm.kt` comment already corrected.)

**🔒 MUST-MATCH when implemented (observable special case — replicate exactly, do NOT drop as "old"):**
- **Feb-28 leap-year time-clearing** in `AddYears`/`AddMonths` for **DATE_TIME** operands (`addiereJahre`/`addiereMonate` `clearTime` branches): crossing a leap-year Feb-28 boundary preserves/clears the time-of-day component. A no-op for pure dates (already matched), so it surfaces only when DateTime arithmetic lands — then a must-match, locked by the differential. (KERNEL-FINDINGS line 272.)

**⏳ NOT yet alignment-reviewed (codegen-backed — next pass):** the field/group quantifiers, value-list, and pattern are emitted by the **code generator** (`kernel-tool/kernel-core-codegen-rules/.../generator/`), not `BedingungsOperatorHelper` — scan those next (broadened source-scan rule). (3VL combine: now reviewed — see ✅ above.)

**Other open edges (low/edge):**
- **Requiredness validation not modeled** — the kernel emits a `mandatoryField` message for a required-but-empty field (incl. a repeatable group's index field); the interpreter does not validate the `required` property at all (it does rules + formal-errors + computations). A whole missing full-validation dimension, surfaced by `AgainstKernelPerfTest` (kernel fired `[mandatoryField, HC]`, interp `[HC]`). Distinct from `validatePart` (#22); needs the `required` property loaded + emitted. Tracked separately.
- **JS regex dialect** — Kotlin/JS `Regex` is ECMAScript, not Java regex; `\p{…}` etc. diverge on JS (JVM matches the kernel). Needs a documented JVM-only guarantee or a translation/validation layer — cross-platform follow-up.
- **TIME has no formal check yet** (`formalError(Kind.TIME) == null`) — the kernel suppresses a rule referencing a malformed `HH:mm:ss` value; the interpreter would extract from it. Diverges only on malformed time input; needs the kernel's time formal-error code + a suppression differential (IF8).
- **`GroupFilled` on an empty *created* repeat row** (kernel TRUE, mine FALSE) — only reachable if an empty-but-instantiated repeat row is representable. **`FieldValueNotIncludedInValueList` polarity** when a list member is an empty field-ref (type-only, narrow). **The 3VL `Not`/`Or` leaf-UNKNOWN leak is NOT reachable** (no standalone `Not` in the DSL) — left as-is, documented (IF6).

**PERF is now its own decision + spec.** The O(N²) `Document` and the **real-document, against-kernel, group-heavy** benchmark method (the spike's `RepDoc` benchmark is *cheating* — retired) live in [INTERPRETER-PERFORMANCE.md](INTERPRETER-PERFORMANCE.md) + task #28.

### Next, in order (user's chosen plan — incrementally, by family)
1. **Finish the date/time family** (clean-room, differential-derived): **`Today` DONE** (the injected `EvalClock` seam — `Expr.Const`/`ConstantOp`, `ClockDiffTest`); next `Now` (needs a date-time value type — `Now` truncated-to-day compares to a date field, full compares to a date-time field) and `BaseYear` (needs the model's `modelInfo.baseYear` metadata + the polymorphic date-vs-number typing), the **time-of-day** family (`HoursFromTime`/`MinutesFromTime`/`SecondsFromTime`, `AddHours`/`AddMinutes`/`AddSeconds`, `DifferenceInHours`/`DifferenceInMinutes`/`DifferenceInSeconds` — need a TIME kind), the **DateTime/Time/Date constructors** + `DateFromDateTime`/`TimeFromDateTime`, `ValueAsDate`(`FirstDay`/`LastDay`), and the **date-range** ops (`DateRange`/`DateRangesOverlap`/`StartOf`/`EndOfDateRange`).
2. **Arithmetic `/` and `^`** (with the div-by-zero / scale semantics — probe), then `Abs`/`Round*`/`Length`/`SumOfProducts`/coercions, **uniqueness** (`FieldValuesNotUnique`/`RepetitionNotUnique`), and **misc/scope** (`EnumCategory` via `->`, `CurrentRepetition`, `FirstFilledValue`, `Having`, `True`/`False`/`Valid`/`Invalid`).
3. **A catalog-parity test** so an unhandled operator FAILS the build (mine the `OperatorCatalog`/construct enums; every catalog op handled or explicitly deferred).
4. **Decide the open audit edges** (scale/precision/JS-regex) — fix or document-as-known-gap with a law.
5. **Native build (task #23)** — re-capture reflection metadata, `:cli:nativeCompile`, `native-gate.sh` with `cli-runtime.md` un-skipped. (Per step 5 detail below.)

**Findings routing (owner rule):** kernel-behavior truth → [KERNEL-FINDINGS.md](KERNEL-FINDINGS.md) (cross-check consult-only `KERNEL-SEMANTICS.md`); interpreter design/treatment → [INTERPRETER-FINDINGS.md](INTERPRETER-FINDINGS.md). This plan is the coverage map; durable rationale lives in those two.

### How to warm up and continue (post-compaction playbook)

A cold agent picking this up should do these **in order**.

**1 — Read, to rebuild the mental model (≈15 min):**
1. **This doc**, top to bottom — the plan, the session-state list above, the API design (below), the loader field-mapping (below).
2. [INTERPRETER-PROPOSAL.md](INTERPRETER-PROPOSAL.md) — §2 (interpret-not-codegen), §6/§7 (owned-vs-delegated + the four standalone hygiene rules), §8 (built in-repo; separation by purity lint), §10 (spike result + build-out progress), §11 (open decisions), §12 (full+partial in scope).
3. The **engine** (`interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/`, **layered into subpackages**): root — `DmInterpreter.kt` (the public API façade + per-row evaluation + iteration scope + the rich-message build), `Dec.kt`, `Unsupported.kt`; `model/Model.kt` (the data contract — `ModelDef`/`FieldDef`/`RuleDef`/`Message`/`Kind`/`MsgType`/…); `ast/Ast.kt` (`Cond`/`Expr`/`Val`/`Tri` + `typedValue`/`emptyValue`); `parse/Parser.kt`; `eval/` — `Interpreter.kt`, `EvalContext.kt`, `Document.kt`, `Operators.kt` (the `PredicateOp`/`FunctionOp` registry), `FormalError.kt`, `Pointer.kt`, `DateMath.kt`, `Message.kt` (the `$…$` renderer). Dependency direction: `model ← ast ← parse`, `model,ast ← eval`, all ← root.
4. The **CLI wiring**: `cli/.../interp/InterpreterModelLoader.java` (DM-JSON→`ModelDef`, instance→`Document`) + `InterpreterEngine.java` (renders the verbs' `data`); `cli/.../model/ModelEvalCommand.java` + `ModelComputeCommand.java`, `cli/.../rule/RuleTestCommand.java` (the `--kernel` branch + the engine default); `cli/.../Cli.java` (`JVM_ONLY_VERBS`, `isNativeImage`).
5. The **differential tests = the live coverage truth** (the spec for every operator): `adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/laws/*DiffTest.kt` (~25 files — incl. this session's `GroupFilledDiffTest`, `ValueListDiffTest`, `FieldListQuantifierDiffTest`, `GroupListQuantifierDiffTest`, `NumberOfFilledDiffTest`, `DateExtractDiffTest`, `DateArithmeticDiffTest`, `DateDifferenceDiffTest`, `EmptyRowGateDiffTest`, `RichMessageDiffTest`, `DisplayNameDiffTest`, `FormalErrorMessageDiffTest`, alongside the originals `InterpreterDiffTest`/`ParserDiffTest`/`PolarityDiffTest`/`AggPolarityDiffTest`/`RepetitionDiffTest`/`LocalizedMessageDiffTest`/`PatternDiffTest`/`ArithmeticDiffTest`/`DifferenceInDaysDiffTest`/`SuppressionDiffTest`/`ValidateFullDiffTest`/`TestRuleDiffTest`/`ComputeDiffTest`) + the oracle `adapter/.../laws/RuntimeLaws.java`; and `cli/.../interp/InterpreterRealModelDiffTest.java` (loader vs live kernel over real models). **`RuntimeLaws` is your black-box oracle — use it (not the kernel source) to derive operator behavior.**
6. **Kernel truth (consult-only, never edit):** `../a12-kernel-demo/KERNEL-SEMANTICS.md`, the merged BA doc `../a12-kernel/documentation/_merged/kernel-ba.md`, and our own [KERNEL-FINDINGS.md](KERNEL-FINDINGS.md). For message/pointer mechanics: kernel source `../a12-kernel/kernel-md/kernel-md-runtime-service/.../MessageImpl.java` + `DocumentValidationResultImpl.java`, and `…/document-v2/.../apiV2/PartiallyKnownDocumentMultiPointer*.java`.
7. `../CLAUDE.md` — the standing conventions (esp. the **"Interpreter: built out in-repo; separation by convention + linting"** section, the build-env isolation, the differential-law discipline).

**2 — Verify the baseline is green before touching anything:**
```
git status                     # clean; HEAD at the session-state commit or later
./build.sh :interpreter:check :adapter:test :cli:test
./scripts/verify-demos.sh      # all 6 demos OK (slower; optional unless touching CLI/demos)
```
If any is red on a clean tree, stop and investigate — don't build on a broken baseline.

**3 — Re-internalize the traps (cheap mistakes this project punishes):**
- **⭐ CLEAN-ROOM, kernel-free (the #1 rule, a LICENSE boundary):** never delegate to the kernel and never transliterate its source; derive operators from the **differential oracle** (black-box behavior) + the public spec, as original Kotlin. The kernel is EUPL-copyleft — using/porting it would force dmtool's runtime to be EUPL. (CLAUDE.md "Interpreter … #1 rule"; INTERPRETER-FINDINGS IF0.)
- **Build ONLY via `./build.sh <args>`** (isolated `GRADLE_USER_HOME` + JDK 21). A bare `./gradlew` gets contaminated by the machine's private registry and fails confusingly.
- **`:interpreter` is kernel-free AND dependency-free** — the `verifyInterpreterPurity` lint fails the build on a forbidden import. Kernel-differential tests live in **`:adapter`** (`testImplementation project(':interpreter')`), never in `:interpreter`. The decimal/date shims are `expect/actual` (`Dec`); the rest is `commonMain`.
- **Every new operator / behavior gets a `*DiffTest` vs the real kernel** (`RuntimeLaws`) — the differential is the spec, not hand-asserted expectations.
- Kotlin **`*/` in KDoc** breaks compilation (write `Items* / Count`). The `expect/actual class` beta warning is harmless.
- Shell: `rm` is aliased to `rm -i` → use **`/bin/rm`**; zsh doesn't word-split unquoted vars.
- **Commit per feature** (Conventional Commits, subject-only), **auto-commit without asking**, **commit on `main` (never branch)**, **never offer to push**.

**4 — Do the next task (finish the date/time family) — the per-operator loop (CLEAN-ROOM):**
For each operator (see "Next, in order" above — start with the time-of-day family or the `Today`/`Now` clock seam):
1. **Add the op to the registry** — `eval/Operators.kt` (`PredicateOp` or `FunctionOp`, with its kernel construct name); add the name to `parse/Parser.kt`'s `PREDICATES` if it's a boolean predicate (value functions need no parser entry — they parse as `Expr.Func`).
2. **Implement in `eval/Interpreter.kt`** (`evalPred`/`evalFunc`) and/or `eval/DateMath.kt` — **original Kotlin, derived from the differential, NOT from kernel source**. For a clock op, thread an injected "current date/time" through `DmInterpreter`/`EvalContext` (the kernel uses the validation's current date; don't call `Date.now()` directly in commonMain).
3. **TDD red→green:** write `adapter/.../laws/<Family>DiffTest.kt` FIRST — load `RuntimeLaws.model("models/order.dm.json")`, inject the rule (`RuntimeLaws.withRule`), build the doc two ways, assert `interp == kernel` (firing + type). Probe edge cases as black-box (the oracle reveals the rule — e.g. how `DifferenceInMonths` rounded). Run, confirm red, implement, green.
4. **`./build.sh :interpreter:check :adapter:test`**; update the operator snapshot below; record any kernel fact in KERNEL-FINDINGS + interpreter treatment in INTERPRETER-FINDINGS (the routing rule); **commit per operator family** (`feat(interpreter): evaluate <ops>`). Run `:cli:test`/`verify-demos.sh` only if a verb's output shape changed (operators don't, unless a demo model uses them).

Then continue down "Next, in order": arithmetic `/`/`^` + coercions, uniqueness, misc/scope, the **catalog-parity guard**, the open audit edges, and finally the native build.

Then **#27 (formal-error messages)** and **#19 (operator coverage)** per the prioritized order, each its own `*DiffTest` + commit. Finally **#23 (native build)** per step 4 of the session-state list — re-capture, `:cli:nativeCompile`, `native-gate.sh` with `cli-runtime.md` un-skipped.

**Differential-test recipe (the pattern every increment follows):** add a `…DiffTest.kt` in `adapter/.../laws/`; load a fixture model (`RuntimeLaws.model("models/order.dm.json")`); inject the rule (`RuntimeLaws.withRule(...)` / `.withComputation(...)`); build the instance two ways from one spec — `RuntimeLaws.doc()…` for the kernel and `Document(kinds)…` / `SingleRowDoc`/`RepDoc` for the interpreter; assert `interpreter == kernel` (fired codes, verdict, type, the rich-message channels). Drive the interpreter from **parsed condition text** (`parseCondition`) where possible, not a hand-built AST.

## Current state (what the engine does today)

| area | today | gap to full-blown |
|---|---|---|
| **Conditions** | tree-walk over `Cond`/`Expr`; 3-valued logic; type-driven empty asymmetry; polarity (VALUE/OMISSION incl. omitted-tail) | — (solid foundation) |
| **Operators** | ~20 of 109 constructs (see below) | ~89 constructs across ~8 families |
| **Computations** | `compute()` → VALUE / CLEARED, kernel-verified (incl. empty-as-0 → `0`) | the ERRORED bucket (operand/computation error) + pre-conditions; awaits the formal-error increment |
| **Formal errors / suppression** | DATE + NUMBER (`fuehrendesBlank` / `zahlHatUngueltigeZeichen` / `zahlHatDezimalTrenner` / scale `zahlFalscheAnzahlNachkommastellen`, scale from `FieldDef.scale`) — closed-set `FormalErrorCode` enum; gate → rule **suppressed** (`rule test` SUPPRESSED + `suppressedBy`) **and** `validate`/`model eval` **emits a `formalePruefung` message per malformed field** (structural channels kernel-matched; own text — Option A), kernel-verified | byte-identical kernel text (the deferred Option-B proposal, KERNEL-INTEGRATION-NOTES) |
| **Document model** | general `Document` (indexed placements, common-prefix resolution, star aggregation) — flat + nested-non-repeatable + one-level repetition, kernel-verified; spike's `SingleRowDoc`/`RepDoc` still present (consolidation pending) | multi-level repetition (resolution is general; only one level is kernel-tested) |
| **Model / instance loading** | structured `ModelDef`/`RuleDef`/`FieldDef` (interpreter owns its types); the consumer builds them | DM-JSON model → `ModelDef`; `{"fields":{…}}` instance → `Document` (dmtool via Jackson; a multiplatform JSON loader later) |
| **Public API** | `DmInterpreter.validateFull(doc)` — whole-document, kernel-verified | `validatePart` / `compute` / `testRule` / candidate-eval entry points |
| **Messages** | `$field.value$` value-interpolation **and `$field$` display-name** (label-for-locale-else-name, both engines), en/de; **rich message model** — structured field pointer (path + repetition indices), `referenced` operands, `fillToFix` (refOmissionErrorResponsible), matching the kernel's `IMessage` channels on both engines; **formal-error messages** emitted (own text) | byte-identical kernel formal-error text (deferred — Option B) |
| **Parser** | recursive-descent subset (comparisons, And/Or, predicates, aggregates, patterns, paths/`*`) | full grammar (or kernel parse-tree-api) — §11 |

## Two tracks

The build-out splits cleanly, and the two are **largely independent**:

- **Track A — use-case-1 critical path (structural).** What `cli-runtime.md` needs is *depth*, not breadth: a **model loader**, an **instance loader**, a **general doc model**, **computations**, **formal-error/suppression**, the **public API**, and the **dmtool wiring**. `cli-runtime.md` itself only uses comparisons on numbers + dates (already supported) — so the operator set is *not* its blocker; the structure is.
- **Track B — use-case-2 breadth (operators).** The ~90 missing catalog operators, each added with a `*DiffTest` against the kernel. Needed for standalone "full" parity, not for the cli-runtime acceptance.

## Operator coverage snapshot

*Snapshot only — the diff tests are the truth.* Handled (~34): `And` `Or` `Not` · `LessThan` `LessThanOrEqual` `GreaterThan` `GreaterThanOrEqual` `Equal` `NotEqual` · `FieldFilled` `FieldNotFilled` `AllFieldsFilled` · **`GroupFilled` `GroupNotFilled`** (non-repeatable group; the repeatable `Group*` aggregate form needs `*` — `MVK_NO_WILDCARD` — deferred to the `*`/aggregate family) · **`FieldValueIncludedInValueList` `FieldValueNotIncludedInValueList`** · **field-list quantifiers `NoFieldFilled` `AtLeastOneFieldFilled` `MoreThanOneFieldFilled` `NotAllFieldsFilled` `NotExactlyOneFieldFilled` `FieldsNotCollectivelyFilled`** and **group-list quantifiers `AllGroupsFilled` `NoGroupFilled` `AtLeastOneGroupFilled` `NotAllGroupsFilled` `GroupsNotCollectivelyFilled`** (one count-of-filled mechanism, fields or groups; data-dependent polarity) · `Min` `Max` `Sum` `MaxValue` `MinValue` `NumberOfDifferentValues` **`NumberOfFilledFields` `NumberOfFilledGroups`** · `PatternMatched` `PatternViolated` · `DifferenceInDays` **`YearFromDate` `MonthFromDate` `DayFromDate` `QuarterFromDate` `AddDays` `AddMonths` `AddYears`** (day-clamped calendar math) **`DifferenceInMonths` `DifferenceInYears`** (whole elapsed periods) · **`Today`** (the clock constant — current date from an injected `EvalClock`, `Expr.Const`/`ConstantOp`) · **`HoursFromTime` `MinutesFromTime` `SecondsFromTime`** (time-of-day extractors over a `Kind.TIME` `HH:mm:ss` value) · arithmetic `+ - *` (`{ }` grouping; `/` `^` deferred). The handled presence-predicate/aggregate sets are the `PredicateOp`/`FunctionOp` enums. An unmodeled/external construct (e.g. `CustomCondition`) is *tolerated* by `validate` (reported `unsupported`), not a crash.

Missing families (~90), by group:

| family | examples | notes |
|---|---|---|
| **filled-predicates** (rest) | the repeatable `Group*` aggregate forms (~2) | field-list + group-list quantifiers + the `NumberOfFilled…` count functions **done** (`FieldListQuantifierDiffTest`, `GroupListQuantifierDiffTest`, `NumberOfFilledDiffTest`); the repeatable `*` forms remain (the `*`/aggregate family) |
| **value-list / enum** | `FieldValueIncludedInValueList` `FieldValueNotIncludedInValueList` `AtLeastOne…` `No…` `NotAll…` `EnumCategory` (~6) | membership against a declared value list |
| **pattern** | `PatternMatched` `PatternViolated` (2) | regex on string fields |
| **arithmetic** | `Arithmetic` `Abs`/`AbsValue` `RoundUp/Down/Accounting`(+`Value`) `SumOfProducts` `Length` `RangeAsNumber/String` `FieldValueAsNumber/AsString` `NumberOfValueInFields` (~15) | numeric expressions/coercions |
| **date / time** | `Now` `BaseYear` `Date` `DateTime` `Time` `AddHours`/`AddMinutes`/`AddSeconds` `DifferenceInHours`/`DifferenceInMinutes`/`DifferenceInSeconds` `DateFromDateTime` `TimeFromDateTime` `ValueAsDate`(`FirstDay`/`LastDay`) … (~14) | **date extractors, `AddDays`/`AddMonths`/`AddYears`, `DifferenceInMonths`/`DifferenceInYears`, `Today` (injected clock), and the time-of-day extractors `HoursFromTime`/`MinutesFromTime`/`SecondsFromTime` done** (`DateExtractDiffTest`, `DateArithmeticDiffTest`, `DateDifferenceDiffTest`, `ClockDiffTest`, `TimeExtractDiffTest`); remaining: `Now`/`BaseYear` (need a date-time type / base-year metadata), the DateTime/Time **constructors** + `*FromDateTime`, the time **arithmetic** (`AddHours`/`DifferenceInHours`/…), and the **date-range** family |
| **date ranges** | `DateRange` `DateRangesOverlap` `AtLeastOneDateRangeOverlaps` `Start/EndOfDateRange` `DiffersWithToleranceRangeN` (~8) | |
| **uniqueness** | `FieldValuesNotUnique` `RepetitionNotUnique` (2) | cross-row |
| **misc / scope** | `CurrentRepetition` `FromScope` `Having` `SemanticIndex` `FirstFilledValue` `CustomCondition` `True` `False` `Valid`/`Invalid`/`ValidType`/`InvalidType` (~12) | |

## cli-runtime.md critical path (the minimal end-to-end slice)

The demo's scenarios pin Track A's deliverables. **The actual `order-ruled.dm.json` rules were inspected (2026-06-23)** — they need more than the demo prose implies:

| scenario | rule / op it actually uses | interpreter status |
|---|---|---|
| `model eval --rule /Order/DeliveryNotBeforeOrder` | `AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0` | ✓ — `DifferenceInDays`, `AllFieldsFilled`, `<` all kernel-verified (`DifferenceInDaysDiffTest`) |
| `rule test` (same rule) → fired/passed/**suppressed** | same + the formal-error gate (`not-a-date` → `datumFormatFalsch`) | ✓ — suppression + the date rule both verified |
| `model eval --condition "[/Order/Quantity] > 100"` | a candidate comparison | ✓ (`evalCandidate`) |
| `model compute` (`[BaseFee]`, empty-as-0) | `[BaseFee]` | ✓ (`compute`) |
| (model also contains) `EligibilityCheck` | `CustomCondition ExternalEligibility` | **uninterpretable by design** (external) — `validate` *tolerates* it (reports `unsupported`, fires the rest), like the kernel-adapter's `Opaque` ✓ |
| (model also contains) `PostalCodeFormat` | `… PatternViolated "[0-9]{5}"` | ✓ |

**Every interpretable rule in cli-runtime's models is now kernel-verified.** The remaining cli-runtime work is purely integration + toolchain: a **DM-JSON → `ModelDef` loader** (consumer-side, native-safe via the kernel read tier) and the **dmtool wiring + native build**.

## Prioritized order

1. ✅ **Public API + general doc model** — `ModelDef`/`RuleDef`/`FieldDef` + `DmInterpreter.validateFull` over a general `Document` (`ValidateFullDiffTest`).
2. ✅ **Single-rule eval** — `testRule` (FIRED/PASSED) + `evalCandidate` (`TestRuleDiffTest`).
3. ✅ **Computations** (`compute()` → value/cleared, empty-as-0) — `ComputeDiffTest`.
4. ✅ **Formal-error / suppression** — the `rule test` SUPPRESSED verdict + `suppressedBy` (`SuppressionDiffTest`); DATE + NUMBER formal codes so far.
5. **dmtool wiring + native image** → the cli-runtime acceptance (use-case 1). **Done:** the DM-JSON → `ModelDef` loader (`InterpreterModelLoader`, now `:cli` main) + `InterpreterEngine` render the runtime verbs' `data` from the interpreter; **`model eval`/`compute` and `rule test` default to the interpreter, with `--kernel` selecting the kernel (JVM only; refused under the native profile).** Real-model differential (`InterpreterRealModelDiffTest`) + the regenerated `examples/cli-runtime.md` (now interpreter-default) prove parity with the live kernel; the kernel-fidelity verb tests pin the kernel via `CliTestSupport.runKernel`. **Remaining (the blocker):** the three verbs are still in `Cli.JVM_ONLY_VERBS` (hidden in the native image), because the `--kernel` branch keeps `KernelAdapter.evaluate` (→ Groovy) reachable in the build. Native enablement = remove them from `JVM_ONLY_VERBS` + make the kernel-eval path unreachable in the native image (a GraalVM substitution / build-time exclusion — the Groovy ceiling) + re-capture reflection metadata + native build + `cli-runtime.md` showboat on the binary.
6. **Operator breadth** (Track B) — family by family, each `*DiffTest`-locked, toward full catalog parity (use-case 2). In progress: `PatternMatched`/`PatternViolated`, arithmetic `+ - *` shipped. Add a **catalog-parity test** so an unhandled operator fails the build rather than silently lurking; add division/power with their scale/div-by-zero semantics.
7. **Partial validation** (`validatePart`) + the **message tiers** (display-name, formal-error bundles).
8. **Doc-model consolidation** — fold the spike's `SingleRowDoc`/`RepDoc` onto the general `Document`.

## Input contract / API (design)

The interpreter owns its types (proposal §6/§7) and takes a structured model + instance — the *consumer* supplies JSON parsing (dmtool via Jackson; a multiplatform JSON loader for standalone JS is a later, additive increment, so the core stays lean):

```
ModelDef(fields: List<FieldDef>, rules: List<RuleDef>, computations: List<ComputationDef>)
  FieldDef(path, kind)                      // the metadata bridge (kinds drive empty-asymmetry + formal errors)
  RuleDef(path, group, conditionText, errorCode, errorEntityRelPath, severity, messages)
  ComputationDef(targetPath, expressionText, …)

DocumentInstance                            // the {"fields":{…}} value tree, general nesting/repetition

DmInterpreter(model)
  validateFull(doc): List<Message>          // every rule that fires
  validatePart(doc): List<Message>          // + the 3VL suppression contract
  compute(doc): List<Computed>              // value / cleared / errored
  testRule(rulePath, doc): Verdict          // fired / passed / suppressed
  evalCandidate(rule, doc): Verdict         // an injected, non-stored rule
```

This keeps the engine kernel-free (the purity lint holds) and lets dmtool adapt *to* it. The *implemented* API matches this closely (see `DmInterpreter`/`Model.kt`): `validate`/`validateFull`, `testRule`/`evalCandidate`, `compute`; `validatePart` is the remaining method.

### DM-JSON → ModelDef loader — where each field comes from (verified 2026-06-23)

The loader is the next concrete deliverable; the source mapping is mechanical (a **self-contained** model — no `include`/`modelAlias`/`typeDefImport` — parses directly with Jackson; a model with imports needs the kernel's expansion first, native-safe via the read tier):

| ModelDef piece | DM-JSON source |
|---|---|
| `FieldDef(path, kind)` | a `Field` node: `id` → path; `Field.fieldType.type` → kind (`StringType`→STRING, `NumberType`→NUMBER, `DateType`→DATE, `BooleanType`→BOOLEAN, `ConfirmType`→CONFIRM; `DateTime`/`Time`/`Enumeration` map as the date/time/enum families land) |
| `RuleDef` | a `Rule` node: `id` → path; path-minus-last-segment → `group`; `Rule.errorCondition` → `conditionText`; `Rule.errorCode`, `Rule.errorEntityRelPath`, `Rule.severity`; `Rule.errorMessage[]` (`locale`→`text`) → `messages` |
| `ComputationDef` | a `Computation` node: `id`-parent → `group`; `Computation.computedFieldRelPath` (resolved vs group) → `targetPath`; `Computation.computationAlternatives[0].operation` → `expressionText` |
| `repeatableGroups` / `declaredReps` | a `Group` node with `Group.repeatability` (the declared max rows, e.g. `50`) → the group is repeatable; `repeatability` → `declaredReps[path]` |

The loader is built as `InterpreterModelLoader` in **`:cli` test scope** (proven by `InterpreterRealModelDiffTest`); `:interpreter` is a `testImplementation` dep, so the native image is untouched for now. **Promotion to `:cli` main** (with `implementation project(':interpreter')`) happens when the verbs are wired — that *is* the native-build change that triggers reflection re-capture per `NATIVE-IMAGE-GRAALVM.md` §5.1. A relpath like `../F` resolves against the rule **node's** id (`/Order/Rule` → `/Order/F`), not its group — a latent bug fixed in `errorPath` while building the loader.
