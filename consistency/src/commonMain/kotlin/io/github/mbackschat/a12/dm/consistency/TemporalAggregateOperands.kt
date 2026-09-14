package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The two gates an entity-list aggregate asks of each temporal operand: its PRECISION, then its COMPONENTS.
 *
 * `MaxValue`, `MinValue` and `NumberOfDifferentValues` type their operand list from its LEADING field, and a
 * temporal leader sends the whole list through `CheckEntityListenUtils.checkOpTypesAreComparableDatetimeType`.
 * That method compares each later operand against the first on the components its declared FORMAT spells —
 * never on the spelling and never on the field's kind — and draws `MVK_DATEFORMATS_NOT_COMPATIBLE` on the
 * first that disagrees ([KERNEL-FINDINGS KF193], [KF263], [KF271];
 * `rulekit.validate.laws.TemporalAggregateComponentSetLawsTest`).
 *
 * **The component set is read off the format STRING, letter by letter** — `y M d H m s`, each by plain
 * containment — so `yyyy-MM-dd` and `dd.MM.yyyy` are one set and are admitted together, while `yyyy-MM` and
 * `yyyy-MM-dd` are two and are refused. That is why this family cannot be written as a kind comparison: a
 * TIME field declared `yyyy-MM-dd` sits with the dates, and a `FieldValuesNotUnique` over the same pair is
 * refused under a different code by a gate that really does read the spelling.
 *
 * **A declared model-level base year SUPPLEMENTS the year**, adding YEAR to both sets before they are
 * compared, so `MM` joins `yyyy-MM` and `MM-dd` joins a complete date — and neither joins the year-only
 * `yyyy`, the control that makes it a supplement rather than a disabled gate.
 *
 * **Silent wherever the chain answers ahead of this gate**, each silence another family's or A12's own:
 *
 * | the operand list | who answers |
 * |---|---|
 * | a leading operand that is not temporal | the numeric arm — `MVK_NOT_SORTABLE` / `MVK_NO_NUMBER` |
 * | a format outside the derived vocabulary | nobody — an undiscovered format is not a defect |
 *
 * **A LATER operand that is not temporal is the chain's first class**, `MVK_DATE_AND_NONDATE`, asked of each
 * operand before that operand's precision and components ([KERNEL-FINDINGS KF389],
 * `rulekit.validate.laws.DateAggregateBaseTypeGateLawsTest`). It is reported only where the declared type is
 * one this reader is certain is not temporal, never merely where a temporal format is absent — a
 * `DateTimeType` declaring no format takes its default and is a date. The LEADING operand is a different
 * question entirely: it decides which arm types the list at all, and a non-temporal leader is answered by
 * that arm — `MVK_NOT_SORTABLE` on the extrema, `MVK_STRING_ENUM_AND_NON_STRING_ENUM` on the count.
 *
 * **A PARTIALLY KNOWN date is the chain's other reported class**, and it is independent of the components:
 * the three carriers here compare a DECODED date, which a partial has not, so every precision is refused
 * `MVK_PARTIAL_DATE_NOT_ALLOWED` whatever the format says — while `FieldValuesNotUnique` and
 * `FirstFilledValue`, which compare the stored text, admit all three ([KERNEL-FINDINGS KF260],
 * `rulekit.validate.laws.PartialDatePrecisionOperandLawsTest`). It is asked of the LEADING operand too,
 * unlike the component comparison, which has no leader to compare against.
 *
 * The walk reproduces A12's INTERLEAVING rather than running the gates in phases: each operand is asked for
 * its temporal declaration, then for its precision, then compared — so a partial precision on a LATER operand
 * than the first component mismatch does not pre-empt the mismatch, exactly as A12 orders it. The vocabulary
 * limit is this reader's and not A12's, so an unreadable format stops the COMPARISON and not the walk: a
 * partial precision further down the list is still reported.
 *
 * **`Min` and `Max` carry the same gate and are deliberately out of scope.** Their operands are OPERATIONS,
 * not entity references, so the leading operand's type can be a computed expression this reader does not
 * type; judging the sub-case where every operand happens to be a bare field would rest the family on a shape
 * the reader cannot tell apart from the rest. `Sum` is not a carrier at all — it refuses a temporal operand
 * outright with its own code.
 */
fun PreparedModel.temporalAggregateOperandFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val carriers = ENTITY_LIST_AGGREGATES.mapNotNull { keywords[it] }.toSet()
    if (carriers.isEmpty()) {
        return emptyList()
    }
    return elements.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val declaringGroup = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).flatMap { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .filter { it.operator in carriers }
                .mapNotNull { call ->
                    operandDefect(call, declaringGroup, element)
                        ?.let { operandFinding(it, call, element, member) }
                }
        }
    }
}

/** The three carriers whose operands are an ENTITY LIST, named by their catalog ids. */
private val ENTITY_LIST_AGGREGATES = listOf("MaxValue", "MinValue", "NumberOfDifferentValues")

private data class OperandDefect(val code: String, val first: TemporalOperand?, val other: TemporalOperand)

