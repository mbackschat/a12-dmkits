package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DocumentInputError
import io.github.mbackschat.a12.dm.interpreter.DocumentInputReasonCode
import io.github.mbackschat.a12.dm.interpreter.DocumentInputResource
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A number literal's exponent at the edges of A12's converter (KERNEL-FINDINGS KF564). A literal converts exactly
 * when its scale, fraction length minus exponent, fits 32 bits, so a zero at the widest scale is `0` and one place
 * further is unconvertible text; an unquoted token past that bound makes the Document unreadable wherever it
 * stands. Past the reader's per-field ceiling the interpreter refuses a value A12 would expand, and the rows pin
 * what A12 does there instead.
 *
 * Own domain (an insurance premium with its cover and riders).
 */
class NumberExponentEdgeDiffTest {

    private val model = DocumentModelDraft.named("premium-exponents").locale("en_US")
        .rootGroup("Policy") { p -> p.numberField("Premium") { it.fractionDigits(2) }.stringField("Note") { }
            .group("Cover") { c -> c.numberField("Sum") { it.fractionDigits(0) } }
            .group("Riders") { r -> r.repeatable(3).stringField("Code") { } } }
        .build().toJson()

    private fun premium(literal: String) = """{"Policy":{"Premium":$literal}}"""

    private val readable = linkedMapOf(
        "a zero at the widest negative scale" to premium("\"0e2147483648\""),
        "a negative zero there" to premium("\"-0e2147483648\""),
        "a zero far past the materializing ceiling" to premium("\"0e99999999\""),
        "an unquoted zero at the widest scale" to premium("0e2147483648"),
        "a zero one place past the widest scale" to premium("\"0e2147483649\""),
        "a scale past 32 bits on the positive side" to premium("\"1.5e-2147483647\""),
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
    }

    @Test
    fun aLiteralConvertsExactlyWhenItsScaleFitsThirtyTwoBits() {
        val observed = readable.mapValues { (_, json) ->
            DocumentDifferential.kernelMessages(model, json, true).map { "${it.code}@${it.pointer}" }
        }
        val unconvertible = listOf("zahlHatUngueltigeZeichen@/Policy[1]/Premium")
        assertEquals(linkedMapOf(
            "a zero at the widest negative scale" to emptyList(),
            "a negative zero there" to emptyList(),
            "a zero far past the materializing ceiling" to emptyList(),
            "an unquoted zero at the widest scale" to emptyList(),
            "a zero one place past the widest scale" to unconvertible,
            "a scale past 32 bits on the positive side" to unconvertible,
        ), observed)
        DocumentDifferential.assertAgree(model, readable)
    }

    @Test
    fun anUnquotedTokenPastThirtyTwoBitsLeavesTheDocumentUnreadableWhereverItStands() {
        val positions = linkedMapOf(
            "a number field" to premium("0e2147483649"),
            "a string field" to """{"Policy":{"Note":0e2147483649}}""",
            "an unknown key" to """{"Policy":{"Premium":1,"Discount":0e2147483649}}""",
            "inside an unknown subtree" to """{"Policy":{"Discount":{"Tiers":[{"Rate":0e2147483649}]}}}""",
            "a group position" to """{"Policy":{"Cover":0e2147483649}}""",
            "a repetition that is no object" to """{"Policy":{"Riders":[{"Code":"a"},0e2147483649]}}""",
            "the root id" to """{"id":0e2147483649,"Policy":{}}""",
        )
        for ((position, json) in positions) {
            assertThrows<NumberFormatException>(position) { DocumentDifferential.kernelMessages(model, json, true) }
            assertThrows<NumberFormatException>(position) { DocumentDifferential.kernelMessages(model, json, false) }
            val refusal = assertThrows<DocumentInputError>(position) { DocumentDifferential.interpreterMessages(model, json) }
            assertEquals(DocumentInputReasonCode.MALFORMED_JSON, refusal.diagnostics.single().reasonCode, position)
        }
    }

    /**
     * The characterized divergence: the interpreter refuses a value whose stored plain form exceeds the per-field
     * ceiling, a million code units by default. A12 expands it instead: a non-zero value past every declarable
     * digit count draws `stelligkeitZuLang`, a zero is valid, and at the widest scale the arithmetic faults.
     */
    @Test
    fun pastThePerFieldCeilingTheInterpreterRefusesWhatA12Expands() {
        val wide = linkedMapOf(
            "a million and one integer digits" to premium("\"1e1000000\""),
            "a million fraction places" to premium("\"1e-1000000\""),
            "a zero at a million places" to premium("\"0e-1000000\""),
        )
        val kernel = wide.mapValues { (_, json) ->
            DocumentDifferential.kernelMessages(model, json, true).map { it.code } +
                DocumentDifferential.kernelMessages(model, json, false).map { "java:${it.code}" }
        }
        assertEquals(linkedMapOf(
            "a million and one integer digits" to listOf("stelligkeitZuLang", "java:stelligkeitZuLang"),
            "a million fraction places" to listOf("stelligkeitZuLang", "java:stelligkeitZuLang"),
            "a zero at a million places" to emptyList(),
        ), kernel)
        val fault = assertThrows<Exception> { DocumentDifferential.kernelMessages(model, premium("\"1e2147483648\""), true) }
        assertEquals("Underflow", generateSequence(fault as Throwable) { it.cause }.last().message)

        for ((label, json) in wide + ("the widest scale" to premium("\"1e2147483648\""))) {
            val refusal = assertThrows<DocumentInputError>(label) { DocumentDifferential.interpreterMessages(model, json) }
            val diagnostic = refusal.diagnostics.single()
            assertEquals(DocumentInputResource.RAW_CODE_UNITS_PER_FIELD, diagnostic.resource, label)
            assertEquals("Policy/Premium", diagnostic.pointer, label)
        }
    }
}
