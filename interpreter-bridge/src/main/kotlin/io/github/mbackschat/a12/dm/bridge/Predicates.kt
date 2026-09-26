package io.github.mbackschat.a12.dm.bridge

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.rulekit.dsl.AggregateOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.CustomTypeValidity
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.EntityListPredicate
import io.github.mbackschat.a12.dm.rulekit.dsl.EnumRef
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupQuantifier
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupRef
import io.github.mbackschat.a12.dm.rulekit.dsl.IndexedGroupValue
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef
import io.github.mbackschat.a12.dm.rulekit.dsl.Quantifier
import io.github.mbackschat.a12.dm.rulekit.dsl.RepetitionNotUniqueCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.StringRef
import java.util.Optional

/**
 * Boolean-valued predicate functions — `Cond.Pred`, the interpreter's shape for `FieldFilled(x)`,
 * `AllFieldsFilled(a, b)` and their kin.
 *
 * The name reaching [map] is the catalog's canonical English keyword whatever language the condition was
 * authored in; the interpreter's parser canonicalises before the AST exists.
 */
internal class Predicates(private val mapping: Mapping) {

    fun map(pred: Cond.Pred): Condition {
        val args = pred.args
        return when (val name = pred.name) {
            // Dsl.fieldFilled takes a FieldRef, which a semantic index is not; the index carries its own
            // presence handle instead.
            "FieldFilled" -> filled(soleFilledOperand(args, name), true)
            "FieldNotFilled" -> filled(soleFilledOperand(args, name), false)
            // The `$` marker is part of the group-presence SHAPE, not a field-only spelling: the kernel admits
            // `GroupFilled($G)` in a Having filter and `GroupPresenceCondition` can carry it since 2026-09-04
            // (API-FINDINGS 135), so this reconstructs it rather than refusing a legal model. A slot with no
            // place for a marker refuses it — see Operands.requireNotCorrelated for which and why.
            "GroupFilled" -> groupPresence(single(args, name), name, filled = true)
            "GroupNotFilled" -> groupPresence(single(args, name), name, filled = false)
            // The same node Dsl.fieldList builds, reached directly: the Dsl overloads for these three are
            // field-only OR group-only, while the catalog gives all of them a field-list-or-groups operand,
            // so no overload expresses the mixed list the kernel accepts.
            "AllFieldsFilled" -> EntityListPredicate(Quantifier.ALL_FIELDS_FILLED, entityList(args))
            "NoFieldFilled" -> EntityListPredicate(Quantifier.NO_FIELD_FILLED, entityList(args))
            "AtLeastOneFieldFilled" ->
                EntityListPredicate(Quantifier.AT_LEAST_ONE_FIELD_FILLED, entityList(args))
            "NotAllFieldsFilled" -> EntityListPredicate(Quantifier.NOT_ALL_FIELDS_FILLED, entityList(args))
            "NotExactlyOneFieldFilled" ->
                EntityListPredicate(Quantifier.NOT_EXACTLY_ONE_FIELD_FILLED, entityList(args))
            "MoreThanOneFieldFilled" ->
                EntityListPredicate(Quantifier.MORE_THAN_ONE_FIELD_FILLED, entityList(args))
            "FieldsNotCollectivelyFilled" ->
                EntityListPredicate(Quantifier.FIELDS_NOT_COLLECTIVELY_FILLED, entityList(args))
            "FieldValuesNotUnique" -> EntityListPredicate(Quantifier.FIELD_VALUES_NOT_UNIQUE, entityList(args))
            "AtLeastOneGroupFilled" -> groupList(GroupQuantifier.AT_LEAST_ONE_GROUP_FILLED, args, name)
            "NoGroupFilled" -> groupList(GroupQuantifier.NO_GROUP_FILLED, args, name)
            "GroupsNotCollectivelyFilled" ->
                groupList(GroupQuantifier.GROUPS_NOT_COLLECTIVELY_FILLED, args, name)
            "NotAllGroupsFilled" -> groupList(GroupQuantifier.NOT_ALL_GROUPS_FILLED, args, name)
            "AllGroupsFilled" -> groupList(GroupQuantifier.ALL_GROUPS_FILLED, args, name)
            "RepetitionNotUnique" -> repetitionNotUnique(args)
            "Invalid" -> customTypeValidity(args, false, "Invalid")
            "Valid" -> customTypeValidity(args, true, "Valid")
            "DateRangesOverlap" -> dateRangesOverlap(args, name)
            "AtLeastOneDateRangeOverlaps" -> atLeastOneDateRangeOverlaps(args, name)
            "FieldValueIncludedInValueList" -> valueList(args, true, name)
            "FieldValueNotIncludedInValueList" -> valueList(args, false, name)
            else -> throw UnmappedConstructException("predicate " + pred.name)
        }
    }

