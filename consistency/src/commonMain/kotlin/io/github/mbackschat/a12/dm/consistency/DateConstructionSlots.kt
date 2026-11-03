package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CallShape
import io.github.mbackschat.a12.dm.consistency.syntax.DATE_EXTRACT_OPERATIONS
import io.github.mbackschat.a12.dm.consistency.syntax.OPERATOR_SPELLINGS
import io.github.mbackschat.a12.dm.consistency.syntax.OUTER_MARKER
import io.github.mbackschat.a12.dm.consistency.syntax.OperatorId
import io.github.mbackschat.a12.dm.consistency.syntax.Token
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.decodeAuthoredStringLiteral
import io.github.mbackschat.a12.dm.consistency.syntax.inAFilterBody
import io.github.mbackschat.a12.dm.consistency.syntax.isName
import io.github.mbackschat.a12.dm.consistency.syntax.normalizedPath
import io.github.mbackschat.a12.dm.consistency.syntax.textOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What each SLOT of a `Date` or `Time` construction admits, on all three of its arms.
 *
 * A slot takes a quoted CONSTANT, a date/time EXTRACT operator, or an ENTITY, and the arm is what picks the
 * class ([KERNEL-FINDINGS KF373], `rulekit.validate.laws.DateConstructionSlotLawsTest` and
 * `rulekit.validate.laws.TemporalComponentDeclarationLawsTest`). A12 walks the slots left to right and
 * answers at the first defective one, whichever arm that slot happens to carry — so this family walks slots,
 * never gates.
 *
 * | the slot carries | class |
 * |---|---|
 * | a constant outside its own range, or of the wrong WIDTH | `MVK_WRONG_DATE_FORMAT_FOR_OP` |
 * | an extract operator of the construction's OWN family, in the wrong slot | `MVK_INVALID_DATE_OR_TIME_EXTRACT_OP` |
 * | any other extract operator | `MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED` |
 * | a string field whose pattern is not one A12 reads as digits | `MVK_NO_DIGIT_PATTERN` |
 * | a field whose declaration does not fit the slot | `MVK_WRONG_DATE_FORMAT_FOR_OP` |
 * | a starred field | `MVK_NO_WILDCARDS_ALLOWED` ([KF532](../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf532)) |
 * | a group, starred or not | `MVK_NO_GROUPS_ALLOWED` ([KF598](../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf598)) |
 *
 * **The group gate comes before the star gate**, so a starred GROUP draws the group class, not the star one:
 * `checkParamIfEntity` asks "a field?" and only then "no star?" (kernel 30.8.1, read 2026-10-22;
 * `adapter.laws.CheckerGroupAndStarOperandAgreementTest`).
 *
 * **`QuarterFromDate` is why rows two and three are not one rule.** It is a DATE extract operator and no slot
 * expects it, so a family keying on "an operator of this construction's family" would name the wrong-slot
 * class where A12 names the other — measured, because the kernel's own switch lists the three slot operators
 * by name rather than asking which family an operator belongs to.
 *
 * **The two ENTITY rows are ordered, not alternative.** The pattern gate fires for a `StringType` before any
 * width is compared, so a two-character field patterned `[0123456789]+` draws the digit-pattern class even
 * though its width is right — and the same field patterned `[0-9]+` at the year slot draws the width class.
 *
 * **A slot's constant range and its field bound are different numbers**, which is why [Slot] carries them
 * apart: a constant year must lie in 1800..2199, while a field year may declare any maximum from 1000 to
 * 9999. Collapsing the two would refuse `maxValue: 9999`, a declaration A12 admits.
 *
 * **Silent where A12's REFERENCE READER answers first.** A component reached by crossing a repetition the
 * rule's own group does not sit in draws `MVK_NO_WILDCARD` and never reaches the slot gate, measured with
 * both controls — so that shape belongs to [CheckFamily.OPERAND_REPETITION_SCOPE] alone, and answering here
 * too would put two findings on a declaration A12 answers once.
 *
 * **The two-component and SPLIT-YEAR shapes are read too, each for what is measured about it.** Two is the
 * year-less form, whose missing year is `MVK_MISSING_YEAR` — model data this does not read — while its
 * present slots are still this walk's. Four is the century/short-year form, and the ledger had it as an
 * H-model shape with no authoring surface: the grammar carries no `isHModel()` predicate on that arity and
 * neither does the raiser, so an ordinary A12 model reaches it and does
 * ([KF423](../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf423)). Its century and short year are two slots with gates of their own
 * ([SPLIT_YEAR_SLOTS]).
 */
