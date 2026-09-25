package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.eval.ConstantOp
import io.github.mbackschat.a12.dm.interpreter.eval.FunctionOp
import io.github.mbackschat.a12.dm.interpreter.eval.PredicateOp
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.model.UnsupportedRule
import io.github.mbackschat.a12.dm.interpreter.regex.PatternRegistry

/** One rule's fully prepared, value-independent execution plan. */
internal data class PreparedRulePlan(
    val ast: Cond,
    val iteration: IterationPlan,
    val repetitionNotUnique: RnuPlan?,
    /** The deepest error-containing selector for one exact authored semantic-key target, at a level ordinary
     *  iteration does not bind. An ordinary message reference to the error field vetoes reanchoring; sibling
     *  payload refs do not enter the target set; two distinct authored key expressions fall back to ordinary
     *  iteration even when their runtime values select the same row. */
    val semanticErrorIndex: Expr.SemanticIndex?,
    /** Rich message operands in deterministic AST order; RNU keys are excluded because its duplicate-cluster projector owns
     *  their concrete peer pointers. */
    val messageReferences: List<MessageReference>,
    /** Kernel codegen skips every rule containing a `Having` filter during partial validation. */
    val hasFilterConditions: Boolean,
)

/** Host/customization inputs the value-independent preparation walk must resolve. */
internal data class PreparationSupport(
    val clock: EvalClock?,
    val predefinedTypes: Map<String, PredefinedType>,
    val customConditions: Map<String, CustomCondition>,
    val customFieldTypes: Map<String, CustomFieldType>,
    val patterns: PatternRegistry,
)

/**
 * The authoritative value-independent preparation boundary for one interpreter. Execution consumes the same
 * cached rule/computation plans that [audit] forces, so support eligibility cannot drift behind a second,
 * incomplete semantics walk. Consumer-provided requirements (custom conditions/types and the clock) are checked
 * while each plan is built; iteration and RNU structure are delegated to their actual runtime planners.
 */
