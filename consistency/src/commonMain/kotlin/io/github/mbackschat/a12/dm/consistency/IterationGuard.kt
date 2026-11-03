package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.DATE_EXTRACT_OPERATIONS
import io.github.mbackschat.a12.dm.consistency.syntax.OperatorId
import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_IDENTITIES
import io.github.mbackschat.a12.dm.consistency.syntax.CallShape
import io.github.mbackschat.a12.dm.consistency.syntax.ComparisonShape
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionNode
import io.github.mbackschat.a12.dm.consistency.syntax.ConnectiveKind
import io.github.mbackschat.a12.dm.consistency.syntax.OperationShape
import io.github.mbackschat.a12.dm.consistency.syntax.PATTERN_COMPARISONS
import io.github.mbackschat.a12.dm.consistency.syntax.RecognizedEntity
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxSite
import io.github.mbackschat.a12.dm.consistency.syntax.TOLERANCE_COMPARISONS
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.isRuleContext
import io.github.mbackschat.a12.dm.consistency.syntax.spanning
import kotlin.math.floor

/**
 * The iteration guard: A12 refuses an iterating rule whose condition can be true for a repetition nobody
 * filled. `MVK_PARALLEL_ITERATION_INVALID` answers it across parallel groups and `MVK_NEG_CONDITION_IN_ITERATION`
 * on the rule's own iteration, in that order and after every other whole-rule check
 * ([KERNEL-FINDINGS KF537], [KF595], [KF596], [KF601]; `adapter.laws.CheckerIterationGuardAgreementTest`,
 * `…CheckerIterationK04AgreementTest`).
 *
 * **What it is built on.** The connective tree the recognizer keeps ([ConditionNode]); the starred levels and
 * resolution every family reads; the parallel marking [markedLevelsOf] states for the filtered-entity family, so
 * A12's two passes ([KERNEL-FINDINGS KF376]) are stated once; and [fieldsUnder], A12's expansion of a group into
 * its fields. What it adds is the verdict walk, which answers true, false or null. Null anywhere declines the
 * whole declaration, because a guess that refused would be an over-rejection.
 *
 * **How a condition is judged.** Every part starts admitted, and a part refuses by its kind and by how its
 * references iterate the judged level: a negative presence wherever it is judged, a positive presence that
 * counts references not iterating it (a starred one counting as often as its level repeats, a group as its
 * fields), a count compared below a positive constant or an amount compared equal to zero over references that
 * all iterate, an aggregate or a product sum against a constant over mixed references, any `CurrentRepetition`
 * outside a filter, the date form of `Invalid` and the value-list forms. Kernel 31.1.1 dropped the own-iteration
 * precondition from the negative presences and from `CurrentRepetition`, and stopped exempting `SumOfProducts`
 * from the mixed-reference arm ([KERNEL-FINDINGS KF656]). A tolerance comparison takes part where `!=` does; a pattern,
 * `RepetitionNotUnique` and the field-and-type `Valid`/`Invalid` have no arm. An `And` is admitted when one of
 * its parts is admitted and iterates the level, an `Or` only when every part is ([KERNEL-FINDINGS KF537]). A
 * selection keyed by a field is one reference that iterates wherever its key does ([KF601]); a `$`-marked
 * reference adds the levels it iterates and iterates nothing itself.
 *
 * **Which levels.** Parallel iteration is judged as one whole. The rule's own iteration is judged level by
 * level, shallowest first, and the first refused level is the one named; a level repeating exactly twice that
 * declares an index field is never judged. A group error target is judged as a field one is, at the levels its
 * references iterate ([KF601]). A computation is judged through the condition A12 joins it into
 * ([computationCondition]), whose leading `FieldFilled` of the computed field is what guards it.
 *
 * **Silent wherever A12 answers before this law, and then the declaration carries a coverage gap
 * ([iterationGuardGaps])**: every `null` this file returns is a shape whose earlier class the differential pins
 * beside the checker's own (`adapter.laws.CheckerIterationGuardAgreementTest`, [KF608]).
 */
internal fun TrackedFactBase.iterationGuardContributions(element: TrackedDeclaration): List<ConditionFinding> {
    val execution = conditionExecution(element) ?: return emptyList()
    val verdict = guardVerdictOf(element, execution) ?: return emptyList()
    return execution.postParse(verdict.site) { listOfNotNull(verdict.finding) }
}

