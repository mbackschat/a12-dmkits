package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.core.rt.a12internal.validation.IIdentifier
import com.mgmtp.a12.kernel.md.document.apiV2.DocumentPointer
import com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointer
import com.mgmtp.a12.kernel.md.document.apiV2.DocumentMultiPointer
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.DocumentV2
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.FieldInstanceV2
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.utils.IDocumentV2Visitor
import com.mgmtp.a12.kernel.md.rt.api.ICustomCondition
import io.github.mbackschat.a12.dm.adapter.KernelCustomConditions
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.CustomCondition as PublicCustomCondition
import io.github.mbackschat.a12.dm.interpreter.CustomConditionRegistry
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.InterpreterConfiguration
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What a custom condition sees of a field that is absent, empty, or stored as `""`. A12 hands a project's
 * md-API condition its `DocumentV2`, where a String stored as `""` is a field holding `""` (so `fieldValue != null`
 * holds), and hands a core condition the `IData` view, whose `getValue` answers `""` for absent and empty alike
 * (`ValidationData.VALUE_FOR_ABSENT_AND_EMPTY`). The interpreter's public view mirrors the first and the
 * [KernelCustomConditions] bridge the second (a12-kernel-lean's custom-condition-bound replay, rows over `""`).
 *
 * Own domain (an order with a note, a quantity, and lines).
 */
class CustomConditionEmptyCellDiffTest {

    private val skeleton = DocumentModelDraft.named("order-notes").locale("en_US")
        .rootGroup("Order") { r -> r
            .stringField("Ref") { }.stringField("Note") { }.numberField("Qty") { it.fractionDigits(0) }
            .group("Lines") { l -> l.repeatable(3).stringField("Sku") { } } }
        .build().toJson()
    private val edit = EditSession.read(skeleton)
    private val order = edit.group("/Order")
    private val model = ModelWriter.addRule(edit.toJson(), order.rule { it.name("ObserveRule").code("OBSERVE")
        .errorField(order.field("Ref")).`when`(order.str("Ref").filled().and(Dsl.customCondition("Observe")))
        .message(Dsl.en("Check the order.")).build() })

    private val documents = linkedMapOf(
        "absent" to """{"Order":{"Ref":"r"}}""",
        "empty" to """{"Order":{"Ref":"r","Note":""}}""",
        "null" to """{"Order":{"Ref":"r","Note":null}}""",
        "text" to """{"Order":{"Ref":"r","Note":"n"}}""",
        "numberEmpty" to """{"Order":{"Ref":"r","Qty":""}}""",
        "lineEmpty" to """{"Order":{"Ref":"r","Lines":[{"Sku":""}]}}""",
    )

    private class Id(private val name: String, private val indices: LongArray) : IIdentifier {
        override fun getName() = name
        override fun getIndices() = indices
        override fun getUsb() = IIdentifier.USB_NOT_SPECIFIED
        override fun equals(other: Any?) = other is IIdentifier && other.name == name && other.indices.contentEquals(indices)
        override fun hashCode() = name.hashCode() * 31 + indices.contentHashCode()
    }

    /** A12's md view of the Document: each field instance it holds, by md pointer, with its value as text. */
    private fun mdFields(document: DocumentV2): Map<String, String?> = buildMap {
        document.traverse(object : IDocumentV2Visitor {
            override fun visitField(pointer: DocumentPointer, field: FieldInstanceV2) {
                put(pointer.toString().replace("[1]", ""), field.value()?.toString())
            }
        })
    }

    private val note = "Order/Note"

    /** The md-API condition a project writes, and its port to the interpreter's public view, both firing iff
     *  the Note field holds a value. Firing is compared too, so a view that is never reached cannot pass. */
    @Test
    fun thePublicViewReadsAStoredEmptyStringAsA12sMdViewDoes() {
        val engine = RuntimeLaws.kernelEngine(model)
        val prepared = PreparedModel.loadExpandedJson(model)
        for ((label, json) in documents) {
            var kernelSaw: Map<String, String?>? = null
            val md = object : ICustomCondition {
                override fun check(document: DocumentV2, relevant: Set<DocumentMultiPointer>?, incorrect: Set<DocumentPointer>,
                    error: PartiallyKnownDocumentMultiPointer): Boolean {
                    val fields = mdFields(document).also { kernelSaw = it }
                    return fields[note] != null
                }
            }
            val kernelFired = engine.validationSignaturesSorted(engine.instance(json), java.util.Locale.US, null,
                mapOf("Observe" to md)).count { it.startsWith("OBSERVE|") }
            assertEquals(MD_VIEW[label], kernelSaw, label)

            var publicSaw: Map<String, String?>? = null
            val conditions = CustomConditionRegistry.builder().register("Observe", PublicCustomCondition { invocation ->
                val view = invocation.document
                val fields = view.fields().associate { it.path.drop(1) to view.value(it) }.also { publicSaw = it }
                fields[note] != null
            }).build()
            val interpreter = prepared.createInterpreter(InterpreterConfiguration.builder().customConditions(conditions).build())
            val publicFired = interpreter.validateFull(prepared.documents.readDocument(json)).findings.count { it.code == "OBSERVE" }
            assertEquals(kernelSaw, publicSaw, label)
            assertEquals(kernelFired, publicFired, label)
        }
    }

    /** A core condition a project ships, run on A12's core route and through [KernelCustomConditions] on the
     *  interpreter: `getValue` answers `""` for an absent or empty field, and the iterator lists every field
     *  instance the Document holds, empty ones included. Group instances in the iterator are a separate gap. */
    @Test
    fun aShippedCoreConditionSeesA12sCoreView() {
        val engine = RuntimeLaws.kernelEngine(model)
        val def = ModelLoader.load(model)
        val probes = listOf(Id("/Order/Note", longArrayOf(1, 1)), Id("/Order/Qty", longArrayOf(1, 1)),
            Id("/Order/Lines/Sku", longArrayOf(1, 1, 1)))
        val fieldPaths = setOf("/Order/Ref", "/Order/Note", "/Order/Qty", "/Order/Lines/Sku")
        for ((label, json) in documents) {
            val seen = mutableListOf<List<String?>>()
            val observer = com.mgmtp.a12.kernel.core.rt.a12internal.validation.ICustomCondition { data, _, _, _ ->
                seen += data.filter { it.name in fieldPaths }.map { it.name + it.indices.toList() }.sorted() +
                    probes.map { data.getValue(it) }
                false
            }
            engine.coreModeOracle().validateWithCoreConditions(engine.instance(json), mapOf("Observe" to observer))
            val kernel = seen.single()
            seen.clear()
            val report = DmInterpreter(def, customConditions = KernelCustomConditions.fromCore(mapOf("Observe" to observer)))
                .validate(def.readDocumentJson(json))
            assertEquals(emptyList<Any>(), report.unsupported, label)
            assertEquals(kernel, seen.single(), label)
        }
    }

    private companion object {
        /** A12's md view, measured: a String stored as `""` is a field holding `""`; JSON `null` and a number
         *  stored as `""` are fields holding no value; an absent field is not there at all. */
        val MD_VIEW = mapOf(
            "absent" to mapOf("Order/Ref" to "r"),
            "empty" to mapOf("Order/Ref" to "r", "Order/Note" to ""),
            "null" to mapOf("Order/Ref" to "r", "Order/Note" to null),
            "text" to mapOf("Order/Ref" to "r", "Order/Note" to "n"),
            "numberEmpty" to mapOf("Order/Ref" to "r", "Order/Qty" to null),
            "lineEmpty" to mapOf("Order/Ref" to "r", "Order/Lines/Sku" to ""),
        )
    }
}
