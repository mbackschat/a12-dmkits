package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * [KernelRuntimeProbe]'s `computeResults` observation — the kernel's own RESULT collections and the document
 * its results apply to, as distinct from the change projection `computations` publishes.
 *
 * **Why the probe grew it, and what the requester's own account got wrong.** a12-kernel-lean has two carriers
 * marked `route-state: blocked` on this and nothing else (their 2026-09-21 note §4): the complete successful
 * result collection, and the applied destination. Their stated mechanism — that `outcomes[]` is "keyed by field
 * and normalized" and so cannot express a result with several instances — is **not** the gap, and measuring it
 * first is what found the real one: `outcomes[]` is already per-INSTANCE, with one entry per repetition and an
 * indexed address ([KernelProbeRepeatableAddressTest]). The actual missing observable is narrower and sharper —
 * `getComputedFieldInstancesWithChanges()` is by the kernel's own javadoc a **subset** of
 * `getComputedFieldInstancesWithoutErrors()`, so a computation that successfully recomputed a value the
 * document ALREADY HELD lands in no channel of `computations` at all. [aSuccessfulRecomputationThatChangedNothingIsVisibleOnlyHere]
 * is that instance.
 *
 * **The applied document is the second half, and it is the one they said blocks more of their work.** The
 * outcome channels report what was computed; nothing in them reports what the document then holds. `applied`
 * is A12's canonical Document JSON after `IDocumentComputationResult.applyTo(DocumentV2)`.
 *
 * **Own domain: an e-commerce order.** Two rows, each carrying one discriminator and acting as the other's
 * control — the first recomputes an already-correct total (no change) and errors its loyalty award, the second
 * fills an empty destination (a change) and errors nothing.
 */
class KernelProbeComputeResultsTest {

    /**
     * The headline gap, in one comparison: the same instance, in the same row, present in the result
     * collection and absent from every channel of the change projection. Both halves are asserted on ONE
     * artifact, because the claim is about the two blocks disagreeing — reading them from separate runs would
     * leave it open that the runs differed.
     */
    @Test
    fun aSuccessfulRecomputationThatChangedNothingIsVisibleOnlyHere() {
        val engine = artifact.rowNamed(UNCHANGED).anchor()

        assertTrue(LINE_TOTAL in engine.resultFields("withoutErrors"),
            "the kernel returns the recomputed instance as a successful result")
        assertTrue(LINE_TOTAL !in engine.outcomeFields(),
            "…and the change projection cannot show it, because its value equals the document's — which is "
                + "exactly the instance `computations` alone leaves a consumer unable to see")

        assertEquals("10.00", engine.resultInstance("withoutErrors", LINE_TOTAL).path("value").asText(),
            "the result carries the recomputed text, so 'unchanged' is a measurement rather than a silence")
    }

    /**
     * The liveness control for the row above: the SAME computation on the SAME model does reach `outcomes[]`
     * when its value differs. Without this, an `outcomes[]` that had simply gone empty would satisfy the
     * absence asserted above.
     */
    @Test
    fun theSameComputationReachesTheChangeProjectionWhenItsValueDiffers() {
        val engine = artifact.rowNamed(EMPTY_DESTINATION).anchor()

        assertTrue(LINE_TOTAL in engine.outcomeFields(), "a changed value IS a change-projection outcome")
        assertTrue(LINE_TOTAL in engine.resultFields("withoutErrors"),
            "…and it is in the result collection too, which is the superset relation the kernel documents")
    }

    /**
     * The applied destination: the document AFTER application carries a value the request's document did not.
     * Asserted against the request's own input rather than against a literal, so the test states the
     * difference application makes rather than restating the model's arithmetic.
     */
    @Test
    fun theAppliedDocumentCarriesWhatTheInputDocumentDidNot() {
        val applied = artifact.rowNamed(EMPTY_DESTINATION).anchor().path("computeResults").path("applied")

        assertTrue(applied.path("Order").has("LineTotal"),
            "the input document left LineTotal absent, so only application can have put it there")
        assertEquals("4.00", applied.path("Order").path("LineTotal").asText(),
            "…carrying the computed value, in A12's canonical Document JSON")
        assertEquals("1", applied.path("Order").path("Quantity").asText(),
            "an untouched operand survives application unchanged — the control that makes the row above a "
                + "difference rather than a rewrite")
    }

