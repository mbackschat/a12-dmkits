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

/**
 * Value-independent audit of one prepared interpreter. It forces and traverses every stored rule, every
 * implicit computation validation, and every computation guard/alternative before a document is evaluated.
 * Consumer-provided requirements (custom conditions/types and the clock) are checked here too, so unsupported
 * work cannot hide behind an empty cell, a row gate, or an unselected computation alternative.
 */
internal class PreparedSupportAudit(
    private val model: ModelDef,
    private val parsed: ParsedModel,
    private val clock: EvalClock?,
    private val predefinedTypes: Map<String, PredefinedType>,
    private val customConditions: Map<String, CustomCondition>,
    private val customFieldTypes: Map<String, CustomFieldType>,
) {
    fun audit(): List<UnsupportedRule> = buildList {
        model.customTypes.forEach { (path, declaration) ->
            if (declaration.name !in predefinedTypes && declaration.name !in customFieldTypes) {
                add(UnsupportedRule(path,
                    "unregistered custom field type \"${declaration.name}\" — register it in the predefined-type or custom-field-type registry"))
            }
        }
        model.rules.forEach { auditRule(it, this) }
        model.computations.forEach { auditComputation(it, this) }
        parsed.implicitRules.forEach { auditRule(it, this) }
    }.distinct()

    private fun auditRule(rule: RuleDef, unsupported: MutableList<UnsupportedRule>) {
        capture(rule.path, unsupported) { auditCond(parsed.condAstOf(rule), outerAllowed = false) }
    }

    private fun auditComputation(comp: ComputationDef, unsupported: MutableList<UnsupportedRule>) {
        val path = comp.name?.let { "${comp.group}/$it" } ?: comp.targetPath
        val prepared = try {
            parsed.parsedOf(comp)
        } catch (e: UnsupportedConstructException) {
            unsupported += UnsupportedRule(path, e.message ?: "unsupported construct")
            return
        }
        capture("$path common precondition", unsupported) {
            prepared.commonPrecondition?.let { auditCond(it, outerAllowed = false) }
        }
        prepared.alternatives.forEachIndexed { index, alternative ->
            capture("$path alternative ${index + 1}", unsupported) {
                alternative.precondition?.let { auditCond(it, outerAllowed = false) }
                auditExpr(alternative.operation, outerAllowed = false, starAllowed = false)
            }
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
                cond.args.forEach { auditExpr(it, outerAllowed, starAllowed = true) }
                if ((op == PredicateOp.VALID || op == PredicateOp.INVALID) && cond.args.size == 2) {
                    val typeName = (cond.args[1] as? Expr.StrLit)?.v
                        ?: throw UnsupportedConstructException(
                            "predefined-type validity needs a string type name, got ${cond.args[1]}")
                    if (typeName !in predefinedTypes && typeName !in customFieldTypes) {
                        throw UnsupportedConstructException(
                            "unknown predefined type \"$typeName\" — register it in the predefined-type or custom-field-type registry")
                    }
                }
            }
            is Cond.Pattern -> {
                auditExpr(cond.value, outerAllowed, starAllowed = false)
                auditExpr(cond.pattern, outerAllowed, starAllowed = false)
                try {
                    cond.compiled
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
                cond.fields.forEach { auditExpr(it, outerAllowed, starAllowed = false) }
                cond.values.forEach { auditExpr(it, outerAllowed, starAllowed = false) }
            }
            is Cond.Custom -> if (cond.name !in customConditions) {
                throw UnsupportedConstructException(
                    "unregistered custom condition \"${cond.name}\" — register it in the custom-condition registry")
            }
        }
    }

    private fun auditExpr(expr: Expr, outerAllowed: Boolean, starAllowed: Boolean) {
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
                expr.args.forEach { auditExpr(it, outerAllowed, starAllowed = true) }
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
                ConstantOp.TODAY -> if (clock == null) {
                    throw UnsupportedConstructException(
                        "Today needs an evaluation clock — construct DmInterpreter with an EvalClock")
                }
                ConstantOp.NOW -> if (clock?.now == null) {
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
                val group = expr.path.substringBeforeLast('/')
                if (model.indexFields.none { it.substringBeforeLast('/') == group }) {
                    throw UnsupportedConstructException(
                        "SemanticIndex needs an index field on the group of ${expr.path}")
                }
                expr.indexFieldPath?.let { auditExpr(Expr.Field(it), outerAllowed, starAllowed = false) }
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
    }
}