/**
 * A rule that iterates and on which this reading declined: whether its condition can be true for a repetition
 * nobody filled was left to A12, so the report says so at the rule rather than reading as examined.
 */
internal fun TrackedFactBase.iterationGuardGaps(element: TrackedDeclaration): List<CoverageGap> {
    val execution = conditionExecution(element)
    if (execution == null || element.declaredKind !in JUDGED || execution.recognition.failure != null ||
        guardVerdictOf(element, execution) != null
    ) return emptyList()
    // An unresolved reference is answered earlier by its own class, so only a rule known to iterate is named.
    if (rulesOwnIterationLevels(element)?.values?.any { it.isNotEmpty() } != true) return emptyList()
    return listOf(CoverageGap(element.path,
        "this ${if (element.declaredKind == DeclaredKind.RULE) "rule" else "computation"} iterates a repetition, and whether its condition can be " +
            "true for a repetition nobody filled (`MVK_NEG_CONDITION_IN_ITERATION`, " +
            "`MVK_PARALLEL_ITERATION_INVALID`) was left to A12: it uses a shape A12 refuses before that law, such " +
            "as a `$` marker beside a parallel iteration or an error field named only by a starred reference"))
}

/** The stage that answered and its finding, null when the condition is admitted. */
private class GuardVerdict(val site: PostParseCheck, val finding: Finding?)

/** Null wherever this reading declines, which is exactly where [iterationGuardGaps] speaks. */
private fun TrackedFactBase.guardVerdictOf(element: TrackedDeclaration, execution: ConditionExecution?): GuardVerdict? {
    if (execution == null || element.declaredKind !in JUDGED) return null
    val guard = guardOf(element, execution) ?: return null
    val parallel = guard.parallelOutcome() ?: return null
    if (parallel.finding != null) return GuardVerdict(PostParseCheck.PARALLEL_ITERATION_GUARD, parallel.finding)
    val ordinary = guard.ordinaryOutcome() ?: return null
    return GuardVerdict(PostParseCheck.ITERATION_GUARD, ordinary.finding)
}

/** A constant operand: its compared value, or one beyond the range this reading holds exactly. */
private sealed interface Constant {
    data class Known(val value: Int) : Constant
    data object Unknown : Constant
}

/** A walk's answer: its finding, null when the condition is admitted. */
private class Outcome(val finding: Finding?)

/** How one reference meets a repetition level. */
private enum class LevelMark { ITERATED, PARALLEL, STARRED, FIXED }

private class GuardLevel(val group: String, var mark: LevelMark, val repeats: Int, val keyed: Boolean)

/**
 * A reference the rule reads itself, with one entry per group on its path from the root. [fields] is what a group
 * stands for where A12 expands it into the fields beneath it: one per field, each repeatable level inside the
 * group multiplying as a star would (measured on `FieldValuesNotUnique`, `CheckerUniquenessGuardAgreementTest`).
 */
private class GuardReference(
    val start: Int,
    val path: String,
    val isField: Boolean,
    val levels: List<GuardLevel>,
    val fields: Long,
    /**
     * The field a selection is keyed by. A12 counts the selection as iterating wherever its key does, as one
     * reference rather than two (`IterationNotValidVisitor.isIteration`, kernel 30.8.1, read 2026-10-23; rows
     * `x1`–`x4`, `w1` and `w2` of `adapter.laws.CheckerIterationK04AgreementTest`), and asks the key's levels as the
     * rule's own.
     */
    val key: GuardReference?,
) {
    /**
     * How many references A12 counts this one as when it does not iterate: each starred level multiplies. The
     * key takes no part, here or in [starred], because A12's star count reads only the selected entity's own
     * indices (`IterationNotValidVisitor.wildcardLevelRepetitions`, kernel 30.8.1, read 2026-10-23); whether the
     * reference iterates, which levels it asks and what it names include the key.
     */
    val weight: Long get() = levels.filter { it.mark == LevelMark.STARRED }.fold(1L) { n, l -> n * l.repeats }
    /**
     * How many references A12 counts this one as in a field list: a group stands for its [fields] (rows `m1` and
     * `m10`–`m11` of `adapter.laws.CheckerIterationGuardAgreementTest`, beside the uniqueness rows).
     */
    val entries: Long get() = weight * if (isField) 1 else fields
    val starred: Boolean get() = levels.any { it.mark == LevelMark.STARRED }
    val iterates: Boolean
        get() = levels.any { it.mark == LevelMark.ITERATED || it.mark == LevelMark.PARALLEL } || key?.iterates == true
    /** The depths the rule's own iteration asks of this reference: its iterated levels and its key's. */
    val judgedDepths: List<Int>
        get() = levels.withIndex().filter { (_, l) -> l.mark == LevelMark.ITERATED && !(l.repeats == 2 && l.keyed) }
            .map { it.index } + key?.judgedDepths.orEmpty()
    /** Every level of this reference and its key. */
    val allLevels: List<GuardLevel> get() = levels + key?.allLevels.orEmpty()
    /** Whether this reference or its key names [target]. */
    fun names(target: String): Boolean = target.isAtOrBelow(path) || key?.names(target) == true
}

