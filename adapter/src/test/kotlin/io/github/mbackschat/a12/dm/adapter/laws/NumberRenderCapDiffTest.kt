package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Kernel 31.3.1 caps a stored Number's runtime rendering at fifteen digits and rounds half up to the cap
 * (KERNEL-FINDINGS KF681). A stored number passes the parse with at most fifteen digits, so the cap binds where
 * the rendering pads to the field's minimum fraction digits or keeps more significant digits than fifteen.
 *
 * The pinned column is dynamic Groovy's; generated Java and the interpreter must observe every document as it
 * does. Own domain: a shipping ledger with an exact ten-decimal balance and a fourteen-decimal rate.
 */
class NumberRenderCapDiffTest {

    private val base = DocumentModelDraft.named("ledger").locale("en_US")
        .rootGroup("Shipment") { s ->
            s.stringField("Reference") { }
                .numberField("Ledger") { it.fractionDigits(10, 10) }
                .numberField("Rate") { it.fractionDigits(0, 14) }
        }
        .build().toJson()

    private val bound = BoundModel.ofJson(base)

    /** Fires when the rate's runtime text exceeds `1.12345678901234`, which only a half-up rounding makes it. */
    private val model = RuntimeLaws.withRule(base, "/Shipment", "RateAbove", "../Rate", "RATE_ABOVE",
        RuntimeLaws.render(bound.num("/Shipment/Rate").gt(BigDecimal("1.12345678901234"))))

    private fun doc(member: String, value: String) = """{"Shipment":{"$member":$value}}"""

    private fun codes(json: String) =
        DocumentDifferential.kernel(model, json, dynamic = true).messages.map { it.toString().substringBefore('|') }

    @Test
    fun aRenderingPastFifteenDigitsIsCappedAndRoundedHalfUp() {
        val rows = linkedMapOf(
            // 5 + 10 = 15 digits: the control, padded in full
            "ledger 12345" to doc("Ledger", "12345"),
            // 6 + 10 = 16: the pad stops at nine decimals, one short of the ten the field requires
            "ledger 123456" to doc("Ledger", "123456"),
            "ledger 1234567" to doc("Ledger", "1234567"),
            // a JSON string is converted by the same reader, so the cap reaches it too
            "ledger \"123456\"" to doc("Ledger", "\"123456\""),
            // seventeen significant digits round to fifteen, which the length check then admits: the rule's own
            // code alone, no length class
            "rate 1234567890.1234567" to doc("Rate", "1234567890.1234567"),
            // the discriminator against HALF_EVEN: the kept 4 is even, so only half-up exceeds the rule's constant
            "rate 1.123456789012345" to doc("Rate", "1.123456789012345"),
            // the control below the half: no rounding up, so the rule does not fire
            "rate 1.123456789012344" to doc("Rate", "1.123456789012344"),
        )
        assertEquals(
            mapOf(
                "ledger 12345" to emptyList(),
                "ledger 123456" to listOf("zahlFalscheAnzahlNachkommastellen"),
                "ledger 1234567" to listOf("zahlFalscheAnzahlNachkommastellen"),
                "ledger \"123456\"" to listOf("zahlFalscheAnzahlNachkommastellen"),
                "rate 1234567890.1234567" to listOf("RATE_ABOVE"),
                "rate 1.123456789012345" to listOf("RATE_ABOVE"),
                "rate 1.123456789012344" to emptyList(),
            ),
            rows.mapValues { (_, json) -> codes(json) },
            "dynamic Groovy's codes",
        )
        DocumentDifferential.assertAgree(model, rows)
    }
}
