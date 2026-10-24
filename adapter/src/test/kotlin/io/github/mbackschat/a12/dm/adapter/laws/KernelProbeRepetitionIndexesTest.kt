package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.github.mbackschat.a12.dm.adapter.laws.KernelRuntimeProbe.Observation
import java.util.Locale
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * The request and ordering contract of the probe's opt-in `repetitionIndexes` (KERNEL-PROBE-SPEC §4). What
 * the kernel puts in a vector is measured on a real run by [KernelProbeParallelIterationPointerTest]; these lock
 * what no kernel run decides.
 */
class KernelProbeRepetitionIndexesTest {

    private val mapper = ObjectMapper()

    private fun request(vararg observe: Observation) = KernelRuntimeProbe.Request(
        "order.dm.json", "{}", Locale.US, Optional.empty(), observe.toSet(),
        listOf(KernelRuntimeProbe.Row("row", "{}")))

    /**
     * The coordinates annotate the message channel, so they need what it needs: `validateFull` and rows. The
     * accepted request is the control that the refusal is about the missing channel rather than the new value.
     */
    @Test
    fun theCoordinatesAreRefusedWithoutTheChannelTheyAnnotate() {
        assertThrows(IllegalArgumentException::class.java) { request(Observation.REPETITION_INDEXES) }
        assertThrows(IllegalArgumentException::class.java) {
            KernelRuntimeProbe.Request("order.dm.json", "{}", Locale.US, Optional.empty(),
                setOf(Observation.MANDATORY_INFORMATION, Observation.REPETITION_INDEXES), listOf())
        }
        assertEquals(setOf(Observation.VALIDATE_FULL, Observation.REPETITION_INDEXES),
            request(Observation.VALIDATE_FULL, Observation.REPETITION_INDEXES).observe())
    }

    /**
     * Two references sharing one house-form string, the group's own row and its `-5`, publish in one order
     * whichever order the kernel emitted them in, and each keeps its own vector at its position. A string-only
     * sort would leave the pair in emission order and could swap the vectors' meaning between runs.
     */
    @Test
    fun addressesSharingAStringSortByTheirCoordinatesAndStayAligned() {
        val group = "/Order[1]/Lines[1]/Slots"
        val emitted = listOf(group to listOf(1, 1, 2), "Order/Note" to listOf(1, 1), group to listOf(1, 1, -5))

        val published = listOf(emitted, emitted.reversed()).map { pairs ->
            val message = KernelProbeArtifact.Message("C", group, "ERROR", "VALUE_ERROR", "x",
                pairs.map { it.first }, listOf(), Optional.of(KernelProbeArtifact.RepetitionIndexes(
                    listOf(1, 1, -5), pairs.map { it.second }, listOf())))
            message.referenced().zip(message.repetitionIndexes().orElseThrow().referenced())
        }

        assertEquals(published[0], published[1])
        assertEquals(listOf(group to listOf(1, 1, -5), group to listOf(1, 1, 2), "Order/Note" to listOf(1, 1)),
            published[0])
    }

    /** A vector list that does not match its address list is refused rather than misaligned. */
    @Test
    fun aVectorPerAddressIsRequired() {
        assertThrows(IllegalArgumentException::class.java) {
            KernelProbeArtifact.Message("C", "Order/Note", "ERROR", "VALUE_ERROR", "x", listOf("Order/Note"),
                listOf(), Optional.of(KernelProbeArtifact.RepetitionIndexes(listOf(1, 1), listOf(), listOf())))
        }
    }

    /** The writer publishes the vectors in the message's own aligned order, as integers. */
    @Test
    fun theWriterPublishesTheAlignedVectors() {
        val message = KernelProbeArtifact.Message("C", "/Order[1]/Lines[1]/Slots", "ERROR", "OMISSION_ERROR",
            "x", listOf("Order/Note"), listOf("Order/Note"), Optional.of(KernelProbeArtifact.RepetitionIndexes(
                listOf(1, 1, -5), listOf(listOf(1, 1)), listOf(listOf(1, 1)))))
        val header = KernelProbeArtifact.Header.builder().dmtoolVersion("0").kernelBuiltAgainst("30.8.1")
            .kernelRuntime("30.8.1").sourceRevision("(unknown)").sourceState("UNKNOWN").model("m.json")
            .locale("en_US").observe(Optional.of(listOf("validateFull", "repetitionIndexes"))).build()
        val row = KernelProbeArtifact.Row("row", listOf(KernelRuntimeProbe.GROOVY_DYNAMIC,
            KernelRuntimeProbe.JAVA_STATIC).map { KernelProbeArtifact.Engine.of(it).messages(listOf(message)).build() })
        val json = mapper.readTree(KernelProbeArtifact(header, listOf(row)).toJson())

        assertEquals("""{"field":[1,1,-5],"referenced":[[1,1]],"fillToFix":[[1,1]]}""",
            json.path("rows")[0].path("engines")[0].path("messages")[0].path("repetitionIndexes").toString())
        assertEquals("""["validateFull","repetitionIndexes"]""", json.path("request").path("observe").toString())
        assertEquals(emptySet<Any>(), schema("artifact").validate(json), "the published artifact schema accepts it")
    }

    /** The request schema states the same refusal as the record; the accepted request is its control. */
    @Test
    fun theRequestSchemaRefusesTheCoordinatesWithoutTheMessageChannel() {
        fun verdict(observe: String) = schema("request").validate(mapper.readTree(
            """{"model":"m.json","observe":$observe,"rows":[{"name":"r","documentInline":{}}]}""")).isEmpty()
        assertEquals(mapOf("alone" to false, "beside validateFull" to true),
            mapOf("alone" to verdict("""["repetitionIndexes"]"""),
                "beside validateFull" to verdict("""["validateFull","repetitionIndexes"]""")))
    }

    private fun schema(name: String) = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
        mapper.readTree(javaClass.classLoader.getResource("kernel-probe/$name.schema.json")!!.readText()))
}