internal class PreparedEvaluationPlan(
    private val model: ModelDef,
    private val plans: ModelStaticPlans,
    private val support: PreparationSupport,
) {
    /**
     * The audit VERDICT for one rule under this evaluator's configuration, memoized per evaluator because the
     * verdict is what configuration decides. The plan itself is memoized once per model in [plans].
     *
     * Ordering is load-bearing and preserved from when the two were one step: the audit runs BEFORE the plan is
     * forced, so a rule that both fails its configuration audit and fails to build (more than one
     * `RepetitionNotUnique`) still reports the audit's message, exactly as before.
     */
    private val ruleVerdicts: Map<RuleDef, Lazy<Unit>> =
        (model.rules + plans.parsed.implicitRules).associateWith { rule ->
            lazy { auditCond(plans.condAstOf(rule), outerAllowed = false) }
        }

    private val computationVerdicts: Map<ComputationDef, Lazy<Unit>> =
        model.computations.associateWith { computation ->
            lazy { auditComputationPlan(plans.computation(computation)) }
        }

    /** The prepared plan execution uses for [rule]; ad-hoc candidates are audited and prepared for that call. */
    fun rule(rule: RuleDef): PreparedRulePlan {
        ruleVerdicts[rule]?.value ?: auditCond(plans.condAstOf(rule), outerAllowed = false)
        return plans.rule(rule)
    }

    /** The parsed and support-checked computation execution uses for [computation]. */
    fun computation(computation: ComputationDef): ParsedComputation {
        computationVerdicts.getValue(computation).value
        return plans.computation(computation)
    }

    fun audit(): List<UnsupportedRule> = buildList {
        model.fieldConstraints.forEach { (path, constraints) ->
            constraints.pattern?.let { pattern ->
                capture(path, this) { support.patterns.pattern(pattern) }
            }
        }
        model.customTypes.forEach { (path, declaration) ->
            if (declaration.name !in support.predefinedTypes && declaration.name !in support.customFieldTypes) {
                add(UnsupportedRule(path,
                    "unregistered custom field type \"${declaration.name}\" — register it in the predefined-type or custom-field-type registry"))
            } else {
                support.predefinedTypes[declaration.name]?.pattern?.let { pattern ->
                    capture(path, this) { support.patterns.pattern(pattern) }
                }
            }
        }
        model.rules.forEach { auditRule(it, this) }
        model.computations.forEach { auditComputation(it, this) }
        plans.parsed.implicitRules.forEach { auditRule(it, this) }
    }.distinct()

    private fun auditRule(rule: RuleDef, unsupported: MutableList<UnsupportedRule>) {
        capture(rule.path, unsupported) { rule(rule) }
    }

    private fun auditComputation(comp: ComputationDef, unsupported: MutableList<UnsupportedRule>) {
        val path = comp.name?.let { "${comp.group}/$it" } ?: comp.targetPath
        capture(path, unsupported) { computation(comp) }
    }

    /** The configuration half of computation preparation, over an already-parsed computation. Separate from the
     *  parse so the parse is memoized once per model and only the verdict is memoized per evaluator. */
    private fun auditComputationPlan(computation: ParsedComputation) {
        computation.commonPrecondition?.let { auditCond(it, outerAllowed = false) }
        computation.alternatives.forEach { alternative ->
            alternative.precondition?.let { auditCond(it, outerAllowed = false) }
            auditExpr(alternative.operation, outerAllowed = false, starAllowed = false)
        }
    }

    private inline fun capture(
        path: String,
        unsupported: MutableList<UnsupportedRule>,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: UnsupportedConstructException) {
            unsupported += UnsupportedRule(path, e.message ?: "unsupported construct")
        }
    }

    private fun auditCond(cond: Cond, outerAllowed: Boolean) {
        when (cond) {
            is Cond.Cmp -> {
                auditExpr(cond.left, outerAllowed, starAllowed = false)
                auditExpr(cond.right, outerAllowed, starAllowed = false)
            }
            is Cond.And -> {
                auditCond(cond.left, outerAllowed)
                auditCond(cond.right, outerAllowed)
            }
            is Cond.Or -> {
                auditCond(cond.left, outerAllowed)
                auditCond(cond.right, outerAllowed)
            }
            is Cond.Not -> auditCond(cond.c, outerAllowed)
            is Cond.Pred -> {
                val op = PredicateOp.byName(cond.name)
                    ?: throw UnsupportedConstructException("unsupported predicate ${cond.name}")
                // a keyed group is a group operand wherever a group's presence is asked (IF331), and where a
                // field-fill quantifier reads the cells inside it (IF340)
                val keyedGroupOperator = op.countsGroups ||
                    op == PredicateOp.GROUP_FILLED || op == PredicateOp.GROUP_NOT_FILLED || op in FIELD_FILL_QUANTIFIERS
                cond.args.forEach {
                    auditExpr(it, outerAllowed, starAllowed = true, groupSemanticIndexAllowed = keyedGroupOperator)
                }
                if ((op == PredicateOp.VALID || op == PredicateOp.INVALID) && cond.args.size == 2) {
                    val typeName = (cond.args[1] as? Expr.StrLit)?.v
                        ?: throw UnsupportedConstructException(
                            "predefined-type validity needs a string type name, got ${cond.args[1]}")
                    if (typeName !in support.predefinedTypes && typeName !in support.customFieldTypes) {
                        throw UnsupportedConstructException(
                            "unknown predefined type \"$typeName\" — register it in the predefined-type or custom-field-type registry")
                    }
                    support.predefinedTypes[typeName]?.pattern?.let { support.patterns.pattern(it) }
                }
            }
            is Cond.Pattern -> {
                auditExpr(cond.value, outerAllowed, starAllowed = false)
                auditExpr(cond.pattern, outerAllowed, starAllowed = false)
                try {
                    support.patterns.pattern((cond.pattern as Expr.StrLit).v)
                } catch (e: IllegalArgumentException) {
                    throw UnsupportedConstructException(
                        "unsupported pattern for this runtime: ${e.message ?: "pattern compilation failed"}")
                }
            }
            is Cond.Tolerance -> {
                auditExpr(cond.left, outerAllowed, starAllowed = false)
                auditExpr(cond.right, outerAllowed, starAllowed = false)
            }
            is Cond.ValueListQuant -> {
                // Value-list quantifiers are themselves collection operators: a starred field/value list is a
                // first-class operand, not a bare star outside an aggregate.
                cond.fields.forEach { auditExpr(it, outerAllowed, starAllowed = true) }
                cond.values.forEach { auditExpr(it, outerAllowed, starAllowed = true) }
            }
            is Cond.Custom -> if (cond.name !in support.customConditions) {
                throw UnsupportedConstructException(
                    "unregistered custom condition \"${cond.name}\" — register it in the custom-condition registry")
            }
        }
    }

    private fun auditExpr(
        expr: Expr,
        outerAllowed: Boolean,
        starAllowed: Boolean,
        groupSemanticIndexAllowed: Boolean = false,
    ) {
        when (expr) {
            is Expr.Field -> requireOuterAllowed(expr.outer, outerAllowed)
            is Expr.NumLit, is Expr.StrLit, is Expr.BoolLit -> Unit
            is Expr.Star -> {
                if (!starAllowed) {
                    throw UnsupportedConstructException("a starred path must sit inside an aggregate (Sum/MaxValue/…)")
                }
                requireOuterAllowed(expr.outer, outerAllowed)
                expr.having?.let { auditCond(it, outerAllowed = true) }
            }
            is Expr.Func -> {
                val op = FunctionOp.byName(expr.name)
                    ?: throw UnsupportedConstructException("unsupported function ${expr.name}")
                expr.args.forEachIndexed { index, argument ->
                    // `ValueAsDate(date, FirstDay|LastDay)` represents its closed boundary selector as a Const
                    // AST node, but those two tokens are function parameters—not evaluation-clock constants.
                    if (op == FunctionOp.VALUE_AS_DATE && index == 1 &&
                        argument is Expr.Const && argument.name in VALUE_AS_DATE_BOUNDARIES
                    ) {
                        return@forEachIndexed
                    }
                    auditExpr(
                        argument,
                        outerAllowed,
                        starAllowed = true,
                        groupSemanticIndexAllowed = op == FunctionOp.FIRST_FILLED_VALUE || op in KEYED_GROUP_AGGREGATES,
                    )
                }
                if (op == FunctionOp.SUM_OF_PRODUCTS &&
                    expr.args.any { it is Expr.Star && it.having != null }) {
                    throw UnsupportedConstructException("SumOfProducts does not support a Having filter on its operand")
                }
                if (op == FunctionOp.FIELD_VALUE_AS_STRING) {
                    val indexed = expr.args.singleOrNull() as? Expr.SemanticIndex
                    if (indexed != null && model.kinds[indexed.path] in DATE_LIKE) {
                        throw UnsupportedConstructException(
                            "FieldValueAsString over a date-valued semantic index is not supported")
                    }
                }
            }
            is Expr.Const -> when (ConstantOp.byName(expr.name)) {
                ConstantOp.TODAY -> if (support.clock == null) {
                    throw UnsupportedConstructException(
                        "Today needs an evaluation clock — construct DmInterpreter with an EvalClock")
                }
                ConstantOp.NOW -> if (support.clock?.now == null) {
                    throw UnsupportedConstructException(
                        "Now needs an evaluation clock with a current date-time — construct DmInterpreter with an EvalClock(today, now)")
                }
                ConstantOp.BASE_YEAR -> if (model.baseYear == null) {
                    throw UnsupportedConstructException("BaseYear needs the model's base year (modelInfo.baseYear)")
                }
                null -> throw UnsupportedConstructException("unsupported constant ${expr.name}")
            }
            is Expr.Arith -> {
                auditExpr(expr.left, outerAllowed, starAllowed = false)
                auditExpr(expr.right, outerAllowed, starAllowed = false)
            }
            is Expr.SemanticIndex -> {
                requireOuterAllowed(expr.outer, outerAllowed)
                requireOuterAllowed(expr.indexFieldOuter, outerAllowed)
                val isField = expr.path in model.kinds
                if (!isField && !groupSemanticIndexAllowed) {
                    throw UnsupportedConstructException(
                        "a group semantic index is only an entity of FirstFilledValue, a group-presence condition, " +
                            "a field-fill quantifier, NumberOfFilledFields, Sum, MaxValue or MinValue")
                }
                if (nearestIndexFieldOf(model.indexFields, expr.path) == null) {
                    throw UnsupportedConstructException(
                        "SemanticIndex needs an index field on the group of ${expr.path}")
                }
                expr.indexFieldPath?.let {
                    auditExpr(Expr.Field(it, expr.indexFieldOuter), outerAllowed, starAllowed = false)
                }
            }
            is Expr.EntitySpec -> {
                auditExpr(expr.source, outerAllowed, starAllowed, groupSemanticIndexAllowed)
                val index = expr.semanticIndex
                if (index != null) {
                    val source = expr.source as? Expr.Field
                        ?: throw UnsupportedConstructException("a wildcard entitySpec cannot carry a semantic index")
                    val indexed = when (index) {
                        is Expr.EntityIndex.Literal -> Expr.SemanticIndex(
                            source.path,
                            indexValue = index.value,
                            outer = source.outer,
                        )
                        is Expr.EntityIndex.Field -> Expr.SemanticIndex(
                            source.path,
                            indexFieldPath = index.path,
                            outer = source.outer,
                            indexFieldOuter = index.outer,
                        )
                    }
                    auditExpr(indexed, outerAllowed, starAllowed = false, groupSemanticIndexAllowed)
                }
            }
            is Expr.EnumCategory -> requireOuterAllowed(expr.outer, outerAllowed)
        }
    }

    private fun requireOuterAllowed(outer: Boolean, allowed: Boolean) {
        if (outer && !allowed) {
            throw UnsupportedConstructException(
                "a \$ outer-correlation reference is only valid inside a Having filter")
        }
    }

    private companion object {
        val DATE_LIKE = setOf(Kind.DATE, Kind.TIME, Kind.DATE_RANGE, Kind.DATE_TIME)

        /** The quantifiers that read a keyed group's cells, each measured on `KeyedGroupFieldListDiffTest` (IF340). */
        val FIELD_FILL_QUANTIFIERS = setOf(PredicateOp.ALL_FIELDS_FILLED, PredicateOp.NO_FIELD_FILLED,
            PredicateOp.AT_LEAST_ONE_FIELD_FILLED, PredicateOp.MORE_THAN_ONE_FIELD_FILLED,
            PredicateOp.NOT_ALL_FIELDS_FILLED, PredicateOp.NOT_EXACTLY_ONE_FIELD_FILLED,
            PredicateOp.FIELDS_NOT_COLLECTIVELY_FILLED)

        /**
         * The aggregates that read a keyed group's cells, each measured on `KeyedGroupFieldListDiffTest` (IF340).
         * `NumberOfDifferentValues` and `NumberOfValueInFields` expand through other owners and have no row over
         * an unstarred keyed group, so they stay declined (INTERPRETER-GAPS IG155).
         */
        val KEYED_GROUP_AGGREGATES = setOf(FunctionOp.NUMBER_OF_FILLED_FIELDS, FunctionOp.SUM, FunctionOp.MAX_VALUE,
            FunctionOp.MIN_VALUE)
        val VALUE_AS_DATE_BOUNDARIES = setOf("FirstDay", "LastDay")
    }
}
