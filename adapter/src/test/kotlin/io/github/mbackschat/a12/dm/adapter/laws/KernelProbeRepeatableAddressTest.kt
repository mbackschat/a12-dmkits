package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The probe artifact's address convention **on a repeatable carrier** — the rows the existing address
 * assertion cannot see. [KernelProbeComputeTest.anOutcomeAddressUsesThePublishedMessageAddressForm] states
 * "the implicit `[1]` omitted", but its model is entirely non-repeatable, so no row in that set has a
 * repetition at all and the claim rides on a carrier that cannot falsify it. Here row 1 renders unindexed,
 * rows 2 and 3 carry `[2]`/`[3]`, and a declaration-level outcome sits in the same artifact.
 *
 * <p><b>Why it matters to a consumer, and why the shape is not changing.</b> a12-kernel-lean's agent spent a
 * second probe run establishing which document row an unindexed outcome named (their instrument note,
 * 2026-09-04) and asked for `[1]` to be emitted. It is not, and the reason is the project's first interop
 * rule rather than a preference: `[1]`-omission is A12's OWN canonical pointer form — `DocumentPointerImpl`'s
 * string form omits it and an absent bracket means repetition 1 (KERNEL-FINDINGS `kf1`) — so emitting it
 * would publish a pointer A12 does not write, in a field whose whole contract is that it can be pasted back
 * into A12. What was genuinely missing is that the convention was documented on the `message` record and only
 * cross-referenced from the outcome property a compute consumer actually reads; the schema now states it in
 * both places, and these rows lock it.
 *
 * <p><b>The ambiguity is real and is stated, not designed away.</b> `Basket/Subtotal` (a form-level computed
 * field) and `Basket/Lines/Total` (row 1 of a repeatable group) are both bracket-free, so the string alone
 * does not say whether a level repeats — that is a property of the MODEL, and a consumer joining outcomes to
 * rows resolves it there. Both are asserted below so the pair is visible rather than inferred.
 *
 * <p>Own domain: an e-commerce basket. `Total` = quantity × price + surcharge per line, `Subtotal` sums the
 * line prices. Row 1's surcharge carries three decimals into a two-decimal field, which is a formal error on
 * a per-row OPERAND — so `formalErrorsInOperands` answers the same question on the same carrier, which is the
 * half their note could only ask about.
 */
class KernelProbeRepeatableAddressTest {

    /** Row 1 unindexed, later rows indexed — one channel, one carrier, three repetitions. */
    @Test
    fun theFirstRepetitionIsUnindexedAndLaterRepetitionsCarryTheirIndex() {
        assertEquals(
            listOf(
                "Basket/Lines/Total",
                "Basket/Lines[2]/Total",
                "Basket/Lines[3]/Total",
                "Basket/Subtotal",
            ),
            outcomeAddresses(),
            "canonical A12: no leading slash, the implicit [1] omitted, sorted by address",
        )
    }

    /**
     * The declaration-level outcome and row 1 are indistinguishable AS STRINGS, which is the consumer-visible
     * consequence of the convention and the thing to know rather than to discover twice.
     */
    @Test
    fun aFormLevelOutcomeAndTheFirstRepetitionAreBothBracketFree() {
        val addresses = outcomeAddresses()
        // Both must be PRESENT and both bracket-free. The first form of this read `it in addresses &&
        // it.contains('[')` over the two literals, which is a tautology — a literal without a bracket makes
        // the right conjunct false whatever the artifact says, so it passed on an empty artifact too.
        assertEquals(
            listOf(true, true),
            listOf("Basket/Subtotal", "Basket/Lines/Total").map { it in addresses },
            "the collision needs both members actually present in the artifact",
        )
        assertEquals(
            emptyList<String>(),
            addresses.filter { it.startsWith("Basket/Subtotal") || it.startsWith("Basket/Lines/Total") }
                .filter { it.contains('[') },
            "…and neither carries a bracket — the level's repeatability is a model fact, not an address fact",
        )
    }

