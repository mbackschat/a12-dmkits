package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.eval.FunctionOp
import io.github.mbackschat.a12.dm.interpreter.eval.GroupRelevance
import io.github.mbackschat.a12.dm.interpreter.eval.renderMessage
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.Computed
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionFieldPointer
import io.github.mbackschat.a12.dm.interpreter.model.Message
import io.github.mbackschat.a12.dm.interpreter.model.MessageLabelProvider
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedType
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedTypeRegistry
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleResult
import io.github.mbackschat.a12.dm.interpreter.model.Suppression
import io.github.mbackschat.a12.dm.interpreter.model.UnsupportedRule
import io.github.mbackschat.a12.dm.interpreter.model.ValidationOutcome
import io.github.mbackschat.a12.dm.interpreter.model.Verdict
import io.github.mbackschat.a12.dm.interpreter.regex.PatternRegistry
import kotlin.jvm.JvmOverloads

/**
 * The public entry point — evaluates a [ModelDef]'s rules over a [Document] instance, kernel-free, on
 * JVM and JS alike. The standalone-library façade (proposal goal 2) and the engine dmtool's native
 * image consumes for the runtime verbs (goal 1). Correctness = agrees with the real kernel (verified by
 * the `:adapter` differential tests).
 *
 * Full validation ([validateFull]/[validate]), partial validation ([validatePart]), single-rule eval
 * ([testRule]/[evalCandidate]), and computations ([compute]) — the kernel's `validateFull`/`validatePart`/
 * `compute` twins, kernel-free.
 */
