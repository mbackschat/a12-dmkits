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

**Next, in order (the user wants all three message-fidelity items, then operators, then the native build):**
1. **`$field$` display-name placeholder (task #26 — JUST STARTED, reverted).** First step (was in progress): add `labels: Map<String,String> = emptyMap()` to `FieldDef`; have `InterpreterModelLoader` read each Field node's `label` array; extend `renderMessage` (Message.kt) with a name pass — `$relpath$` → the field's label for the locale, **falling back to the field name** (last path segment) when unlabeled (order-ruled fields are unlabeled — verify the kernel returns the name there). Update the `renderMessage` call in `InterpreterMultiplatformTest` (it'll need the label resolver arg). Lock with a differential: a rule message `"$Quantity$ is $Quantity.value$"` rendered → matches the kernel's `getErrorText`. Kernel fact: `$field$` = `identifierToString` (label, or name when no label); `$field.value$` = value (`getValueForDisplay`) — both keep the `$`; same placeholder across locales (KERNEL-FINDINGS line 78).
2. **Formal-error messages (task #27).** On `model eval`, emit the kernel's `formalePruefung` messages for malformed fields (the interpreter currently only *suppresses*). Needs the full formal-error code set + **localized text** — a separate German-keyed bundle tier in the kernel; byte-identical text likely needs bundling/porting the kernel's message resources, so it's best-effort (flag where exact text needs the bundles). Differential vs the kernel's formal-check messages.
3. **Operator coverage (task #19, ongoing — the big one).** ~85 remaining catalog operators, family by family, each `*DiffTest`-locked. Add a **catalog-parity test** so an unhandled operator fails the build. (This is large — consider a workflow/fan-out, or scope to what real models need first.)
4. **Native build (task #23, blocked on 1–3 to avoid repeated captures).** GraalVM 25 is installed (`GRAALVM_HOME` set). Steps: `./scripts/gen-native-reflect-metadata.sh`? no — re-capture via `GRAALVM_HOME=… ./scripts/capture-native-config.sh` (the `:cli:test` suite now exercises the interpreter path + `DocInstance`/`InterpreterModelLoader`, so the test-suite-driven capture covers it), then `./gradlew :cli:nativeCompile`, then `./scripts/native-gate.sh` with **`cli-runtime.md` un-skipped** in `NATIVE_SKIP` (it should now PASS via the interpreter — native output == JVM interpreter output). Hypothesis (NATIVE-IMAGE-GRAALVM §1/§3.1/§4): the build already compiles these verbs in (Groovy is dynamically-loaded, not statically reachable, and excluded from the native classpath); the default interpreter path touches no Groovy and `--kernel` is refused before any kernel call — so they should go native cleanly. The genuine unknown is a reflection/resource gap in the interpreter/Jackson path, which the re-capture + `native-battery.sh` are meant to catch.

**Key findings this session (durable):**
- The kernel's `IMessage` carries **7 channels**; dmtool's adapter previously dropped `referencedFields` + `refOmissionErrorResponsible` and stringified the pointer via `String.valueOf` (the `PartiallyKnownDocumentMultiPointerImpl[…]` leak — a debug `toString`, never meant for output; the kernel team's own demo renders pointers via `fullName()`/`repetitionIndexes()`).
- **`refOmissionErrorResponsible` = ALL the omission error's operands** (verified for And *and* Or: both the filled and the empty operand), not just the unfilled ones — hence `fillToFix = referenced` for an OMISSION, empty for a VALUE error.
- **Locale selection is correct as exact-key match given the API contract** (the locale is an explicit API input; pass a declared code → that text). The kernel's resolution/fallback (e.g. `de_DE`→`de`) is only a leniency for mismatched-form requests — not a fidelity gap under "pass the declared code."
- Two loader bugs the demo regeneration caught + fixed: an empty group object `{}` now instantiates a group row (empty-operand compute), and an absolute path `[/Group/Field]` in a condition is used as-is.

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
| **Messages** | `$field.value$` value-interpolation, en/de (wired into `validateFull`); **rich message model** — structured field pointer (path + repetition indices), `referenced` operands, `fillToFix` (refOmissionErrorResponsible), matching the kernel's `IMessage` channels on both engines | `$field$` display-name tier; formal-error (German-keyed) bundles |
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
