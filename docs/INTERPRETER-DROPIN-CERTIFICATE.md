# Interpreter drop-in COMPATIBILITY CERTIFICATE — the eval-surface gate for retiring `--kernel` eval

> **What this is.** The documented removal gate that [`INTERPRETER-SPEC.md`](INTERPRETER-SPEC.md) §8a makes the precondition for retiring the `--kernel` eval flag. It enumerates every capability the kernel's `--kernel` **evaluation** path provides, states the interpreter's parity status for each, names the **exact lock** that proves it, records the **residuals**, argues **why the differential estate spans the surface** (0-divergences-on-a-corpus is not proof-on-all-inputs), and renders a **verdict**.
>
> **Scope — EVALUATION only ("Scope A").** This certifies the kernel's *evaluation* role: `model eval` / `rule eval` / `model compute` and the JVM `--kernel` behaviour behind them. It does **NOT** certify the kernel's *consistency / MVK-acceptance* role (`rule check` / `model check` / `checkConsistency` / the F4 "the real kernel accepts this" guarantee) — that stays kernel-backed ("Scope B", a separate deferred question). So even after `--kernel` eval is retired, dmtool keeps bundling the kernel as the MVK consistency oracle and the `:adapter` differential oracle. Never collapse the two roles.
>
> **This document was the removal gate; retirement has since EXECUTED (Set V, 2026-07-12).** Building the certificate was an audit + documentation deliverable that gated retirement on the then-owner-ratified green-or-documented-equivalent verdict and changed eval behavior only where the audit surfaced a real bug. A 2026-07-19 source-and-evidence re-audit found additional legal-input, platform, mutation, and coverage dimensions outside that original certificate. The historical retirement decision remains recorded; this document must no longer be read as a current strict whole-domain certificate.

Record: [`INTERPRETER-FINDINGS.md`](INTERPRETER-FINDINGS.md) IF122 (executed retirement), IF134 (NFR-commit audit), and IF135 (follow-on source/certificate audit). The complete live set is [INTERPRETER-GAPS.md](INTERPRETER-GAPS.md); certificate-relevant examples include model assembly/reference handling (IG68/IG87), timezone domain/history (IG83), charset sequences (IG88), JS decimal/regex semantics (IG89/IG90), custom-validator codes (IG92), computation tolerance and quantifier composition/order (IG93/IG95/IG96), lossy unsupported handling (IG97), mutable-document caches (IG81), and the missing derived evidence matrix (IG94). Related: [`KERNEL-DECOUPLING-PROPOSAL.md`](KERNEL-DECOUPLING-PROPOSAL.md) (Stage 2 = model assembly) and [`KERNEL-SEMANTICS.md`](KERNEL-SEMANTICS.md).

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

**Follow-on audit (2026-07-19) — the certificate is evidence, not a self-updating proof.** A live-source sweep found dimensions that the original per-construct/name sweep and prepared-model corpora did not enumerate: legal combined charset graphemes, the kernel's wider timezone-id/history domain, Java-pattern constructs unavailable to JS, JS decimal context/plain rendering, custom-validator code identity, computation tolerance, composed `RepetitionNotUnique`, mutate-after-warm reuse, and a possible bare-group computation quantifier read-order split. It also found that the real-corpus harness prepares models before the interpreter and can skip generated-Java failures, so “tri-engine corpus” is conditional rather than a complete per-capability proof. These are tracked in the live gap ledger. No evaluator behavior changed in this documentation audit.

---

## 1. The eval-surface enumeration

Status legend: **GREEN** = differentially proven identical to the kernel · **DOC-EQUIV** = a deliberate, documented difference with the observable behaviour reconciled/projected · **RESIDUAL** = a known gap, guarded (never mis-evaluates) · **GAP** = a capability the `--kernel` path has that the interpreter does not.

### 1a. Model assembly (decoupling Stage 2 — the kernel-free expand the eval path runs first)

