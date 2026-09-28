package io.github.mbackschat.a12.dm.bridge

import java.math.BigDecimal
import io.github.mbackschat.a12.dm.core.ValueKind
import io.github.mbackschat.a12.dm.interpreter.Dec
import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.ValueListQuantOp
import io.github.mbackschat.a12.dm.rulekit.dsl.AggregateOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.BaseYear
import io.github.mbackschat.a12.dm.rulekit.dsl.BoolLiteral
import io.github.mbackschat.a12.dm.rulekit.dsl.BoolRef
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.ConfirmRef
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.DateLiteral
import io.github.mbackschat.a12.dm.rulekit.dsl.DateOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRangeIndexedValue
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRangeOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimeExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimeOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumRef
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumValueLiteral
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldValue
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListRef
import io.github.mbackschat.a12.dm.rulekit.dsl.IndexedFieldValue
import io.github.mbackschat.a12.dm.rulekit.dsl.IndexedGroupValue
import io.github.mbackschat.a12.dm.rulekit.dsl.LogicCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.StringExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.StringLiteral
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef
import io.github.mbackschat.a12.dm.rulekit.dsl.TemporalOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.TimeExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.TimeOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.Tolerance
import io.github.mbackschat.a12.dm.rulekit.dsl.ValueListOperand

/**
 * Condition-node shapes that are not predicate calls — the logic connectives, the comparison, and the infix
 * forms (`Pattern`, `Tolerance`, `ValueListQuant`) and `Custom`.
 *
 * `Cond.Not` has no arm here and cannot grow one: the kernel has no negation, so rulekit's `LogicCondition`
 * is `And`/`Or` only and the interpreter's parser never builds the node.
 */
internal class Conditions(private val mapping: Mapping) {

    fun map(node: Cond): Condition = when (node) {
        is Cond.And -> LogicCondition(
            LogicCondition.Op.AND, mapping.condition(node.left), mapping.condition(node.right)
        )
        is Cond.Or -> LogicCondition(
            LogicCondition.Op.OR, mapping.condition(node.left), mapping.condition(node.right)
        )
        is Cond.Cmp -> comparison(node)
        is Cond.Pattern -> pattern(node)
        is Cond.ValueListQuant -> valueListQuant(node)
        is Cond.Tolerance -> tolerance(node)
        is Cond.Custom -> Dsl.customCondition(node.name)
        else -> throw UnmappedConstructException("condition node " + node.javaClass.simpleName)
    }

    // --- Pattern ------------------------------------------------------------------------------

    /**
     * `[field] PatternMatched|PatternViolated "regex"`. The pattern slot of `PatternCondition` is a `String`,
     * so a right side that is anything but a string literal has no representation at all — and with it rulekit
     * loses the chance to report `MVK_MISSING_CONST_IN_PATTERN_COMP`, which its node shape erases rather than
     * checks.
     */
    private fun pattern(node: Cond.Pattern): Condition {
        val regex = node.pattern
        if (regex !is Expr.StrLit) {
            throw UnmappedConstructException(
                "pattern operand " + node.pattern.javaClass.simpleName + " — the node takes a string constant"
            )
        }
        return when (val cell = mapping.aggregateOperand(node.value)) {
            is FieldRef -> {
                val field = stringRef(cell)
                if (node.expectMatch) Dsl.matches(field, regex.v) else Dsl.violates(field, regex.v)
            }
            is IndexedFieldValue ->
                if (node.expectMatch) Dsl.matches(cell, regex.v) else Dsl.violates(cell, regex.v)
            else -> throw UnmappedConstructException("pattern needs a scalar field cell")
        }
    }

    // --- Tolerance ----------------------------------------------------------------------------

    /** `left DiffersWithToleranceRangeN right` — the band is one of the kernel's fixed four. */
    private fun tolerance(node: Cond.Tolerance): Condition =
        numberExpr(mapping.calculation(node.left))
            .differsWithTolerance(band(node.tolerance), numberExpr(mapping.calculation(node.right)))

    // --- Cmp ----------------------------------------------------------------------------------

