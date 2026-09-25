package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "the calculation module asks its stages in order, after the parse and beside the messages".
 *
 * Mirrors `rulekit.validate.laws.CalculationMemberWithholdingLawsTest` row for row. A row's verdict is the
 * model's COMPLETE class set, sorted, because the laws half compares every class A12 reports and the two
 * halves order a co-reported pair differently.
 */
class CalculationMemberWithholdingTest {

    @Test
    fun aTextDefectWithholdsItsOwnComputationsMemberClasses() {
        val actual = linkedMapOf<String, String>()
        val expected = linkedMapOf<String, String>()
        for (defect in Defect.entries) {
            actual["no member class · $defect"] = classes(plain(defect))
            expected["no member class · $defect"] = defect.alone
            for (shape in Shape.entries) {
                actual["$shape · $defect"] = classes(shape.written(defect))
                expected["$shape · $defect"] = if (defect == Defect.NONE) shape.code else defect.alone
            }
        }
        assertEquals(expected, actual, "a member class beside a defect of its own computation's texts")
    }

    @Test
    fun aDefectElsewhereWithholdsOnlyTheCycle() {
        val actual = linkedMapOf<String, String>()
        for (defect in listOf(Defect.PRECONDITION_ARITY, Defect.OPERATION_NAME)) {
            for (shape in Shape.entries) {
                actual["$shape · another computation's $defect"] =
                    classes(shape.written(Defect.NONE) + plain(defect).single().copy(name = "TaxCalc", target = "../Tax"))
            }
        }
        actual["CYCLE · another computation's rounding class"] =
            classes(Shape.CYCLE.written(Defect.NONE) + Calc("TaxCalc", "../Tax", "RoundUp", listOf(SOUND)))
        actual["CYCLE · another computation's mixed alternatives"] =
            classes(Shape.CYCLE.written(Defect.NONE) + Calc("TaxCalc", "../Tax", null, listOf(UNCONDITIONAL, SOUND)))
        actual["CYCLE · a sound rule — the control"] = classes(Shape.CYCLE.written(Defect.NONE), rule(PRECONDITION))
        actual["CYCLE · a rule's arity gate"] =
            classes(Shape.CYCLE.written(Defect.NONE), rule(Defect.PRECONDITION_ARITY.precondition))
        actual["CYCLE · a rule naming nothing"] =
            classes(Shape.CYCLE.written(Defect.NONE), rule(Defect.PRECONDITION_NAME.precondition))
        actual["ROUNDING · a rule's arity gate"] =
            classes(Shape.ROUNDING.written(Defect.NONE), rule(Defect.PRECONDITION_ARITY.precondition))
        actual["a structural class alone"] = classes(plain(Defect.NONE), BAD_FIELD)
        actual["CYCLE · a structural class"] = classes(Shape.CYCLE.written(Defect.NONE), BAD_FIELD)
        actual["ROUNDING · a structural class"] = classes(Shape.ROUNDING.written(Defect.NONE), BAD_FIELD)

        val expected = linkedMapOf(
            "ROUNDING · another computation's PRECONDITION_ARITY" to
                "MVK_ERROR_WRONG_ROUNDING_MODE,MVK_PARAMSIZE_INVALID1",
            "INDEX_TARGET · another computation's PRECONDITION_ARITY" to
                "MVK_ERROR_INDEX_AS_CALCULATED_FIELD,MVK_PARAMSIZE_INVALID1",
            "MIXED · another computation's PRECONDITION_ARITY" to
                "MVK_ERROR_EMPTY_PRECONDITION,MVK_PARAMSIZE_INVALID1",
            "CYCLE · another computation's PRECONDITION_ARITY" to "MVK_PARAMSIZE_INVALID1",
            "ROUNDING · another computation's OPERATION_NAME" to "MVK_ERROR_WRONG_ROUNDING_MODE,MVK_INVALID_ENTITY",
            "INDEX_TARGET · another computation's OPERATION_NAME" to
                "MVK_ERROR_INDEX_AS_CALCULATED_FIELD,MVK_INVALID_ENTITY",
            "MIXED · another computation's OPERATION_NAME" to "MVK_ERROR_EMPTY_PRECONDITION,MVK_INVALID_ENTITY",
            "CYCLE · another computation's OPERATION_NAME" to "MVK_INVALID_ENTITY",
            "CYCLE · another computation's rounding class" to "MVK_ERROR_WRONG_ROUNDING_MODE",
            "CYCLE · another computation's mixed alternatives" to "MVK_ERROR_EMPTY_PRECONDITION",
            "CYCLE · a sound rule — the control" to "MVK_ERROR_CALC_CYCLE",
            "CYCLE · a rule's arity gate" to "MVK_PARAMSIZE_INVALID1",
            "CYCLE · a rule naming nothing" to "MVK_INVALID_ENTITY",
            "ROUNDING · a rule's arity gate" to "MVK_ERROR_WRONG_ROUNDING_MODE,MVK_PARAMSIZE_INVALID1",
            "a structural class alone" to "MVK_PATH_INVALID",
            "CYCLE · a structural class" to "MVK_PATH_INVALID",
            "ROUNDING · a structural class" to "MVK_ERROR_WRONG_ROUNDING_MODE,MVK_PATH_INVALID",
        )
        assertEquals(expected, actual, "a member class beside a defect elsewhere in the model")
    }

