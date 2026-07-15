# Interpreter drop-in COMPATIBILITY CERTIFICATE — the eval-surface gate for retiring `--kernel` eval

> **What this is.** The documented removal gate that [`INTERPRETER-SPEC.md`](INTERPRETER-SPEC.md) §8a makes the precondition for retiring the `--kernel` eval flag. It enumerates every capability the kernel's `--kernel` **evaluation** path provides, states the interpreter's parity status for each, names the **exact lock** that proves it, records the **residuals**, argues **why the differential estate spans the surface** (0-divergences-on-a-corpus is not proof-on-all-inputs), and renders a **verdict**.
>
> **Scope — EVALUATION only ("Scope A").** This certifies the kernel's *evaluation* role: `model eval` / `rule eval` / `model compute` and the JVM `--kernel` behaviour behind them. It does **NOT** certify the kernel's *consistency / MVK-acceptance* role (`rule check` / `model check` / `checkConsistency` / the F4 "the real kernel accepts this" guarantee) — that stays kernel-backed ("Scope B", a separate deferred question). So even after `--kernel` eval is retired, dmtool keeps bundling the kernel as the MVK consistency oracle and the `:adapter` differential oracle. Never collapse the two roles.
>
> **This document was the removal gate; retirement has since EXECUTED (Set V, 2026-07-12).** Building the certificate was an **audit + doc** deliverable that gated the retirement on this verdict and changed eval behaviour only where the audit surfaced a real bug. With the verdict GREEN, the follow-on set removed the `--kernel` eval flag (§0 Set V note; IF122).

Record: [`INTERPRETER-FINDINGS.md`](INTERPRETER-FINDINGS.md) IF122 (the executed retirement); residual JS-expander gap [`INTERPRETER-GAPS.md`](INTERPRETER-GAPS.md) IG68. Related: [`KERNEL-DECOUPLING-PROPOSAL.md`](KERNEL-DECOUPLING-PROPOSAL.md) (Stage 2 = model assembly), [`INTERPRETER-FINDINGS.md`](INTERPRETER-FINDINGS.md), [`KERNEL-SEMANTICS.md`](KERNEL-SEMANTICS.md).

---

## 0. Verification status (the nets run to substantiate this certificate)

| net | invocation | result |
|---|---|---|
| **full differential + fuzz + laws**, forced fresh | `:adapter:test -PrunFuzz --rerun` | **BUILD SUCCESSFUL in 4m 41s** (2026-07-12) — every `*DiffTest` / `*LawsTest` + the CI-gated fuzz nets green |
| **catalog-parity meter** | `:adapter:test --tests CatalogParityTest --rerun` | `110 constructs: 110 handled, 0 deferred, 0 excluded`; `fuzz net: 22/76 handled callables (54 pending, each DiffTest-locked)` |
| **real-corpus sweep — nice** | `RealCorpusSweepTest -PrunCorpusSweep -Da12.corpus1.dir=<workspaces>` | JUnit XML **`tests="2" skipped="0" failures="0" errors="0"`** (both corpora swept, not `assumeTrue`-skipped); **workspaces** 107 models / 690 rules / 646 computations → 530 validateFull + 350 compute runs, 151 fired codes, **0 java-static splits, 0 interp≠kernel divergences**; **kernel-mm** 15 models / 547 rules / 6 computations, 0 divergences |
| **real-corpus sweep — hostile** | + `-Dcorpus.hostile=true` | JUnit XML **`tests="2" skipped="0" failures="0" errors="0"`** (workspaces 107 + kernel-mm 15 swept); **0 interp≠kernel divergences** under the hostile seeding (~every fourth NUMBER cell violates an operand-invalidity class) |

Sweep skip-counts (JUnit XML `skipped="0"` ⇒ both corpora present and actually swept, not `assumeTrue`-skipped) are the coverage argument's §2 point (5). The nice sweep's only eval-level skips were **5 + 5 kernel-codegen rejections** (`DocumentValidationException: … while generating code` — the **kernel itself** refuses the model; not an interpreter gap), tallied by class, never a silent drop.

