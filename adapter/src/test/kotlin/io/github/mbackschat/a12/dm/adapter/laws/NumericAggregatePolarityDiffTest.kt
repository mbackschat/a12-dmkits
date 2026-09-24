package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-strategy lock for numeric aggregate fill polarity: groovy-dynamic == java-static == interpreter.
 * The all-empty `0` identity is fillable both ways, while a present-value multi-declaration `Sum` derives
 * shrinkability from the specifically missing declarations rather than from a global aggregate sign.
 */
class NumericAggregatePolarityDiffTest {

    private val base = RuntimeLaws.model("models/order.dm.json")
    private val m = BoundModel.ofJson(base)

    @Test fun unsignedAllEmptyValueAggregatesHaveBothDirectionPolarity() {
        val star = m.flatten("/Order/Items*/StockQty")
        agree(Dsl.sum(star).ge(0), "OMISSION_ERROR", "all-empty unsigned Sum") { }
        agree(Dsl.maxValue(star).ge(0), "OMISSION_ERROR", "all-empty unsigned MaxValue") { }
        agree(Dsl.minValue(star).ge(0), "OMISSION_ERROR", "all-empty unsigned MinValue") { }
    }

    @Test fun presentUnsignedStarWithOmittedTailRemainsGrowOnly() {
        agree(Dsl.sum(m.flatten("/Order/Items*/StockQty")).ge(0), "VALUE_ERROR", "present unsigned Sum with tail") {
            item(1, stock = "5")
        }
    }

    @Test fun multiDeclarationSumUsesTheMissingDeclarationsSignedness() {
        val sum = Dsl.sum(m.field(QUANTITY), m.field(STOCK)).ge(0)
        agree(sum, "VALUE_ERROR", "only the unsigned declaration is missing") { field(QUANTITY, "5", 1, 1) }
        agree(sum, "OMISSION_ERROR", "only the signed declaration is missing") { field(STOCK, "5", 1, 1) }
    }

    private fun agree(condition: Condition, expected: String, label: String, build: Both.() -> Unit) {
        val rendered = RuntimeLaws.render(m.field(ANCHOR).filled().and(condition))
        val kernelModel = RuntimeLaws.withRule(base, ROOT, CODE, "../CustomerName", CODE, rendered)
        val definition = ModelLoader.load(kernelModel)
        val documents = Both(RuntimeLaws.doc(), definition.newDocument())
            .group(ROOT, 1)
            .field(ANCHOR, "Acme", 1, 1)
            .apply(build)

        val groovy = RuntimeLaws.typeOf(kernelModel, CODE, documents.kDoc).orElseThrow().name
        val javaEngine = RuntimeLaws.kernelJavaEngine(kernelModel)
        val javaStatic = javaEngine.messages(javaEngine.instance(documents.kDoc)).single { it.code() == CODE }.type().name
        val interpreter = DmInterpreter(definition).validateFull(documents.iDoc).single { it.code == CODE }.type
            .let { if (it == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }

        assertEquals(expected, groovy, "$label — expected kernel outcome")
        assertEquals(groovy, javaStatic, "$label — groovy-dynamic vs java-static")
        assertEquals(groovy, interpreter, "$label — kernel vs interpreter")
    }

    private fun Both.item(row: Int, stock: String): Both = group(ITEMS, 1, row)
        .field(SKU, "S$row", 1, row, 1)
        .field(STOCK_ITEM, stock, 1, row, 1)

    private companion object {
        const val ROOT = "/Order"
        const val ANCHOR = "/Order/CustomerName"
        const val QUANTITY = "/Order/Quantity"
        const val STOCK = "/Order/StockOnHand"
        const val ITEMS = "/Order/Items"
        const val SKU = "/Order/Items/Sku"
        const val STOCK_ITEM = "/Order/Items/StockQty"
        const val CODE = "NUMAGG"
    }
}