    /**
     * A binary comparison, routed through the TYPED operand family its two sides belong to.
     *
     * rulekit states most of what the kernel forbids here in its type system rather than in a runtime guard:
     * [DateRangeOperand] publishes only `eq`/`ne` because a date range has no ordering, [StringExpr] likewise,
     * and no operand family accepts a member of another. Building `ComparisonCondition` straight from two
     * untyped [Calculation]s reaches past all of it, so an illegal comparison would convert silently. Picking
     * the operand family from each side's [Calculation.kind] is what puts those signatures back in the path.
     *
     * Where no signature accepts the pair there is nothing to call, and the answer is
     * [UnmappedConstructException] — never a refusal written here, which would be indistinguishable from
     * rulekit's own verdict.
     *
     * **The NUMBER and DATE_RANGE arms answer a kind mismatch with a REJECTION where the others answer with an
     * unmapped construct, and the difference is load-bearing rather than an oversight.** Those two need
     * something from the model that the kind alone does not carry — a number's declared scale, a date range's
     * typed handle — so they ask `BoundModel` for it, and it refuses a field of the wrong kind on the way in.
     * That refusal is rulekit's, so it is a finding. The other families need nothing from the model, so a
     * mismatch there has no rulekit call to make and manufacturing one would fabricate a verdict.
     *
     * Each side is resolved via [comparisonOperand], not a bare [Mapping.calculation]: a quoted literal
     * carries no type of its own, so `[OrderDate] > "12.04.2020"` needs the OTHER side consulted before the
     * literal converts, mirroring `ExprEval.evalCmpOperand`/`producesDate`.
     */
    private fun comparison(cmp: Cond.Cmp): Condition {
        val left = comparisonOperand(cmp.left, cmp.right)
        val right = comparisonOperand(cmp.right, cmp.left)
        return when (left.kind()) {
            ValueKind.NUMBER -> numberComparison(cmp.op, left, right)
            ValueKind.STRING, ValueKind.CUSTOM -> stringComparison(cmp.op, left, right)
            ValueKind.ENUMERATION -> enumComparison(cmp.op, left, right)
            ValueKind.BOOLEAN -> booleanComparison(cmp.op, left, right)
            ValueKind.CONFIRM -> confirmComparison(cmp.op, left, right)
            // The temporal arm is entered on the KIND — every date-like kind routes here — but the operand
            // FAMILY inside it is picked by the declaration, which is the class the kernel's gate reads.
            ValueKind.DATE, ValueKind.DATE_FRAGMENT, ValueKind.TIME, ValueKind.DATE_TIME ->
                temporalComparison(
                    cmp.op, left, right, mapping.temporalClass(left), mapping.temporalClass(right),
                )
            ValueKind.DATE_RANGE -> dateRangeComparison(cmp.op, left, right)
            else -> throw noTypedComparison(cmp.op, left, right)
        }
    }

    /**
     * One side of a comparison, typing a bare quoted literal from its SIBLING rather than itself. The kernel
     * has no `DateLit` AST node — only `StrLit` — and types a literal from the other operand at evaluation
     * time (`ExprEval.evalCmpOperand`/`producesDate`): a quoted literal beside a DATE-kind sibling is a date,
     * in the kernel's `dd.MM.yyyy` (or omitted-year `dd.MM.`) form, which [DateLiteral] carries verbatim.
     * `producesDate` checks the sibling's field/const/function kind but never TIME or DATE_TIME directly, so
     * this mirrors that: only DATE and DATE_FRAGMENT (a `DateFragmentType` field is `Kind.DATE` to the kernel)
     * make a literal a date.
     */
    private fun comparisonOperand(node: Expr, sibling: Expr): Calculation =
        if (node is Expr.StrLit && isDateLike(sibling)) DateLiteral(node.v) else mapping.calculation(node)

