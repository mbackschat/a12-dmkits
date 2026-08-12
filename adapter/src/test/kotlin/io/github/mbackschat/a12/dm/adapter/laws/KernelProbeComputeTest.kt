package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * [KernelRuntimeProbe]'s kernel-backed acceptance for the `compute` observation — what the REAL kernel's
 * `compute()` reported, on both codegen strategies, reached through the request file rather than through a
 * purpose-written Kotlin test. The validation half is [KernelRuntimeProbeTest]; the shape half (byte
 * compatibility, a constructed strategy split, the outcome order, the `observe` refusals) is
 * [KernelProbeComputeShapeTest].
 *
 * **The load-bearing channel is the operand formal-error inventory.** Value/cleared/errored is already reachable
 * through the interpreter's `model compute`; what no route this project can reach exposes is the kernel's
 * pre-computation formal validation of computation operands. The two rows below are chosen so that channel has
 * to be a genuine second observation: one row's clear is accompanied by an operand formal error that names its
 * field, the other's is caused by an unmet precondition and names nothing. An implementation that mirrored the
 * outcome list into the error channel would pass the first assertion and fail the second.
 *
 * **It is an inventory, not a causal attribution** — `IDocumentRtService.compute` validates the operand set
 * before anything runs and computes anyway ("Regardless of the formal errors found, computations will be
 * performed. Any computations relying directly or indirectly on invalid fields will be skipped"), so an entry
 * here does not imply that any target was skipped, and no edge to the outcome it explains is published. The
 * row below shows both halves: `ShippingFee`'s error sits in the channel, `OrderTotal` is skipped, and the
 * unrelated `LineTotal` computes normally.
 *
 * Own domain: an e-commerce order. `LineTotal` = unit price × quantity, `OrderTotal` adds the shipping fee,
 * `RushFee` is a percentage of the line total charged only on an express tier, and `LoyaltyPoints` awards a flat
 * ten against a field that permits at most nine — a deliberate out-of-range computed value, which the kernel
 * returns and flags rather than suppresses (KERNEL-FINDINGS `kf19`). `HandlingNote` is read by nothing and
 * exists only to carry a formal error no computation sees.
 *
 * **One limit of the channel, measured and not papered over:** it inventories the formal errors of *stored*
 * operands. A field cleared because the computation it read was itself ERRORED carries no entry — the kernel
 * erases the cause across a dependency edge, which is a separate recorded finding
 * (`ComputedDependencyCauseErasureDiffTest`) rather than something this exposure can repair.
 */
class KernelProbeComputeTest {

    // ---- acceptance case 2: the three buckets are distinct ---------------------------------------

    /**
     * One document, all three buckets: `LineTotal` computes a value, `OrderTotal` is cleared, `LoyaltyPoints`
     * errors. Each is read off the same engine block, so this is one kernel observation rather than three that
     * happen to agree.
     *
     * The collapse this fails is `cleared` rendered as an empty value: a cleared outcome carries **no `value`
     * key at all**, while a valued one carries it, so the two are distinguishable by key presence and not by
     * guessing what an empty string meant. (On kernel 30.8.1 a computation whose final result is empty text
     * stores nothing and is reported CLEARED rather than `VALUE ""` — IF123 — so the empty-string arm is a
     * guarantee of the shape rather than a state this kernel reaches; [KernelProbeComputeShapeTest] holds it.)
     */
    @Test
    fun oneDocumentReportsAValueAClearAndAnErrorAsThreeSeparateOutcomes() {
        val outcomes = artifact.rowNamed(OPERAND_INVALID).anchor().outcomesByField()

        val value = outcomes.getValue("Order/LineTotal")
        assertEquals("50.00", value.path("value").asText(), "unit price × quantity, rounded to the target scale")
        assertFalse(value.path("cleared").asBoolean())
        assertFalse(value.path("errored").asBoolean())

        val cleared = outcomes.getValue("Order/OrderTotal")
        assertTrue(cleared.path("cleared").asBoolean(), "a stale total whose operand is invalid is cleared")
        assertFalse(cleared.path("errored").asBoolean(), "a clear is not an error")
        assertTrue(cleared.path("value").isMissingNode, "a cleared field has NO value key — not an empty string")

        val errored = outcomes.getValue("Order/LoyaltyPoints")
        assertTrue(errored.path("errored").asBoolean(), "ten points into a field that permits nine")
        assertFalse(errored.path("cleared").asBoolean(), "an errored instance is never also cleared")
        assertEquals(
            "10", errored.path("value").asText(),
            "…and the kernel RETURNS the out-of-range value while flagging it, so errored is not 'no value'",
        )
    }

    /** The addresses keep the artifact's one published form, so a computed field reads like a message field. */
    @Test
    fun anOutcomeAddressUsesThePublishedMessageAddressForm() {
        assertEquals(
            listOf("Order/LineTotal", "Order/LoyaltyPoints", "Order/OrderTotal", "Order/RushFee"),
            artifact.rowNamed(OPERAND_INVALID).anchor().path("computations").path("outcomes")
                .map { it.path("field").asText() },
            "canonical A12 — no leading slash, the implicit [1] omitted — and sorted by address, never by " +
                "the kernel's channel emission order",
        )
    }

    // ---- acceptance case 3: the cause survives, with its negative control -------------------------

    /**
     * **The case the whole request exists for.** A cleared computed field whose cause is an operand formal error
     * carries that error, with its locus — and the negative control proves the channel is a second observation
     * rather than a copy of the outcome list: the other row clears a field for an unmet precondition and reports
     * no operand error at all.
     */
    @Test
    fun aClearCausedByAnOperandFormalErrorCarriesThatErrorAndAPreconditionClearCarriesNone() {
        val caused = artifact.rowNamed(OPERAND_INVALID).anchor()
        assertTrue(
            caused.outcomesByField().getValue("Order/OrderTotal").path("cleared").asBoolean(),
            "the row's premise: the total really is cleared",
        )
        assertEquals(
            listOf("Order/ShippingFee"),
            caused.path("computations").path("formalErrorsInOperands").map { it.path("field").asText() }.distinct(),
            "…and the operand whose formal error caused it is named — the discriminator no route this project " +
                "can reach exposes, since the outcome alone is cause-blind",
        )

        val uncaused = artifact.rowNamed(GATE_CLOSED).anchor()
        assertTrue(
            uncaused.outcomesByField().getValue("Order/RushFee").path("cleared").asBoolean(),
            "the control's premise: a field is cleared here too, for an unmet precondition",
        )
        assertEquals(
            emptyList<String>(),
            uncaused.path("computations").path("formalErrorsInOperands").map { it.path("field").asText() },
            "…and it carries NO operand error, so the channel is not a mirror of the cleared outcomes",
        )
        assertFalse(
            uncaused.path("computations").isMissingNode,
            "…read off a block that really carries the channel — `isEmpty` is also true of a missing node, so " +
                "without this the control would pass if the whole block vanished",
        )
    }

    /**
     * The sharper control, and the one that says what the channel is actually worth: a field carrying the
     * **same formal-error code** but which is an **operand of no computation** appears in `messages` and is
     * **absent** from `formalErrorsInOperands`.
     *
     * Without it the exposure would be open to a fair objection — that an operand's formal error is also a
     * validation message, so the compute channel merely repeats one. It does not: it is the kernel's filter
     * down to the formal errors found among computation operands, which is the difference between "this
     * document has a formal error somewhere" and "a formal error was found in the computation's operand set".
     *
     * **"Operand of" is not a runtime read trace**, and the distinction matters to anyone acting on this: the
     * kernel's operand set includes every field merely *contained by* a referenced group, so a field nothing
     * evaluates can still be inventoried (`ComputationEagerPrepassLawsTest`). `HandlingNote` is excluded
     * because no computation names it or its group, not because nothing read it.
     */
    @Test
    fun aFormalErrorOnAFieldThatIsNoComputationsOperandIsAMessageOnly() {
        val engine = artifact.rowNamed(OPERAND_INVALID).anchor()
        val code = "zahlFalscheAnzahlNachkommastellen"

        assertEquals(
            listOf("Order/HandlingNote", "Order/ShippingFee"),
            engine.path("messages").filter { it.path("code").asText() == code }
                .map { it.path("field").asText() }.sorted(),
            "validation reports the same formal error on both fields, so the two are indistinguishable there",
        )
        assertEquals(
            listOf("Order/ShippingFee"),
            engine.path("computations").path("formalErrorsInOperands").map { it.path("field").asText() },
            "…while the compute channel keeps only the one an operand carried — a filter, not a copy",
        )
    }

    /** An operand error is projected exactly like a validation message, so one parser reads both channels. */
    @Test
    fun anOperandErrorCarriesTheSameChannelsAsAValidationMessage() {
        val error = artifact.rowNamed(OPERAND_INVALID).anchor()
            .path("computations").path("formalErrorsInOperands").first()

        assertEquals(
            listOf("code", "field", "severity", "type", "message", "referenced", "fillToFix"),
            error.fieldNames().asSequence().toList(),
            "the message record's key names and order, not a second spelling of a message",
        )
        assertFalse(error.path("code").asText().isEmpty(), "the kernel's own formal-check code")
        assertFalse(error.path("message").asText().isEmpty(), "interpolated at the request's locale")
    }

    // ---- acceptance case 4: composition ----------------------------------------------------------

    /**
     * A computation reading another computed field's output reports the composed value, and both strategies
     * agree on it. Read from the clean row, where the chain completes: `OrderTotal` adds the shipping fee to a
     * `LineTotal` that is itself computed in the same run.
     */
    @Test
    fun aComputationReadingAnotherComputedFieldReportsTheComposedValue() {
        val row = artifact.rowNamed(GATE_CLOSED)
        assertEquals(
            "50.00", row.anchor().outcomesByField().getValue("Order/LineTotal").path("value").asText(),
            "the producer's own value",
        )
        assertEquals(
            "53.00", row.anchor().outcomesByField().getValue("Order/OrderTotal").path("value").asText(),
            "…and the consumer composed it with the shipping fee rather than reading the stale seed",
        )
        assertTrue(row.path("enginesAgree").asBoolean(), "both codegen strategies compose identically")

        val generated = row.path("engines").single { it.path("engine").asText() == KernelRuntimeProbe.JAVA_STATIC }
        assertFalse(generated.path("computations").isMissingNode, "the static-Java block carries the channel")
        assertEquals(
            row.anchor().path("computations"), generated.path("computations"),
            "…and agreement really means the generated-Java block carries the same computations block. Selected " +
                "BY STRATEGY NAME, not by position: reading engines[1] would compare a block with itself if the " +
                "producer ever emitted the anchor twice",
        )
    }

    // ---- acceptance case 6: determinism ----------------------------------------------------------

    /** Two runs over one request — including the row carrying an errored instance and a poisoned clear — are
     *  byte-identical, which is what lets a consumer pin a compute observation by SHA-256. */
    @Test
    fun twoRunsOfAComputeRequestProduceByteIdenticalArtifacts() {
        artifact   // force the first run, whichever order this method lands in
        val repeat = dir.resolve("compute-repeat.json")
        KernelRuntimeProbe.main(arrayOf(computeRequest.toString(), repeat.toString()))

        assertEquals(
            Files.readString(dir.resolve("compute-artifact.json")), Files.readString(repeat),
            "a compute observation is a function of its input alone",
        )
    }

    // ---- acceptance case 7: refusal unchanged ----------------------------------------------------

    /** Asking for compute does not soften the consistency gate: an inconsistent model still writes nothing. */
    @Test
    fun aStaticallyRefusedModelStillRefusesWhenComputeIsRequested() {
        val out = dir.resolve("refused-compute.json")
        val refused = assertThrows(KernelRuntimeProbe.Refused::class.java) {
            KernelRuntimeProbe.main(arrayOf(refusedComputeRequest.toString(), out.toString()))
        }

        assertTrue(
            refused.diagnostics().any { "MVK_ERROR_FIELD_NOT_REFERENCED" in it },
            "the refusal carries the kernel's own diagnostics: ${refused.diagnostics()}",
        )
        assertFalse(Files.exists(out), "a refused run leaves no artifact that could be pinned as evidence")
    }

    /** A compute artifact conforms to the published schema, so the schema cannot drift from the producer. */
    @Test
    fun aComputeArtifactConformsToThePublishedSchema() {
        val schemaNode = MAPPER.readTree(
            KernelProbeComputeTest::class.java.classLoader
                .getResource("kernel-probe/artifact.schema.json")!!.readText(),
        )
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode)
        assertEquals(emptySet<Any>(), schema.validate(artifact), "the real compute artifact must conform")
    }

    /**
     * Acceptance case 1, on the **producer** rather than the renderer: a request that does not ask for compute
     * produces the artifact it produced before compute existed — the same rows, the same messages, and no
     * `computations` key anywhere.
     *
     * [KernelProbeComputeShapeTest] pins the exact bytes against output captured from the pre-change renderer;
     * this pins that the real `run()` path still reaches that shape, which a value-level test of the renderer
     * cannot say. Both are needed: the renderer could be right while the producer passed `Optional.of(empty)`.
     */
    @Test
    fun aRequestThatDoesNotAskForComputeProducesTheArtifactItAlwaysDid() {
        val validationOnly = probe(
            request("validation-only", model, ROWS.trimIndent()), "validation-only-artifact.json",
        )
        val engines = validationOnly.path("rows").flatMap { it.path("engines") }

        assertEquals(4, engines.size, "two rows × two strategies — the positive control that anything was read")
        assertEquals(
            emptyList<String>(), engines.filterNot { it.path("computations").isMissingNode }
                .map { it.path("engine").asText() },
            "not observed is reported by ABSENCE; an empty block would read as 'nothing computed'",
        )
        assertEquals(
            artifact.path("rows").flatMap { row -> row.path("engines").map { it.path("messages") } },
            engines.map { it.path("messages") },
            "…and asking for compute changed nothing about the message channel it rides beside",
        )
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private fun JsonNode.rowNamed(name: String): JsonNode =
        path("rows").single { it.path("name").asText() == name }

    /** The production-normative block: dynamic Groovy is the anchor, generated Java the co-evidence (IF136). */
    private fun JsonNode.anchor(): JsonNode =
        path("engines").single { it.path("engine").asText() == KernelRuntimeProbe.GROOVY_DYNAMIC }

    private fun JsonNode.outcomesByField(): Map<String, JsonNode> =
        path("computations").path("outcomes").associateBy { it.path("field").asText() }

    companion object {

        private val MAPPER = ObjectMapper()

        private const val OPERAND_INVALID = "shipping-fee-malformed"
        private const val GATE_CLOSED = "standard-tier"

        private const val ROOT = "/Order"
        private const val UNIT_PRICE = "$ROOT/UnitPrice"
        private const val QUANTITY = "$ROOT/Quantity"
        private const val SHIPPING_FEE = "$ROOT/ShippingFee"
        private const val EXPRESS_TIER = "$ROOT/ExpressTier"
        private const val LINE_TOTAL = "$ROOT/LineTotal"
        private const val ORDER_TOTAL = "$ROOT/OrderTotal"
        private const val RUSH_FEE = "$ROOT/RushFee"
        private const val LOYALTY_POINTS = "$ROOT/LoyaltyPoints"

        @JvmStatic
        @TempDir
        lateinit var dir: Path

        /**
         * The order model, authored through modelkit and the rulekit typed computation builder — every path
         * written once, at a call site that validates it.
         */
        private val model: String by lazy {
            val base = BoundModel.ofJson(
                DocumentModelDraft.named("probe-compute").locale("en_US")
                    .rootGroup("Order") { order ->
                        order.numberField("UnitPrice") { it.fractionDigits(2, 2) }
                            .numberField("Quantity") { it.fractionDigits(0, 0) }
                            .numberField("ShippingFee") { it.fractionDigits(2, 2) }
                            .stringField("ExpressTier") { }
                            .numberField("LineTotal") { it.fractionDigits(2, 2) }
                            .numberField("OrderTotal") { it.fractionDigits(2, 2) }
                            .numberField("RushFee") { it.fractionDigits(2, 2) }
                            .numberField("LoyaltyPoints") { it.fractionDigits(0, 0).max("9") }
                            .numberField("HandlingNote") { it.fractionDigits(2, 2) }
                    }.build().toJson(),
            )
            val lineTotal = base.computation(base.num(LINE_TOTAL)).name("LineTotalComputation")
                .calc(round(base.num(UNIT_PRICE).asNumber().times(base.num(QUANTITY).asNumber())))
                .message(Dsl.en("Line total.")).build()
            val orderTotal = base.computation(base.num(ORDER_TOTAL)).name("OrderTotalComputation")
                .calc(round(base.num(LINE_TOTAL).asNumber().plus(base.num(SHIPPING_FEE).asNumber())))
                .message(Dsl.en("Order total including shipping.")).build()
            val rushFee = base.computation(base.num(RUSH_FEE)).name("RushFeeComputation")
                .commonPrecondition(base.str(EXPRESS_TIER).filled())
                .calc(round(base.num(LINE_TOTAL).asNumber().times(NumberExpr(NumberLiteral.of("0.05")))))
                .message(Dsl.en("Express handling fee.")).build()
            val loyaltyPoints = base.computation(base.num(LOYALTY_POINTS)).name("LoyaltyPointsComputation")
                .calc(NumberLiteral.of("10")).message(Dsl.en("Flat loyalty award.")).build()
            base.addAll(emptyList(), listOf(lineTotal, orderTotal, rushFee, loyaltyPoints)).toJson()
        }

        private fun round(expression: NumberExpr) = Dsl.roundAccounting(expression, 2).expression()

        /**
         * Two rows, each seeding every computed target so a non-computation is reported as a CLEAR rather than
         * as silence (the kernel clears an input-filled target it did not compute).
         *
         *  - `shipping-fee-malformed` carries a three-decimal shipping fee into a two-decimal field, so the
         *    total that reads it is cleared with a named cause, while the unrelated line total still computes;
         *  - `standard-tier` is formally clean and simply leaves the express tier unfilled, so the rush fee is
         *    cleared by its precondition and no operand error exists to name.
         */
        private val ROWS = """
            { "name": "$OPERAND_INVALID", "documentInline": { "Order": {
                "UnitPrice": 12.50, "Quantity": 4, "ShippingFee": 1.234, "ExpressTier": "NEXT-DAY",
                "HandlingNote": 5.678,
                "LineTotal": 0.00, "OrderTotal": 0.00, "RushFee": 0.00, "LoyaltyPoints": 0 } } },
            { "name": "$GATE_CLOSED", "documentInline": { "Order": {
                "UnitPrice": 12.50, "Quantity": 4, "ShippingFee": 3.00,
                "LineTotal": 0.00, "OrderTotal": 0.00, "RushFee": 0.00, "LoyaltyPoints": 0 } } }
        """

        /** The kernel refuses a rule whose error field its condition never references — a static ERROR. */
        private val refusedModel: String by lazy {
            val base = DocumentModelDraft.named("probe-compute-refused").locale("en_US")
                .rootGroup("Order") { order ->
                    order.stringField("Reference") { }
                        .numberField("Quantity") { it.fractionDigits(0, 0) }
                }.build().toJson()
            RuntimeLaws.withRule(
                base, "/Order", "RUNREFERENCED", "../Quantity", "UNREF",
                DslRenderer().render(BoundModel.ofJson(base).str("/Order/Reference").filled()),
            )
        }

        private fun write(name: String, content: String): Path = Files.writeString(dir.resolve(name), content)

        private fun request(name: String, model: String, rows: String, observe: String = ""): Path =
            write(
                "$name.request.json",
                """{ "model": "${write("$name.model.json", model).fileName}", $observe "rows": [ $rows ] }""",
            )

        private val computeRequest: Path by lazy {
            request("compute", model, ROWS.trimIndent(), """ "observe": ["validateFull", "compute"], """)
        }

        private val refusedComputeRequest: Path by lazy {
            request(
                "refused-compute", refusedModel,
                """{ "name": "any", "documentInline": { "Order": { "Reference": "ORD-9" } } }""",
                """ "observe": ["validateFull", "compute"], """,
            )
        }

        private fun probe(request: Path, out: String): JsonNode {
            KernelRuntimeProbe.main(arrayOf(request.toString(), dir.resolve(out).toString()))
            return MAPPER.readTree(Files.readString(dir.resolve(out)))
        }

        private val artifact: JsonNode by lazy { probe(computeRequest, "compute-artifact.json") }
    }
}