/** The level being judged: the parallel iteration as a whole, or the rule's own iteration at one depth. */
private sealed interface Judged {
    data object Parallel : Judged
    data class Ordinary(val depth: Int) : Judged
}

private class IterationGuard(
    val element: TrackedDeclaration,
    val target: String,
    val tree: ConditionNode,
    val references: List<GuardReference>,
    /**
     * The `$`-marked references inside a filter. A12 takes the levels the rule iterates from them as from any
     * reference, but its verdict never reads inside a filter, so they iterate nothing there
     * (`IterationNotValidVisitor.getIterationLevels` against `AnalyseService.isIteration`, kernel 30.8.1; rows
     * `d7`, `d9` and `d14`).
     */
    val outer: List<GuardReference>,
    val operations: List<OperationShape>,
    /** The filter bodies' offsets, which A12's verdict does not read. */
    val bodies: List<IntRange>,
    val identities: Map<String, OperatorId>,
    /** The condition text the recognized offsets index into. */
    val source: String,
) {
    fun iterates(reference: GuardReference, judged: Judged): Boolean = when (judged) {
        Judged.Parallel -> reference.levels.any { it.mark == LevelMark.PARALLEL }
        is Judged.Ordinary -> reference.levels.getOrNull(judged.depth)?.mark == LevelMark.ITERATED
    } || reference.key?.let { iterates(it, judged) } == true

    fun within(at: Int, end: Int) = references.filter { it.start in at until end }

    /** Null where the walk has no verdict to give. */
    fun parallelOutcome(): Outcome? {
        if (references.none { r -> r.levels.any { it.mark == LevelMark.PARALLEL } }) return Outcome(null)
        val admitted = admitted(tree, Judged.Parallel) ?: return null
        return Outcome(if (admitted) null else finding(
            "MVK_PARALLEL_ITERATION_INVALID",
            "this rule iterates `$target`'s repetition in parallel with another group's, and its condition can be " +
                "true for a parallel repetition nobody filled — guard the negative part with a positive reading " +
                "of the same repetitions, joined by `And`",
        ))
    }

    /** Null where the walk has no verdict to give at a level it reaches before any refusal. */
    fun ordinaryOutcome(): Outcome? {
        val depths = (references + outer).flatMap { it.judgedDepths }.distinct().sorted()
        for (depth in depths) {
            when (admitted(tree, Judged.Ordinary(depth))) {
                null -> return null
                true -> continue
                false -> {
                    val level = target.split('/').take(depth + 2).joinToString("/")
                    return Outcome(finding(
                        "MVK_NEG_CONDITION_IN_ITERATION",
                        "this rule runs once per repetition of `$level`, and its condition can be true for a " +
                            "repetition nobody filled — guard the negative part with a positive reading of that " +
                            "repetition, joined by `And`",
                    ))
                }
            }
        }
        return Outcome(null)
    }

    private fun finding(code: String, explain: String) =
        Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, element.path)

    /** Whether [node] is admitted at [judged], or null where this reading has no verdict to give. */
    fun admitted(node: ConditionNode, judged: Judged): Boolean? = when (node) {
        ConditionNode.Custom -> true
        is ConditionNode.Junction -> {
            val connective = node.connectives.distinct().singleOrNull()
            val parts = node.parts.map { part -> admitted(part, judged)?.let { it && iterates(part, judged) } }
            when {
                connective == null || parts.any { it == null } -> null
                connective == ConnectiveKind.AND -> parts.any { it == true }
                else -> parts.all { it == true }
            }
        }
        is ConditionNode.Call -> callAdmitted(node.shape, judged)
        is ConditionNode.Comparison -> comparisonAdmitted(node.shape, judged)
    }

    fun iterates(node: ConditionNode, judged: Judged): Boolean = when (node) {
        ConditionNode.Custom -> false
        is ConditionNode.Junction -> node.parts.any { iterates(it, judged) }
        is ConditionNode.Call -> within(node.shape.at, node.shape.end).any { iterates(it, judged) }
        is ConditionNode.Comparison ->
            within(node.shape.left.first().start, node.shape.right.last().end).any { iterates(it, judged) }
    }

    private fun callAdmitted(call: CallShape, judged: Judged): Boolean? {
        val id = identities[call.operator] ?: return null
        val list = within(call.at, call.end)
        fun counted(threshold: Long) = list.filterNot { iterates(it, judged) }.sumOf { it.entries } >= threshold
        return when (id) {
            in NEGATIVE_PRESENCE -> false
            in POSITIVE_PRESENCE -> !counted(1)
            OperatorId.MORE_THAN_ONE_FIELD_FILLED, OperatorId.FIELD_VALUES_NOT_UNIQUE -> !counted(2)
            OperatorId.FIELD_FILLED, OperatorId.ALL_FIELDS_FILLED -> !(list.sumOf { it.entries } == 1L && counted(1))
            OperatorId.GROUP_FILLED, OperatorId.ALL_GROUPS_FILLED -> !(list.size == 1 && !list.single().starred && counted(1))
            // Leaves A12's guard has no arm for, admitted and iterating where their references do.
            OperatorId.DATE_RANGES_OVERLAP, OperatorId.AT_LEAST_ONE_DATE_RANGE_OVERLAPS, OperatorId.REPETITION_NOT_UNIQUE -> true
            // One spelling names both forms, so the operand decides: only a `Date` construction is A12's date
            // validity condition (`IDateValidityCondition`); the field-and-type form has no arm.
            OperatorId.VALID, OperatorId.VALID_TYPE, OperatorId.INVALID, OperatorId.INVALID_TYPE ->
                identities[call.operands.firstOrNull()?.firstOrNull()?.text] != OperatorId.DATE ||
                    (id == OperatorId.VALID || id == OperatorId.VALID_TYPE) || list.none { iterates(it, judged) }
            OperatorId.NO_FIELD_VALUE_INCLUDED_IN_VALUE_LIST, OperatorId.NOT_ALL_FIELD_VALUES_INCLUDED_IN_VALUE_LIST,
            OperatorId.AT_LEAST_ONE_FIELD_VALUE_INCLUDED_IN_VALUE_LIST -> listMembershipAdmitted(id, call, judged)
            OperatorId.FIELD_VALUE_INCLUDED_IN_VALUE_LIST, OperatorId.FIELD_VALUE_NOT_INCLUDED_IN_VALUE_LIST -> valueListAdmitted(id, call, judged)
            else -> null
        }
    }

    /** `Subject list In values`: the entities before the `In` keyword are the list, the rest the values. */
    private fun listMembershipAdmitted(id: OperatorId, call: CallShape, judged: Judged): Boolean? {
        val all = within(call.at, call.end)
        if (all.none { iterates(it, judged) }) return true
        val inAt = call.operands.flatten().firstOrNull { it.kind == TokenKind.NAME && it.text in IN_WORDS }?.start
            ?: return null
        val subjects = all.filter { it.start < inAt }
        val values = all.filter { it.start > inAt }
        val stringValues = call.operands.flatten()
            .any { it.start > inAt && (it.kind == TokenKind.STRING || it.kind == TokenKind.NUMBER) }
        val subjectOutside = subjects.any { !iterates(it, judged) }
        return when (id) {
            OperatorId.NO_FIELD_VALUE_INCLUDED_IN_VALUE_LIST -> false
            OperatorId.NOT_ALL_FIELD_VALUES_INCLUDED_IN_VALUE_LIST -> !subjectOutside
            else -> !(subjectOutside && (values.any { !iterates(it, judged) } || stringValues))
        }
    }

    /** `Field, values…`: judged only when the values iterate and the field does not. */
    private fun valueListAdmitted(id: OperatorId, call: CallShape, judged: Judged): Boolean? {
        val subject = call.operands.firstOrNull()?.firstOrNull()?.start ?: return null
        val all = within(call.at, call.end)
        val field = all.filter { it.start == subject }
        val values = all - field.toSet()
        if (all.none { iterates(it, judged) } || field.any { iterates(it, judged) }) return true
        return when (id) {
            OperatorId.FIELD_VALUE_NOT_INCLUDED_IN_VALUE_LIST -> false
            else -> values.all { iterates(it, judged) }
        }
    }

    /**
     * Each side is read in turn as the operation, with the operator turned to face it, against the other side
     * when that is a constant. A side refuses when its operation needs a filled row there: mixed references
     * against any constant, or references that all iterate compared below a positive constant or at zero.
     */
    private fun comparisonAdmitted(shape: ComparisonShape, judged: Judged): Boolean? {
        val left = operations.spanning(shape.left) ?: return null
        val right = operations.spanning(shape.right) ?: return null
        val written = shape.operator.text.let { identities[it]?.catalogId ?: it }.takeIf { it in FACING } ?: return null
        val kinds = (kindOf(left) ?: return null) to (kindOf(right) ?: return null)
        if (readsJudgedRepetition(shape)) return false
        var admitted: Boolean? = true
        val sides = listOf(Triple(left, kinds.first, written), Triple(right, kinds.second, FACING.getValue(written)))
        for ((side, facing) in sides.withIndex()) {
            val (operation, kind, operator) = facing
            val constant = constantOf(sides[1 - side].first) ?: continue
            val needsRow: Boolean? = when {
                kind.needsRowAgainstConstant && mixed(operation, judged) -> true
                !allIterate(operation, judged) -> false
                constant is Constant.Known -> (kind.needsRowBelow && operator in BELOW && constant.value > 0) ||
                    (kind.needsRowAtZero && operator in AT_ZERO && constant.value == 0)
                // A constant beyond the exact range: its value decides only where an arm applies at all.
                (kind.needsRowBelow && operator in BELOW) || (kind.needsRowAtZero && operator in AT_ZERO) -> null
                else -> false
            }
            when (needsRow) {
                true -> return false
                null -> admitted = null
                false -> Unit
            }
        }
        return admitted
    }

    /**
     * Any `CurrentRepetition` in the comparison outside a filter body, whichever level it reads: A12's verdict does
     * not read inside a filter, and since kernel 31.1.1 it refuses the operation without asking whether it iterates
     * the judged level ([KERNEL-FINDINGS KF656]).
     */
    private fun readsJudgedRepetition(shape: ComparisonShape): Boolean =
        operations.filterIsInstance<OperationShape.Call>().any { op ->
            op.at >= shape.left.first().start && op.end <= shape.right.last().end &&
                identities[op.call.operator] == OperatorId.CURRENT_REPETITION && bodies.none { op.at in it }
        }

    /** A bracket only groups, so the operation inside it is the one compared (measured, `{0}` and `{Sum(…)}`). */
    private fun kindOf(op: OperationShape): OperationKind? = when (op) {
        is OperationShape.Group -> kindOf(op.child)
        is OperationShape.Read -> OperationKind.FIELD
        is OperationShape.Literal, is OperationShape.Arithmetic -> OperationKind.OTHER
        is OperationShape.Call -> when (val id = identities[op.call.operator]) {
            null -> null
            OperatorId.SUM_OF_PRODUCTS -> OperationKind.PRODUCT_SUM
            in ENTITY_LIST_KINDS -> ENTITY_LIST_KINDS.getValue(id)
            in OPERATION_LISTS -> OperationKind.OPERATION_LIST
            in FIELD_OPERATIONS -> OperationKind.FIELD
            in DATE_DIFFERENCES, in DATE_EXTRACT_OPERATIONS -> OperationKind.DATE
            else -> OperationKind.OTHER
        }
    }

    /**
     * A number constant as A12 compares it: parsed to the nearest double, rounded to the nearest whole number with
     * a tie going up and the result saturating at a long's bounds, then narrowed to 32 bits
     * (`FeldOperationUtil.getZahlKonstanteWert`, kernel 30.8.1: `parseDouble`, `Math.round`, an `int` cast). So
     * `-0.5` compares as zero and `2^32` as zero ([KERNEL-FINDINGS KF595]), a value past a long's range as `-1`, and
     * `0.4999999999999999999` as one, because its nearest double is a half. A text that is not plain digits
     * (an exponent) is [Constant.Unknown]: still a constant, but one whose value this reading does not claim.
     */
    private fun constantOf(op: OperationShape): Constant? {
        if (op is OperationShape.Group) return constantOf(op.child)
        if (op !is OperationShape.Literal) return null
        val text = source.substring(op.at, op.end).filterNot { it == ' ' || it == '\t' || it == '\n' || it == '\r' }
        val negative = text.startsWith('-')
        val digits = text.removePrefix("-")
        if (digits.firstOrNull() !in '0'..'9') return null
        val whole = digits.substringBefore('.')
        val fraction = digits.substringAfter('.', missingDelimiterValue = "")
        if ((whole + fraction).any { it !in '0'..'9' }) return Constant.Unknown
        // Rebuilt from its digits so that only plain decimal text reaches the parser, which every platform reads
        // as the nearest double (`IterationGuardTest`, whose rows run on the JVM and on Node).
        val value = "${if (negative) "-" else ""}$whole.${fraction.ifEmpty { "0" }}".toDouble()
        val below = floor(value)
        return Constant.Known((if (value - below >= 0.5) below + 1 else below).toLong().toInt())
    }

    private fun allIterate(op: OperationShape, judged: Judged) =
        within(op.at, op.end).let { refs -> refs.any { iterates(it, judged) } && refs.all { iterates(it, judged) } }

    private fun mixed(op: OperationShape, judged: Judged) =
        within(op.at, op.end).let { refs -> refs.any { iterates(it, judged) } && refs.any { !iterates(it, judged) } }
}