    /**
     * Whether [node] resolves to a DATE-kind calculation — the signal that a sibling literal is a date.
     * Deciding this must not raise [node]'s own refusal as the COMPARISON's verdict: a genuine mismatch
     * belongs to the slot that resolves that operand for real, not to the peer that merely consulted its kind.
     */
    private fun isDateLike(node: Expr): Boolean {
        if (node is Expr.StrLit || node is Expr.NumLit || node is Expr.BoolLit) {
            return false
        }
        return try {
            when (mapping.calculation(node).kind()) {
                ValueKind.DATE, ValueKind.DATE_FRAGMENT -> true
                else -> false
            }
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun numberComparison(op: CmpOp, left: Calculation, right: Calculation): Condition {
        val l = numberExpr(left)
        val r = numberExpr(right)
        return when (op) {
            CmpOp.LT -> l.lt(r)
            CmpOp.LE -> l.le(r)
            CmpOp.GT -> l.gt(r)
            CmpOp.GE -> l.ge(r)
            CmpOp.EQ -> l.eq(r)
            CmpOp.NE -> l.ne(r)
        }
    }

    /**
     * Enumerations compare by stored value. Against a constant the model's own handle does it, because
     * [EnumRef] checks the literal against the DECLARED DOMAIN and [EnumExpr] does not — the stronger of the
     * two routes, and the one that raises the domain code. Otherwise the peer must be another enumeration;
     * ordering has no builder in either.
     */
    private fun enumComparison(op: CmpOp, left: Calculation, right: Calculation): Condition {
        if (right is StringLiteral && left is FieldValue) {
            val ref = mapping.model().enumOf(left.field().path().value()).keeping(left) { outer() }
            return when (op) {
                CmpOp.EQ -> ref.`is`(right.value())
                CmpOp.NE -> ref.isNot(right.value())
                else -> throw noTypedComparison(op, left, right)
            }
        }
        // A quoted operand beside a non-field enumeration — a category access, whose comparable domain is the
        // CATEGORY's values, not the underlying field's. The model handle above would check the literal
        // against the wrong domain, so the literal travels as rulekit's own EnumValueLiteral and the domain
        // stays where the model knows it.
        if (right is StringLiteral && left.kind() == ValueKind.ENUMERATION) {
            val value: Calculation = EnumValueLiteral(right.value())
            return when (op) {
                CmpOp.EQ -> EnumExpr(left).`is`(EnumExpr(value))
                CmpOp.NE -> EnumExpr(left).isNot(EnumExpr(value))
                else -> throw noTypedComparison(op, left, right)
            }
        }
        return when (op) {
            CmpOp.EQ -> EnumExpr(left).`is`(enumExpr(op, right, left))
            CmpOp.NE -> EnumExpr(left).isNot(enumExpr(op, right, left))
            else -> throw noTypedComparison(op, left, right)
        }
    }

    /** A boolean compares to `True`/`False`; rulekit publishes no field-vs-field arm. */
    private fun booleanComparison(op: CmpOp, left: Calculation, right: Calculation): Condition {
        if (left !is FieldValue || right !is BoolLiteral) {
            throw noTypedComparison(op, left, right)
        }
        val ref: BoolRef = mapping.model().bool(left.field().path().value()).keeping(left) { outer() }
        return when (op) {
            CmpOp.EQ -> if (right.value()) ref.isTrue else ref.isFalse
            CmpOp.NE -> if (right.value()) ref.isNotTrue else ref.isNotFalse
            else -> throw noTypedComparison(op, left, right)
        }
    }

    /**
     * A confirm compares to `True` only. [ConfirmRef] deliberately publishes no `isFalse`, so `== False` —
     * the kernel's `MVK_INVALID_COMPARE_TO_YES` — has no builder to land on.
     */
    private fun confirmComparison(op: CmpOp, left: Calculation, right: Calculation): Condition {
        if (left !is FieldValue || right !is BoolLiteral || !right.value()) {
            throw noTypedComparison(op, left, right)
        }
        val ref: ConfirmRef = mapping.model().confirm(left.field().path().value()).keeping(left) { outer() }
        return when (op) {
            CmpOp.EQ -> ref.confirmed()
            CmpOp.NE -> ref.notConfirmed()
            else -> throw noTypedComparison(op, left, right)
        }
    }

    /**
     * A date range has no ordering — [DateRangeOperand] publishes equality only.
     *
     * Routed through the model's checked forms rather than the bare `eq`/`ne`: both operands must expose the
     * same declared date-component set, which is a CONTEXTUAL rule and so lives on the model. The ungated
     * pair stays for `:adapter`'s converter, whose obligation is the opposite one — rebuild a kernel-accepted
     * model without refusing it.
     */
    private fun dateRangeComparison(op: CmpOp, left: Calculation, right: Calculation): Condition {
        val model = mapping.model()
        val first = dateRangeOperand(op, left, right)
        val second = dateRangeOperand(op, right, left)
        return when (op) {
            CmpOp.EQ -> model.rangesEqual(first, second)
            CmpOp.NE -> model.rangesNotEqual(first, second)
            else -> throw noTypedComparison(op, left, right)
        }
    }

    // --- typed operands -------------------------------------------------------------------------

    /**
     * A numeric operand. A bare field goes through the model's number handle for its DECLARED SCALE, which is
     * what arms the equality gate — and which is where rulekit refuses a field of another kind. A derived
     * expression carries no model scale, so the gate defers to the kernel for it.
     *
     * `BaseYear` is the one node whose role a caller must name rather than read: it reports DATE as its
     * primary kind and offers the year number through [Dsl.baseYearNumber] alone.
     */
    private fun numberExpr(value: Calculation): NumberExpr {
        if (value is BaseYear) {
            return mapping.model().baseYearNumber()
        }
        if (value is FieldValue) {
            return mapping.model().num(value.field().path().value()).keeping(value) { outer() }.asNumber()
        }
        if (value.kind() != ValueKind.NUMBER) {
            throw UnmappedConstructException("a " + value.kind() + " expression in a numeric slot")
        }
        return NumberExpr(value)
    }

    /**
     * A date-range operand — a field, a keyed cell, or a `DateRange(start, end)` construction, which is itself
     * one. The keyed cell is its own arm of [DateRangeOperand] rather than a computed range, so it has to be
     * rebuilt from the indexed value rather than wrapped.
     */
    private fun dateRangeOperand(op: CmpOp, value: Calculation, other: Calculation): DateRangeOperand {
        if (value is FieldValue) {
            return mapping.model().dateRange(value.field().path().value()).keeping(value) { outer() }
        }
        if (value is IndexedFieldValue) {
            return DateRangeIndexedValue(value)
        }
        if (value is DateRangeOperand) {
            return value
        }
        // Any OTHER DATE_RANGE-kind Calculation — chiefly a bare FirstFilledValue — has no typed
        // date-range operand to become: DateRangeExpr is deliberately not publicly constructible, so that
        // a date-range value can only arise from an owner entity reference or a DateRange(start, end)
        // construction (rulekit's dsl.operators.DateRangeConstructionTest pins that surface). Reaching past
        // it here would fabricate an operand rulekit declines to publish, which is precisely the shape
        // NoTypedOperationException exists to record.
        throw noTypedComparison(op, value, other)
    }

    // --- ValueListQuant -----------------------------------------------------------------------

    /**
     * `AtLeastOne|No|NotAllFieldValue(s)IncludedInValueList(f1, f2 In v1, v2)`. The kernel keeps the two value
     * sides apart — literal constants or field references, never both in one list — and rulekit gives each its
     * own node, so the value side decides which builder family the field side lands on.
     */
    private fun valueListQuant(node: Cond.ValueListQuant): Condition {
        val values = node.values
        if (values.all { it is Expr.StrLit }) {
            return literalValueList(node.op, node.fields, values.map { (it as Expr.StrLit).v })
        }
        if (values.none { it is Expr.StrLit || it is Expr.NumLit || it is Expr.BoolLit }) {
            return entityValueList(node.op, node.fields, values)
        }
        throw UnmappedConstructException(
            "value list mixing constants and references, or a non-string constant, in " + node.op.kernelName
        )
    }

    /**
     * The constant-valued form. rulekit types the field list by KIND — that is how it erases
     * `MVK_VARYING_TYPES_NOT_ALLOWED` — so the kind of the first member picks the builder and the model's
     * typed accessor is what refuses a member of any other kind.
     */
    private fun literalValueList(
        op: ValueListQuantOp,
        fields: List<Expr>,
        values: List<String>,
    ): Condition {
        val operands = fields.map { mapping.aggregateOperand(it) }
        val head = operands.first()
        if (head is GroupListRef) {
            if (operands.size != 1) {
                throw UnmappedConstructException(
                    op.kernelName + " over a group scope beside " + (operands.size - 1) + " further operands"
                )
            }
            return when (op) {
                ValueListQuantOp.AT_LEAST_ONE -> Dsl.atLeastOneFieldValueIncludedInValueList(values, head)
                ValueListQuantOp.NO -> Dsl.noFieldValueIncludedInValueList(values, head)
                ValueListQuantOp.NOT_ALL -> Dsl.notAllFieldValuesIncludedInValueList(values, head)
            }
        }
        if (head is FieldListRef) {
            val lists = operands.map {
                it as? FieldListRef ?: throw UnmappedConstructException(
                    op.kernelName + " mixing a repetition list with " + it.renderedPath()
                )
            }.toTypedArray()
            val rest = lists.copyOfRange(1, lists.size)
            return when (op) {
                ValueListQuantOp.AT_LEAST_ONE ->
                    Dsl.atLeastOneFieldValueIncludedInValueList(values, lists[0], *rest)
                ValueListQuantOp.NO -> Dsl.noFieldValueIncludedInValueList(values, lists[0], *rest)
                ValueListQuantOp.NOT_ALL -> Dsl.notAllFieldValuesIncludedInValueList(values, lists[0], *rest)
            }
        }
        val firstCell = head as? FieldOperand.Cell ?: throw UnmappedConstructException(
            op.kernelName + " needs a scalar field cell"
        )
        return when (firstCell.kind()) {
            ValueKind.STRING -> {
                val cells = operands.map {
                    val cell = it as? FieldOperand.Cell ?: throw UnmappedConstructException(
                        op.kernelName + " mixing a scalar field with " + it.renderedPath()
                    )
                    stringRef(cell.field())
                    cell
                }.toTypedArray()
                val rest = cells.copyOfRange(1, cells.size)
                when (op) {
                    ValueListQuantOp.AT_LEAST_ONE ->
                        Dsl.atLeastOneFieldValueIncludedInValueList(values, cells[0], *rest)
                    ValueListQuantOp.NO -> Dsl.noFieldValueIncludedInValueList(values, cells[0], *rest)
                    ValueListQuantOp.NOT_ALL ->
                        Dsl.notAllFieldValuesIncludedInValueList(values, cells[0], *rest)
                }
            }
            ValueKind.ENUMERATION -> {
                val refs = operands.map { enumRef(fieldRefOperand(op, it)) }.toTypedArray()
                val rest = refs.copyOfRange(1, refs.size)
                when (op) {
                    ValueListQuantOp.AT_LEAST_ONE ->
                        Dsl.atLeastOneFieldValueIncludedInValueList(values, refs[0], *rest)
                    ValueListQuantOp.NO -> Dsl.noFieldValueIncludedInValueList(values, refs[0], *rest)
                    ValueListQuantOp.NOT_ALL ->
                        Dsl.notAllFieldValuesIncludedInValueList(values, refs[0], *rest)
                }
            }
            // A number field list against integer constants is a legal kernel shape with no rulekit builder —
            // the constant-valued entry points exist for string and enum kinds only.
            else -> throw UnmappedConstructException(
                op.kernelName + " over a " + fieldRefOperand(op, head).kind() + " field list"
            )
        }
    }

    /** The entity-valued form — both sides are references, and rulekit checks the pair as one list. */
    private fun entityValueList(op: ValueListQuantOp, fields: List<Expr>, values: List<Expr>): Condition {
        val valueOperands = values.map { valueListOperand(it) }
        val fieldOperands = fields.map { valueListOperand(it) }
        val first = fieldOperands.first()
        val rest = fieldOperands.subList(1, fieldOperands.size).toTypedArray()
        return when (op) {
            ValueListQuantOp.AT_LEAST_ONE ->
                Dsl.atLeastOneFieldValueIncludedInValueList(valueOperands, first, *rest)
            ValueListQuantOp.NO -> Dsl.noFieldValueIncludedInValueList(valueOperands, first, *rest)
            ValueListQuantOp.NOT_ALL ->
                Dsl.notAllFieldValuesIncludedInValueList(valueOperands, first, *rest)
        }
    }

    private fun valueListOperand(node: Expr): ValueListOperand = when (val operand = mapping.aggregateOperand(node)) {
        is FieldOperand -> operand
        is GroupListRef -> operand
        // Not evidenced for this family — ValueListOperand has no arm for it either, so leave it unmapped
        // rather than guess.
        is IndexedGroupValue -> throw UnmappedConstructException(
            "an index-selected group in a value-list quantifier's entity slot, " + operand.renderedPath()
        )
        else -> throw UnmappedConstructException(
            "entity-list operand " + operand.javaClass.simpleName + " in a value-list quantifier's slot"
        )
    }

    // --- typed handles ------------------------------------------------------------------------

    /** The model's string handle for an already-resolved field — it refuses a field of any other kind. */
    private fun stringRef(field: FieldRef): StringRef {
        val ref = mapping.model().str(field.path().value())
        return if (field.correlated()) ref.outer() else ref
    }

    /** The model's enumeration handle, carrying the domain every listed constant is checked against. */
    private fun enumRef(field: FieldRef): EnumRef {
        val ref = mapping.model().enumOf(field.path().value())
        return if (field.correlated()) ref.outer() else ref
    }

    private companion object {

        /**
         * Carry the `$` correlation across a REBUILD. Typing a comparison operand means going from the
         * untyped [FieldValue] back to the model's own handle for the field's DECLARED kind — and that
         * rebuild starts from the path alone, so the marker is gone unless it is re-applied here.
         *
         * <b>Why it needs a named helper rather than an `if` per site.</b> A dropped marker renders exactly
         * like an uncorrelated reference and only changes which ROW the operand reads, so nothing looks
         * wrong: five sites carried it, one sibling had it right, and no unit test could see the difference
         * because the module's own tests compare against hand-authored rulekit types. The catalog parity net
         * caught it by comparing a `Having` filter against the kernel (DEV-API-GAPS `DG44`).
         */
        private fun <T> T.keeping(value: FieldValue, outer: T.() -> T): T =
            if (value.outer()) outer() else this

        private fun band(tolerance: Dec): Tolerance {
            val value = tolerance.v
            return when {
                value.compareTo(BigDecimal.ONE) == 0 -> Tolerance.RANGE_1
                value.compareTo(BigDecimal.valueOf(2)) == 0 -> Tolerance.RANGE_2
                value.compareTo(BigDecimal.valueOf(5)) == 0 -> Tolerance.RANGE_5
                value.compareTo(BigDecimal.TEN) == 0 -> Tolerance.RANGE_10
                else -> throw UnmappedConstructException("tolerance band " + value.toPlainString())
            }
        }

        /** Strings carry no ordering — [StringExpr] publishes equality only. */
        private fun stringComparison(op: CmpOp, left: Calculation, right: Calculation): Condition = when (op) {
            CmpOp.EQ -> StringExpr(left).eq(stringExpr(op, right, left))
            CmpOp.NE -> StringExpr(left).ne(stringExpr(op, right, left))
            else -> throw noTypedComparison(op, left, right)
        }

        /**
         * Dates, times and date-times. Each family compares within itself; date and date-time also compare
         * across, but for ORDERING only — the equality verbs take their own family.
         *
         * [leftClass]/[rightClass] are each side's DECLARED temporal class ([Mapping.temporalClass]), not its
         * kind: a TIME field declared `yyyy-MM-dd` belongs to the DATE family here, which is what makes
         * `[TimeAsDate] == [Date]` — kernel-admitted (KF271) — translatable at all.
         */
        private fun temporalComparison(
            op: CmpOp,
            left: Calculation,
            right: Calculation,
            leftClass: ValueKind,
            rightClass: ValueKind,
        ): Condition {
            val l = temporalOperand(op, left, right, leftClass, rightClass)
            val r = temporalOperand(op, right, left, rightClass, leftClass)
            if (l is DateOperand) {
                if (r is DateOperand) {
                    return when (op) {
                        CmpOp.LT -> l.before(r)
                        CmpOp.LE -> l.onOrBefore(r)
                        CmpOp.GT -> l.after(r)
                        CmpOp.GE -> l.onOrAfter(r)
                        CmpOp.EQ -> l.eq(r)
                        CmpOp.NE -> l.ne(r)
                    }
                }
                if (r is DateTimeOperand) {
                    return orderingAcrossTheTimeline(
                        op, left, right,
                        { l.before(r) }, { l.onOrBefore(r) }, { l.after(r) }, { l.onOrAfter(r) },
                    )
                }
            }
            if (l is DateTimeOperand) {
                if (r is DateTimeOperand) {
                    return when (op) {
                        CmpOp.LT -> l.before(r)
                        CmpOp.LE -> l.onOrBefore(r)
                        CmpOp.GT -> l.after(r)
                        CmpOp.GE -> l.onOrAfter(r)
                        CmpOp.EQ -> l.eq(r)
                        CmpOp.NE -> l.ne(r)
                    }
                }
                if (r is DateOperand) {
                    return orderingAcrossTheTimeline(
                        op, left, right,
                        { l.before(r) }, { l.onOrBefore(r) }, { l.after(r) }, { l.onOrAfter(r) },
                    )
                }
            }
            if (l is TimeOperand && r is TimeOperand) {
                return when (op) {
                    CmpOp.LT -> l.before(r)
                    CmpOp.LE -> l.onOrBefore(r)
                    CmpOp.GT -> l.after(r)
                    CmpOp.GE -> l.onOrAfter(r)
                    CmpOp.EQ -> l.eq(r)
                    CmpOp.NE -> l.ne(r)
                }
            }
            throw noTypedComparison(op, left, right)
        }

        private fun orderingAcrossTheTimeline(
            op: CmpOp,
            left: Calculation,
            right: Calculation,
            before: () -> Condition,
            onOrBefore: () -> Condition,
            after: () -> Condition,
            onOrAfter: () -> Condition,
        ): Condition = when (op) {
            CmpOp.LT -> before()
            CmpOp.LE -> onOrBefore()
            CmpOp.GT -> after()
            CmpOp.GE -> onOrAfter()
            CmpOp.EQ, CmpOp.NE -> throw noTypedComparison(op, left, right)
        }

        private fun stringExpr(op: CmpOp, value: Calculation, other: Calculation): StringExpr {
            if (value.kind() != ValueKind.STRING && value.kind() != ValueKind.CUSTOM) {
                throw noTypedComparison(op, other, value)
            }
            return StringExpr(value)
        }

        private fun enumExpr(op: CmpOp, value: Calculation, other: Calculation): EnumExpr {
            if (value.kind() != ValueKind.ENUMERATION) {
                throw noTypedComparison(op, other, value)
            }
            return EnumExpr(value)
        }

        /**
         * The kernel authors a TIME or DATE_TIME constant the same way it authors a date one — a quoted
         * string, typed by whatever it is compared against. [comparisonOperand]'s sibling typing already
         * retypes a DATE/DATE_FRAGMENT-adjacent literal into a [DateLiteral] before either side reaches here,
         * but it stops short of TIME and DATE_TIME — so a bare [StringLiteral] reaching this switch is a TIME
         * or DATE_TIME constant this gate has to type from `other` itself, via rulekit's existing public entry
         * points ([Dsl.timeValue]/[Dsl.dateTimeValue]) rather than the generic kind switch.
         */
        private fun temporalOperand(
            op: CmpOp,
            value: Calculation,
            other: Calculation,
            valueClass: ValueKind,
            otherClass: ValueKind,
        ): TemporalOperand {
            if (value is StringLiteral) {
                return when (otherClass) {
                    ValueKind.TIME -> Dsl.timeValue(value.value())
                    ValueKind.DATE_TIME -> Dsl.dateTimeValue(value.value())
                    else -> throw noTypedComparison(op, value, other)
                }
            }
            return when (valueClass) {
                ValueKind.DATE, ValueKind.DATE_FRAGMENT -> DateExpr(value)
                ValueKind.TIME -> TimeExpr(value)
                ValueKind.DATE_TIME -> DateTimeExpr(value)
                else -> throw noTypedComparison(op, value, other)
            }
        }

        private fun fieldRefOperand(op: ValueListQuantOp, operand: AggregateOperand): FieldRef =
            operand as? FieldRef ?: throw UnmappedConstructException(
                op.kernelName + " mixing a plain field with " + operand.renderedPath()
            )

        /**
         * Not a gap in this mapper: rulekit states which comparisons exist by publishing them per operand
         * family, so a pair with no method is a pair the kernel forbids — a date range has no ordering because
         * ordering one is illegal. The rule is real and unreachable at the same time, which is why it gets its
         * own type rather than being folded into either neighbour.
         */
        private fun noTypedComparison(
            op: CmpOp,
            left: Calculation,
            right: Calculation,
        ): NoTypedOperationException = NoTypedOperationException(
            "no typed rulekit comparison for " + left.kind() + " " + op + " " + right.kind()
        )
    }
}
