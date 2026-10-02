package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionRecognizer
import io.github.mbackschat.a12.dm.consistency.syntax.OperationShape
import io.github.mbackschat.a12.dm.consistency.syntax.Recognition
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxEntry
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxSite
import io.github.mbackschat.a12.dm.consistency.syntax.operandOf
import io.github.mbackschat.a12.dm.consistency.syntax.textOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

/** A direct field and a literal retain provenance that the comparison gates must not infer from type. */
internal data class OperationValue(
    val type: OperationType,
    val field: TrackedDeclaration? = null,
    val constant: WrittenConstant? = null,
    val baseYear: BaseYearRole? = null,
) {
    val numeric get() = type as? OperationType.Number
    val temporal get() = type as? OperationType.Temporal

    /** The same type as a computed result, which carries no field, constant or base-year provenance. */
    fun derived() = copy(field = null, constant = null, baseYear = null)
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
    private val identities = CONDITION_OPERATOR_KEYWORDS.getValue(facts.conditionLanguage)
        .entries.associate { (id, spelling) -> spelling to id }
    private val values = mutableMapOf<OperationShape, OperationValue>()
    val contributions = mutableListOf<ConditionFinding>()
    val findings: List<Finding> get() = contributions.map { it.finding }

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
            if (fieldValueReadIsRefused(shape.read, group, element)) UNKNOWN
            else bareReferenceField(written(shape), group, element,
                shape.read.inAFilterBody && !shape.read.outerMarker)?.let(::field) ?: UNKNOWN
        }
        is OperationShape.Group -> value(shape.child).derived()
        is OperationShape.Arithmetic -> arithmetic(shape)
        is OperationShape.Call -> call(shape)
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

    private fun field(field: TrackedDeclaration): OperationValue {
        val type = when (field.declaredFieldType()) {
            FieldType.NUMBER -> OperationType.Number((FieldType.NUMBER.bodyIn(field.body?.get("fieldType") as? JsonObject)
                ?.get("maxFractionalDigits") as? JsonPrimitive)?.intOrNull ?: 0)
            FieldType.STRING, FieldType.CUSTOM_FIELD -> OperationType.Text
            FieldType.ENUMERATION -> OperationType.Enumeration
            FieldType.BOOLEAN -> OperationType.Boolean
            FieldType.CONFIRM -> OperationType.Confirm
            FieldType.DATE, FieldType.DATE_TIME, FieldType.TIME, FieldType.DATE_FRAGMENT ->
                declaredTemporalFormatOf(field)?.let { OperationType.Temporal(componentsOf(it), declaresAPartialPrecision(field)) }
                    ?: OperationType.Unknown
            FieldType.DATE_RANGE -> declaredDateRangeFormatOf(field)?.let { OperationType.Range(componentsOf(it)) }
                ?: OperationType.Unknown
            else -> OperationType.Unknown
        }
        return OperationValue(type, field)
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
            if (left.type in textual && right.type in textual && !left.labelsArithmetic() && !right.labelsArithmetic())
                return OperationValue(OperationType.Text)
            return refuse("MVK_NO_STRING_OR_NUMBER_FOR_ADD", shape,
                "`+` adds two numbers or joins two texts; this pair is neither, or carries enumeration display labels")
        }
        if (a == null || b == null) return refuse("MVK_NO_NUMBER_FOR_CALC", shape,
            "`${written(shape)}` requires two numeric operands for `$operator`")
        if (operator == "^") {
            // The harvested partial-model contract declines an exponent whose declaration omits its scale.
            val undeclaredScale = right.field?.let { field ->
                FieldType.NUMBER.bodyIn(field.body?.get("fieldType") as? JsonObject)?.get("maxFractionalDigits") == null
            } == true
            if (!undeclaredScale && (b.scale == null || b.scale > 0)) return refuse("MVK_INVALID_NUMBER_FOR_EXP", shape,
                "the exponent `${written(shape.right)}` needs a known whole-number scale")
            if ((shape.left as? OperationShape.Arithmetic)?.operator?.text == "^")
                return refuse("MVK_TOO_MANY_POW_FOR_CALC", shape, "brace the inner power before raising it again")
        }
        if (divisions(shape) > 1) return refuse("MVK_TOO_MANY_DIV_FOR_CALC", shape,
            "brace the inner quotient; a multiplicative chain admits one division")
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

    private fun OperationValue.labelsArithmetic(): Boolean =
        (field?.enumerationBody()?.get("values") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .any { (it["label"] as? JsonArray)?.isNotEmpty() == true }

    private fun divisions(shape: OperationShape): Int = if (shape is OperationShape.Arithmetic &&
        shape.operator.text in setOf("*", "/")) divisions(shape.left) + divisions(shape.right) +
        (if (shape.operator.text == "/") 1 else 0) else 0

    private fun call(shape: OperationShape.Call): OperationValue {
        val id = identities[shape.call.operator] ?: return UNKNOWN
        val operands = shape.operands.map(::value)
        fun fields(): List<OperationValue>? = with(facts) {
            shape.call.operands.flatMap { tokens ->
                val written = shape.call.textOf(tokens)
                val reference = operandReferenceIn(written, group, element) {
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
                if (id == "FirstFilledValue") fields.firstOrNull()?.derived() ?: UNKNOWN
                else numericAggregate(fields, id == "SumOfProducts")
            }
            "NumberOfFilledFields", "NumberOfFilledGroups", "NumberOfValueInFields", "CurrentRepetition", "Length" ->
                OperationValue(OperationType.Number(0))
            "Abs" -> operands.firstOrNull()?.derived() ?: UNKNOWN
            "AbsValue" -> fields()?.singleOrNull()?.derived() ?: UNKNOWN
            "RoundUp", "RoundDown", "RoundAccounting", "RoundUpValue", "RoundDownValue", "RoundAccountingValue" -> {
                if (shape.operands.firstOrNull() != null && operands.first().type == OperationType.Unknown) return UNKNOWN
                val scale = shape.call.operands.getOrNull(1)?.let { shape.call.textOf(it).toIntOrNull() } ?: 0
                OperationValue(OperationType.Number(scale))
            }
            "FieldValueAsString" -> OperationValue(OperationType.Text)
            "StartOfDateRange", "EndOfDateRange" ->
                if (shape.call.operands.singleOrNull()?.let { facts.isBaseYearConstant(shape.call.textOf(it)) } == true)
                    UNKNOWN.copy(baseYear = BaseYearRole.ENDPOINT) else UNKNOWN
            else -> UNKNOWN
        }
    }

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