/** The operation kinds the comparison arms distinguish, with the arms each kind takes part in. */
private enum class OperationKind(
    val needsRowBelow: Boolean = false,
    val needsRowAtZero: Boolean = false,
    val needsRowAgainstConstant: Boolean = false,
) {
    COUNT(needsRowBelow = true, needsRowAgainstConstant = true),
    COUNT_OF_VALUE(needsRowBelow = true, needsRowAtZero = true, needsRowAgainstConstant = true),
    AMOUNT(needsRowAtZero = true, needsRowAgainstConstant = true),
    OPERATION_LIST(needsRowAtZero = true, needsRowAgainstConstant = true),
    PRODUCT_SUM(needsRowAtZero = true, needsRowAgainstConstant = true),
    FIELD(needsRowAtZero = true),
    DATE(needsRowAtZero = true),
    OTHER,
}

private fun TrackedFactBase.guardOf(element: TrackedDeclaration, execution: ConditionExecution): IterationGuard? {
    val recognition = execution.recognition
    val tree = recognition.tree ?: return null
    val declared = element.declaredReference()
        ?.takeIf { it.role == ReferenceRole.ERROR_TARGET || it.role == ReferenceRole.COMPUTED_TARGET } ?: return null
    val target = (declared.target as? Reference.Resolved)?.path ?: return null
    // A group error target is judged as a field one is, at the levels its references iterate (rows `g01`–`g06`,
    // `y1`–`y9`, `t0`–`t8`, `f1`, `f5a`–`f5f` of `adapter.laws.CheckerIterationK04AgreementTest`), in parallel
    // against its parent's index chain (rows `g2`, `g2b` of `adapter.laws.CheckerGroupErrorTargetAgreementTest`).
    val anchor = anchorOf(target) ?: return null
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    val bodies = recognition.sites.filterIsInstance<SyntaxSite.FilterExit>().map { it.entry.end until it.end }
    val references = mutableListOf<GuardReference>()
    val outer = mutableListOf<GuardReference>()
    // Each reference's levels beside the levels A12's pass marks on them, a field key's included.
    val marking = mutableListOf<Pair<List<GuardLevel>, MarkedLevels>>()
    var pendingKey: GuardReference? = null
    for (entity in recognition.entities) {
        val inBody = bodies.any { entity.site.start in it }
        if (inBody != entity.inFilter) return null
        // A term's reads iterate its own entity, not the rule (KERNEL-FINDINGS KF670); the entity itself is kept.
        if (entity.inTerm) continue
        // `RuleGroup` is the declaring group, iterating where it repeats (KERNEL-FINDINGS KF611); a starred one is
        // refused earlier (`MVK_INVALID_WILDCARD_FOR_RULEGROUP`) and stays unresolved here.
        val written = entity.writtenPath
            .let { if (isRuleContext(it, conditionLanguage)) group else it }
        if (entity.outerMarked) {
            // Outside a filter the marker is refused earlier (`MVK_INVALID_OUTER_ITERATION`).
            if (!inBody) return null
            outer += guardReferenceOf(entity, written, group, element, key = null) ?: return null
            continue
        }
        if (inBody) continue
        // A field key is recognized ahead of the selection it keys, and joins that selection rather than
        // standing as a reference of its own.
        if (entity.indexKey) {
            if (pendingKey != null) return null
            pendingKey = guardReferenceOf(entity, written, group, element, key = null) ?: return null
            marking += pendingKey.levels to (markedLevelsOf(written, group, anchor, element) ?: return null)
            continue
        }
        if (pendingKey != null && !entity.indexed) return null
        val reference = guardReferenceOf(entity, written, group, element, key = pendingKey) ?: return null
        pendingKey = null
        references += reference
        // A selected level is never marked parallel: A12's pass marks only an iterated one.
        if (!entity.indexed) marking += reference.levels to (markedLevelsOf(written, group, anchor, element) ?: return null)
    }
    if (pendingKey != null) return null
    // A12's two passes (KF376), read where the filtered-entity family reads them: only once some reference
    // iterates jointly does every reference's keyed level on the error field's path become parallel too, a
    // field key's included (rows `x1`–`x4` of `adapter.laws.CheckerIterationGuardAgreementTest`).
    if (marking.any { it.second.joint.isNotEmpty() }) {
        // A `$` marker beside a parallel iteration is refused earlier (`MVK_PARALLEL_ITERATION_NOT_ALLOWED`,
        // rows `d11`, `d15`–`d17`).
        if (outer.isNotEmpty()) return null
        marking.forEach { (levels, marked) ->
            levels.filter { it.mark == LevelMark.ITERATED && it.group in marked.marked }.forEach { it.mark = LevelMark.PARALLEL }
        }
    }
    val all = references + outer
    if (all.any { r -> r.allLevels.any { it.mark == LevelMark.ITERATED && !target.isAtOrBelow(it.group) } }) return null
    // A12 decides the error-field binding (KF417) before this law, so where no reference naming the error
    // field iterates while another does, that earlier class may answer and this reading has no verdict.
    if (references.any { it.iterates } && all.none { it.iterates && it.names(target) }) return null
    return IterationGuard(element, target, tree, references, outer, recognition.operations, bodies,
        CONDITION_OPERATOR_IDENTITIES.getValue(conditionLanguage), execution.carrier.text)
}