    /**
     * `RepetitionNotUnique(key1, key2 @From Scope)`. The keys are plain fields by the [FieldRef] signature, so
     * a group, a star and a category projection are all unrepresentable — a category arrives as
     * `Expr.EnumCategory` and leaves as an unmapped construct rather than a verdict. The `@From` scope's
     * repeatability is [RepetitionNotUniqueCondition.from]'s check.
     */
    private fun repetitionNotUnique(args: List<Expr>): Condition {
        var keyArgs = args
        var scope: String? = null
        val last = args.lastOrNull()
        if (last is Expr.StrLit && last.v.startsWith(FROM_MARKER)) {
            scope = last.v.substring(FROM_MARKER.length)
            keyArgs = args.subList(0, args.size - 1)
        }
        val keys = keyArgs.map { mapping.fieldRef(it) }
        val condition = RepetitionNotUniqueCondition(keys, Optional.empty())
        return if (scope == null) condition else condition.from(mapping.groupReference(scope))
    }

    /**
     * A group-list quantifier over the kernel's common entity list. Despite the name the list admits a checked
     * field beside a group scope, which is exactly [GroupListOperand]; a flattened repetition list is not one
     * of its arms, so a starred FIELD operand is unmapped rather than rejected.
     */
    private fun groupList(quantifier: GroupQuantifier, args: List<Expr>, operator: String): Condition {
        val entities = groupListOperands(args, operator)
        val rest = entities.copyOfRange(1, entities.size)
        return when (quantifier) {
            GroupQuantifier.AT_LEAST_ONE_GROUP_FILLED -> Dsl.atLeastOneGroupFilled(entities[0], *rest)
            GroupQuantifier.NO_GROUP_FILLED -> Dsl.noGroupFilled(entities[0], *rest)
            GroupQuantifier.GROUPS_NOT_COLLECTIVELY_FILLED -> Dsl.groupsNotCollectivelyFilled(entities[0], *rest)
            GroupQuantifier.NOT_ALL_GROUPS_FILLED -> Dsl.notAllGroupsFilled(entities[0], *rest)
            GroupQuantifier.ALL_GROUPS_FILLED -> Dsl.allGroupsFilled(entities[0], *rest)
            else -> throw UnmappedConstructException("group-list quantifier $operator")
        }
    }

    /**
     * `Invalid(field, "TypeName")` / `Valid(field, "TypeName")` — the custom-field-type overload
     * ([CustomTypeValidity], catalog id `InvalidType`). The catalog keywords are overloaded: a single
     * `Date(...)` operand is the distinct date-construct condition (`DateValidity`), mapped by [dateValidity].
     */
    private fun customTypeValidity(args: List<Expr>, valid: Boolean, operator: String): Condition {
        if (args.size == 1) {
            return dateValidity(args[0], valid, operator)
        }
        val typeName = args.getOrNull(1)
        if (args.size != 2 || typeName !is Expr.StrLit) {
            throw UnmappedConstructException(
                operator + " with " + args.size + " operands — only the custom-field-type overload " +
                    operator + "(field, \"TypeName\") or the date-construct overload " + operator +
                    "(Date(...)) is mapped"
            )
        }
        val field = mapping.fieldRef(args[0])
        return if (valid) Dsl.validType(field, typeName.v) else Dsl.invalidType(field, typeName.v)
    }

    /**
     * `Invalid(Datum(...))` / `Valid(Datum(...))` — the date-construct overload (`DateValidity`,
     * `Dsl.invalid`/`Dsl.valid`), distinct from the custom-field-type overload the same keyword also names.
     * The catalog's own constraint is narrower than "any date value" — "the operand is a Date(...)
     * construction" — so only a literal `Date(...)` call takes this path: a bare non-Date operand here is a
     * PARSE-time refusal upstream (the kernel's own grammar never produces this shape for `Invalid`/`Valid`),
     * so that stays unmapped rather than guessed at.
     */
    private fun dateValidity(operand: Expr, valid: Boolean, operator: String): Condition {
        if (operand !is Expr.Func || operand.name != "Date") {
            throw UnmappedConstructException(
                operator + " with a single non-Date(...) operand — only " + operator + "(Date(...)) is mapped"
            )
        }
        val dateConstruct = DateExpr(mapping.calculation(operand))
        return if (valid) Dsl.valid(dateConstruct) else Dsl.invalid(dateConstruct)
    }

    /**
     * `DateRangesOverlap(r1, r2, …)` — any pair among the listed date-range fields overlaps. Each operand is a
     * plain field or a flattened repetition list, exactly [Mapping.fieldOperand].
     */
    private fun dateRangesOverlap(args: List<Expr>, operator: String): Condition {
        val fields = fieldOperands(args, operator)
        return Dsl.dateRangesOverlap(fields[0], *fields.copyOfRange(1, fields.size))
    }

