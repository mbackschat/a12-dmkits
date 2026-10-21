package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What a `RepetitionNotUnique` leaf contributes to its message's references (KERNEL-FINDINGS KF557, a12-kernel-lean
 * SPEC-2026-10-13-09). When a sibling branch of the condition fires the message and the leaf does not, the message
 * refers to the sibling's field alone, none of the leaf's keys, the current row's included; when the leaf fires,
 * it refers to its keys over the duplicate cluster. A leaf firing as an omission beside a winning sibling is not
 * varied here.
 *
 * Own domain (a warehouse's aisles and their coded bins).
 */
class RepetitionNotUniqueSiblingReferenceDiffTest {

    private val model: String = run {
        val session = EditSession.read(DocumentModelDraft.named("warehouse-bins").locale("en_US")
            .rootGroup("Warehouse") { w -> w.group("Aisles") { a -> a.repeatable(2)
                .group("Bins") { b -> b.repeatable(3).stringField("Code") { }.numberField("Qty") { it.fractionDigits(0) } } } }
            .build().toJson())
        val aisles = session.group("/Warehouse/Aisles")
        aisles.addRule {
            it.name("BinCheck").code("BIN_CHECK").errorField(aisles.num("Bins/Qty"))
                .`when`(Dsl.repetitionNotUnique(aisles.field("Bins/Code")).or(aisles.num("Bins/Qty").gt(5L)))
                .message(Dsl.en("Check the bin.")).build()
        }
        session.toJson()
    }

    private fun aisles(vararg bins: String) = """{"Warehouse":{"Aisles":[${bins.joinToString(",") { """{"Bins":[$it]}""" }}]}}"""

    private val documents = linkedMapOf(
        "sibling fires, codes distinct" to aisles("""{"Code":"A","Qty":1},{"Code":"B","Qty":9}"""),
        "sibling fires beside a bin without a code" to aisles("""{"Code":"A","Qty":9},{"Qty":1}"""),
        "sibling fires on the bin without a code" to aisles("""{"Code":"A","Qty":1},{"Qty":9}"""),
        "duplicate fires" to aisles("""{"Code":"A","Qty":1},{"Code":"A","Qty":2}"""),
        "duplicate and sibling" to aisles("""{"Code":"A","Qty":9},{"Code":"A","Qty":1}"""),
        "three bins, one without a code, sibling fires" to aisles("""{"Code":"A","Qty":1},{"Qty":2},{"Code":"B","Qty":9}"""),
        "sibling here, duplicate in the other aisle" to
            aisles("""{"Code":"A","Qty":9},{"Code":"B","Qty":1}""", """{"Code":"C","Qty":1},{"Code":"C","Qty":1}"""),
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /** The references of every message, on A12 itself, so the agreement above is not agreement on nothing. */
    @Test
    fun aNonFiringLeafContributesNoReference() {
        fun refs(document: String) = DocumentDifferential.kernelMessages(model, documents.getValue(document), true)
            .map { it.pointer.removePrefix(AISLE) to it.referenced.map { r -> r.removePrefix(AISLE) } }
        assertEquals(listOf("Bins[2]/Qty" to listOf("Bins[2]/Qty")), refs("sibling fires, codes distinct"))
        assertEquals(listOf("Bins[1]/Qty" to listOf("Bins[1]/Qty")), refs("sibling fires beside a bin without a code"))
        assertEquals(listOf("Bins[2]/Qty" to listOf("Bins[2]/Qty")), refs("sibling fires on the bin without a code"))
        assertEquals(listOf("Bins[3]/Qty" to listOf("Bins[3]/Qty")), refs("three bins, one without a code, sibling fires"))
        // The firing leaf, with and without the sibling: the duplicate cluster's keys and the row's own field.
        val cluster = listOf("Bins[1]/Qty" to listOf("Bins[1]/Code", "Bins[1]/Qty", "Bins[2]/Code"),
            "Bins[2]/Qty" to listOf("Bins[1]/Code", "Bins[2]/Code", "Bins[2]/Qty"))
        assertEquals(cluster, refs("duplicate fires"))
        assertEquals(cluster, refs("duplicate and sibling"))
        // The sibling's aisle refers to its own field alone; the other aisle's duplicate keeps its own cluster.
        val other = "/Warehouse[1]/Aisles[2]/"
        assertEquals(listOf("Bins[1]/Qty" to listOf("Bins[1]/Qty"),
            "${other}Bins[1]/Qty" to listOf("${other}Bins[1]/Code", "${other}Bins[1]/Qty", "${other}Bins[2]/Code"),
            "${other}Bins[2]/Qty" to listOf("${other}Bins[1]/Code", "${other}Bins[2]/Code", "${other}Bins[2]/Qty")),
            refs("sibling here, duplicate in the other aisle"))
    }

    private companion object {
        const val AISLE = "/Warehouse[1]/Aisles[1]/"
    }
}
