# Feedback queue from a12-kernel-lean — working triage doc

> **Provenance + role.** Copied 2026-07-15 from the sibling [`a12-kernel-lean`](../../a12-kernel-lean/) project (`docs/A12-DMKITS-FEEDBACK.md`), whose maintainers recorded actionable upstream feedback discovered while formalizing/transporting kernel semantics. Their review is pinned to our revision `699e8619` (pre-Set-W; Set W touched none of the cited files, so every pin holds) and kernel `30.8.1`. **This copy is the WORKING triage doc:** each item carries a dated master triage disposition; when an item ships, its row gets the outcome + the graduated home (IG/IF row, catalog, findings) and the source queue in a12-kernel-lean is told, per its own handoff-use note. Links into our repo are rewritten repo-relative; evidence links point into the sibling checkout (consult-only).
>
> **Triage summary (master, 2026-07-15 — every claim spot-verified against source before acceptance):** all 12 items are **valid**. F10 is a **confirmed real eval divergence** (interpreter rule #1 must-fix); F12 is a credible divergence *candidate* (probe first). F1/F2/F3/F9/F11a are doc/comment corrections (F9 is an iron-rule violation on our side — a contradicted KERNEL-FINDINGS note left stale). F4–F8 are corpus/capture methodology improvements. Proposed packaging: **Set Y** (interpreter correctness: F10 fix + F12 probe + the co-located doc/comment fixes F1, F2, F3, F9, F11a — best agent) and **Set Z** (corpus/capture program: F4–F8 + F11b — regular agent).
>
> **Queue state (owner, 2026-07-15 — final):** **Set Y LANDED + gated** (F1, F2, F3, F9, F11a fixed; F10 → IF123; F12 → IF124 — plus the review-found IF125). **Set Z was superseded before minting:** F4, F5, F7, F8, F11b are **absorbed into the adopted [`A12-DMKITS-CAPTURE-PROPOSAL.md`](A12-DMKITS-CAPTURE-PROPOSAL.md)** (per-item dispositions below); **F6 stays open, re-sequenced** (corpus promotion is deliberately outside the proposal's V1). **This doc is DELETED when the capture program completes** (owner-directed lifecycle — the proposal file archives at the same time; git + the findings are the record).

## Recommended corrections

### F1 — Correct the contradictory `Length` implementation comment

**Finding:** [`ExprEval.kt`](../interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/eval/ExprEval.kt) describes an empty or absent `Length` operand as producing `Unknown`, immediately above code that deliberately returns numeric zero for a check-relevant empty operand. The implementation agrees with the settled finding and differential tests; the comment does not.

**Recommendation:** Change the local comment to say that a check-relevant empty String produces `0`, while a malformed, wrong-kind, or otherwise non-evaluable operand produces `Unknown`. Keep the rationale local because this is an operator-specific exception to direct String comparison.

**Acceptance check:** The comment agrees with the branch it describes and points briefly to IF73 or the corresponding differential; no semantic code change is needed.

**Triage (master, 2026-07-15): VERIFIED + ACCEPTED.** The stale line is `ExprEval.kt:485` ("an empty/absent (Unknown) operand → Unknown") directly above the `FunctionOp.LENGTH` arm returning `Val.Num(DEC_ZERO)` for the empty-check-relevant case; the nearby `emptyCheckRelevant` KDoc (l.644–650) is already correct. Trivial comment fix → Set Y.

**Disposition (Set Y, 2026-07-15): FIXED.** The `FunctionOp.LENGTH` comment now states: check-relevant empty String → 0 (the kernel's `laenge` grow-only fill, IF73); malformed / wrong-kind / non-evaluable → Unknown — an operator-specific exception to direct String comparison, kept local. Unit wording aligned to UTF-16 code units (F2). Landing revision: the Set Y slice-3 commit.

### F2 — Name the `Length` unit precisely in public descriptions

**Finding:** [`LengthOf.java`](../rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/dsl/LengthOf.java) and the [`operators.json`](../rulekit/src/main/resources/catalog/operators.json) catalog call the result a "character length." a12-dmkits' interpreter account uses UTF-16 code units, as recorded in [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md) and locked by the multiplatform [`LengthUnicodeCountTest.kt`](../interpreter/src/commonTest/kotlin/io/github/mbackschat/a12/dm/interpreter/LengthUnicodeCountTest.kt). The kernel differential [`AbsLengthDiffTest.kt`](../adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/laws/AbsLengthDiffTest.kt) anchors `Length` behavior but does not by itself establish a supplementary-plane Unicode separator; the operator-sensitive evidence retained here is ASCII-only.

**Recommendation:** Say "UTF-16 code-unit length" for the a12-dmkits interpreter and portable consumer contract, with a short reader-facing note that this differs from code-point and grapheme-cluster counts. Do not label the supplementary-plane boundary kernel-confirmed until a focused real-kernel observation separates it.

**Acceptance check:** Public API and catalog text use the same exact unit for a12-dmkits, the interpreter retains a supplementary-plane separating case, and any kernel-correspondence wording names only the Unicode cases actually run against the kernel.

**Triage (master, 2026-07-15): VERIFIED + ACCEPTED.** "character length" confirmed in `LengthOf.java:3` and the catalog `Length.meaning`. Per the placement rule the operator fact goes in the **catalog** (N7-validated), the Javadoc follows it. Heed the caution: a supplementary-plane kernel differential is the only thing that would let us say "kernel-confirmed" — until then, wording claims the a12-dmkits contract only. → Set Y.

**Disposition (Set Y, 2026-07-15): FIXED.** Catalog `Length.meaning` → "The UTF-16 code-unit length of a string field's value." plus a new SURPRISE gotcha naming the unit split (code units ≠ code points ≠ grapheme clusters; a supplementary-plane char counts 2) and scoping the claim: "the a12-dmkits contract, locked multiplatform; kernel correspondence is verified on BMP cases" — no supplementary-plane kernel confirmation asserted. `LengthOf.java` Javadoc follows the catalog; the `validExample` note says "UTF-16 code units". Catalog guards green (`rulekit.catalog.*`, incl. the kernel-executed examples). Landing revision: the Set Y slice-3 commit.

### F3 — Narrow or complete the "Kernel tri-checked" claim

**Finding:** [`EmptyOperandFiringDiffTest.kt`](../adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/laws/EmptyOperandFiringDiffTest.kt) labels the `Length(empty) >= 0` polarity witness "Kernel tri-checked," but its helper compares Groovy-dynamic kernel output with the a12-dmkits interpreter; that test does not execute the Java-static strategy.

**Recommendation:** Either add the Java-static leg for this witness or narrow the comment to the two strategies actually executed. Prefer the static leg because this is the distinguishing grow-only polarity case and the corpus already treats strategy identity as evidence metadata.

**Acceptance check:** The test's prose names exactly the executed engines, and a deliberately changed static result would fail if "tri-checked" remains.

**Triage (master, 2026-07-15): VERIFIED + ACCEPTED.** The claim is at l.86. Prefer their stronger option: add the Java-static leg (rule #1 demands BOTH strategies; this witness is exactly a strategy-divergence-prone polarity case). → Set Y.

**Disposition (Set Y, 2026-07-15): FIXED — the stronger option.** `agreePolarity` gained the java-static leg (`kernelJavaEngine(model).messages(...)`, polarity compared groovy-dynamic vs java-static before the interpreter compare), so EVERY polarity witness in the file is now genuinely tri-checked and the l.86 comment reads "Tri-checked: groovy-dynamic == java-static == interpreter". All 23 tests green — the static leg agrees throughout. Landing revision: the Set Y slice-3 commit.

## Recommended corpus and capture improvements

### F4 — Reject kernel-invalid authored scenarios before capture

**Finding:** [`CorpusCapture.kt`](../adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/laws/CorpusCapture.kt) executes the Groovy anchor, interpreter, and Java-static strategy, but it does not first require the authored rule or computation to pass the kernel consistency/MVK acceptance surface. Deserialization and runtime code generation are not a substitute for an explicit legality claim. [`CatalogFacetProbesTest.java`](../adapter/src/test/java/io/github/mbackschat/a12/dm/adapter/laws/CatalogFacetProbesTest.java) demonstrates the stronger sequence: require `RuleValidator` acceptance, then execute the runtime probe.

**Recommendation:** Add an explicit kernel-confirmed consistency gate before any scenario becomes committable, and add a negative guard proving that a deliberately illegal rule is rejected even if a runtime strategy happens to produce an output. Record the acceptance result in capture provenance rather than relying only on the generator succeeding.

**Acceptance check:** A legal seed still captures identically; an illegal comparison or rule placement fails at the acceptance stage with a classified diagnostic and cannot write a case.

**Triage (master, 2026-07-15): ACCEPTED.** Sound methodology — mirrors our own F4 "the real kernel is the oracle" testing rule; the negative does-fire guard is mandatory per the refactoring-gate discipline. → Set Z.

**Disposition (owner, 2026-07-15): SUPERSEDED — absorbed into the adopted [capture proposal](A12-DMKITS-CAPTURE-PROPOSAL.md) (§6 "Legality before runtime", work-plan W3).** Not implemented piecemeal; the acceptance check carries forward strengthened (the legality result becomes packet provenance, and both supplied + adapted digests are bound so the gate and runtime provably inspect the same bytes).

### F5 — Separate conformance capture from characterization export

**Finding:** The strict behavior in [`CorpusCapture.kt`](../adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/laws/CorpusCapture.kt) blocks a case whenever the interpreter differs from the Groovy anchor. That is correct for the green portable conformance corpus, but it also prevents the same machinery from exporting a kernel observation precisely when an interpreter defect or still-unsupported semantic mechanism is being researched.

**Recommendation:** Preserve strict mode as the default and as the only path that can update the conformance corpus. Add a separately named, opt-in characterization/export mode that retains the Groovy anchor, Java-static result, interpreter result, divergence classification, source revision, kernel version, and payload hashes without requiring interpreter agreement. Its artifacts must be unmistakably non-conformance evidence until the divergence is resolved.

**Acceptance check:** Strict capture still rejects an interpreter split; characterization mode exports the same split without relabeling it PASS or allowing it into the green corpus.

**Triage (master, 2026-07-15): ACCEPTED.** The F10 receipt itself proves the need — a12-kernel-lean had to hand-build a disposable harness to export a divergence our strict capture refuses to write. Design constraint: the characterization artifacts live outside `corpus/` and are loudly non-conformance. → Set Z.

**Disposition (owner, 2026-07-15): SUPERSEDED — absorbed into the adopted [capture proposal](A12-DMKITS-CAPTURE-PROPOSAL.md) (§5 "Capture once, qualify separately", work-plan W4).** The strict/characterization split generalizes to the three named qualification policies over immutable packets (`characterization-v1` retains divergences and can never promote; capture always preserves observations).

### F6 — Promote operator-sensitive empty and directional-polarity controls into the portable corpus

**Finding:** [`CorpusScenarios.kt`](../adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/laws/CorpusScenarios.kt) currently exposes only the small seed set, while the concise operator-sensitive knowledge remains mostly in repository-local differential tests. The Lean capsule needed portable paired observations for direct empty String comparison versus `Length(empty)`, and for directional Number polarity where kind and empty value stay fixed but signedness, literal direction, or filled state changes.

**Recommendation:** Add corpus cases that preserve the complete external message list for at least: empty String direct equality suppressed; the same empty String under `Length < 5` firing OMISSION; `Length >= 0` firing VALUE; unsigned empty Number `!= -1` firing VALUE; signed empty Number `!= -1` firing OMISSION; unsigned empty Number `!= 1` firing OMISSION; and filled zero `!= -1` firing VALUE. Keep content-bearing and all-empty-row controls separate so operator semantics are not confused with row eligibility.

**Acceptance check:** The standard three-engine corpus replay covers every added case, and paired case metadata identifies the one varied axis.

**Triage (master, 2026-07-15): ACCEPTED.** The seven named cases are all settled findings on our side (IF73 family, the polarity gradient) — promoting them makes the portable corpus carry the knowledge instead of only repo-local DiffTests. → Set Z.

**Disposition (owner, 2026-07-15): STILL OPEN — re-sequenced, NOT absorbed.** The adopted [capture proposal](A12-DMKITS-CAPTURE-PROPOSAL.md) deliberately defers corpus **promotion** out of its V1 (§5: "a separate hard boundary... not a mandatory generic V1 command"), so this item does not ride it automatically. It stays the one live capture-family row: promote the seven paired cases into the committed portable corpus once the capture boundary lands (through its gated path + a reviewed promotion), or via the existing corpus machinery if the program stalls.

### F7 — Generate an auditable capture receipt

**Finding:** [`CorpusGenerator.kt`](../adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/laws/CorpusGenerator.kt) writes cases and deduplicated models, and each case carries a kernel version, but there is no single generated receipt binding the exact capture schema, a12-dmkits revision, kernel version, strategy identities, model/case inventory, byte counts, and SHA-256 digests.

**Recommendation:** Generate a closed receipt or manifest beside the corpus and verify it in the ordinary replay gate. Treat it as provenance and drift control, not a second semantic specification. A changed case or model then has one reviewable inventory delta, and external consumers can prove which bytes they received.

**Acceptance check:** Replay rejects an added, removed, renamed, or byte-modified retained artifact until the reviewed receipt is regenerated; the receipt itself contains no machine-specific path.

**Triage (master, 2026-07-15): ACCEPTED.** Same golden-guard pattern as our schemas/API dumps — a receipt the replay gate verifies, regenerated deliberately. Their evidence receipt (schemaVersion 1) is a good reference shape. → Set Z.

**Disposition (owner, 2026-07-15): SUPERSEDED — absorbed into the adopted [capture proposal](A12-DMKITS-CAPTURE-PROPOSAL.md) (§2 `RECEIPT.json` + §7 determinism, work-plan W1/W3).** The receipt becomes the packet's closed inventory (relative paths, roles, byte counts, SHA-256; self-hash-cycle excluded; out-of-band digest handoff; byte-identical recapture as the determinism contract).

### F8 — Retain a rerunnable capture recipe for focused external consumers

**Finding:** The focused `operator-sensitive-empty` capture used for the Lean capsule was produced by a temporary adapter test in an ignored disposable workspace; the portable observations and receipt were retained here, but the source-side capture command/test was intentionally removed. The generic [`corpus/README.md`](../corpus/README.md) documents full corpus regeneration, not a small read-only export recipe for a named set of rules and documents.

**Recommendation:** Add a maintained focused-capture entry point or recipe in a12-dmkits that accepts an explicitly authored scenario set and writes only to a caller-supplied ignored/output directory. It should run the legality gate, both kernel strategies, the interpreter, and receipt generation without rewriting the committed corpus unless a distinct promotion command is invoked.

**Acceptance check:** A clean checkout can reproduce the named String/Length and Number-directional observations into an ignored directory with one documented command; `git status --short` remains unchanged.

**Triage (master, 2026-07-15): ACCEPTED.** Composes F4+F5+F7 into one maintained entry point; the `git status` invariant is the right acceptance bar. → Set Z (the capstone item).

**Disposition (owner, 2026-07-15): SUPERSEDED — absorbed into the adopted [capture proposal](A12-DMKITS-CAPTURE-PROPOSAL.md) (the maintained command surface itself, §7, work-plan W3).** The "focused-capture recipe" grows into the full `capture`/`validate-scenarios`/`verify`/`diff-*` command family with caller-owned output, committed-corpus refusal, and the clean-`git status` invariant carried forward verbatim.

### F9 — Correct the superseded bare-name ancestor claim

**Finding:** The later source-backed account in [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md) correctly states that a bare `[Name]` lookup tries the declaring group and then, only when `fieldRefByShortNameAllowed` is enabled, a model-wide unique field short name. The older "relative path forms" supplement in the same document still says a miss walks ancestor groups without `../`. Those mechanisms are contradictory: the apparent ancestor success can be explained by the model-wide unique fallback, while explicit parent navigation remains the `../` form. The a12-dmkits interpreter and its IF119 tests already implement the corrected local-or-global mechanism.

**Recommendation:** Amend the older supplement in place with a visible correction or supersession pointer to the later source-backed finding. Remove the claim that bare lookup performs an ancestor walk, and state the flag precondition for the model-wide fallback so readers cannot reintroduce the retired mechanism in another interpreter or importer.

**Acceptance check:** Searching the live documentation yields one non-contradictory bare-field resolution rule: declaring-group first, then flag-gated model-wide unique field fallback; an ancestor field requires either explicit `../` navigation or qualification through that separately named fallback.

**Triage (master, 2026-07-15): VERIFIED + ACCEPTED — an iron-rule violation on our side.** The stale 2026-06-05 probe-era claim ("Bare names also resolve *upward*… no `../` needed") sits in the relative-path-forms supplement (~l.1766) and contradicts the 2026-07-11 source-backed §10 finding (~l.1226, `CheckIdNameRefImpl` order, IF119). "A contradicted note is updated, never left stale" — supersede in place with a pointer. → Set Y.

**Disposition (Set Y, 2026-07-15): FIXED — re-probed first, then superseded in place, plus two surfaces the sweep found.** The June-5 shape re-probed on exactly its model (`[Tier]` from a rule on `/Subscription/Addons`): flag-on → accepted, flag-off → `MVK_INVALID_ENTITY` — the "ancestor success" was the `fieldRefByShortNameAllowed` unique-short-name fallback (modelkit drafts default it ON; the kernel default is off), locked as two new `validate.laws.PathLawsTest` arms. The relative-path-forms supplement now carries a visible SUPERSEDED correction naming the §10/IF119 mechanism + the flag precondition; the acceptance-check sweep also found and fixed the claim in `skill/SKILL.md` ("bare name" bullet — every replacement claim executed through the live binary per the skill-edit #1 rule: bare-unique accepted, ambiguous `MVK_FIELDNAME_NOT_UNIQUE`, `../Tier` accepted; plugin copies re-synced) and annotated the two 2026-06-05 `eval/RESULTS.md` entries with supersession notes (the dated record itself stays). Landing revision: the Set Y slice-3 commit.

### F10 — Treat a final empty String computation as no stored value

**Finding:** At a12-dmkits revision `699e8619`, the interpreter correctly lets a clean empty String field contribute `""` inside concatenation, but it then reports the resulting final empty text as `VALUE ""`. Kernel 30.8.1 applies a second root-store decision: a final empty String is no computed value. The retained [String-computation receipt](../../a12-kernel-lean/evidence/kernel-30.8.1/captures/string-computation-2026-07-15.json) records both kernel strategies agreeing and the already kernel-granularity-projected interpreter disagreeing in exactly three cases: a stale target becomes `CLEARED`, while an absent target remains silent in both a content-bearing and an otherwise empty row. The nonempty control `[Source] + "-X"` still produces `VALUE "-X"`, so the problem is not empty operand substitution; it is the final String store boundary.

**Recommendation:** Keep expression evaluation and target storage separate. Concatenation should continue to map clean missing operands to empty contributions, but the root String store must convert a final empty text to the same no-value store decision as a bare empty copy. Apply kernel delta granularity only afterward: no-value clears a previously filled target and reports nothing for an already empty target. Preserve poison separately even where its immediate delta is also `CLEARED`.

**Acceptance check:** Add paired interpreter and kernel-differential cases for filled-plus-empty, empty-plus-nonempty, all-empty with stale target, and all-empty with absent target. Mutation controls must independently catch skipping empty contribution, storing final empty text, and clearing an already empty target. The three retained mismatch cases then agree without weakening the comparison or treating a12-dmkits as the oracle.

**Triage (master, 2026-07-15): VERIFIED — A REAL DIVERGENCE, rule-#1 MUST-FIX (top priority).** I read the receipt: 13 cases, both kernel strategies agree on all, the interpreter splits on exactly the 3 named all-empty-concat cases (`VALUE ""` where the kernel stores no value → `CLEARED`/silent). Escaped our nets because the corpus computations don't produce final-empty concats and the compute fuzz doesn't cover the shape — the fix set must also extend the fuzz/differential coverage to the class (empty-concat store boundary), not just the 3 cases. New IG row on landing. → Set Y, the headline item.

**Disposition (Set Y, 2026-07-15): FIXED — graduated IG69 → [`INTERPRETER-FINDINGS`](INTERPRETER-FINDINGS.md) IF123.** Kernel mechanism source-pinned in both strategies (`CalculationController.handleBerechnetenWert(VkString…)`: `!hasValue()` → return before check-and-store — shared runtime code both codegen templates call; KERNEL-FINDINGS §11 "the root store takes a SECOND decision", incl. the scope ruling: String-only in effect — date no-value is a null date, a number has no empty arm). Fix at the store boundary (`ComputationEngine.toComputed` + outcome-keyed cascade bookkeeping), expression evaluation untouched; the three receipt shapes now agree raw AND projected. Locks per the acceptance check: `adapter.laws.EmptyConcatStoreDiffTest` (tri-check, all four paired shapes + minLength gate-order), multiplatform `EmptyStringComputationStoreTest`, and the new string-concat compute-fuzz family (`ComputationFuzz.stringModel` — mutation-checked: gate disabled diverges on 17/200 pairs). Landing revision: the Set Y slice-1 commit.

### F11 — Document and transport payloadful `ERRORED` computation results

**Finding:** At the pinned revision, [`Computed`](../interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/model/Model.kt) documents its `value` as present only for `ComputeOutcome.VALUE`, although [`ComputationEngine.toComputed`](../interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/ComputationEngine.kt) also returns the attempted rendered value for `ERRORED`; the `toComputed` KDoc itself describes only VALUE/CLEARED despite that branch. The implementation therefore carries more useful information than its public comment admits. Conversely, [`InterpreterReplay.sigOf`](../interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/conformance/InterpreterReplay.kt) renders every error only as `pointer|ERRORED`, erasing the attempted value, while `computedOutcome` reduces `typeFormatError` to a Boolean and discards the target-check cause. The nine-case String target-validation experiment had to use a purpose-built rich observation to retain attempted `ABCD` plus `stringZuLang`/`stringZuKurz`. This is an evidence-transport and API-description gap, not a claim of semantic disagreement: the a12-dmkits interpreter agrees with all nine retained kernel outcomes at projected-delta and stored-value application granularity.

**Recommendation:** Correct the `Computed.value` and `toComputed` comments to state that an errored result retains its attempted rendered value. Add a versioned or explicitly named rich computation-result/signature surface that also preserves the structured target-check cause, while keeping the compact legacy corpus signature only where compatibility requires it and labeling that projection as lossy. Avoid reconstructing the cause from the rendered value downstream; retain the `typeFormatError` result at the point where `computedOutcome` currently collapses it.

**Acceptance check:** Public KDoc matches every constructor path; a String `maxLength` violation is available as an `ERRORED` result containing both attempted value and cause; the same invalid value is retained for absent, stale, and equal prior targets; and a deliberate attempted-value or cause mutation fails the rich replay. Any unchanged compact `pointer|ERRORED` route explicitly documents that it cannot support attempted-value or cause correspondence claims.

**Triage (master, 2026-07-15): VERIFIED + ACCEPTED, split in two.** (a) The KDoc corrections (`Computed.value`, `toComputed`) are stale-comment fixes → Set Y. (b) The rich result/signature surface is a conformance-corpus design item riding the same machinery as F5/F7 → Set Z.

**Disposition for (a) (Set Y, 2026-07-15): FIXED.** `Computed`/`ComputeOutcome` KDoc now states `value` is present for BOTH `VALUE` (the computed value) and `ERRORED` (the ATTEMPTED rendered value — the kernel stores it and flags the instance); `toComputed`'s KDoc names all three outcomes incl. the ERRORED-retains-value branch and the IF123 empty-text clear. Matches every constructor path (VALUE/ERRORED carry a value; CLEARED — Unknown, empty-text, poison, invalid-index — never does). (b) F11b rides Set Z, untouched. Landing revision: the Set Y slice-3 commit.

**Disposition for (b) (owner, 2026-07-15): SUPERSEDED — absorbed into the adopted [capture proposal](A12-DMKITS-CAPTURE-PROPOSAL.md) (§4 exact public observations, work-plan W1/W2).** The rich signature becomes `compute-observation-v1`'s separate multiplicity-preserving channels (attempted value + structured cause + typed-vs-rendered values + per-field fidelity), the compact legacy signature stays byte-compatible and explicitly lossy-labeled — a stronger form of the acceptance check.

### F12 — Probe CRLF normalization before String target-length checks

**Finding:** On the ordinary full-check route with `noValueValidation = false`, kernel [`FormatDefinitionString.validiereFormat`](../../a12-kernel/kernel-rt/kernel-core-runtime/src/main/java/com/mgmtp/a12/kernel/core/rt/_30_8/internal/formatdef/FormatDefinitionString.java) applies the line-break-permission check, calls [`RuntimeUtils.normalizeLinebreaks`](../../a12-kernel/kernel-rt/kernel-core-runtime/src/main/java/com/mgmtp/a12/kernel/core/rt/_30_8/internal/util/RuntimeUtils.java) to normalize CRLF to LF, and only then applies String minimum/maximum length. The earlier no-value/non-full-check exits are separate. At the pinned a12-dmkits revision, the clean-room [`stringConstraintError`](../interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/eval/FormalError.kt) route appears to reject newline text when line breaks are forbidden but to measure the unnormalized host String when line breaks are permitted. That creates a source-level divergence candidate for a permitted `"\r\n"` value near a one-code-unit length boundary: the kernel may measure one normalized LF while a12-dmkits may measure two UTF-16 code units. This is not yet an observed disagreement and must not be promoted as one without a focused differential packet.

**Recommendation:** Add paired kernel-strategy and interpreter probes on the full-check route with `noValueValidation = false` and line breaks permitted, holding the String kind and source constant while varying a minimum or maximum around the normalized CRLF length. Preserve the pre-normalized input, normalized stored/result form, target-error cause, and final delta separately so the probe identifies whether normalization belongs before validation, before storage, or only in rendering.

**Acceptance check:** Groovy-dynamic and static-Java agree on the exact CRLF boundary behavior; the a12-dmkits result either agrees or records a reviewed mismatch without weakening projection granularity. A mutation that counts raw CRLF where normalization is required must fail, and ordinary LF plus a no-line-break-permitted rejection control must keep the clause order visible.

**Triage (master, 2026-07-15): CREDIBLE CANDIDATE + ACCEPTED as a probe-first item.** Confirmed our `FormalError.kt` performs no line-break normalization anywhere; their kernel-source citation (normalize CRLF→LF **before** min/max length on the permitted route) makes the divergence plausible but unobserved — exactly the probe → differential-lock → fix-or-refute drill (the IG57/IG58 mold). Don't fix ahead of the probe. → Set Y, after F10.

**Disposition (Set Y, 2026-07-15): CONFIRMED + FIXED — and the mechanism is BROADER than the F12 hypothesis; graduated IG70 → [`INTERPRETER-FINDINGS`](INTERPRETER-FINDINGS.md) IF124.** The focused paired probes (12 tri-check arms, both kernel strategies unanimous throughout) confirmed the length divergence AND refuted "measure-only": the kernel normalizes CRLF→LF once at eval ingestion (`CheckCommand` → `checkAndConvertFieldValue.normalizedValue()` feeds the eval caches), so pattern checks, RULE reads (`Length` reads 5 — even on a `noValueValidation` field), and COMPUTATION reads (a bare copy *stores* `"AB\nCD"`) all see the normalized text; the line-break gate runs first on the raw text and covers ENUM fields (newline-before-domain — a second clause-order divergence we also fixed). Lone CR confirmed un-normalized. Fix at the interpreter's twin locus (the `Document` eval-index ingestion; consumer placements stay raw) + the `stringConstraintError` clause reorder. Kernel fact: KERNEL-FINDINGS §3 "CRLF is normalized to LF at EVAL INGESTION". Locks: `adapter.laws.CrlfLengthNormalizationDiffTest` + multiplatform `CrlfNormalizationTest`. Landing revision: the Set Y slice-2 commit. **Review addendum (same day):** the master's gate question ("is the kernel's normalization really ingestion-wide, or only in `validiereFormat`?") prompted a positive-fire discriminator (`Length > 4` must FIRE at the normalized 5) — it CONFIRMED the ingestion-wide read for checked fields (fires in both strategies) but EXPOSED that a `noValueValidation` field is out of the class entirely (its rule read never surfaces the stored text — a separate, pre-existing divergence, opened as IG71); the IF124/KERNEL-FINDINGS texts and the diff test were corrected in the same pass.

## Handoff use

When feeding this queue back to a12-dmkits, open or update items in that project's own gap/plan surfaces and link back to the specific evidence or capsule that motivated them. Close an item here only after recording the upstream revision and outcome; if upstream rejects or supersedes it, retain the disposition so later formalization work does not rediscover the same question.

**Our side of the contract:** when a Set Y/Z item lands, record here the landing revision + outcome per F-item, graduate the durable content to its proper home (KERNEL-FINDINGS / INTERPRETER-FINDINGS / the catalog / IG rows), and notify the a12-kernel-lean queue so it can close its row with our revision.