    /**
     * `AtLeastOneDateRangeOverlaps(range In r1, r2, …)` — the scalar `range` overlaps at least one field in
     * the list. The parser folds the `In` boundary away, so `args` arrives as one flat list with the scalar
     * range first.
     */
    private fun atLeastOneDateRangeOverlaps(args: List<Expr>, operator: String): Condition {
        val fields = fieldOperands(args, operator)
        if (fields.size < 2) {
            throw UnmappedConstructException("$operator needs a scalar range and at least one listed range")
        }
        return Dsl.atLeastOneDateRangeOverlaps(fields[0], fields[1], *fields.copyOfRange(2, fields.size))
    }

    private fun fieldOperands(args: List<Expr>, operator: String): Array<FieldOperand> {
        if (args.isEmpty()) {
            throw UnmappedConstructException("$operator with no operands")
        }
        return Array(args.size) { mapping.fieldOperand(args[it]) }
    }

    /**
     * `FieldValueIncludedInValueList(field, v1, v2, …)` / `FieldValueNotIncludedInValueList(…)` — membership
     * of a single field's value in a literal or field-reference list (`ValueListCondition`). The field narrows
     * to the typed ref its kind admits ([Mapping.typedRef]); the value side then decides which builder
     * overload applies — same split rulekit draws for the plural quantifiers: literal constants or field
     * references, never both, and (per the catalog) a string/enum field takes string literals while a number
     * field takes INTEGER literals only.
     */
    private fun valueList(args: List<Expr>, included: Boolean, operator: String): Condition {
        if (args.size < 2) {
            throw UnmappedConstructException("$operator needs a field and at least one value")
        }
        val ref = mapping.typedRef(args[0])
        val values = args.subList(1, args.size)
        if (values.all { it is Expr.StrLit }) {
            return stringValueList(ref, values, included, operator)
        }
        if (values.all { it is Expr.NumLit }) {
            return numberValueList(ref, values, included, operator)
        }
        if (values.none { it is Expr.StrLit || it is Expr.NumLit || it is Expr.BoolLit }) {
            return referenceValueList(ref, values, included, operator)
        }
        throw UnmappedConstructException(
            "$operator mixing constant kinds, or a boolean constant, in its value list"
        )
    }

    /** The string-literal form — legal against a STRING or ENUMERATION field (the latter domain-checked). */
    private fun stringValueList(
        ref: Any,
        values: List<Expr>,
        included: Boolean,
        operator: String,
    ): Condition {
        val literals = values.map { (it as Expr.StrLit).v }.toTypedArray()
        val rest = literals.copyOfRange(1, literals.size)
        return when (ref) {
            is StringRef -> if (included) ref.`in`(literals[0], *rest) else ref.notIn(literals[0], *rest)
            is EnumRef -> if (included) ref.`in`(literals[0], *rest) else ref.notIn(literals[0], *rest)
            else -> throw UnmappedConstructException(
                "$operator with a string-literal value list against a field with no matching value-list builder"
            )
        }
    }

    /**
     * The number-literal form — legal against a NUMBER field only. A non-integral literal is a shape this
     * bridge does not handle (the kernel's own grammar takes integers only here), not a rulekit verdict, so it
     * surfaces as [UnmappedConstructException] rather than the `ArithmeticException` `longValueExact` would
     * otherwise throw.
     */
    private fun numberValueList(
        ref: Any,
        values: List<Expr>,
        included: Boolean,
        operator: String,
    ): Condition {
        if (ref !is NumberRef) {
            throw UnmappedConstructException(
                "$operator with a numeric-literal value list against a field with no matching value-list builder"
            )
        }
        val literals = LongArray(values.size) { i ->
            val value = (values[i] as Expr.NumLit).v.v
            try {
                value.longValueExact()
            } catch (e: ArithmeticException) {
                throw UnmappedConstructException(
                    "$operator with the non-integer value-list literal " + value.toPlainString() +
                        " — the kernel's value-list numbers are integer literals only"
                )
            }
        }
        val rest = literals.copyOfRange(1, literals.size)
        return if (included) ref.`in`(literals[0], *rest) else ref.notIn(literals[0], *rest)
    }

