package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.Token
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.callShapes

/**
 * What each SLOT of a `Date` or `Time` construction admits, on the two arms that need no declaration.
 *
 * A slot takes a quoted CONSTANT, a date/time EXTRACT operator, or an ENTITY, and the arm is what picks the
 * class ([KERNEL-FINDINGS KF373], `rulekit.validate.laws.DateConstructionSlotLawsTest`). A12 walks the slots
 * left to right and answers at the first defective one, whichever arm that slot happens to carry — so this
 * family walks slots, never gates.
 *
 * | the slot carries | class |
 * |---|---|
 * | a constant outside its own range, or of the wrong WIDTH | `MVK_WRONG_DATE_FORMAT_FOR_OP` |
 * | an extract operator of the construction's OWN family, in the wrong slot | `MVK_INVALID_DATE_OR_TIME_EXTRACT_OP` |
 * | any other extract operator | `MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED` |
 *
 * **`QuarterFromDate` is why the last two rows are not one rule.** It is a DATE extract operator and no slot
 * expects it, so a family keying on "an operator of this construction's family" would name the wrong-slot
 * class where A12 names the other — measured, because the kernel's own switch lists the three slot operators
 * by name rather than asking which family an operator belongs to.
 *
 * **The ENTITY arm is deliberately not judged here.** It reads the field's declared type against the slot's
 * width and range and has its own gate chain (`TemporalComponentDeclarationLawsTest`); nothing in this family
 * touches a declaration.
 *
 * **Silent on a `Date` that does not carry exactly three components.** Two is the year-less shape, admitted
 * only when the model declares a base year (`MVK_MISSING_YEAR`, model data this does not read), and four is
 * the split-year H-model shape whose own class is erased in A12 models.
 */
fun PreparedModel.dateConstructionSlotFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val constructions = TemporalConstruction.entries.mapNotNull { c -> keywords[c.id]?.let { it to c } }.toMap()
    if (constructions.isEmpty()) {
        return emptyList()
    }
    val extractOps = EXTRACT_OPS.mapNotNull { (id, op) -> keywords[id]?.let { it to op } }.toMap()
    return elements.mapNotNull { element ->
        val body = element.body ?: return@mapNotNull null
        conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
            callShapes(text, conditionLanguage, syntaxEntryOf(member))
                .firstNotNullOfOrNull { shape ->
                    val construction = constructions[shape.operator] ?: return@firstNotNullOfOrNull null
                    construction.firstDefectiveSlot(shape.operands, extractOps)
                        ?.let { slotFinding(it, shape.operator, element, member) }
                }
        }
    }
}

/** One slot's demands: the constant range it accepts, its width, and the extract operator it expects. */
private data class Slot(
    val name: String,
    val minInclusive: Int,
    val maxExclusive: Int,
    val width: Int?,
    val expects: String,
)

/**
 * The two constructions, each with its slot list and the extract-operator family its own slots draw from.
 *
 * `Date` is listed with its three-component shape alone; the other two arities are other laws' and the
 * walk refuses to judge them.
 */
private enum class TemporalConstruction(val id: String, val slots: List<Slot>, val ownFamily: Set<String>) {
    DATE(
        "Date",
        listOf(
            Slot("day", 1, 32, null, "DayFromDate"),
            Slot("month", 1, 13, null, "MonthFromDate"),
            Slot("year", 1800, 2200, 4, "YearFromDate"),
        ),
        setOf("DayFromDate", "MonthFromDate", "YearFromDate"),
    ),

    /** A trailing component may be omitted, so any prefix of the three is a shape A12 accepts. */
    TIME(
        "Time",
        listOf(
            Slot("hour", 0, 24, null, "HoursFromTime"),
            Slot("minute", 0, 60, null, "MinutesFromTime"),
            Slot("second", 0, 60, null, "SecondsFromTime"),
        ),
        setOf("HoursFromTime", "MinutesFromTime", "SecondsFromTime"),
    ),
    ;

