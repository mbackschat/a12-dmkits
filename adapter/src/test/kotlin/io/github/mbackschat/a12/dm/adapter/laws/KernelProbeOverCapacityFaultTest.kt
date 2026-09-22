package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The kernel READS an over-capacity document and refuses to WRITE it — and asking for the write no longer costs
 * a consumer the read.
 *
 * **Two claims, deliberately in one file, because the second is only visible through the first.** The kernel
 * fact is an asymmetry: a document carrying four rows against a maximum of three deserializes, validates, and
 * reports the excess as two ordinary `ERROR` messages, while serializing the applied copy that `computeResults`
 * produces raises `DocumentSerializationException` instead. The probe fact is what that asymmetry exposed —
 * until 2026-09-22 a raise in any channel discarded every channel of that block, so a request that ADDED
 * `computeResults` came back without the two messages a `compute`-only request returns for the same row.
 *
 * **Provenance.** a12-kernel-lean reported the symptom on 2026-09-22 against `2304a6849`. A peer's report is
 * their measurement, so both halves are re-measured here rather than cited; the numbers below are this file's
 * own run against kernel 30.8.1.
 *
 * **The discriminator is the REQUEST, not the document.** Both runs use the same model and the same rows and
 * differ in exactly one thing — whether `computeResults` is in `observe`. That is what separates "the kernel
 * dislikes an over-capacity document" (true of both runs, and unsurprising) from the claim that matters.
 *
 * **The within-capacity row is the liveness control**, and it earns its place twice: it shows that
 * `computeResults` ran at all (it carries an `applied` document), and that the request difference costs an
 * unaffected row nothing.
 *
 * Own domain: an e-commerce order whose delivery legs repeat at most three times.
 */
class KernelProbeOverCapacityFaultTest {

    /**
     * The kernel asymmetry: the same document that validates into two messages cannot be written back out.
     *
     * Asserted on the `compute`-only run, so the messages are read from a block that never reached the
     * serializer — which is what makes "read succeeds" a measurement rather than an artifact of the fix below.
     */
    @Test
    fun anOverCapacityDocumentValidatesButItsAppliedCopyCannotBeSerialized() {
        val readOnly = run("read", """ "observe": ["validateFull", "compute"], """)
            .rowNamed(OVER_CAPACITY)
        val written = run("write", """ "observe": ["validateFull", "compute", "computeResults"], """)
            .rowNamed(OVER_CAPACITY)

        readOnly.path("engines").forEach { engine ->
            assertFalse(engine.has("fault"), "reading and validating it raises nothing on ${engine.engineName()}")
            assertEquals(EXCESS_MESSAGES, engine.messageAddresses(),
                "…it reports the excess as ordinary validation, on ${engine.engineName()}")
        }
        written.path("engines").forEach { engine ->
            assertEquals(SERIALIZATION_REFUSAL, engine.path("fault").raise(),
                "…while writing the applied copy refuses it outright, on ${engine.engineName()}")
        }
        assertTrue(written.path("enginesAgree").asBoolean(),
            "both strategies refuse identically, so no engine split hides inside this row")
    }

    /**
     * The probe contract `observe` advertises: adding an observation costs an existing consumer nothing.
     *
     * The `computeResults` raise takes its own channel and only its own — the two messages the same row returns
     * without it are still there, byte-for-byte the same set.
     */
    @Test
    fun addingComputeResultsKeepsTheValidationMessagesTheRowAlreadyReturned() {
        val without = run("without", """ "observe": ["validateFull", "compute"], """)
            .rowNamed(OVER_CAPACITY).anchor()
        val with = run("with", """ "observe": ["validateFull", "compute", "computeResults"], """)
            .rowNamed(OVER_CAPACITY).anchor()

        assertEquals("computeResults", with.path("fault").path("observation").asText(),
            "the fault names the channel that raised, which is the only one that may be missing")
        assertEquals(without.path("messages"), with.path("messages"),
            "the channel that finished before the raise survives it — the invariant KERNEL-PROBE-SPEC §3 states")
        assertEquals(without.path("computations"), with.path("computations"),
            "…and so does every other channel the raise did not reach")
        assertFalse(with.has("computeResults"),
            "the raising channel itself is absent rather than empty, or a half-read block would read as an answer")
    }

    /**
     * The liveness control: `computeResults` really ran, and the row inside the maximum is untouched by it.
     *
     * Without the `applied` assertion the test above is satisfied by a `computeResults` that silently did
     * nothing on every row, which is a different and much weaker claim.
     */
    @Test
    fun aRowWithinTheMaximumObservesTheAddedChannelAndLosesNothing() {
        val without = run("ctl-without", """ "observe": ["validateFull", "compute"], """)
            .rowNamed(WITHIN_CAPACITY).anchor()
        val with = run("ctl-with", """ "observe": ["validateFull", "compute", "computeResults"], """)
            .rowNamed(WITHIN_CAPACITY).anchor()

        assertFalse(with.has("fault"), "a row inside the maximum must not fault, got: $with")
        assertEquals(2, with.path("computeResults").path("applied").path("Order").path("Legs").size(),
            "the added channel produced a real applied document, so the row above is a refusal and not a no-op")
        assertEquals(without.path("messages"), with.path("messages"),
            "and the request difference costs an unaffected row nothing at all")
    }