class DmInterpreter @JvmOverloads constructor(
    private val model: ModelDef,
    private val clock: EvalClock? = null,
    /** The consumer-supplied predefined-type registry (`Valid`/`Invalid(field, "Name")`) — the kernel's
     *  `IPredefinedType` client SPI. Defaults to the built-in common set ([PredefinedTypeRegistry.DEFAULT]);
     *  a consumer may pass its own (built-ins + project types). An unregistered name → the rule is unsupported. */
    private val predefinedTypes: Map<String, PredefinedType> = PredefinedTypeRegistry.DEFAULT,
    /** Consumer-supplied custom conditions (the kernel's `ICustomCondition` SPI, cross-platform JVM+JS), keyed
     *  by name — `CustomCondition X`. Default empty: a model using an unregistered custom condition reports it
     *  unsupported (fail-fast). A JVM consumer adapts existing kernel `ICustomCondition` impls onto the SPI; a
     *  JS consumer implements the SPI directly. */
    private val customConditions: Map<String, CustomCondition> = emptyMap(),
    /** Consumer-supplied CODE-BASED custom field types ([CustomFieldType] — the kernel's `ICustomFieldType` SPI,
     *  for imperative checksum types), keyed by name. Default empty. Consulted ONLY after [predefinedTypes] misses,
     *  so the declarative per-cell path stays monomorphic ([CustomFieldType]'s KDoc; `CustomTypePerfTest`). Each
     *  relevant concrete value is checked once per call with its locale/effective bounds/stored-value context;
     *  the cached code/message result drives both emission and suppression. A custom-typed cell whose name is in
     *  NEITHER registry is reported unsupported (the lenient degrade). */
    private val customFieldTypes: Map<String, CustomFieldType> = emptyMap(),
    /** The consumer-supplied legal character set field values are validated against — the SPI for a project charset
     *  the DM-JSON does NOT carry (e.g. **DIN 91379**, the EU public-administration set), IF43. **Default
     *  [LegalCharset.BMP]** (the kernel's runtime default: an emoji / supplementary-plane char is illegal). This is
     *  the FALLBACK: when the model itself declares `modelConfig.supportedCharacters` ([ModelDef.legalCharset],
     *  IF120) that model-declared slot takes precedence (it is what the kernel bakes into codegen and enforces), and
     *  this SPI applies only when the model declares none. The interpreter must expose every kernel customization to
     *  be 100% compatible. */
    private val legalCharset: LegalCharset = LegalCharset.BMP,
    /** Consumer override for `$field$` message names. A non-null result wins exactly (including empty); null
     *  falls through to the nonempty model label, then the field's indexed debug identity. */
    private val messageLabelProvider: MessageLabelProvider? = MessageLabelProvider.MODEL_LABEL_OR_NAME,
) {

    /** The effective legal charset: the model-declared `supportedCharacters` ([ModelDef.legalCharset], IF120) when
     *  present, else the consumer-supplied [legalCharset] SPI. */
    private val effectiveLegalCharset: LegalCharset = model.legalCharset ?: legalCharset
    private val patterns = PatternRegistry()

    /** Model-static path prefixes and repetition bounds, prepared once rather than rebuilt per validation cell. */
    private val paths = ModelPathIndex(model)

    /** The auto-check emitter + formal-status oracle — the call-invariant I2 seam collaborator (IF117). */
    private val checks = AutoChecks(model, effectiveLegalCharset, predefinedTypes, customFieldTypes, patterns, paths)

    /** The iteration-scope machinery + the RepetitionNotUnique evaluator — call-invariant I2 seam collaborators (IF117). */
    private val scopes = IterationScopes(model)
    private val rnuEval = RepetitionNotUniqueEvaluator(model, scopes)
    /** Nullable, instance-local deterministic seam used only by model-static performance guards. */
    internal var preparationObserver: PreparationObserver? = null
    private val parsedModel = ParsedModel(model) { preparationObserver }
    /** Nullable, instance-local structural seam used only by the cache-lifetime guard. */
    internal var cacheObserver: CacheObserver? = null
    private val preparedModel = PreparedEvaluationPlan(
        model,
        parsedModel,
        scopes,
        rnuEval,
        observer = { preparationObserver },
        support = PreparationSupport(
            clock = clock,
            predefinedTypes = predefinedTypes,
            customConditions = customConditions,
            customFieldTypes = customFieldTypes,
            patterns = patterns,
        ),
    )
    private val engine = ComputationEngine(
        model,
        clock,
        predefinedTypes,
        customFieldTypes,
        preparedModel,
        scopes,
        checks,
        patterns = patterns,
        cacheObserver = { cacheObserver },
        preparationObserver = { preparationObserver },
    )

    /**
     * Audit this prepared model without consulting document values. Every stored rule, implicit computation
     * validation, computation guard/alternative, customization requirement, and host-sensitive primitive is
     * traversed. A non-empty result means an evaluator must refuse performance/certification work up front;
     * [validate] remains the per-instance outcome-bearing check for failures raised by a registered callback.
     */
    fun supportAudit(): List<UnsupportedRule> = preparedModel.audit()

    /** Test/measurement seam: number of distinct sources compiled by this engine owner. */
    internal fun patternCompilationCount(): Int = patterns.compilationCount

    /**
     * Run every rule over each row of its iteration scope and return one [Message] per firing — the
     * runtime twin of the kernel's `validateFull`. A rule fires when its error condition is TRUE on a row.
     *
     * The messages-only convenience over [validate]. It **fails loudly** (throws) when any rule is
     * [ValidationOutcome.unsupported] — a state the kernel can never reach (it supports every construct), so a
     * silent messages-only projection there would be a FALSE-CLEAN result (IF139). Callers that want the raw
     * firings plus the unsupported report use [validate]; a caller that deliberately wants messages while
     * ignoring unsupported writes `validate(doc).messages` explicitly.
     */
    fun validateFull(doc: Document, locale: String = "en_US"): List<Message> {
        val outcome = validate(doc, locale)
        check(outcome.unsupported.isEmpty()) {
            "validateFull would silently drop ${outcome.unsupported.size} unsupported rule(s): " +
                outcome.unsupported.joinToString(prefix = "[", postfix = "]") { "${it.rulePath} (${it.reason})" } +
                " — use validate(doc) for the ValidationOutcome (messages + unsupported), or " +
                "validate(doc).messages to project messages only on purpose."
        }
        return outcome.messages
    }

    /**
     * The tolerant whole-document validation: each rule's firings as [Message]s, plus the rules that use
     * a construct the interpreter does not model (an unknown operator, or a deliberately external one
     * like `CustomCondition`) reported as [ValidationOutcome.unsupported] instead of crashing the run —
     * mirroring the kernel-adapter's `Opaque` read. Genuine bugs still throw.
     */
    fun validate(doc: Document, locale: String = "en_US"): ValidationOutcome =
        validateWith(doc, locale, ALL_RELEVANT)

    /**
     * Tolerant **partial** validation — the runtime twin of the kernel's `validatePart(DocumentV2,
     * relevantEntities, config)`. Only the rules whose **error field instance** is in [relevant] are
     * evaluated; of those, any reference to a field/group instance **not** in [relevant] resolves to
     * `Val.Unknown`, so the existing 3VL produces the kernel's exact suppression — `true And Unknown`
     * suppresses, `true Or Unknown` still fires (the "no possible value could prevent the error" clause is
     * just Kleene logic). The auto-generated checks (formal/mandatory/unique) are likewise gated by their
     * field's relevance. A relevant group makes all its descendants relevant; [RelevantEntity.ALL] wildcards
     * a repetition level (the encouraged form). With the whole document relevant this equals [validate].
     * KERNEL-FINDINGS "Partial validation".
     *
     * Matches the kernel across the partial surface: rule-gating + 3VL suppression; starred aggregates under a
     * *subset* of relevant repetitions gated UNKNOWN (IF35); GLOBAL fields auto-relevant (IF37, the kernel's
     * md-layer behavior); a `CustomCondition` receives the relevant set (IF38); and uniqueness (`uniqueIndex` /
     * `RepetitionNotUnique`) needs the duplicate partner relevant too (IF39). All locked by `…laws/PartialValidation*DiffTest`.
     */
    fun validatePart(doc: Document, relevant: Set<RelevantEntity>, locale: String = "en_US"): ValidationOutcome =
        validateWith(
            doc,
            locale,
            relevanceOf(relevant),
            customRelevantOf(relevant),
            starRelevantOf(relevant),
            groupRelevanceOf(relevant),
        )

    /**
     * Whether a STARRED operation has complete extent in PARTIAL mode (IF42). The kernel evaluates an
     * all-rows aggregate (`Sum`/`Max`/`Min`/`NumberOfDifferentValues`/`SumOfProducts`) only when the relevant set
     * **wildcards** every repeatable level of the path — `ALL_SEMANTIC` at the starred group, or a relevant
     * ANCESTOR group (whose descent makes all rows relevant). Enumerating concrete rows — even ALL of them — is
     * NOT enough (kernel-probed: the `*` reference is "fully known" only via a wildcard). A relevant entity covers
     * the star iff it is an ancestor-or-equal prefix AND wildcards every repeatable level it itself specifies
     * (levels deeper than it are covered by group descent). `FirstFilled` does NOT use this — it is order-aware.
     * The same fact also gates a starred VALUE-LIST quantifier's complete extent (`No` either side / `NotAll`
     * values side — [io.github.mbackschat.a12.dm.interpreter.eval.evalValueListQuant], SPEC-2026-07-22-09).
     */
    private fun starRelevantOf(relevant: Set<RelevantEntity>): (String) -> Boolean = { starPath ->
        val sParts = pathParts(starPath)
        relevant.any { r ->
            val rParts = pathParts(r.path)
            rParts.size <= sParts.size && rParts == sParts.take(rParts.size) &&
                r.indices.indices.all { i ->
                    r.indices[i] == RelevantEntity.ALL || "/" + rParts.take(i + 1).joinToString("/") !in model.repeatable
                }
        }
    }

    /** The relevant set a [io.github.mbackschat.a12.dm.interpreter.model.CustomCondition] sees in PARTIAL mode —
     *  the kernel feeds it the caller's relevant entities (wildcards preserved, groups NOT expanded) **plus the
     *  auto-added global fields** (IF37/IF38, `MainValidatorController` → `getAllPruefungsrelevanteEntitiesExtern`).
     *  Full validation passes `null` ("all relevant"); here the caller's [RelevantEntity]s map straight to
     *  [CustomConditionFieldPointer]s and each global field rides as an all-reps-wildcard pointer. */
    private fun customRelevantOf(relevant: Set<RelevantEntity>): Set<CustomConditionFieldPointer> =
        relevant.mapTo(HashSet()) { CustomConditionFieldPointer(it.path, it.indices) } +
            model.globalFields.map { CustomConditionFieldPointer(it, List(pathParts(it).size) { RelevantEntity.ALL }) }

    /**
     * The shared validation pipeline, parameterized by [isRelevant] (`(path, reps) → Boolean`). Full
     * validation passes [ALL_RELEVANT]; partial passes a relevance predicate. Formal errors first — the
     * kernel checks every field before evaluating rules: format errors on every filled-but-malformed field,
     * and `mandatoryField` on every required-but-empty field. Both make the field "unknown", so a rule
     * referencing it sees Val.Unknown (the 3VL propagates it). Every emitted message is gated by its error
     * field's relevance.
     */
    private fun validateWith(doc: Document, locale: String, isRelevant: Relevance,
            customRelevant: Set<CustomConditionFieldPointer>? = null, starRelevant: ((String) -> Boolean)? = null,
            groupRelevance: (String, List<Int>) -> GroupRelevance = { _, _ -> GroupRelevance.FULL }): ValidationOutcome =
        observeCaches(doc) {
            // The index-default staging split (SPEC-2026-07-23-14): a FULL call stages each eligible
            // clean-empty index cell's S-value into a call-local view — before the generated checks and
            // authored rules, never mutating the caller's document (the kernel's transient evaluation
            // cache); a PARTIAL call does NOT stage and instead silently marks those cells unavailable
            // (the `unavailable` seam of the formal-status cache below).
            val indexDefaults = checks.eligibleIndexDefaults(doc)
            val full = isRelevant === ALL_RELEVANT
            val call = if (full && indexDefaults.isNotEmpty()) doc.withComputed(indexDefaults) else doc
            val unavailable = if (full) emptySet() else indexDefaults.keys
            val dup = checks.duplicateIndexCells(call, isRelevant)   // computed ONCE: feeds both the uniqueIndex messages and the suppression
            lateinit var formalResults: FormalResultCache
            val admittedGroupContent by lazy(LazyThreadSafetyMode.NONE) {
                AdmittedGroupContentIndex.build(model, call, isRelevant, groupRelevance, formalResults, paths)
            }
            formalResults = FormalResultCache(checks, locale, dup, unavailable) { ctx, path ->
                admittedGroupContent.isFilled(ctx, path)
            }
            // Custom-type unsupported is collected up front (cells whose type is in NEITHER registry); formalErrors
            // ADDS to it any REGISTERED code-based type whose validate() can't run (a JS worker down / errored), so
            // that degrades to unsupported per cell rather than crashing the whole validation.
            val unsupported = checks.customTypeUnsupported(call, isRelevant).toMutableList()
            val messages = (checks.formalErrors(call, locale, isRelevant, formalResults, unsupported) +
                    checks.mandatoryErrors(call, locale, isRelevant, formalResults)
                    + checks.uniquenessMessages(call, dup, locale, isRelevant)
                    + checks.overRepetitionMessages(call, locale, isRelevant)).toMutableList()
            // The per-call seam (IF117): the formal-status memo spans ALL rules of this validation (the doc is
            // immutable here), and the EvalConfig is built once for the whole call.
            val formallyIncorrect by lazy(LazyThreadSafetyMode.NONE) {
                formalResults.invalidPointers()
            }
            val groupPresence by lazy(LazyThreadSafetyMode.NONE) {
                GroupPresenceIndex(admittedGroupContent, formallyIncorrect, groupRelevance)
            }
            val pass = ValidationPass(call, doc, locale, isRelevant, dup,
                validationConfig(isRelevant, customRelevant, starRelevant) { ctx, path -> groupPresence.state(ctx, path) },
                formalResults, { formallyIncorrect })
            for (rule in model.rules) {
                try {
                    // RepetitionNotUnique now flows through the ordinary rule path as a 3VL leaf (IF149) — no
                    // recognizer/emitter special-casing; [rowOutcomes] builds its duplicate cache and threads it in.
                    messages += messagesFor(rule, pass)
                } catch (e: UnsupportedConstructException) {
                    unsupported += UnsupportedRule(rule.path, e.message ?: "unsupported construct")
                }
            }
            // Every computation is ALSO a validation rule (KERNEL-FINDINGS, IF32/IF65): the kernel auto-generates one
            // implicit validation per computation — a field gate `FieldFilled(F) And` then one guarded
            // `[F] != <operation>` branch PER alternative. Unlike computation's first-holding-alternative scan,
            // every holding mismatch remains in this OR, so an overlapping later tier can fire validation even
            // though it cannot supply the computed value (IF175). Evaluated through the same rule machinery.
            for (implicit in parsedModel.implicitRules) {
                try {
                    messages += messagesFor(implicit, pass)
                } catch (e: UnsupportedConstructException) {
                    unsupported += UnsupportedRule(
                        implicit.errorEntityRelPath,
                        e.message ?: "unsupported computation validation",
                    )
                }
            }
            ValidationOutcome(messages, unsupported)
        }

    /**
     * Evaluate a SINGLE stored rule against the instance — the `rule eval` shape. [Verdict.FIRED] (a
     * violation, with the rendered [RuleResult.message]) / [Verdict.PASSED] (evaluated, clean) /
     * [Verdict.SUPPRESSED] (never evaluated — a referenced field is formally invalid, named in
     * [RuleResult.suppressedBy]).
     */
    fun testRule(rulePath: String, doc: Document, locale: String = "en_US"): RuleResult {
        val rule = model.rules.firstOrNull { it.path == rulePath }
            ?: throw IllegalArgumentException("no such rule: $rulePath")
        return evalRule(rule, doc, locale)
    }

    /** Evaluate an injected, NON-stored candidate rule (the `model eval --condition/--field` shape). */
    fun evalCandidate(rule: RuleDef, doc: Document, locale: String = "en_US"): RuleResult =
        evalRule(rule, doc, locale)

    /**
     * Run every computation over each row of its iteration scope and return each computed-field outcome
     * — the runtime twin of the kernel's `compute()`. VALUE (the expression resolved, empty-as-0 applies)
     * / CLEARED (the expression computed nothing) / ERRORED (the computed value is formally invalid for
     * the target).
     *
     * **Reporting granularity — report-ALL, deliberately wider than the kernel's change projection.** This
     * returns an outcome for **every** computed instance. The kernel's `IDocumentComputationResult` is a
     * five-channel result (successful-incl-unchanged / its changed subset / errored / cleared / operand
     * formal errors; KERNEL-FINDINGS §11 five-channel supplement); our corpus/differential compares against
     * its **change projection** (changed values / clears of input-filled cells). Report-all is the more useful
     * library contract ("what does this field compute to?"), and result-envelope granularity is not
     * behavior-parity territory — a comparison against a kernel-granularity expectation projects via
     * [io.github.mbackschat.a12.dm.interpreter.conformance.kernelComputeGranularity].
     */
    fun compute(doc: Document, locale: String = "en_US"): List<Computed> =
        observeCaches(doc) { engine.computeInstances(doc, locale) }

    /**
     * Evaluate a SINGLE computed field against the instance — the single-computation twin of [testRule], the
     * "run this field like a function" primitive (IF129, graduated from IG74). Computes [fieldPath]'s transitive
     * computed-dependency CLOSURE in dependency order (so a cascade operand is never read stale/empty) and returns
     * the target's outcome PER INSTANCE — one [Computed] per row of a repeated computed field, VALUE / CLEARED /
     * ERRORED, with poison + precondition semantics intact. Behaviour-identical to [compute] filtered to
     * [fieldPath], just pruned to the needed subset — an unrelated computation cannot feed the target
     * (`ComputeFieldInvariantTest`/`ComputeFieldDiffTest`). Throws [IllegalArgumentException] if no computation
     * targets [fieldPath] (a non-computed field has no outcome). [locale] rides for compute-family symmetry; like
     * [compute] the result is locale-independent (computed values, not rendered messages).
     */
    fun computeField(fieldPath: String, doc: Document, locale: String = "en_US"): List<Computed> {
        require(model.computations.any { it.targetPath == fieldPath }) { "no computation targets field: $fieldPath" }
        return observeCaches(doc) { engine.computeField(fieldPath, doc, locale) }
    }

    /**
     * Apply this model's computations to [doc] and return the **updated document** (old + computed values) —
     * the interpreter's twin of the kernel's `compute().applyTo(doc)` (KERNEL-FINDINGS "validateFull does NOT
     * run computations": the kernel keeps validate and compute separate and the consumer applies). A VALUE
     * outcome sets the computed cell; an ERRORED or CLEARED outcome empties it (matching the kernel's `applyTo`,
     * which applies the changed values and CLEARS both the errored and the cleared instances). The original
     * [doc] is unchanged.
     *
     * This is the apply-computed-back primitive behind the **form-engine validation flow**
     * `validate(applyComputations(doc))` — what the running application does, and the default behaviour of the
     * `model eval` verb (a required-but-computed field is filled by the computation before the required check
     * runs, so it is not a false `mandatoryField`).
     */
    fun applyComputations(doc: Document, locale: String = "en_US"): Document =
        observeCaches(doc) {
            val overrides = HashMap<Pair<String, List<Int>>, String?>()
            for (computed in engine.computeInstances(doc, locale)) {
                overrides[computed.path to computed.indices] =
                    if (computed.outcome == ComputeOutcome.VALUE) computed.value else null
            }
            doc.withComputed(overrides)
        }

    private fun evalRule(rule: RuleDef, doc: Document, locale: String): RuleResult = observeCaches(doc) {
        // A single-rule pass (fresh formal-status memo, everything relevant) — the testRule/evalCandidate shape.
        // Full-call semantics, so the eligible index defaults are staged exactly as in [validate] (SPEC-2026-07-23-14).
        val indexDefaults = checks.eligibleIndexDefaults(doc)
        val call = if (indexDefaults.isEmpty()) doc else doc.withComputed(indexDefaults)
        val dup = checks.duplicateIndexCells(call)
        val groupRelevance = { _: String, _: List<Int> -> GroupRelevance.FULL }
        lateinit var formalResults: FormalResultCache
        val admittedGroupContent by lazy(LazyThreadSafetyMode.NONE) {
            AdmittedGroupContentIndex.build(model, call, ALL_RELEVANT, groupRelevance, formalResults, paths)
        }
        formalResults = FormalResultCache(checks, locale, dup) { ctx, path ->
            admittedGroupContent.isFilled(ctx, path)
        }
        val formallyIncorrect by lazy(LazyThreadSafetyMode.NONE) {
            formalResults.invalidPointers()
        }
        val groupPresence by lazy(LazyThreadSafetyMode.NONE) {
            GroupPresenceIndex(admittedGroupContent, formallyIncorrect, groupRelevance)
        }
        val pass = ValidationPass(
            call,
            doc,
            locale,
            ALL_RELEVANT,
            dup,
            validationConfig(ALL_RELEVANT, null, null) { ctx, path -> groupPresence.state(ctx, path) },
            formalResults,
            { formallyIncorrect },
        )
        val rows = rowOutcomes(rule, pass)
        rows.firstNotNullOfOrNull { it as? RowOutcome.Fired }?.let {
            return@observeCaches RuleResult(rule.path, Verdict.FIRED, it.message.text, it.message.type)
        }
        // No firing: a referenced field being formally invalid means the rule was NOT evaluated (the
        // kernel skips it) → SUPPRESSED, not PASSED. PASSED only when every row was cleanly evaluated.
        val suppressed = rows.filterIsInstance<RowOutcome.Suppressed>().map { it.suppression }
        if (suppressed.isNotEmpty()) RuleResult(rule.path, Verdict.SUPPRESSED, suppressedBy = suppressed)
        else RuleResult(rule.path, Verdict.PASSED)
    }

    private inline fun <T> observeCaches(doc: Document, action: () -> T): T {
        val observer = cacheObserver ?: return action()
        return try {
            action()
        } finally {
            doc.observeCacheEntries(observer)
            parsedModel.observeCacheEntries(observer)
        }
    }

    /** Every firing of one rule across its iteration-scope rows, as [Message]s (suppressed rows produce none). */
    private fun messagesFor(rule: RuleDef, pass: ValidationPass): List<Message> =
        rowOutcomes(rule, pass).filterIsInstance<RowOutcome.Fired>().map { it.message }

    /**
     * The invariant [EvalConfig] for one validate call — the bundle every rule's per-row [Interpreter] shares,
     * built ONCE (not per row). The relevance seams carry the partial-validation set: [EvalConfig.cellRelevant]
     * is per-cell relevance for `FirstFilled`'s order-aware gate (IF42; null in full validation so the gate is
     * skipped and the flat-spike `RepDoc` path is untouched), [EvalConfig.starRelevant] whether an all-rows
     * aggregate's star is wildcard-relevant (IF42), [EvalConfig.customRelevant] the set a `CustomCondition` sees
     * (null in full, the caller's set + globals in partial — IF38).
     */
    private fun validationConfig(isRelevant: Relevance, customRelevant: Set<CustomConditionFieldPointer>?,
            starRelevant: ((String) -> Boolean)?,
            groupPresence: (EvalContext, String) -> io.github.mbackschat.a12.dm.interpreter.eval.GroupPresenceState): EvalConfig =
        EvalConfig(clock, model.baseYear, model.indexFields, timeZone = model.timeZone,
            predefinedTypes = predefinedTypes, customConditions = customConditions, customFieldTypes = customFieldTypes,
            patternMatches = patterns::matches,
            cellRelevant = if (isRelevant === ALL_RELEVANT) null else { path, reps -> isRelevant(path, reps) },
            starRelevant = starRelevant, customRelevant = customRelevant, groupPresence = groupPresence)

    /** The kernel's allowed-and-eliminated raw-type length shape (see [rowOutcomes]): the whole condition is
     *  `Length(nvv) > constant` or the mirrored `constant < Length(nvv)` — GT/LT only (`>=`-style variants are
     *  model-rejected `MVK_INVALID_LENGTH_OF_RAW_TYPE`, so they never reach a legal eval), the one `Length`
     *  argument a plain field ref whose declared constraints carry `noValueValidation`. */
    private fun eliminatedNvvMetaLengthRule(ast: Cond): Boolean {
        if (ast !is Cond.Cmp) return false
        val (fn, lit) = when (ast.op) {
            CmpOp.GT -> ast.left to ast.right
            CmpOp.LT -> ast.right to ast.left
            else -> return false
        }
        if (lit !is Expr.NumLit || fn !is Expr.Func || FunctionOp.byName(fn.name) != FunctionOp.LENGTH) return false
        val field = fn.args.singleOrNull() as? Expr.Field ?: return false
        return model.constraintsOf(field.path).noValueValidation
    }

    /** The per-row outcome of one rule: fired (with its message), passed, or suppressed (which field/code). */
    private sealed interface RowOutcome {
        data class Fired(val message: Message) : RowOutcome
        data object Passed : RowOutcome
        data class Suppressed(val suppression: Suppression) : RowOutcome
    }

    private fun rowOutcomes(rule: RuleDef, pass: ValidationPass): List<RowOutcome> {
        val prepared = preparedModel.rule(rule)
        // Kernel codegen puts this method-entry return before iteration and evaluation for every rule whose
        // AST contains a Having filter. Keep the partial path O(1); full validation continues normally.
        if (pass.partial && prepared.hasFilterConditions) return emptyList()
        val ast = prepared.ast
        // The nvv META-LENGTH rule is ELIMINATED — never evaluated, no rows (IG71 → IF125). A rule whose WHOLE
        // condition is `Length(nvvField) > constant` (or the mirrored `constant < Length(nvvField)`) is the
        // kernel's disguised max-length declaration for a raw-type field: the codegen REMOVES the rule and only
        // tightens the generated meta-model's max length (`ValidationCodeGenerator.
        // handleLengthOfAllowedValueValidationRule` → `addMaxLengthDefinedByRules`; the same extraction as
        // `AnalyseService.getNameAndLengthOfAllowedValueValidation` — GT/LT-mirrored only), in BOTH strategies.
        // This is the ONLY kernel-legal value-ish read of an nvv field (every other value position is
        // model-rejected: MVK_INVALID_RAW_TYPE / MVK_INVALID_LENGTH_OF_RAW_TYPE / INVALID_NO_VALUE_VALIDATION),
        // so eliminating the rule — not inventing a per-read np value state — is the mechanism-exact twin.
        if (eliminatedNvvMetaLengthRule(ast)) return emptyList()
        val refs = nonStarredRefs(ast).distinct()
        val errorPath = resolveErrorPath(rule)
        // A rule whose condition contains a RepetitionNotUnique leaf gets its duplicate cache built ONCE (IF149),
        // guard-independent, keyed by error-path reps; the per-row seam looks the current row up in O(1). Null for
        // a normal rule (no cache, no seam). Building here can throw UnsupportedConstructException (>1 RNU, a
        // group/asterisk key, a too-deep scope) — the caller's per-rule tolerance reports it unsupported.
        val rnuCache = rnuEval.outcomes(prepared.repetitionNotUnique, pass)
        val ordinaryMessageRefs = if (rnuCache == null) refs else nonRnuMessageRefs(ast).distinct()
        val ordinaryRefsOutsideRnuKeys = rnuCache?.let { cache ->
            val rnuKeys = cache.keys.toSet()
            ordinaryMessageRefs.filterNot(rnuKeys::contains)
        }
        val rnuSeam: ((EvalContext) -> MsgType?)? =
            rnuCache?.let { cache -> { context -> cache.rows[context.repsOf(errorPath)]?.type } }
        return scopes.iterationContexts(prepared.iteration, pass.doc).map { ctx ->
            // Partial validation: the kernel runs a rule only if its error-field instance is relevant — else
            // the rule is NOT executed for this row (no message, not a suppression). Full validation: always.
            if (!pass.isRelevant(errorPath, ctx.repsOf(errorPath))) return@map RowOutcome.Passed
            // A formally-invalid referenced field resolves to Val.Unknown (NOT a whole-rule skip): the 3VL
            // propagates it, so a bare comparison goes UNKNOWN but `… Or <true>` still FIRES — matching the
            // kernel's operand-level "not check-relevant" behavior (verified). A duplicate-index field is
            // likewise unknown (branch-scoped, same as a formal error) via [formalErrorAt]. In partial mode a
            // referenced field instance that is NOT relevant is also unknown — the same 3VL yields the kernel's
            // suppression (`And` suppresses, `Or` with a relevant-true operand still fires). The custom-condition
            // registry + the row's error-field pointer ride along for a `CustomCondition X` leaf; [rnuSeam] the
            // per-row RepetitionNotUnique cache outcome for an RNU leaf (IF149).
            val interp = pass.interpreterFor(ctx, errorPath, rnuSeam)
            // ONE typed walk per row (the fired MsgType, or null if it doesn't fire) — firedType != null ⟺ the
            // rule fires (truth == TRUE), and the value IS the polarity. The walk is LAZY-polarity: it skips the
            // VALUE/OMISSION typing on non-firing nodes (the kernel types only firing rows), measured faster in
            // both regimes. (A kernel-style evalCond-then-evalTyped two-phase guard was measured a net loss — the
            // second walk re-traverses + re-evals leaves; the lazy single walk gets the skip without it.
            // INTERPRETER-PERFORMANCE.md #62 / results log.)
            val firedType = interp.firedType(ast)
            if (firedType != null) {
                val type = firedType
                val text = rule.messages[pass.locale]?.let {
                    renderMessage(
                        it,
                        rule.group,
                        ctx,
                        defaultValueOf = model::messageDefaultOf,
                        labelOf = { path -> messageLabel(path, pass.locale, ctx) },
                        // `$field.value$` reads the ORIGINAL caller document, not the staged eval view: a staged
                        // enum index cell interpolates as if empty (kernel `getDaten().getValueForDisplay`; IF221).
                        messageValueOf = { path -> pass.callerDoc.messageDisplayValueAt(path, ctx.repsOf(path)) },
                    )
                } ?: ""
                val matchingRnuOutcome = rnuCache?.rows?.get(ctx.repsOf(errorPath))?.takeIf { it.type == type }
                // When the RNU leaf contributes this message's polarity, its cluster projection already contains
                // every occurrence of an RNU key path. Drop a same-path ordinary occurrence, render the remaining
                // small prefix, and expose an O(1) immutable joined view over the shared cluster list. This keeps
                // exact list semantics without copying the N-entry peer projection into every one of N messages.
                val ordinaryRefs = if (matchingRnuOutcome != null) ordinaryRefsOutsideRnuKeys.orEmpty() else ordinaryMessageRefs
                val ordinaryPointers = ordinaryRefs.map { pass.doc.formattedPointer(it, ctx.repsOf(it)) }
                val rnuPointers = matchingRnuOutcome?.peerKeyPointers.orEmpty()
                val referenced = joinedList(ordinaryPointers, rnuPointers)
                // refOmissionErrorResponsible: for an OMISSION error the kernel attaches ALL the operands the
                // omission depends on (verified — both the filled and the empty operand of an And), so filling/
                // changing any could resolve it. Empty for a VALUE error (no fill fixes a value).
                val fillToFix = if (type == MsgType.OMISSION) referenced else emptyList()
                RowOutcome.Fired(Message(rule.errorCode, rule.path, pass.doc.formattedPointer(errorPath, ctx.repsOf(errorPath)),
                    rule.severity, type, text, referenced, fillToFix))
            } else {
                // Not fired: a formally-invalid operand means the rule was NOT cleanly evaluated → SUPPRESSED
                // (the `rule eval` third verdict), naming the offending field/code; else a clean PASSED.
                pass.firstSuppressor(refs, ctx)?.let { RowOutcome.Suppressed(it) } ?: RowOutcome.Passed
            }
        }
    }

    private fun messageLabel(path: String, locale: String, ctx: EvalContext): String {
        val supplied = model.fieldOf(path)?.let { field -> messageLabelProvider?.label(field, locale) }
        if (supplied != null) return supplied
        model.declaredLabelOf(path, locale)?.takeIf { it.isNotEmpty() }?.let { return it }
        val repetitions = ctx.repsOf(path)
        return if (repetitions.isEmpty()) path else "$path/${repetitions.joinToString(".")}"
    }

    /** Immutable concatenation without copying either input; both inputs are call-local immutable projections. */
    private fun <T> joinedList(prefix: List<T>, suffix: List<T>): List<T> = when {
        prefix.isEmpty() -> suffix
        suffix.isEmpty() -> prefix
        else -> JoinedList(prefix, suffix)
    }

    private class JoinedList<T>(private val prefix: List<T>, private val suffix: List<T>) : AbstractList<T>() {
        override val size: Int = prefix.size + suffix.size

        override fun get(index: Int): T = when {
            index < 0 || index >= size -> throw IndexOutOfBoundsException("index $index, size $size")
            index < prefix.size -> prefix[index]
            else -> suffix[index - prefix.size]
        }
    }

    /**
     * Build the [Relevance] predicate from a relevant-entity set: an instance `(path, reps)` is relevant iff
     * some [RelevantEntity] **is** it or is an **ancestor group** of it, with the shared-prefix repetition
     * indices matching ([RelevantEntity.ALL] wildcards a level). A relevant group thus makes every descendant
     * relevant (the kernel rule). An empty set → nothing relevant (no rule runs).
     */
    private fun relevanceOf(relevant: Set<RelevantEntity>): Relevance =
        // The kernel's md-runtime-service auto-adds every GLOBAL field (wildcarded at all reps) to the relevant
        // set before validating (LowKernelApiUtils.addGlobalFieldsToRelevantEntities), so a global field's
        // instances are relevant regardless of the caller's set — IG11→IF37, KERNEL-FINDINGS "Partial validation".
        { path, reps -> path in model.globalFields || relevant.any { matches(it, path, reps) } }

    /**
     * Partial validation's two group-relevance levels. Any selected descendant makes its ancestors PARTIAL,
     * which is enough to prove positive presence. Absence is known only when an ancestor selection covers the
     * whole group, or every declared descendant field is covered across every deeper repeatable level.
     */
    private fun groupRelevanceOf(relevant: Set<RelevantEntity>): (String, List<Int>) -> GroupRelevance {
        val effective = relevant + model.globalFields.map(RelevantEntity::anyRep)
        val fieldsByAncestor = HashMap<String, MutableList<String>>()
        for (field in model.fields) {
            val parts = pathParts(field.path)
            for (depth in 1 until parts.size) {
                fieldsByAncestor.getOrPut("/" + parts.take(depth).joinToString("/")) { ArrayList() } += field.path
            }
        }
        val cache = HashMap<String, HashMap<List<Int>, GroupRelevance>>()

        fun touchesGroup(entity: RelevantEntity, groupPath: String, groupReps: List<Int>): Boolean {
            val entityParts = pathParts(entity.path)
            val groupParts = pathParts(groupPath)
            val common = minOf(entityParts.size, groupParts.size)
            if (entityParts.take(common) != groupParts.take(common)) return false
            return (0 until minOf(common, entity.indices.size, groupReps.size)).all { level ->
                entity.indices[level] == RelevantEntity.ALL || entity.indices[level] == groupReps[level]
            }
        }

        fun fullyCoversField(
            entity: RelevantEntity,
            fieldPath: String,
            groupPath: String,
            groupReps: List<Int>,
        ): Boolean {
            if (!touchesGroup(entity, groupPath, groupReps)) return false
            val entityParts = pathParts(entity.path)
            val fieldParts = pathParts(fieldPath)
            if (entityParts.size > fieldParts.size || entityParts != fieldParts.take(entityParts.size)) return false
            val groupDepth = pathParts(groupPath).size
            for (level in groupDepth until entityParts.size) {
                val prefix = "/" + entityParts.take(level + 1).joinToString("/")
                if (prefix in model.repeatable && entity.indices[level] != RelevantEntity.ALL) return false
            }
            return true
        }

        return { groupPath, groupReps ->
            cache.getOrPut(groupPath) { HashMap() }.getOrPut(groupReps) {
                if (effective.none { touchesGroup(it, groupPath, groupReps) }) {
                    GroupRelevance.NONE
                } else if (effective.any { matches(it, groupPath, groupReps) } ||
                    fieldsByAncestor[groupPath].orEmpty().all { field ->
                        effective.any { fullyCoversField(it, field, groupPath, groupReps) }
                    }) {
                    GroupRelevance.FULL
                } else {
                    GroupRelevance.PARTIAL
                }
            }
        }
    }

    /** Whether relevant entity [r] covers the instance at [path]/[reps] — [r.path] a prefix of [path] (== or a
     *  group ancestor) with each of [r]'s indices either [RelevantEntity.ALL] or equal to the instance's. */
    private fun matches(r: RelevantEntity, path: String, reps: List<Int>): Boolean {
        val rParts = pathParts(r.path)
        val pParts = pathParts(path)
        if (rParts.size > pParts.size || r.indices.size != rParts.size) return false
        if (rParts != pParts.take(rParts.size)) return false
        return r.indices.indices.all { i -> r.indices[i] == RelevantEntity.ALL || r.indices[i] == reps[i] }
    }
}