private data class TemporalOperand(val path: String, val format: String, val components: Set<Component>)

/**
 * The first operand whose components disagree with the leader's, in A12's own order.
 *
 * The list is taken whole or not at all: an operand this reader cannot reduce to one resolved entity is one
 * whose components it cannot know, and comparing the rest would judge a shorter list than A12 judged.
 */
private fun PreparedModel.operandDefect(
    call: ConditionCall,
    declaringGroup: String,
    element: DeclaredElement,
): OperandDefect? {
    val fields = call.operands.flatMap { operand ->
        val written = operand.path ?: return null
        val resolved = resolveEntity(declaringGroup, written.replace("*", ""), element.origin)
        expandToFields((resolved as? Reference.Resolved)?.path ?: return null) ?: return null
    }
    var first: TemporalOperand? = null
    var comparable = true
    fields.forEach { field ->
        // A12 asks the BASE TYPE first, of each operand in turn, before that operand's precision or its
        // components — so a non-temporal operand answers here whenever it comes before the defect that would
        // otherwise be reported, and is out-ranked whenever it comes after.
        if (first != null && field.declaredFieldType() in PLAINLY_NON_TEMPORAL_TYPES) {
            return OperandDefect(NOT_TEMPORAL, first, TemporalOperand(field.path, "", emptySet()))
        }
        // Absence of a declared format is NOT absence of a temporal type: a `DateTimeType` may declare none
        // and take its default, so this bail is "nothing to compare", never "this operand is not a date".
        val format = declaredTemporalFormatOf(field) ?: return null
        if (declaresAPartialPrecision(field)) {
            return OperandDefect(PARTIAL_REFUSED, null, TemporalOperand(field.path, format, emptySet()))
        }
        if (format !in ADMITTED_FORMATS) {
            comparable = false
            return@forEach
        }
        val operand = TemporalOperand(field.path, format, componentsOf(format))
        val leader = first
        if (leader == null) {
            first = operand
        } else if (comparable && !compatible(leader.components, operand.components, hasBaseYear)) {
            return OperandDefect(INCOMPATIBLE, leader, operand)
        }
    }
    return null
}

private const val NOT_TEMPORAL = "MVK_DATE_AND_NONDATE"

private const val PARTIAL_REFUSED = "MVK_PARTIAL_DATE_NOT_ALLOWED"

private const val INCOMPATIBLE = "MVK_DATEFORMATS_NOT_COMPATIBLE"

/**
 * The fields A12's `expandToFieldList` would take for one operand — the field itself, or every field under a
 * group, in declaration order.
 *
 * Null where the operand names a group with no field beneath it, which A12 refuses under its own class.
 */
private fun PreparedModel.expandToFields(path: String): List<DeclaredElement>? {
    val target = elements.firstOrNull { it.path == path } ?: return null
    if (target.declaredKind == DeclaredKind.FIELD) {
        return listOf(target)
    }
    return elements
        .filter { it.declaredKind == DeclaredKind.FIELD && it.path.startsWith("${target.path}/") }
        .takeIf { it.isNotEmpty() }
}

private fun declaresAPartialPrecision(field: DeclaredElement): Boolean {
    val declared = field.body?.get("fieldType") as? JsonObject ?: return false
    val body = declared["DateType"] as? JsonObject ?: return false
    return (body["datePrecision"] as? JsonPrimitive)?.contentOrNull in PARTIAL_PRECISIONS
}

/**
 * A12's two predicates, both of them, in the order `checkOpTypesAreComparableDatetimeType` reads them.
 *
 * The kernel's own comment calls the first one weaker and probably redundant, and it is redundant on every
 * shape but one: with a base year the sets may agree after supplementation while one of them carries a date
 * component and the other does not, and the weaker predicate refuses that pair. Whether the format
 * vocabulary can build it is not claimed here — it is transcribed because A12 runs it, not because a case
 * was found.
 */
private fun compatible(first: Set<Component>, other: Set<Component>, hasBaseYear: Boolean): Boolean {
    if (!hasBaseYear && first.contains(Component.YEAR) != other.contains(Component.YEAR)) {
        return false
    }
    if (namesADate(first) != namesADate(other)) {
        return false
    }
    return supplemented(first, hasBaseYear) == supplemented(other, hasBaseYear)
}

private fun operandFinding(
    defect: OperandDefect,
    call: ConditionCall,
    element: DeclaredElement,
    member: String,
): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    defect.code,
    if (defect.first == null) {
        "in `$member`: `${call.operator}` compares decoded dates and `${defect.other.path}` declares a " +
            "PARTIAL precision, which leaves a stored value with no date to decode — compare the stored " +
            "text with `FieldValuesNotUnique` instead, or drop the precision from the declaration"
    } else {
        "in `$member`: `${call.operator}` compares `${defect.first.path}` declared `${defect.first.format}` " +
            "with `${defect.other.path}` declared `${defect.other.format}`, and the two spell different " +
            "date components — A12 compares the components rather than the spelling, so give both operands " +
            "formats that name the same ones"
    },
    element.path,
)
