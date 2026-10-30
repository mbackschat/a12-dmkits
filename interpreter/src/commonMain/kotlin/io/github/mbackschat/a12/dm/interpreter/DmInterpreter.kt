package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.parse.A12Lexis
import io.github.mbackschat.a12.dm.interpreter.parse.parseExpression
import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.ComputationApplication
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.eval.RuntimeEvaluationFailure
import io.github.mbackschat.a12.dm.interpreter.eval.evalTyped
import io.github.mbackschat.a12.dm.interpreter.eval.WorkLedger
import io.github.mbackschat.a12.dm.interpreter.eval.FunctionOp
import io.github.mbackschat.a12.dm.interpreter.eval.GroupCountGate
import io.github.mbackschat.a12.dm.interpreter.eval.GroupPresenceState
import io.github.mbackschat.a12.dm.interpreter.eval.GroupRelevance
import io.github.mbackschat.a12.dm.interpreter.eval.UNKNOWN_REP
import io.github.mbackschat.a12.dm.interpreter.eval.DisplayPresentation
import io.github.mbackschat.a12.dm.interpreter.eval.MessageTerminals
import io.github.mbackschat.a12.dm.interpreter.eval.presentDisplayValue
import io.github.mbackschat.a12.dm.interpreter.eval.SelectedCell
import io.github.mbackschat.a12.dm.interpreter.eval.messageFieldPath
import io.github.mbackschat.a12.dm.interpreter.eval.renderMessage
import io.github.mbackschat.a12.dm.interpreter.eval.semanticIndexRepetitions
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.Computed
import io.github.mbackschat.a12.dm.interpreter.model.ComputationEvaluation
import io.github.mbackschat.a12.dm.interpreter.model.UnsupportedSubject
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.Requiredness
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
import io.github.mbackschat.a12.dm.interpreter.model.UndeclaredReference
import io.github.mbackschat.a12.dm.interpreter.model.UnsupportedRule
import io.github.mbackschat.a12.dm.interpreter.model.ValidationOutcome
import io.github.mbackschat.a12.dm.interpreter.model.Verdict
import io.github.mbackschat.a12.dm.interpreter.regex.PatternRegistry
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmSynthetic
import io.github.mbackschat.a12.dm.interpreter.workspace.ImmutableProjection

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
class DmInterpreter private constructor(
    private val model: ModelDef,
    /** The clock for one call, asked once per validation or computation call, as A12 samples its current instant
     *  per call when `currentDateForTest` is unset. A source yields a clock on every call or on none. */
    private val clock: () -> EvalClock?,
    /** The consumer-supplied predefined-type registry (`Valid`/`Invalid(field, "Name")`) — the kernel's
     *  `IPredefinedType` client SPI. Defaults to the built-in common set ([PredefinedTypeRegistry.DEFAULT]);
     *  a consumer may pass its own (built-ins + project types). An unregistered name → the rule is unsupported. */
    private val predefinedTypes: Map<String, PredefinedType>,
    /** Consumer-supplied custom conditions (the kernel's `ICustomCondition` SPI, cross-platform JVM+JS), keyed
     *  by name — `CustomCondition X`. Default empty: a model using an unregistered custom condition reports it
     *  unsupported (fail-fast). A JVM consumer adapts existing kernel `ICustomCondition` impls onto the SPI; a
     *  JS consumer implements the SPI directly. */
    private val customConditions: Map<String, CustomCondition>,
    /** Consumer-supplied CODE-BASED custom field types ([CustomFieldType] — the kernel's `ICustomFieldType` SPI,
     *  for imperative checksum types), keyed by name. Default empty. Consulted ONLY after [predefinedTypes] misses,
     *  so the declarative per-cell path stays monomorphic ([CustomFieldType]'s KDoc; `CustomTypePerfTest`). Each
     *  relevant concrete value is checked once per call with its locale/effective bounds/stored-value context;
     *  the cached code/message result drives both emission and suppression. A custom-typed cell whose name is in
     *  NEITHER registry is reported unsupported (the lenient degrade). */
    private val customFieldTypes: Map<String, CustomFieldType>,
    /** The consumer-supplied legal character set field values are validated against — the SPI for a project charset
     *  the DM-JSON does NOT carry (e.g. **DIN 91379**, the EU public-administration set), IF43. **Default
     *  [LegalCharset.BMP]** (the kernel's runtime default: an emoji / supplementary-plane char is illegal). This is
     *  the FALLBACK: when the model itself declares `modelConfig.supportedCharacters` ([ModelDef.legalCharset],
     *  IF120) that model-declared slot takes precedence (it is what the kernel bakes into codegen and enforces), and
     *  this SPI applies only when the model declares none. The interpreter must expose every kernel customization to
     *  be 100% compatible. */
    private val legalCharset: LegalCharset,
    /** Consumer override for `$field$` message names. A non-null result wins exactly (including empty); null
     *  falls through to the nonempty model label, then the field's indexed debug identity. */
    private val messageLabelProvider: MessageLabelProvider?,
    plans: ModelStaticPlans?,
) {

    /** The message-parameter terminal spellings this model's CONDITION language selects ([KF198]). */
    private val messageTerminals = MessageTerminals.of(model.conditionLanguage)

    /**
     * A `$field.value$` parameter renders the stored value RE-PRESENTED for the message locale — the locale
     * date pattern and number separators, never the field's declared storage format ([KF199]). An unmapped
     * locale has no presentation, which is the kernel's own behaviour and not a fallback we chose.
     */
    private fun presentedDisplayValue(stored: String?, path: String, locale: String): String? {
        if (stored.isNullOrEmpty()) {
            return stored
        }
        val presentation = DisplayPresentation.of(locale) ?: return stored
        val kind = model.kinds[path] ?: return stored
        return presentDisplayValue(
            stored, kind, model.dateLikeFormatOf(path), model.rangeSeparatorOf(path), presentation)
    }

    /**
     * The public construction path, unchanged. It owns a private [ModelStaticPlans], so a directly constructed
     * evaluator behaves exactly as before; sharing happens only through [PreparedModel].
     */
    @JvmOverloads
    constructor(
        model: ModelDef,
        clock: EvalClock? = null,
        predefinedTypes: Map<String, PredefinedType> = PredefinedTypeRegistry.DEFAULT,
        customConditions: Map<String, CustomCondition> = emptyMap(),
        customFieldTypes: Map<String, CustomFieldType> = emptyMap(),
        legalCharset: LegalCharset = LegalCharset.BMP,
        messageLabelProvider: MessageLabelProvider? = MessageLabelProvider.MODEL_LABEL_OR_NAME,
    ) : this(
        model, { clock }, predefinedTypes, customConditions, customFieldTypes, legalCharset, messageLabelProvider,
        plans = null,
    )

    /** The model-static preparation this evaluator reads: shared when supplied, private otherwise. */
    private val plans: ModelStaticPlans = plans ?: ModelStaticPlans.of(model)

    companion object {
        /**
         * Build an evaluator that SHARES [plans] with every other evaluator over the same model — the sharing
         * boundary the P-SERVICE profile needs, reached through [PreparedModel.createInterpreter] rather than by
         * a consumer assembling one.
         *
         * This is a factory rather than an `internal` constructor on purpose: Kotlin emits an `internal`
         * constructor as `ACC_PUBLIC`, so a Java caller could reach it and bypass the boundary. That exact trap
         * was closed once before for `A12PartiallyKnownMultiPointer` (see the interpreter CHANGELOG) and must not
         * be reopened. `@JvmSynthetic` hides this function from Java; it cannot be applied to a constructor.
         */
        @JvmSynthetic
        internal fun sharing(
            model: ModelDef,
            clock: () -> EvalClock?,
            predefinedTypes: Map<String, PredefinedType>,
            customConditions: Map<String, CustomCondition>,
            customFieldTypes: Map<String, CustomFieldType>,
            legalCharset: LegalCharset,
            messageLabelProvider: MessageLabelProvider?,
            plans: ModelStaticPlans,
        ): DmInterpreter = DmInterpreter(
            model, clock, predefinedTypes, customConditions, customFieldTypes, legalCharset,
            messageLabelProvider, plans,
        )
    }

    /** The effective legal charset: the model-declared `supportedCharacters` ([ModelDef.legalCharset], IF120) when
     *  present, else the consumer-supplied [legalCharset] SPI. */
    private val effectiveLegalCharset: LegalCharset = model.legalCharset ?: legalCharset
    private val patterns = PatternRegistry()

    /** Model-static path prefixes and repetition bounds, prepared once per MODEL rather than per evaluator. */
    private val paths = this.plans.paths
    private val partial = PartialRelevance(model, paths)

    /** The rich message channel's pointer projection, over the same path index. */
    private val pointers = MessagePointers(model, paths)

    /** The auto-check emitter + formal-status oracle — the call-invariant I2 seam collaborator (IF117). */
    private val checks = AutoChecks(
        model,
        effectiveLegalCharset,
        predefinedTypes,
        customFieldTypes,
        patterns,
        paths,
        ::messageLabel,
    )

    /** The iteration-scope machinery + the RepetitionNotUnique evaluator — call-invariant I2 seam collaborators
     *  (IF117), now model-owned so per-request evaluators share one build. */
    private val scopes = this.plans.scopes
    private val rnuEval = this.plans.repetitionNotUnique

    /** Nullable deterministic seam used only by model-static performance guards. It delegates to the shared
     *  preparation, because that is where the model-static work now happens. */
    internal var preparationObserver: PreparationObserver?
        get() = plans.preparationObserver
        set(value) { plans.preparationObserver = value }

    private val parsedModel = this.plans.parsed
    /** Nullable, instance-local structural seam used only by the cache-lifetime guard. */
    internal var cacheObserver: CacheObserver? = null
    /** Nullable, instance-local work tally used only by the deterministic work guard ([WorkLedger]). */
    internal var workLedger: WorkLedger? = null
    private val preparedModel = PreparedEvaluationPlan(
        model,
        this.plans,
        support = PreparationSupport(
            clock = clock(),
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
        this.plans,
        checks,
        patterns = patterns,
        cacheObserver = { cacheObserver },
        workLedger = { workLedger },
    )

    /**
     * Audit this prepared model without consulting document values. Every stored rule, implicit computation
     * validation, computation guard/alternative, customization requirement, and host-sensitive primitive is
     * traversed. A non-empty result means an evaluator must refuse performance/certification work up front;
     * [validate] remains the per-instance outcome-bearing check for failures raised by a registered callback.
     */
    fun supportAudit(): List<UnsupportedRule> = preparedModel.audit()

    /**
     * Every reference in this model's rules and computations that names an entity the model does not declare —
     * value-independent, so it answers before any document is read.
     *
     * Separate from [supportAudit] on purpose: an undeclared reference is not a construct we cannot evaluate but
     * a model the kernel's consistency gate rejects (`MVK_INVALID_ENTITY`), and evaluating it produces a
     * confident answer about a rule that cannot exist. It has no single failure shape either — a presence
     * predicate reads the missing cell as empty and FIRES, a starred aggregate collapses to an empty domain and
     * PASSES, and a value position throws — so a caller that wants to be honest has to ask up front rather than
     * interpret whatever came back.
     *
     * The domain checked against is the model's declared entity set, and the reference has already been bound by
     * the model's own rules, so a legal absolute / `..`-relative / `..Name` / short-name / starred / multi-star
     * reference resolves to a declared path and is not reported. Implicit computation-validation rules are
     * excluded because they are generated from the computations that are checked here.
     */
    fun undeclaredReferences(): List<UndeclaredReference> =
        (model.rules.flatMap { undeclaredReferences(it) } + undeclaredComputationReferences()).distinct()

    /**
     * The same check for ONE rule's condition — a stored rule or an ad-hoc `--condition`/`--field` candidate.
     *
     * A focused caller uses this rather than [undeclaredReferences] so it stays focused: the whole-model form
     * forces every rule's AST, which would defeat the parse-once-lazily design that lets [testRule] prepare only
     * the rule it touches. It also keeps the refusal proportionate — an unrelated rule's bad reference cannot
     * change this rule's verdict, so it is not this call's business to report.
     */
    fun undeclaredReferences(rule: RuleDef): List<UndeclaredReference> =
        undeclared(rule.path, namedEntityPaths(parsedModel.condAstOf(rule))).distinct()

    /**
     * The computation half on its own — every guard and operation. Two callers need it apart from the whole-model
     * query: [compute], which validates nothing; and a FOCUSED rule evaluation, which reaches a computation's
     * reference despite being focused whenever the form-engine flow applies the computations first. A focused
     * evaluation that skips that step reaches only its own rule, because [testRule]/[evalCandidate] never touch
     * the implicit computation-validation rules that whole-model [validate] evaluates.
     */
    fun undeclaredComputationReferences(): List<UndeclaredReference> = model.computations.flatMap { comp ->
        val locus = comp.name?.let { "${comp.group}/$it" } ?: comp.targetPath
        val parsed = parsedModel.parsedOf(comp)
        val conditions = listOfNotNull(parsed.commonPrecondition) + parsed.alternatives.mapNotNull { it.precondition }
        conditions.flatMap { undeclared(locus, namedEntityPaths(it)) } +
            parsed.alternatives.flatMap { undeclared(locus, namedEntityPaths(it.operation)) }
    }.distinct()

    private fun undeclared(locus: String, paths: List<String>): List<UndeclaredReference> =
        paths.filter { it !in model.entityPaths }.map { UndeclaredReference(locus, it) }

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
        validateWith(doc, locale, CallRelevance.FULL)

    /**
     * Tolerant **partial** validation — the runtime twin of the kernel's `validatePart(DocumentV2,
     * relevantEntities, config)`. A rule runs through ONE selector per **error field**, merged from every relevant
     * pointer of that field and its ancestor groups, a coordinate the pointers disagree on becoming a wildcard
     * ([PartialRuleDispatch], KF621): two relevant cells in different rows therefore run every row between and
     * beside them. A level the iteration never bound counts as a wildcard (iteration is reference-driven, so the
     * declared group does not decide this), and the message reports at the default first repetition (IF268).
     * A parallel rule that also iterates an ordinary level is suppressed by a concrete joined coordinate; one that
     * does not ignores the coordinates (KF544, KF621). Dispatch never widens operand relevance. In the rules it
     * selects, any reference to a field/group instance **not** in [relevant] resolves to
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
    fun validatePart(doc: Document, relevant: Set<RelevantEntity>, locale: String = "en_US"): ValidationOutcome {
        val queries = partial.relevanceQueriesOf(relevant)
        return validateWith(doc, locale, CallRelevance(
            isRelevant = queries::isRelevant,
            structuralChecksEnabled = relevant.isNotEmpty(),
            customRelevant = partial.customRelevantOf(relevant),
            starRelevant = partial.starRelevantOf(relevant),
            starExtentKnown = partial.starExtentKnownOf(relevant),
            starFullyEnumerated = partial.starFullyEnumeratedOf(relevant),
            groupRelevance = partial.groupRelevanceOf(relevant),
            groupExtents = partial.PartialGroupExtents(relevant),
            dispatch = PartialRuleDispatch(model, relevant, paths),
        ))
    }

    /**
     * The relevance seams one validation call derives from its relevant set. They are one value because they are
     * always derived together, and three of them share one function type, so the positional list they replaced
     * let a caller swap two with no compiler prompt. [FULL] is full validation: every cell relevant, every
     * partial-only seam absent.
     */
    private class CallRelevance(
        val isRelevant: Relevance,
        /** Empty partial calls skip structural checks; nonempty ones report every address (KF58). */
        val structuralChecksEnabled: Boolean = true,
        /** The set a `CustomCondition` sees: the caller's set plus the globals ([IF38]). */
        val customRelevant: Set<CustomConditionFieldPointer>? = null,
        /** Whether an all-rows aggregate's star is wildcard-relevant ([IF42]). */
        val starRelevant: ((String, Int, List<Int>) -> Boolean)? = null,
        val starExtentKnown: ((String, Int, List<Int>) -> Boolean)? = null,
        val starFullyEnumerated: ((String, Int, List<Int>) -> Boolean)? = null,
        val groupRelevance: (String, List<Int>) -> GroupRelevance = { _, _ -> GroupRelevance.FULL },
        /** Missing-position coverage for group predicates and the stricter count gate ([IF193], [IF339]). */
        val groupExtents: PartialRelevance.PartialGroupExtents? = null,
        val dispatch: PartialRuleDispatch? = null,
    ) {
        companion object {
            val FULL = CallRelevance(ALL_RELEVANT)
        }
    }


    /**
     * The shared validation pipeline, parameterized by the call's [relevance]. Full validation passes
     * [CallRelevance.FULL]; partial passes the seams its relevant set derives. Formal errors first — the
     * kernel checks every field before evaluating rules: format errors on every filled-but-malformed field,
     * and `mandatoryField` on every required-but-empty field. Both make the field "unknown", so a rule
     * referencing it sees Val.Unknown (the 3VL propagates it). Every emitted message is gated by its error
     * field's relevance.
     */
    private fun validateWith(stored: Document, locale: String, relevance: CallRelevance): ValidationOutcome =
        inCall(stored) { doc ->
            val state = CallState(doc, locale, relevance)
            val call = state.call
            val isRelevant = relevance.isRelevant
            // Custom-type unsupported is collected up front (cells whose type is in NEITHER registry); formalErrors
            // ADDS to it any REGISTERED code-based type whose validate() can't run (a JS worker down / errored), so
            // that degrades to unsupported per cell rather than crashing the whole validation.
            val unsupported = checks.customTypeUnsupported(call, isRelevant).toMutableList()
            val messages = (checks.formalErrors(call, locale, isRelevant, state.formalResults, unsupported) +
                    checks.mandatoryErrors(call, locale, isRelevant, state.formalResults, unsupported)
                    + checks.uniquenessMessages(call, state.dup, locale, isRelevant)
                    + checks.overRepetitionMessages(call, locale, ALL_RELEVANT,
                        state.gateNeeded && relevance.structuralChecksEnabled)).toMutableList()
            val pass = state.pass()
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
     * behavior-parity territory. The public [ComputationReport] retains the source-relative action subset beside
     * these report-all results; a comparison against a kernel-granularity expectation projects via
     * [io.github.mbackschat.a12.dm.interpreter.conformance.kernelComputeGranularity].
     */
    fun compute(doc: Document, locale: String = "en_US"): List<Computed> =
        retainedComputations(doc, locale).results

    /** Retain support observations without rerunning custom callbacks for a separate support audit. */
    fun computeWithSupport(stored: Document, locale: String = "en_US"): ComputationEvaluation = try {
        inCall(stored) { doc ->
            val run = engine.computeRunWithSupport(doc, locale)
            ComputationEvaluation(run.results, run.unsupported)
        }
    } catch (failure: UnsupportedConstructException) {
        ComputationEvaluation(emptyList(), listOf(UnsupportedRule(
            "<compute>", failure.message.orEmpty(), UnsupportedSubject.ELEMENT,
        )))
    }

    /** One compute pass retaining the source-relative actions consumed by both application surfaces. */
    internal fun retainedComputations(stored: Document, locale: String = "en_US"): RetainedComputationRun =
        inCall(stored) { doc ->
            val run = engine.computeRun(doc, locale)
            val applications = run.results.mapNotNull { computed ->
                val retain = when (computed.outcome) {
                    ComputeOutcome.VALUE ->
                        !doc.computedValueEquals(
                            computed.path,
                            computed.indices,
                            checkNotNull(computed.value),
                        )
                    ComputeOutcome.CLEARED -> doc.filledAt(computed.path, computed.indices)
                    ComputeOutcome.ERRORED -> true
                }
                if (retain) {
                    ComputationApplication(
                        path = computed.path,
                        reps = computed.indices,
                        outcome = computed.outcome,
                        value = computed.value,
                    )
                } else {
                    null
                }
            }
            RetainedComputationRun(
                results = run.results,
                applications = applications,
                formalErrorsInOperands = run.formalErrorsInOperands,
                computedErrors = run.computedErrors,
                unsupported = run.unsupported,
            )
        }

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
        return inCall(doc) { call -> engine.computeField(fieldPath, call, locale) }
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
        retainedComputations(doc, locale).applyTo(doc)

    private fun evalRule(rule: RuleDef, stored: Document, locale: String): RuleResult = inCall(stored) { doc ->
        // A single-rule pass (fresh formal-status memo, everything relevant) — the testRule/evalCandidate shape.
        val pass = CallState(doc, locale, CallRelevance.FULL).pass()
        val rows = rowOutcomes(rule, pass)
        rows.firstNotNullOfOrNull { it as? RowOutcome.Fired }?.let {
            return@inCall RuleResult(rule.path, Verdict.FIRED, it.message)
        }
        // No firing: a referenced field being formally invalid means the rule was NOT evaluated (the
        // kernel skips it) → SUPPRESSED, not PASSED. PASSED only when every row was cleanly evaluated.
        val suppressed = rows.filterIsInstance<RowOutcome.Suppressed>().map { it.suppression }
        if (suppressed.isNotEmpty()) RuleResult(rule.path, Verdict.SUPPRESSED, suppressedBy = suppressed)
        else RuleResult(rule.path, Verdict.PASSED)
    }

    /**
     * Run one call's [action] on [stored] read under this interpreter's effective charset, observing the caches it
     * filled. A consumer may supply a charset the model does not declare, and every read of a stored value, a
     * computation's star read included, must judge it by the same set the formal checks use (KERNEL-FINDINGS
     * KF555), so every entry point reads the document through here.
     */
    private inline fun <T> inCall(stored: Document, action: (Document) -> T): T {
        val doc = stored.withLegalCharset(effectiveLegalCharset)
        val observer = cacheObserver ?: return action(doc)
        return try {
            action(doc)
        } finally {
            doc.observeCacheEntries(observer)
            parsedModel.observeCacheEntries(observer)
        }
    }

    /**
     * The per-call state a validation builds before its first check, shared by [validateWith] and the single-rule
     * [evalRule]. The index-default staging split (SPEC-2026-07-23-14): a FULL call stages each eligible clean-empty
     * index cell's S-value into a call-local view — before the generated checks and authored rules, never mutating
     * the caller's document (the kernel's transient evaluation cache); a PARTIAL call does NOT stage and instead
     * silently marks those cells unavailable (the `unavailable` seam of the formal-status cache).
     *
     * The [ValidationPass] is built by [pass], not here: its config asks the consumer's clock, which [validateWith]
     * keeps after the generated checks.
     */
    private inner class CallState(private val doc: Document, private val locale: String,
            private val relevance: CallRelevance) {
        private val indexDefaults = checks.eligibleIndexDefaults(doc)
        private val full = relevance.isRelevant === ALL_RELEVANT
        val call = if (full && indexDefaults.isNotEmpty()) doc.withComputed(indexDefaults, indexDefaults.keys) else doc
        // Computed on the STAGED document (the one every gate below consults), once per call (IG127.1).
        val gateNeeded = checks.overRepetitionGateNeeded(call)
        private val valueAdmissions = ValueAdmissionCache(checks, locale, call, gateNeeded)
        // Computed ONCE: feeds both the uniqueIndex messages and the suppression.
        val dup = checks.duplicateIndexCells(call, valueAdmissions, relevance.isRelevant)
        val formalResults: FormalResultCache = FormalResultCache(
            checks,
            locale,
            dup,
            if (full) emptySet() else indexDefaults.keys,
            { ctx, path -> admittedGroupContent.isFilled(ctx, path) },
            valueAdmissions,
        )
        private val admittedGroupContent by lazy(LazyThreadSafetyMode.NONE) {
            AdmittedGroupContentIndex.build(model, call, relevance.isRelevant, relevance.groupRelevance, formalResults,
                paths)
        }
        // The per-call seam (IF117): the formal-status memo spans ALL rules of this validation (the doc is
        // immutable here), and the EvalConfig is built once for the whole call.
        private val formallyIncorrect by lazy(LazyThreadSafetyMode.NONE) { formalResults.invalidPointers() }
        private val groupPresence by lazy(LazyThreadSafetyMode.NONE) {
            GroupPresenceIndex(admittedGroupContent, formallyIncorrect, relevance.groupRelevance)
        }

        fun pass(): ValidationPass {
            val presence: (String, List<Int>) -> GroupPresenceState = { path, reps -> groupPresence.state(path, reps) }
            val indexIdentityAdmitted: (String, List<Int>, String) -> Boolean = { path, reps, raw ->
                relevance.isRelevant(path, reps) && formalResults.admitsIdentityAt(path, reps, raw)
            }
            return ValidationPass(call, doc, locale, relevance.isRelevant, dup,
                validationConfig(relevance, indexIdentityAdmitted, relevance.groupExtents?.gate(presence), presence),
                formalResults, { formallyIncorrect }, relevance.dispatch,
                { checks.messageIndexDefaults(call, formalResults::admitsIdentityAt) }, groupPaths = model.groupPaths)
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
    private fun validationConfig(relevance: CallRelevance,
            indexIdentityAdmitted: (String, List<Int>, String) -> Boolean,
            groupCountGate: GroupCountGate?,
            groupPresence: (String, List<Int>) -> GroupPresenceState): EvalConfig =
        EvalConfig(clock(), model.baseYear, model.indexFields, rawStringFields = model.rawStringFields, timeZone = model.timeZone,
            predefinedTypes = predefinedTypes, customConditions = customConditions, customFieldTypes = customFieldTypes,
            patternMatches = patterns::matches,
            indexIdentityAdmitted = indexIdentityAdmitted,
            cellRelevant = relevance.isRelevant.takeUnless { it === ALL_RELEVANT },
            starRelevant = relevance.starRelevant, starExtentKnown = relevance.starExtentKnown,
            starFullyEnumerated = relevance.starFullyEnumerated,
            customRelevant = relevance.customRelevant, groupPresence = groupPresence, groupCountGate = groupCountGate,
            // Outside a partial call an empty cell fails its formal check only when the field is required.
            emptyMayBeUnavailable = if (relevance.isRelevant === ALL_RELEVANT) {
                { path -> model.requirednessOf(path) != Requiredness.NOT_REQUIRED }
            } else {
                { true }
            })
            .also { config ->
                config.work = workLedger
                config.absentGroupsCovered = relevance.groupExtents?.let { extents ->
                    { path, depth, reps, present -> extents.coversAbsent(path, depth, reps, present, groupPresence) }
                }
            }

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
        // AST contains a Having filter, and a rule reporting on a group never reports in a partial call. Keep the
        // partial path O(1); full validation continues normally.
        if (pass.partial && prepared.skippedInPartial) return emptyList()
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
        val errorPath = prepared.errorPath
        val parallel = prepared.iteration as? IterationPlan.Parallel
        if (parallel != null && pass.dispatch?.admits(errorPath, parallel) == false) return emptyList()
        // A rule whose condition contains a RepetitionNotUnique leaf gets its duplicate cache built ONCE (IF149),
        // guard-independent, keyed by error-path reps; the per-row seam looks the current row up in O(1). Null for
        // a normal rule (no cache, no seam). Building here can throw UnsupportedConstructException (>1 RNU, a
        // group/asterisk key, a too-deep scope) — the caller's per-rule tolerance reports it unsupported.
        val rnuCache = rnuEval.outcomes(prepared.repetitionNotUnique, pass)
        val directMessageRefs = prepared.messageReferences
        val semanticErrorIndex = prepared.semanticErrorIndex
        val selectedErrorReference = if (semanticErrorIndex == null) null
            else directMessageRefs.filterIsInstance<MessageReference.Selected>().first { it.path == errorPath }
        fun errorPointer(ctx: EvalContext): A12PartiallyKnownMultiPointer {
            val repetitions = pass.doc.messageRepetitions(errorPath, ctx)
            val reference = selectedErrorReference ?: return pass.doc.messagePointer(errorPath, repetitions)
            val level = paths.groupsThrough(errorPath).indexOf(parentGroup(checkNotNull(reference.indexField)))
            val selected = pointers.selectedRepetitions(reference, pass, ctx)?.get(level)
                ?: A12PartiallyKnownMultiPointer.UNKNOWN
            // KF545: selecting an error address must not make a duplicate or invalid value readable.
            return pass.doc.messagePointer(errorPath, repetitions.mapIndexed { index, repetition ->
                if (index == level) selected else repetition
            })
        }
        val directRefsOutsideRnuKeys = rnuCache?.let { cache ->
            val rnuKeys = cache.keys.toSet()
            directMessageRefs.filterNot { it.path in rnuKeys }
        }
        val rnuSeam: ((EvalContext) -> MsgType?)? =
            rnuCache?.let { cache -> { context -> cache.rows[context.repsOf(errorPath)]?.type } }
        val iterationContexts = scopes.iterationContexts(
            prepared.iteration,
            pass.doc,
            pass.config.cellRelevant,
            pass::admitsIndexIdentity,
            validation = true,
        ).let { contexts ->
            if (semanticErrorIndex == null) contexts
            else contexts.map { iterationContext ->
                val semanticIndex = semanticErrorIndex
                val selector = pass.interpreterFor(iterationContext, errorPath, rnuSeam)
                val selectedGroup = nearestIndexFieldOf(model.indexFields, semanticIndex.path)
                    ?.let(::parentGroup)
                    ?: if (semanticIndex.path in model.groupPaths) semanticIndex.path else parentGroup(semanticIndex.path)
                val selectedFieldReps = selector.semanticIndexRepetitions(semanticIndex)
                val errorGroup = parentGroup(errorPath)
                val selectedDepth = pathParts(selectedGroup).size
                val errorDepth = pathParts(errorGroup).size
                val errorGroupReps = selectedFieldReps?.let { repetitions ->
                    if (semanticIndex.path in model.groupPaths) {
                        val targetDepth = pathParts(semanticIndex.path).size
                        repetitions.take(targetDepth) + List(errorDepth - targetDepth) { 1 }
                    } else {
                        repetitions.take(errorDepth)
                    }
                } ?: run {
                    iterationContext.repsOf(errorPath).dropLast(1).toMutableList().also { repetitions ->
                        repetitions[selectedDepth - 1] = UNKNOWN_REP
                    }
                }
                pass.doc.contextFor(errorGroup, errorGroupReps)
            }.distinctBy { context -> context.repsOf(errorPath) }
        }
        // The iteration half of the work tally, counted after the early returns above so an eliminated or
        // partial-skipped rule contributes the zero rows it actually evaluates (WorkLedger).
        pass.config.work?.rowsEvaluated(iterationContexts.size)
        fun rowOutcome(ctx: EvalContext): RowOutcome {
            // Relevance controls whether a rule executes, not just message emission; both routes dispatch through
            // the error field's merged selector (KF621).
            val runs = if (parallel != null && pass.dispatch != null) pass.dispatch.includes(errorPath, parallel, ctx)
            else pass.runsForRow(errorPath, ctx)
            if (!runs) return RowOutcome.Passed
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
            // INTERPRETER-PERFORMANCE.md task #62; measurements in INTERPRETER-PERFORMANCE-RESULTS.md.)
            //
            // A leaf that can fail at runtime, or a consumer callback, makes the reached leaves observable, so such a
            // condition takes the kernel's two passes instead; elsewhere the single walk gives the same verdict and
            // type at lower cost.
            val firedType = if (prepared.reachObservable) interp.firedTypeInTwoPasses(ast, prepared.classifies)
                else interp.firedType(ast)
            if (firedType != null) {
                val text = rule.messages[pass.locale]?.let {
                    renderMessage(
                        it,
                        rule.group,
                        messageTerminals,
                        defaultValueOf = model::messageDefaultOf,
                        labelOf = { path -> messageLabel(path, pass.locale, ctx) },
                        // `$field.value$` reads the ORIGINAL caller document, not the staged eval view: a staged
                        // enum index cell interpolates as if empty (kernel `getDaten().getValueForDisplay`; IF221).
                        messageValueOf = { path ->
                            presentedDisplayValue(
                                pass.callerDoc.messageDisplayValueAt(path, ctx.repsOf(path)), path, pass.locale)
                        },
                        // An ENUMERATION's `.value` renders its declared LABEL, and `->Category` the mapped
                        // token; the Base Year terminal is a non-field parameter with a signed offset ([KF198]).
                        enumValueOf = { path, stored -> model.enumValueTextOf(path, stored, pass.locale) },
                        enumCategoryOf = model::enumCategoryTokenOf,
                        baseYearOf = { offset -> model.baseYear?.let { year -> (year + offset).toString() } },
                        // `$#group$` renders the FIRING ROW's own 1-based index at that group's level, and 1 for
                        // a non-repeatable group — the same coordinate `CurrentRepetition` reads, which is why
                        // both go through `repsOf(path).last()` rather than through two derivations (KF245).
                        groupIndexOf = { argument -> groupParameterIndex(argument, rule.group, pass, ctx) },
                        indexTextOf = { name, category -> indexParameterText(name, category, errorPath, pass, ctx) },
                        selectedCellOf = { written -> selectedMessageCell(written, rule.group, pass, ctx) },
                        fieldPathOf = { name -> messageParameterPath(rule.group, name) },
                    )
                } ?: ""
                val matchingRnuOutcome = rnuCache?.rows?.get(ctx.repsOf(errorPath))?.takeIf { it.type == firedType }
                // When the RNU leaf contributes this message's polarity, its cluster projection already contains
                // every occurrence of an RNU key path. Drop a same-path ordinary occurrence, render the remaining
                // small prefix, and expose an O(1) immutable joined view over the shared cluster list. This keeps
                // exact list semantics without copying the N-entry peer projection into every one of N messages.
                val directRefs = if (matchingRnuOutcome != null) directRefsOutsideRnuKeys.orEmpty() else directMessageRefs
                val directPointers = directRefs.map { pointers.of(it, pass, ctx) }.distinct()
                val rnuPointers = matchingRnuOutcome?.peerKeyPointers.orEmpty()
                val referenced = joinedList(directPointers, rnuPointers)
                // refOmissionErrorResponsible: the referenced operands that can cause an omission, filled or not,
                // fixed per rule ([omissionReferences]); the RNU cluster stays in. Empty for a VALUE error.
                val fillToFix = if (firedType == MsgType.OMISSION) {
                    if (directRefs.all { it in prepared.omissionReferences }) referenced
                    else joinedList(directRefs.filter { it in prepared.omissionReferences }
                        .map { pointers.of(it, pass, ctx) }.distinct(), rnuPointers)
                } else emptyList()
                return RowOutcome.Fired(Message(rule.errorCode, rule.path,
                    errorPointer(ctx), rule.severity, firedType, text, referenced,
                    fillToFix))
            }
            // Not fired: a formally-invalid operand means the rule was NOT cleanly evaluated → SUPPRESSED
            // (the `rule eval` third verdict), naming the offending field/code; else a clean PASSED.
            return pass.firstSuppressor(refs, ctx)?.let { RowOutcome.Suppressed(it) } ?: RowOutcome.Passed
        }
        // A runtime failure ends the rule's row loop, which the kernel's generated rule catches OUTSIDE its
        // iteration: rows already fired keep their messages, and the failure adds one VALUE message under the rule's
        // own code at the iterator's FIRST address — a fresh iterator's first state, whichever row failed — with no
        // referenced fields (SPEC-2026-10-03-06). Other rules still visit their rows.
        val outcomes = ArrayList<RowOutcome>(iterationContexts.size)
        for (ctx in iterationContexts) {
            try {
                outcomes += rowOutcome(ctx)
            } catch (failure: RuntimeEvaluationFailure) {
                val first = iterationContexts.first()
                outcomes += RowOutcome.Fired(Message(rule.errorCode, rule.path,
                    errorPointer(first), rule.severity, MsgType.VALUE,
                    "An error occurred while evaluating the data check: ${failure.message}"))
                break
            }
        }
        return outcomes
    }

    /**
     * A message field name's path, in A12's two tiers: below the rule's group, and otherwise, for a bare name, the
     * model's one field of that short name. The second tier holds whatever `fieldRefByShortNameAllowed` says, and
     * the kernel's admission has already refused a name two fields share (KERNEL-FINDINGS KF335, KF561).
     */
    private fun messageParameterPath(ruleGroup: String, name: String): String {
        val local = messageFieldPath(ruleGroup, name)
        if (local in model.kinds || '/' in name) return local
        return fieldsByShortName[name]?.singleOrNull() ?: local
    }

    private val fieldsByShortName: Map<String, List<String>> by lazy {
        model.kinds.keys.groupBy { it.substringAfterLast('/') }
    }

    /** A `Target For key` message argument as the selected reference its condition twin reads. */
    private fun selectedMessageReference(written: String, ruleGroup: String): MessageReference.Selected? {
        val selected = parseExpression(written, ruleGroup, model.conditionLanguage)
            as? Expr.SemanticIndex ?: return null
        val indexField = nearestIndexFieldOf(model.indexFields, selected.path) ?: return null
        val key = selected.indexValue?.let(SelectedKey::Literal)
            ?: selected.indexFieldPath?.let(SelectedKey::Field) ?: return null
        return MessageReference.Selected(selected.path, key, indexField)
    }

    /**
     * The cell a `$Field.value For key$` token reads: the row the key selects, through the same lookup as the
     * `$#Group For key$` token, displayed like any message value (KERNEL-FINDINGS KF561).
     */
    private fun selectedMessageCell(written: String, ruleGroup: String, pass: ValidationPass, ctx: EvalContext): SelectedCell? {
        val reference = selectedMessageReference(written, ruleGroup) ?: return null
        val repetitions = pointers.selectedRepetitions(reference, pass, ctx)
            ?: return SelectedCell(reference.path, null, matched = false)
        val stored = pass.callerDoc.messageDisplayValueAt(reference.path, repetitions)
        return SelectedCell(reference.path, presentedDisplayValue(stored, reference.path, pass.locale), matched = true)
    }

    /**
     * The `$#…$` message parameter's value: the firing row's own 1-based index at the named group's level, and
     * `1` for a non-repeatable group ([KF245]).
     *
     * The argument is absolute, relative to the declaring group, or one of its condition-language shorthands.
     * KF245's relative child and parent-step controls render the same coordinates as their absolute twins;
     * KF214's old ancestor-only admission claim did not cover their reference footprint.
     * A `null` return preserves the token when the address names no declared group.
     */
    private fun groupParameterIndex(written: String, ruleGroup: String, pass: ValidationPass, ctx: EvalContext): String? {
        if (written.any(A12Lexis::isSpace)) {
            val selected = selectedMessageReference(written, ruleGroup) ?: return null
            return pointers.selectedRepetitions(selected, pass, ctx)?.lastOrNull()?.toString() ?: "?"
        }
        val argument = written.replace("'", "")
        val path = when (argument) {
            messageTerminals.ruleGroup -> ruleGroup
            messageTerminals.rootGroup -> rootGroupOf(ruleGroup)
            else -> if (argument.startsWith('/')) argument
                else relativeMessageGroup(argument, ruleGroup) ?: return null
        }
        // The declared-group test is what makes the verbatim fallback REAL: `repsOf` answers for any string,
        // padding an unknown path with 1s, so without it the other language's spelling would render `1`
        // rather than survive as its own token — a wrong VALUE where the kernel gives a refusal.
        if (path !in model.groupPaths) return null
        return ctx.repsOf(path).lastOrNull()?.toString()
    }

    private fun indexParameterText(
        name: String, category: String?, errorPath: String, pass: ValidationPass, ctx: EvalContext,
    ): String? {
        val field = model.indexFields.singleOrNull { it.substringAfterLast('/') == name &&
            errorPath.startsWith(it.substringBeforeLast('/') + "/") } ?: return null
        val value = pass.doc.messageIndexValue(field, ctx) ?: return ""
        val repetitions = ctx.repsOf(field)
        if (repetitions.all { it > 0 } && !pass.admitsIndexIdentity(field, repetitions, value)) return ""
        return if (category == null) value else model.enumCategoryTokenOf(field, value, category) ?: ""
    }

    private fun relativeMessageGroup(argument: String, ruleGroup: String): String? {
        val parts = pathParts(ruleGroup).toMutableList()
        for (segment in argument.split('/')) {
            when (segment) {
                "" -> return null
                ".." -> {
                    if (parts.size <= 1) return null
                    parts.removeAt(parts.lastIndex)
                }
                else -> parts.add(segment)
            }
        }
        return "/" + parts.joinToString("/")
    }

    private fun messageLabel(path: String, locale: String, ctx: EvalContext): String {
        return messageLabel(path, locale, ctx.repsOf(path))
    }

    private fun messageLabel(path: String, locale: String, repetitions: List<Int>): String {
        val supplied = model.fieldOf(path)?.let { field -> messageLabelProvider?.label(field, locale) }
        if (supplied != null) return supplied
        model.declaredLabelOf(path, locale)?.takeIf { it.isNotEmpty() }?.let { return it }
        return if (repetitions.isEmpty()) path else "$path/${repetitions.joinToString(".")}"
    }

    /** Immutable concatenation without copying either input; both inputs are call-local immutable projections. */
    private fun <T> joinedList(prefix: List<T>, suffix: List<T>): List<T> = when {
        prefix.isEmpty() -> suffix
        suffix.isEmpty() -> prefix
        else -> JoinedList(prefix, suffix)
    }

    /** Immutable by construction over two call-local immutable inputs, so it publishes without a copy: an
     *  RNU message that also carries direct references lands here rather than on the bare cluster snapshot, and
     *  a boundary that recognized only the snapshot copied this one per message (IF261). */
    private class JoinedList<T>(private val prefix: List<T>, private val suffix: List<T>)
        : AbstractList<T>(), ImmutableProjection {
        override val size: Int = prefix.size + suffix.size

        override fun get(index: Int): T = when {
            index < 0 || index >= size -> throw IndexOutOfBoundsException("index $index, size $size")
            index < prefix.size -> prefix[index]
            else -> suffix[index - prefix.size]
        }
    }
}
