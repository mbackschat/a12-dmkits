package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionComparison
import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_IDENTITIES
import io.github.mbackschat.a12.dm.consistency.syntax.CallShape
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionRecognizer
import io.github.mbackschat.a12.dm.consistency.syntax.FieldValueRead
import io.github.mbackschat.a12.dm.consistency.syntax.OperationShape
import io.github.mbackschat.a12.dm.consistency.syntax.Recognition
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxEntry
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxSite
import io.github.mbackschat.a12.dm.consistency.syntax.operandOf
import io.github.mbackschat.a12.dm.consistency.syntax.textOf
import io.github.mbackschat.a12.dm.consistency.syntax.asCall
import io.github.mbackschat.a12.dm.consistency.syntax.encloses
import io.github.mbackschat.a12.dm.consistency.syntax.filterBodiesIn
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** KF43 distinguishes an unknown numeric scale from an operation whose type is unsupported. */
internal sealed interface OperationType {
    data object Unknown : OperationType
    data class Number(val scale: Int?, val expandable: kotlin.Boolean = false) : OperationType
    data object Text : OperationType
    data object Enumeration : OperationType
    data object Boolean : OperationType
    data object Confirm : OperationType
    data class Temporal(val components: Set<Component>, val partial: kotlin.Boolean = false) : OperationType
    data class Range(val components: Set<Component>) : OperationType
}

/**
 * A direct field and a literal retain provenance that the comparison gates must not infer from type.
 *
 * [field] and [constant] are an IMMEDIATE read and an immediate literal. [typedBy] and [domainConstant] are the
 * same facts as a brace leaves them: A12 types a braced operand by what it encloses and finds a constant inside
 * any number of braces (`CheckOpUtils.isConstant`), while its display gate against a text asks for an immediate
 * field read (`isFeldWertOp`) — measured on both positions by `adapter.laws.CheckerComparisonOperandShapeAgreementTest`.
 * [categoryDomain] is the value domain of a category read, which carries no declaration of its own ([KERNEL-FINDINGS
 * KF610]).
 */
internal data class OperationValue(
    val type: OperationType,
    val field: TrackedDeclaration? = null,
    val constant: WrittenConstant? = null,
    val baseYear: BaseYearRole? = null,
    val typedBy: TrackedDeclaration? = field,
    val domainConstant: WrittenConstant? = constant,
    val categoryDomain: Set<String>? = null,
) {
    val numeric get() = type as? OperationType.Number
    val temporal get() = type as? OperationType.Temporal

    /** The same type as a computed result, which carries no field, constant or base-year provenance. */
    fun derived() =
        copy(field = null, constant = null, baseYear = null, typedBy = null, domainConstant = null, categoryDomain = null)

    /** The same value behind a brace: no longer an immediate read or literal, still typed and still constant. */
    fun braced() = copy(field = null, constant = null, baseYear = null)
}

/**
 * Which base-year operand a value is: the `BaseYear` terminal itself, or a range endpoint over it. Both keep
 * the [OperationType.Unknown] type, so arithmetic, extrema and every other consumer leave them unjudged as
 * before; only the comparison walk resolves them, and only against the partners A12 was measured with
 * (`OperationComparisons.baseYearResolved`).
 */
internal enum class BaseYearRole { YEAR, ENDPOINT }