    @Test
    fun theCalculationModuleAsksItsStagesInOrder() {
        val actual = linkedMapOf(
            "the language feature alone" to classes(listOf(total(null, Alternative(FEATURE, "[Subtotal]")))),
            "ROUNDING · the language feature" to classes(listOf(total("RoundUp", Alternative(FEATURE, "[Subtotal]")))),
            "MIXED · the language feature" to
                classes(listOf(total(null, UNCONDITIONAL, Alternative(FEATURE, "[Subtotal]")))),
            "INDEX_TARGET · the language feature" to
                classes(listOf(Calc("SkuCalc", "../Lines/Sku", null, listOf(Alternative(FEATURE, "[Coupon]"))))),
            "an enumeration source alone" to classes(listOf(tier(null, Alternative(PRECONDITION, "[Label]")))),
            "MIXED · an enumeration source" to
                classes(listOf(tier(null, Alternative(null, "[Label]"), Alternative(PRECONDITION, "[Label]")))),
            "INDEX_TARGET · an enumeration source" to
                classes(listOf(Calc("KindCalc", "../Parcels/Kind", null, listOf(Alternative(PRECONDITION, "[Label]"))))),
            "ROUNDING · an enumeration source" to classes(listOf(tier("RoundUp", Alternative(PRECONDITION, "[Label]")))),
            "a read of the computed field alone" to classes(listOf(label(Alternative(SELF_READ, "[Coupon]")))),
            "MIXED · a read of the computed field" to
                classes(listOf(label(Alternative(null, "[Coupon]"), Alternative(SELF_READ, "[Coupon]")))),
            "ROUNDING · a read of the computed field" to
                classes(listOf(Calc("LabelCalc", "../Label", "RoundUp", listOf(Alternative(SELF_READ, "[Coupon]"))))),
            "ROUNDING · a read of the computed NUMBER field" to
                classes(listOf(total("RoundUp", Alternative("FieldFilled(Total)", "[Subtotal]")))),
            "INDEX_TARGET · a read of the computed field" to classes(
                emptyList(),
                inLines = listOf(Calc("SkuCalc", "../Sku", null, listOf(Alternative("FieldFilled(Sku)", "\\\"x\\\"")))),
            ),
        )
        val expected = linkedMapOf(
            "the language feature alone" to FEATURE_CLASS,
            "ROUNDING · the language feature" to FEATURE_CLASS,
            "MIXED · the language feature" to FEATURE_CLASS,
            "INDEX_TARGET · the language feature" to Shape.INDEX_TARGET.code,
            "an enumeration source alone" to ENUM_SOURCE,
            "MIXED · an enumeration source" to "${Shape.MIXED.code},$ENUM_SOURCE",
            "INDEX_TARGET · an enumeration source" to Shape.INDEX_TARGET.code,
            "ROUNDING · an enumeration source" to ENUM_SOURCE,
            "a read of the computed field alone" to SELF_READ_CLASS,
            "MIXED · a read of the computed field" to "${Shape.MIXED.code},$SELF_READ_CLASS",
            "ROUNDING · a read of the computed field" to SELF_READ_CLASS,
            // Divergence lock: the checker is silent on a NUMBER target's self-read (the derived-scale gate
            // `ComputationSelfRead` documents), so the later stage's class stands in the place of A12's.
            "ROUNDING · a read of the computed NUMBER field" to Shape.ROUNDING.code,
            "INDEX_TARGET · a read of the computed field" to Shape.INDEX_TARGET.code,
        )
        assertEquals(expected, actual, "the calculation module's classes on one computation")
    }