private fun TrackedFactBase.guardReferenceOf(
    entity: RecognizedEntity,
    written: String,
    declaringGroup: String,
    element: TrackedDeclaration,
    key: GuardReference?,
): GuardReference? {
    val resolved = resolveEntity(declaringGroup, written.replace("*", ""), element.origin) as? Reference.Resolved
        ?: return null
    val starred = starredLevelsOf(written, declaringGroup, element) ?: return null
    val declaration = declarationAt(resolved.path) ?: return null
    val segments = resolved.path.split('/').drop(1)
    val groups = segments.indices.map { "/" + segments.take(it + 1).joinToString("/") }
        .let { if (declaration.declaredKind == DeclaredKind.FIELD) it.dropLast(1) else it }
    // A constant selection fixes the level it selects, which then neither iterates nor multiplies; the levels
    // above it iterate as they would without it (KERNEL-FINDINGS KF64's "replaces only its own level").
    val selected = if (entity.indexed) indexGroupOf(resolved.path) ?: return null else null
    val levels = groups.map { path ->
        val group = declarationAt(path) ?: return null
        val repeats = repetitionCountIn(group.body)
        val mark = when {
            path == selected -> LevelMark.FIXED
            path in starred -> LevelMark.STARRED
            repeats > 1 -> LevelMark.ITERATED
            else -> LevelMark.FIXED
        }
        GuardLevel(path, mark, repeats, indexFieldNameOf(group) != null)
    }
    val fields = fieldsUnder(declaration).orEmpty().sumOf { field ->
        field.path.removePrefix("${resolved.path}/").split('/').dropLast(1)
            .runningReduce { above, segment -> "$above/$segment" }
            .fold(1L) { n, inner -> n * repetitionCountIn(declarationAt("${resolved.path}/$inner")?.body) }
    }
    return GuardReference(entity.site.start, resolved.path, declaration.declaredKind == DeclaredKind.FIELD, levels, fields,
        key = key)
}

