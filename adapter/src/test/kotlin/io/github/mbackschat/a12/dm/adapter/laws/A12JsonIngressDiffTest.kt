package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.DocumentInputError
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import com.mgmtp.a12.kernel.md.document.api.services.DocumentSerializationException
import com.mgmtp.a12.kernel.md.serializer.document.internal.service.serialization.DocumentJsonAndXmlDeserializationInfoImpl
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The interpreter's public Document ingress against A12's own, value for value: the SAME Document JSON is fed
 * to `PreparedModel.documents.readDocument` and to the Kernel's `IDocumentV2Serializer`, and both the
 * **re-written JSON** and the **formal outcome** are compared. `A12JsonIngressKernelTest` owns the kernel-side
 * measurement (and the matrix); this class is what proves our reimplementation of it.
 *
 * A12's Document JSON reader stores typed values and re-renders from them. The explicit V1/String helpers
 * preserve raw input instead; both ingress regimes remain distinct in [RuntimeLaws]. This matrix locks the
 * serializer's rewrite as well as the formal validation result, beyond the runtime channels [DocumentDifferential] compares.
 *
 * [KNOWN_DIVERGENCES] is the exhaustive, characterized residual — a new disagreement fails here, and so does a
 * fixed one. Each entry names why it is left standing (INTERPRETER-GAPS / IF227).
 */
class A12JsonIngressDiffTest {

    private val model = RuntimeLaws.model("models/order.dm.json")
    private val prepared by lazy { PreparedModel.loadExpandedJson(model) }
    private val interpreter by lazy { prepared.createInterpreter() }

    @Test
    fun theInterpretersJsonIngressReproducesA12sRewriteAndItsFormalOutcome() {
        val divergences = A12JsonIngressKernelTest.rows().mapNotNull { row ->
            val kernel = RuntimeLaws.jsonIngress(model, row.documentJson())
            val snapshot = prepared.documents.readDocument(row.documentJson())
            val encoded = prepared.documents.encodeDocument(snapshot)
            val codes = interpreter.validateFull(snapshot).findings.map { it.code }.sorted()
            when {
                encoded != kernel.rewritten() ->
                    "${row.field()}=${row.authored()} WRITE kernel=${kernel.rewritten()} interpreter=$encoded"
                codes != kernel.codes() ->
                    "${row.field()}=${row.authored()} EVAL kernel=${kernel.codes()} interpreter=$codes"
                else -> null
            }
        }.toSet()

        assertEquals(KNOWN_DIVERGENCES, divergences, "the interpreter's A12 Document-JSON ingress drifted")
    }

    @Test
    fun notificationOnlyNodesPreserveSiblingsWhileFatalInputsRefuseTheDocument() {
        val json = DocumentModelDraft.named("parcel-json-shapes").locale("en_US")
            .rootGroup("Parcel") { p -> p.stringField("Sibling") { }
                .numberField("Amount") { it.fractionDigits(0, 2) }
                .group("Lines") { g -> g.repeatable(3).stringField("Note") { } } }
            .build().toJson()
        val documents = PreparedModel.loadExpandedJson(json).documents
        val expected = """{"Parcel":{"Sibling":"kept"}}"""
        val entries = listOf(
            "known-group-scalar" to "\"Lines\":7", "known-group-null" to "\"Lines\":null",
            "known-group-empty-array" to "\"Lines\":[]", "known-group-null-array" to "\"Lines\":[null]",
            "known-field-object" to "\"Amount\":{}", "known-field-empty-array" to "\"Amount\":[]",
            "known-field-null-array" to "\"Amount\":[null]", "known-field-object-array" to "\"Amount\":[{}]",
            "unknown-scalar" to "\"Unexpected\":7", "unknown-object" to "\"Unexpected\":{}",
            "unknown-empty-array" to "\"Unexpected\":[]", "unknown-null-array" to "\"Unexpected\":[null]",
            "unknown-object-array" to "\"Unexpected\":[{}]",
        )
        assertEquals(entries.size, entries.map { it.first }.distinct().size)
        for ((label, entry) in entries) {
            val input = """{"Parcel":{"Sibling":"kept",$entry}}"""
            assertEquals(expected, RuntimeLaws.jsonIngress(json, input).rewritten(), "$label kernel")
            assertEquals(expected, documents.encodeDocument(documents.readDocument(input)), "$label interpreter")
        }
        for (value in listOf("{}", "[]", "\"P-1\"", "7")) {
            val input = """{"id":$value,"Parcel":{"Sibling":"kept"}}"""
            val output = when (value) {
                "{}", "[]" -> expected
                "7" -> """{"id":"7","Parcel":{"Sibling":"kept"}}"""
                else -> """{"id":"P-1","Parcel":{"Sibling":"kept"}}"""
            }
            // SPEC07's pinned serializer accepts numeric ids in production but asserts under -ea.
            if (value == "7" && DocumentJsonAndXmlDeserializationInfoImpl::class.java.desiredAssertionStatus()) {
                assertThrows(AssertionError::class.java) { RuntimeLaws.jsonIngress(json, input) }
            } else assertEquals(output, RuntimeLaws.jsonIngress(json, input).rewritten(), "id=$value kernel")
            assertEquals(output, documents.encodeDocument(documents.readDocument(input)), "id=$value interpreter")
        }
        for (input in listOf("""{"Parcel":}""", "[]", """{"id":true}""", """{"id":null}""")) {
            assertThrows(DocumentSerializationException::class.java) { RuntimeLaws.jsonIngress(json, input) }
            assertThrows(DocumentInputError::class.java) { documents.readDocument(input) }
        }
    }

    private companion object {
        // EMPTY, and that is the measured state — not an unwritten row. A12 renders one stored decimal twice
        // (the serializer's `toPlainString()` versus the runtime's
        // `setScale(max(stripTrailingZeros().scale(), minFractionalDigits))`), and the cell now carries BOTH:
        // the stored text the encoder writes back, and the evaluation text every channel reads (IF231). The
        // `Quantity="250.00"` row that stood here, and the whole PADDING direction this model could not even
        // express before `/Order/LineTotal` existed, are gone rather than characterized.
        val KNOWN_DIVERGENCES = emptySet<String>()
    }
}
