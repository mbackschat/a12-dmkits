package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The interpreter's public Document ingress against A12's own, value for value: the SAME Document JSON is fed
 * to `PreparedModel.documents.readDocument` and to the Kernel's `IDocumentV2Serializer`, and both the
 * **re-written JSON** and the **formal outcome** are compared. `A12JsonIngressKernelTest` owns the kernel-side
 * measurement (and the matrix); this class is what proves our reimplementation of it.
 *
 * The ingress is the only place the interpreter can normalize, because it is the only place A12 does: A12
 * stores a typed value and re-renders from it, while every *other* kernel oracle in this package builds a V1
 * instance from raw strings, which `ValidationData.getValue` returns unchanged. So this differential covers a
 * regime none of the others reach.
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
