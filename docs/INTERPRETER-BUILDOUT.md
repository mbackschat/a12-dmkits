# Interpreter build-out — plan & coverage map

The working plan for growing the `:interpreter` module from the validated spike ([INTERPRETER-SPIKE-FINDINGS.md](INTERPRETER-SPIKE-FINDINGS.md)) into the full-blown evaluator the proposal commits to ([INTERPRETER-PROPOSAL.md](INTERPRETER-PROPOSAL.md) — built out **in-repo**, §8). This is the *method + architecture + order*; the **live coverage truth is the differential tests** (`adapter/…/laws/*DiffTest.kt`) plus a planned catalog-parity test, never a hand-maintained checklist here.

**Acceptance (both use-cases):**
1. **Native image** runs [`examples/cli-runtime.md`](../examples/cli-runtime.md) end-to-end (the `model eval` / `model compute` / `rule test` verbs, today `JVM_ONLY_VERBS`).
2. **Standalone** Kotlin/JVM + Kotlin/JS: full **and partial** validation, computations, and **single-rule / single-computation** eval over a document model.

Correctness throughout = **agrees with the real kernel** (the `RuntimeLaws` differential oracle).

## Session state & how to resume (2026-06-23, HEAD `beafd6a0`)

A long build-out session is paused here with a **clean tree** at the rich-message-model commit. Resume from this list.

