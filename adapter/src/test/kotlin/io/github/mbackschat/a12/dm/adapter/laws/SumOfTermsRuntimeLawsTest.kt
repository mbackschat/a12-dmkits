package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.PlacementKind
import io.github.mbackschat.a12.dm.interpreter.conformance.RelevantPointer
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * KERNEL-FINDINGS KF670 — what kernel 31.1.1's `SumOfTerms` evaluates to, read on BOTH kernel strategies (the
 * dynamic-Groovy anchor and generated Java) from one model and one document per row.
 *
 * Each document is read as the sorted signatures of every rule and computation, `code|type|pointer` and
 * `pointer|VALUE|value`. The rules share one term, so a row that moves one of them and not the others names the
 * comparison, not the sum. The rival the rows are built to refute is `SumOfProducts`' contract, where a malformed
 * cell makes the whole sum undecidable ([SumOfProductsDiffTest]).
 *
 * The conditions are raw strings because the typed surface does not author `SumOfTerms` yet.
 */
class SumOfTermsRuntimeLawsTest {

    private companion object {
        const val HOLDER = "/Policy/Holder"
        const val CLAIMS = "/Policy/Claims"
        const val AMOUNT = "/Policy/Claims/Amount"
        const val RESERVE = "/Policy/Claims/Reserve"
        const val STATUS = "/Policy/Claims/Status"
        const val DEDUCTIBLE = "/Policy/Claims/Deductible"
        const val PARTS = "/Policy/Claims/Parts"
        const val QTY = "/Policy/Claims/Parts/Qty"
        const val AT = "/Policy[1]/Holder"
        const val TERM = "SumOfTerms(Claims*, [Claims/Amount] + [Claims/Reserve])"

        val RULES = linkedMapOf(
            "GT" to "$TERM > 100",
            "LT" to "$TERM < 50",
            "EQ0" to "SumOfTerms(Claims*, [Claims/Amount]) == 0",
            "DIV" to "SumOfTerms(Claims*, [Claims/Amount] / [Claims/Reserve]) > 1",
            "FILT" to "SumOfTerms(Claims* Having [Claims/Status] == \"open\", [Claims/Amount]) > 100",
            "NEST" to "SumOfTerms(Claims*/Parts*, [Claims/Parts/Qty]) > 5",
        )
    }

    private val model: String = run {
        val base = DocumentModelDraft.named("policy-sum-of-terms-runtime").locale("en_US")
            .rootGroup("Policy") { p ->
                p.stringField("Holder") { }
                    .numberField("Total") { it.fractionDigits(2) }
                    .group("Claims") { c ->
                        c.repeatable(4)
                            .stringField("Status") { }
                            .numberField("Amount") { it.fractionDigits(2) }
                            .numberField("Reserve") { it.fractionDigits(2) }
                            .numberField("Deductible") { it.fractionDigits(2) }
                            .group("Parts") { it.repeatable(3).numberField("Qty") { q -> q.fractionDigits(0) } }
                    }
            }.build().toJson()
        val ruled = RULES.entries.fold(base) { json, (code, condition) ->
            RuntimeLaws.withRule(json, "/Policy", code, "../Holder", code, "FieldFilled(Holder) And $condition")
        }
        val perRow = RuntimeLaws.withRule(ruled, "/Policy", "ROW", "../Claims/Amount", "ROW",
            "FieldFilled(Claims/Amount) And SumOfTerms(Claims/Parts*, [Claims/Parts/Qty]) > 5")
        val control = RuntimeLaws.withRule(perRow, "/Policy", "CTRL", "../Holder", "CTRL",
            "FieldFilled(Holder) And Sum(Claims*/Amount) > 50")
        RuntimeLaws.withComputation(control, "/Policy", "TotalComp", "../Total", TERM)
    }

    private class Doc {
        val placements = mutableListOf(Placement(PlacementKind.GROUP, "/Policy", listOf(1)),
            Placement(PlacementKind.FIELD, HOLDER, listOf(1, 1), "Ada"))