internal fun TrackedFactBase.dateConstructionContributions(element: TrackedDeclaration): List<ConditionFinding> {
    val execution = conditionExecution(element) ?: return emptyList()
    val keywords = OPERATOR_SPELLINGS[conditionLanguage] ?: return emptyList()
    val constructions = TemporalConstruction.entries.mapNotNull { c -> keywords[c.id]?.let { it to c } }.toMap()
    val extractOps = DATE_EXTRACT_OPERATIONS.mapNotNull { id -> keywords[id]?.let { it to id } }.toMap()
    return callContributions(element, execution) { member, call ->
        val construction = constructions[call.operator] ?: return@callContributions null
        val shape = execution.callsByStart.getValue(call.at)
        construction.firstDefectiveSlot(this, element, shape, extractOps)
            ?.let { slotFinding(it, shape.operator, element, member, shape.spanIn(member)) }
    }
}

/**
 * What a `Date` construction's slots say to a reader whose own question comes AFTER the slot gates.
 *
 * The date-validity condition asks one thing of a construction and A12 asks it last, so its family must
 * decline wherever a slot gate answers and must use the same reading of "this slot names an entity" that
 * the gates use. Both are read off the walk here rather than restated there — one live statement of the
 * slot demands, per [validityConditionOperandFindings]' only caller.
 *
 * @property aSlotAnswersFirst whether some slot already fails its own gate.
 * @property namesAnEntity whether some slot is a bare entity path — A12's `entitySpec() != null`, which an
 *     EXTRACT operator does NOT satisfy even though it reads a field inside itself.
 */
internal data class DateConstructionReading(val aSlotAnswersFirst: Boolean, val namesAnEntity: Boolean)

/**
 * Whether a `Date` construction's PRESENT slots already fail a gate of their own, whatever its arity.
 *
 * The year law of a two-parameter construction is asked after the day and month gates, so its family has to
 * know whether those answered — and the three-slot arity gate that [readDateConstruction] applies would hide
 * exactly the shape that law is about.
 */
internal fun TrackedFactBase.aDateSlotAnswersFirst(
    shape: CallShape,
    element: TrackedDeclaration,
): Boolean {
    val keywords = OPERATOR_SPELLINGS[conditionLanguage] ?: return false
    val extractOps = DATE_EXTRACT_OPERATIONS.mapNotNull { id -> keywords[id]?.let { it to id } }.toMap()
    return TemporalConstruction.DATE.defectAmongSlots(this, element, shape, extractOps) != null
}

/** Read the `Date` construction [shape]; null where this reader does not judge that shape. */
internal fun TrackedFactBase.readDateConstruction(
    shape: CallShape,
    element: TrackedDeclaration,
): DateConstructionReading? {
    val operands = shape.operands
    val keywords = OPERATOR_SPELLINGS[conditionLanguage] ?: return null
    val extractOps = DATE_EXTRACT_OPERATIONS.mapNotNull { id -> keywords[id]?.let { it to id } }.toMap()
    val construction = TemporalConstruction.DATE
    // The COMPLETE shape only. A year-less construction's own law is `MVK_MISSING_YEAR`, asked before the
    // validity question and owned by [baseYearContributions]; reading it here would put a second
    // finding — and the wrong class — on a model A12 refuses once.
    if (operands.size != construction.slots.size) {
        return null
    }
    return DateConstructionReading(
        aSlotAnswersFirst = construction.firstDefectiveSlot(this, element, shape, extractOps) != null,
        namesAnEntity = operands.any { armIn(shape, it, element, extractOps).namesAnEntity },
    )
}

/**
 * One slot's demands, split the way A12 splits them: the two arms are asked different questions.
 *
 * Keeping [constant] and [entity] apart is what stops the complete year from carrying one number for both:
 * a constant year has to lie in 1800..2199 and a field year may be bounded anywhere up to 9999, so a slot
 * holding a single maximum would refuse `maxValue: 9999` — a declaration A12 admits.
 *
 * @property expects the extract operator this slot takes; null for the split year's two slots, which take none.
 */