| capability | status | the exact lock | residual |
|---|---|---|---|
| includes / `modelReferences` mounting | GREEN | `adapter.ModelIndexParityTest` (kernel-free `dm.core` reader index == kernel expand); `cli.IncludeEvalDefaultPathTest` (a rule over a mounted field fires like the kernel) | — |
| imported typedef → effective types, `id ≠ path` | GREEN on the prepared dmtool path | `adapter.ModelIndexParityTest` (same `ElementFacts` at every path: kind/scale/bounds/lengths/pattern/enum-domain/repeatability) — includes + typedefs live in the real corpora it sweeps | raw `ModelLoader` requires prepared input; commonMain assembler IG68, enforced boundary/`model seed` IG91 |
| **included-model's own rules/computations** — RELATIVE + SINGLE_RG absolute conditions | GREEN (Set U) | the kernel-free relativizer in `dm.core.ModelReader` carries a relative condition verbatim and rewrites a SINGLE_RG `/RG/rest` absolute ref to its depth-D relative form; `cli.IncludeEvalDefaultPathTest` — a relative included rule *and* a D=0/D=1 absolute-path included rule now **evaluate on the default path, matching `--kernel`**; the rewrite is unit-locked `core.ModelReaderTest` | R1 — narrow residual (§3) |
| ↳ MODEL_ROOT-absolute / bare `/RG` group ref / outer-correlated `[$/…]` included refs | GAP, loud-guarded | the relativizer leaves these; `expandForEval` still **refuses loudly** (`RK_INTERPRETER_INCLUDE_RULES_UNSUPPORTED`), so they never mis-evaluate; `core.ModelReaderTest.flagsAResidualBareRootGroupAbsoluteReferenceItCannotRelativize` | R1 / [IG87](INTERPRETER-GAPS.md) |

### 1b. Evaluate / compute / formal-check

