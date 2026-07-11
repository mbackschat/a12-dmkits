package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.emptyValue
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.eval.PoisonedReadException
import io.github.mbackschat.a12.dm.interpreter.eval.UNKNOWN_REP
import io.github.mbackschat.a12.dm.interpreter.eval.OverlayContext
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.typeFormatError
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.renderDateToFormat
import io.github.mbackschat.a12.dm.interpreter.eval.renderMessage
import io.github.mbackschat.a12.dm.interpreter.eval.storedComputedNumber
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.Computed
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.FieldPointer
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.Message
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedType
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedTypeRegistry
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleResult
import io.github.mbackschat.a12.dm.interpreter.model.Suppression
import io.github.mbackschat.a12.dm.interpreter.model.UnsupportedRule
import io.github.mbackschat.a12.dm.interpreter.model.ValidationOutcome
import io.github.mbackschat.a12.dm.interpreter.model.Verdict
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import io.github.mbackschat.a12.dm.interpreter.parse.parseExpression
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
     *  so the declarative per-cell path stays monomorphic ([CustomFieldType]'s KDoc; `CustomTypePerfTest`). A
     *  custom-typed cell whose name is in NEITHER registry is reported unsupported (the lenient degrade). */
    private val customFieldTypes: Map<String, CustomFieldType> = emptyMap(),
    /** The legal character set field values are validated against — the kernel's project-level restricted charset
     *  ([LegalCharset], IF43). **Default [LegalCharset.BMP]** (the kernel's DM-JSON runtime default: an emoji /
     *  supplementary-plane char is illegal). A consumer running the kernel with a narrower programming-language
     *  charset (e.g. **DIN 91379**) supplies the matching set here so the interpreter enforces the SAME restriction
     *  — the interpreter must expose every kernel customization to be 100% compatible. */
    private val legalCharset: LegalCharset = LegalCharset.BMP,
) {

    /** The auto-check emitter + formal-status oracle — the call-invariant I2 seam collaborator (IF117). */
    private val checks = AutoChecks(model, legalCharset, predefinedTypes, customFieldTypes)

    /**
     * **Parse once, reuse across calls** — the prepare-once optimization (INTERPRETER-PERFORMANCE.md "the
     * interpreter's two goals"). Each rule's condition / computation's expression is parsed to an AST exactly
     * ONCE, on first use, then cached for every subsequent `validateFull`/`compute`/`testRule` call and every
     * row. This is what makes the interpreter **on-par with the kernel at all rule counts**: the kernel
     * code-gens once at model-load, and this is the direct analog (the per-call re-parse was the #31 cost that
     * made interp/kernel cross 1.0 past ~48 rules). It is **lazy** (the constructor only builds the wrappers, no
     * parsing) so **startup stays fast** and a single-rule [testRule] forces only the one rule it touches — the
     * two goals, met without trading one for the other. `lazy {}` is SYNCHRONIZED, so the engine is safe to
     * reuse across threads (the kernel's caches are concurrent too); each AST is parsed exactly once even under
     * a race. A candidate rule not in the model (an ad-hoc [evalCandidate]) falls back to a one-off parse.
     */
    private val ruleCondAst: Map<RuleDef, Lazy<Cond>> =
        model.rules.associateWith { r -> lazy { parseCondition(r.conditionText, r.group) } }
    private val compParsedAst: Map<ComputationDef, Lazy<ParsedComputation>> =
        model.computations.associateWith { c -> lazy { parseComputation(c) } }

    /** The implicit per-computation validation rules (IF32/IF65): one synthetic [RuleDef] per named computation,
     *  with its condition AST **built directly from the already-parsed alternatives** ([parsedOf]) rather than
     *  re-parsed from text — so the embedded operation/precondition ASTs are byte-identical to the ones the
     *  compute path uses, and no fragile string round-trip can fail to re-parse (IF65). The AST is cached (lazy,
     *  forced by [condAstOf]) exactly like a model rule's. */
    private val implicitComputationAst: Map<RuleDef, Lazy<Cond>> =
        model.computations.mapNotNull { c -> implicitComputationRuleDef(c)?.let { it to lazy { implicitComputationCond(c) } } }.toMap()

    /** A computation's parsed form: each tiered alternative's (precondition?, operation), under an optional
     *  whole-computation common precondition — all parsed once. The kernel selects the first alternative whose
     *  precondition holds (null precondition = unconditional); a present common precondition gates them all. */
    private class ParsedComputation(val commonPrecondition: Cond?, val alternatives: List<ParsedAlternative>)
    private class ParsedAlternative(val precondition: Cond?, val operation: Expr)

    private fun parseComputation(c: ComputationDef): ParsedComputation = ParsedComputation(
        c.commonPrecondition?.let { parseCondition(it, c.group) },
        c.alternatives.map { ParsedAlternative(it.precondition?.let { p -> parseCondition(p, c.group) }, parseExpression(it.operation, c.group)) },
    )

    /** The parsed condition AST for [rule] — cached for a model rule or an implicit computation-validation rule;
     *  a one-off parse for an ad-hoc candidate. */
    private fun condAstOf(rule: RuleDef): Cond =
        ruleCondAst[rule]?.value ?: implicitComputationAst[rule]?.value ?: parseCondition(rule.conditionText, rule.group)

    /** The parsed form of [comp] (a model computation) — cached. */
    private fun parsedOf(comp: ComputationDef): ParsedComputation = compParsedAst.getValue(comp).value

    /**
     * Run every rule over each row of its iteration scope and return one [Message] per firing — the
     * runtime twin of the kernel's `validateFull`. A rule fires when its error condition is TRUE on a row.
     * Convenience over [validate] returning just the messages (drops the unsupported-rule report).
     */
    fun validateFull(doc: Document, locale: String = "en_US"): List<Message> =
        validate(doc, locale).messages

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
        validateWith(doc, locale, relevanceOf(relevant), customRelevantOf(relevant), starRelevantOf(relevant))

    /**
     * Whether a STARRED aggregate over `starPath` is relevant in PARTIAL mode (IF42). The kernel evaluates an
     * all-rows aggregate (`Sum`/`Max`/`Min`/`NumberOfDifferentValues`/`SumOfProducts`) only when the relevant set
     * **wildcards** every repeatable level of the path — `ALL_SEMANTIC` at the starred group, or a relevant
     * ANCESTOR group (whose descent makes all rows relevant). Enumerating concrete rows — even ALL of them — is
     * NOT enough (kernel-probed: the `*` reference is "fully known" only via a wildcard). A relevant entity covers
     * the star iff it is an ancestor-or-equal prefix AND wildcards every repeatable level it itself specifies
     * (levels deeper than it are covered by group descent). `FirstFilled` does NOT use this — it is order-aware.
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
     *  [FieldPointer]s and each global field rides as an all-reps-wildcard pointer. */
    private fun customRelevantOf(relevant: Set<RelevantEntity>): Set<FieldPointer> =
        relevant.mapTo(HashSet()) { FieldPointer(it.path, it.indices) } +
            model.globalFields.map { FieldPointer(it, List(pathParts(it).size) { RelevantEntity.ALL }) }

    /**
     * The shared validation pipeline, parameterized by [isRelevant] (`(path, reps) → Boolean`). Full
     * validation passes [ALL_RELEVANT]; partial passes a relevance predicate. Formal errors first — the
     * kernel checks every field before evaluating rules: format errors on every filled-but-malformed field,
     * and `mandatoryField` on every required-but-empty field. Both make the field "unknown", so a rule
     * referencing it sees Val.Unknown (the 3VL propagates it). Every emitted message is gated by its error
     * field's relevance.
     */
    private fun validateWith(doc: Document, locale: String, isRelevant: Relevance,
            customRelevant: Set<FieldPointer>? = null, starRelevant: ((String) -> Boolean)? = null): ValidationOutcome {
        val dup = checks.duplicateIndexCells(doc, isRelevant)   // computed ONCE: feeds both the uniqueIndex messages and the suppression
        // Custom-type unsupported is collected up front (cells whose type is in NEITHER registry); formalErrors
        // ADDS to it any REGISTERED code-based type whose validate() can't run (a JS worker down / errored), so
        // that degrades to unsupported per cell rather than crashing the whole validation.
        val unsupported = checks.customTypeUnsupported(doc, isRelevant).toMutableList()
        val messages = (checks.formalErrors(doc, locale, isRelevant, unsupported) + checks.mandatoryErrors(doc, locale, isRelevant)
                + checks.uniquenessMessages(dup, locale, isRelevant) + checks.overRepetitionMessages(doc, locale, isRelevant)).toMutableList()
        // The per-call seam (IF117): the formal-status memo spans ALL rules of this validation (the doc is
        // immutable here), and the EvalConfig is built once for the whole call.
        val pass = ValidationPass(doc, locale, isRelevant, dup,
            validationConfig(isRelevant, customRelevant, starRelevant), checks)
        for (rule in model.rules) {
            try {
                val rnu = repetitionNotUnique(rule)
                messages += if (rnu != null) repetitionNotUniqueMessages(rule, rnu, pass)
                else messagesFor(rule, pass)
            } catch (e: UnsupportedConstructException) {
                unsupported += UnsupportedRule(rule.path, e.message ?: "unsupported construct")
            }
        }
        // Every computation is ALSO a validation rule (KERNEL-FINDINGS, IF32/IF65): the kernel auto-generates one
        // implicit validation per computation — a field gate `FieldFilled(F) And` then the guard/tier selection
        // and `[F] != <selected operation>` — that fires under the computation's NAME when a stored computed value
        // disagrees with what the (guard-selected) tier computes. This covers GUARDED/TIERED computations, not
        // just the degenerate single-unconditional one (IF65). Evaluated through the same rule machinery.
        for (implicit in implicitComputationAst.keys) {
            try {
                messages += messagesFor(implicit, pass)
            } catch (e: UnsupportedConstructException) {
                unsupported += UnsupportedRule(implicit.errorEntityRelPath, e.message ?: "unsupported computation validation")
            }
        }
        return ValidationOutcome(messages, unsupported)
    }

    /** The synthetic [RuleDef] for [comp]'s implicit validation (IF32/IF65): error code = the computation's NAME,
     *  rule element at `<group>/<name>` (a sibling of the computed field, like the kernel's rule-from-computation).
     *  The error entity is the computed field [ComputationDef.targetPath]; the synthetic error-entity relpath is the
     *  general relative path FROM the rule element TO that absolute target ([relativePath], matching how
     *  [resolveErrorPath] climbs `../` from [RuleDef.path]).
     *
     *  The computed field may NOT be a descendant of the computation's group — a computation can target a field in a
     *  sibling subtree, or in ANOTHER top-level root (a `header`/`content` multi-root model), its DM-JSON
     *  `computedFieldRelPath` walking `../` up past its own group. The earlier form `"../" + targetPath.removePrefix(group)`
     *  assumed the target sat under the group: for a cross-subtree target the `removePrefix` no-op'd and left an
     *  absolute path, so the relpath became a `..//abs/path` double-slash that [resolveErrorPath] mangled into a
     *  wrong, over-long pointer (IF66, graduated from IG41). [relativePath] resolves to the exact target for all three
     *  shapes (descendant, sibling-subtree, cross-root). Its condition AST is supplied by [implicitComputationCond]
     *  via [implicitComputationAst], never parsed from the placeholder [RuleDef.conditionText]. Null when the
     *  computation is unnamed or has no alternatives. */
    private fun implicitComputationRuleDef(comp: ComputationDef): RuleDef? {
        val name = comp.name ?: return null
        if (comp.alternatives.isEmpty()) return null
        // Anchored at the TARGET's parent group, NOT the declaring group — the kernel's rule-from-computation
        // fires at the error entity (the computed field), so a computation declared in an EMPTY container (a
        // `*_Rules` root targeting another subtree/root) must not be row-gated away by its declaring group's
        // lack of content (IF100 — the validation twin of IF89's "a computation iterates where it WRITES").
        // A cross-group REPEATABLE target never reaches the runtime (codegen-rejected,
        // MVK_ERROR_FIELD_NOT_IN_RULEGROUP), so the anchor move changes no per-row iteration.
        val group = parentGroup(comp.targetPath)
        val node = "$group/$name"
        return RuleDef(node, group, "FieldFilled(${comp.targetPath}) And <implicit validation of $name>",
            name, relativePath(node, comp.targetPath))
    }

    /**
     * The implicit computation-validation condition AST, built directly from [comp]'s already-parsed alternatives
     * ([parsedOf]) — mirroring the kernel's `ComputationAlternativesJoiner.join()` (md-model
     * `ValidationModelConverter.getComputationStatement`): a **field gate** `FieldFilled(F) And`, then the optional
     * `(commonPrecondition) And`, then the alternatives — a SINGLE alternative is `[(precondition) And] [F] != op`;
     * **>1** alternatives are the `Or` of each so-gated comparison. So the field is flagged when it is filled and,
     * for a tier whose precondition holds, its stored value disagrees with that tier's operation — the exact
     * per-tier semantics, gated by the same preconditions the compute path uses (so `compute` and this validation
     * never disagree on which tier applies). Building the AST from the parsed components (not a re-parsed string)
     * means the operation/precondition ASTs are identical to compute's and cannot fail to re-parse (the initial
     * string-synthesis attempt introduced parser crashes — IF65). (`@SuppressWarning` + tolerance-`!=` ops are not modeled:
     * absent from the corpora; a tolerance-op computation would compare with `!=`, a narrow over-fire to revisit.) */
    private fun implicitComputationCond(comp: ComputationDef): Cond {
        val parsed = parsedOf(comp)
        val fExpr = Expr.Field(comp.targetPath)
        fun alternative(a: ParsedAlternative): Cond {
            val cmp = Cond.Cmp(CmpOp.NE, fExpr, a.operation)
            return a.precondition?.let { Cond.And(it, cmp) } ?: cmp
        }
        val body = parsed.alternatives.map(::alternative).reduce { l, r -> Cond.Or(l, r) }
        val guarded = parsed.commonPrecondition?.let { Cond.And(it, body) } ?: body
        return Cond.And(Cond.Pred("FieldFilled", listOf(fExpr)), guarded)
    }

    /** A recognized `RepetitionNotUnique` rule: its key field paths, the optional `@From` reference-group ref,
     *  and the optional [guard] — the conjunction of the OTHER top-level conjuncts when the RNU predicate is
     *  one conjunct of an And-tree (null when RNU is the whole condition). */
    private data class RnuRule(val keys: List<String>, val fromScope: String?, val guard: Cond?)

    /**
     * If [rule]'s condition is `[<guard> And] RepetitionNotUnique(key, … [@From ref])` — the RNU predicate
     * standing alone OR as exactly ONE top-level conjunct of an And-tree — its keys, the optional `@From` ref,
     * and the guard (the AND of the remaining conjuncts, or null); else null (a normal rule, handled per-row).
     *
     * The kernel evaluates RepetitionNotUnique as a **per-row 3VL predicate** whose duplicate cache is built
     * over the full scope **independent of the guard** — confirmed in BOTH strategies: the runtime
     * `RuntimeController.wiederholungNichtEindeutig` builds the cache once (`isInitialised`) over all rows in
     * `ermittleWiederholungenMitDuplikaten`, then per-row returns TRUE_WF/TRUE_AF/FALSE_OR_UNKNOWN; the codegen
     * `EntityListCondition.st` emits a call to that same method with a per-rule `RepetitionNotUniqueErrorCache`.
     * So a guard is just a separate conjunct combined by the ordinary 3VL And ([repetitionNotUniqueMessages]
     * gates each duplicate row by it).
     *
     * Recognition stays STRICT — degrade loud (IF61), never silently wrong — returning null when the shape is
     * NOT this per-row-predicate-under-And form: RNU under an `Or` or nested below a non-`And` (never appears as
     * a top-level conjunct → 0 found), more than one RNU conjunct, a group/asterisk (non-`Expr.Field`) key (the
     * kernel forbids those), or a guard whose iteration scope is DEEPER than the keys' group (an iteration
     * granularity we don't model). None of these occurs in the real corpora; each is handled loud not wrong.
     */
    private fun repetitionNotUnique(rule: RuleDef): RnuRule? {
        val conjuncts = flattenAnd(condAstOf(rule))
        val rnuPreds = conjuncts.filter { it is Cond.Pred && it.name == "RepetitionNotUnique" }
        if (rnuPreds.size != 1) return null                              // 0 → not an RNU rule; >1 → unmodeled
        val pred = rnuPreds[0] as Cond.Pred
        val guards = conjuncts.filter { it !== pred }
        if (guards.any { containsRepetitionNotUnique(it) }) return null  // RNU nested in another conjunct — unmodeled
        // The parser appends an `@From:<ref>` StrLit marker as the last arg when `@From` is present.
        val fromScope = (pred.args.lastOrNull() as? Expr.StrLit)?.v?.takeIf { it.startsWith("@From:") }?.removePrefix("@From:")
        val keyArgs = if (fromScope != null) pred.args.dropLast(1) else pred.args
        val keys = keyArgs.map { (it as? Expr.Field)?.path ?: return null }.ifEmpty { return null }
        val guard = guards.reduceOrNull { a, b -> Cond.And(a, b) }
        if (guard != null && !guardScopeWithinKeyGroup(guard, rule, keys.first())) return null
        return RnuRule(keys, fromScope, guard)
    }

    /** Flatten a top-level `And`-tree into its conjuncts; a non-`And` node is a single conjunct. */
    private fun flattenAnd(cond: Cond): List<Cond> =
        if (cond is Cond.And) flattenAnd(cond.left) + flattenAnd(cond.right) else listOf(cond)

    /** Whether a `RepetitionNotUnique` predicate appears anywhere within [cond] (any nesting/boolean position). */
    private fun containsRepetitionNotUnique(cond: Cond): Boolean = when (cond) {
        is Cond.Pred -> cond.name == "RepetitionNotUnique"
        is Cond.And -> containsRepetitionNotUnique(cond.left) || containsRepetitionNotUnique(cond.right)
        is Cond.Or -> containsRepetitionNotUnique(cond.left) || containsRepetitionNotUnique(cond.right)
        is Cond.Not -> containsRepetitionNotUnique(cond.c)
        else -> false
    }

    /** The guard's iteration scope must be the keys' repeatable group or ABOVE it, so each key row uniquely
     *  determines the guard's row (RNU iterates the key group; a deeper/sibling guard would need a different
     *  iteration granularity — unmodeled, degrade loud). */
    private fun guardScopeWithinKeyGroup(guard: Cond, rule: RuleDef, firstKey: String): Boolean {
        val keyGroup = parentGroup(firstKey)
        val guardScope = scopeFromRefs(nonStarredRefs(guard), rule.group)
        return keyGroup == guardScope || keyGroup.startsWith("$guardScope/")
    }

    /**
     * The scope prefix length: the rep-prefix length of the **reference group's PARENT** row. The reference
     * group is the `@From` ref when given ([rnu].fromScope), else the **basic version** default — the first
     * repeatable group below the rule group on the key path (BA "basic version", Situation 2). So a key two
     * repeatable levels deep (`/Project/Milestones/Tasks/Effort`, rule at `/Project`) is by default checked
     * across ALL milestones (scope = the `/Project` row); `@From Milestones/Tasks` narrows it to per-milestone.
     */
    private fun scopePrefixLen(rule: RuleDef, rnu: RnuRule): Int {
        if (rnu.fromScope != null) return resolveGroupRef(rnu.fromScope, rule.group).trim('/').split('/').size - 1
        val parts = rnu.keys.first().trim('/').split('/')
        var d = rule.group.trim('/').split('/').size + 1     // first group strictly below the rule group
        while (d < parts.size && "/" + parts.take(d).joinToString("/") !in model.repeatable) d++
        return d - 1                                          // parent(referenceGroup) depth
    }

    /** Resolve a `@From` reference-group ref to an absolute group path — the `RuleGroup` keyword (the rule's
     *  group), an absolute `/…` path, or a path relative to the rule group (handling leading `..` segments). */
    private fun resolveGroupRef(ref: String, ruleGroup: String): String {
        if (ref == "RuleGroup") return ruleGroup
        if (ref.startsWith("/")) return ref
        val segs = ruleGroup.trim('/').split('/').toMutableList()
        for (s in ref.split('/')) when {
            s == ".." -> if (segs.isNotEmpty()) segs.removeAt(segs.size - 1)
            s.isNotEmpty() -> segs.add(s)
        }
        return "/" + segs.joinToString("/")
    }

    /**
     * `RepetitionNotUnique(keys…)` — one [Message] per repetition row whose (composite) key DUPLICATES another
     * row's within the same parent-row scope (kernel-probed): empties match empties among participants, an
     * **all-empty** key row is skipped, and a duplicate whose key has an empty (fillable) component is
     * **OMISSION** (else VALUE). The rule sits at the parent and iterates the keys' repeatable group.
     */
    private fun repetitionNotUniqueMessages(rule: RuleDef, rnu: RnuRule, pass: ValidationPass): List<Message> {
        val keys = rnu.keys
        val repGroup = parentGroup(keys.first())     // the keys' shared repeatable group (kernel: keys on one path)
        val errorPath = resolveErrorPath(rule)
        val scopeLen = scopePrefixLen(rule, rnu)
        data class Row(val ctx: EvalContext, val reps: List<Int>, val key: List<String?>)
        val rows = pass.doc.contextsFor(repGroup).mapNotNull { ctx ->
            // A key cell that is FORMALLY INVALID (kernel `isWertAngegeben == UNKNOWN`) drops the WHOLE row from
            // the duplicate cache — exactly the kernel's getWerteOrNullIfEinFeldNichtPruefungsRelevant (any UNKNOWN
            // key cell → returns null → the row is `continue`-skipped). A cell is UNKNOWN whenever it is marked
            // `fehlerhaft` (not check-relevant), which is EVERY [formalErrorAt] cause: a bad FORMAT, a custom-type
            // rejection, a duplicate-INDEX (`uniqueIndex`) cell, an over-repetition cell, AND a required-EMPTY
            // cell — the kernel's mandatory check is a PRELIMINARY autogen rule (`isPreliminary` → `preliminaryError`
            // → `markFieldAsInvalidForValidation`) that runs BEFORE the RNU rule builds its cache, so a required-
            // empty key cell is UNKNOWN, not a valid empty participant. (This corrects IF64's original
            // MANDATORY_FIELD carve-out — the composite-key required-empty differential proved the kernel drops
            // the row; IF64 corrected 2026-07-07.) Contrast an OPTIONAL-empty key cell: NOT required → `formalErrorAt` is null →
            // `isWertAngegeben == FALSE` → a null key component → a valid empty participant (the OMISSION case).
            // Exclusion is per-KEY-CELL: a formal error on a NON-key field of the same row does not drop it (the
            // kernel checks only the key `entityList`).
            if (keys.any { k -> pass.formalErrorAt(k, ctx.repsOf(k), ctx) != null })
                return@mapNotNull null
            Row(ctx, ctx.repsOf(errorPath), keys.map { ctx.displayValue(it)?.takeIf { v -> v.isNotEmpty() } })
        }
        val out = mutableListOf<Message>()
        rows.filter { row -> row.key.any { it != null } }      // participant: at least one key field is filled
            // Partial validation: only RELEVANT rows participate in duplicate detection — the kernel builds its
            // duplicate cache from the relevant fields only, so a relevant row whose duplicate PARTNER is not
            // relevant is NOT flagged (IF39, the sibling of the uniqueIndex case). Full: every row (ALL_RELEVANT).
            .filter { row -> pass.isRelevant(errorPath, row.reps) }
            .groupBy { it.reps.take(scopeLen) }                // scope: per reference-group parent row (basic version)
            .forEach { (_, scope) ->
                val dups = scope.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys
                for (row in scope.filter { it.key in dups }) {
                    // RepetitionNotUnique's own polarity: OMISSION when a (fillable) key component is empty, else VALUE.
                    val rnuType = if (row.key.any { it == null }) MsgType.OMISSION else MsgType.VALUE
                    // A combined `<guard> And RepetitionNotUnique(key)`: the guard is a SEPARATE per-row 3VL
                    // conjunct (the duplicate cache above is guard-independent, matching the kernel). The And fires
                    // only if the guard also fires, and its polarity combines by the Cond.And rule (OMISSION if
                    // either operand is OMISSION). A guard that is FALSE/UNKNOWN for this row suppresses it.
                    val type = if (rnu.guard == null) rnuType else {
                        val guardType = pass.interpreterFor(row.ctx, errorPath)
                            .firedType(rnu.guard) ?: continue
                        if (rnuType == MsgType.OMISSION || guardType == MsgType.OMISSION) MsgType.OMISSION else MsgType.VALUE
                    }
                    val text = rule.messages[pass.locale]?.let { renderMessage(it, rule.group, row.ctx) { p -> model.labelOf(p, pass.locale) } } ?: ""
                    val referenced = keys.map { formatPointer(it, row.ctx.repsOf(it)) }
                    val fillToFix = if (type == MsgType.OMISSION) referenced else emptyList()
                    out += Message(rule.errorCode, rule.path, formatPointer(errorPath, row.reps),
                        rule.severity, type, text, referenced, fillToFix)
                }
            }
        return out
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
     * **Reporting granularity — report-ALL, deliberately wider than the kernel's.** This returns an outcome
     * for **every** computed instance; the kernel's `IDocumentComputationResult` reports only the **delta
     * vs the input document** (changed values / clears of input-filled cells; KERNEL-FINDINGS "compute
     * reporting granularity"). Report-all is the more useful library contract ("what does this field
     * compute to?"), and result-envelope granularity is not behavior-parity territory — a comparison
     * against a kernel-granularity expectation projects via
     * [io.github.mbackschat.a12.dm.interpreter.conformance.kernelComputeGranularity].
     */
    fun compute(doc: Document, locale: String = "en_US"): List<Computed> =
        computeInstances(doc)

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
    fun applyComputations(doc: Document, locale: String = "en_US"): Document {
        val overrides = HashMap<Pair<String, List<Int>>, String?>()
        for (computed in computeInstances(doc)) {
            overrides[computed.path to computed.indices] = if (computed.outcome == ComputeOutcome.VALUE) computed.value else null
        }
        return doc.withComputed(overrides)
    }

    /**
     * Every computed-field outcome WITH its target cell's repetition indices ([Computed.indices]) — the core
     * behind both [compute] (which returns it as the public report) and [applyComputations] (which needs the
     * indices to address the right cell). The per-row identity flows straight onto [Computed] (no separate
     * carrier), which the conformance corpus records (CONFORMANCE-CORPUS-SPEC §7).
     *
     * **Cascade** — a computation may reference ANOTHER computation's target field; the kernel computes the
     * dependency first and the dependent reads its computed value (the CalculationCache). We mirror that
     * **side-effect-free**: compute in dependency order, accumulating each result into an [overlay] (the
     * computed values), and evaluate a dependent through an [OverlayContext] that returns those overlaid
     * values for computed-field reads. Without this the dependent read the stored (empty) operand → a wrong
     * value (verified vs the kernel). No doc mutation, so a reused doc never accumulates placements; for a
     * model with no inter-computation dependency the overlay is never consulted (zero behavior change).
     *
     * A computation whose evaluation READS an invalid cell — a formally-invalid input field, an ERRORED
     * computed dependency, or an itself-poisoned one — is POISONED (IF85, the kernel's
     * `CalculationFieldException` → "fehlerhaft berechnet"): no value, reported CLEARED (report-all), and its
     * own dependents' reads poison transitively. Read-driven, so evaluation order/short-circuits decide.
     */
    private fun computeInstances(doc: Document): List<Computed> {
        // A field may be targeted by MULTIPLE computations (a composed base + subtype override — IF87): they run in
        // document order and the FIRST NON-EMPTY outcome (a VALUE or a POISON) wins per cell; an empty/clearing one is
        // a no-op and control falls through to the next (the kernel's `CalculationController.handleBerechnetenWert` —
        // "for empty values, do like no calculation was done"). [outcomeByCell] keeps the winner per target cell
        // (insertion-ordered), [decided] marks the cells a non-empty outcome has already claimed.
        val outcomeByCell = LinkedHashMap<Pair<String, List<Int>>, Computed>()
        val decided = HashSet<Pair<String, List<Int>>>()
        val overlay = HashMap<Pair<String, List<Int>>, Val>()
        // A computed field a PRIOR computation CLEARED — dependents must read it as EMPTY (empty-as-0 / skip), NOT
        // the stale STORED (seeded) value the overlay would otherwise fall through to. Without this a dependent's
        // `FieldFilled(dep)` reads the stale seed as filled and computes where the kernel (seeing dep empty) clears
        // — a large slice of the IF76/IF77 CVV class, AND the root of the IF76 gate's over-suppression (a cleared
        // dependency read stale-and-invalid would wrongly trip the gate). KERNEL-FINDINGS §11 (cascade clearing).
        val cleared = HashSet<Pair<String, List<Int>>>()
        val emptyOf: (String) -> Val = { p -> emptyValue(model.kinds[p] ?: Kind.STRING) }
        // A computed NUMBER is STORED padded to its field's minFractionalDigits (IF82) — so a downstream read of
        // a cascade-overlaid value must see that SAME stored form. Without this an integer-valued computed
        // operand (a constant `10`, a whole-number result) reads back as `10` (0 fractional digits) and a min-2
        // field's poison gate ([computeFormalErrorAt]) wrongly flags it `zahlFalscheAnzahlNachkommastellen` →
        // poisons a downstream computation the kernel computes fine (IF90).
        val minFracOf: (String) -> Int = { p -> model.constraintsOf(p).minFractionalDigits ?: 0 }
        val poisoned = HashSet<Pair<String, List<Int>>>()
        // The compute-invariant config, built once for every instance's Interpreter (computeMode; no
        // CustomCondition / relevance seams on the compute path). Only the per-instance `invalid` poison gate
        // varies (it closes over the instance context), so it stays an Interpreter constructor arg.
        val computeConfig = EvalConfig(clock, model.baseYear, model.indexFields, predefinedTypes,
            customFieldTypes = customFieldTypes, computeMode = true)
        // A FILLED operand that is "not check-relevant" — formally invalid — makes a computation over it
        // non-evaluable (IF76, generalized IF78; poison semantics IF85). The gate is the SAME
        // operand-invalidity test full validation uses ([operandNotCheckRelevant] → [computeFormalErrorAt]),
        // but on the compute path a positive test THROWS: the consult IS the kernel's poisoning read. An EMPTY
        // operand — even a required one — stays empty-as-0 (`nichtAngegeben`, not `nichtPruefungsrelevant`).
        for (comp in computationsInDependencyOrder()) {
            val parsed = parsedOf(comp)
            // A computation iterates where it WRITES (the target's scope), not where it is DECLARED. When neither
            // the operands nor the target has a repeatable ancestor (an all-non-repeatable target), fall back to
            // the TARGET's parent group — NOT `comp.group` (IF89): a computation declared in a separate,
            // uninstantiated container group (a `*_Rules` group) targeting a field in another subtree would
            // otherwise resolve its scope to that empty container and compute nothing, where the kernel computes
            // the single instance at the target (non-repeatable groups are implicitly present).
            val scopeRefs = computationScopeRefs(parsed)
            val scope = scopeFromRefs(scopeRefs + comp.targetPath, parentGroup(comp.targetPath))

            // Evaluate the computation in one instance context and record its outcome (shared by the plain and
            // the parallel iteration arms).
            fun computeAt(ctx: EvalContext) {
                val targetKey = comp.targetPath to ctx.repsOf(comp.targetPath)
                if (targetKey in decided) return   // an earlier computation's non-empty outcome already won (IF87)
                val ectx = if (overlay.isEmpty() && cleared.isEmpty() && poisoned.isEmpty()) ctx
                    else OverlayContext(ctx, overlay, cleared, poisoned, emptyOf, minFracOf)
                val interp = Interpreter(ectx, computeConfig,
                    // The compute gate THROWS (IF85): consulting it on a filled formally-invalid operand IS the
                    // kernel's poisoning read (validation's gate stays Boolean → the 3VL exclusion).
                    invalid = { path -> if (operandNotCheckRelevant(path, ectx)) throw PoisonedReadException(path) else false })
                // A read of a formally-invalid cell — an input field, or a POISONED computed dependency — aborts
                // this instance (IF85, the kernel's CalculationFieldException → "fehlerhaft berechnet"): it skips
                // the remaining alternatives, produces no value (reported CLEARED under the report-all contract;
                // the kernel reports it only when input-filled — kernelComputeGranularity projects), and joins
                // the poison set so ITS dependents' reads poison transitively. The poison is READ-driven: a
                // dependent that never actually reads the poisoned target (a short-circuit skipped it) computes
                // normally — there is no static dependency pre-skip.
                val computed = try {
                    val v = evalComputation(parsed, interp)
                    // ERRORED: poison the target + do NOT overlay — a dependent's READ of it must poison, not read
                    // the bad value. A resolved VALUE overlays (dependents read the computed result) and DECIDES the
                    // cell. An EMPTY (Unknown) is a no-op (IF87): recorded as a tentative clear (dependents read it
                    // EMPTY, IF77) but NOT decided, so a later computation for the SAME cell can still win.
                    toComputed(comp, v, targetKey.second).also { c ->
                        when {
                            c.outcome == ComputeOutcome.ERRORED -> { poisoned += targetKey; cleared -= targetKey; decided += targetKey }
                            v !is Val.Unknown -> { overlay[targetKey] = v; cleared -= targetKey; decided += targetKey }
                            else -> cleared += targetKey
                        }
                    }
                } catch (p: PoisonedReadException) {
                    poisoned += targetKey; cleared -= targetKey; decided += targetKey
                    Computed(comp.targetPath, ComputeOutcome.CLEARED, indices = targetKey.second)
                }
                outcomeByCell[targetKey] = computed   // a decided cell is skipped above → a value/poison overwrites a prior tentative clear
            }

            // PARALLEL ITERATION for computations (IF96): operands referencing OUT-OF-SCOPE sibling repeatable
            // groups join them to the target's scope by the shared INDEX field — the target row for key K reads
            // each sibling's row with the SAME key (join-by-key, never physical position). Two calc-specific
            // rules on top of the rule-side join: (1) an INVALID index in ANY joined group (a duplicated or
            // empty key cell) marks EVERY instance of this computation "incorrectly calculated" — the kernel's
            // CalculationCache.markFieldsUsingInvalidIndexGroupsAsIncorrectlyCalculated, deliberately "more
            // instances than strictly necessary" — poisoned + CLEARED; (2) the calc never CREATES a repetition
            // ("no additional repetitions should be created by calculation"): only join rows where the TARGET's
            // row exists compute — a side-only key is skipped; an unmatched SIDE reads empty (empty-as-0).
            // The join anchors on the TARGET's OWN scope (the calc iterates where it WRITES — IF89): the
            // referenced out-of-scope groups are the joined SIDES, never the iteration anchor ([scope], derived
            // from ALL refs, may resolve to a referenced side — which would collapse every join row onto one
            // target key).
            val targetScope = scopeFromRefs(listOf(comp.targetPath), parentGroup(comp.targetPath))
            val parallelGroups = scopeRefs.flatMap { ancestorGroups(it).filter { g -> g in model.repeatable } }
                .distinct().filter { it != targetScope && !targetScope.startsWith("$it/") }
            if (parallelGroups.isNotEmpty()) {
                val joined = (listOf(targetScope) + parallelGroups).distinct()
                if (joined.any { g -> invalidIndexGroup(doc, g) }) {
                    for (ctx in doc.computeContexts(targetScope)) {
                        val targetKey = comp.targetPath to ctx.repsOf(comp.targetPath)
                        if (targetKey in decided) continue
                        poisoned += targetKey; cleared -= targetKey; decided += targetKey
                        outcomeByCell[targetKey] = Computed(comp.targetPath, ComputeOutcome.CLEARED, indices = targetKey.second)
                    }
                    continue
                }
                for (pctx in parallelIterationContexts(targetScope, parallelGroups, doc)) {
                    val reps = pctx.repsOf(comp.targetPath)
                    if (UNKNOWN_REP in reps) continue   // no TARGET row for this key — the calc creates none
                    computeAt(pctx)
                }
                continue
            }
            for (ctx in doc.computeContexts(scope)) computeAt(ctx)
        }
        return outcomeByCell.values.toList()
    }

    /** Whether [group]'s index is COMPROMISED for the compute parallel join (IF96) — an instantiated row with
     *  an EMPTY index cell, or a DUPLICATED index value. Either marks every instance of a computation joining
     *  over [group] incorrectly calculated (`markFieldsUsingInvalidIndexGroupsAsIncorrectlyCalculated`). */
    private fun invalidIndexGroup(doc: Document, group: String): Boolean {
        val indexField = model.indexFields.firstOrNull { parentGroup(it) == group } ?: return false
        val values = doc.contextsFor(group).map { it.displayValue(indexField) }
        return values.any { it.isNullOrEmpty() } || values.size != values.distinct().size
    }

    /** Whether a FILLED operand is "not check-relevant" for a computation — the SAME test full validation uses
     *  ([computeFormalErrorAt]): the kernel clears a computation over ANY formally-invalid filled operand — every
     *  number formal code (range, fraction-digit, integer-cap, zero-not-allowed, length, leading-zeros, …), a
     *  string length/pattern/enum-domain, a rejected custom type, an over-limit cell — matching both strategies
     *  (differentially confirmed: every formal-error class clears, IF78). An EMPTY operand → false (empty-as-0
     *  applies, even when required — the kernel's `nichtAngegeben`, IF76); duplicate-index is not tracked in
     *  compute (empty [dup]). */
    private fun operandNotCheckRelevant(path: String, ctx: EvalContext): Boolean {
        if (ctx.displayValue(path) == null) return false   // empty → empty-as-0, not cleared
        return checks.computeFormalErrorAt(path, ctx.repsOf(path), ctx, emptySet()) != null
    }

    /**
     * Evaluate a parsed (tiered) computation in one row context, mirroring the kernel's
     * `CodeGenCalculationAlternative` codegen (KERNEL-FINDINGS §2): a present [commonPrecondition] must hold,
     * then the alternatives are tried IN ORDER and the FIRST whose precondition holds supplies the value (a
     * null precondition is unconditional). If the common precondition fails, or no alternative's precondition
     * holds, the field computes nothing ([Val.Unknown] → CLEARED). A precondition "holds" iff it evaluates to
     * three-valued TRUE — an UNKNOWN/FALSE precondition (e.g. over an empty operand) does not select its branch.
     */
    private fun evalComputation(parsed: ParsedComputation, interp: Interpreter): Val {
        if (parsed.commonPrecondition != null && interp.eval(parsed.commonPrecondition) != Tri.TRUE) return Val.Unknown
        for (alt in parsed.alternatives) {
            if (alt.precondition == null || interp.eval(alt.precondition) == Tri.TRUE) return interp.evalExpression(alt.operation)
        }
        return Val.Unknown
    }

    /** One computed-field outcome for [comp] at target-cell [reps] from its evaluated [v]: VALUE (resolved,
     *  empty-as-0 applies) / CLEARED. [reps] rides onto [Computed.indices] for the per-row identity (§7). */
    private fun toComputed(comp: ComputationDef, v: Val, reps: List<Int>): Computed {
        // Render the computed value to the TARGET field's stored form: a Num → canonical decimal; a DATE Txt →
        // the field's date format (IF33; `AddYears(...)` into `dd.MM.yyyy` → `30.06.2026`); a date-RANGE → the
        // field's (format, separator). An Unknown computes nothing → CLEARED.
        val rendered = when (v) {
            // A computed NUMBER stores in the target's form (the kernel's `handleBerechnetenWert` store rule,
            // IF82/IF92): scale-19 pre-round, then — when the natural scale FITS the target's maxFractionalDigits —
            // padded to at least its `minFractionalDigits`, UNCAPPED (10 → "10.00" in a min=2 field; a > 15-digit
            // form stores in full and the formal check below buckets it ERRORED). A value whose natural scale
            // does NOT fit (authorable only via the `errorCodesToSuppress` scale-gate suppression) stores
            // length-bounded to 16 significant digits and is "fehlerhaft berechnet" — ERRORED unconditionally,
            // feeding the same poison set as any other errored computed cell (IF85/IF40).
            is Val.Num -> {
                val stored = storedComputedNumber(v.v, model.constraintsOf(comp.targetPath).minFractionalDigits ?: 0,
                    model.scaleOf(comp.targetPath) ?: Int.MAX_VALUE)
                if (!stored.fits) return Computed(comp.targetPath, ComputeOutcome.ERRORED, stored.rendered, reps)
                stored.rendered
            }
            is Val.Txt -> renderScalarValue(v.v, comp.targetPath)
            is Val.Bool -> v.v.toString()
            is Val.Range -> renderRange(v, comp.targetPath)
            is Val.Unknown -> return Computed(comp.targetPath, ComputeOutcome.CLEARED, indices = reps)
        }
        return Computed(comp.targetPath, computedOutcome(comp.targetPath, rendered), rendered, reps)
    }

    /** A computed value's bucket: ERRORED when it is formally INVALID for the target field (e.g. a number out of
     *  the declared range — the kernel returns the value but flags it `getComputedFieldInstancesWithErrors`, IF34);
     *  else VALUE. The value is returned either way. Runs the TYPE-format check only (the kernel's
     *  `checkFieldValueFormatBasic` — a computed value skips the baseline charset/blank checks a stored INPUT
     *  value gets, so a computed string may legally start/end with a blank). */
    private fun computedOutcome(targetPath: String, rendered: String): ComputeOutcome {
        val kind = model.kinds[targetPath] ?: return ComputeOutcome.VALUE
        val invalid = typeFormatError(kind, rendered, model.scaleOf(targetPath), model.dateLikeFormatOf(targetPath),
            model.datePrecisionOf(targetPath), model.rangeSeparatorOf(targetPath), model.constraintsOf(targetPath),
            model.baseYear ?: 2000) != null
        return if (invalid) ComputeOutcome.ERRORED else ComputeOutcome.VALUE
    }

    /**
     * The model's computations in **dependency order** — a computation whose expression references another
     * computation's target field comes AFTER it (the cascade). A DFS topological sort; a cyclic dependency
     * (which the kernel rejects at codegen) throws. References are taken over ALL field/star paths so a
     * dependency through an aggregate over a computed field is ordered too.
     */
    private fun computationsInDependencyOrder(): List<ComputationDef> {
        // A field can be targeted by MULTIPLE computations (a composed base + subtype override — IF87), so order the
        // TARGET GROUPS by dependency and return ALL computations (no dedup). Within a target the computations keep
        // DOCUMENT order ([groupBy] preserves encounter order), which is the order [computeInstances]'s
        // first-non-empty-wins consumes; across targets, a target is emitted only after every target it reads.
        val byTarget: Map<String, List<ComputationDef>> = model.computations.groupBy { it.targetPath }
        val ordered = mutableListOf<ComputationDef>()
        val done = HashSet<String>()
        val onStack = HashSet<String>()
        fun visit(target: String) {
            if (target in done) return
            if (!onStack.add(target)) {
                throw UnsupportedConstructException("cyclic computation dependency involving $target")
            }
            for (comp in byTarget[target].orEmpty())
                for (ref in computationDepRefs(parsedOf(comp)))
                    if (ref != target && ref in byTarget) visit(ref)
            onStack.remove(target)
            if (done.add(target)) ordered.addAll(byTarget[target].orEmpty())
        }
        model.computations.forEach { visit(it.targetPath) }
        return ordered
    }

    /** Every field/star path an expression reads — for cascade dependency ordering: starred aggregates count,
     *  and a star's `Having` FILTER refs count too (the filter reads the cascade — a `Sum(… Having [flag] ==
     *  True)` over a computed flag must run after the flag's computation, IF98). */
    private fun referencedFieldPaths(expr: Expr): List<String> = when (expr) {
        is Expr.Field -> listOf(expr.path)
        is Expr.Star -> listOf(expr.path) + (expr.having?.let { condDepRefs(it) } ?: emptyList())
        is Expr.Func -> expr.args.flatMap { referencedFieldPaths(it) }
        is Expr.Arith -> referencedFieldPaths(expr.left) + referencedFieldPaths(expr.right)
        is Expr.EnumCategory -> listOf(expr.path)
        is Expr.SemanticIndex -> listOfNotNull(expr.path, expr.indexFieldPath)
        else -> emptyList()
    }

    /** ALL field/star refs of a condition — the cascade-DEPENDENCY view of a precondition (its quantifier/
     *  aggregate stars and their `Having` refs count, unlike the star-free SCOPE view [nonStarredRefs]). */
    private fun condDepRefs(cond: Cond): List<String> = when (cond) {
        is Cond.Cmp -> referencedFieldPaths(cond.left) + referencedFieldPaths(cond.right)
        is Cond.Tolerance -> referencedFieldPaths(cond.left) + referencedFieldPaths(cond.right)
        is Cond.ValueListQuant -> (cond.fields + cond.values).flatMap { referencedFieldPaths(it) }
        is Cond.And -> condDepRefs(cond.left) + condDepRefs(cond.right)
        is Cond.Or -> condDepRefs(cond.left) + condDepRefs(cond.right)
        is Cond.Not -> condDepRefs(cond.c)
        is Cond.Pred -> cond.args.flatMap { referencedFieldPaths(it) }
        is Cond.Pattern -> referencedFieldPaths(cond.value) + referencedFieldPaths(cond.pattern)
        is Cond.Custom -> emptyList()
    }

    /** Render a computed [Val.Range] to the target field's `(format, separator)` (e.g. `01.06.2024-30.06.2024`);
     *  ISO `start/end` fallback when the target declares no range config or an endpoint is malformed. */
    private fun renderRange(v: Val.Range, targetPath: String): String {
        val cfg = model.rangeConfig[targetPath] ?: return "${v.start}/${v.end}"
        val (format, sep) = cfg
        val s = renderDateToFormat(v.start, format)
        val e = renderDateToFormat(v.end, format)
        return if (s != null && e != null) "$s$sep$e" else "${v.start}/${v.end}"
    }

    /** Render a computed scalar to the TARGET field's stored form: a DATE result is reformatted from the
     *  interpreter's ISO to the target's declared date format (the kernel stores a computed date in the field's
     *  format, IF33). STRING and DATE_TIME results pass through (no datetime re-formatter yet). */
    private fun renderScalarValue(iso: String, targetPath: String): String =
        if (model.kinds[targetPath] == Kind.DATE)
            model.dateFormats[targetPath]?.let { renderDateToFormat(iso, it) } ?: iso
        else iso

    private fun evalRule(rule: RuleDef, doc: Document, locale: String): RuleResult {
        // A single-rule pass (fresh formal-status memo, everything relevant) — the testRule/evalCandidate shape.
        val pass = ValidationPass(doc, locale, ALL_RELEVANT, checks.duplicateIndexCells(doc),
            validationConfig(ALL_RELEVANT, null, null), checks)
        val rows = rowOutcomes(rule, pass)
        rows.firstNotNullOfOrNull { it as? RowOutcome.Fired }?.let {
            return RuleResult(rule.path, Verdict.FIRED, it.message.text, it.message.type)
        }
        // No firing: a referenced field being formally invalid means the rule was NOT evaluated (the
        // kernel skips it) → SUPPRESSED, not PASSED. PASSED only when every row was cleanly evaluated.
        val suppressed = rows.filterIsInstance<RowOutcome.Suppressed>().map { it.suppression }
        return if (suppressed.isNotEmpty()) RuleResult(rule.path, Verdict.SUPPRESSED, suppressedBy = suppressed)
        else RuleResult(rule.path, Verdict.PASSED)
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
    private fun validationConfig(isRelevant: Relevance, customRelevant: Set<FieldPointer>?,
            starRelevant: ((String) -> Boolean)?): EvalConfig =
        EvalConfig(clock, model.baseYear, model.indexFields, predefinedTypes, customConditions, customFieldTypes,
            cellRelevant = if (isRelevant === ALL_RELEVANT) null else { path, reps -> isRelevant(path, reps) },
            starRelevant = starRelevant, customRelevant = customRelevant)

    /** The per-row outcome of one rule: fired (with its message), passed, or suppressed (which field/code). */
    private sealed interface RowOutcome {
        data class Fired(val message: Message) : RowOutcome
        data object Passed : RowOutcome
        data class Suppressed(val suppression: Suppression) : RowOutcome
    }

    private fun rowOutcomes(rule: RuleDef, pass: ValidationPass): List<RowOutcome> {
        val ast = condAstOf(rule)
        val refs = nonStarredRefs(ast).distinct()
        val errorPath = resolveErrorPath(rule)
        return iterationContexts(rule, ast, pass.doc).map { ctx ->
            // Partial validation: the kernel runs a rule only if its error-field instance is relevant — else
            // the rule is NOT executed for this row (no message, not a suppression). Full validation: always.
            if (!pass.isRelevant(errorPath, ctx.repsOf(errorPath))) return@map RowOutcome.Passed
            // A formally-invalid referenced field resolves to Val.Unknown (NOT a whole-rule skip): the 3VL
            // propagates it, so a bare comparison goes UNKNOWN but `… Or <true>` still FIRES — matching the
            // kernel's operand-level "not check-relevant" behavior (verified). A duplicate-index field is
            // likewise unknown (branch-scoped, same as a formal error) via [formalErrorAt]. In partial mode a
            // referenced field instance that is NOT relevant is also unknown — the same 3VL yields the kernel's
            // suppression (`And` suppresses, `Or` with a relevant-true operand still fires). The custom-condition
            // registry + the row's error-field pointer ride along for a `CustomCondition X` leaf.
            val interp = pass.interpreterFor(ctx, errorPath)
            // ONE typed walk per row (the fired MsgType, or null if it doesn't fire) — firedType != null ⟺ the
            // rule fires (truth == TRUE), and the value IS the polarity. The walk is LAZY-polarity: it skips the
            // VALUE/OMISSION typing on non-firing nodes (the kernel types only firing rows), measured faster in
            // both regimes. (A kernel-style evalCond-then-evalTyped two-phase guard was measured a net loss — the
            // second walk re-traverses + re-evals leaves; the lazy single walk gets the skip without it.
            // INTERPRETER-PERFORMANCE.md #62 / results log.)
            val firedType = interp.firedType(ast)
            if (firedType != null) {
                val type = firedType
                val text = rule.messages[pass.locale]?.let { renderMessage(it, rule.group, ctx) { p -> model.labelOf(p, pass.locale) } } ?: ""
                val referenced = refs.map { formatPointer(it, ctx.repsOf(it)) }
                // refOmissionErrorResponsible: for an OMISSION error the kernel attaches ALL the operands the
                // omission depends on (verified — both the filled and the empty operand of an And), so filling/
                // changing any could resolve it. Empty for a VALUE error (no fill fixes a value).
                val fillToFix = if (type == MsgType.OMISSION) referenced else emptyList()
                RowOutcome.Fired(Message(rule.errorCode, rule.path, formatPointer(errorPath, ctx.repsOf(errorPath)),
                    rule.severity, type, text, referenced, fillToFix))
            } else {
                // Not fired: a formally-invalid operand means the rule was NOT cleanly evaluated → SUPPRESSED
                // (the `rule eval` third verdict), naming the offending field/code; else a clean PASSED.
                pass.firstSuppressor(refs, ctx)?.let { RowOutcome.Suppressed(it) } ?: RowOutcome.Passed
            }
        }
    }

    /**
     * The contexts a rule is evaluated over. The normal case: one per row of its iteration **scope** — the
     * **deepest repeatable group** that is an ancestor of any *non-aggregated* field reference (a starred ref
     * is an aggregate and collapses the repetition, so it does NOT force iteration); with no repeatable ref, the
     * scope is the rule's declared [RuleDef.group]. This is the kernel law (KERNEL-FINDINGS §9 supplement,
     * `IterationLawsTest`): the iteration scope is the set of *referenced* repeatable fields, NOT the rule's node
     * placement — a rule at `/Order` reaching into `/Order/Items` iterates the Items rows.
     *
     * **Parallel iteration** — when the condition references TWO (or more) NON-nested repeatable groups per-row
     * (siblings, neither an ancestor of the other), the kernel iterates them JOINTLY as an outer join keyed by a
     * shared index field (KERNEL-SEMANTICS §9). [parallelIterationContexts] builds that join; an unsupported
     * parallel shape (non-sibling depth, missing / mismatched index field) fails loud (reported unsupported),
     * never silently wrong.
     */
    private fun iterationContexts(rule: RuleDef, ast: Cond, doc: Document): List<EvalContext> {
        val refs = nonStarredRefs(ast)
        val scope = scopeFromRefs(refs, rule.group)
        val parallelGroups = refs.flatMap { ancestorGroups(it).filter { g -> g in model.repeatable } }
            .distinct().filter { it != scope && !scope.startsWith("$it/") }
        if (parallelGroups.isNotEmpty()) return parallelIterationContexts(scope, parallelGroups, doc)
        val contexts = doc.contextsFor(scope)
        // A rule with NO repeatable non-starred ref, declared in a container without instance content (a
        // real-workspace `*_Rules` root referencing + erroring into OTHER roots), still evaluates ONCE — the
        // kernel's iteration is reference-driven, and the rule fires at its ERROR ENTITY (IF103, the ordinary-
        // rule sibling of IF100's implicit-validation anchor). Re-anchor at the error entity's parent ONLY when
        // the declaring scope yields nothing, so every currently-anchored rule is bit-identical.
        if (contexts.isEmpty() && scope == rule.group) {
            val errorAnchor = parentGroup(resolveErrorPath(rule))
            if (errorAnchor != scope) return doc.contextsFor(errorAnchor)
        }
        return contexts
    }

    /**
     * Build the parallel-iteration outer-join contexts: the joined groups (the [scope] group + the
     * [parallelGroups]) iterated jointly over the union of their shared index field's values (delegated to
     * [Document.parallelContexts]). Each joined group must carry the **same-named** index field. Non-repeatable
     * intermediates between the common ancestor and a joined group are transparent — sibling, wrapped, and
     * asymmetric-depth groups all qualify (the kernel renders the wrappers `[1]`). A *repeatable* non-indexed
     * intermediate above ONE joined group is a **frame**: the kernel iterates it normally and joins inside each
     * frame row (the `/Plan/X/A ⋈ B` case), supported via the `frameGroup` argument. The one **unsupported
     * shape** is a repeatable frame above MORE THAN ONE joined group (an exotic multi-frame cross-product),
     * which throws [UnsupportedConstructException] (→ rule reported unsupported, loud-not-wrong). An **invalid**
     * index (duplicate/empty) is NOT declined — modeled exactly: the compromised group's unmatched references
     * read 3VL UNKNOWN (KERNEL-FINDINGS §9), and the auto checks still emit `uniqueIndex`/`mandatoryField`.
     */
    private fun parallelIterationContexts(scope: String, parallelGroups: List<String>, doc: Document): List<EvalContext> {
        val joined = (listOf(scope) + parallelGroups).distinct()
        val commonParent = commonAncestor(joined)
        val parentDepth = pathParts(commonParent).size
        val indexNames = joined.map { g ->
            val indexField = model.indexFields.firstOrNull { parentGroup(it) == g }
                ?: throw UnsupportedConstructException("parallel iteration group $g has no index field")
            pathParts(indexField).last()
        }.distinct()
        if (indexNames.size != 1) {
            throw UnsupportedConstructException(
                "parallel-iterated groups must share one index field name, found $indexNames")
        }
        // A "framed" joined group sits under a repeatable non-indexed ancestor (strictly between the common
        // ancestor and it) — the kernel iterates that frame normally and joins inside each frame row (the
        // `/Plan/X/A ⋈ B` case). At most ONE joined group may be framed; a frame above two joined groups is an
        // exotic cross-product, not modeled → loud decline.
        fun frameAncestors(g: String) = ancestorGroups(g).filter { pathParts(it).size > parentDepth && it in model.repeatable }
        val framed = joined.filter { frameAncestors(it).isNotEmpty() }
        if (framed.size > 1) {
            throw UnsupportedConstructException(
                "parallel iteration with repeatable frames above multiple joined groups ($framed) is not modeled")
        }
        val frameGroup = framed.firstOrNull()
        val deepestFrame = frameGroup?.let { g -> frameAncestors(g).maxByOrNull { pathParts(it).size } }
        return doc.parallelContexts(joined, indexNames.single(), commonParent, frameGroup, deepestFrame)
    }

    /** The deepest common ancestor group path of [groups] (their longest shared path prefix). */
    private fun commonAncestor(groups: List<String>): String {
        val partsList = groups.map { pathParts(it) }
        var n = 0
        while (n < partsList.minOf { it.size } && partsList.all { it[n] == partsList[0][n] }) n++
        return "/" + partsList[0].take(n).joinToString("/")
    }

    /** The deepest repeatable group ancestor of any [refs] path, else [defaultGroup]. */
    private fun scopeFromRefs(refs: List<String>, defaultGroup: String): String =
        refs.flatMap { ref ->
            // ancestor-or-SELF: a bare GROUP reference (a group-scope quantifier arg — legal un-starred on an
            // indexed repeatable group) is itself an iteration anchor: the kernel iterates the rule PER ROW of
            // the referenced repeatable group and judges each row's cells (the bare-group
            // FieldsNotCollectivelyFilled idiom — kernel-pinned per-row fire set + pointers, IF106). The error
            // entity is NOT an anchor (the kernel-captured corpus cases pin that), only the references are.
            (ancestorGroups(ref) + listOfNotNull(ref.takeIf { model.kinds[ref] == null }))
                .filter { it in model.repeatable }
        }.maxByOrNull { it.count { c -> c == '/' } }
            ?: defaultGroup

    /** Every non-starred field-reference path in the condition (Star refs are aggregates, excluded). */
    private fun nonStarredRefs(cond: Cond): List<String> = when (cond) {
        is Cond.Cmp -> exprRefs(cond.left) + exprRefs(cond.right)
        is Cond.Tolerance -> exprRefs(cond.left) + exprRefs(cond.right)
        is Cond.ValueListQuant -> (cond.fields + cond.values).flatMap { exprRefs(it) }
        is Cond.And -> nonStarredRefs(cond.left) + nonStarredRefs(cond.right)
        is Cond.Or -> nonStarredRefs(cond.left) + nonStarredRefs(cond.right)
        is Cond.Not -> nonStarredRefs(cond.c)
        is Cond.Pred -> cond.args.flatMap { exprRefs(it) }
        is Cond.Pattern -> exprRefs(cond.value) + exprRefs(cond.pattern)
        is Cond.Custom -> emptyList()   // the name references no field in the text — the rule is guarded by a field ref
    }

    private fun exprRefs(expr: Expr): List<String> = when (expr) {
        is Expr.Field -> listOf(expr.path)
        is Expr.Func -> expr.args.flatMap { exprRefs(it) }   // Star args excluded → drop through
        is Expr.Arith -> exprRefs(expr.left) + exprRefs(expr.right)
        is Expr.EnumCategory -> listOf(expr.path)            // `[field -> cat]` references the enum field
        // `[F For keyField]`: the KEY field is a per-row ref (drives iteration + is a referenced field); the
        // target `F` is an absolute semantic lookup (a specific repetition), not a per-row ref. A literal key
        // (`[F For "v"]`) references no field.
        is Expr.SemanticIndex -> expr.indexFieldPath?.let { listOf(it) } ?: emptyList()
        else -> emptyList()
    }

    /** Non-starred refs of a whole tiered computation — its iteration SCOPE input: each operation's non-starred
     *  expr refs plus each precondition's + the common precondition's refs (a starred aggregate is evaluated
     *  WITHIN the scope, so it does not drive it — mirrors the single-operation `exprRefs` use). */
    private fun computationScopeRefs(parsed: ParsedComputation): List<String> =
        condRefsOrEmpty(parsed.commonPrecondition) +
            parsed.alternatives.flatMap { condRefsOrEmpty(it.precondition) + exprRefs(it.operation) }

    /** All field/star refs of a whole tiered computation — its cascade DEPENDENCY input: each operation's full
     *  refs (incl. starred and `Having`-filter refs, so a dependency through an aggregate over a computed field
     *  is ordered) plus each precondition's + the common precondition's FULL refs ([condDepRefs] — a
     *  precondition's quantifier over a computed field is a dependency too). */
    private fun computationDepRefs(parsed: ParsedComputation): List<String> =
        (parsed.commonPrecondition?.let { condDepRefs(it) } ?: emptyList()) +
            parsed.alternatives.flatMap {
                (it.precondition?.let { p -> condDepRefs(p) } ?: emptyList()) + referencedFieldPaths(it.operation)
            }

    private fun condRefsOrEmpty(cond: Cond?): List<String> = cond?.let { nonStarredRefs(it) } ?: emptyList()

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