private data class Slot(
    val name: String,
    val constant: ConstantDemand,
    val entity: EntityDemand,
    val expects: OperatorId?,
)

/**
 * What a quoted constant has to be.
 *
 * @param width how many digits it must carry — absent where the slot imposes none, which is every slot but
 *     the complete year (`Date("1", "2", "2024")` is admitted).
 */
private data class ConstantDemand(val minInclusive: Int, val maxExclusive: Int, val width: Int?)

/**
 * What a field component has to declare — either the length or the maximum, never both.
 *
 * @param flexibleFloor the lowest declared `maxValue` A12 still takes, where it accepts a model bounding the
 *     component more tightly than the calendar does (`isMaxValueFlexible`); absent where only [maxValue] will
 *     do, which is every slot this family judges except the complete year.
 */
private data class EntityDemand(val maxLength: Int, val maxValue: Int, val flexibleFloor: Int?)

/** A range A12 checks without a width, which it spells as an undefined expected length. */
private fun constantRange(minInclusive: Int, maxExclusive: Int) =
    ConstantDemand(minInclusive, maxExclusive, width = null)

/** A two-character field component whose declared maximum, if it carries one, must be the slot's own. */
private fun boundedBy(maxValue: Int) = EntityDemand(maxLength = 2, maxValue = maxValue, flexibleFloor = null)

/**
 * The two constructions, each with its slot list and the extract-operator family its own slots draw from.
 *
 * `Date` is listed with the three slots it can have; a two-parameter construction is judged on the two it
 * wrote, its missing year being a law of its own, and the four-parameter shape swaps the year for [SPLIT_YEAR_SLOTS].
 */
private enum class TemporalConstruction(val id: OperatorId, val slots: List<Slot>, val ownFamily: Set<OperatorId>) {
    DATE(
        OperatorId.DATE,
        listOf(
            Slot("day", constantRange(1, 32), boundedBy(31), OperatorId.DAY_FROM_DATE),
            Slot("month", constantRange(1, 13), boundedBy(12), OperatorId.MONTH_FROM_DATE),
            Slot(
                "year",
                ConstantDemand(minInclusive = 1800, maxExclusive = 2200, width = 4),
                EntityDemand(maxLength = 4, maxValue = 9999, flexibleFloor = 1000),
                OperatorId.YEAR_FROM_DATE,
            ),
        ),
        setOf(OperatorId.DAY_FROM_DATE, OperatorId.MONTH_FROM_DATE, OperatorId.YEAR_FROM_DATE),
    ),

    /** A trailing component may be omitted, so any prefix of the three is a shape A12 accepts. */
    TIME(
        OperatorId.TIME,
        listOf(
            Slot("hour", constantRange(0, 24), boundedBy(23), OperatorId.HOURS_FROM_TIME),
            Slot("minute", constantRange(0, 60), boundedBy(59), OperatorId.MINUTES_FROM_TIME),
            Slot("second", constantRange(0, 60), boundedBy(59), OperatorId.SECONDS_FROM_TIME),
        ),
        setOf(OperatorId.HOURS_FROM_TIME, OperatorId.MINUTES_FROM_TIME, OperatorId.SECONDS_FROM_TIME),
    ),
    ;

    /**
     * Which written arities this walk judges.
     *
     * A12 runs the day and month gates whatever the arity, so the two-parameter `Date` is judged here too —
     * its MISSING year is a law of its own ([baseYearContributions]) and its present slots are still
     * this walk's. The four-parameter split-year form is admitted here as well: it is reachable in an A12
     * model, and its year slots are [SPLIT_YEAR_SLOTS].
     */
    fun admitsArity(count: Int): Boolean = when (this) {
        DATE -> count in 2..SPLIT_YEAR_ARITY
        TIME -> count in 1..slots.size
    }

    fun firstDefectiveSlot(
        model: TrackedFactBase,
        element: TrackedDeclaration,
        shape: CallShape,
        extractOps: Map<String, OperatorId>,
    ): SlotDefect? =
        if (admitsArity(shape.operands.size)) defectAmongSlots(model, element, shape, extractOps) else null

