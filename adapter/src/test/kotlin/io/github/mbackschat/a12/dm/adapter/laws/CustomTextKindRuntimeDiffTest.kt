package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The runtime half of "a Custom declaration is string-LIKE" (KERNEL-FINDINGS KF396 for `+`, KF353's index-type
 * table for a join), on both kernel strategies and the interpreter: a String index key joined with a Custom one
 * matches by equal text, and `+` over a Custom field concatenates. The static locks admit both shapes and say
 * nothing about what they then do.
 *
 * The join rows discriminate text matching from "keys of different kinds never match", the account every measured
 * temporal pair fits (KF353): under that rival the equal-text row would leave the line without a partner and fire.
 * The unequal row is the positive control that the rule is live.
 *
 * Own domain (an order's lines joined with its stock bins by SKU; a generated order label).
 */
class CustomTextKindRuntimeDiffTest {

    private val base = DocumentModelDraft.named("order-custom-text").locale("en_US")
        .rootGroup("Order") { order -> order
            .customField("Code") { it.type(TYPE) }
            .stringField("Label") { }
            .group("Lines") { lines -> lines.repeatable(10).indexField("Sku")
                .stringField("Sku") { }
                .numberField("Qty") { it.fractionDigits(0) } }
            .group("Stock") { stock -> stock.repeatable(10).indexField("Sku")
                .customField("Sku") { it.type(TYPE) }
                .stringField("Bin") { } } }
        .build().toJson()

    private val bound = BoundModel.ofJson(base)
    private val model = RuntimeLaws.withComputation(
        RuntimeLaws.withRule(base, "/Order/Lines", "Unstocked", "../Qty", UNSTOCKED,
            RuntimeLaws.render(bound.num(QTY).filled().and(bound.str(BIN).notFilled()))),
        "/Order", "LabelComp", "../Label",
        RuntimeLaws.render("/Order", bound.custom(CODE).plus("x").expression()))
    private val interpreterModel = ModelLoader.load(model)
    private val kernelTypes = DeclarativeCustomFieldTypes.factoryOf(
        mapOf(TYPE to DeclarativeCustomFieldTypes.Declared.of(TYPE, null, null, null)))
    private val interpreterTypes = mapOf(TYPE to CustomFieldType { _, _ -> null })
    private val javaEngine by lazy { RuntimeLaws.kernelJavaEngine(model) }

    @Test
    fun aStringKeyMeetsACustomKeyOfTheSameText() = assertJoin("ab", emptyList())

    @Test
    fun aStringKeyFindsNoPartnerInACustomKeyOfAnotherText() =
        assertJoin("cd", listOf("$UNSTOCKED@/Order[1]/Lines[1]/Qty|OMISSION_ERROR"))

    @Test
    fun plusOverACustomFieldConcatenates() {
        val kernelDoc = RuntimeLaws.doc().group("/Order", 1).field(CODE, "ab", 1, 1)
        val dynamic = RuntimeLaws.computedValue(RuntimeLaws.compute(model, kernelDoc, kernelTypes), LABEL).orElse(null)
        assertEquals("abx", dynamic, "dynamic Groovy")
        val javaStatic = RuntimeLaws.computedValue(
            javaEngine.compute(javaEngine.instance(kernelDoc), kernelTypes), LABEL).orElse(null)
        assertEquals(dynamic, javaStatic, "static Java")
        val interpreterDoc = interpreterModel.newDocument().group("/Order", 1).field(CODE, "ab", 1, 1)
        val interpreter = DmInterpreter(interpreterModel, customFieldTypes = interpreterTypes)
            .compute(interpreterDoc).firstOrNull { it.path == LABEL }?.value
        assertEquals(dynamic, interpreter, "interpreter")
    }

    private fun assertJoin(stockSku: String, expected: List<String>) {
        val kernelDoc = RuntimeLaws.doc().group("/Order", 1)
            .group(LINES, 1, 1).field(LINE_SKU, "ab", 1, 1, 1).field(QTY, "1", 1, 1, 1)
            .group(STOCK, 1, 1).field(STOCK_SKU, stockSku, 1, 1, 1).field(BIN, "B1", 1, 1, 1)
        val dynamic = RuntimeLaws.messages(model, kernelDoc, kernelTypes, Locale.US)
            .filter { it.code() == UNSTOCKED }.map { "${it.code()}@${it.field()}|${it.type().name}" }
        assertEquals(expected, dynamic, "dynamic Groovy, stock SKU $stockSku")
        val javaStatic = javaEngine.messages(javaEngine.instance(kernelDoc), kernelTypes, Locale.US)
            .filter { it.code() == UNSTOCKED }.map { "${it.code()}@${it.field()}|${it.type().name}" }
        assertEquals(dynamic, javaStatic, "static Java, stock SKU $stockSku")
        val interpreterDoc = interpreterModel.newDocument().group("/Order", 1)
            .group(LINES, 1, 1).field(LINE_SKU, "ab", 1, 1, 1).field(QTY, "1", 1, 1, 1)
            .group(STOCK, 1, 1).field(STOCK_SKU, stockSku, 1, 1, 1).field(BIN, "B1", 1, 1, 1)
        val interpreter = DmInterpreter(interpreterModel, customFieldTypes = interpreterTypes)
            .validate(interpreterDoc).messages.filter { it.code == UNSTOCKED }
            .map { "${it.code}@${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }
        assertEquals(dynamic, interpreter, "interpreter, stock SKU $stockSku")
    }

    private companion object {
        const val TYPE = "SkuCode"
        const val CODE = "/Order/Code"
        const val LABEL = "/Order/Label"
        const val LINES = "/Order/Lines"
        const val STOCK = "/Order/Stock"
        const val LINE_SKU = "/Order/Lines/Sku"
        const val QTY = "/Order/Lines/Qty"
        const val STOCK_SKU = "/Order/Stock/Sku"
        const val BIN = "/Order/Stock/Bin"
        const val UNSTOCKED = "LINE_UNSTOCKED"
    }
}
