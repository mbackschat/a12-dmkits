package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * What [KernelRuntimeProbe] publishes when A12 puts `IIdentifier.PARALLEL_ITERATION` (`-6`) on a message
 * pointer (KERNEL-FINDINGS kf607).
 *
 * The carrier is the one a12-kernel-lean's K22 capture faulted on: a rule at `/Order` reporting on the
 * `SlotNo`-keyed `Lines/Slots` under `GroupNotFilled(Lines/Slots) And FieldFilled(Picks/Ref)`, `Picks` joined to
 * `Lines` on `LineNo`. The probe used to fault the whole row, losing every message and field with it.
 *
 * Runs only in `kernelProbeProductionAssertionsTest`, which matches the probe's own JVM setting: A12's message
 * pointer asserts its index domain is `>= 0` or `-5`, so under `-ea` the kernel's own accessor raises and the
 * value the probe publishes cannot be reached.
 *
 * Own domain: [OrderK04Model], the order whose lines hold keyed slots.
 */
class KernelProbeParallelIterationPointerTest {

    /**
     * The whole message survives, `-6` included: the error field, its type, and both reference lists, on both
     * strategies. The regression guard: before the fix both firing rows faulted. `noRef` is the liveness
     * control, showing that the message depends on the pick's `Ref`.
     */
    @Test
    fun theProbePublishesTheParallelIterationIndexInsteadOfFaulting() {
        assertFalse(Class.forName(KERNEL_POINTER_IMPL).desiredAssertionStatus(),
            "the kernel's pointer assertion must be off, as in the probe's own JVM, or -6 is unreachable")

        val fired = listOf(
            "SLOTS_BESIDE_PICK @ /Order[1]/Lines[1]/Slots OMISSION_ERROR" +
                " referenced=[/Order[1]/Lines[1]/Slots[-6]/Mark, /Order[1]/Lines[1]/Slots[-6]/SlotNo," +
                " Order/Picks/LineNo, Order/Picks/Ref]" +
                " fillToFix=[/Order[1]/Lines[1]/Slots[-6]/Mark, /Order[1]/Lines[1]/Slots[-6]/SlotNo]")
        assertEquals(
            mapOf("noSlot" to fired, "twoSlots" to fired, "noRef" to emptyList()).mapValues { (_, messages) ->
                ENGINES.associateWith { messages } },
            artifact.path("rows").associate { row ->
                row.path("name").asText() to row.path("engines").associate { engine ->
                    engine.path("engine").asText() to engine.path("messages").map(::render) } },
        )
    }

    /**
     * The error pointer's own level, which the published `field` cannot show: its house form drops the last
     * coordinate (CLI-CONFORMANCE-GAPS CG24), so the lossless key is read off the dynamic-Groovy pointer
     * instead. A kernel lock only: it never reaches our pointer factory, so it is no part of the regression
     * guard above.
     */
    @Test
    fun theErrorPointerLeavesTheGroupsOwnLevelUnknown() {
        assertEquals(mapOf("noSlot" to listOf("/Order[1]/Lines[1]/Slots[-5]"),
                "twoSlots" to listOf("/Order[1]/Lines[1]/Slots[-5]")),
            listOf("noSlot", "twoSlots").associateWith { RuntimeLaws.jsonMessages(model, DOCUMENTS.getValue(it)).map { m -> m.key() } })
    }

    internal companion object {

        const val KERNEL_POINTER_IMPL = "com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointerImpl"

        val ENGINES = listOf("kernel-groovy-dynamic", "kernel-java-static")

        fun render(message: JsonNode): String =
            "${message.path("code").asText()} @ ${message.path("field").asText()} ${message.path("type").asText()}" +
                " referenced=${message.path("referenced").map(JsonNode::asText)}" +
                " fillToFix=${message.path("fillToFix").map(JsonNode::asText)}"

        @JvmStatic
        @TempDir
        lateinit var dir: Path

        val model: String by lazy {
            val base = OrderK04Model.json()
            val order = EditSession.read(base).group("/Order")
            val slots = order.group("Lines/Slots")
            ModelWriter.addRule(base, order.rule { it.name("SlotsBesidePick").errorEntity(slots.ref())
                .`when`(Dsl.groupNotFilled(slots).and(order.str("Picks/Ref").filled()))
                .code("SLOTS_BESIDE_PICK").message(Dsl.en("Assign a slot to the picked line.")).build() })
        }

        /** Line A holds no slot, or two, beside its pick's `Ref`; the control's pick carries no `Ref`. */
        val DOCUMENTS = mapOf(
            "noSlot" to """{"Order":{"Lines":[{"LineNo":"A","Sku":"s"}],"Picks":[{"LineNo":"A","Ref":"r"}]}}""",
            "twoSlots" to """{"Order":{"Lines":[{"LineNo":"A","Sku":"s","Slots":[{"SlotNo":"1","Mark":"m"},""" +
                """{"SlotNo":"2"}]}],"Picks":[{"LineNo":"A","Ref":"r"}]}}""",
            "noRef" to """{"Order":{"Lines":[{"LineNo":"A","Sku":"s"}],"Picks":[{"LineNo":"A"}]}}""",
        )

        val artifact: JsonNode by lazy {
            RuntimeProbeComparison.probe(dir, "parallel-iteration", model, """["validateFull"]""", DOCUMENTS)
        }
    }
}
