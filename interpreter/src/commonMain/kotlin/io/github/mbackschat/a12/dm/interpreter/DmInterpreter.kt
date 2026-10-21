package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.parse.A12Lexis
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
import io.github.mbackschat.a12.dm.interpreter.eval.EffectiveRelevanceIndex
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

/** A `Long` holds a wildcard mask for a path this deep; beyond it `reducedByPatternScan` takes over. */
private const val MASKABLE_DEPTH = 63

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
     * relevantEntities, config)`. Only the rules whose **error field** is relevant **at the repetition levels the rule's ITERATION bound** run — a level the iteration never bound (rule iteration is reference-driven, so the declared group does not decide this) counts as a wildcard, and the message reports at the default first repetition (IF268) — measured against
     * [relevant]; a rule whose error field is in a repeatable group the rule does not iterate therefore
     * runs when ANY of that group's rows is relevant. Parallel rules instead compress error selectors for
     * dispatch: a concrete joined coordinate suppresses a rule with ordinary outer/frame iteration, while
     * differing selectors can restore dispatch without widening operand relevance (SPEC-2026-10-10-10). Those rules are
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
    fun validatePart(doc: Document, relevant: Set<RelevantEntity>, locale: String = "en_US"): ValidationOutcome {
        val queries = relevanceQueriesOf(relevant)
        return validateWith(doc, locale, CallRelevance(
            isRelevant = queries::isRelevant,
            structuralChecksEnabled = relevant.isNotEmpty(),
            customRelevant = customRelevantOf(relevant),
            starRelevant = starRelevantOf(relevant),
            starExtentKnown = starExtentKnownOf(relevant),
            starFullyEnumerated = starFullyEnumeratedOf(relevant),
            groupRelevance = groupRelevanceOf(relevant),
            relevantWithin = queries::isRelevantWithin,
            groupExtents = PartialGroupExtents(relevant),
            parallelSelection = ParallelRuleSelection(model, relevant, paths),
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
        /** [EffectiveRelevanceIndex.isRelevantWithin] — the rule-run gate's bound-level query ([IF268]). */
        val relevantWithin: ((String, List<Int>, Int) -> Boolean)? = null,
        /** Missing-position coverage for group predicates and the stricter count gate ([IF193], [IF339]). */
        val groupExtents: PartialGroupExtents? = null,
        val parallelSelection: ParallelRuleSelection? = null,
    ) {
        companion object {
            val FULL = CallRelevance(ALL_RELEVANT)
        }
    }

    /**
     * Whether a STARRED operation has complete extent in PARTIAL mode (IF42, IF262). The kernel evaluates an
     * all-rows aggregate (`Sum`/`Max`/`Min`/`NumberOfDifferentValues`, but NOT `SumOfProducts`) and the direct
     * filled-field/group counts only when its reduced relevant identifiers wildcard every repeatable level
     * traversed by the star. Relevant ancestor groups first
     * expand to the operand path, and a broader wildcard identifier removes every narrower identifier it encompasses.
     * After that reduction, ANY remaining concrete coordinate at a starred level makes the extent unknown.
     * `FirstFilled` does not use this path because it is order-aware. Starred value-list quantifiers and
     * `NumberOfValueInFields` use the declared-coverage [starExtentKnownOf] instead.
     */
    private fun starRelevantOf(relevant: Set<RelevantEntity>): (String, Int, List<Int>) -> Boolean {
        val candidates = wellFormed(relevant)
        val reducedByPath = HashMap<String, RowAddressIndex>()
        val decided = HashMap<Triple<String, Int, List<Int>>, Boolean>()
        return { starPath, bindDepth, rowReps ->
            val scope = starScope(starPath, bindDepth, rowReps)
            decided.getOrPut(Triple(starPath, bindDepth, scope.bound)) {
                wildcardsEveryStarredLevel(
                    reducedByPath.getOrPut(starPath) { RowAddressIndex(reducedStarRelevantCandidates(candidates, starPath)) },
                    scope,
                )
            }
        }
    }

    /**
     * Whether the reduced identifiers that address [scope]'s row establish the extent: at least one does, and
     * none is concrete at a level the star iterates.
     */
    private fun wildcardsEveryStarredLevel(reduced: RowAddressIndex, scope: StarScope): Boolean {
        val scoped = reduced.addressing(scope.bound)
        return scoped.isNotEmpty() && scope.levels.none { level -> scoped.any { it.indices[level] != RelevantEntity.ALL } }
    }

    /**
     * Partial group predicates and counts share the missing-position coverage check (SPEC-2026-10-10-20/21).
     * The stricter [GroupCountGate] for `NumberOfFilledGroups` is decided on the counted group
     * itself rather than on its fields (KF464, IF339). An operand is countable when three things hold:
     *
     * 1. **The group's own identifiers establish the extent.** They are the relevant entities at or above the
     *    group and every relevant descendant cut back to the group's depth, reduced exactly as [starRelevantOf]
     *    reduces a field's, and none may stay concrete at a starred level.
     * 2. **The first declared instance is fully relevant and not erroneous** — the one instance A12 inspects
     *    before it counts anything, held or not, so a partly covered or erroneous first row leaves the count
     *    undecidable even where every other row is decided.
     * 3. **Every declared instance the document lacks is fully relevant.** A12 walks the declared rows, not the
     *    held ones, and an absent row it cannot call empty has no count.
     *
     * Rule 3 is decided on one representative per equivalence class rather than on every declared coordinate:
     * group relevance at a level depends only on whether an identifier names that index, so the indices some
     * identifier or held instance names are checked each, and all the rest by the smallest of them, which is
     * absent by construction. That keeps the check proportional to the named indices when a capacity is large.
     * A group without a declared capacity, which only a hand-built model can express since `ModelLoader` gives
     * every A12 repeatable group one, is read as having such a row beyond every named index.
     */
    private inner class PartialGroupExtents(relevant: Set<RelevantEntity>) {
        private val candidates = wellFormed(relevant)
        private val reducedByPath = HashMap<String, RowAddressIndex>()
        private val namedByLevel = HashMap<String, Set<Int>>()
        private val absentCoverage = HashMap<Triple<String, Int, List<Int>>, Boolean>()

        fun gate(presence: (String, List<Int>) -> GroupPresenceState): GroupCountGate {
            val decided = HashMap<Triple<String, Int, List<Int>>, Boolean>()
            return GroupCountGate { groupPath, bindDepth, rowReps, present ->
                val scope = starScope(groupPath, bindDepth, rowReps)
                decided.getOrPut(Triple(groupPath, bindDepth, scope.bound)) {
                    wildcardsEveryStarredLevel(reducedByPath.getOrPut(groupPath) { RowAddressIndex(ownIdentifiers(groupPath)) }, scope) &&
                        presence(groupPath, firstInstance(groupPath, scope))
                            .let { it.relevance == GroupRelevance.FULL && !it.erroneous } &&
                        coversAbsent(groupPath, bindDepth, rowReps, present, presence)
                }
            }
        }

        /** Missing positions have the same coverage requirement for predicates and counts; predicates do not
         * inherit the count's own-identifier wildcard or first-instance gates (SPEC-2026-10-10-20/21). */
        fun coversAbsent(groupPath: String, bindDepth: Int, rowReps: List<Int>, present: Set<List<Int>>,
                presence: (String, List<Int>) -> GroupPresenceState): Boolean {
            val scope = starScope(groupPath, bindDepth, rowReps)
            return absentCoverage.getOrPut(Triple(groupPath, bindDepth, scope.bound)) {
                absentRepresentatives(groupPath, scope, present)
                    .all { presence(groupPath, it).relevance == GroupRelevance.FULL }
            }
        }

        private fun ownIdentifiers(groupPath: String): List<RelevantEntity> {
            val groupParts = pathParts(groupPath)
            return reducedStarRelevantCandidates(
                candidates.mapNotNull { e ->
                    when {
                        covers(e, groupPath) -> e
                        pathParts(e.path).take(groupParts.size) == groupParts ->
                            RelevantEntity(groupPath, e.indices.take(groupParts.size))
                        else -> null
                    }
                },
                groupPath,
            )
        }

        private fun firstInstance(groupPath: String, scope: StarScope): List<Int> =
            scope.bound + List(pathParts(groupPath).size - scope.bound.size) { 1 }

        private fun absentRepresentatives(groupPath: String, scope: StarScope, present: Set<List<Int>>): List<List<Int>> {
            val parts = pathParts(groupPath)
            var tuples = listOf(scope.bound)
            for (level in scope.bound.size until parts.size) {
                val prefix = "/" + parts.take(level + 1).joinToString("/")
                val axis = if (prefix !in model.repeatable) listOf(1) else {
                    val named = HashSet(namedIndices(prefix, level))
                    present.mapTo(named) { it[level] }
                    val capacity = model.declaredReps[prefix]
                    (named + generateSequence(1) { it + 1 }.first { it !in named })
                        .filter { capacity == null || it <= capacity }
                }
                tuples = tuples.flatMap { tuple -> axis.map { tuple + it } }
            }
            return tuples.filter { it !in present }
        }

        /** The concrete indices the identifiers on [prefix]'s branch name at its [level], whatever row asks. */
        private fun namedIndices(prefix: String, level: Int): Set<Int> = namedByLevel.getOrPut(prefix) {
            val prefixParts = pathParts(prefix)
            candidates.mapNotNullTo(HashSet()) { e ->
                e.indices.getOrNull(level)?.takeIf { index ->
                    index != RelevantEntity.ALL && pathParts(e.path).take(level + 1) == prefixParts
                }
            }
        }
    }

    /**
     * The complete-extent gate for value lists, value counts and descendant group coverage uses the same star anchor and
     * per-iteration-row scope as [starRelevantOf]. A wildcard covering every reopened level or a union covering
     * every declared slot establishes extent; concrete siblings do not suppress either (SPEC-2026-10-10-15,
     * `PartialDeclaredListExtentDiffTest`). Descendant group coverage shares this union (SPEC-2026-10-10-22,
     * `PartialParentGroupCoverageDiffTest`). Wildcards still establish extent for unbounded declarations.
     *
     * The two gates were briefly one predicate, on the reasoning that they answer the same structural question.
     * They do not. On `{field wildcard} + {concrete group rows}` the kernel FIRES a starred `No` and is SILENT
     * on `Sum` over the same rows, on both codegen strategies — so folding the value-list site into [IF262]'s
     * reduction made the interpreter suppress where the kernel fires. Seven relevant-set shapes measured side by
     * side in `PartialExtentGateShapeDiffTest`; the split is IF264.
     */
    private fun starExtentKnownOf(relevant: Set<RelevantEntity>): (String, Int, List<Int>) -> Boolean {
        val covering = coveringIndexes(relevant)
        val decided = HashMap<Triple<String, Int, List<Int>>, Boolean>()
        return { starPath, bindDepth, rowReps ->
            val scope = starScope(starPath, bindDepth, rowReps)
            decided.getOrPut(Triple(starPath, bindDepth, scope.bound)) {
                val addressing = covering(starPath).addressing(scope.bound)
                addressing.any { e ->
                    scope.levels.all { i -> i >= e.indices.size || e.indices[i] == RelevantEntity.ALL }
                } || coversEveryRepetition(addressing, starPath, scope)
            }
        }
    }

    /**
     * The kernel expands each relevant ancestor group into an identifier for every contained field, then removes
     * identifiers encompassed by a broader wildcard identifier of the same field name. Its `EntityIterator`
     * rejects an aggregate extent when ANY identifier left for that operand is concrete at a starred level.
     * Therefore "any wildcard wins" is wrong: `[1,*,1]` beside an expanded `[0,1,*]` stays mixed and is UNKNOWN,
     * while `[*,*,*]` encompasses and removes the concrete sibling before the iterator asks.
     *
     * Reduction happens before the per-row subtree filter, matching `ValidationCache.reduceRedundancies` followed
     * by `EntityIterator.certainlyNotInSameSubtree`. This preserves IF257's row-local answer when one outer row is
     * wildcarded and another is concrete. SumOfProducts does not use this combiner iterator and keeps the separate
     * [starFullyEnumeratedOf] path. See IF262 for the source trace and discriminating cases.
     *
     * **Cost.** A candidate is removed exactly when the set contains a tuple whose wildcard mask is a STRICT
     * SUPERSET of the candidate's and whose remaining coordinates match it — so only masks with a strictly
     * larger wildcard count can remove anything, which is what [wildcardMasksByBreadth] orders by. Testing a
     * mask first and materializing the broader tuple only on a hit is what keeps this off the quadratic path:
     * scanning every distinct mask and hashing a fresh list for each took **1306 ms** on 3000 relevant
     * identifiers whose masks form an antichain (equal wildcard counts, so nothing removes anything and every
     * probe was wasted), growing 3.86x when the set doubled — measured, not predicted, by
     * `StarRelevanceReductionGrowthTest`. The residual worst case is a set mixing many wildcard counts, bounded
     * by cheap `Long` comparisons rather than allocation.
     */
    private fun reducedStarRelevantCandidates(
        relevant: List<RelevantEntity>,
        starPath: String,
    ): List<RelevantEntity> {
        val depth = pathParts(starPath).size
        val expanded = relevant.asSequence()
            .filter { covers(it, starPath) }
            .map { entity ->
                RelevantEntity(starPath, entity.indices + List(depth - entity.indices.size) { RelevantEntity.ALL })
            }
            .distinctBy { it.indices }
            .toList()
        if (depth > MASKABLE_DEPTH) return reducedByPatternScan(expanded)
        val tuples = expanded.mapTo(HashSet()) { it.indices }
        val byBreadth = wildcardMasksByBreadth(expanded)
        return expanded.filter { candidate ->
            val own = wildcardMask(candidate.indices)
            val ownBreadth = countOneBits(own)
            byBreadth.none { (breadth, masks) ->
                breadth > ownBreadth && masks.any { mask ->
                    mask and own == own && broaderTuple(candidate.indices, mask) in tuples
                }
            }
        }
    }

    /**
     * The same reduction for an operand path deeper than a `Long` has bits for — the direct pairwise scan,
     * kept because it needs no mask and is provably equivalent. It is quadratic, but a path this deep does not
     * occur: reaching it needs more than [MASKABLE_DEPTH] segments in one field path.
     */
    private fun reducedByPatternScan(expanded: List<RelevantEntity>): List<RelevantEntity> {
        val tuples = expanded.mapTo(HashSet()) { it.indices }
        val patterns = expanded.asSequence()
            .map { entity -> entity.indices.map { it == RelevantEntity.ALL } }
            .filter { pattern -> pattern.any { it } }
            .distinct()
            .toList()
        return expanded.filter { candidate ->
            patterns.none { pattern ->
                val broader = candidate.indices.mapIndexed { level, index ->
                    if (pattern[level]) RelevantEntity.ALL else index
                }
                broader != candidate.indices && broader in tuples
            }
        }
    }

    /**
     * The distinct wildcard masks present, grouped by how many levels each wildcards and ordered by that count.
     * Only a mask that wildcards strictly MORE levels than a candidate can encompass it, so a candidate whose
     * own breadth is maximal — the antichain case — skips every group without touching a single mask.
     */
    private fun wildcardMasksByBreadth(expanded: List<RelevantEntity>): List<Pair<Int, List<Long>>> =
        expanded.asSequence()
            .map { wildcardMask(it.indices) }
            .filter { it != 0L }
            .distinct()
            .groupBy { countOneBits(it) }
            .toList()
            .sortedBy { it.first }

    private fun wildcardMask(indices: List<Int>): Long {
        var mask = 0L
        indices.forEachIndexed { level, index -> if (index == RelevantEntity.ALL) mask = mask or (1L shl level) }
        return mask
    }

    private fun broaderTuple(indices: List<Int>, mask: Long): List<Int> =
        indices.mapIndexed { level, index ->
            if (mask shr level and 1L == 1L) RelevantEntity.ALL else index
        }

    private fun countOneBits(mask: Long): Int {
        var rest = mask
        var count = 0
        while (rest != 0L) {
            rest = rest and (rest - 1)
            count++
        }
        return count
    }

    /** The relevant entities carrying one index per path segment — the shape both gates can reason about. */
    private fun wellFormed(relevant: Set<RelevantEntity>): List<RelevantEntity> =
        relevant.filter { it.indices.size == pathParts(it.path).size }

    /** Whether [e] speaks about [starPath] at all: its path is an ancestor-or-equal prefix. */
    private fun covers(e: RelevantEntity, starPath: String): Boolean {
        val sParts = pathParts(starPath)
        val eParts = pathParts(e.path)
        return eParts.size <= sParts.size && eParts == sParts.take(eParts.size)
    }

    /** The repetition coordinates the current row BINDS for a starred operand, and the levels the star iterates. */
    private class StarScope(val bound: List<Int>, val levels: List<Int>)

    /**
     * Split [starPath]'s repeatable levels into the ones the current row fixes and the ones the star iterates.
     *
     * The split is [io.github.mbackschat.a12.dm.interpreter.ast.Expr.Star.bindDepth] — the segments strictly
     * above the FIRST `*`-marked group (IF94) — and **not** the path's deepest repeatable level. For a
     * single-star operand the two coincide, which is why deriving it from the path alone survived every
     * single-star probe. For `Outer* then Inner* then Qty` they differ by exactly one level, and that level is
     * one the row would claim to bind while the operand in fact spans every one of its rows: pinning `Outer` to
     * row 1 then read as "everything unbound is wildcarded", and the interpreter fired where the kernel is
     * silent (IF259). A hand-built node (−1) keeps the leaf-parent default, where the two coincide by definition.
     */
    private fun starScope(starPath: String, bindDepth: Int, rowReps: List<Int>): StarScope {
        val parts = pathParts(starPath)
        fun repeatableAt(i: Int) = "/" + parts.take(i + 1).joinToString("/") in model.repeatable
        val firstStar = if (bindDepth >= 0) minOf(bindDepth, parts.size)
            else parts.indices.lastOrNull { repeatableAt(it) } ?: 0
        val bound = rowReps.take(minOf(firstStar, rowReps.size))
        return StarScope(bound, parts.indices.filter { it >= bound.size && repeatableAt(it) })
    }

    /**
     * `SumOfProducts`' own, WEAKER completeness notion. It does not share [starRelevantOf]'s wildcard-only gate
     * (it runs a hand-written two-list loop with no three-value pre-survey), but it is not ungated either: the
     * kernel evaluates it under a CONCRETE enumeration only when that enumeration is COMPLETE. Measured against
     * the dynamic kernel on `/Cart/Lines[2]` (`PartialValidationAggregateDiffTest`):
     *
     * | relevant set | kernel |
     * |---|---|
     * | wildcard | fires |
     * | both rows enumerated | fires |
     * | row 1 only / row 2 only | **silent** |
     *
     * Exempting it from the gate ENTIRELY — reading "concrete enumeration evaluates" off the both-rows case
     * alone — made the interpreter fire where the kernel is silent, on 5 of the fuzz net's 180 (document,
     * relevant-subset) combinations. Completeness is measured against the DECLARED repetitions, which is what
     * the probe varied; a document with fewer rows than declared is not separately measured.
     *
     * **Completeness is a property of the TUPLES, not of each level independently** — the distinction only
     * appears once two repeatable levels are nested, which is why the one-level probe above could not see it.
     * Enumerating `Outer[1]/Inner[1]` and `Outer[2]/Inner[2]` covers `{1,2}` at both levels while covering only
     * the diagonal, half of the four rows; reading per-level unions called that complete and fired where the
     * kernel is silent. So a candidate must cover the full CROSS-PRODUCT of the declared repetitions of the
     * levels the star iterates.
     *
     * The cover is taken over the **union of every addressing entity**, not per wildcard pattern. Deciding it
     * per pattern suppressed a set whose union is complete but whose individual patterns are not — `[*,*,1]`
     * together with `[*,1,2]` and `[*,2,2]` covers all four pairs, and the kernel fires (IF260).
     */
    private fun starFullyEnumeratedOf(relevant: Set<RelevantEntity>): (String, Int, List<Int>) -> Boolean {
        // Filtered ONCE per call. Deciding completeness by rescanning the relevant set for every declared
        // repetition is O(relevant² × reps), and enumerating every row makes the relevant set grow WITH the
        // document — measured 8.63× growth for 4× rows on `laws/PartialSumOfProductsPerfTest` (a growth
        // figure, so interpreter-only and unaffected by that guard's since-corrected kernel baseline).
        val covering = coveringIndexes(relevant)
        val decided = HashMap<Triple<String, Int, List<Int>>, Boolean>()
        return { starPath, bindDepth, rowReps ->
            val scope = starScope(starPath, bindDepth, rowReps)
            decided.getOrPut(Triple(starPath, bindDepth, scope.bound)) {
                coversEveryRepetition(covering(starPath).addressing(scope.bound), starPath, scope)
            }
        }
    }

    /** Per star path, the well-formed entities that speak about it ([covers]), indexed by their coordinates. */
    private fun coveringIndexes(relevant: Set<RelevantEntity>): (String) -> RowAddressIndex {
        val candidates = wellFormed(relevant)
        val byPath = HashMap<String, RowAddressIndex>()
        return { starPath -> byPath.getOrPut(starPath) { RowAddressIndex(candidates.filter { covers(it, starPath) }) } }
    }

    /**
     * Whether the relevant entities [addressing] this row cover every declared repetition TUPLE of the levels
     * [starPath]'s star iterates. Levels **deeper** than an entity's own path need no enumeration: a relevant
     * group makes every descendant relevant, which is the kernel's own rule, so such a level reads as wildcarded.
     */
    private fun coversEveryRepetition(
        addressing: List<RelevantEntity>,
        starPath: String,
        scope: StarScope,
    ): Boolean {
        if (scope.levels.isEmpty()) return addressing.isNotEmpty()

        val parts = pathParts(starPath)
        val declared = IntArray(scope.levels.size) { k ->
            model.declaredReps["/" + parts.take(scope.levels[k] + 1).joinToString("/")] ?: return false
        }
        // Each addressing entity becomes a BOX over the iterated levels — a concrete index pins that level,
        // ALL (and any level deeper than the entity's own path) spans it.
        val boxes = addressing.map { e ->
            IntArray(scope.levels.size) { k ->
                val i = scope.levels[k]
                if (i >= e.indices.size) RelevantEntity.ALL else e.indices[i]
            }
        }
        return boxesCoverEveryTuple(0, declared, boxes)
    }

    /**
     * Whether a union of boxes covers the full cross-product of [declared], from level [k] down.
     *
     * Recursing on the boxes that WILDCARD level `k` first is what keeps this linear in practice: if they alone
     * cover the remaining levels, every index of `k` is covered and no per-index descent is needed. Otherwise
     * each declared index must be covered by the wildcards plus the boxes pinning it — and an index no box
     * pins at all fails immediately, so the descent is bounded by the concrete indices actually present rather
     * than by the declared repetition counts.
     */
    private fun boxesCoverEveryTuple(k: Int, declared: IntArray, boxes: List<IntArray>): Boolean {
        if (k == declared.size) return boxes.isNotEmpty()
        val wild = boxes.filter { it[k] == RelevantEntity.ALL }
        if (boxesCoverEveryTuple(k + 1, declared, wild)) return true
        val pinned = boxes.filter { it[k] != RelevantEntity.ALL }.groupBy { it[k] }
        return (1..declared[k]).all { v ->
            val here = pinned[v] ?: return false
            boxesCoverEveryTuple(k + 1, declared, wild + here)
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
     * The shared validation pipeline, parameterized by the call's [relevance]. Full validation passes
     * [CallRelevance.FULL]; partial passes the seams its relevant set derives. Formal errors first — the
     * kernel checks every field before evaluating rules: format errors on every filled-but-malformed field,
     * and `mandatoryField` on every required-but-empty field. Both make the field "unknown", so a rule
     * referencing it sees Val.Unknown (the 3VL propagates it). Every emitted message is gated by its error
     * field's relevance.
     */
    private fun validateWith(stored: Document, locale: String, relevance: CallRelevance): ValidationOutcome {
        val doc = callDocument(stored)
        return observeCaches(doc) {
            val isRelevant = relevance.isRelevant
            // The index-default staging split (SPEC-2026-07-23-14): a FULL call stages each eligible
            // clean-empty index cell's S-value into a call-local view — before the generated checks and
            // authored rules, never mutating the caller's document (the kernel's transient evaluation
            // cache); a PARTIAL call does NOT stage and instead silently marks those cells unavailable
            // (the `unavailable` seam of the formal-status cache below).
            val indexDefaults = checks.eligibleIndexDefaults(doc)
            val full = isRelevant === ALL_RELEVANT
            val call = if (full && indexDefaults.isNotEmpty()) doc.withComputed(indexDefaults) else doc
            val unavailable = if (full) emptySet() else indexDefaults.keys
            // Computed on the STAGED document (the one every gate below consults), once per call (IG127.1).
            val gateNeeded = checks.overRepetitionGateNeeded(call)
            val valueAdmissions = ValueAdmissionCache(checks, locale, call, gateNeeded)
            val dup = checks.duplicateIndexCells(call, valueAdmissions, isRelevant)   // computed ONCE: feeds both the uniqueIndex messages and the suppression
            lateinit var formalResults: FormalResultCache
            val admittedGroupContent by lazy(LazyThreadSafetyMode.NONE) {
                AdmittedGroupContentIndex.build(model, call, isRelevant, relevance.groupRelevance, formalResults, paths)
            }
            formalResults = FormalResultCache(
                checks,
                locale,
                dup,
                unavailable,
                { ctx, path -> admittedGroupContent.isFilled(ctx, path) },
                valueAdmissions,
            )
            // Custom-type unsupported is collected up front (cells whose type is in NEITHER registry); formalErrors
            // ADDS to it any REGISTERED code-based type whose validate() can't run (a JS worker down / errored), so
            // that degrades to unsupported per cell rather than crashing the whole validation.
            val unsupported = checks.customTypeUnsupported(call, isRelevant).toMutableList()
            val messages = (checks.formalErrors(call, locale, isRelevant, formalResults, unsupported) +
                    checks.mandatoryErrors(call, locale, isRelevant, formalResults, unsupported)
                    + checks.uniquenessMessages(call, dup, locale, isRelevant)
                    + checks.overRepetitionMessages(call, locale, ALL_RELEVANT,
                        gateNeeded && relevance.structuralChecksEnabled)).toMutableList()
            // The per-call seam (IF117): the formal-status memo spans ALL rules of this validation (the doc is
            // immutable here), and the EvalConfig is built once for the whole call.
            val formallyIncorrect by lazy(LazyThreadSafetyMode.NONE) {
                formalResults.invalidPointers()
            }
            val groupPresence by lazy(LazyThreadSafetyMode.NONE) {
                GroupPresenceIndex(admittedGroupContent, formallyIncorrect, relevance.groupRelevance)
            }
            val indexIdentityAdmitted: (String, List<Int>, String) -> Boolean = { path, reps, raw ->
                isRelevant(path, reps) && formalResults.admitsIdentityAt(path, reps, raw)
            }
            val pass = ValidationPass(call, doc, locale, isRelevant, relevance.relevantWithin, dup,
                validationConfig(
                    relevance,
                    indexIdentityAdmitted,
                    relevance.groupExtents?.gate { path, reps -> groupPresence.state(path, reps) },
                ) { path, reps -> groupPresence.state(path, reps) },
                formalResults, { formallyIncorrect }, relevance.parallelSelection,
                { checks.messageIndexDefaults(call, formalResults::admitsIdentityAt) })
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
        return evalRule(rule, callDocument(doc), locale)
    }

    /** Evaluate an injected, NON-stored candidate rule (the `model eval --condition/--field` shape). */
    fun evalCandidate(rule: RuleDef, doc: Document, locale: String = "en_US"): RuleResult =
        evalRule(rule, callDocument(doc), locale)

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
        val doc = callDocument(stored)
        observeCaches(doc) {
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
        callDocument(stored).let { doc -> observeCaches(doc) {
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
        } }

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
        val call = callDocument(doc)
        return observeCaches(call) { engine.computeField(fieldPath, call, locale) }
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

    private fun evalRule(rule: RuleDef, doc: Document, locale: String): RuleResult = observeCaches(doc) {
        // A single-rule pass (fresh formal-status memo, everything relevant) — the testRule/evalCandidate shape.
        // Full-call semantics, so the eligible index defaults are staged exactly as in [validate] (SPEC-2026-07-23-14).
        val indexDefaults = checks.eligibleIndexDefaults(doc)
        val call = if (indexDefaults.isEmpty()) doc else doc.withComputed(indexDefaults)
        val valueAdmissions = ValueAdmissionCache(checks, locale, call, checks.overRepetitionGateNeeded(call))
        val dup = checks.duplicateIndexCells(call, valueAdmissions)
        val relevance = CallRelevance.FULL
        lateinit var formalResults: FormalResultCache
        val admittedGroupContent by lazy(LazyThreadSafetyMode.NONE) {
            AdmittedGroupContentIndex.build(model, call, relevance.isRelevant, relevance.groupRelevance, formalResults,
                paths)
        }
        formalResults = FormalResultCache(
            checks,
            locale,
            dup,
            groupFilled = { ctx, path -> admittedGroupContent.isFilled(ctx, path) },
            valueAdmissions = valueAdmissions,
        )
        val formallyIncorrect by lazy(LazyThreadSafetyMode.NONE) {
            formalResults.invalidPointers()
        }
        val groupPresence by lazy(LazyThreadSafetyMode.NONE) {
            GroupPresenceIndex(admittedGroupContent, formallyIncorrect, relevance.groupRelevance)
        }
        val pass = ValidationPass(
            call,
            doc,
            locale,
            relevance.isRelevant,
            relevance.relevantWithin,
            dup,
            validationConfig(relevance, formalResults::admitsIdentityAt, null) { path, reps -> groupPresence.state(path, reps) },
            formalResults,
            { formallyIncorrect },
            messageIndexDefaults = { checks.messageIndexDefaults(call, formalResults::admitsIdentityAt) },
        )
        val rows = rowOutcomes(rule, pass)
        rows.firstNotNullOfOrNull { it as? RowOutcome.Fired }?.let {
            return@observeCaches RuleResult(rule.path, Verdict.FIRED, it.message)
        }
        // No firing: a referenced field being formally invalid means the rule was NOT evaluated (the
        // kernel skips it) → SUPPRESSED, not PASSED. PASSED only when every row was cleanly evaluated.
        val suppressed = rows.filterIsInstance<RowOutcome.Suppressed>().map { it.suppression }
        if (suppressed.isNotEmpty()) RuleResult(rule.path, Verdict.SUPPRESSED, suppressedBy = suppressed)
        else RuleResult(rule.path, Verdict.PASSED)
    }

    /**
     * [stored] read under this interpreter's effective charset. A consumer may supply a charset the model does not
     * declare, and every read of a stored value, a computation's star read included, must judge it by the same set
     * the formal checks use (KERNEL-FINDINGS KF555).
     */
    private fun callDocument(stored: Document): Document = stored.withLegalCharset(effectiveLegalCharset)

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
    private fun validationConfig(relevance: CallRelevance,
            indexIdentityAdmitted: (String, List<Int>, String) -> Boolean,
            groupCountGate: GroupCountGate?,
            groupPresence: (String, List<Int>) -> GroupPresenceState): EvalConfig =
        EvalConfig(clock(), model.baseYear, model.indexFields, timeZone = model.timeZone,
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
        val parallel = prepared.iteration as? IterationPlan.Parallel
        if (parallel != null && pass.parallelSelection?.admits(errorPath, parallel) == false) return emptyList()
        // A rule whose condition contains a RepetitionNotUnique leaf gets its duplicate cache built ONCE (IF149),
        // guard-independent, keyed by error-path reps; the per-row seam looks the current row up in O(1). Null for
        // a normal rule (no cache, no seam). Building here can throw UnsupportedConstructException (>1 RNU, a
        // group/asterisk key, a too-deep scope) — the caller's per-rule tolerance reports it unsupported.
        val rnuCache = rnuEval.outcomes(prepared.repetitionNotUnique, pass)
        val directMessageRefs = prepared.messageReferences
        val selectedErrorReference = prepared.semanticErrorIndex?.let {
            directMessageRefs.filterIsInstance<MessageReference.Selected>().first { it.path == errorPath }
        }
        fun errorPointer(ctx: EvalContext): A12PartiallyKnownMultiPointer {
            val repetitions = pass.doc.messageRepetitions(errorPath, ctx)
            val reference = selectedErrorReference ?: return pass.doc.messagePointer(errorPath, repetitions)
            val level = paths.groupsThrough(errorPath).indexOf(parentGroup(checkNotNull(reference.indexField)))
            val selected = selectedMessageRepetitions(reference, pass, ctx)?.get(level)
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
        ).map { iterationContext ->
            prepared.semanticErrorIndex?.let { semanticIndex ->
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
            } ?: iterationContext
        }.let { contexts ->
            if (prepared.semanticErrorIndex == null) contexts
            else contexts.distinctBy { context -> context.repsOf(errorPath) }
        }
        // The iteration half of the work tally, counted after the early returns above so an eliminated or
        // partial-skipped rule contributes the zero rows it actually evaluates (WorkLedger).
        pass.config.work?.rowsEvaluated(iterationContexts.size)
        fun rowOutcome(ctx: EvalContext): RowOutcome {
            // Relevance controls whether a rule executes, not just message emission. Ordinary rules use
            // bound levels (IF268); parallel dispatch owns its compressed selector (SPEC10).
            val runs = if (parallel != null && pass.parallelSelection != null)
                pass.parallelSelection.includes(errorPath, parallel, ctx)
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
            // A condition that can fail at runtime makes the reached leaves observable, so it takes the kernel's two
            // passes instead; elsewhere the single walk gives the same verdict and type at lower cost.
            val firedType = if (prepared.canFailAtRuntime) interp.firedTypeInTwoPasses(ast, prepared.classifies)
                else interp.firedType(ast)
            if (firedType != null) {
                val type = firedType
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
                val matchingRnuOutcome = rnuCache?.rows?.get(ctx.repsOf(errorPath))?.takeIf { it.type == type }
                // When the RNU leaf contributes this message's polarity, its cluster projection already contains
                // every occurrence of an RNU key path. Drop a same-path ordinary occurrence, render the remaining
                // small prefix, and expose an O(1) immutable joined view over the shared cluster list. This keeps
                // exact list semantics without copying the N-entry peer projection into every one of N messages.
                val directRefs = if (matchingRnuOutcome != null) directRefsOutsideRnuKeys.orEmpty() else directMessageRefs
                val directPointers = directRefs.map { messagePointer(it, pass, ctx) }.distinct()
                val rnuPointers = matchingRnuOutcome?.peerKeyPointers.orEmpty()
                val referenced = joinedList(directPointers, rnuPointers)
                // refOmissionErrorResponsible: the referenced operands that can cause an omission, filled or not,
                // fixed per rule ([omissionReferences]); the RNU cluster stays in. Empty for a VALUE error.
                val fillToFix = if (type == MsgType.OMISSION) {
                    if (directRefs.all { it in prepared.omissionReferences }) referenced
                    else joinedList(directRefs.filter { it in prepared.omissionReferences }
                        .map { messagePointer(it, pass, ctx) }.distinct(), rnuPointers)
                } else emptyList()
                return RowOutcome.Fired(Message(rule.errorCode, rule.path,
                    errorPointer(ctx), rule.severity, type, text, referenced,
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

    /** Project one prepared message operand into A12's partially-known pointer domain. The wildcard coordinates
     *  belong only to the rich message channel; iteration/suppression deliberately continue to use the
     *  star-free reference view. */
    private fun messagePointer(reference: MessageReference, pass: ValidationPass, ctx: EvalContext) = when (reference) {
        is MessageReference.Ordinary -> pass.doc.messagePointer(reference.path, pass.doc.messageRepetitions(reference.path, ctx))
        is MessageReference.Selected -> selectedPointer(reference, pass, ctx)
        is MessageReference.Wildcard -> {
            val prefixes = paths.groupsThrough(reference.path)
            val bindDepth = if (reference.bindDepth >= 0) minOf(reference.bindDepth, prefixes.size)
            else prefixes.size - if (model.kinds[reference.path] == null) 1 else 2
            val repetitions = pass.doc.messageRepetitions(reference.path, ctx).mapIndexed { level, repetition ->
                if (level >= bindDepth && prefixes[level] in model.repeatable) A12PartiallyKnownMultiPointer.WILDCARD
                else repetition
            }
            pass.doc.messagePointer(reference.path, repetitions)
        }
    }

    /**
     * A selected operand's pointer: the firing row's coordinates above the indexed group, the row the key selects
     * at that level — A12's unknown row when no admitted row carries the key, or the key is empty — and every row
     * at a repeatable level below it (`adapter.laws.SelectedMessageReferenceDiffTest`).
     */
    private fun selectedPointer(reference: MessageReference.Selected, pass: ValidationPass, ctx: EvalContext) = run {
        val indexField = checkNotNull(reference.indexField) { "a selected reference is expanded before it is resolved" }
        val levels = paths.groupsThrough(reference.path)
        val indexLevel = levels.indexOf(parentGroup(indexField))
        val selected = selectedMessageRepetitions(reference, pass, ctx)
        val repetitions = pass.doc.messageRepetitions(reference.path, ctx).mapIndexed { level, repetition ->
            when {
                level < indexLevel -> repetition
                level == indexLevel -> selected?.get(level) ?: A12PartiallyKnownMultiPointer.UNKNOWN
                levels[level] in model.repeatable -> A12PartiallyKnownMultiPointer.WILDCARD
                else -> repetition
            }
        }
        pass.doc.messagePointer(reference.path, repetitions)
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
        val selected = io.github.mbackschat.a12.dm.interpreter.parse.parseExpression(written, ruleGroup, model.conditionLanguage)
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
        val repetitions = selectedMessageRepetitions(reference, pass, ctx)
            ?: return SelectedCell(reference.path, null, matched = false)
        val stored = pass.callerDoc.messageDisplayValueAt(reference.path, repetitions)
        return SelectedCell(reference.path, presentedDisplayValue(stored, reference.path, pass.locale), matched = true)
    }

    private fun selectedMessageRepetitions(
        reference: MessageReference.Selected, pass: ValidationPass, ctx: EvalContext,
    ): List<Int>? {
        val key = when (val written = reference.key) {
            is SelectedKey.Literal -> written.value
            is SelectedKey.Field -> pass.checkedKeyValue(ctx, written.path)
        }?.takeIf { it.isNotEmpty() } ?: return null
        return pass.selectedMessageRepetitions(ctx, reference.path, checkNotNull(reference.indexField), key)
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
            return selectedMessageRepetitions(selected, pass, ctx)?.lastOrNull()?.toString() ?: "?"
        }
        val argument = written.replace("'", "")
        val path = when (argument) {
            messageTerminals.ruleGroup -> ruleGroup
            messageTerminals.rootGroup -> ruleGroup.split('/').take(2).joinToString("/")
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

    /** One shared production index for concrete-cell and bound-level rule-gate relevance. */
    private fun relevanceQueriesOf(relevant: Set<RelevantEntity>): EffectiveRelevanceIndex =
        EffectiveRelevanceIndex.build(model.globalFields, relevant)

    /**
     * Partial validation's two group-relevance levels. Any selected descendant makes its ancestors PARTIAL,
     * which is enough to prove positive presence. Absence is known only when an ancestor selection covers the
     * whole group, or every declared descendant field is covered across every deeper repeatable level.
     */
    private fun groupRelevanceOf(relevant: Set<RelevantEntity>): (String, List<Int>) -> GroupRelevance {
        val effective = relevant + model.globalFields.map(RelevantEntity::anyRep)
        // PartialParentGroupCoverageDiffTest: fixed-group coordinates above one cannot prove absence;
        // terminal field coordinates do not narrow that coverage, and partial presence stays independent.
        val coversField by lazy {
            val coverage = wellFormed(effective).filterTo(HashSet()) { entity ->
                entity.path in model.entityPaths && paths.groupsThrough(entity.path).withIndex().all { (level, path) ->
                    path in model.kinds || path in model.repeatable || entity.indices[level] in 0..1
                }
            }
            starExtentKnownOf(coverage)
        }
        val fieldsByAncestor by lazy {
            val fields = HashMap<String, MutableList<String>>()
            for (field in model.fields) {
                val parts = pathParts(field.path)
                for (depth in 1 until parts.size) {
                    fields.getOrPut("/" + parts.take(depth).joinToString("/")) { ArrayList() } += field.path
                }
            }
            fields
        }
        val cache = HashMap<String, HashMap<List<Int>, GroupRelevance>>()
        // Both questions are indexed, because a relevant set that names every row would otherwise be scanned once
        // per group instance (IF412, `GrowingRelevancePartialPerfTest`). An entity TOUCHES a group when one path
        // is a prefix of the other and its indices agree on the levels both carry; it COVERS one when its own path
        // is the group's or an ancestor's and every index it carries agrees.
        val touching = HashMap<String, IndexTrie>()
        fun touchesGroup(groupPath: String, groupReps: List<Int>): Boolean = touching.getOrPut(groupPath) {
            val groupParts = pathParts(groupPath)
            IndexTrie().also { trie ->
                for (entity in effective) {
                    val entityParts = pathParts(entity.path)
                    val common = minOf(entityParts.size, groupParts.size)
                    if (entityParts.take(common) == groupParts.take(common)) trie.add(entity.indices.take(common))
                }
            }
        }.agreesWithin(groupReps)
        val covering by lazy {
            val tries = HashMap<List<String>, IndexTrie>()
            for (entity in effective) {
                val entityParts = pathParts(entity.path)
                if (entity.indices.size == entityParts.size) tries.getOrPut(entityParts) { IndexTrie() }.add(entity.indices)
            }
            tries
        }
        fun coversGroup(groupPath: String, groupReps: List<Int>): Boolean {
            val groupParts = pathParts(groupPath)
            return (1..groupParts.size).any { depth -> covering[groupParts.take(depth)]?.selects(groupReps, depth) == true }
        }

        return { groupPath, groupReps ->
            cache.getOrPut(groupPath) { HashMap() }.getOrPut(groupReps) {
                if (!touchesGroup(groupPath, groupReps)) {
                    GroupRelevance.NONE
                } else if (coversGroup(groupPath, groupReps) ||
                    fieldsByAncestor[groupPath].orEmpty().all { field ->
                        coversField(field, pathParts(groupPath).size, groupReps)
                    }) {
                    GroupRelevance.FULL
                } else {
                    GroupRelevance.PARTIAL
                }
            }
        }
    }
}
