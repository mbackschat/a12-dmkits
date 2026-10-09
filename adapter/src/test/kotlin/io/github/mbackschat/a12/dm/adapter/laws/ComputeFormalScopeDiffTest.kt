package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Which stored cells the compute report formally checks (KERNEL-FINDINGS KF538). Every model computes the root
 * `Total = [Base] + 1`, and at most one more computation reads the `Returns` notes, targets the `Returns` tags, or
 * targets `Fit` in the non-repeating `Detail` inside `Returns`. The documents put a fourth row past the `Returns`
 * capacity of three, holding a note, a quantity or only a tag, or store a formally invalid quantity, `Detail` size and
 * root `Spare`, none of which any computation reads.
 *
 * Own domain (parcel returns).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComputeFormalScopeDiffTest {
    private enum class Variant { ROOT_ONLY, READS_NOTES, TARGETS_TAGS, TARGETS_DETAIL_FIT }

    private val models = Variant.entries.associateWith(::build)

    private fun build(variant: Variant): String {
        val draft = DocumentModelDraft.named("parcel-compute-scope").locale("en_US")
            .rootGroup("Parcel") { p ->
                p.stringField("Ref") { }
                p.numberField("Base") { n -> n.fractionDigits(0) }
                p.numberField("Spare") { n -> n.fractionDigits(0) }
                p.group("Returns") { r -> r.repeatable(3).stringField("Note") { }
                    .numberField("Qty") { n -> n.fractionDigits(0) }
                    .numberField("Tag") { n -> n.fractionDigits(0) }
                    .group("Detail") { d -> d.numberField("Size") { n -> n.fractionDigits(0) }
                        .numberField("Fit") { n -> n.fractionDigits(0) } } }
                p.numberField("Total") { n -> n.fractionDigits(0) }
                p.numberField("Count") { n -> n.fractionDigits(0) }
            }.build().toJson()
        val session = EditSession.read(draft)
        val p = session.group("/Parcel")
        var json = TypedRules.inject(session.toJson(), p.computation(p.num("Total")) {
            it.name("TotalComp").calc(p.num("Base").plus(1)).message(Dsl.en("Check the total.")).build()
        })
        json = when (variant) {
            Variant.ROOT_ONLY -> json
            Variant.READS_NOTES -> TypedRules.inject(json, p.computation(p.num("Count")) {
                it.name("CountComp").calc(Dsl.numberOfFilledFields(p.flatten("Returns*/Note")))
                    .message(Dsl.en("Check the returns.")).build()
            })
            Variant.TARGETS_TAGS -> {
                val returns = session.group("/Parcel/Returns")
                TypedRules.inject(json, returns.computation(returns.num("Tag")) {
                    it.name("TagComp").calc(p.num("Base").plus(1)).message(Dsl.en("Check the returns.")).build()
                })
            }
            Variant.TARGETS_DETAIL_FIT -> {
                val detail = session.group("/Parcel/Returns/Detail")
                TypedRules.inject(json, detail.computation(detail.num("Fit")) {
                    it.name("FitComp").calc(p.num("Base").plus(1)).message(Dsl.en("Check the returns.")).build()
                })
            }
        }
        return json
    }

    private fun parcel(rows: List<String>, spare: String? = null) =
        """{"Parcel":{"Ref":"P-1","Base":"4",""" + (spare?.let { "\"Spare\":\"$it\"," } ?: "") +
            """"Returns":[${rows.joinToString(",")}]}}"""

    private val three = List(3) { """{"Note":"r"}""" }

    private val documents = linkedMapOf(
        "a fourth row holding a note" to parcel(three + """{"Note":"r"}"""),
        "a fourth row holding a quantity" to parcel(three + """{"Qty":"2"}"""),
        "a fourth row holding only a tag" to parcel(three + """{"Tag":"1"}"""),
        "an invalid quantity, size and spare" to
            parcel(listOf("""{"Note":"r","Qty":"1.5","Detail":{"Size":"2.5"}}""") + three.drop(1), spare = "1.5"),
    )

    /** The compute report's formal findings, as code and cell, pinned against dynamic Groovy. */
    @Test
    fun theComputeReportChecksTheCellsTheProgrammeKeeps() {
        val actual = LinkedHashMap<String, List<String>>()
        for (variant in Variant.entries) for ((name, json) in documents) {
            actual["$variant, $name"] = DocumentDifferential.kernel(models.getValue(variant), json, dynamic = true)
                .operandErrors.map { it.code + " " + it.pointer }.sorted()
        }
        val row = "zuGrosseZeile /Parcel[1]/Returns"
        fun beyond(field: String) = "zuGrosseKontextnummer /Parcel[1]/Returns[4]/$field"
        val invalid = listOf("zahlHatDezimalTrenner /Parcel[1]/Returns[1]/Detail[1]/Size",
            "zahlHatDezimalTrenner /Parcel[1]/Returns[1]/Qty")
        // A root target pulls in nothing. A read pulls in the field read. A target below a repeatable group pulls in
        // every uncomputed field that group contains, at any depth: the computed tag itself stays out.
        assertEquals(linkedMapOf(
            "ROOT_ONLY, a fourth row holding a note" to emptyList(),
            "ROOT_ONLY, a fourth row holding a quantity" to emptyList(),
            "ROOT_ONLY, a fourth row holding only a tag" to emptyList(),
            "ROOT_ONLY, an invalid quantity, size and spare" to emptyList(),
            "READS_NOTES, a fourth row holding a note" to listOf(beyond("Note"), row),
            "READS_NOTES, a fourth row holding a quantity" to listOf(row),
            "READS_NOTES, a fourth row holding only a tag" to listOf(row),
            "READS_NOTES, an invalid quantity, size and spare" to emptyList(),
            "TARGETS_TAGS, a fourth row holding a note" to listOf(beyond("Note"), row),
            "TARGETS_TAGS, a fourth row holding a quantity" to listOf(beyond("Qty"), row),
            "TARGETS_TAGS, a fourth row holding only a tag" to listOf(row),
            "TARGETS_TAGS, an invalid quantity, size and spare" to invalid,
            "TARGETS_DETAIL_FIT, a fourth row holding a note" to listOf(beyond("Note"), row),
            "TARGETS_DETAIL_FIT, a fourth row holding a quantity" to listOf(beyond("Qty"), row),
            "TARGETS_DETAIL_FIT, a fourth row holding only a tag" to listOf(beyond("Tag"), row),
            "TARGETS_DETAIL_FIT, an invalid quantity, size and spare" to invalid,
        ), actual)
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        models.values.forEach { DocumentDifferential.assertAgree(it, documents) }
    }

    @Test
    fun everyModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), models.values.flatMap { MessageParameterProbes.kernel(it) })
    }
}