private val JUDGED = setOf(DeclaredKind.RULE, DeclaredKind.COMPUTATION)

private val NEGATIVE_PRESENCE = setOf(OperatorId.FIELD_NOT_FILLED, OperatorId.NO_FIELD_FILLED, OperatorId.NOT_ALL_FIELDS_FILLED, OperatorId.NOT_EXACTLY_ONE_FIELD_FILLED,
    OperatorId.GROUP_NOT_FILLED, OperatorId.NO_GROUP_FILLED, OperatorId.NOT_ALL_GROUPS_FILLED)
private val POSITIVE_PRESENCE = setOf(OperatorId.AT_LEAST_ONE_FIELD_FILLED, OperatorId.FIELDS_NOT_COLLECTIVELY_FILLED, OperatorId.AT_LEAST_ONE_GROUP_FILLED,
    OperatorId.GROUPS_NOT_COLLECTIVELY_FILLED)
private val ENTITY_LIST_KINDS = mapOf(
    OperatorId.NUMBER_OF_FILLED_FIELDS to OperationKind.COUNT, OperatorId.NUMBER_OF_FILLED_GROUPS to OperationKind.COUNT,
    OperatorId.NUMBER_OF_DIFFERENT_VALUES to OperationKind.COUNT, OperatorId.NUMBER_OF_VALUE_IN_FIELDS to OperationKind.COUNT_OF_VALUE,
    OperatorId.SUM to OperationKind.AMOUNT, OperatorId.MIN_VALUE to OperationKind.AMOUNT, OperatorId.MAX_VALUE to OperationKind.AMOUNT,
    OperatorId.FIRST_FILLED_VALUE to OperationKind.AMOUNT,
)
private val OPERATION_LISTS = setOf(OperatorId.MAX, OperatorId.MIN, OperatorId.ABS, OperatorId.ROUND_ACCOUNTING, OperatorId.ROUND_UP, OperatorId.ROUND_DOWN)
private val FIELD_OPERATIONS = setOf(OperatorId.LENGTH, OperatorId.ROUND_ACCOUNTING_VALUE, OperatorId.ROUND_UP_VALUE, OperatorId.ROUND_DOWN_VALUE, OperatorId.ABS_VALUE,
    OperatorId.RANGE_AS_NUMBER, OperatorId.FIELD_VALUE_AS_NUMBER, OperatorId.RANGE_AS_STRING, OperatorId.FIELD_VALUE_AS_STRING, OperatorId.VALUE_AS_DATE)