    @Test
    fun theErrorTextClassesStandBesideTheCalculationModule() {
        val actual = linkedMapOf(
            "no message — the control" to classes(plain(Defect.NONE).unmessaged("TotalCalc")),
            "no message · a text the parser refuses" to
                classes(plain(Defect.PRECONDITION_ARITY).unmessaged("TotalCalc")),
        )
        for (shape in listOf(Shape.ROUNDING, Shape.INDEX_TARGET, Shape.MIXED)) {
            val calcs = shape.written(Defect.NONE)
            actual["no message · $shape"] = classes(calcs.unmessaged(calcs.first().name))
        }
        actual["no message · the language feature"] =
            classes(listOf(total(null, Alternative(FEATURE, "[Subtotal]"))).unmessaged("TotalCalc"))
        actual["no message · an enumeration source"] =
            classes(listOf(tier(null, Alternative(PRECONDITION, "[Label]"))).unmessaged("TierCalc"))
        actual["no message · a read of the computed field"] =
            classes(listOf(label(Alternative(SELF_READ, "[Coupon]"))).unmessaged("LabelCalc"))
        actual["no message on the computation the cycle names"] =
            classes(Shape.CYCLE.written(Defect.NONE).unmessaged("DiscountCalc"))
        actual["a refused character · ROUNDING"] =
            classes(Shape.ROUNDING.written(Defect.NONE).map { it.copy(message = "see\\tit") })

        val missing = "MVK_ERROR_MESSAGE_MISSING,RULE_HAS_NO_ERRORTEXT"
        val expected = linkedMapOf(
            "no message — the control" to missing,
            "no message · a text the parser refuses" to "MVK_ERROR_MESSAGE_MISSING,MVK_PARAMSIZE_INVALID1",
            "no message · ROUNDING" to "MVK_ERROR_MESSAGE_MISSING,${Shape.ROUNDING.code},RULE_HAS_NO_ERRORTEXT",
            "no message · INDEX_TARGET" to "${Shape.INDEX_TARGET.code},$missing",
            "no message · MIXED" to "${Shape.MIXED.code},$missing",
            "no message · the language feature" to "$FEATURE_CLASS,$missing",
            "no message · an enumeration source" to "$ENUM_SOURCE,$missing",
            "no message · a read of the computed field" to "MVK_ERROR_MESSAGE_MISSING,$SELF_READ_CLASS,RULE_HAS_NO_ERRORTEXT",
            "no message on the computation the cycle names" to missing,
            "a refused character · ROUNDING" to "INVALID_CHARACTERS,${Shape.ROUNDING.code}",
        )
        assertEquals(expected, actual, "the error-text classes beside the calculation module's")
    }

    @Test
    fun aCircleThroughAFilteredComputationIsNoCycle() {
        val cycle = Shape.CYCLE.written(Defect.NONE)
        val actual = linkedMapOf(
            "unfiltered — the control" to classes(cycle),
            "the computation it names, filtered" to
                classes(cycle.map { if (it.name == "DiscountCalc") it.copy(filtered = true) else it }),
            "the other computation, filtered" to
                classes(cycle.map { if (it.name == "TotalCalc") it.copy(filtered = true) else it }),
        )
        val expected = linkedMapOf(
            "unfiltered — the control" to Shape.CYCLE.code,
            "the computation it names, filtered" to ADMITTED,
            "the other computation, filtered" to ADMITTED,
        )
        assertEquals(expected, actual, "a dependency circle through a variant-filtered computation")
    }