        fun claim(row: Int, amount: String? = null, reserve: String? = null, status: String? = null) = apply {
            placements += Placement(PlacementKind.GROUP, CLAIMS, listOf(1, row))
            amount?.let { placements += Placement(PlacementKind.FIELD, AMOUNT, listOf(1, row, 1), it) }
            reserve?.let { placements += Placement(PlacementKind.FIELD, RESERVE, listOf(1, row, 1), it) }
            status?.let { placements += Placement(PlacementKind.FIELD, STATUS, listOf(1, row, 1), it) }
        }

        fun deductible(row: Int, value: String) = apply {
            placements += Placement(PlacementKind.FIELD, DEDUCTIBLE, listOf(1, row, 1), value)
        }

        fun part(row: Int, part: Int, qty: String) = apply {
            placements += Placement(PlacementKind.GROUP, PARTS, listOf(1, row, part))
            placements += Placement(PlacementKind.FIELD, QTY, listOf(1, row, part, 1), qty)
        }
    }

    /** Both strategies' readings of [doc] under [op], asserted equal, the Groovy one returned. */
    private fun read(doc: Doc, op: Op): List<String> {
        val groovy = CorpusEngines.groovyDynamic(model, doc.placements, op)
        assertEquals(groovy, CorpusEngines.javaStatic(model, doc.placements, op), "generated Java parts from Groovy")
        return groovy
    }

    private fun validated(doc: Doc) = read(doc, Op.ValidateFull).filter { it.substringBefore('|') in RULES.keys + "ROW" }

    @Test
    fun theModelIsAdmitted() = assertEquals(listOf<String>(), RuntimeLaws.consistencyMessages(model))

    @Test
    fun aTermSumOverRowsComparesAndAssigns() {
        val documents = linkedMapOf(
            "two rows, every operand filled" to Doc().claim(1, "30", "10").claim(2, "50", "20"),
            "every row of the capacity filled" to
                Doc().claim(1, "30", "10").claim(2, "30", "10").claim(3, "30", "10").claim(4, "30", "10"),
            "no row" to Doc(),
            "one row, its reserve empty" to Doc().claim(1, "30"),
            "one row's amount malformed" to Doc().claim(1, "30", "10").claim(2, "abc", "20"),
            "one row's deductible malformed, a field no term reads" to
                Doc().claim(1, "30", "10").claim(2, "50", "20").deductible(2, "abc"),
            "one row holding only a status" to Doc().claim(1, "30", "10").claim(2, status = "open"),
            "one row dividing by zero" to Doc().claim(1, "30", "10").claim(2, "50", "0"),
            "an open row over the bar beside a closed one" to
                Doc().claim(1, "150", "0", "open").claim(2, "10", "0", "closed"),
            "nested parts summing over the bar, one claim alone over it" to
                Doc().claim(1, "1", "0").part(1, 1, "2").part(1, 2, "4").claim(2, "1", "0").part(2, 1, "4"),
        )
        val expected = linkedMapOf(
            "two rows, every operand filled" to listOf("DIV|OMISSION_ERROR|$AT", "GT|OMISSION_ERROR|$AT"),
            "every row of the capacity filled" to listOf("DIV|VALUE_ERROR|$AT", "GT|VALUE_ERROR|$AT"),
            "no row" to listOf("EQ0|OMISSION_ERROR|$AT", "LT|OMISSION_ERROR|$AT"),
            "one row, its reserve empty" to listOf("LT|OMISSION_ERROR|$AT"),
            "one row's amount malformed" to listOf(),
            "one row's deductible malformed, a field no term reads" to
                listOf("DIV|OMISSION_ERROR|$AT", "GT|OMISSION_ERROR|$AT"),
            "one row holding only a status" to listOf("DIV|OMISSION_ERROR|$AT", "LT|OMISSION_ERROR|$AT"),
            "one row dividing by zero" to listOf("DIV|OMISSION_ERROR|$AT"),
            "an open row over the bar beside a closed one" to listOf("FILT|OMISSION_ERROR|$AT", "GT|OMISSION_ERROR|$AT"),
            "nested parts summing over the bar, one claim alone over it" to listOf("LT|OMISSION_ERROR|$AT",
                "NEST|OMISSION_ERROR|$AT", "ROW|OMISSION_ERROR|/Policy[1]/Claims[1]/Amount"),
        )
        assertEquals(expected, documents.mapValues { (_, doc) -> validated(doc) })
    }

