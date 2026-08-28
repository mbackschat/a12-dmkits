package io.github.mbackschat.a12.dm.bridge

import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.interpreter.parse.RefResolver
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.AggregateOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef
import io.github.mbackschat.a12.dm.rulekit.dsl.IndexedFieldValue
import io.github.mbackschat.a12.dm.rulekit.dsl.IndexedGroupValue
import io.github.mbackschat.a12.dm.rulekit.dsl.SemanticIndex

/**
 * Condition text to a rulekit [Condition], with no kernel — the interpreter parses the grammar into its own
 * AST and this maps that AST onto rulekit's typed one.
 *
 * The mapping is the validation. rulekit's DSL builders check arity, operand kinds, root-group bans and
 * duplicate operands as they construct, so reaching a [Condition] at all means the rule passed those checks,
 * and a rule that fails them arrives as the builder's own [IllegalArgumentException] carrying the kernel's
 * `MVK_*` code. Nothing here re-implements a check.
 *
 * Three outcomes, and they are not interchangeable: a returned condition, a rejection
 * ([IllegalArgumentException]), and [UnmappedConstructException] for a construct the bridge does not cover —
 * which is an admission of ignorance, not a verdict.
 *
 * This class is only the entry point and the recursion. Each construct family lives behind [Mapping] in its
 * own class, so a family can be widened without touching the others.
 *
 * @param shortNameRefsAllowed the model's `fieldRefByShortNameAllowed`. With it on, a bare reference the
 *     declaring group does not have resolves to a unique model-wide field short name — the kernel's own
 *     fallback, which the parser's default resolver does not implement.
 */
internal class ConditionBridge @JvmOverloads constructor(
    private val boundModel: BoundModel,
    private val modelIndex: ModelIndex,
    private val shortNameRefsAllowed: Boolean = false,
) : Mapping {

    private val operands = Operands(this)
    private val predicates = Predicates(this)
    private val functions = Functions(this)
    private val conditions = Conditions(this)
    private val expressions = Expressions(this)

    private var base: String = ""
    private var declaredPaths: Set<String>? = null
    private var fieldsByShortName: Map<String, List<String>> = emptyMap()

    /**
     * The parser's bare-reference resolver, model-aware: `base/name` when the declaring group has it, else a
     * unique model-wide field short name when the model allows that. Mirrors `ParsedModel`'s own resolver
     * rather than inventing a second rule — an ambiguous short name and a no-match both stay `base/name`, so
     * the reference fails loudly rather than resolving to a plausible wrong field.
     */
    private fun resolveBareReference(scope: String, name: String): String {
        val candidate = "$scope/$name"
        val paths = declaredPaths ?: modelIndex.elementPaths().toList().toSet().also {
            declaredPaths = it
            fieldsByShortName = it.groupBy { path -> path.substringAfterLast('/') }
        }
        if (candidate in paths || !shortNameRefsAllowed) {
            return candidate
        }
        val matches = fieldsByShortName[name].orEmpty()
        return if (matches.size == 1) matches[0] else candidate
    }

    /** Parse [conditionText] against [basePath] and map it. */
    fun translate(conditionText: String, basePath: String, language: ConditionLanguage): Condition {
        base = basePath
        return condition(parse(conditionText, basePath, language, ::resolveBareReference))
    }

    override fun basePath(): String = base

    override fun model(): BoundModel = boundModel

    override fun index(): ModelIndex = modelIndex

    override fun condition(node: Cond): Condition =
        if (node is Cond.Pred) predicates.map(node) else conditions.map(node)

    override fun calculation(node: Expr): Calculation =
        if (node is Expr.Func) functions.map(node) else expressions.map(node)

    override fun fieldRef(node: Expr): FieldRef = operands.fieldRef(node)

    override fun groupRef(node: Expr): GroupRef = operands.groupRef(node)

    override fun groupReference(rawReference: String): GroupRef = operands.groupReference(rawReference)

    override fun fieldOperand(node: Expr): FieldOperand = operands.fieldOperand(node)

    override fun semanticIndex(node: Expr): SemanticIndex = operands.semanticIndex(node)

    override fun indexedValue(node: Expr): IndexedFieldValue = operands.indexedValue(node)

    override fun indexedGroup(node: Expr): IndexedGroupValue = operands.indexedGroup(node)

    override fun typedRef(node: Expr): Any = operands.typedRef(node)

    override fun aggregateOperand(node: Expr): AggregateOperand = operands.aggregateOperand(node)

    private companion object {
        /**
         * The parser raises a bare [IllegalArgumentException], which is the same channel a rulekit refusal
         * arrives on. Naming it here — at the one call site that runs the parser — is what keeps "the text did
         * not parse" from being counted as "rulekit rejected the rule".
         */
        private fun parse(
            conditionText: String,
            basePath: String,
            language: ConditionLanguage,
            resolver: RefResolver,
        ): Cond = try {
            parseCondition(conditionText, basePath, resolver, language)
        } catch (e: IllegalArgumentException) {
            throw ConditionParseException(e.message, e)
        }
    }
}