    /**
     * The field-reference form (DEV-API-GAPS DG10) — every entry must narrow to the SAME typed-ref family as
     * the subject field; rulekit publishes no cross-kind or enum field-entry overload.
     */
    private fun referenceValueList(
        ref: Any,
        values: List<Expr>,
        included: Boolean,
        operator: String,
    ): Condition = when (ref) {
        is StringRef -> {
            val refs = values.map { asStringRef(mapping.typedRef(it), operator) }.toTypedArray()
            val rest = refs.copyOfRange(1, refs.size)
            if (included) ref.`in`(refs[0], *rest) else ref.notIn(refs[0], *rest)
        }
        is NumberRef -> {
            val refs = values.map { asNumberRef(mapping.typedRef(it), operator) }.toTypedArray()
            val rest = refs.copyOfRange(1, refs.size)
            if (included) ref.`in`(refs[0], *rest) else ref.notIn(refs[0], *rest)
        }
        else -> throw UnmappedConstructException(
            "$operator with a field-reference value list against a field with no matching value-list builder" +
                " (an enumeration field admits no field-entry overload)"
        )
    }

    private fun groupListOperands(args: List<Expr>, operator: String): Array<GroupListOperand> {
        if (args.isEmpty()) {
            throw UnmappedConstructException("$operator with no operands")
        }
        return Array(args.size) { i ->
            val operand: AggregateOperand = mapping.aggregateOperand(args[i])
            operand as? GroupListOperand ?: throw UnmappedConstructException(
                operator + " operand " + operand.renderedPath() +
                    " is a flattened repetition list; the entity list takes a field or a group scope"
            )
        }
    }

    /** The entity list of a field-list quantifier — a field, a repetition list, or a group scope. */
    private fun entityList(args: List<Expr>): List<AggregateOperand> = args.map { mapping.aggregateOperand(it) }

    private fun filled(node: Expr, expectFilled: Boolean): Condition {
        if (node is Expr.SemanticIndex) {
            val index = mapping.semanticIndex(node)
            return if (expectFilled) index.filled() else index.notFilled()
        }
        // The FieldOperand overload, not fieldRef/FieldRef: a starred field arrives as a FieldListRef, and
        // Dsl.fieldFilled/fieldNotFilled reject that in kind (MVK_NO_WILDCARDS_ALLOWED) rather than the
        // bridge shrugging with no typed operation to call.
        val field = mapping.fieldOperand(node)
        return if (expectFilled) Dsl.fieldFilled(field) else Dsl.fieldNotFilled(field)
    }

    /**
     * A group-presence leaf, carrying the `$` marker when the source has it.
     *
     * <p>The marker is read off the AST rather than routed through `Mapping.groupRef`, which refuses a
     * correlated source on purpose: the other group slots (`CurrentRepetition`, the aggregate group scopes,
     * the group lists) have no representation for it, so refusing there is the honest answer while
     * reconstructing here is.
     */
    private fun groupPresence(node: Expr, operator: String, filled: Boolean): Condition {
        // An index-selected row has a presence node of its own.
        if (node is Expr.SemanticIndex && mapping.isGroup(node.path)) {
            val row = mapping.aggregateOperand(node) as IndexedGroupValue
            return if (filled) row.filled() else row.notFilled()
        }
        // Only the plain reference form carries a marker we can reconstruct. Anything else — a starred
        // source, a semantic index — goes through `groupRef` unchanged and is refused there if marked,
        // which is the honest answer for a slot with no representation for it.
        val marked = node is Expr.Field && node.outer
        val group = mapping.groupRef(if (marked) Expr.Field((node as Expr.Field).path) else node)
        return if (filled) Dsl.groupFilled(group, marked) else Dsl.groupNotFilled(group, marked)
    }

    private companion object {
        /**
         * The parser's marker for `RepetitionNotUnique`'s optional `@From` scope — a trailing `StrLit`
         * carrying the reference group raw, so it rides the ordinary argument list.
         */
        private const val FROM_MARKER = "@From:"

        private fun asStringRef(ref: Any, operator: String): StringRef = ref as? StringRef
            ?: throw UnmappedConstructException("$operator mixing field kinds across its value-list entries")

        private fun asNumberRef(ref: Any, operator: String): NumberRef = ref as? NumberRef
            ?: throw UnmappedConstructException("$operator mixing field kinds across its value-list entries")

        private fun single(args: List<Expr>, operator: String): Expr {
            if (args.size != 1) {
                throw UnmappedConstructException(operator + " with " + args.size + " operands")
            }
            return args[0]
        }

        /**
         * `FieldFilled`/`FieldNotFilled` admit exactly one operand: `Dsl.fieldFilled`/`fieldNotFilled` take a
         * lone [FieldRef] (or the index carries its own handle), with no N-ary overload to call instead. A
         * multi-operand call is not a gap in this bridge — the kernel's own grammar rejects it too ("genau ein
         * Parameter"), and rulekit states that the same way, by publishing no operation for it.
         */
        private fun soleFilledOperand(args: List<Expr>, operator: String): Expr {
            if (args.size != 1) {
                throw NoTypedOperationException(
                    operator + " with " + args.size + " operands — Dsl.fieldFilled/fieldNotFilled take a" +
                        " single field, with no multi-operand form"
                )
            }
            return args[0]
        }
    }
}