    /**
     * The applied document keeps A12's own number scale, asserted on the artifact's TEXT because that is what
     * the claim is about — a reader that parses first has already decided the answer with its mapper
     * configuration. `4.00` rather than `4.0` is load-bearing: the runtime renders a NumberType's text from the
     * stored `BigDecimal` and its length bound counts that text's characters, so the two spellings fire
     * different codes on one field (KERNEL-FINDINGS `kf1`).
     */
    @Test
    fun theAppliedDocumentKeepsA12sOwnNumberScale() {
        artifact   // force the run, whichever order this method lands in
        val bytes = Files.readString(dir.resolve("results.json"))

        assertTrue(""""LineTotal" : 4.00""" in bytes,
            "a two-decimal computed value is written with both decimals — got:\n" + bytes.lines()
                .filter { "LineTotal" in it }.joinToString("\n"))
        assertTrue(""""UnitPrice" : 5.00""" in bytes,
            "…and an untouched operand keeps the scale the request wrote, through the whole round trip")
    }

    /**
     * The third channel our own spec named as unexposed: `IComputedFieldInstance.getErrorMessage()`. The
     * change projection records THAT an instance errored; only the result block says what the kernel said
     * about it.
     */
    @Test
    fun anErroredInstanceCarriesTheKernelsOwnPerInstanceMessage() {
        val engine = artifact.rowNamed(UNCHANGED).anchor()
        val errored = engine.resultInstance("errored", LOYALTY_POINTS)

        assertTrue(errored.path("errorMessage").isObject,
            "the errored instance carries its own message, not merely the errored flag `outcomes[]` has")
        assertEquals(LOYALTY_POINTS, errored.path("errorMessage").path("field").asText())
        assertTrue(errored.path("errorMessage").path("code").asText().isNotEmpty(),
            "…projected through the same Message shape the validation channel uses")

        assertTrue(LOYALTY_POINTS !in engine.resultFields("withoutErrors"),
            "an errored instance is not a successful one — the two collections do not overlap")
    }

    /** Both strategies produce the identical result observation, so the row reports no split to chase. */
    @Test
    fun bothKernelStrategiesProduceTheSameResultObservation() {
        artifact.path("rows").forEach { row ->
            assertTrue(row.path("enginesAgree").asBoolean(),
                "row '${row.path("name").asText()}' — enginesAgree covers computeResults too")
        }
    }

    /** A request that does not ask for it is byte-identical to one written before the observation existed. */
    @Test
    fun anUnaskedObservationLeavesTheArtifactBytesUnchanged() {
        val out = dir.resolve("plain.json")
        KernelRuntimeProbe.main(arrayOf(request("plain", ROWS).toString(), out.toString()))

        MAPPER.readTree(Files.readString(out)).path("rows").forEach { row ->
            row.path("engines").forEach { engine ->
                assertTrue(!engine.has("computeResults"), "absent when unobserved, so an existing SHA-256 holds")
            }
        }
    }

    /** Two runs over one request are byte-identical, so a consumer can pin a result observation by SHA-256. */
    @Test
    fun twoRunsProduceByteIdenticalArtifacts() {
        artifact
        val repeat = dir.resolve("results-repeat.json")
        KernelRuntimeProbe.main(arrayOf(resultsRequest.toString(), repeat.toString()))

        assertEquals(
            Files.readString(dir.resolve("results.json")), Files.readString(repeat),
            "both collections sort on a total order, and the applied document is A12's own canonical writing",
        )
    }

    /** The published request schema must admit the observation the producer accepts, and demonstrate it. */
    @Test
    fun theRequestSchemaAdmitsAndDemonstratesTheObservation() {
        val schemaNode = MAPPER.readTree(
            javaClass.classLoader.getResource("kernel-probe/request.schema.json")!!.readText())
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode)

        assertTrue(schema.validate(MAPPER.readTree(
            """{ "model": "m.dm.json", "observe": ["validateFull", "computeResults"],
                 "rows": [ { "name": "r", "documentInline": {} } ] }""")).isEmpty(),
            "the schema must admit what the producer accepts, or an agent reading it first is misled")
        assertTrue(schemaNode.path("examples").any { example ->
            example.path("observe").any { it.asText() == "computeResults" }
        }, "an advertised observation with no worked example is how a consumer learns it from prose instead")
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private fun JsonNode.rowNamed(name: String): JsonNode =
        path("rows").first { it.path("name").asText() == name }

