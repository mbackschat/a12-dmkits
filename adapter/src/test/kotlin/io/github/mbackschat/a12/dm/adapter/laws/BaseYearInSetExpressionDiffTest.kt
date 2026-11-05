package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.PlacementKind
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `BaseYear` read inside a set expression: a `Having` filter of an aggregate and of `SumOfTerms`, and a
 * `SumOfTerms` term. The changelog's A12K-4204 (kernel 31.0.0, tagged TS and Validation Language) fixed
 * "evaluation errors during validation" for exactly these placements; these rows read what both Java-side kernel
 * strategies and the interpreter decide there.
 *
 * The base year is 2020. In the first document two of three claims fall in 2020 and one in 2019, so the filters
 * keep 120 and the term `BaseYear - Year` sums to exactly 1; in the second every claim is from 2018, so the filters
 * keep nothing and the term sums to 6. `BaseYear` read as empty, as zero or as any other year cannot produce that
 * split. The rules fire as omissions because three of the four declared rows are filled and the unfilled capacity
 * is unspecified ([KF670](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf670)), which the filters and the term inherit. Conditions are raw strings so the rows read the kernel's
 * own spelling. Own domain (insurance claims by year).
 */
class BaseYearInSetExpressionDiffTest {

    private companion object {
        const val HOLDER = "/Policy/Holder"
        const val CLAIMS = "/Policy/Claims"
        const val AT = "/Policy[1]/Holder"
        val RULES = linkedMapOf(
            "SUM_FILTER" to "Sum(Claims*/Amount Having ([Claims/Year] == BaseYear)) > 100",
            "TERMS_FILTER" to "SumOfTerms(Claims* Having [Claims/Year] == BaseYear, [Claims/Amount]) > 100",
            "TERMS_TERM" to "SumOfTerms(Claims*, BaseYear - [Claims/Year]) == 1",
        )
    }

    private val model: String = run {
        val base = DocumentModelDraft.named("policy-claims-by-year").locale("en_US").baseYear(2020)
            .rootGroup("Policy") { p ->
                p.stringField("Holder") { }
                    .numberField("Total") { it.fractionDigits(2) }
                    .group("Claims") { c ->
                        c.repeatable(4)
                            .numberField("Year") { it.fractionDigits(0) }
                            .numberField("Amount") { it.fractionDigits(2) }
                    }
            }.build().toJson()
        val ruled = RULES.entries.fold(base) { json, (code, condition) ->
            RuntimeLaws.withRule(json, "/Policy", code, "../Holder", code, "FieldFilled(Holder) And $condition")
        }
        RuntimeLaws.withComputation(ruled, "/Policy", "TotalThisYear", "../Total",
            "SumOfTerms(Claims* Having [Claims/Year] == BaseYear, [Claims/Amount])")
    }

    private fun document(vararg claims: Pair<String, String>): List<Placement> =
        listOf(Placement(PlacementKind.GROUP, "/Policy", listOf(1)), Placement(PlacementKind.FIELD, HOLDER, listOf(1, 1), "Ada")) +
            claims.withIndex().flatMap { (i, claim) ->
                val row = i + 1
                listOf(Placement(PlacementKind.GROUP, CLAIMS, listOf(1, row)),
                    Placement(PlacementKind.FIELD, "$CLAIMS/Year", listOf(1, row, 1), claim.first),
                    Placement(PlacementKind.FIELD, "$CLAIMS/Amount", listOf(1, row, 1), claim.second))
            }

    /** This year's claims: 80 + 40 in 2020, 60 in 2019. */
    private val thisYear = document("2020" to "80", "2019" to "60", "2020" to "40")

    /** Every claim in 2018: nothing falls in the base year, and each row's term is 2. */
    private val earlier = document("2018" to "80", "2018" to "60", "2018" to "40")

    /** Both strategies' readings and the interpreter's, asserted equal; the Groovy one returned. */
    private fun read(placements: List<Placement>, op: Op): List<String> {
        val groovy = CorpusEngines.groovyDynamic(model, placements, op)
        assertEquals(groovy, CorpusEngines.javaStatic(model, placements, op), "generated Java parts from Groovy")
        assertEquals(groovy, CorpusEngines.interpret(model, placements, op), "the interpreter parts from Groovy")
        return groovy
    }

    @Test
    fun theModelIsAdmitted() = assertEquals(listOf<String>(), RuntimeLaws.consistencyMessages(model))

    @Test
    fun baseYearIsReadInsideFiltersAndTerms() {
        fun validated(doc: List<Placement>) = read(doc, Op.ValidateFull).filter { it.substringBefore('|') in RULES.keys }
        assertEquals(
            linkedMapOf(
                "this year" to listOf("SUM_FILTER|OMISSION_ERROR|$AT", "TERMS_FILTER|OMISSION_ERROR|$AT",
                    "TERMS_TERM|OMISSION_ERROR|$AT"),
                "earlier" to listOf(),
            ),
            linkedMapOf("this year" to validated(thisYear), "earlier" to validated(earlier)),
        )
    }

    @Test
    fun baseYearIsReadInsideAComputedFilter() = assertEquals(
        linkedMapOf("this year" to listOf("/Policy[1]/Total|VALUE|120"), "earlier" to listOf("/Policy[1]/Total|VALUE|0")),
        linkedMapOf("this year" to read(thisYear, Op.Compute), "earlier" to read(earlier, Op.Compute)),
    )
}