    fun admitsArity(count: Int): Boolean = when (this) {
        DATE -> count == slots.size
        TIME -> count in 1..slots.size
    }

    fun firstDefectiveSlot(
        operands: List<List<Token>>,
        extractOps: Map<String, String>,
    ): SlotDefect? {
        if (!admitsArity(operands.size)) {
            return null
        }
        operands.forEachIndexed { at, operand ->
            val slot = slots[at]
            val defect = when (val arm = armOf(operand, extractOps)) {
                is SlotArm.Constant -> slot.constantDefect(arm.digits)
                is SlotArm.Extract -> slot.extractDefect(arm.op, ownFamily)
                SlotArm.Unjudged -> null
            }
            if (defect != null) {
                return defect
            }
        }
        return null
    }
}

/** The catalog ids of every operator A12's `datumExtractOp` rule admits, keyed for spelling lookup. */
private val EXTRACT_OPS = listOf(
    "DayFromDate", "MonthFromDate", "YearFromDate", "QuarterFromDate",
    "HoursFromTime", "MinutesFromTime", "SecondsFromTime",
).associateWith { it }

private sealed interface SlotArm {
    /** A quoted constant, already unquoted. */
    data class Constant(val digits: String) : SlotArm

    /** An extract operator, by catalog id. */
    data class Extract(val op: String) : SlotArm

    /** An entity, or any shape this reader did not reduce to one of the two arms above. */
    data object Unjudged : SlotArm
}

private data class SlotDefect(val code: String, val slot: String, val wrote: String, val expected: String)

private fun armOf(operand: List<Token>, extractOps: Map<String, String>): SlotArm {
    val head = operand.firstOrNull() ?: return SlotArm.Unjudged
    if (operand.size == 1 && head.kind == TokenKind.STRING) {
        return SlotArm.Constant(head.text.trim('"').replace("\"\"", "\""))
    }
    if (head.kind == TokenKind.NAME && operand.getOrNull(1)?.text == "(") {
        extractOps[head.text]?.let { return SlotArm.Extract(it) }
    }
    return SlotArm.Unjudged
}

/**
 * Whether a constant fills this slot, by A12's own test: all digits, the slot's width where it has one, and
 * inside the half-open range.
 *
 * A very long run of digits is left alone rather than compared: A12 parses it as an `int` and this reader has
 * no measurement for what an overflowing one does, so silence is the safe answer.
 */
private fun Slot.constantDefect(digits: String): SlotDefect? {
    if (digits.length > MAX_JUDGED_DIGITS) {
        return null
    }
    val value = digits.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toInt()
    val fits = value != null && (width == null || digits.length == width) &&
        value >= minInclusive && value < maxExclusive
    return if (fits) {
        null
    } else {
        SlotDefect("MVK_WRONG_DATE_FORMAT_FOR_OP", name, "\"$digits\"", rangeText())
    }
}

private const val MAX_JUDGED_DIGITS = 9

private fun Slot.rangeText(): String {
    val range = "a number from $minInclusive to ${maxExclusive - 1}"
    return if (width == null) range else "$range, written in exactly $width digits"
}

/**
 * Which class a wrongly-placed extract operator draws.
 *
 * The second arm is not "an operator of another family": A12's switch lists the three operators its own slots
 * expect and treats every other one, `QuarterFromDate` included, as not allowed in the construction at all.
 */
private fun Slot.extractDefect(op: String, ownFamily: Set<String>): SlotDefect? = when {
    op == expects -> null
    op in ownFamily -> SlotDefect("MVK_INVALID_DATE_OR_TIME_EXTRACT_OP", name, op, expects)
    else -> SlotDefect("MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED", name, op, expects)
}

private fun slotFinding(
    defect: SlotDefect,
    construction: String,
    element: DeclaredElement,
    member: String,
): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    defect.code,
    "in `$member`: the ${defect.slot} of `$construction` is written as `${defect.wrote}`, where A12 takes " +
        "${defect.expected}",
    element.path,
)