/** One declaration owns recognition, derived results and the tracked model reads that justify them. */
internal class OperationAnalysis(
    val facts: TrackedFactBase,
    val element: TrackedDeclaration,
    val carrier: ConditionText,
    val recognition: Recognition = ConditionRecognizer(carrier.text, facts.conditionLanguage).recognize(SyntaxEntry.CONDITION),
) {
    val text = carrier.text
    private val group = element.path.substringBeforeLast('/')
    private val identities = CONDITION_OPERATOR_IDENTITIES.getValue(facts.conditionLanguage)
    /** The text's `Having` bodies, read once however many calls it holds: every shape here comes from [text]. */
    private val filterBodies by lazy { filterBodiesIn(text, facts.conditionLanguage) }
    private val values = mutableMapOf<OperationShape, OperationValue>()
    val contributions = mutableListOf<ConditionFinding>()
    val findings: List<Finding> get() = contributions.map { it.finding }

    /** The operation members whose lowered comparison passed A12's scale gate with both scales known. */
    val scaleAdmittedOperations = mutableSetOf<String>()

    fun value(shape: OperationShape?): OperationValue = values[shape] ?: UNKNOWN
    fun written(shape: OperationShape): String = text.substring(shape.at, shape.end)

    fun analyze() {
        for (site in recognition.sites) when (site) {
            is SyntaxSite.Operation -> values[site.shape] = operation(site.shape)
            is SyntaxSite.Comparison -> comparisonFinding(site.shape)?.let {
                contributions += ConditionFinding(element.key, site, it)
            }
            else -> Unit
        }
    }

    private fun operation(shape: OperationShape): OperationValue = when (shape) {
        is OperationShape.Literal -> literal(written(shape))
        is OperationShape.Read -> with(facts) {
            when {
                fieldValueReadIsRefused(shape.read, group, element) -> UNKNOWN
                shape.read.category != null -> categoryRead(shape.read)
                else -> bareReferenceField(referenceText(shape), group, element,
                    bodyAddresses(shape.read.inAFilterBody, shape.read.outerMarked))?.let(::field) ?: UNKNOWN
            }
        }
        is OperationShape.Group -> value(shape.child).braced()
        is OperationShape.Arithmetic -> arithmetic(shape)
        is OperationShape.Call -> call(shape)
    }

    /**
     * The text a read's reference is reduced from: a `$`-marked read in a filter body is the rule's own reference,
     * which the recognizer has already reduced past its marker ([KERNEL-FINDINGS KF487]). Its bracketed text would
     * hide the marker from the reference reader, which recognizes one only at the head of a bare spec, and every
     * comparison gate then stood down on a side A12 types ([KERNEL-FINDINGS KF487]). A keyed marked read keeps no
     * path and stays unjudged.
     */
    private fun referenceText(shape: OperationShape.Read): String =
        shape.read.path?.takeIf { shape.read.outerMarked && shape.read.inAFilterBody } ?: written(shape)

    /**
     * A category read, typed as A12 types it: the Enumeration it hangs off, looked up in the CATEGORY's values
     * (`CheckVergleichsBedingungImpl.getValidEnumValues`, [KERNEL-FINDINGS KF610]). It is no immediate field read,
     * so the display gates that ask for one pass it, and a keyed read stays unjudged because no row measures it.
     */
    private fun categoryRead(read: FieldValueRead): OperationValue = with(facts) {
        val written = read.path ?: return UNKNOWN
        val category = read.category?.trim('`') ?: return UNKNOWN
        val target = addressedTargetOf(written, group, element, indexSelected = false,
            everyLevelAddressed = bodyAddresses(read.inAFilterBody, read.outerMarked)) ?: return UNKNOWN
        val field = declarationAt(target)?.takeIf { it.declaredKind == DeclaredKind.FIELD } ?: return UNKNOWN
        val domain = enumerationBodyOf(field)?.lookupDomain(category) ?: return UNKNOWN
        OperationValue(OperationType.Enumeration, categoryDomain = domain.toSet())
    }

    /** Record [comparison] in [scaleAdmittedOperations] when it is the comparison an operation is lowered to. */
    fun admitScale(comparison: ConditionComparison) {
        val origin = carrier.at(comparison.start) ?: return
        val member = origin.member ?: return
        if (!origin.authored && isOperationMember(member)) scaleAdmittedOperations += member
    }

    fun finding(code: String, at: Int, end: Int, because: (String) -> String): Finding? {
        val span = carrier.semanticSpan(at, end) ?: return null
        return Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, because(span.member), element.path, span)
    }

    private fun refuse(code: String, shape: OperationShape, reason: String): OperationValue {
        finding(code, shape.at, shape.end) { "in `$it`: $reason" }?.let {
            contributions += ConditionFinding(element.key, SyntaxSite.Operation(shape), it)
        }
        return UNKNOWN
    }

    private fun literal(written: String): OperationValue =
        if (facts.isBaseYearConstant(written)) UNKNOWN.copy(baseYear = BaseYearRole.YEAR) else writtenLiteral(written)

    private fun writtenLiteral(written: String): OperationValue = when (val literal = facts.writtenConstantOf(written)) {
        is WrittenConstant.Number -> {
            val digits = written.removePrefix("-")
            val scale = if ('.' in digits || digits.all { it == '0' }) literal.scale
                else -digits.takeLastWhile { it == '0' }.length
            OperationValue(OperationType.Number(scale, expandable = true), constant = literal)
        }
        is WrittenConstant.Text -> OperationValue(OperationType.Text, constant = literal)
        is WrittenConstant.BooleanConstant -> OperationValue(OperationType.Boolean, constant = literal)
        is WrittenConstant.Temporal -> OperationValue(OperationType.Temporal(literal.components), constant = literal)
        null -> UNKNOWN
    }

    /**
     * A declared Number's scale is its `maxFractionalDigits`, read as 0 when absent; an [UNDEFINED] maximum is an
     * UNKNOWN scale, which every scale-gated equality and exponent refuses ([KERNEL-FINDINGS KF625]).
     */
    private fun field(field: TrackedDeclaration): OperationValue = with(facts) {
        val type = when (effectiveFieldType(field)) {
            FieldType.NUMBER -> OperationType.Number(((typeBodyOf(field, FieldType.NUMBER)
                ?.get("maxFractionalDigits") as? JsonPrimitive)?.intOrNull ?: 0).takeIf { it != UNDEFINED })
            FieldType.STRING, FieldType.CUSTOM_FIELD -> OperationType.Text
            FieldType.ENUMERATION -> OperationType.Enumeration
            FieldType.BOOLEAN -> OperationType.Boolean
            FieldType.CONFIRM -> OperationType.Confirm
            FieldType.DATE, FieldType.DATE_TIME, FieldType.TIME, FieldType.DATE_FRAGMENT ->
                temporalFormatOf(field)?.let { OperationType.Temporal(componentsOf(it), hasAPartialPrecision(field)) }
                    ?: OperationType.Unknown
            FieldType.DATE_RANGE -> dateRangeFormatOf(field)?.let { OperationType.Range(componentsOf(it)) }
                ?: OperationType.Unknown
            else -> OperationType.Unknown
        }
        OperationValue(type, field)
    }

    private fun arithmetic(shape: OperationShape.Arithmetic): OperationValue {
        val left = value(shape.left)
        val right = value(shape.right)
        if (left.type == OperationType.Unknown || right.type == OperationType.Unknown) return UNKNOWN
        val operator = shape.operator.text
        val a = left.numeric
        val b = right.numeric
        if (operator == "+" && (a == null || b == null)) {
            val textual = setOf(OperationType.Text, OperationType.Enumeration)
            if (left.type in textual && right.type in textual && !left.remapsDisplay() && !right.remapsDisplay())
                return OperationValue(OperationType.Text)
            return refuse("MVK_NO_STRING_OR_NUMBER_FOR_ADD", shape,
                "`+` adds two numbers or joins two texts; this pair is neither, or reads an enumeration whose display labels differ from its values")
        }
        if (a == null || b == null) return refuse("MVK_NO_NUMBER_FOR_CALC", shape,
            "`${written(shape)}` requires two numeric operands for `$operator`")
        if (operator == "^") {
            // The harvested partial-model contract declines an exponent whose declaration omits its scale.
            val undeclaredScale = right.field?.let { field ->
                facts.typeBodyOf(field, FieldType.NUMBER)?.get("maxFractionalDigits") == null
            } == true
            if (!undeclaredScale && (b.scale == null || b.scale > 0)) return refuse("MVK_INVALID_NUMBER_FOR_EXP", shape,
                "the exponent `${written(shape.right)}` needs a known whole-number scale")
            if ((shape.left as? OperationShape.Arithmetic)?.operator?.text == "^")
                return refuse("MVK_TOO_MANY_POW_FOR_CALC", shape, "brace the inner power before raising it again")
        }
        if (divisionWalk(shape, DivisionMark()) == DivisionWalk.REFUSED) return refuse("MVK_TOO_MANY_DIV_FOR_CALC", shape,
            "brace one of the quotients; an operator admits one division through its operands and the functions it applies")
        val scale = when (operator) {
            "+", "-" -> if (a.scale == null || b.scale == null) null else maxOf(a.scale, b.scale)
            "*" -> if (a.scale == null || b.scale == null) null else a.scale + b.scale
            "^" -> if (a.scale == 0 && shape.right is OperationShape.Literal &&
                b.scale != null && b.scale <= 0 && !written(shape.right).startsWith('-')) 0 else null
            else -> null
        }
        val expandable = when (operator) {
            "+", "-" -> a.expandable && b.expandable
            "*" -> a.expandable || b.expandable
            else -> false
        }
        return OperationValue(OperationType.Number(scale, expandable))
    }

    /**
     * `+` refuses an operand only as an immediate field read, so a braced or computed value carries no field here
     * and passes; a category access reduces to no declaration and passes too ([KF396]).
     */
    private fun OperationValue.remapsDisplay(): Boolean = field?.let { facts.bearsDisplayValues(it) } == true

    /**
     * A12's division check from one operator node: a pre-order walk carrying one division mark, which a second
     * division refuses ([KERNEL-FINDINGS KF503], read from the parser's `CheckOperationImpl.checkOnlyOneDiv`).
     *
     * The walk descends through `*`, `/` and value-function operands with the same mark. A brace, an addition
     * or subtraction, or a power walks each of its operands with a fresh mark and then ends the WHOLE walk, the
     * enclosing ones included, so a cut in a first operand leaves every later operand unwalked. That is why
     * `[Amount] + [Amount] + Max(…)` passes and `{Max(…)} + [Amount]` does not, which no count of divisions
     * per region separates.
     */
    private fun divisionWalk(shape: OperationShape?, mark: DivisionMark): DivisionWalk = when (shape) {
        null, is OperationShape.Literal, is OperationShape.Read -> DivisionWalk.CONTINUE
        is OperationShape.Group -> freshWalks(shape.child)
        is OperationShape.Call -> shape.operands.asSequence().map { divisionWalk(it, mark) }
            .firstOrNull { it != DivisionWalk.CONTINUE } ?: DivisionWalk.CONTINUE
        is OperationShape.Arithmetic -> when (shape.operator.text) {
            "*", "/" -> if (shape.operator.text == "/" && !mark.mark()) DivisionWalk.REFUSED
                else listOf(shape.left, shape.right).asSequence().map { divisionWalk(it, mark) }
                    .firstOrNull { it != DivisionWalk.CONTINUE } ?: DivisionWalk.CONTINUE
            else -> freshWalks(shape.left, shape.right)
        }
    }

    /** Each of [operands] walked with its own mark, in order, after which the enclosing walk ends. */
    private fun freshWalks(vararg operands: OperationShape): DivisionWalk {
        for (operand in operands) {
            val outcome = divisionWalk(operand, DivisionMark())
            if (outcome != DivisionWalk.CONTINUE) return outcome
        }
        return DivisionWalk.CUT
    }

    private fun call(shape: OperationShape.Call): OperationValue {
        val id = identities[shape.call.operator] ?: return UNKNOWN
        val operands = shape.operands.map(::value)
        fun fields(): List<OperationValue>? = with(facts) {
            shape.call.operands.flatMap { tokens ->
                val written = shape.call.textOf(tokens)
                val reference = operandReferenceIn(written, group, element, inAFilterBody(shape.call)) {
                    operandOf(tokens, conditionLanguage, text).base
                        ?.let { OperandReference(it) }
                } ?: return null
                val resolved = resolveEntity(group, reference.reference.replace("*", ""), element.origin)
                    as? Reference.Resolved ?: return null
                expandToFields(resolved.path)?.map(::field) ?: return null
            }
        }
        return when (id) {
            "Min", "Max" -> extremum(shape, operands)
            "MinValue", "MaxValue" -> extremum(shape, fields() ?: return UNKNOWN, numericGate = false)
            "NumberOfDifferentValues" -> {
                val fields = fields() ?: return UNKNOWN
                val result = if (fields.firstOrNull()?.temporal != null) extremum(shape, fields, numericGate = false)
                    else fields.firstOrNull() ?: UNKNOWN
                if (result.type != OperationType.Unknown) OperationValue(OperationType.Number(0)) else result
            }
            "Sum", "SumOfProducts", "FirstFilledValue" -> {
                val fields = fields() ?: return UNKNOWN
                // KF181 / NumericOperationGateAgreementTest: numeric scale summarizes the expanded list;
                // KF391's first-field kind rule still applies to nonnumeric results.
                if (id == "FirstFilledValue" && fields.firstOrNull()?.numeric == null)
                    fields.firstOrNull()?.derived() ?: UNKNOWN
                else numericAggregate(fields, id == "SumOfProducts")
            }
            "NumberOfFilledFields", "NumberOfFilledGroups", "NumberOfValueInFields", "CurrentRepetition", "Length" ->
                OperationValue(OperationType.Number(0))
            "Abs" -> computedDate(shape, operands.firstOrNull()) ?: operands.firstOrNull()?.derived() ?: UNKNOWN
            "AbsValue" -> fields()?.singleOrNull()?.derived() ?: UNKNOWN
            "RoundUp", "RoundDown", "RoundAccounting", "RoundUpValue", "RoundDownValue", "RoundAccountingValue" -> {
                computedDate(shape, operands.firstOrNull())?.let { return it }
                if (shape.operands.firstOrNull() != null && operands.first().type == OperationType.Unknown) return UNKNOWN
                val scale = shape.call.operands.getOrNull(1)?.let { shape.call.textOf(it).asciiIntOrNull() } ?: 0
                OperationValue(OperationType.Number(scale))
            }
            "FieldValueAsString" -> OperationValue(OperationType.Text)
            // A12 hands a shift the type of the date it moves (`CheckDatumAddOpImpl`), so a comparison meets the
            // shifted date as that field ([KERNEL-FINDINGS KF606]).
            "AddDays", "AddMonths", "AddYears", "AddHours", "AddMinutes", "AddSeconds" -> shifted(shape.call)
            "DateRange" -> constructedRange(shape)
            "StartOfDateRange", "EndOfDateRange" ->
                if (shape.call.operands.singleOrNull()?.let { facts.isBaseYearConstant(shape.call.textOf(it)) } == true)
                    UNKNOWN.copy(baseYear = BaseYearRole.ENDPOINT) else UNKNOWN
            else -> UNKNOWN
        }
    }

    /**
     * The refusal of a computed DATE that [shape] would take as its number, or null for any other operand.
     *
     * A12 types the whole operation it rounds or makes absolute and refuses a date with `MVK_NO_NUMBER` before
     * the comparison around it is asked anything ([KERNEL-FINDINGS KF606],
     * `adapter.laws.CheckerShiftedDateComparisonAgreementTest`). An immediate field read is `RoundingOperands`'
     * to answer, so it is left to that family.
     */
    private fun computedDate(shape: OperationShape.Call, operand: OperationValue?): OperationValue? =
        if (operand?.temporal != null && operand.field == null) {
            refuse("MVK_NO_NUMBER", shape, "`${written(shape)}` takes a date, and only a number can be rounded or made absolute")
        } else {
            null
        }

    /**
     * The range `DateRange(a, b)` builds, or its refusal.
     *
     * A12 reads each endpoint in turn as one unstarred field (`datumBereichKonstrukt` takes two field specs) that
     * must be a fully known date or fragment naming a calendar and no clock, then asks the two for exactly the
     * same components, with no base-year allowance, and types the range by the first
     * (`CheckDatumBereichKonstruktImpl`; [KERNEL-FINDINGS KF613]). A keyed or categorized endpoint and a type this
     * reader cannot know are left unjudged.
     */
    private fun constructedRange(shape: OperationShape.Call): OperationValue = with(facts) {
        val endpoints = shape.call.operands.map { tokens ->
            val reference = operandReferenceIn(shape.call.textOf(tokens), group, element, inAFilterBody(shape.call)) {
                operandOf(tokens, conditionLanguage, text).path?.let { OperandReference(it) }
            } ?: return UNKNOWN
            if (reference.indexSelected || reference.category != null) return UNKNOWN
            val resolved = resolveEntity(group, reference.reference.replace("*", ""), element.origin)
                as? Reference.Resolved ?: return UNKNOWN
            val endpoint = declarationAt(resolved.path) ?: return UNKNOWN
            when {
                endpoint.declaredKind == DeclaredKind.GROUP -> return refuse("MVK_NO_GROUPS_ALLOWED", shape,
                    "`${endpoint.path}` is a group, and a range is built from two date fields")
                endpoint.declaredKind != DeclaredKind.FIELD -> return UNKNOWN
                '*' in reference.reference -> return refuse("MVK_NO_WILDCARDS_ALLOWED", shape,
                    "`${reference.reference}` reads across a repetition, and a range endpoint is one value")
                else -> endpoint
            }
        }
        if (endpoints.size != 2) return UNKNOWN
        val components = endpoints.map { endpoint ->
            val type = effectiveFieldType(endpoint)
            val date = field(endpoint).temporal
            when {
                type in PLAINLY_NON_DATE_TYPES || type == FieldType.TIME || type == FieldType.DATE_TIME -> null
                type != FieldType.DATE && type != FieldType.DATE_FRAGMENT || date == null -> return UNKNOWN
                date.partial || !namesADate(date.components) || namesATime(date.components) -> null
                else -> date.components
            } ?: return refuse("MVK_WRONG_DATE_FORMAT_FOR_OP", shape,
                "`${endpoint.path}` is no fully known calendar date, and a range is built from two of them")
        }
        if (components[0] != components[1]) return refuse("MVK_WRONG_DATE_FORMAT_FOR_OP", shape,
            "`${endpoints[0].path}` and `${endpoints[1].path}` name different date components, and a range needs one set")
        OperationValue(OperationType.Range(components[0]))
    }

    /** The date [call] shifts, typed as its declared field, where A12 admits the shift and this reader judges it. */
    private fun shifted(call: CallShape): OperationValue = with(facts) {
        admittedShiftField(call.asCall(conditionLanguage, inAFilterBody(call)), group, element)
            ?.let { field(it).derived() } ?: UNKNOWN
    }

    /** Whether a `Having` body of [text] holds [call], over the bodies this analysis has already read. */
    private fun inAFilterBody(call: CallShape): Boolean = filterBodies.any { it.encloses(call.at, call.end) }

    private fun numericAggregate(values: List<OperationValue>, product: Boolean = false): OperationValue {
        val numbers = values.map { it.numeric ?: return UNKNOWN }
        if (numbers.isEmpty()) return UNKNOWN
        val scales = numbers.map { it.scale ?: return OperationValue(OperationType.Number(null)) }
        return OperationValue(OperationType.Number(if (product) scales.sum() else scales.max(),
            if (product) numbers.any { it.expandable } else numbers.all { it.expandable }))
    }

    private fun extremum(shape: OperationShape.Call, operands: List<OperationValue>, numericGate: Boolean = true): OperationValue {
        val leader = operands.firstOrNull() ?: return UNKNOWN
        val temporal = leader.temporal
        if (temporal == null) {
            if (operands.any { it.type == OperationType.Unknown }) return UNKNOWN
            if (operands.any { it.numeric == null }) {
                // The entity-list numeric arm stays with FieldListOperationOperands; only Min/Max move here.
                if (!numericGate) return UNKNOWN
                return refuse("MVK_NOT_SORTABLE", shape,
                    "`${shape.call.operator}` needs numeric operands when its first operand is not temporal")
            }
            return numericAggregate(operands)
        }
        for (operand in operands) {
            if (operand.type == OperationType.Unknown) return UNKNOWN
            val other = operand.temporal ?: return refuse("MVK_DATE_AND_NONDATE", shape,
                "`${shape.call.operator}` mixes a temporal leader with a non-temporal operand")
            if (other.partial) return refuse("MVK_PARTIAL_DATE_NOT_ALLOWED", shape,
                "`${shape.call.operator}` compares decoded dates; a partial precision has no decoded value")
            val compatible = (facts.hasBaseYear || (Component.YEAR in temporal.components) == (Component.YEAR in other.components)) &&
                namesADate(temporal.components) == namesADate(other.components) &&
                supplemented(temporal.components, facts.hasBaseYear) == supplemented(other.components, facts.hasBaseYear)
            if (!compatible) return refuse("MVK_DATEFORMATS_NOT_COMPATIBLE", shape,
                "`${shape.call.operator}` needs the same temporal components in every operand")
        }
        return leader.derived()
    }

    private companion object {
        val UNKNOWN = OperationValue(OperationType.Unknown)
    }
}

/** How a division walk ended: still walking, ended by a cut, or refused at a second division. */
private enum class DivisionWalk { CONTINUE, CUT, REFUSED }

/** The one division mark a walk carries; [mark] reports whether the division it records is the first. */
private class DivisionMark {
    private var marked = false

    fun mark(): Boolean {
        val first = !marked
        marked = true
        return first
    }
}