    /** `formalErrorsInOperands` publishes the same address form: a row-1 operand error is unindexed too. */
    @Test
    fun anOperandFormalErrorOnTheFirstRepetitionIsAlsoUnindexed() {
        assertEquals(
            listOf("Basket/Lines/Surcharge"),
            anchor().path("computations").path("formalErrorsInOperands").map { it.path("field").asText() },
            "one record shape and one address contract across both compute channels",
        )
    }

    private fun outcomeAddresses(): List<String> =
        anchor().path("computations").path("outcomes").map { it.path("field").asText() }

    private fun anchor(): JsonNode = artifact.path("rows")[0]
        .path("engines").single { it.path("engine").asText() == KernelRuntimeProbe.GROOVY_DYNAMIC }

    companion object {

        private val MAPPER = ObjectMapper()

        private const val ROOT = "/Basket"
        private const val SUBTOTAL = "$ROOT/Subtotal"
        private const val LINES = "$ROOT/Lines"
        private const val QTY = "$LINES/Qty"
        private const val PRICE = "$LINES/Price"
        private const val SURCHARGE = "$LINES/Surcharge"
        private const val TOTAL = "$LINES/Total"

        @JvmStatic
        @TempDir
        lateinit var dir: Path

        /** Authored through modelkit + the typed computation builder, so every path is written once. */
        private val model: String by lazy {
            val base = BoundModel.ofJson(
                DocumentModelDraft.named("probe-repeatable-address").locale("en_US")
                    .rootGroup("Basket") { basket ->
                        basket.numberField("Subtotal") { it.fractionDigits(2, 2) }
                            .group("Lines") { lines ->
                                lines.repeatable(5)
                                    .numberField("Qty") { it.fractionDigits(0, 0) }
                                    .numberField("Price") { it.fractionDigits(2, 2) }
                                    .numberField("Surcharge") { it.fractionDigits(2, 2) }
                                    .numberField("Total") { it.fractionDigits(2, 2) }
                            }
                    }.build().toJson(),
            )
            val lineTotal = base.computation(base.num(TOTAL)).name("LineTotalComputation")
                .calc(
                    Dsl.roundAccounting(
                        base.num(QTY).asNumber().times(base.num(PRICE).asNumber())
                            .plus(base.num(SURCHARGE).asNumber()),
                        2,
                    ).expression(),
                )
                .message(Dsl.en("Line total including its surcharge.")).build()
            val subtotal = base.computation(base.num(SUBTOTAL)).name("SubtotalComputation")
                .calc(Dsl.sum(base.flatten("$LINES*/Price")).expression())
                .message(Dsl.en("Sum of the line prices.")).build()
            base.addAll(emptyList(), listOf(lineTotal, subtotal)).toJson()
        }

        /**
         * Three line rows, every computed target seeded so a skipped computation reports as a CLEAR rather
         * than as silence. Row 1's surcharge carries three decimals into a two-decimal field — a formal error
         * on a per-row operand, whose address is the point of the third case.
         */
        private val ROW = """
            { "name": "three-lines", "documentInline": { "Basket": {
                "Subtotal": 0.00,
                "Lines": [
                  { "Qty": 2, "Price": 10.00, "Surcharge": 1.234, "Total": 0.00 },
                  { "Qty": 3, "Price": 20.00, "Surcharge": 2.00, "Total": 0.00 },
                  { "Qty": 1, "Price": 30.00, "Surcharge": 3.00, "Total": 0.00 }
                ] } } }
        """

        private fun write(name: String, content: String): Path = Files.writeString(dir.resolve(name), content)

        private val artifact: JsonNode by lazy {
            val request = write(
                "repeatable-address.request.json",
                """{ "model": "${write("repeatable-address.model.json", model).fileName}",
                     "observe": ["validateFull", "compute"], "rows": [ ${ROW.trimIndent()} ] }""",
            )
            val out = dir.resolve("repeatable-address-artifact.json")
            KernelRuntimeProbe.main(arrayOf(request.toString(), out.toString()))
            MAPPER.readTree(Files.readString(out))
        }
    }
}