    /**
     * The same walk over whatever slots were written, arity unjudged.
     *
     * A12 runs the day and month gates before it asks about the year at all, so a shape this construction
     * does not admit still has those two gates answered — which is what the base-year law needs to know
     * ([baseYearContributions]) and what [firstDefectiveSlot] deliberately declines to look at.
     */
    fun defectAmongSlots(
        model: TrackedFactBase,
        element: TrackedDeclaration,
        shape: CallShape,
        extractOps: Map<String, OperatorId>,
    ): SlotDefect? {
        val operands = shape.operands
        val inAFilterBody = shape.inAFilterBody(model.conditionLanguage)
        // The split-year shape's last two operands are a CENTURY and a SHORT YEAR, with gates of their own.
        val walked = if (this == DATE && operands.size == SPLIT_YEAR_ARITY) slots.take(2) + SPLIT_YEAR_SLOTS else slots
        operands.take(walked.size).forEachIndexed { at, operand ->
            val slot = walked[at]
            val arm = model.armIn(shape, operand, element, extractOps)
            val bodyAddressed = bodyAddresses(inAFilterBody, arm.outerMarked)
            val defect = model.groupSlotDefect(slot, arm, element, bodyAddressed) ?: when (arm) {
                is SlotArm.Constant -> slot.constantDefect(arm.digits)
                is SlotArm.Extract -> slot.extractDefect(arm.op, ownFamily)
                is SlotArm.Entity -> slot.entityDefect(model, element, arm.path, keyed = false, bodyAddressed)
                is SlotArm.Keyed -> arm.value?.let { slot.entityDefect(model, element, it, keyed = true, bodyAddressed) }
                is SlotArm.Starred -> SlotDefect("MVK_NO_WILDCARDS_ALLOWED", slot.name, arm.written, "a field read without a star")
                SlotArm.Unjudged -> null
            }
            if (defect != null) {
                return defect
            }
        }
        return null
    }
}

/** A `Date` written with a CENTURY and a SHORT YEAR instead of one complete year. */
private const val SPLIT_YEAR_ARITY = 4

/**
 * The split year's two slots, as `checkYearFormat(century, shortYear)` asks them (source-read; every arm measured
 * in `adapter.laws.CheckerConstructorComponentAgreementTest`,
 * [KF423](../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf423)).
 *
 * A century constant lies in 18..21 and a short year in 0..99, both written with two digits. A century field may
 * declare any `maxValue` from 10 to 99, where a short-year field must declare 99 exactly. Neither slot takes an
 * extract operator of any family, which the production routes straight to its own class.
 */
private val SPLIT_YEAR_SLOTS = listOf(
    Slot(
        "century", ConstantDemand(18, 22, width = 2), EntityDemand(2, 99, flexibleFloor = 10), expects = null,
    ),
    Slot(
        "short year", ConstantDemand(0, 100, width = 2), EntityDemand(2, 99, flexibleFloor = null), expects = null,
    ),
)


private sealed interface SlotArm {
    /** A quoted constant, already unquoted. */
    data class Constant(val digits: String) : SlotArm

    /** An extract operator, by catalog id. */
    data class Extract(val op: OperatorId) : SlotArm

    /** A bare entity path — no wildcard, no category selector, no filter. */
    data class Entity(val path: String, override val outerMarked: Boolean = false) : SlotArm

    /** An entity spec carrying a semantic index: its value where the chain vouches for the key, else null. */
    data class Keyed(val value: String?) : SlotArm

    /** A path crossing a level with a star, as written. */
    data class Starred(val written: String, override val outerMarked: Boolean = false) : SlotArm

    /** Any shape this reader did not reduce to one of the arms above. */
    data object Unjudged : SlotArm

    /** Whether the slot is A12's `entitySpec` arm, which a keyed spec is as much as a bare one. */
    val namesAnEntity: Boolean get() = this is Entity || this is Keyed

    /**
     * Whether a `$` capture marker opens the path, which a filter body reads as the enclosing rule's own
     * reference, addressed by the rule's iteration (KERNEL-FINDINGS KF599).
     */
    val outerMarked: Boolean get() = false
}

private data class SlotDefect(val code: String, val slot: String, val wrote: String, val expected: String)

