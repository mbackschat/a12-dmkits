package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Locks the mixed absent-key rows requested by a12-kernel-lean on 2026-09-30, not just P3 squared. */
class AbsentKeyedProductDiffTest {
    private val base = DocumentModelDraft.named("parcel-product-provenance").locale("en_US")
        .rootGroup("Order") { order -> order
            .stringField("Reference") { }
            .group("Packages") { packages -> packages.repeatable(5).indexField("Code")
                .stringField("Code") { }
                .group("Items") { items -> items.repeatable(3)
                    .numberField("Weight") { it.fractionDigits(0, 0) } }
            }
        }.build().toJson()

    private val order = EditSession.read(base).group("/Order")
    private val reference = order.str("Reference")
    private val code = order.str("Packages/Code")
    private val weight = order.num("Packages/Items/Weight")
    private val weights = order.flatten("Packages/Items*/Weight")

    private data class Row(val left: String, val right: String, val comparison: Long, val fires: Boolean)

    private fun model(row: Row): String {
        return ModelWriter.addRule(base, order.rule {
            it.name("MissingPackageProduct").code("PRODUCT").errorField(reference.field())
                .`when`(reference.filled().and(
                    Dsl.sumOfProducts(weights.forIndex(row.left), weights.forIndex(row.right)).eq(row.comparison)))
                .message(Dsl.en("Check the parcel product.")).build()
        })
    }

    private fun seed(model: String): Both {
        val both = Both(RuntimeLaws.doc(), ModelLoader.load(model).newDocument())
        both.field(reference.field().path().value(), "ORD-1", 1, 1)
            .field(code.field().path().value(), "P1", 1, 1, 1)
            .field(weight.field().path().value(), "2", 1, 1, 1, 1)
            .field(weight.field().path().value(), "5", 1, 1, 2, 1)
        return both
    }

    private fun signature(messages: List<RuntimeLaws.Msg>) = messages.filter { it.code() == "PRODUCT" }
        .map { "${it.field()}|${it.type().name}" }.sorted()

    @Test
    fun anAbsentKeyMakesTheMixedProductZeroInEitherOperandOrder() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(base).filter { it.startsWith("ERROR") })
        val rows = listOf(
            Row("P1", "P3", 0, true), Row("P3", "P1", 0, true),
            Row("P1", "P3", 1, false), Row("P3", "P1", 1, false),
            Row("P1", "P1", 29, true), Row("P3", "P3", 0, true),
        )
        for (row in rows) {
            val model = model(row)
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model).filter { it.startsWith("ERROR") },
                "admission: $row")
            val expected = if (row.fires) listOf("/Order[1]/Reference|OMISSION_ERROR") else emptyList()
            val document = seed(model)
            assertEquals(expected, signature(RuntimeLaws.messages(model, document.kDoc)), "dynamic Groovy: $row")
            assertEquals(expected, signature(RuntimeLaws.kernelJavaEngine(model)
                .messagesModern(seed(model).kDoc, null, Locale.US)), "static Java: $row")
            val result = DmInterpreter(ModelLoader.load(model)).validate(document.iDoc)
            assertEquals(0, result.unsupported.size, "interpreter support: $row: ${result.unsupported}")
            assertEquals(expected, result.messages.filter { it.code == "PRODUCT" }.map {
                "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}"
            }.sorted(), "interpreter: $row")
        }
    }
}
