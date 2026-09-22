package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Tolerance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SPEC-2026-07-19-13 handback: separating truth and polarity cases against Groovy-dynamic, generated Java,
 * and the interpreter. The typed rulekit surface authors every condition.
 */
class ToleranceExactnessDiffTest {

    private val modelJson = RuntimeLaws.model("models/order.dm.json")
    private val model = BoundModel.ofJson(modelJson)
    private val fields = listOf(
        FieldDef(QTY, Kind.NUMBER, scale = 0),
        FieldDef(BACK, Kind.NUMBER, scale = 0),
        FieldDef(STOCK, Kind.NUMBER, scale = 0, signed = false),
        FieldDef(PRICE, Kind.NUMBER, scale = 2),
    )

    @Test
    fun independentlyRoundsOperandsBeforeTakingTheDifference() {
        val epsilon = Dsl.number("0.000000000000000000049")
        val condition = model.num(QTY).asNumber().plus(epsilon)
            .differsWithTolerance(Tolerance.RANGE_1, Dsl.number(0).minus(epsilon))

        agree(condition, mapOf(QTY to "1"), expected = null,
            clue = "R19(1+epsilon)-R19(-epsilon) is exactly the closed boundary 1")
    }

    @Test
    fun unsignedEmptyPolarityUsesTheDirectionOfTheFiringGap() {
        val condition = model.num(STOCK).differsWithTolerance(Tolerance.RANGE_1, model.num(BACK))

        agree(condition, mapOf(BACK to "-2"), expected = "VALUE_ERROR", errorField = STOCK,
            clue = "0 > -2: unsigned empty left cannot shrink toward the filled right")
        agree(condition, mapOf(BACK to "2"), expected = "OMISSION_ERROR", errorField = STOCK,
            clue = "0 < 2: unsigned empty left can grow toward the filled right")
        agree(condition, mapOf(STOCK to "abc"), expected = null, errorField = STOCK,
            clue = "malformed numeric input suppresses the rule")
    }

    @Test
    fun fixedBandsMixedScalesAndNumericBaseYearStayAdmitted() {
        val cases = listOf(
            Triple(model.num(QTY).differsWithTolerance(Tolerance.RANGE_1, model.num(BACK)),
                mapOf(QTY to "5", BACK to "6"), null),
            Triple(model.num(QTY).differsWithTolerance(Tolerance.RANGE_2, model.num(BACK)),
                mapOf(QTY to "5", BACK to "8"), "VALUE_ERROR"),
            Triple(model.num(QTY).differsWithTolerance(Tolerance.RANGE_5, model.num(BACK)),
                mapOf(QTY to "5", BACK to "10"), null),
            Triple(model.num(QTY).differsWithTolerance(Tolerance.RANGE_10, model.num(BACK)),
                mapOf(QTY to "5", BACK to "16"), "VALUE_ERROR"),
            Triple(model.num(PRICE).asNumber().differsWithTolerance(
                Tolerance.RANGE_1, model.num(QTY).asNumber()),
                mapOf(PRICE to "7.25", QTY to "5"), "VALUE_ERROR"),
            Triple(model.num(QTY).asNumber().differsWithTolerance(
                Tolerance.RANGE_1, Dsl.baseYearNumber()),
                mapOf(QTY to "2022"), "VALUE_ERROR"),
        )
        cases.forEachIndexed { index, (condition, values, expected) ->
            agree(condition, values, expected, "fixed/mixed/BaseYear case $index")
        }
    }

    private fun agree(
        condition: Condition,
        values: Map<String, String>,
        expected: String?,
        clue: String,
        errorField: String = QTY,
    ) {
        val dsl = RuntimeLaws.render(condition)
        val code = "TOL_EXACT"
        val errorRel = "../${errorField.substringAfterLast('/')}"
        val kernelModel = RuntimeLaws.withRule(modelJson, "/Order", "ToleranceExact", errorRel, code, dsl)
        val kernelDoc = RuntimeLaws.doc().group("/Order", 1)
        values.forEach { (path, value) -> kernelDoc.field(path, value, 1, 1) }

        val dynamic = RuntimeLaws.typeOf(kernelModel, code, kernelDoc).map { it.name }.orElse(null)
        val javaEngine = RuntimeLaws.kernelJavaEngine(kernelModel)
        val generated = javaEngine.messages(javaEngine.instance(kernelDoc))
            .firstOrNull { it.code() == code }?.type()?.name

        val definition = ModelDef(
            fields,
            listOf(RuleDef("/Order/ToleranceExact", "/Order", dsl, code, errorRel)),
            baseYear = 2020,
        )
        val document = definition.newDocument().group("/Order", 1)
        values.forEach { (path, value) -> document.field(path, value, 1, 1) }
        val interpreter = DmInterpreter(definition).validate(document).messages.firstOrNull { it.code == code }?.type
            ?.let { if (it == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }

        assertEquals(expected, dynamic, "$clue — dynamic")
        assertEquals(dynamic, generated, "$clue — generated Java")
        assertEquals(dynamic, interpreter, "$clue — interpreter")
    }

    private companion object {
        const val QTY = "/Order/Quantity"
        const val BACK = "/Order/BackorderQuantity"
        const val STOCK = "/Order/StockOnHand"
        const val PRICE = "/Order/UnitPrice"
    }
}
