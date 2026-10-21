package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.consistency.DiagnosticCode
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A12's JSON parser reads only RFC 8259 literals, so an unquoted token that is not `true`, `false`, `null` or a JSON
 * number leaves no model and no document (KERNEL-FINDINGS KF593), while kotlinx reads each of them as a primitive.
 * The rows put the token where any number is legal, a number field's `maxValue` and a number cell, so a refusal is the
 * parser's; `-0`, `1E1` and `5` are the controls that the same place reads a number at all. Own domain (a cover).
 */
class JsonLiteralStrictnessDiffTest {

    private val refused = listOf("abc", "True", "NaN", "0x10", "01", "1.", ".5", "+1")
    private val controls = listOf("-0", "1E1", "5")

    private val model = ObjectMapper().readTree(DocumentModelDraft.named("cover").locale("en_US")
        .rootGroup("Cover") { g -> g.numberField("Amount") { it.fractionDigits(0) } }.build().toJson()).toString()

    @Test
    fun aModelWithAnUnquotedNonLiteralIsReadByNoEngine() {
        val observed = (refused + controls).associateWith { literal ->
            val json = model.replace("\"NumberType\":{", "\"NumberType\":{\"maxValue\":$literal,")
            check(json != model) { "the carrier did not take the literal" }
            val checker = check(ModelSource(json)).diagnostics.none { it.code == DiagnosticCode.SOURCE_NOT_WELL_FORMED }
            "A12 ${RuntimeLaws.deserializes(json).verb()}, interpreter ${runCatching { ModelLoader.load(json) }.isSuccess.verb()}, " +
                "checker ${checker.verb()}"
        }
        assertEquals(observed.mapValues { (literal, _) -> if (literal in controls) READ_THREE else REFUSED_THREE }, observed)
    }

    @Test
    fun aDocumentWithAnUnquotedNonLiteralIsReadByNeitherEngine() {
        val prepared = PreparedModel.loadExpandedJson(model)
        val engine = RuntimeLaws.kernelEngine(model)
        val observed = (refused + controls).associateWith { literal ->
            val document = """{"Cover":{"Amount":$literal}}"""
            "A12 ${runCatching { engine.instance(document) }.isSuccess.verb()}, " +
                "interpreter ${runCatching { prepared.documents.readDocument(document) }.isSuccess.verb()}"
        }
        assertEquals(observed.mapValues { (literal, _) ->
            if (literal in controls) "A12 reads, interpreter reads" else "A12 refuses, interpreter refuses"
        }, observed)
    }

    private fun Boolean.verb() = if (this) "reads" else "refuses"

    private companion object {
        const val READ_THREE = "A12 reads, interpreter reads, checker reads"
        const val REFUSED_THREE = "A12 refuses, interpreter refuses, checker refuses"
    }
}