    @Test
    fun aComputedTermSum() {
        val documents = linkedMapOf(
            "two rows, every operand filled" to Doc().claim(1, "30", "10").claim(2, "50", "20.5"),
            "no row" to Doc(),
            "one row's amount malformed" to Doc().claim(1, "30", "10").claim(2, "abc", "20"),
        )
        val expected = linkedMapOf(
            "two rows, every operand filled" to listOf("/Policy[1]/Total|VALUE|110.5"),
            "no row" to listOf("/Policy[1]/Total|VALUE|0"),
            "one row's amount malformed" to listOf(),
        )
        assertEquals(expected, documents.mapValues { (_, doc) -> read(doc, Op.Compute) })
    }

    /**
     * Partial validation decides a term sum only when every ROW of the iteration entity is relevant as a whole —
     * the group, or every field it holds — over the whole capacity. `CTRL`, a plain `Sum` over one of the term's
     * fields, is the liveness control: it is decided by that field alone, so a row where it fires and the term sum
     * does not is a row the term sum's own relevance rule refuses.
     */
    @Test
    fun partialValidationNeedsEveryRowOfTheCapacityRelevantAsAWhole() {
        val doc = Doc().claim(1, "30", "10").claim(2, "50", "20")
        val holder = RelevantPointer(HOLDER, listOf(1, 1))
        fun cells(path: String, vararg rows: Int) = rows.map { RelevantPointer(path, listOf(1, it, 1)) }
        fun rows(vararg rows: Int) = rows.map { RelevantPointer(CLAIMS, listOf(1, it)) }
        val qty = listOf(RelevantPointer(QTY, listOf(1, 0, 0, 1)))
        fun part(vararg relevant: List<RelevantPointer>) = validated(doc, Op.ValidatePart(listOf(holder) + relevant.flatMap { it }))
        val actual = linkedMapOf(
            "the group, any row" to part(rows(0)),
            "the group, every declared row" to part(rows(1, 2, 3, 4)),
            "the group, the two filled rows" to part(rows(1, 2)),
            "every field of the group, any row" to
                part(cells(AMOUNT, 0), cells(RESERVE, 0), cells(STATUS, 0), cells(DEDUCTIBLE, 0), qty),
            "every field but one the term never reads, any row" to
                part(cells(AMOUNT, 0), cells(RESERVE, 0), cells(STATUS, 0), qty),
            "both term fields, any row" to part(cells(AMOUNT, 0), cells(RESERVE, 0)),
            "one term field, any row" to part(cells(AMOUNT, 0)),
            "both term fields, every declared row" to part(cells(AMOUNT, 1, 2, 3, 4), cells(RESERVE, 1, 2, 3, 4)),
        )
        val expected = linkedMapOf(
            "the group, any row" to listOf("CTRL|OMISSION_ERROR|$AT", "GT|OMISSION_ERROR|$AT"),
            "the group, every declared row" to listOf("GT|OMISSION_ERROR|$AT"),
            "the group, the two filled rows" to listOf(),
            "every field of the group, any row" to listOf("CTRL|OMISSION_ERROR|$AT", "GT|OMISSION_ERROR|$AT"),
            "every field but one the term never reads, any row" to listOf("CTRL|OMISSION_ERROR|$AT"),
            "both term fields, any row" to listOf("CTRL|OMISSION_ERROR|$AT"),
            "one term field, any row" to listOf("CTRL|OMISSION_ERROR|$AT"),
            "both term fields, every declared row" to listOf(),
        )
        assertEquals(expected, actual)
    }

    private fun validated(doc: Doc, op: Op) = read(doc, op).filter { it.substringBefore('|') in setOf("GT", "CTRL") }
}