    private enum class Defect(val alone: String) {
        NONE(ADMITTED),
        PRECONDITION_ARITY("MVK_PARAMSIZE_INVALID1"),
        PRECONDITION_NAME("MVK_INVALID_ENTITY"),
        OPERATION_ROUNDING("MVK_NO_NUMBER"),
        OPERATION_NAME("MVK_INVALID_ENTITY"),
        ;

        val precondition: String
            get() = when (this) {
                PRECONDITION_ARITY -> "FieldFilled(Coupon, Subtotal)"
                PRECONDITION_NAME -> "FieldFilled(NoSuchField)"
                NONE, OPERATION_ROUNDING, OPERATION_NAME -> PRECONDITION
            }

        fun operation(sound: String): String = when (this) {
            OPERATION_ROUNDING -> "$sound + RoundUpValue(Coupon, 2)"
            OPERATION_NAME -> "$sound + [NoSuchField]"
            NONE, PRECONDITION_ARITY, PRECONDITION_NAME -> sound
        }
    }

    private enum class Shape(val code: String) {
        ROUNDING("MVK_ERROR_WRONG_ROUNDING_MODE"),
        INDEX_TARGET("MVK_ERROR_INDEX_AS_CALCULATED_FIELD"),
        MIXED("MVK_ERROR_EMPTY_PRECONDITION"),
        CYCLE("MVK_ERROR_CALC_CYCLE"),
        ;

        fun written(defect: Defect): List<Calc> {
            val carrying = Alternative(defect.precondition, defect.operation("[Subtotal]"))
            return when (this) {
                ROUNDING -> listOf(Calc("TotalCalc", "../Total", "RoundUp", listOf(carrying)))
                INDEX_TARGET -> listOf(
                    Calc("SkuCalc", "../Lines/Sku", null, listOf(Alternative(defect.precondition, defect.operation("[Coupon]")))),
                )
                MIXED -> listOf(Calc("TotalCalc", "../Total", null, listOf(UNCONDITIONAL, carrying)))
                CYCLE -> listOf(
                    Calc("TotalCalc", "../Total", null, listOf(Alternative(defect.precondition, defect.operation("[Discount]")))),
                    Calc("DiscountCalc", "../Discount", null, listOf(Alternative(PRECONDITION, "[Total]"))),
                )
            }
        }
    }

    private data class Alternative(val precondition: String?, val operation: String)

    /** One computation; a null [message] declares none, and [filtered] takes it out of the default variant. */
    private data class Calc(
        val name: String,
        val target: String,
        val roundingMode: String?,
        val alternatives: List<Alternative>,
        val message: String? = "see it",
        val filtered: Boolean = false,
    )

    private fun plain(defect: Defect) = listOf(
        Calc("TotalCalc", "../Total", null, listOf(Alternative(defect.precondition, defect.operation("[Subtotal]")))),
    )

    private fun total(roundingMode: String?, vararg alternatives: Alternative) =
        Calc("TotalCalc", "../Total", roundingMode, alternatives.toList())

    private fun tier(roundingMode: String?, vararg alternatives: Alternative) =
        Calc("TierCalc", "../Tier", roundingMode, alternatives.toList())

    private fun label(vararg alternatives: Alternative) = Calc("LabelCalc", "../Label", null, alternatives.toList())

    private fun List<Calc>.unmessaged(name: String) = map { if (it.name == name) it.copy(message = null) else it }