/**
 * The arm [operand] of [shape] takes, read as [armOf] reads it except for a slot that IS a keyed entity spec.
 *
 * A keyed slot is an entity spec like a bare one, so it names an entity and its declaration is judged — read
 * through [operandReferenceIn], which yields its value only where the chain vouches for the key and stands
 * the slot down otherwise, the key's own class answering first ([KERNEL-FINDINGS KF476]). An extract operator
 * whose ARGUMENT is keyed stays the extract arm: A12 asks the slot, not what the operator reads, so it neither
 * names an entity for the validity gate nor escapes the slot gates (a12-kernel-lean SPEC-2026-10-06-05,
 * `adapter.laws.CheckerSelectedDateValidityAgreementTest`).
 */
private fun TrackedFactBase.armIn(
    shape: CallShape,
    operand: List<Token>,
    element: TrackedDeclaration,
    extractOps: Map<String, OperatorId>,
): SlotArm {
    val text = shape.textOf(operand)
    val arm = armOf(operand, extractOps)
    if (arm is SlotArm.Extract || !writesASemanticIndex(text)) {
        return arm
    }
    val declaringGroup = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    return SlotArm.Keyed(operandReferenceIn(text, declaringGroup, element, shape.inAFilterBody(conditionLanguage)) { null }?.reference)
}

private fun armOf(operand: List<Token>, extractOps: Map<String, OperatorId>): SlotArm {
    val head = operand.firstOrNull() ?: return SlotArm.Unjudged
    if (head.text == OUTER_MARKER) {
        return when (val marked = armOf(operand.drop(1), emptyMap())) {
            is SlotArm.Entity -> marked.copy(outerMarked = true)
            is SlotArm.Starred -> marked.copy(outerMarked = true)
            else -> SlotArm.Unjudged
        }
    }
    if (operand.size == 1 && head.kind == TokenKind.STRING) {
        return SlotArm.Constant(head.text.decodeAuthoredStringLiteral())
    }
    if (head.kind == TokenKind.NAME && operand.getOrNull(1)?.text == "(") {
        extractOps[head.text]?.let { return SlotArm.Extract(it) }
    }
    // A bare path and nothing else. A category arrow, a filter or a bracket each brings a gate of its own that
    // runs ahead of the declaration tests, so such an operand is left to the family owning it.
    if (operand.all { it.isName() || it.text in BARE_PATH_PUNCTUATION }) {
        return SlotArm.Entity(normalizedPath(operand))
    }
    // A starred path is refused as a wildcard in the slot, ahead of its declaration
    // (`adapter.laws.CheckerConstructorComponentAgreementTest`).
    if (operand.any { it.text == "*" } && operand.all { it.isName() || it.text in BARE_PATH_PUNCTUATION || it.text == "*" }) {
        return SlotArm.Starred(operand.joinToString("") { it.text })
    }
    return SlotArm.Unjudged
}

private val BARE_PATH_PUNCTUATION = setOf("/", "..")

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
    val fits = value != null && (constant.width == null || digits.length == constant.width) &&
        value >= constant.minInclusive && value < constant.maxExclusive
    return if (fits) {
        null
    } else {
        SlotDefect("MVK_WRONG_DATE_FORMAT_FOR_OP", name, "\"$digits\"", rangeText())
    }
}

private const val MAX_JUDGED_DIGITS = 9

private fun Slot.rangeText(): String {
    val range = "a number from ${constant.minInclusive} to ${constant.maxExclusive - 1}"
    return if (constant.width == null) range else "$range, written in exactly ${constant.width} digits"
}

/**
 * Which class a wrongly-placed extract operator draws.
 *
 * The second arm is not "an operator of another family": A12's switch lists the three operators its own slots
 * expect and treats every other one, `QuarterFromDate` included, as not allowed in the construction at all.
 */
private fun Slot.extractDefect(op: OperatorId, ownFamily: Set<OperatorId>): SlotDefect? = when {
    expects == null -> SlotDefect(
        "MVK_EXTRACT_OP_NOT_ALLOWED_4_DATE_CONSTRUCT_WITH_4_PARAMS", name, op.catalogId,
        "a two-digit constant or a field",
    )
    op == expects -> null
    op in ownFamily -> SlotDefect("MVK_INVALID_DATE_OR_TIME_EXTRACT_OP", name, op.catalogId, expects.catalogId)
    else -> SlotDefect("MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED", name, op.catalogId, expects.catalogId)
}