    /** The production-normative strategy; [bothKernelStrategiesProduceTheSameResultObservation] pins that
     *  reading one of them keeps these assertions about the channel rather than about a split. */
    private fun JsonNode.anchor(): JsonNode =
        path("engines").first { it.path("engine").asText() == KernelRuntimeProbe.GROOVY_DYNAMIC }

    private fun JsonNode.resultFields(collection: String): List<String> =
        path("computeResults").path(collection).map { it.path("field").asText() }

    private fun JsonNode.resultInstance(collection: String, field: String): JsonNode =
        path("computeResults").path(collection).first { it.path("field").asText() == field }

    private fun JsonNode.outcomeFields(): List<String> =
        path("computations").path("outcomes").map { it.path("field").asText() }

    companion object {

        /**
         * Configured as a CONSUMER of this artifact must be: the applied document carries A12's own decimals,
         * and a default mapper reads `4.00` into a double and hands back `4.0`. The artifact's bytes are
         * correct either way — [theAppliedDocumentKeepsA12sOwnNumberScale] asserts them as TEXT — but a reader
         * that does not configure this cannot see the scale it is reading about.
         */
        private val MAPPER = ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)

        @JvmStatic
        @TempDir
        lateinit var dir: Path

        private const val UNCHANGED = "recomputed-to-the-same-value"
        private const val EMPTY_DESTINATION = "destination-not-yet-filled"
        private const val LINE_TOTAL = "Order/LineTotal"
        private const val LOYALTY_POINTS = "Order/LoyaltyPoints"

        /**
         * An order whose line total is computed from price and quantity, plus a loyalty award capped below what
         * two items earn — so one row can carry a successful unchanged result and an errored one at once.
         */
        private val model: String by lazy {
            val base = BoundModel.ofJson(
                DocumentModelDraft.named("probe-compute-results").locale("en_US")
                    .rootGroup("Order") { order ->
                        order.numberField("UnitPrice") { it.fractionDigits(2, 2) }
                            .numberField("Quantity") { it.fractionDigits(0, 0) }
                            .numberField("LineTotal") { it.fractionDigits(2, 2) }
                            .numberField("LoyaltyPoints") { it.fractionDigits(0, 0).max("9") }
                    }.build().toJson(),
            )
            val lineTotal = base.computation(base.num("/Order/LineTotal")).name("LineTotalComputation")
                .calc(Dsl.roundAccounting(
                    base.num("/Order/UnitPrice").asNumber().times(base.num("/Order/Quantity").asNumber()),
                    2).expression())
                .message(Dsl.en("Line total.")).build()
            val loyalty = base.computation(base.num("/Order/LoyaltyPoints")).name("LoyaltyPointsComputation")
                .calc(base.num("/Order/Quantity").asNumber().times(NumberExpr(NumberLiteral.of("5")))
                    .expression())
                .message(Dsl.en("Five points per item.")).build()
            base.addAll(emptyList(), listOf(lineTotal, loyalty)).toJson()
        }

        /**
         * Two items at 5.00 with the total ALREADY 10.00 — a successful recomputation that changes nothing,
         * beside a ten-point award into a field that permits nine. One item at 4.00 with the total absent is
         * the control on both counts: a change, and no error.
         */
        private val ROWS = """
            { "name": "$UNCHANGED", "documentInline": { "Order": {
                "UnitPrice": 5.00, "Quantity": 2, "LineTotal": 10.00 } } },
            { "name": "$EMPTY_DESTINATION", "documentInline": { "Order": {
                "UnitPrice": 4.00, "Quantity": 1 } } }
        """.trimIndent()

        private fun write(name: String, content: String): Path = Files.writeString(dir.resolve(name), content)

        private fun request(name: String, rows: String, observe: String = ""): Path = write(
            "$name.request.json",
            """{ "model": "${write("$name.model.json", model).fileName}", $observe "rows": [ $rows ] }""",
        )

        private val resultsRequest: Path by lazy {
            request("results", ROWS, """ "observe": ["validateFull", "compute", "computeResults"], """)
        }

        private val artifact: JsonNode by lazy {
            val out = dir.resolve("results.json")
            KernelRuntimeProbe.main(arrayOf(resultsRequest.toString(), out.toString()))
            MAPPER.readTree(Files.readString(out))
        }
    }
}