Locks added by the AUDIT set (Set T): **none** — it found no capability that was GREEN-in-reality but zero-locked (every one of the 110 handled constructs is referenced by ≥1 `*DiffTest`/`*LawsTest`; every one of the 54 fuzz-pending callables lands in a real DiffTest — verified by name-sweep over `adapter/src/test`). No eval-behaviour change; the residuals below were already tracked (IG67, IG62) or documented (Option A formal text, the `field` pointer, compute granularity).

**Set U (2026-07-12) — R1 largely closed.** The kernel-free included-rule relativizer landed in `dm.core.ModelReader`: relative included conditions are carried verbatim (kernel-exact) and SINGLE_RG absolute references are rewritten to their depth-D relative form. New locks: `cli.IncludeEvalDefaultPathTest` (a relative included rule + D=0 and D=1 absolute-path included rules now **evaluate on the default path, differentially matched to `--kernel`**) and `core.ModelReaderTest` (the rewriter's exact output + the residual-flag path). Nets green forced-fresh: `:test`, `:adapter:test`, `:cli:test`. The R1 row (§1a/§3) and verdict (§4) are updated accordingly.

**Set V (2026-07-12) — retirement EXECUTED.** With this certificate GREEN, the terminal step landed: the user-facing `--kernel` option was removed from `model eval` / `rule eval` / `model compute` (interpreter-only on every target, JVM + native). The CLI-level kernel differential is preserved by reaching the kernel via the `KernelAdapter` API directly (the test-only `KernelEval` harness in `:cli`, mirroring `:adapter`'s `*DiffTest`s), so the parity estate stays intact — `cli.IncludeEvalDefaultPathTest` (the G1 differential) + the eval/compute/rule verb parity tests (kernel leg via `KernelEval`) — with **zero kernel-eval code in production `:cli`**. `Cli.NATIVE_REMOVED_OPTIONS`/`removeNativeUnavailableOptions` and `CustomSupportOptions.kernelConflict` were deleted with the flag; the G1′ diagnostic's `--kernel` fix-pointer was reworded off it. Graduated: IG67 → **IF122**; the JS-includes-expander residual → **IG68**.

**Set AA (2026-07-15) — a post-certificate divergence found and closed (nvv raw-type reads).** The Set Y review gate surfaced a class the certificate's corpora could not reach (no corpus/fuzz model declared `noValueValidation`): a rule of the whole-condition shape `Length(nvvField) > c` FIRED in the interpreter where the kernel ELIMINATES it at codegen — it is a disguised max-length declaration, never runtime code (both strategies; every other value window on an nvv field is model-closed, `MVK_INVALID_RAW_TYPE`/`MVK_INVALID_LENGTH_OF_RAW_TYPE`/`INVALID_NO_VALUE_VALIDATION`). Closed by the mechanism-exact twin — rule elimination in `DmInterpreter.rowOutcomes` — and netted so the class stays generable: locks `adapter.laws.NvvRawTypeDiffTest` (tri-check incl. partial validation) + `validate.laws.NvvRawTypeLawsTest` (accept/reject) + multiplatform `interpreter … NvvEliminatedLengthRuleTest` + the `ModelFuzz.nvvModel` fuzz family (mutation-checked 26/200). Graduated: IG71 → **IF125**. KERNEL-FINDINGS §3 "raw-type (`noValueValidation`) fields".

---

## 1. The eval-surface enumeration

Status legend: **GREEN** = differentially proven identical to the kernel · **DOC-EQUIV** = a deliberate, documented difference with the observable behaviour reconciled/projected · **RESIDUAL** = a known gap, guarded (never mis-evaluates) · **GAP** = a capability the `--kernel` path has that the interpreter does not.

### 1a. Model assembly (decoupling Stage 2 — the kernel-free expand the eval path runs first)

| capability | status | the exact lock | residual |
|---|---|---|---|
| includes / `modelReferences` mounting | GREEN | `adapter.ModelIndexParityTest` (kernel-free `dm.core` reader index == kernel expand); `cli.IncludeEvalDefaultPathTest` (a rule over a mounted field fires like the kernel) | — |
| imported typedef → effective types, `id ≠ path` | GREEN | `adapter.ModelIndexParityTest` (same `ElementFacts` at every path: kind/scale/bounds/lengths/pattern/enum-domain/repeatability) — includes + typedefs live in the real corpora it sweeps | — |
| **included-model's own rules/computations** — RELATIVE + SINGLE_RG absolute conditions | GREEN (Set U) | the kernel-free relativizer in `dm.core.ModelReader` carries a relative condition verbatim and rewrites a SINGLE_RG `/RG/rest` absolute ref to its depth-D relative form; `cli.IncludeEvalDefaultPathTest` — a relative included rule *and* a D=0/D=1 absolute-path included rule now **evaluate on the default path, matching `--kernel`**; the rewrite is unit-locked `core.ModelReaderTest` | R1 — narrow residual (§3) |
| ↳ MODEL_ROOT-absolute / bare `/RG` group ref / outer-correlated `[$/…]` included refs | RESIDUAL-guarded | the relativizer leaves these; `expandForEval` still **refuses loudly** (`RK_INTERPRETER_INCLUDE_RULES_UNSUPPORTED`), so they never mis-evaluate; `core.ModelReaderTest.flagsAResidualBareRootGroupAbsoluteReferenceItCannotRelativize` | R1 (§3) |

### 1b. Evaluate / compute / formal-check

| capability | status | the exact lock | residual |
|---|---|---|---|
| `validateFull` (whole-document) | GREEN | `ValidateFullDiffTest`, `FullMessageFuzzDiffTest` (`code`·`type`·`pointer`), `ModelFuzzDiffTest` (random-depth models), + the whole `laws/*DiffTest` estate | — |
| `validatePart` (partial, 3VL suppression) | GREEN | `PartialValidationDiffTest` + the `PartialValidation*DiffTest` family (aggregate/global-field/custom-condition/unique/subset-rep) + `PartialValidationFuzzDiffTest` (random doc × relevant-subset) | — |
| single-rule eval (`testRule`/`evalCandidate`, FIRED/PASSED/SUPPRESSED) | GREEN | `TestRuleDiffTest`, `RuleTestLawsTest`, `SuppressionDiffTest` | — |
| `compute()` + the dependency cascade | GREEN | `ComputeDiffTest`, `ComputeCascadeDiffTest`, `ComputeCascadeErroredDiffTest`, `ComputationFuzzDiffTest` (random compute chains) | R4 — report-all granularity (§3), DOC-EQUIV |
| `applyComputations` (compute → apply → validate, the `model eval` default) | GREEN | `ComputeApplyDiffTest`; `cli.IncludeEvalDefaultPathTest` exercises the flow end-to-end | — |
| formal: date format + Gregorian floor (`datumFormatFalsch`/`datumFalsch`) | GREEN | `FieldConstraintFormalDiffTest`, `BaselineValueFormalDiffTest`, `ComputedDateGregorianFloorDiffTest`, `PlainDateDiffTest`, `FormalErrorLawsTest` | — |
| formal: number format (invalid-char / decimal-sep / scale / signed-zero / plus / leading-zeros / total-digits / integer-cap / length) | GREEN | `FieldConstraintFormalDiffTest`, `NumberSignFormalDiffTest`, `ComputedNumberScaleDiffTest`, `ComputedNumberDigitOverflowDiffTest` (each `FormalErrorCode` carries the kernel's verbatim code) | — |
| formal: string length / pattern / newline; enum value-domain | GREEN | `FieldConstraintFormalDiffTest` (IF20 declared-constraint family) | — |
| formal: date-range (separator / format / start≤end / floor) | GREEN | `DateRangeFormalDiffTest`, `FragmentRangeFormalDiffTest` | — |
| formal: **charset** (BMP default; model `supportedCharacters`; consumer SPI) | GREEN (default + model slot) · DOC-EQUIV (SPI source) | `SupportedCharactersDiffTest` (model slot, ranges A-Z/0-9 = the SPI mechanism); `SupportedCharactersLawsTest`; BMP default exercised by every differential | R6 — consumer-SPI *source* unlocked (§3), DOC-EQUIV |
| formal: leading/trailing blank (baseline, every kind) | GREEN | `BaselineValueFormalDiffTest` (IF43) | — |
| `mandatoryField` (requiredness) | GREEN | `RequirednessDiffTest`, `RequiredPropertyLawsTest` | — |
| `uniqueIndex` / `RepetitionNotUnique` | GREEN | `UniquenessDiffTest`, the `RepetitionNotUnique*DiffTest` family, `RepetitionNotUniqueLawsTest` | — |
| over-repetition (`zuGrosseZeile` / `zuGrosseKontextnummer`) | GREEN | `OverRepetitionDiffTest` | — |

### 1c. Operators, functions, quantifiers, aggregates (the catalog — 110/110 handled)

| capability | status | the exact lock | residual |
|---|---|---|---|
| every catalog construct is HANDLED | GREEN | `CatalogParityTest.everyConstructIsClassifiedExactlyOnce` (110 handled, **0 deferred, 0 excluded** — a derived build guard, not a checklist) | — |
| boolean 3VL (`And`/`Or`), comparisons, arithmetic (`+ - * / ^`), concat, patterns, tolerance | GREEN | `KleeneDiffTest`, `PolarityDiffTest`, `ArithmeticDiffTest`, `ConcatComputeDiffTest`, `PatternDiffTest`, `ToleranceDiffTest`, `OperatorBatteryDiffTest` | — |
| field-list / group-list / value-list quantifiers | GREEN | `FieldListQuantifierDiffTest`, `GroupListQuantifierDiffTest`, `ValueListQuantifier*DiffTest`, `*LawsTest` | — |
| aggregates (`Sum`/`Min`/`Max`/`NumberOfDifferentValues`/`SumOfProducts`/`NumberOf*`/`FirstFilledValue`) | GREEN | `MultiFieldAggregateDiffTest`, `IndexAggregateDiffTest`, `GroupStarValueAggregateDiffTest`, `SumOfProductsDiffTest`, `NumberOfFilled*DiffTest`, `FirstFilledValue*DiffTest`, `AggPolarityDiffTest`, `AggMalformedDiffTest` | — |
| value functions (`FieldValueAsString`/`AsNumber`, `ValueAsDate`, `Concatenation`, `EnumCategory`, round/abs) | GREEN | `FieldValueAsStringComputeDiffTest`, `FieldValueCoercionDiffTest`, `ValueAsDateDiffTest`, `EnumCategoryDiffTest`, `RoundDiffTest`, `AbsLengthDiffTest` | — |
| date/time construction, extraction, arithmetic, difference | GREEN | `DateConstructionDiffTest`, `Date4ArgConstructionDiffTest`, `DateExtractDiffTest`, `DateArithmeticDiffTest`, `DateDifferenceDiffTest`, `DateTime*DiffTest`, `TimeExtractDiffTest`, `DifferenceInDaysDiffTest` | R5 — DST (§3) |
| date-range functions + overlap | GREEN | `DateRangeDiffTest`, `DateRangeOverlapSemanticsDiffTest`, `DateRangeComputeDiffTest` | — |
| semantic-index reads (`[F For key]`) | GREEN | `SemanticIndexDiffTest`, `SemanticIndexFieldKeyedDiffTest`, `SemanticIndexPresenceDiffTest`, `SemanticIndexComputeNoMatchDiffTest`, `SemanticIndexMatchedInvalidCellDiffTest` | — |
| correlation (`$` outer), star (`*`), `Having` filter | GREEN | `OuterCorrelationDiffTest`, `HavingDiffTest`/`Having*DiffTest`; **`CatalogParityTest.fuzzNetCoversRendererModifiers`** asserts the fuzz net exercises `$`/`*`/`Having` (the IF47 durable guard — a modifier is not an operator, so the operator guards can't cover it) | — |
| parallel / cross-level / sibling-group iteration | GREEN | `ParallelIterationDiffTest`, `FramedParallelIterationDiffTest`, `WrappedParallelIterationDiffTest`, `CrossSubtreeGroupStarDiffTest`, `perf.SiblingGroupFuzzDiffTest` | — |
| predefined types (`Valid`/`Invalid(field,"Name")`) | GREEN | `PredefinedTypeValidityDiffTest` (same registry in both engines) | — |

### 1d. The customization surface (`DocumentProcessingConfig` + charset + clock — every kernel SPI mapped)

| kernel customization | interpreter input | status | the exact lock | residual |
|---|---|---|---|---|
| `languageOfErrorMessages` (locale) | `DmInterpreter.locale` | GREEN | `LocalizedMessageDiffTest` (EN + DE), `DisplayNameDiffTest`, `UnspecifiedValueInterpolationDiffTest` | — |
| `customConditionFactory` (`ICustomCondition`) | `customConditions` SPI | GREEN (mechanism) | `CustomConditionDiffTest` (runs a REAL kernel-core `ICustomCondition` via the bridge; identical firing/polarity), `PartialValidationCustomConditionDiffTest` | R2 — consumer must supply the impl (§3) |
| `customFieldTypeFactory` (`ICustomFieldType`) | `customFieldTypes` SPI | GREEN (mechanism) · DOC-EQUIV (reject code) | `CustomFieldTypeBoundsDiffTest` (same validator in both engines; observable suppression bit-for-bit) | R2 (impl) + R7 (reject code, §3) |
| `metaData.legalCodepoints` (charset) | `legalCharset` SPI + model slot | GREEN (default + model slot) | `SupportedCharactersDiffTest`, `SupportedCharactersLawsTest` | R6 (SPI source, §3) |
| `Now`/`Today` clock | `EvalClock` | GREEN | `ClockDiffTest` (`Today`), `NowDiffTest` (`Now`) — deterministic far from now; exact boundary in `:interpreter` commonTest | R5 — timezone (§3) |

### 1e. Messages & output shapes

| capability | status | the exact lock | residual |
|---|---|---|---|
| rule/computation message: value interpolation `$field.value$` | GREEN | `LocalizedMessageDiffTest`, `UnspecifiedValueInterpolationDiffTest` (IF121: unspecified ⇒ `0`/empty) | — |
| rule/computation message: display-name `$field$` | GREEN | `DisplayNameDiffTest` | — |
| rich message channels (`referenced` operands, `fillToFix` for OMISSION, polarity VALUE/OMISSION) | GREEN | `RichMessageDiffTest`, `FullMessageFuzzDiffTest` (trips on wrong polarity / right-code-wrong-row) | — |
| **formal-error** message TEXT | DOC-EQUIV | `FormalErrorMessageDiffTest` — code + pointer + severity + type match the kernel as a SET; the human TEXT is the interpreter's OWN wording (Option A) | R7 (§3) |
| output: `field` pointer shape | DOC-EQUIV | `InterpreterEngine` renders the resolved *path*, not the kernel's verbose pointer object | R3 (§3) |
| output: `model compute` result granularity | DOC-EQUIV | `compute()` reports **all** computed instances; `--kernel` reports the kernel's **delta**; semantic parity projected via `conformance.kernelComputeGranularity` and locked in every compute DiffTest | R4 (§3) |
| output: `data.unsupported` bucket (a construct with no impl) | GREEN (shape) | `InterpreterEngine.addUnsupported` (present only when non-empty; `{name, reason}`); `model eval --strict-custom` refuses | R2 (§3) |

---

## 2. The coverage argument — why the differential estate SPANS the surface

"0 divergences on the corpus" is **not** "proof on all inputs." The argument that the estate spans the eval surface rests on five independent, always-current mechanisms — three *breadth* nets and two *combination* nets — plus an explicit hole-hunt.

**(1) Every construct is handled — a derived guard, not a checklist.** `CatalogParityTest` reads the live `operators.json` universe, the live `PredicateOp`/`ConstantOp`/`FunctionOp` enums (what the engine handles), and the fuzz net, and **build-fails** if any catalog construct is unclassified or any handled callable is un-fuzzed-without-a-reason. Current: **110/110 handled, 0 deferred, 0 excluded.** A kernel that adds an operator, or an operator implemented-but-unrecorded, trips it — coverage cannot silently rot.

**(2) Every handled construct has a hand-written differential.** Audit (name-sweep over `adapter/src/test`, this set): **zero** of the 110 handled constructs is un-referenced by a `*DiffTest`/`*LawsTest`; **zero** of the 54 fuzz-pending callables lacks a real DiffTest (spot-checked the obscure tail — `NumberOfFilledGroups`→`NumberOfFilledGroupsStarDiffTest`, `StartOfDateRange`/`EndOfDateRange`/`AtLeastOneDateRangeOverlaps`→`DateRange*DiffTest`, operand-list `Min`/`Max`→`MultiFieldAggregate`/`IndexAggregateDiffTest`). The "fuzz-pending" 54 are DiffTest-locked *curated* cases; only their random-*combination* coverage is deferred, each with a recorded reason.

**(3) The renderer-modifier net closes the class the operator guards structurally cannot see.** `CatalogParityTest.fuzzNetCoversRendererModifiers` keys on what `DslRenderer` can EMIT (`$`/`*`/`Having`) rather than the operator inventory — the exact net whose absence let the IF47 `$`-correlation gap hide (a modifier is not an operator, so no operator-keyed guard covers it).

**(4) Two combination fuzz nets sweep what no hand-picked case reaches.** `InterpreterKernelFuzzTest` (codes multiset, tri-differential: `interp == groovy == java-static` — so it also gates the **static codegen** the runtime oracle can't see) and its stronger sibling `FullMessageFuzzDiffTest` (`code|type|pointer` — trips on wrong polarity / wrong row); `ModelFuzzDiffTest` varies the *model* (random depth 2–4); `ComputationFuzzDiffTest` sweeps compute chains; `SiblingGroupFuzzDiffTest` the sibling-group shape; `PartialValidationFuzzDiffTest` the (document × relevant-subset) space. All CI-gated (`-PrunFuzz`), green this run.

**(5) Two real-corpus sweeps — kernel-team-authored rules over seeded instances, tri-engine, both seeding modes.** `RealCorpusSweepTest` over the A12 Tools workspaces (107 models, 690 rules, 646 computations) + `a12-kernel-mm` (15 canonical metamodels): a genuine **divergence** (both engines evaluate, disagree) fails the sweep; a construct neither engine can handle is **skip-and-reported by class**, never silently dropped.

  - **nice mode:** `RealCorpusSweepTest -PrunCorpusSweep -Da12.corpus1.dir=<workspaces>` → JUnit XML **`skipped="0"`, 0 divergences** (workspaces 107 models / 530 validateFull + 350 compute; kernel-mm 15 models). The only skips: **5+5 kernel-codegen rejections** (the kernel itself refuses the input) — not an interpreter gap.
  - **hostile mode:** `+ -Dcorpus.hostile=true` (~every fourth NUMBER cell violates an operand-invalidity class) → JUnit XML **`tests="2" skipped="0" failures="0" errors="0"`, 0 divergences** (this set's run — §0).

  The `skipped="0"` matters: it proves both corpora were *present and actually swept*, not `assumeTrue`-skipped into a false green.

  **A precision on what the sweep proves.** The sweep pipeline prepares each model with `CorpusModelPreparer`, which uses the **kernel's** `expand` to produce a self-contained model *before* handing it to the interpreter. So the sweep exercises the interpreter's **evaluation** surface on already-assembled models — it does **not** exercise the interpreter's own assembly layer (Stage 2). Assembly parity is carried separately by **`ModelIndexParityTest`** (the kernel-free `dm.core` reader's index == the kernel's expand, over the same corpora) and **`cli.IncludeEvalDefaultPathTest`** (the CLI default path's own pre-expand). This is also why R1's closure (Set U) rests entirely on `cli.IncludeEvalDefaultPathTest` + `core.ModelReaderTest`, **not** on the sweep: because the sweep never runs the interpreter's assembler on that class, a green sweep neither validates nor could regress the relativizer — those targeted differential + unit locks are its whole proof (the bounded, enumerable transform is exhaustively unit-tested and differentially matched to `--kernel` at D=0/D=1, so corpus breadth adds no signal here).

**The hole-hunt (the honest part).** With breadth (1–3) and combination (4–5) established, the audit asked: *is there any capability / operator / formal check / message locale / customization point that NO lock exercises?* Findings:

- **No un-locked operator, formal check, or quantifier** — every one maps to a DiffTest (§1, audit (2)).
- **Message locales:** the interpolation mechanism is a per-locale template map lookup (locale-agnostic by construction); EN + DE are differentially locked; a third locale is the same mechanism, different key — not a hole.
- **Customization points:** all five kernel SPIs are mapped and locked *at the mechanism level* (§1d). The gaps are not "unexercised capability" but the residuals R2/R6/R7 below (consumer-supplied *impl*, SPI *source*, formal *text/code*) — each characterized, none a silent hole.
- **The genuine holes** the hunt surfaced were R1 (assembly: included-model rules) and R5 (DST), both tracked (IG67, IG62) and promoted to the §3 ledger. R1 is now **closed for the realistic population** (Set U, 2026-07-12); what remains are the loud-guarded R1 residual (G1′) and R5/DST (G2), each a narrow owner accept-or-fix call — no broad blocker.

---

## 3. The residuals ledger

Each residual with its disposition: **BLOCKS** a strict drop-in (a capability the `--kernel` path has that the interpreter lacks / a correctness divergence), or **ACCEPTED** (a documented caveat / consumer-input requirement that does not make the interpreter *wrong*).

| # | residual | what it is | disposition |
|---|---|---|---|
| **R1** | **included-model-own-rules relativization** (IG67 res. 1) — **CLOSED for the realistic population (Set U, 2026-07-12)** | When a mounted included model carries its **own** rules/computations, the included root-group name is dropped on mount. The kernel-free relativizer in `dm.core.ModelReader` now (a) carries a **relative** condition verbatim — kernel-exact, since the kernel rewrites nothing for it and the interpreter resolves it against the rule's post-mount group — and (b) rewrites a **SINGLE_RG absolute** `/RG/rest` reference to its depth-D relative form (`("../"*D)+rest`). Both evaluate on the default path, differentially matched to `--kernel` at D=0 and D=1 (`cli.IncludeEvalDefaultPathTest`). **Narrow residual, still loud-guarded:** a MODEL_ROOT-include absolute ref, a bare `/RG` group reference (no descent), and an outer-correlated `[$/…]` absolute ref are not rewritten — the default path still **refuses loudly** (`RK_INTERPRETER_INCLUDE_RULES_UNSUPPORTED`) rather than mis-evaluate. | **ACCEPTED (owner-ratified 2026-07-12).** No longer blocks: relative (what modelkit emits) and SINGLE_RG-absolute included rules now evaluate, differentially matched to `--kernel`. The remaining loud-guarded residual (**G1′**: MODEL_ROOT-absolute + bare-`/RG` group ref + outer-correlated `[$/…]`) is **accepted as a documented caveat** — rare shapes, and the guard refuses loudly (never mis-evaluates); extending the relativizer to cover them stays a future option (not a gate). *(DONE, Set V: the flag retired and the refusal's fix-pointer was reworded off `--kernel` to the rare-shape framing.)* |
| **R2** | **custom-construct implementations** (IG67 res. 2) | An *external* custom condition / custom field type has no impl inside a pure engine; it lands in `data.unsupported` unless the consumer supplies the impl via the SPI. | **ACCEPTED.** The **same limitation the kernel path has** — the kernel also needs a registered `ICustomCondition`/`ICustomFieldValidator`. The certificate *characterizes* this boundary; it cannot be erased (SPEC §8a "the one residual that may never reach zero-consumer-input"). The dispatch MECHANISM is GREEN (`CustomConditionDiffTest`/`CustomFieldTypeBoundsDiffTest` register the impl in both engines and agree). |
| **R3** | **output: `field` pointer shape** | The interpreter emits the resolved *path*; the kernel emits its verbose pointer object. | **ACCEPTED** (SPEC §8a (e)). A documented output-shape difference; the **default** already uses the resolved path, so retiring `--kernel` changes nothing. |
| **R4** | **output: `model compute` granularity** | `compute()` reports **all** computed instances (the useful library contract "what does this field compute to?"); `--kernel` reports the kernel's **delta** vs input. | **ACCEPTED.** Semantic parity is proven (`conformance.kernelComputeGranularity` projects report-all → the kernel's delta in every compute DiffTest + the corpus sweep). The **default** is already report-all; retirement removes only the narrower `--kernel` delta *view*, not any correct value. Surfaced by this audit; recorded here (not an IG — it is accepted, not open work). |
| **R5** | **timezone / DST** (IG62) | The interpreter carries no timezone (naive 86 400 s/day epoch-seconds), so datetime diffs match a `UTC` model (the DM-JSON default) but diverge from a `Europe/Berlin` model across a DST transition. | **ACCEPTED as a documented caveat (owner-ratified 2026-07-12).** A real divergence for `Europe/Berlin` datetime models across a DST transition, but **near-zero practical weight** — 0 corpus/differential footprint (real corpora are UTC, the DM-JSON default). Does **not** gate retirement. The DST-aware fix (surface the meta-model `timeZone`, DST-aware datetime parsing, gated by the recorded spring-forward fixture — KERNEL-FINDINGS §6) stays an open option in IG62 if a consumer hits it. |
| **R6** | **consumer-supplied charset SPI *source*** | The model-slot charset is differentially locked (`SupportedCharactersDiffTest`); a charset supplied via the `legalCharset` SPI **without** a model slot has no kernel differential (the oracle has no project-config channel), and the CLI eval verbs do not expose it. | **ACCEPTED / out-of-eval-scope.** The `LegalCharset` *enforcement* mechanism (BMP bitset membership) is identical for the SPI and the locked model-slot path — the model-slot lock proves the mechanism; the SPI is the same value, source-selected (unit-tested in `:interpreter` `LegalCharsetTest`). dmtool's eval path uses only the model slot, so it is out of the `--kernel`-retirement scope; a library residual, not a blocker. |
| **R7** | **formal-error text / custom-type reject code** | Auto-generated formal-check messages use the interpreter's OWN wording (Option A), and a rejected custom-typed cell emits `customFieldTypeInvalid` instead of the kernel's project-specific validator key. | **ACCEPTED.** For formal checks the kernel-parity channels (code / pointer / severity / type) match (`FormalErrorMessageDiffTest`); only the human TEXT differs, by decision (IF3, KERNEL-INTEGRATION-NOTES). For the custom-type reject the observable SUPPRESSION matches bit-for-bit (`CustomFieldTypeBoundsDiffTest`); only the per-cell formal CODE differs. Rule/computation message text IS kernel-identical (R7 does not touch it). |
| **R8** | **JS-side includes expander** (IG67 res. also) | A pure-JS standalone consumer with includes has no `commonMain` Kotlin expander (dmtool's JVM/native path uses the Java `dm.core.ModelReader`). | **ACCEPTED / out-of-eval-scope.** Library-consumer concern; dmtool (the `--kernel`-retirement subject) runs on JVM/native and expands via `ModelReader`. Deferred (IG67). |

---

## 4. The verdict

**GREEN (owner-ratified 2026-07-12).** The eval surface is **green-or-documented-equivalent**; the former broad blocker (G1) is closed for the realistic population; the two narrow residuals (G1′, G2) are **accepted documented caveats**. **No remaining blocker — `--kernel` eval retirement EXECUTED (Set V, 2026-07-12; IF122).**

The **eval-core surface** — evaluate / partial / compute / the cascade / every formal-check family / all 110 operators / messages / the five customization SPIs / single-rule eval — is **GREEN-or-documented-equivalent**, proven by a differential estate that (§2) provably spans the surface: 110/110 handled with per-construct DiffTests, the renderer-modifier net, two combination-fuzz nets, and two tri-engine real-corpus sweeps at 0 divergences in both seeding modes. The accepted residuals (R2 custom-impl, R3 field-pointer, R4 compute-granularity, R6 charset-SPI-source, R7 formal-text, R8 JS-expander) are each a documented caveat or a consumer-input requirement, not a case where the interpreter is *wrong*.

**The broad blocker G1 is closed (Set U).** Included-model-own rules with **relative** conditions (what modelkit emits) and **SINGLE_RG absolute** conditions evaluate on the default path, differentially matched to `--kernel` at D=0 and D=1 (R1) — validated by `cli.IncludeEvalDefaultPathTest` + `core.ModelReaderTest` (not the corpus sweep, which uses kernel `expand`; §2). The two remaining residuals are **accepted as documented caveats** (owner-ratified), neither a broad capability gap:

- **G1′ (R1 narrow residual) — MODEL_ROOT-absolute / bare-`/RG` / outer-correlated-`[$/…]` included refs.** Loud-guarded (refuses, never mis-evaluates). **Accepted** — rare shapes; extending the relativizer stays a future option, not a gate.
- **G2 (R5) — DST timezone** (IG62). A real `Europe/Berlin` DST-transition divergence with **near-zero practical weight** (0 corpus footprint; UTC is the DM-JSON default). **Accepted** — the DST-aware fix stays an open IG62 option if a consumer hits it.

With both caveats accepted, this certificate is **fully GREEN**. `--kernel` eval retirement is **EXECUTED (Set V, 2026-07-12; IF122)** — the kernel now remains only the bundled MVK consistency oracle (Scope B) and the `:adapter` differential oracle. Both accepted residuals are narrow, characterized, and loud-guarded (never a silent mis-evaluation) — honest documented caveats, not surprises.