private fun slotFinding(
    defect: SlotDefect,
    construction: String,
    element: TrackedDeclaration,
    member: String,
    span: TextSpan,
): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    defect.code,
    "in `$member`: the ${defect.slot} of `$construction` is written as `${defect.wrote}`, where A12 takes " +
        "${defect.expected}",
    element.path,
    span,
)

/**
 * What a field declaration has to look like to fill this slot, in A12's own gate order.
 *
 * The pattern gate runs first and answers for a `StringType` alone; the width-or-maximum test runs after it,
 * and each judged type satisfies it its own way — a number by its stored width or its declared bound, a
 * string by its stored width alone, a date only by being the complete year written exactly `yyyy`.
 *
 * **Silent on a GROUP operand**, whose own gate runs even earlier ([groupSlotDefect]); a starred one never reaches
 * this arm, since [armOf] gives it its own. Silent too on every type but the three, which A12 refuses outright. A
 * type-defined field is judged by its definition's type and body, as A12 judges it ([KERNEL-FINDINGS KF602]).
 */
private fun Slot.entityDefect(
    model: TrackedFactBase,
    element: TrackedDeclaration,
    written: String,
    keyed: Boolean,
    everyLevelAddressed: Boolean,
): SlotDefect? {
    val declaringGroup = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    // A12's reference reader answers BEFORE this gate: a component read across a repetition nothing addresses
    // draws `MVK_NO_WILDCARD` there and the slot gate never runs — measured 2026-09-14 with both controls
    // (`DateConstructionSlotLawsTest.theReferenceReaderAnswersBeforeTheSlotGate`), and that class is
    // `OPERAND_REPETITION_SCOPE`'s. What addresses a level is [addressedTargetOf]'s one reading — the rule's
    // iteration, a key, a filter body — which a containment test got wrong on both of the last two, standing
    // the gate down where A12 names its class (`adapter.laws.CheckerKeyedOperandAgreementTest`).
    val target = model.addressedTargetOf(written, declaringGroup, element, keyed, everyLevelAddressed) ?: return null
    val field = model.declarationAt(target)?.takeIf { it.declaredKind == DeclaredKind.FIELD } ?: return null
    val resolved = model.resolvedFieldTypeOf(field) ?: return null
    if (resolved.declared == null) return null
    return when (resolved.type) {
        // A string declaring no body declares no pattern, which the pattern gate refuses like any non-digit one.
        FieldType.STRING -> stringDefect(resolved.body ?: JsonObject(emptyMap()), written)
        FieldType.NUMBER -> numberDefect(resolved.body ?: return null, written)
        FieldType.DATE -> dateDefect(resolved.body ?: return null, written)
        else -> null
    }
}

/**
 * The slot's first gate, which A12 asks of every entity arm before its star: whether it names a group.
 *
 * Null wherever the reference reader answers first, which [addressedTargetOf] decides for this walk as it does
 * for [entityDefect].
 */
private fun TrackedFactBase.groupSlotDefect(
    slot: Slot,
    arm: SlotArm,
    element: TrackedDeclaration,
    everyLevelAddressed: Boolean,
): SlotDefect? {
    val (written, keyed) = when (arm) {
        is SlotArm.Entity -> arm.path to false
        is SlotArm.Keyed -> (arm.value ?: return null) to true
        is SlotArm.Starred -> arm.written to false
        else -> return null
    }
    val declaringGroup = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    val target = addressedTargetOf(written, declaringGroup, element, keyed, everyLevelAddressed) ?: return null
    if (declarationAt(target)?.declaredKind != DeclaredKind.GROUP) return null
    return SlotDefect("MVK_NO_GROUPS_ALLOWED", slot.name, written, "one field, never a group")
}

private fun Slot.stringDefect(body: JsonObject, written: String): SlotDefect? {
    val maxLength = body.declaredInt("maxLength")
    val pattern = (body["pattern"] as? JsonPrimitive)?.contentOrNull
    if (!describesOnlyDigits(pattern, maxLength)) {
        return SlotDefect(
            "MVK_NO_DIGIT_PATTERN", name, written,
            "a string component declared with one of ${DIGIT_PATTERNS.joinToString(", ") { "`$it`" }} " +
                "(or the same with `{n}` for its own `maxLength`)",
        )
    }
    return if (maxLength == entity.maxLength) {
        null
    } else {
        SlotDefect(
            "MVK_WRONG_DATE_FORMAT_FOR_OP", name, written,
            "a `maxLength` of exactly ${entity.maxLength}",
        )
    }
}

