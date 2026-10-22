package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.CallShape
import io.github.mbackschat.a12.dm.consistency.syntax.ComparisonShape
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionNode
import io.github.mbackschat.a12.dm.consistency.syntax.ConnectiveKind
import io.github.mbackschat.a12.dm.consistency.syntax.OperationShape
import io.github.mbackschat.a12.dm.consistency.syntax.RecognizedEntity
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxSite
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.isRuleContext
import io.github.mbackschat.a12.dm.consistency.syntax.normalizedPath
import kotlinx.serialization.json.JsonPrimitive

/**
 * The iteration guard: A12 refuses an iterating rule whose condition can be true for a repetition nobody
 * filled. `MVK_PARALLEL_ITERATION_INVALID` answers it across parallel groups and `MVK_NEG_CONDITION_IN_ITERATION`
 * on the rule's own iteration, in that order and after every other whole-rule check
 * ([ITERATION-GUARD-PORT-PROPOSAL]; `adapter.laws.CheckerIterationGuardAgreementTest`).
 *
 * **How a condition is judged.** Every part starts admitted, and a part refuses by its kind and by how its
 * references iterate the judged level: a negative presence over an iterating reference, a positive presence that
 * counts references not iterating it (a starred one counting as often as its level repeats), a count compared
 * below a positive constant or an amount compared equal to zero over references that all iterate, an amount
 * against a constant over mixed references, `CurrentRepetition` of the judged level, `Invalid` and the value-list
 * forms. An `And` is admitted when one of its parts is admitted and iterates the level, an `Or` only when every
 * part is ([KERNEL-FINDINGS KF537]).
 *
 * **Which levels.** Parallel iteration is judged as one whole. The rule's own iteration is judged level by
 * level, shallowest first, and the first refused level is the one named; a level repeating exactly twice that
 * declares an index field is never judged. A computation is judged through the condition A12 joins it into
 * ([computationCondition]), whose leading `FieldFilled` of the computed field is what guards it.
 *
 * **Silent wherever the reading is not this checker's to give, and then the declaration carries a coverage gap
 * ([iterationGuardGaps])**: CONSISTENCY-GAPS DG146 lists the shapes, and every `null` this file returns is one.
 */
