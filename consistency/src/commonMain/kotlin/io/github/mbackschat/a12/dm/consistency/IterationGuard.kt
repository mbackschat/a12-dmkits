package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_IDENTITIES
import io.github.mbackschat.a12.dm.consistency.syntax.CallShape
import io.github.mbackschat.a12.dm.consistency.syntax.ComparisonShape
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionNode
import io.github.mbackschat.a12.dm.consistency.syntax.ConnectiveKind
import io.github.mbackschat.a12.dm.consistency.syntax.OperationShape
import io.github.mbackschat.a12.dm.consistency.syntax.REPETITION_NOT_UNIQUE
import io.github.mbackschat.a12.dm.consistency.syntax.RecognizedEntity
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxSite
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.isRuleContext
import io.github.mbackschat.a12.dm.consistency.syntax.normalizedPath
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
 * references iterate the judged level: a negative presence over an iterating reference, a positive presence that
 * counts references not iterating it (a starred one counting as often as its level repeats, a group as its
 * fields), a count compared below a positive constant or an amount compared equal to zero over references that
 * all iterate, an amount against a constant over mixed references, `CurrentRepetition` of the judged level, the
 * date form of `Invalid` and the value-list forms. A tolerance comparison takes part where `!=` does; a pattern,
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
    /** Whether this reference carries a selection, which a field key may then join. */
    val selected: Boolean,
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
    val identities: Map<String, String>,
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
            in NEGATIVE_PRESENCE -> list.none { iterates(it, judged) }
            in POSITIVE_PRESENCE -> !counted(1)
            "MoreThanOneFieldFilled", "FieldValuesNotUnique" -> !counted(2)
            "FieldFilled", "AllFieldsFilled" -> !(list.sumOf { it.entries } == 1L && counted(1))
            "GroupFilled", "AllGroupsFilled" -> !(list.size == 1 && !list.single().starred && counted(1))
            // Leaves A12's guard has no arm for, admitted and iterating where their references do.
            "DateRangesOverlap", "AtLeastOneDateRangeOverlaps", REPETITION_NOT_UNIQUE -> true
            // One spelling names both forms, so the operand decides: only a `Date` construction is A12's date
            // validity condition (`IDateValidityCondition`); the field-and-type form has no arm.
            "Valid", "ValidType", "Invalid", "InvalidType" ->
                identities[call.operands.firstOrNull()?.firstOrNull()?.text] != "Date" ||
                    id.startsWith("Valid") || list.none { iterates(it, judged) }
            "NoFieldValueIncludedInValueList", "NotAllFieldValuesIncludedInValueList",
            "AtLeastOneFieldValueIncludedInValueList" -> listMembershipAdmitted(id, call, judged)
            "FieldValueIncludedInValueList", "FieldValueNotIncludedInValueList" -> valueListAdmitted(id, call, judged)
            else -> null
        }
    }

    /** `Subject list In values`: the entities before the `In` keyword are the list, the rest the values. */
    private fun listMembershipAdmitted(id: String, call: CallShape, judged: Judged): Boolean? {
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
            "NoFieldValueIncludedInValueList" -> false
            "NotAllFieldValuesIncludedInValueList" -> !subjectOutside
            else -> !(subjectOutside && (values.any { !iterates(it, judged) } || stringValues))
        }
    }

    /** `Field, values…`: judged only when the values iterate and the field does not. */
    private fun valueListAdmitted(id: String, call: CallShape, judged: Judged): Boolean? {
        val subject = call.operands.firstOrNull()?.firstOrNull()?.start ?: return null
        val all = within(call.at, call.end)
        val field = all.filter { it.start == subject }
        val values = all - field.toSet()
        if (all.none { iterates(it, judged) } || field.any { iterates(it, judged) }) return true
        return when (id) {
            "FieldValueNotIncludedInValueList" -> false
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
        val written = shape.operator.text.let { identities[it] ?: it }.takeIf { it in FACING } ?: return null
        val kinds = (kindOf(left) ?: return null) to (kindOf(right) ?: return null)
        if (readsJudgedRepetition(shape, judged)) return false
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

    /** `CurrentRepetition` of a level being judged, anywhere in the comparison. */
    private fun readsJudgedRepetition(shape: ComparisonShape, judged: Judged): Boolean =
        operations.filterIsInstance<OperationShape.Call>().any { op ->
            op.at >= shape.left.first().start && op.end <= shape.right.last().end &&
                identities[op.call.operator] == "CurrentRepetition" &&
                within(op.at, op.end).any { iterates(it, judged) }
        }

    /** A bracket only groups, so the operation inside it is the one compared (measured, `{0}` and `{Sum(…)}`). */
    private fun kindOf(op: OperationShape): OperationKind? = when (op) {
        is OperationShape.Group -> kindOf(op.child)
        is OperationShape.Read -> OperationKind.FIELD
        is OperationShape.Literal, is OperationShape.Arithmetic -> OperationKind.OTHER
        is OperationShape.Call -> when (val id = identities[op.call.operator]) {
            null -> null
            "SumOfProducts" -> OperationKind.PRODUCT_SUM
            in ENTITY_LIST_KINDS -> ENTITY_LIST_KINDS.getValue(id)
            in OPERATION_LISTS -> OperationKind.OPERATION_LIST
            in FIELD_OPERATIONS -> OperationKind.FIELD
            in DATE_DIFFERENCES, in DATE_EXTRACTIONS -> OperationKind.DATE
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
    /** A12 leaves a product sum out of the constant arm, unlike every other aggregate. */
    PRODUCT_SUM(needsRowAtZero = true),
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
        // `RuleGroup` is the declaring group, iterating where it repeats (KERNEL-FINDINGS KF611); a starred one is
        // refused earlier (`MVK_INVALID_WILDCARD_FOR_RULEGROUP`) and stays unresolved here.
        val written = normalizedPath(entity.path.dropWhile { it.text == OUTER_MARKER })
            .let { if (isRuleContext(it, conditionLanguage)) group else it }
        if (entity.path.first().text == OUTER_MARKER) {
            // Outside a filter the marker is refused earlier (`MVK_INVALID_OUTER_ITERATION`).
            if (!inBody) return null
            outer += guardReferenceOf(entity, written, group, element, key = null) ?: return null
            continue
        }
        if (inBody) continue
        val levels = markedLevelsOf(written, group, anchor, element)
        // A field key is recognized ahead of the selection it keys, and joins that selection rather than
        // standing as a reference of its own.
        if (entity.indexKey) {
            if (pendingKey != null) return null
            pendingKey = guardReferenceOf(entity, written, group, element, key = null) ?: return null
            marking += pendingKey.levels to (levels ?: return null)
            continue
        }
        if (pendingKey != null && !entity.indexed) return null
        val reference = guardReferenceOf(entity, written, group, element, key = pendingKey) ?: return null
        pendingKey = null
        references += reference
        // A selected level is never marked parallel: A12's pass marks only an iterated one.
        if (!entity.indexed) marking += reference.levels to (levels ?: return null)
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
    return IterationGuard(element, target, tree, references, outer, recognition.operations,
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
        selected = entity.indexed, key = key)
}

private val JUDGED = setOf(DeclaredKind.RULE, DeclaredKind.COMPUTATION)

private val NEGATIVE_PRESENCE = setOf("FieldNotFilled", "NoFieldFilled", "NotAllFieldsFilled", "NotExactlyOneFieldFilled",
    "GroupNotFilled", "NoGroupFilled", "NotAllGroupsFilled")
private val POSITIVE_PRESENCE = setOf("AtLeastOneFieldFilled", "FieldsNotCollectivelyFilled", "AtLeastOneGroupFilled",
    "GroupsNotCollectivelyFilled")
private val ENTITY_LIST_KINDS = mapOf(
    "NumberOfFilledFields" to OperationKind.COUNT, "NumberOfFilledGroups" to OperationKind.COUNT,
    "NumberOfDifferentValues" to OperationKind.COUNT, "NumberOfValueInFields" to OperationKind.COUNT_OF_VALUE,
    "Sum" to OperationKind.AMOUNT, "MinValue" to OperationKind.AMOUNT, "MaxValue" to OperationKind.AMOUNT,
    "FirstFilledValue" to OperationKind.AMOUNT,
)
private val OPERATION_LISTS = setOf("Max", "Min", "Abs", "RoundAccounting", "RoundUp", "RoundDown")
private val FIELD_OPERATIONS = setOf("Length", "RoundAccountingValue", "RoundUpValue", "RoundDownValue", "AbsValue",
    "RangeAsNumber", "FieldValueAsNumber", "RangeAsString", "FieldValueAsString", "ValueAsDate")
private val DATE_DIFFERENCES = setOf("DifferenceInSeconds", "DifferenceInMinutes", "DifferenceInHours", "DifferenceInDays",
    "DifferenceInMonths", "DifferenceInYears")
private val DATE_EXTRACTIONS = setOf("DayFromDate", "MonthFromDate", "YearFromDate", "QuarterFromDate", "HoursFromTime",
    "MinutesFromTime", "SecondsFromTime", "DateFromDateTime", "TimeFromDateTime")
/**
 * A12's tolerance comparisons, which take part in the arms where `!=` does (`ComparisonOperation` `NE1`…`NE10`,
 * read by `IterationNotValidVisitor.checkIfVergleichMitKleiner`, kernel 30.8.1).
 */
private val TOLERANCES = setOf("DiffersWithToleranceRange1", "DiffersWithToleranceRange2", "DiffersWithToleranceRange5",
    "DiffersWithToleranceRange10")
/** A12's pattern comparisons, which no arm reads (`NM`, `WM`). */
private val PATTERNS = setOf("PatternMatched", "PatternViolated")
/** Every comparison operator, as read from the right-hand side. */
private val FACING = mapOf("==" to "==", "!=" to "!=", "<" to ">", ">" to "<", "<=" to ">=", ">=" to "<=") +
    (TOLERANCES + PATTERNS).associateWith { it }
/** Comparisons below a bound, which an operation counting filled rows cannot meet without one. */
private val BELOW = setOf("<", "<=", "!=") + TOLERANCES
/** Comparisons an amount meets at zero. */
private val AT_ZERO = setOf("==", "<=", ">=")
private val IN_WORDS = setOf("In", "IN", "in")
