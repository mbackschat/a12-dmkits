package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A declared `,` decimal separator changes neither how a stored Number is read nor how a condition literal is
 * written (KERNEL-FINDINGS KF1; a12-kernel-lean SPEC-2026-10-30-09): twin models differing in `decimalSeparator`
 * alone give identical messages on every document, a stored `1,5` being a formal error under both, and the literal
 * `1,5` is refused under both.
 *
 * Every document compares dynamic Groovy, static Java and the interpreter; the messages are pinned from dynamic
 * Groovy. Own domain (an insurance quote's premium).
 */
class DeclaredCommaSeparatorDiffTest {

    private fun model(separator: String): String {
        val session = EditSession.read(DocumentModelDraft.named("quote-separator").locale("en_US")
            .rootGroup("Quote") { q -> q.numberField("Premium") { it.fractionDigits(1) } }.build().toJson())
        session.config().decimalSeparator(separator)
        val quote = session.group("/Quote")
        val premium = quote.num("Premium")
        mapOf<String, Condition>("ABOVE" to premium.gt("1.5"), "EXACT" to premium.eq(2), "GIVEN" to premium.filled())
            .forEach { (code, condition) ->
                quote.addRule { it.name(code.lowercase()).code(code).errorField(premium).`when`(condition)
                    .message(Dsl.en("Check the premium.")).build() }
            }
        return session.toJson()
    }

    private val comma = model(",")
    private val point = model(".")

    private val documents = linkedMapOf(
        "1,5" to """{"Quote":{"Premium":"1,5"}}""",
        "2,5" to """{"Quote":{"Premium":"2,5"}}""",
        "1.5" to """{"Quote":{"Premium":1.5}}""",
        "2" to """{"Quote":{"Premium":2}}""",
        "2.5" to """{"Quote":{"Premium":2.5}}""",
    )

    /** The control that keeps the twin comparison from being vacuous: the models differ in the separator alone. */
    @Test
    fun theTwinsDifferInTheSeparatorAlone() {
        val mapper = ObjectMapper()
        val separator = { model: String -> mapper.readTree(model).path("content").path("modelConfig").path("decimalSeparator").asText() }
        assertEquals(",", separator(comma))
        val aligned = mapper.readTree(comma) as ObjectNode
        (aligned.path("content").path("modelConfig") as ObjectNode).put("decimalSeparator", ".")
        assertEquals(mapper.readTree(point), aligned)
    }

    @Test
    fun a12AndThePortableCheckerAdmitBothTwins() {
        for (model in listOf(comma, point)) {
            assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
            assertEquals(emptyList<String>(), rejectingCodes(model))
        }
    }

    /** The typed surface cannot spell `1,5`, so the refused literal is written as condition text. */
    @Test
    fun aCommaLiteralIsRefusedUnderEitherSeparator() {
        for (model in listOf(comma, point)) {
            val literal = ModelWriter.addRule(model, "/Quote", "comma", "../Premium", "COMMA",
                "[/Quote/Premium] > 1,5", "ERROR", listOf(Dsl.en("Check the premium.")))
            assertEquals(listOf("MVK_UNEXPECTED_TOKEN"), MessageParameterProbes.kernel(literal))
            assertEquals(listOf("MVK_UNEXPECTED_TOKEN"), rejectingCodes(literal))
        }
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(comma, documents)
        DocumentDifferential.assertAgree(point, documents)
    }

    /** The comma-stored values are formal errors and the point-stored ones fire what they satisfy, under both twins. */
    @Test
    fun theTwinsGiveIdenticalMessages() {
        assertEquals(PINNED, codes(comma))
        assertEquals(PINNED, codes(point))
    }

    private fun codes(model: String) = documents.keys.associateWith { document ->
        DocumentDifferential.kernel(model, documents.getValue(document), dynamic = true).messages.map { it.code }.sorted()
    }

    private companion object {
        val PINNED = mapOf(
            "1,5" to listOf("zahlHatUngueltigeZeichen"),
            "2,5" to listOf("zahlHatUngueltigeZeichen"),
            "1.5" to listOf("GIVEN"),
            "2" to listOf("ABOVE", "EXACT", "GIVEN"),
            "2.5" to listOf("ABOVE", "GIVEN"),
        )
    }
}
