package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "a refused text withholds the calculation module's member classes".
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

    private data class Calc(val name: String, val target: String, val roundingMode: String?, val alternatives: List<Alternative>)

    private fun plain(defect: Defect) = listOf(
        Calc("TotalCalc", "../Total", null, listOf(Alternative(defect.precondition, defect.operation("[Subtotal]")))),
    )

    /** The model's complete A12 class set, sorted, or [ADMITTED]. */
    private fun classes(calcs: List<Calc>, extra: String? = null): String =
        check(ModelSource(model((calcs.map(::computation) + listOfNotNull(extra)).joinToString(",\n"))))
            .findings.filter { it.category == FindingCategory.A12_CONSTRAINT }
            .map { it.code }.distinct().sorted().joinToString(",").ifEmpty { ADMITTED }

    private fun computation(calc: Calc): String {
        val alternatives = calc.alternatives.joinToString(",") { alternative ->
            val precondition = alternative.precondition?.let { "\"precondition\":\"$it\"," }.orEmpty()
            """{$precondition"operation":"${alternative.operation}"}"""
        }
        val rounding = calc.roundingMode?.let { "\"roundingMode\":\"$it\"," }.orEmpty()
        return """{"type":"Computation","id":"c${calc.name}","name":"${calc.name}","Computation":{
              "computedFieldRelPath":"${calc.target}",$rounding"computationAlternatives":[$alternatives],
              "errorMessage":[{"locale":"en_US","text":"see it"}]}}"""
    }

    private fun rule(condition: String) = """{"type":"Rule","id":"rprobe","name":"Probe","Rule":{
          "errorCondition":"$condition","errorEntityRelPath":"../Coupon","errorCode":"C1","severity":"ERROR",
          "errorMessage":[{"locale":"en_US","text":"see it"}]}}"""

    /** The checkout of the laws test. */
    private fun model(declarations: String): String = """
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
            {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":5,"indexFieldName":"Sku","elements":[
              {"type":"Field","id":"f5","name":"Sku","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f6","name":"Quantity","Field":{"fieldType":{"type":"NumberType",
                "NumberType":{"maxFractionalDigits":0}}}}
            ]}},
            $declarations
          ]}}
         ]}}}
    """.trimIndent()

    private companion object {
        const val ADMITTED = "ADMITTED"
        const val PRECONDITION = "FieldFilled(Coupon)"
        val SOUND = Alternative(PRECONDITION, "[Subtotal]")
        val UNCONDITIONAL = Alternative(null, "[Subtotal]")

        /** A field no text reads, whose hyphenated name A12 refuses as a path. */
        const val BAD_FIELD =
            """{"type":"Field","id":"fbad","name":"Gift-Note","Field":{"fieldType":{"type":"StringType"}}}"""
    }
}