| capability | status | the exact lock | residual |
|---|---|---|---|
| `validateFull` (whole-document messages) | GREEN when every rule is supported; unsafe projection otherwise | `ValidateFullDiffTest`, `FullMessageFuzzDiffTest` (`code`·`type`·`pointer`), `ModelFuzzDiffTest` (random-depth models), + the whole `laws/*DiffTest` estate compare messages on supported models | the convenience discards `ValidationOutcome.unsupported`, [IG97](INTERPRETER-GAPS.md) |
| `validatePart` (partial, 3VL suppression) | GREEN | `PartialValidationDiffTest` + the `PartialValidation*DiffTest` family (aggregate/global-field/custom-condition/unique/subset-rep) + `PartialValidationFuzzDiffTest` (random doc × relevant-subset) | — |
| single-rule eval (`testRule`/`evalCandidate`, FIRED/PASSED/SUPPRESSED) | GREEN | `TestRuleDiffTest`, `RuleTestLawsTest`, `SuppressionDiffTest` | — |
| `compute()` + the dependency cascade | GREEN | `ComputeDiffTest`, `ComputeCascadeDiffTest`, `ComputeCascadeErroredDiffTest`, `ComputationFuzzDiffTest` (random compute chains) | R4 — report-all granularity (§3), DOC-EQUIV |
| `applyComputations` (compute → apply → validate, the `model eval` default) | GREEN | `ComputeApplyDiffTest`; `cli.IncludeEvalDefaultPathTest` exercises the flow end-to-end | — |
| formal: date format + Gregorian floor (`datumFormatFalsch`/`datumFalsch`) | GREEN | `FieldConstraintFormalDiffTest`, `BaselineValueFormalDiffTest`, `ComputedDateGregorianFloorDiffTest`, `PlainDateDiffTest`, `FormalErrorLawsTest` | — |
| formal: number format (invalid-char / decimal-sep / scale / signed-zero / plus / leading-zeros / total-digits / integer-cap / length) | GREEN | `FieldConstraintFormalDiffTest`, `NumberSignFormalDiffTest`, `ComputedNumberScaleDiffTest`, `ComputedNumberDigitOverflowDiffTest` (each `FormalErrorCode` carries the kernel's verbatim code) | — |
| formal: string length / newline; enum value-domain | GREEN on the exercised JVM surface | `FieldConstraintFormalDiffTest` (IF20 declared-constraint family) | pattern dialect on JS is [IG90](INTERPRETER-GAPS.md) |
| formal: date-range (separator / format / start≤end / floor) | GREEN | `DateRangeFormalDiffTest`, `FragmentRangeFormalDiffTest` | — |
| formal: **charset** (BMP default; model `supportedCharacters`; consumer SPI) | GREEN for BMP singleton/range cases · GAP for legal combined graphemes | `SupportedCharactersDiffTest` and `SupportedCharactersLawsTest` cover ranges A-Z/0-9 and the default; no current lock covers sequence atomicity | R6 + [IG88](INTERPRETER-GAPS.md) |
| formal: leading/trailing blank (baseline, every kind) | GREEN | `BaselineValueFormalDiffTest` (IF43) | — |
| `mandatoryField` (requiredness) | GREEN | `RequirednessDiffTest`, `RequiredPropertyLawsTest` | — |
| `uniqueIndex` / `RepetitionNotUnique` | GREEN | `UniquenessDiffTest`, the `RepetitionNotUnique*DiffTest` family, `RepetitionNotUniqueLawsTest` | — |
| over-repetition (`zuGrosseZeile` / `zuGrosseKontextnummer`) | GREEN | `OverRepetitionDiffTest` | — |

### 1c. Operators, functions, quantifiers, aggregates (the catalog — 110/110 handled)

| capability | status | the exact lock | residual |
|---|---|---|---|
| every catalog construct is HANDLED | GREEN | `CatalogParityTest.everyConstructIsClassifiedExactlyOnce` (110 handled, **0 deferred, 0 excluded** — a derived build guard, not a checklist) | — |
| boolean 3VL (`And`/`Or`), comparisons, arithmetic (`+ - * / ^`), concat, patterns, tolerance | GREEN on the exercised JVM/kernel cases; JS and computation-tolerance residuals | `KleeneDiffTest`, `PolarityDiffTest`, `ArithmeticDiffTest`, `ConcatComputeDiffTest`, `PatternDiffTest`, `ToleranceDiffTest`, `OperatorBatteryDiffTest` | JS MathContext/plain rendering [IG89](INTERPRETER-GAPS.md); Java-pattern dialect [IG90](INTERPRETER-GAPS.md); dropped computation tolerance [IG96](INTERPRETER-GAPS.md) |
| field-list / group-list / value-list quantifiers | GREEN on the exercised cases | `FieldListQuantifierDiffTest`, `GroupListQuantifierDiffTest`, `ValueListQuantifier*DiffTest`, `*LawsTest` | bare-group computation poison/read-order discriminator [IG93](INTERPRETER-GAPS.md) |
| `RepetitionNotUnique` | GREEN only standalone/top-level-conjunct forms | `RepetitionNotUnique*DiffTest`, `RepetitionNotUniqueLawsTest`; `RepetitionNotUniqueGuardedTest` currently locks unsupported nesting | legal `Or`/nested composition [IG95](INTERPRETER-GAPS.md) |
| aggregates (`Sum`/`Min`/`Max`/`NumberOfDifferentValues`/`SumOfProducts`/`NumberOf*`/`FirstFilledValue`) | GREEN | `MultiFieldAggregateDiffTest`, `IndexAggregateDiffTest`, `GroupStarValueAggregateDiffTest`, `SumOfProductsDiffTest`, `NumberOfFilled*DiffTest`, `FirstFilledValue*DiffTest`, `AggPolarityDiffTest`, `AggMalformedDiffTest` | — |
| value functions (`FieldValueAsString`/`AsNumber`, `ValueAsDate`, `Concatenation`, `EnumCategory`, round/abs) | GREEN | `FieldValueAsStringComputeDiffTest`, `FieldValueCoercionDiffTest`, `ValueAsDateDiffTest`, `EnumCategoryDiffTest`, `RoundDiffTest`, `AbsLengthDiffTest` | — |
| date/time construction, extraction, arithmetic, difference | GREEN for UTC and the exercised modern-Berlin cases | `DateConstructionDiffTest`, `Date4ArgConstructionDiffTest`, `DateExtractDiffTest`, `DateArithmeticDiffTest`, `DateDifferenceDiffTest`, `DateTime*DiffTest`, `TimeExtractDiffTest`, `DifferenceInDaysDiffTest` | R5 — other kernel-accepted zones + full history ([IG83](INTERPRETER-GAPS.md)) |
| date-range functions + overlap | GREEN | `DateRangeDiffTest`, `DateRangeOverlapSemanticsDiffTest`, `DateRangeComputeDiffTest` | — |
| semantic-index reads (`[F For key]`) | GREEN | `SemanticIndexDiffTest`, `SemanticIndexFieldKeyedDiffTest`, `SemanticIndexPresenceDiffTest`, `SemanticIndexComputeNoMatchDiffTest`, `SemanticIndexMatchedInvalidCellDiffTest` | — |
| correlation (`$` outer), star (`*`), `Having` filter | GREEN | `OuterCorrelationDiffTest`, `HavingDiffTest`/`Having*DiffTest`; **`CatalogParityTest.fuzzNetCoversRendererModifiers`** asserts the fuzz net exercises `$`/`*`/`Having` (the IF47 durable guard — a modifier is not an operator, so the operator guards can't cover it) | — |
| parallel / cross-level / sibling-group iteration | GREEN | `ParallelIterationDiffTest`, `FramedParallelIterationDiffTest`, `WrappedParallelIterationDiffTest`, `CrossSubtreeGroupStarDiffTest`, `perf.SiblingGroupFuzzDiffTest` | — |
| predefined types (`Valid`/`Invalid(field,"Name")`) | GREEN | `PredefinedTypeValidityDiffTest` (same registry in both engines) | — |

### 1d. The customization/configuration surface

| kernel customization | interpreter input | status | the exact lock | residual |
|---|---|---|---|---|
| `languageOfErrorMessages` (locale) | `DmInterpreter.locale` | GREEN | `LocalizedMessageDiffTest` (EN + DE), `DisplayNameDiffTest`, `UnspecifiedValueInterpolationDiffTest` | — |
| `customConditionFactory` (`ICustomCondition`) | `customConditions` SPI | GREEN (mechanism) | `CustomConditionDiffTest` (runs a REAL kernel-core `ICustomCondition` via the bridge; identical firing/polarity), `PartialValidationCustomConditionDiffTest` | R2 — consumer must supply the impl (§3) |
| `customFieldTypeFactory` (`ICustomFieldType`) | `customFieldTypes` SPI | GREEN for accept/reject suppression; GAP for rejection-code identity | `CustomFieldTypeBoundsDiffTest` compares the validator result and downstream suppression, not the project error key | R2 (impl) + [IG92](INTERPRETER-GAPS.md) |
| `metaData.legalCodepoints` (charset) | `legalCharset` SPI + model slot | GREEN for default/BMP singleton/range cases | `SupportedCharactersDiffTest`, `SupportedCharactersLawsTest` | R6 + combined-grapheme [IG88](INTERPRETER-GAPS.md) |
| `modelConfig.timeZone` | `ModelDef.timeZone` / `ModelTimeZone` | GREEN for UTC and exercised modern Europe/Berlin; incomplete accepted domain | `DstTimeZoneDiffTest`, `WallDayArithmeticDiffTest`, `ModelTimeZoneTest` | R5 — other accepted IDs + history ([IG83](INTERPRETER-GAPS.md)) |
| `Now`/`Today` clock | `EvalClock` | GREEN for the exercised zone/date regimes | `ClockDiffTest` (`Today`), `NowDiffTest` (`Now`) — deterministic far from now; exact boundary in `:interpreter` commonTest | R5 follows the model-zone gap ([IG83](INTERPRETER-GAPS.md)) |

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

**(1) Every construct is handled — a derived guard, not a whole-surface certificate.** `CatalogParityTest` reads the live `operators.json` universe, the live `PredicateOp`/`ConstantOp`/`FunctionOp` enums (what the engine handles), and the fuzz net, and build-fails if any catalog construct is unclassified or any handled callable is un-fuzzed-without-a-reason. Current: **110/110 handled, 0 deferred, 0 excluded.** This proves dispatch inventory, not every legal operand modifier, configuration value, platform dialect, computation metadata, assembly form, or both-strategy evidence cell; [IG94](INTERPRETER-GAPS.md) owns that derived matrix.

**(2) Every handled construct is named by a hand-written differential/law, but that mapping was audited manually.** The 2026-07-12 name sweep found zero of the 110 handled constructs unreferenced by a `*DiffTest`/`*LawsTest`, and zero of the 54 fuzz-pending callables without a real DiffTest. That remains valuable breadth evidence, but a test name does not prove all operand/config/platform variants or both kernel strategies, and the mapping can rot. IG94 turns this manual claim into per-cell derived evidence.

**(3) The renderer-modifier net closes the class the operator guards structurally cannot see.** `CatalogParityTest.fuzzNetCoversRendererModifiers` keys on what `DslRenderer` can EMIT (`$`/`*`/`Having`) rather than the operator inventory — the exact net whose absence let the IF47 `$`-correlation gap hide (a modifier is not an operator, so no operator-keyed guard covers it).

**(4) Two combination fuzz nets sweep what no hand-picked case reaches.** `InterpreterKernelFuzzTest` (codes multiset, tri-differential: `interp == groovy == java-static` — so it also gates the **static codegen** the runtime oracle can't see; on a legal-input split the ratified anchor is Groovy-dynamic and a static-Java divergence is recorded via the corpus `divergences` mechanism, never adopted — IF136) and its stronger sibling `FullMessageFuzzDiffTest` (`code|type|pointer` — trips on wrong polarity / wrong row); `ModelFuzzDiffTest` varies the *model* (random depth 2–4); `ComputationFuzzDiffTest` sweeps compute chains; `SiblingGroupFuzzDiffTest` the sibling-group shape; `PartialValidationFuzzDiffTest` the (document × relevant-subset) space. All CI-gated (`-PrunFuzz`), green this run.

**(5) Two real-corpus sweeps — kernel-team-authored rules over seeded instances, dynamic/interpreter throughout and generated-Java where generation succeeds, both seeding modes.** `RealCorpusSweepTest` covers the A12 Tools workspaces (107 models, 690 rules, 646 computations) + `a12-kernel-mm` (15 canonical metamodels): a genuine divergence between engines that evaluate fails the sweep; generated-Java rejections are counted and skipped rather than converted into evidence. The sweep is opt-in and prepares each model with the kernel before the interpreter sees it, so it is breadth evidence—not an always-on tri-engine/assembly certificate.

  - **nice mode:** `RealCorpusSweepTest -PrunCorpusSweep -Da12.corpus1.dir=<workspaces>` → JUnit XML **`skipped="0"`, 0 divergences** (workspaces 107 models / 530 validateFull + 350 compute; kernel-mm 15 models). The only skips: **5+5 kernel-codegen rejections** (the kernel itself refuses the input) — not an interpreter gap.
  - **hostile mode:** `+ -Dcorpus.hostile=true` (~every fourth NUMBER cell violates an operand-invalidity class) → JUnit XML **`tests="2" skipped="0" failures="0" errors="0"`, 0 divergences** (this set's run — §0).

  The `skipped="0"` matters: it proves both corpora were *present and actually swept*, not `assumeTrue`-skipped into a false green.

  **A precision on what the sweep proves.** The sweep pipeline prepares each model with `CorpusModelPreparer`, which uses the **kernel's** `expand` to produce a self-contained model *before* handing it to the interpreter. So the sweep exercises the interpreter's **evaluation** surface on already-assembled models — it does **not** exercise the interpreter's own assembly layer (Stage 2). Assembly parity is carried separately by **`ModelIndexParityTest`** (the kernel-free `dm.core` reader's index == the kernel's expand, over the same corpora) and **`cli.IncludeEvalDefaultPathTest`** (the CLI default path's own pre-expand). This is also why R1's closure (Set U) rests entirely on `cli.IncludeEvalDefaultPathTest` + `core.ModelReaderTest`, **not** on the sweep: because the sweep never runs the interpreter's assembler on that class, a green sweep neither validates nor could regress the relativizer — those targeted differential + unit locks are its whole proof (the bounded, enumerable transform is exhaustively unit-tested and differentially matched to `--kernel` at D=0/D=1, so corpus breadth adds no signal here).

**The hole-hunt (the honest part).** With breadth (1–3) and combination (4–5) established, the audit asked: *is there any capability / operator / formal check / message locale / customization point that NO lock exercises?* Findings:

- **No un-locked operator, formal check, or quantifier** — every one maps to a DiffTest (§1, audit (2)).
- **Message locales:** the interpolation mechanism is a per-locale template map lookup (locale-agnostic by construction); EN + DE are differentially locked; a third locale is the same mechanism, different key — not a hole.
- **Customization/configuration points:** the six input axes in §1d are mapped, but the later audit found unexercised semantic subdomains: combined-grapheme charset entries (IG88), custom-validator rejection codes (IG92), and the timezone-id/history domain (IG83).
- **The original hole-hunt surfaced R1 and R5; it did not exhaust the legal domain.** R1 is covered for relative and single-root-group absolute references while G1′ remains IG87. IF128 closed the exercised modern-Berlin cases, not every accepted zone/history. The follow-on audit's live IGs are why this document now describes a historical layered audit rather than a complete proof.

---

## 3. The residuals ledger

Each residual with its disposition: **BLOCKS** a strict drop-in (a capability the `--kernel` path has that the interpreter lacks / a correctness divergence), or **ACCEPTED** (a documented caveat / consumer-input requirement that does not make the interpreter *wrong*).

| # | residual | what it is | disposition |
|---|---|---|---|
| **R1** | **included-model-own-rules relativization** (IG67 res. 1; live [IG87](INTERPRETER-GAPS.md))—closed only for relative + SINGLE_RG absolute references | When a mounted included model carries its own rules/computations, the included root-group name is dropped on mount. The relativizer carries relative conditions verbatim and rewrites a SINGLE_RG absolute `/RG/rest` reference to its depth-D relative form; both match the kernel at D=0/D=1. MODEL_ROOT-absolute, bare `/RG`, and outer-correlated `[$/…]` references remain loud-refused. | **OPEN capability gap, safe failure.** Owner acceptance was sufficient for the historical retirement decision; the standing exactness goal still requires evaluating the legal forms or an explicit enforced supported-input contract. |
| **R2** | **custom-construct implementations** (IG67 res. 2) | An *external* custom condition / custom field type has no impl inside a pure engine; it lands in `data.unsupported` unless the consumer supplies the impl via the SPI. | **ACCEPTED.** The **same limitation the kernel path has** — the kernel also needs a registered `ICustomCondition`/`ICustomFieldValidator`. The certificate *characterizes* this boundary; it cannot be erased (SPEC §8a "the one residual that may never reach zero-consumer-input"). The dispatch MECHANISM is GREEN (`CustomConditionDiffTest`/`CustomFieldTypeBoundsDiffTest` register the impl in both engines and agree). |
| **R3** | **output: `field` pointer shape** | The interpreter emits the resolved *path*; the kernel emits its verbose pointer object. | **ACCEPTED** (SPEC §8a (e)). A documented output-shape difference; the **default** already uses the resolved path, so retiring `--kernel` changes nothing. |
| **R4** | **output: `model compute` granularity** | `compute()` reports **all** computed instances (the useful library contract "what does this field compute to?"); `--kernel` reports the kernel's **delta** vs input. | **ACCEPTED.** Semantic parity is proven (`conformance.kernelComputeGranularity` projects report-all → the kernel's delta in every compute DiffTest + the corpus sweep). The **default** is already report-all; retirement removes only the narrower `--kernel` delta *view*, not any correct value. Surfaced by this audit; recorded here (not an IG — it is accepted, not open work). |
| **R5** | **timezone id + history** (IG62 → IF128; live [IG83](INTERPRETER-GAPS.md)) | The interpreter now models UTC/GMT and modern Europe/Berlin. The kernel accepts the wider Java `TimeZone` ID domain and uses its full history; pre-1996 Berlin is only one counterexample. | **OPEN.** IF128 closed the exercised modern-Berlin regime, not the kernel's accepted zone/history contract. |
| **R6** | **charset source + legal combined sequences** | The model-slot/SPI BMP singleton/range mechanism is exercised; a source-only SPI value lacks a kernel project-config differential, and legal combined grapheme entries are flattened rather than matched atomically. | **MIXED.** Source selection remains an accepted eval-scope boundary; the semantic sequence gap is open as [IG88](INTERPRETER-GAPS.md). |
| **R7** | **formal-error text / custom-type reject code** | Auto-generated formal-check messages use the interpreter's own wording, and a rejected custom-typed cell emits `customFieldTypeInvalid` instead of the validator's project-specific key. | **MIXED.** Human wording remains an intentional license-safe difference (IF3). The machine-observable custom rejection code is open exactness work as [IG92](INTERPRETER-GAPS.md). |
| **R8** | **commonMain prepared-model assembler** (graduated residual of IG67) | A standalone JS consumer has no complete commonMain equivalent of `dm.core.ModelReader`; raw includes, typedefs, ids, and included-rule paths require preparation. | **DEFERRED library delivery gap** as [IG68](INTERPRETER-GAPS.md); existing consumers must not bypass preparation (IG91). |
| **R9** | **JS numeric and pattern semantics** | big.js does not currently reproduce the common MathContext(50)/plain-rendering contract, and JS `RegExp` cannot parse every kernel-valid Java pattern. | **OPEN legal-input gaps** as [IG89](INTERPRETER-GAPS.md) and [IG90](INTERPRETER-GAPS.md). Node compilation alone is not semantic parity. |
| **R10** | **computation metadata and boolean composition** | `toleranceRangeOp` is dropped from computation alternatives, composed `RepetitionNotUnique` is unsupported, and one bare-group compute quantifier order remains uncharacterized. | **OPEN** as [IG96](INTERPRETER-GAPS.md), [IG95](INTERPRETER-GAPS.md), and [IG93](INTERPRETER-GAPS.md). |
| **R11** | **certificate derivation and unsupported-result safety** | Operator dispatch is derived, but the capability/variant × dynamic/static/platform inventory is a manual prose/name sweep; `validateFull` also drops unsupported reports. | **OPEN governance/API gaps** as [IG94](INTERPRETER-GAPS.md) and [IG97](INTERPRETER-GAPS.md). |

---

## 4. The verdict

**Historical retirement verdict: GREEN-OR-DOCUMENTED-EQUIVALENT (owner-ratified 2026-07-12). Current audit verdict: strong evidence on the exercised JVM surface, not a current strict drop-in certificate.** `--kernel` retirement remains executed, but the follow-on audit found additional open exactness/capability dimensions and a missing derived evidence matrix. The authoritative current list is [INTERPRETER-GAPS.md](INTERPRETER-GAPS.md); no prose verdict may collapse it to only G1′ + Berlin history.

The eval-core has broad, valuable evidence: 110/110 catalog constructs are handled; per-construct differentials, renderer-modifier coverage, combination fuzzing, and prepared-model corpus sweeps agree on the cases they execute. The limits are now explicit: generated-Java evidence is not derived per capability, corpora do not exercise raw assembly or the newly identified platform/configuration corners, and several former “documented equivalents” are observable exactness gaps rather than mere output projections.

**The broad blocker G1 is closed (Set U).** Included-model-own rules with **relative** conditions (what modelkit emits) and **SINGLE_RG absolute** conditions evaluate on the default path, differentially matched to `--kernel` at D=0 and D=1 (R1) — validated by `cli.IncludeEvalDefaultPathTest` + `core.ModelReaderTest` (not the corpus sweep, which uses kernel `expand`; §2). The residual state is:

- **G1′ (R1)—MODEL_ROOT-absolute / bare-`/RG` / outer-correlated-`[$/…]` included refs.** Loud-guarded (refuses, never mis-evaluates), historically accepted for retirement, now open exactness work as [IG87](INTERPRETER-GAPS.md).
- **G2 (R5)—timezone.** Modern Europe/Berlin is differentially locked (IF128), but the kernel's wider accepted zone-id domain and full history remain [IG83](INTERPRETER-GAPS.md).

`--kernel` retirement is executed (Set V; IF122); the kernel remains the bundled MVK oracle and `:adapter` differential oracle. Re-certification requires the live correctness gaps to close or become explicit owner-ratified/enforced input-contract exclusions, plus IG94's derived evidence matrix. Until then, cite the named locks and their exercised domains—not a whole-domain GREEN label.