    /**
     * The published schema carries the same invariant the record does, in both directions.
     *
     * The positive half admits the shape this file produces — a `fault` beside the channels it did not reach —
     * which the old schema forbade outright. The negative half is what gives the change teeth: a block
     * publishing the very channel its fault names must be REFUSED, or the schema would merely have been
     * loosened. Without it, deleting every conditional from the schema still passes.
     */
    @Test
    fun theSchemaAdmitsTheSurvivingChannelsAndStillRefusesTheRaisedOne() {
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
            javaClass.classLoader.getResource("kernel-probe/artifact.schema.json")!!.toURI())
        val artifact = run("schema", """ "observe": ["validateFull", "compute", "computeResults"], """)

        assertEquals(emptyList<String>(), schema.validate(artifact).map { it.message },
            "the artifact this file produces must satisfy the published shape")
        assertTrue(artifact.rowNamed(OVER_CAPACITY).anchor().has("messages"),
            "…over an artifact that actually carries a fault beside a surviving channel, or the check is vacuous")

        val illegal = artifact.deepCopy<JsonNode>()
        (illegal.rowNamed(OVER_CAPACITY).anchor() as ObjectNode)
            .set<ObjectNode>("computeResults", artifact.rowNamed(WITHIN_CAPACITY).anchor().path("computeResults"))
        assertTrue(schema.validate(illegal).isNotEmpty(),
            "a block publishing the channel its own fault names must be refused, not merely never written")
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private fun JsonNode.rowNamed(name: String): JsonNode =
        path("rows").first { it.path("name").asText() == name }

    private fun JsonNode.anchor(): JsonNode =
        path("engines").first { it.path("engine").asText() == KernelRuntimeProbe.GROOVY_DYNAMIC }

    private fun JsonNode.engineName(): String = path("engine").asText()

    private fun JsonNode.messageAddresses(): List<String> =
        path("messages").map { "${it.path("code").asText()}@${it.path("field").asText()}" }

    /** The one raise, as `type: message` — a chain of exactly one is itself part of the claim. */
    private fun JsonNode.raise(): String = path("chain").single()
        .let { "${it.path("type").asText().substringAfterLast('.')}: ${it.path("message").asText()}" }

    /**
     * A faulting run still writes its artifact and then fails, so the fault is caught rather than avoided —
     * swallowing it would make the two runs incomparable, which is the whole measurement.
     */
    private fun run(name: String, observe: String): JsonNode {
        val request = write(
            "$name.request.json",
            """{ "model": "${write("$name.model.json", model).fileName}", $observe "rows": [ $ROWS ] }""",
        )
        val out = dir.resolve("$name.out.json")
        try {
            KernelRuntimeProbe.main(arrayOf(request.toString(), out.toString()))
        } catch (expected: KernelRuntimeProbe.FaultsObserved) {
            // The artifact is written BEFORE the run fails ([KernelProbeFaultCaptureTest]); that is what is read.
        }
        return MAPPER.readTree(Files.readString(out))
    }

    companion object {

        private val MAPPER = ObjectMapper()

        @JvmStatic
        @TempDir
        lateinit var dir: Path

        private const val OVER_CAPACITY = "four-rows-against-a-maximum-of-three"
        private const val WITHIN_CAPACITY = "two-rows-well-inside-it"

        /**
         * Both codes, each at its own address — the pair is the point. `zuGrosseKontextnummer` attaches to the
         * FIELD read inside the excess row and `zuGrosseZeile` to the row itself, so the kernel reports the
         * excess twice at two granularities rather than once.
         */
        private val EXCESS_MESSAGES = listOf(
            "zuGrosseKontextnummer@Order/Legs[4]/Distance",
            "zuGrosseZeile@Order/Legs[4]",
        )

        /**
         * The write-side refusal, quoted exactly.
         *
         * Its address spelling is worth reading beside [EXCESS_MESSAGES]: the serializer names the LIST
         * (`Legs[0]`) and the count it found, the validator names the offending ROW (`Legs[4]`). One condition,
         * two vocabularies, so a consumer joining the two channels on an address will not match them.
         */
        private const val SERIALIZATION_REFUSAL = "DocumentSerializationException: Following errors were "
            .plus("found:\nMax repetition exceeded: Order/Legs[0] (got 4 but max repetition is 3)")

        /** An order whose delivery legs repeat at most three times — the capacity the rows sit either side of. */
        private val model: String by lazy {
            DocumentModelDraft.named("probe-over-capacity").locale("en_US")
                .rootGroup("Order") { order ->
                    order.stringField("Reference") { it.maxLength(20) }
                        .group("Legs") { legs ->
                            legs.repeatable(3)
                                .numberField("Distance") { it.fractionDigits(0, 0).max("500") }
                        }
                }.build().toJson()
        }

        /** Four legs against a maximum of three, and two legs as the control — one model, one request. */
        private val ROWS = """
            { "name": "$OVER_CAPACITY", "documentInline": { "Order": {
                "Legs": [ { "Distance": 1 }, { "Distance": 2 }, { "Distance": 3 }, { "Distance": 4 } ] } } },
            { "name": "$WITHIN_CAPACITY", "documentInline": { "Order": {
                "Legs": [ { "Distance": 1 }, { "Distance": 2 } ] } } }
        """.trimIndent()

        private fun write(name: String, content: String): Path = Files.writeString(dir.resolve(name), content)
    }
}
