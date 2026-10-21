package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A computed value meets its target's type format but none of the input gates (KERNEL-FINDINGS KF547), and a
 * dependent computation reads it as computed, without checking it again. Under a model whose characters are letters
 * and the blank, a stored date is `ZeichenNichtImZeichensatz`, while a date built from constants is stored all the
 * same and feeds what reads it: a later date, a range over the two, a copy of that range, and the first filled of a
 * dotted computed column. A computed number, whose digits the charset excludes as well, feeds a sum over its column
 * and a product of that sum. The same model with the default charset is the control that holds every other variable.
 *
 * Own domain (a claim's cover period and its instalments).
 */
class ComputedValueInputGateDiffTest {

    private fun model(lettersOnly: Boolean): String = run {
        val draft = DocumentModelDraft.named("claim-computed-reads").locale("en_US")
        if (lettersOnly) draft.supportedCharacters("A-Z", "a-z", " ")
        val session = EditSession.read(draft
            .rootGroup("Claim") { c -> c.stringField("Ref") { }
                .dateField("Reported") { it.format("yyyy-MM-dd") }
                .dateField("Due") { it.format("yyyy-MM-dd") }.dateField("Later") { it.format("yyyy-MM-dd") }
                .dateRangeField("Cover") { it.format("yyyy-MM-dd").separator("/") }
                .dateRangeField("CoverCopy") { it.format("yyyy-MM-dd").separator("/") }
                .dateField("FirstInstalment") { it.format("dd.MM.yyyy") }
                .numberField("TotalFee") { it.fractionDigits(0) }.numberField("FeeTwice") { it.fractionDigits(0) }
                .group("Instalments") { i -> i.repeatable(2).dateField("On") { it.format("dd.MM.yyyy") }
                    .numberField("Fee") { it.fractionDigits(0) } } }
            .build().toJson())
        val claim = session.group("/Claim")
        val march5 = Dsl.date(Dsl.number(5), Dsl.number(3), Dsl.number(2024))
        claim.addComputation(claim.date("Due")) { it.name("DueComp").calc(march5).message(Dsl.en("Check the due date.")).build() }
        claim.addComputation(claim.date("Later")) {
            it.name("LaterComp").calc(Dsl.addDays(claim.date("Due"), 1)).message(Dsl.en("Check the later date.")).build()
        }
        claim.addComputation(claim.field("Cover")) {
            it.name("CoverComp").calc(Dsl.dateRange(claim.date("Due"), claim.date("Later"))).message(Dsl.en("Check the cover.")).build()
        }
        claim.addComputation(claim.field("CoverCopy")) {
            it.name("CoverCopyComp").calc(claim.dateRange("Cover").expression()).message(Dsl.en("Check the cover copy.")).build()
        }
        val instalments = session.group("/Claim/Instalments")
        instalments.addComputation(instalments.date("On")) {
            it.name("OnComp").calc(march5).message(Dsl.en("Check the instalment date.")).build()
        }
        instalments.addComputation(instalments.num("Fee")) {
            it.name("FeeComp").calc(Dsl.number(5)).message(Dsl.en("Check the fee.")).build()
        }
        claim.addComputation(claim.num("TotalFee")) {
            it.name("TotalFeeComp").calc(Dsl.sum(claim.flatten("Instalments*/Fee"))).message(Dsl.en("Check the total fee.")).build()
        }
        claim.addComputation(claim.num("FeeTwice")) {
            it.name("FeeTwiceComp").calc(claim.num("TotalFee").times(2)).message(Dsl.en("Check the doubled fee.")).build()
        }
        claim.addComputation(claim.date("FirstInstalment")) {
            it.name("FirstInstalmentComp").calc(Dsl.firstFilledDate(claim.flatten("Instalments*/On")))
                .message(Dsl.en("Check the first instalment.")).build()
        }
        session.toJson()
    }

    private val models = linkedMapOf("letters only" to model(lettersOnly = true), "the default charset" to model(lettersOnly = false))

    private val documents = linkedMapOf(
        "a stored date, the control" to """{"Claim":{"Ref":"R","Reported":"2024-03-05","Instalments":[{},{}]}}""",
        "nothing stored" to """{"Claim":{"Ref":"R","Instalments":[{},{}]}}""",
    )

    @Test
    fun bothModelsAreKernelConsistent() {
        for ((label, model) in models) assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model), label)
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        for (model in models.values) DocumentDifferential.assertAgree(model, documents)
    }

    @Test
    fun aDependentReadsTheComputedValueWithoutTheInputGates() {
        val observed = models.mapValues { (_, model) -> documents.mapValues { (_, json) ->
            DocumentDifferential.kernelMessages(model, json, true).map { "${it.code}@${it.pointer.removePrefix("/Claim[1]/")}" } +
                DocumentDifferential.kernel(model, json, dynamic = true).outcomes.map { (k, v) -> "$k=$v" }.sorted()
        } }
        val computed = listOf("Cover=VALUE|2024-03-05/2024-03-06", "CoverCopy=VALUE|2024-03-05/2024-03-06",
            "Due=VALUE|2024-03-05", "FeeTwice=VALUE|20", "FirstInstalment=VALUE|05.03.2024", "Instalments/Fee=VALUE|5",
            "Instalments/On=VALUE|05.03.2024", "Instalments[2]/Fee=VALUE|5", "Instalments[2]/On=VALUE|05.03.2024",
            "Later=VALUE|2024-03-06", "TotalFee=VALUE|10")
        assertEquals(mapOf(
            "letters only" to mapOf("a stored date, the control" to listOf("ZeichenNichtImZeichensatz@Reported") + computed,
                "nothing stored" to computed),
            "the default charset" to mapOf("a stored date, the control" to computed, "nothing stored" to computed),
        ), observed)
    }
}