/**
 * The one temporal component A12 admits: a `DateType` declared exactly `yyyy`, and only where a whole year
 * goes.
 *
 * The slot is recognised by the width it demands rather than by its name, because that is what the kernel
 * tests — the year written as two separate components asks for two characters and refuses the same field.
 */
private fun Slot.dateDefect(body: JsonObject, written: String): SlotDefect? {
    val format = (body["format"] as? JsonPrimitive)?.contentOrNull
    return if (entity.maxLength == COMPLETE_YEAR_WIDTH && format == YEAR_ONLY_FORMAT) {
        null
    } else {
        SlotDefect(
            "MVK_WRONG_DATE_FORMAT_FOR_OP", name, written,
            "a number or digit-string component, or — at a complete year alone — a date field declared " +
                "exactly `$YEAR_ONLY_FORMAT`",
        )
    }
}

private const val COMPLETE_YEAR_WIDTH = 4

private const val YEAR_ONLY_FORMAT = "yyyy"

/**
 * A12's four unbounded digit patterns, plus the two `{n}` spellings it accepts for a field's own `maxLength`.
 *
 * It is a LIST of spellings and not a regex equivalence: `[0123456789]+` describes the same language and is
 * refused, measured — so the set may only grow by measurement, never by reasoning about what a pattern means.
 */
private val DIGIT_PATTERNS = listOf("[0-9]+", "[0-9]*", "\\d+", "\\d*")

private fun describesOnlyDigits(pattern: String?, maxLength: Int?): Boolean {
    val declared = pattern?.takeIf { it.isNotEmpty() } ?: return false
    if (declared in DIGIT_PATTERNS) {
        return true
    }
    return maxLength != null && declared in listOf("[0-9]{$maxLength}", "\\d{$maxLength}")
}

private fun Slot.numberDefect(body: JsonObject, written: String): SlotDefect? {
    val minFractional = body.declaredInt("minFractionalDigits") ?: 0
    val maxFractional = body.declaredInt("maxFractionalDigits") ?: 0
    val scaled = minFractional != 0 || maxFractional != 0
    // The sign gate takes EITHER satisfier, and the second one has no minimum to read: `positivesOnly` is
    // A12's spelling of the kernel's `hasSign()` inverted, so a component declaring the flag and nothing
    // else is admitted. Measured 2026-09-14 — a reader keying on the declared minimum alone refuses that
    // shape, while the same field with neither the flag nor a minimum, and with `minValue: -1`, are both
    // genuinely refused (`TemporalComponentDeclarationLawsTest`).
    val nonNegative = body.flag("positivesOnly") == true ||
        (body["minValue"] as? JsonPrimitive)?.a12DecimalOrNull()?.let { it >= 0 } == true
    val maxValue = (body["maxValue"] as? JsonPrimitive)?.a12DecimalOrNull()
    val fits = !scaled && nonNegative &&
        (body.declaredInt("maxLength") == entity.maxLength || entity.acceptsDeclaredMaximum(maxValue))
    return if (fits) {
        null
    } else {
        SlotDefect(
            "MVK_WRONG_DATE_FORMAT_FOR_OP", name, written,
            "a scale-free number declared non-negative — a `minValue` of 0 or more, or `positivesOnly` " +
                "— with either a `maxLength` of exactly " +
                "${entity.maxLength} or ${entity.maximumText()}",
        )
    }
}

/**
 * Whether a declared `maxValue` fills this slot: the slot's own maximum exactly, or, where the slot allows
 * it, any smaller one still wide enough to spell the component.
 */
private fun EntityDemand.acceptsDeclaredMaximum(declared: Double?): Boolean {
    val max = declared ?: return false
    if (max == maxValue.toDouble()) {
        return true
    }
    val floor = flexibleFloor?.toDouble() ?: return false
    return max in floor..maxValue.toDouble()
}

private fun EntityDemand.maximumText(): String = if (flexibleFloor == null) {
    "a `maxValue` of exactly $maxValue"
} else {
    "a `maxValue` between $flexibleFloor and $maxValue"
}

/** A declared length member, absent when the member is missing or carries A12's suppression sentinel. */
private fun JsonObject.declaredInt(member: String): Int? = boundInt(member)?.takeIf { it >= 0 }
