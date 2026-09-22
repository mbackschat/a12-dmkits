package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.Requiredness
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) for a **bare STRING copy** computation
 * — `Target = [Source]`, ONE operand, no concatenation — over an EMPTY / FILLED / required-EMPTY source (the
 * IG56 Set-S probe candidate 1: only the concatenation (IF81) and comparison positions were locked; whether a
 * bare copy of an empty string CLEARS or stores an empty-string VALUE was an unwritten kernel fact).
 *
 * The target is seeded with a sentinel value so a kernel clear is a reported CHANGE (the change-only kernel
 * report vs the interpreter's report-all — the ComputeInvalidOperandDiffTest pattern).
 */
class StringCopyComputeDiffTest {

    private companion object {
        const val SRC = "/Shipment/Reference"
        const val TGT = "/Shipment/TrackingLabel"

        fun shipment(requiredSource: Boolean): String = DocumentModelDraft.named("shipment").locale("en_US")
            .rootGroup("Shipment") { g ->
                g.stringField("Reference") { r -> if (requiredSource) r.required() }
                    .stringField("TrackingLabel") {}
            }
            .build().toJson()
    }

    private fun operation(json: String): String {
        val m = BoundModel.ofJson(json)
        return RuntimeLaws.render("/Shipment", m.str(SRC).asString().expression())
    }

    private fun kOutcome(requiredSource: Boolean, sourceValue: String?): String {
        val json = shipment(requiredSource)
        val model = RuntimeLaws.withComputation(json, "/Shipment", "CopyComp", "../TrackingLabel", operation(json))
        val doc = RuntimeLaws.doc().group("/Shipment", 1)
        doc.field(TGT, "SENTINEL", 1, 1)
        sourceValue?.let { doc.field(SRC, it, 1, 1) }
        val c = RuntimeLaws.compute(model, doc).firstOrNull { it.path() == TGT }
        return when {
            c == null -> "NONE"
            c.cleared() -> "CLEARED"
            c.errored() -> "ERRORED"
            else -> "VALUE|${c.value().orElse("")}"
        }
    }

    private fun jOutcome(requiredSource: Boolean, sourceValue: String?): String {
        val json = shipment(requiredSource)
        val model = RuntimeLaws.withComputation(json, "/Shipment", "CopyComp", "../TrackingLabel", operation(json))
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val doc = RuntimeLaws.doc().group("/Shipment", 1)
        doc.field(TGT, "SENTINEL", 1, 1)
        sourceValue?.let { doc.field(SRC, it, 1, 1) }
        val raw = engine.computeOutcomesSorted(engine.instance(doc)).firstOrNull { it.startsWith("$TGT|") }
            ?.substringAfter("$TGT|") ?: return "NONE"
        return when (raw) { "ERR" -> "ERRORED"; "CLEARED" -> "CLEARED"; else -> "VALUE|$raw" }
    }

    private fun iOutcome(requiredSource: Boolean, sourceValue: String?): String {
        val json = shipment(requiredSource)
        val srcRequired = if (requiredSource) Requiredness.ABSOLUTE else Requiredness.NOT_REQUIRED
        val def = ModelDef(
            listOf(FieldDef(SRC, Kind.STRING, required = srcRequired), FieldDef(TGT, Kind.STRING)),
            emptyList(),
            computations = listOf(ComputationDef(TGT, "/Shipment", listOf(ComputationAlternative(null, operation(json))))),
        )
        val doc = def.newDocument()
            .group("/Shipment", 1)
        doc.field(TGT, "SENTINEL", 1, 1)
        sourceValue?.let { doc.field(SRC, it, 1, 1) }
        val c = DmInterpreter(def).compute(doc).firstOrNull { it.path == TGT }
        return when (c?.outcome) {
            null -> "NONE"
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED"
            ComputeOutcome.VALUE -> "VALUE|${c.value}"
        }
    }

    private fun triCheck(requiredSource: Boolean, sourceValue: String?, label: String): String {
        val k = kOutcome(requiredSource, sourceValue)
        assertEquals(k, jOutcome(requiredSource, sourceValue), "$label: groovy-dynamic vs java-static")
        assertEquals(k, iOutcome(requiredSource, sourceValue), "$label: kernel vs interpreter")
        return k
    }

    @Test
    fun filledSourceCopies() = assertEquals("VALUE|REF-42", triCheck(false, "REF-42", "filled"))

    @Test
    fun emptySourceOutcome() {
        println("[probe] bare string copy over EMPTY optional source: " + triCheck(false, null, "empty-optional"))
    }

    @Test
    fun requiredEmptySourceOutcome() {
        println("[probe] bare string copy over EMPTY required source: " + triCheck(true, null, "empty-required"))
    }
}