internal fun TrackedFactBase.iterationGuardContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> {
    val verdict = guardVerdictOf(element, execution) ?: return emptyList()
    return execution!!.postParse(verdict.site) { listOfNotNull(verdict.finding) }
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
            "`MVK_PARALLEL_ITERATION_INVALID`) was left to A12: it uses a shape this checker does not judge for " +
            "that law, such as a `$` marker, a selection, a condition kind outside the measured set or a group " +
            "error target"))
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
) {
    /** How many references A12 counts this one as when it does not iterate: each starred level multiplies. */
    val weight: Long get() = levels.filter { it.mark == LevelMark.STARRED }.fold(1L) { n, l -> n * l.repeats }
    val starred: Boolean get() = levels.any { it.mark == LevelMark.STARRED }
    val iterates: Boolean get() = levels.any { it.mark == LevelMark.ITERATED || it.mark == LevelMark.PARALLEL }
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
    val operations: List<OperationShape>,
    val identities: Map<String, String>,
    /** The condition text the recognized offsets index into. */
    val source: String,
) {
    fun iterates(reference: GuardReference, judged: Judged): Boolean = when (judged) {
        Judged.Parallel -> reference.levels.any { it.mark == LevelMark.PARALLEL }
        is Judged.Ordinary -> reference.levels.getOrNull(judged.depth)?.mark == LevelMark.ITERATED
    }

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
        val depths = references.flatMap { r ->
            r.levels.withIndex().filter { (_, l) -> l.mark == LevelMark.ITERATED && !(l.repeats == 2 && l.keyed) }
                .map { it.index }
        }.distinct().sorted()
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
        fun counted(threshold: Long) =
            list.filterNot { iterates(it, judged) }.sumOf { it.weight } >= threshold
        val single = list.size == 1 && !list.single().starred
        // A group named where fields are listed stands for its fields, which is measured only for the uniqueness
        // condition; elsewhere the count it contributes is unknown.
        val groupAsFields = id in FIELD_LISTS && list.any { !it.isField }
        if (groupAsFields && id != "FieldValuesNotUnique") return null
        return when (id) {
            in NEGATIVE_PRESENCE -> list.none { iterates(it, judged) }
            in POSITIVE_PRESENCE -> !counted(1)
            "MoreThanOneFieldFilled" -> !counted(2)
            "FieldValuesNotUnique" ->
                list.filterNot { iterates(it, judged) }.sumOf { it.weight * if (it.isField) 1 else it.fields } < 2
            "FieldFilled", "AllFieldsFilled" -> !(single && list.single().isField && counted(1))
            "GroupFilled", "AllGroupsFilled" -> !(single && counted(1))
            "DateRangesOverlap", "AtLeastOneDateRangeOverlaps" -> true
            // One spelling names both forms, so the operand decides: only a `Date` construction is A12's date
            // validity condition, and the field-and-type form is not measured here.
            "Valid", "ValidType", "Invalid", "InvalidType" ->
                if (identities[call.operands.firstOrNull()?.firstOrNull()?.text] != "Date") null
                else id.startsWith("Valid") || list.none { iterates(it, judged) }
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
        val left = operationOf(shape.left) ?: return null
        val right = operationOf(shape.right) ?: return null
        val written = shape.operator.text.takeIf { it in COMPARISON_SYMBOLS } ?: return null
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

    private fun operationOf(tokens: List<io.github.mbackschat.a12.dm.consistency.syntax.Token>): OperationShape? {
        val meaningful = tokens.filter { it.kind != TokenKind.COMMENT }
        return operations.lastOrNull { it.at == meaningful.firstOrNull()?.start && it.end == meaningful.lastOrNull()?.end }
    }

    /** A bracket only groups, so the operation inside it is the one compared (measured, `{0}` and `{Sum(…)}`). */
    private fun kindOf(op: OperationShape): OperationKind? = when (op) {
        is OperationShape.Group -> kindOf(op.child)
        is OperationShape.Read -> OperationKind.FIELD
        is OperationShape.Literal, is OperationShape.Arithmetic -> OperationKind.OTHER
        is OperationShape.Call -> when (val id = identities[op.call.operator]) {
            null -> null
            // A product sum needs a star at its lowest level and one group for its operands, so every row we
            // could author was answered by an earlier class and its arm stays unmeasured: declined.
            "SumOfProducts" -> null
            in ENTITY_LIST_OPERATIONS -> ENTITY_LIST_KINDS.getValue(id)
            in OPERATION_LISTS -> OperationKind.OPERATION_LIST
            in FIELD_OPERATIONS -> OperationKind.FIELD
            in DATE_DIFFERENCES, in DATE_EXTRACTIONS -> OperationKind.DATE
            else -> OperationKind.OTHER
        }
    }

    /**
     * A number constant as A12 compares it: rounded half up on its sign to a whole number, then narrowed to 32
     * bits, so `-0.5`, `-0.4` and `0.4` all compare as zero ([KERNEL-FINDINGS KF595]). Read from its digits, with
     * any space after the sign dropped, rather than through a platform number parser, which shared code may not
     * depend on. A value with an exponent or more significant digits than a double holds exactly is
     * [Constant.Unknown]: still a constant, but one whose value this reading does not claim.
     */
    private fun constantOf(op: OperationShape): Constant? {
        if (op is OperationShape.Group) return constantOf(op.child)
        if (op !is OperationShape.Literal) return null
        val text = source.substring(op.at, op.end).filterNot { it == ' ' || it == '\t' || it == '\n' || it == '\r' }
        val negative = text.startsWith('-')
        val digits = text.removePrefix("-")
        if (digits.firstOrNull() !in '0'..'9') return null
        val whole = digits.substringBefore('.')
        val fraction = digits.substringAfter('.', missingDelimiterValue = "").trimEnd('0')
        if ((whole + fraction).any { it !in '0'..'9' } || (whole + fraction).trimStart('0').length > EXACT_DIGITS) {
            return Constant.Unknown
        }
        val magnitude = whole.fold(0L) { value, digit -> value * 10 + (digit - '0') }
        val half = fraction.firstOrNull()?.let { it >= '5' } ?: false
        // Half up on the signed value: a positive half rounds away from zero, a negative one towards it.
        val rounded = if (!negative) magnitude + if (half) 1 else 0
        else -magnitude - if (half && fraction != "5") 1 else 0
        return Constant.Known(rounded.toInt())
    }

    private fun referencesOf(op: OperationShape) = within(op.at, op.end)

    private fun allIterate(op: OperationShape, judged: Judged) =
        referencesOf(op).let { refs -> refs.any { iterates(it, judged) } && refs.all { iterates(it, judged) } }

    private fun mixed(op: OperationShape, judged: Judged) =
        referencesOf(op).let { refs -> refs.any { iterates(it, judged) } && refs.any { !iterates(it, judged) } }
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
    if (declarationAt(target)?.declaredKind != DeclaredKind.FIELD) return null
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    val bodies = recognition.sites.filterIsInstance<SyntaxSite.FilterExit>().map { it.entry.end until it.end }
    val targetGroup = target.substringBeforeLast('/')
    val references = mutableListOf<GuardReference>()
    val marked = mutableListOf<MarkedLevels>()
    for (entity in recognition.entities) {
        if (entity.path.first().text == "$" || entity.indexKey || entity.indexed) return null
        val inBody = bodies.any { entity.site.start in it }
        if (inBody != entity.inFilter) return null
        if (inBody) continue
        references += guardReferenceOf(entity, group, element) ?: return null
        marked += markedLevelsOf(normalizedPath(entity.path), group, targetGroup, element) ?: return null
    }
    // A12's two passes (KF376), read where the filtered-entity family reads them: only once some reference
    // iterates jointly does every reference's keyed level on the error field's path become parallel too.
    if (marked.any { it.joint.isNotEmpty() }) {
        references.zip(marked).forEach { (reference, levels) ->
            reference.levels.filter { it.mark == LevelMark.ITERATED && it.group in levels.marked }
                .forEach { it.mark = LevelMark.PARALLEL }
        }
    }
    if (references.any { r -> r.levels.any { it.mark == LevelMark.ITERATED && !target.isAtOrBelow(it.group) } }) return null
    // A12 decides the error-field binding (KF417) before this law, so where no reference naming the error
    // field iterates while another does, that earlier class may answer and this reading has no verdict.
    if (references.any { it.iterates } && references.none { it.iterates && target.isAtOrBelow(it.path) }) return null
    val identities = CONDITION_OPERATOR_KEYWORDS.getValue(conditionLanguage).entries.associate { (id, spelling) -> spelling to id }
    return IterationGuard(element, target, tree, references, recognition.operations, identities, execution.carrier.text)
}

private fun TrackedFactBase.guardReferenceOf(
    entity: RecognizedEntity,
    declaringGroup: String,
    element: TrackedDeclaration,
): GuardReference? {
    val written = normalizedPath(entity.path)
    if (isRuleContext(written, conditionLanguage)) return null
    val resolved = resolveEntity(declaringGroup, written.replace("*", ""), element.origin) as? Reference.Resolved
        ?: return null
    val starred = starredLevelsOf(written, declaringGroup, element) ?: return null
    val declaration = declarationAt(resolved.path) ?: return null
    val segments = resolved.path.split('/').drop(1)
    val groups = segments.indices.map { "/" + segments.take(it + 1).joinToString("/") }
        .let { if (declaration.declaredKind == DeclaredKind.FIELD) it.dropLast(1) else it }
    if (declaration.declaredKind == DeclaredKind.GROUP && groups.size == 1) return null
    val levels = groups.map { path ->
        val group = declarationAt(path) ?: return null
        val repeats = (group.body?.get("repeatability") as? JsonPrimitive)?.boundRepetitionCount() ?: 1
        val mark = when {
            path in starred -> LevelMark.STARRED
            repeats > 1 -> LevelMark.ITERATED
            else -> LevelMark.FIXED
        }
        GuardLevel(path, mark, repeats, indexFieldNameOf(group) != null)
    }
    val fields = fieldsUnder(declaration).orEmpty().sumOf { field ->
        field.path.removePrefix("${resolved.path}/").split('/').dropLast(1)
            .runningReduce { above, segment -> "$above/$segment" }
            .fold(1L) { n, inner ->
                val repeats = (declarationAt("${resolved.path}/$inner")?.body?.get("repeatability") as? JsonPrimitive)
                    ?.boundRepetitionCount() ?: 1
                n * repeats
            }
    }
    return GuardReference(entity.site.start, resolved.path, declaration.declaredKind == DeclaredKind.FIELD, levels, fields)
}


/** Significant digits a double carries exactly, so a half is never produced by the parse itself. */
private const val EXACT_DIGITS = 15

private val JUDGED = setOf(DeclaredKind.RULE, DeclaredKind.COMPUTATION)

private val NEGATIVE_PRESENCE = setOf("FieldNotFilled", "NoFieldFilled", "NotAllFieldsFilled", "NotExactlyOneFieldFilled",
    "GroupNotFilled", "NoGroupFilled", "NotAllGroupsFilled")
/** The conditions that list fields, where a named group stands for the fields beneath it. */
private val FIELD_LISTS = setOf("FieldFilled", "AllFieldsFilled", "AtLeastOneFieldFilled", "FieldsNotCollectivelyFilled",
    "MoreThanOneFieldFilled", "FieldValuesNotUnique", "FieldNotFilled", "NoFieldFilled", "NotAllFieldsFilled",
    "NotExactlyOneFieldFilled")
private val POSITIVE_PRESENCE = setOf("AtLeastOneFieldFilled", "FieldsNotCollectivelyFilled", "AtLeastOneGroupFilled",
    "GroupsNotCollectivelyFilled")
private val ENTITY_LIST_KINDS = mapOf(
    "NumberOfFilledFields" to OperationKind.COUNT, "NumberOfFilledGroups" to OperationKind.COUNT,
    "NumberOfDifferentValues" to OperationKind.COUNT, "NumberOfValueInFields" to OperationKind.COUNT_OF_VALUE,
    "Sum" to OperationKind.AMOUNT, "MinValue" to OperationKind.AMOUNT, "MaxValue" to OperationKind.AMOUNT,
    "FirstFilledValue" to OperationKind.AMOUNT,
)
private val ENTITY_LIST_OPERATIONS = ENTITY_LIST_KINDS.keys
private val OPERATION_LISTS = setOf("Max", "Min", "Abs", "RoundAccounting", "RoundUp", "RoundDown")
private val FIELD_OPERATIONS = setOf("Length", "RoundAccountingValue", "RoundUpValue", "RoundDownValue", "AbsValue",
    "RangeAsNumber", "FieldValueAsNumber", "RangeAsString", "FieldValueAsString", "ValueAsDate")
private val DATE_DIFFERENCES = setOf("DifferenceInSeconds", "DifferenceInMinutes", "DifferenceInHours", "DifferenceInDays",
    "DifferenceInMonths", "DifferenceInYears")
private val DATE_EXTRACTIONS = setOf("DayFromDate", "MonthFromDate", "YearFromDate", "QuarterFromDate", "HoursFromTime",
    "MinutesFromTime", "SecondsFromTime", "DateFromDateTime", "TimeFromDateTime")
private val COMPARISON_SYMBOLS = setOf("==", "!=", "<", ">", "<=", ">=")
/** The operator as read from the right-hand side. */
private val FACING = mapOf("==" to "==", "!=" to "!=", "<" to ">", ">" to "<", "<=" to ">=", ">=" to "<=")
/** Comparisons below a bound, which an operation counting filled rows cannot meet without one. */
private val BELOW = setOf("<", "<=", "!=")
/** Comparisons an amount meets at zero. */
private val AT_ZERO = setOf("==", "<=", ">=")
private val IN_WORDS = setOf("In", "IN", "in")