**Done & committed (all green: `./build.sh :interpreter:check :adapter:test :cli:test` + `./scripts/verify-demos.sh`):**
- Engine grown out of the spike: `validateFull` · `testRule`/`evalCandidate` (FIRED/PASSED/SUPPRESSED) · `compute` (value/cleared, empty-as-0) · formal-error gate (DATE/NUMBER) · tolerant `validate` (unsupported constructs reported, not crashed) · general `Document` (indexed placements, common-prefix resolution, iteration scope from referenced repeatable fields).
- Operators (~24/109): comparisons, And/Or/Not, FieldFilled/FieldNotFilled/AllFieldsFilled, Min/Max/Sum/MaxValue/MinValue/NumberOfDifferentValues, **PatternMatched/PatternViolated**, arithmetic **`+ - *`** (`{}` grouping; `/`,`^` deferred), **DifferenceInDays**.
- **CLI engine selection:** `model eval`/`compute`, `rule test` default to the **interpreter**, `--kernel` opts into the kernel (JVM only; refused under the native profile — refusal fires at the top of each verb). Loader `InterpreterModelLoader` (`:cli` main) + `InterpreterEngine` render the verbs' `data`. `cli-runtime.md` regenerated to the interpreter default.
- **Native enablement (code only — NOT built):** `Cli.JVM_ONLY_VERBS` emptied (the runtime verbs are native-capable via the interpreter); guard test renamed `NativeImageRuntimeVerbsTest` (asserts the verbs are PRESENT in native + `--kernel` refused).
- **Rich message model (task #25):** `data.messages[]` matches the kernel's `IMessage` — `field` is a clean pointer `formatPointer` (path + index per group segment, e.g. `/Order[1]/Items[2]/Count`), plus `referenced` (operands) and `fillToFix` (`refOmissionErrorResponsible`). Both engines (interpreter native; `--kernel` via the JDK-only `FieldPointer` boundary type + the shared `formatPointer`). Locked by `RichMessageDiffTest`.
- **`$field$` display-name placeholder (task #26):** `renderMessage` now resolves `$field$` (not just `$field.value$`). Both engines wire the same **label-for-locale-else-name** resolver — the interpreter's `ModelDef.labelOf` (fed by `FieldDef.labels`, loaded from the Field node's `labels` array) and `KernelAdapter`'s `ILabelProvider` (reading `IField.getLabel()`/`getName()`). This **closes the `/Order/Quantity/1.1` path+index leak** the kernel emits with no provider (KERNEL-FINDINGS §3 supplement). No shipped A12 model populates field labels, so `$field$` → the field NAME in practice. Locked by `DisplayNameDiffTest` (the `RuntimeLaws` harness wires the matching provider, so the comparison is label-to-label).

**Next, in order (formal-error messages, then operators, then the native build):**
1. **Formal-error messages (task #27).** On `model eval`, emit the kernel's `formalePruefung` messages for malformed fields (the interpreter currently only *suppresses*). Needs the full formal-error code set + **localized text** — a separate German-keyed bundle tier in the kernel; byte-identical text likely needs bundling/porting the kernel's message resources, so it's best-effort (flag where exact text needs the bundles). Differential vs the kernel's formal-check messages.
2. **Operator coverage (task #19, ongoing — the big one).** ~85 remaining catalog operators, family by family, each `*DiffTest`-locked. Add a **catalog-parity test** so an unhandled operator fails the build. (This is large — consider a workflow/fan-out, or scope to what real models need first.)
3. **Native build (task #23, blocked on 1–2 to avoid repeated captures).** GraalVM 25 is installed (`GRAALVM_HOME` set). Steps: re-capture via `GRAALVM_HOME=… ./scripts/capture-native-config.sh` (the `:cli:test` suite now exercises the interpreter path + `DocInstance`/`InterpreterModelLoader`, so the test-suite-driven capture covers it), then `./gradlew :cli:nativeCompile`, then `./scripts/native-gate.sh` with **`cli-runtime.md` un-skipped** in `NATIVE_SKIP` (it should now PASS via the interpreter — native output == JVM interpreter output). Hypothesis (NATIVE-IMAGE-GRAALVM §1/§3.1/§4): the build already compiles these verbs in (Groovy is dynamically-loaded, not statically reachable, and excluded from the native classpath); the default interpreter path touches no Groovy and `--kernel` is refused before any kernel call — so they should go native cleanly. The genuine unknown is a reflection/resource gap in the interpreter/Jackson path, which the re-capture + `native-battery.sh` are meant to catch.

**Key findings this session (durable):**
- The kernel's `IMessage` carries **7 channels**; dmtool's adapter previously dropped `referencedFields` + `refOmissionErrorResponsible` and stringified the pointer via `String.valueOf` (the `PartiallyKnownDocumentMultiPointerImpl[…]` leak — a debug `toString`, never meant for output; the kernel team's own demo renders pointers via `fullName()`/`repetitionIndexes()`).
- **`refOmissionErrorResponsible` = ALL the omission error's operands** (verified for And *and* Or: both the filled and the empty operand), not just the unfilled ones — hence `fillToFix = referenced` for an OMISSION, empty for a VALUE error.
- **Locale selection is correct as exact-key match given the API contract** (the locale is an explicit API input; pass a declared code → that text). The kernel's resolution/fallback (e.g. `de_DE`→`de`) is only a leniency for mismatched-form requests — not a fidelity gap under "pass the declared code."
- Two loader bugs the demo regeneration caught + fixed: an empty group object `{}` now instantiates a group row (empty-operand compute), and an absolute path `[/Group/Field]` in a condition is used as-is.

### How to warm up and continue (post-compaction playbook)

A cold agent picking this up should do these **in order**.

**1 — Read, to rebuild the mental model (≈15 min):**
1. **This doc**, top to bottom — the plan, the session-state list above, the API design (below), the loader field-mapping (below).
2. [INTERPRETER-PROPOSAL.md](INTERPRETER-PROPOSAL.md) — §2 (interpret-not-codegen), §6/§7 (owned-vs-delegated + the four standalone hygiene rules), §8 (built in-repo; separation by purity lint), §10 (spike result + build-out progress), §11 (open decisions), §12 (full+partial in scope).
3. The **engine** (all `interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/`): `Ast.kt`, `Interpreter.kt`, `DmInterpreter.kt` (the public API + per-row evaluation + iteration scope + the rich-message build), `Document.kt`, `Model.kt` (the data types), `Parser.kt`, `Pointer.kt`, `EvalContext.kt`, `FormalError.kt`, `DateMath.kt`, `Message.kt` (the `$…$` renderer — the next edit lands here).
4. The **CLI wiring**: `cli/.../interp/InterpreterModelLoader.java` (DM-JSON→`ModelDef`, instance→`Document`) + `InterpreterEngine.java` (renders the verbs' `data`); `cli/.../model/ModelEvalCommand.java` + `ModelComputeCommand.java`, `cli/.../rule/RuleTestCommand.java` (the `--kernel` branch + the engine default); `cli/.../Cli.java` (`JVM_ONLY_VERBS`, `isNativeImage`).
5. The **differential tests = the live coverage truth**: `adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/laws/*DiffTest.kt` (`InterpreterDiffTest`, `ParserDiffTest`, `PolarityDiffTest`, `AggPolarityDiffTest`, `RepetitionDiffTest`, `LocalizedMessageDiffTest`, `PatternDiffTest`, `ArithmeticDiffTest`, `DifferenceInDaysDiffTest`, `SuppressionDiffTest`, `ValidateFullDiffTest`, `TestRuleDiffTest`, `ComputeDiffTest`, `RichMessageDiffTest`) + the oracle `adapter/.../laws/RuntimeLaws.java`; and `cli/.../interp/InterpreterRealModelDiffTest.java` (loader vs live kernel over real models).
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
- **Build ONLY via `./build.sh <args>`** (isolated `GRADLE_USER_HOME` + JDK 21). A bare `./gradlew` gets contaminated by the machine's private registry and fails confusingly.
- **`:interpreter` is kernel-free AND dependency-free** — the `verifyInterpreterPurity` lint fails the build on a forbidden import. Kernel-differential tests live in **`:adapter`** (`testImplementation project(':interpreter')`), never in `:interpreter`. The decimal/date shims are `expect/actual` (`Dec`); the rest is `commonMain`.
- **Every new operator / behavior gets a `*DiffTest` vs the real kernel** (`RuntimeLaws`) — the differential is the spec, not hand-asserted expectations.
- Kotlin **`*/` in KDoc** breaks compilation (write `Items* / Count`). The `expect/actual class` beta warning is harmless.
- Shell: `rm` is aliased to `rm -i` → use **`/bin/rm`**; zsh doesn't word-split unquoted vars.
- **Commit per feature** (Conventional Commits, subject-only), **auto-commit without asking**, **commit on `main` (never branch)**, **never offer to push**.

**4 — Do the next task (`$field$`, #26) — concrete steps:**
1. `FieldDef` (Model.kt): add `labels: Map<String, String> = emptyMap()` (locale→label); add a `ModelDef.labelOf(path, locale)` helper.
2. `InterpreterModelLoader.load` (cli): read each `Field` node's `label` array → `labels` (mirror the rule `messages` reader).
3. `renderMessage` (Message.kt): add a **name pass** after the value pass — `$relpath$` → the field's label for the locale, **falling back to the field name** (last path segment) when no label. Signature gains a label resolver; `DmInterpreter` passes `{ path -> model.labelOf(path, locale) }`. Update the one direct call in `InterpreterMultiplatformTest`.
4. Lock it: a differential — inject a rule whose message is `"$Quantity$ is $Quantity.value$ …"`, render via the kernel (`RuntimeLaws.errorTextOf`/the message text) and via the interpreter, assert equal. Cover **unlabeled** (order-ruled → kernel returns the name; verify) and, if a labeled fixture is available, **labeled**.
5. `./build.sh :interpreter:check :adapter:test`; update the build-out doc's Messages row; **commit** `feat(interpreter): $field$ display-name placeholder`.

Then **#27 (formal-error messages)** and **#19 (operator coverage)** per the prioritized order, each its own `*DiffTest` + commit. Finally **#23 (native build)** per step 4 of the session-state list — re-capture, `:cli:nativeCompile`, `native-gate.sh` with `cli-runtime.md` un-skipped.

**Differential-test recipe (the pattern every increment follows):** add a `…DiffTest.kt` in `adapter/.../laws/`; load a fixture model (`RuntimeLaws.model("models/order.dm.json")`); inject the rule (`RuntimeLaws.withRule(...)` / `.withComputation(...)`); build the instance two ways from one spec — `RuntimeLaws.doc()…` for the kernel and `Document(kinds)…` / `SingleRowDoc`/`RepDoc` for the interpreter; assert `interpreter == kernel` (fired codes, verdict, type, the rich-message channels). Drive the interpreter from **parsed condition text** (`parseCondition`) where possible, not a hand-built AST.

## Current state (what the engine does today)

| area | today | gap to full-blown |
|---|---|---|
| **Conditions** | tree-walk over `Cond`/`Expr`; 3-valued logic; type-driven empty asymmetry; polarity (VALUE/OMISSION incl. omitted-tail) | — (solid foundation) |
| **Operators** | ~20 of 109 constructs (see below) | ~89 constructs across ~8 families |
| **Computations** | `compute()` → VALUE / CLEARED, kernel-verified (incl. empty-as-0 → `0`) | the ERRORED bucket (operand/computation error) + pre-conditions; awaits the formal-error increment |
| **Formal errors / suppression** | DATE (`datumFormatFalsch`) + NUMBER (`fuehrendesBlank`/`zahlHatUngueltigeZeichen`) gate → rule **suppressed**; `rule test` SUPPRESSED verdict + `suppressedBy`, kernel-verified | scale check (`zahlFalscheAnzahlNachkommastellen`, needs per-field scale) + the other formal codes + the German message-bundle tier |
| **Document model** | general `Document` (indexed placements, common-prefix resolution, star aggregation) — flat + nested-non-repeatable + one-level repetition, kernel-verified; spike's `SingleRowDoc`/`RepDoc` still present (consolidation pending) | multi-level repetition (resolution is general; only one level is kernel-tested) |
| **Model / instance loading** | structured `ModelDef`/`RuleDef`/`FieldDef` (interpreter owns its types); the consumer builds them | DM-JSON model → `ModelDef`; `{"fields":{…}}` instance → `Document` (dmtool via Jackson; a multiplatform JSON loader later) |
| **Public API** | `DmInterpreter.validateFull(doc)` — whole-document, kernel-verified | `validatePart` / `compute` / `testRule` / candidate-eval entry points |
| **Messages** | `$field.value$` value-interpolation **and `$field$` display-name** (label-for-locale-else-name, both engines), en/de; **rich message model** — structured field pointer (path + repetition indices), `referenced` operands, `fillToFix` (refOmissionErrorResponsible), matching the kernel's `IMessage` channels on both engines | formal-error (German-keyed) bundles |
| **Parser** | recursive-descent subset (comparisons, And/Or, predicates, aggregates, patterns, paths/`*`) | full grammar (or kernel parse-tree-api) — §11 |

## Two tracks

The build-out splits cleanly, and the two are **largely independent**:

- **Track A — use-case-1 critical path (structural).** What `cli-runtime.md` needs is *depth*, not breadth: a **model loader**, an **instance loader**, a **general doc model**, **computations**, **formal-error/suppression**, the **public API**, and the **dmtool wiring**. `cli-runtime.md` itself only uses comparisons on numbers + dates (already supported) — so the operator set is *not* its blocker; the structure is.
- **Track B — use-case-2 breadth (operators).** The ~90 missing catalog operators, each added with a `*DiffTest` against the kernel. Needed for standalone "full" parity, not for the cli-runtime acceptance.

## Operator coverage snapshot

*Snapshot only — the diff tests are the truth.* Handled (~24): `And` `Or` `Not` · `LessThan` `LessThanOrEqual` `GreaterThan` `GreaterThanOrEqual` `Equal` `NotEqual` · `FieldFilled` `FieldNotFilled` `AllFieldsFilled` · `Min` `Max` `Sum` `MaxValue` `MinValue` `NumberOfDifferentValues` · `PatternMatched` `PatternViolated` · `DifferenceInDays` · arithmetic `+ - *` (`{ }` grouping; `/` `^` deferred). An unmodeled/external construct (e.g. `CustomCondition`) is *tolerated* by `validate` (reported `unsupported`), not a crash.

Missing families (~90), by group:

| family | examples | notes |
|---|---|---|
| **filled-predicates** (rest) | `GroupFilled` `GroupNotFilled` `AllGroupsFilled` `AtLeastOneFieldFilled` `NoFieldFilled` `NotAllFieldsFilled` `MoreThanOneFieldFilled` `NotExactlyOneFieldFilled` `FieldsNotCollectivelyFilled` `NumberOfFilledFields` `NumberOfFilledGroups` … (~15) | `GroupFilled`/`GroupNotFilled` are *already parsed* but error in `evalPred` — a partial-support bug to close first |
| **value-list / enum** | `FieldValueIncludedInValueList` `FieldValueNotIncludedInValueList` `AtLeastOne…` `No…` `NotAll…` `EnumCategory` (~6) | membership against a declared value list |
| **pattern** | `PatternMatched` `PatternViolated` (2) | regex on string fields |
| **arithmetic** | `Arithmetic` `Abs`/`AbsValue` `RoundUp/Down/Accounting`(+`Value`) `SumOfProducts` `Length` `RangeAsNumber/String` `FieldValueAsNumber/AsString` `NumberOfValueInFields` (~15) | numeric expressions/coercions |
| **date / time** | `Today` `Now` `Date` `DateTime` `Time` `AddDays/…/Years` `DifferenceIn…` `DayFromDate` `MonthFromDate` `YearFromDate` `QuarterFromDate` `FirstDay` `LastDay` `BaseYear` `HoursFromTime` … (~30) | the largest family; needs the `java.time`⟷luxon shim (§5) |
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