private val DATE_DIFFERENCES = setOf(OperatorId.DIFFERENCE_IN_SECONDS, OperatorId.DIFFERENCE_IN_MINUTES, OperatorId.DIFFERENCE_IN_HOURS, OperatorId.DIFFERENCE_IN_DAYS,
    OperatorId.DIFFERENCE_IN_MONTHS, OperatorId.DIFFERENCE_IN_YEARS)
/**
 * Every comparison operator, as read from the right-hand side. The tolerance comparisons take part in the arms
 * where `!=` does (`IterationNotValidVisitor.checkIfVergleichMitKleiner`, kernel 30.8.1); the pattern ones in none.
 */
private val FACING = mapOf("==" to "==", "!=" to "!=", "<" to ">", ">" to "<", "<=" to ">=", ">=" to "<=") +
    (TOLERANCE_COMPARISONS + PATTERN_COMPARISONS).map { it.catalogId }.associateWith { it }
/** Comparisons below a bound, which an operation counting filled rows cannot meet without one. */
private val BELOW = setOf("<", "<=", "!=") + TOLERANCE_COMPARISONS.map { it.catalogId }
/** Comparisons an amount meets at zero. */
private val AT_ZERO = setOf("==", "<=", ">=")
private val IN_WORDS = setOf("In", "IN", "in")