    /** The model's complete A12 class set, sorted, or [ADMITTED]; [inLines] are declared inside `Lines`. */
    private fun classes(calcs: List<Calc>, extra: String? = null, inLines: List<Calc> = emptyList()): String =
        check(ModelSource(model((calcs.map(::computation) + listOfNotNull(extra)).joinToString(",\n"),
            inLines.joinToString("") { ",\n" + computation(it) })))
            .findings.filter { it.category == FindingCategory.A12_CONSTRAINT }
            .map { it.code }.distinct().sorted().joinToString(",").ifEmpty { ADMITTED }

    private fun computation(calc: Calc): String {
        val alternatives = calc.alternatives.joinToString(",") { alternative ->
            val precondition = alternative.precondition?.let { "\"precondition\":\"$it\"," }.orEmpty()
            """{$precondition"operation":"${alternative.operation}"}"""
        }
        val rounding = calc.roundingMode?.let { "\"roundingMode\":\"$it\"," }.orEmpty()
        val message = calc.message?.let { ""","errorMessage":[{"locale":"en_US","text":"$it"}]""" }.orEmpty()
        val filter = if (calc.filtered) ""","variantFilter":{"filterType":"ONLY","variants":["premium"]}""" else ""
        return """{"type":"Computation","id":"c${calc.name}","name":"${calc.name}","Computation":{
              "computedFieldRelPath":"${calc.target}",$rounding"computationAlternatives":[$alternatives]$message$filter}}"""
    }

    private fun rule(condition: String) = """{"type":"Rule","id":"rprobe","name":"Probe","Rule":{
          "errorCondition":"$condition","errorEntityRelPath":"../Coupon","errorCode":"C1","severity":"ERROR",
          "errorMessage":[{"locale":"en_US","text":"see it"}]}}"""

    /** The checkout of the laws test. */
    private fun model(declarations: String, inLines: String = ""): String = """
        {"header":{"id":"checkout","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Checkout","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Subtotal","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"maxFractionalDigits":2}}}},
            {"type":"Field","id":"f1","name":"Discount","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"maxFractionalDigits":2}}}},
            {"type":"Field","id":"f2","name":"Total","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"maxFractionalDigits":2}}}},
            {"type":"Field","id":"f3","name":"Tax","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"maxFractionalDigits":2}}}},
            {"type":"Field","id":"f4","name":"Coupon","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f7","name":"Label","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f8","name":"Tier","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[{"value":"GOLD"},{"value":"SILVER"}]}}}},
            {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":5,"indexFieldName":"Sku","elements":[
              {"type":"Field","id":"f5","name":"Sku","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f6","name":"Quantity","Field":{"fieldType":{"type":"NumberType",
                "NumberType":{"maxFractionalDigits":0}}}}$inLines
            ]}},
            {"type":"Group","id":"g2","name":"Parcels","Group":{"repeatability":3,"indexFieldName":"Kind","elements":[
              {"type":"Field","id":"f9","name":"Kind","Field":{"fieldType":{"type":"EnumerationType",
                "EnumerationType":{"values":[{"value":"BOX"},{"value":"ENVELOPE"}]}}}}
            ]}}${if (declarations.isEmpty()) "" else ",\n$declarations"}
          ]}}
         ]}}}
    """.trimIndent()

    private companion object {
        const val ADMITTED = "ADMITTED"
        const val PRECONDITION = "FieldFilled(Coupon)"
        val SOUND = Alternative(PRECONDITION, "[Subtotal]")
        val UNCONDITIONAL = Alternative(null, "[Subtotal]")
        const val FEATURE = "FieldFilled(Coupon) And CustomCondition NotReverse"
        const val SELF_READ = "FieldFilled(Label)"
        const val FEATURE_CLASS = "MVK_ERROR_INVALID_LANGUAGE_FEATURE"
        const val ENUM_SOURCE = "MVK_ERROR_INVALID_ENUM_CALCULATION"
        const val SELF_READ_CLASS = "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD"

        /** A field no text reads, whose hyphenated name A12 refuses as a path. */
        const val BAD_FIELD =
            """{"type":"Field","id":"fbad","name":"Gift-Note","Field":{"fieldType":{"type":"StringType"}}}"""
    }
}
