package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.PlacementKind
import io.github.mbackschat.a12.dm.interpreter.conformance.RelevantPointer
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * KERNEL-FINDINGS KF670 — the admitted `SumOfTerms` shapes [SumOfTermsRuntimeLawsTest] does not evaluate: a count
 * inside the term, an aggregate or a non-repeatable read in it, a `$` read in the iteration filter, an error field
 * inside the iteration group, the per-row form reaching its capacity, and the erroneous-row and filter-verdict
 * edges.
 *
 * Every document is observed on dynamic Groovy, generated Java and the interpreter, and the three observations —
 * messages with their referenced and fill-to-fix pointers, computation outcomes — are asserted equal before any
 * expectation is read; the expectations then name what the Groovy anchor reported.
 */
class SumOfTermsShapesRuntimeLawsTest {

    private companion object {
        const val HOLDER = "/Policy/Holder"
        const val CLAIMS = "/Policy/Claims"
        const val AMOUNT = "/Policy/Claims/Amount"
        const val RESERVE = "/Policy/Claims/Reserve"
        const val DEDUCTIBLE = "/Policy/Claims/Deductible"

        /** Rules at `/Policy` with error field `Holder`. */
        val RULES = linkedMapOf(
            // A count on the small side and its count-free control, guarded alike: on an empty root, a rule A12
            // files as always evaluated runs and the other is gated out with its root (KF507).
            "CNT" to "FieldNotFilled(Holder) And SumOfTerms(Claims*, [Claims/Amount] + NumberOfFilledFields(Vehicles*/Value)) < 2",
            "PLAIN" to "FieldNotFilled(Holder) And SumOfTerms(Claims*, [Claims/Amount]) < 2",
            "CNTF" to "FieldNotFilled(Holder) And " +
                "SumOfTerms(Claims* Having NumberOfFilledFields(Vehicles*/Value) < [Claims/Amount], [Claims/Amount]) < 2",
            "UP" to "FieldFilled(Holder) And SumOfTerms(Claims*, [Claims/Amount] + NumberOfFilledFields(Vehicles*/Value)) > 100",
            "AGG" to "FieldFilled(Holder) And SumOfTerms(Claims*, Sum(Claims/Parts*/Qty)) > 8",
            "ZIP" to "FieldFilled(Holder) And SumOfTerms(Claims*, [Claims/Amount] + [Address/Zip]) > 150",
            // Each value rule's bar sits between the sum it predicts and the rival's, so a row cannot pass by both.
            "OUT" to "FieldFilled(Holder) And SumOfTerms(Claims* Having [Claims/Amount] > [\$Threshold], [Claims/Amount]) > 175",
            "OUTX" to "FieldFilled(Holder) And SumOfTerms(Claims* Having [Claims/Amount] > [\$Threshold], [Claims/Amount]) > 185",
            "DED" to "FieldFilled(Holder) And SumOfTerms(Claims* Having [Claims/Deductible] > 0, [Claims/Amount]) > 100",
            "DEDK" to "FieldFilled(Holder) And SumOfTerms(Claims* Having [Claims/Deductible] > 0, [Claims/Amount]) > 155",
            "GT" to "FieldFilled(Holder) And SumOfTerms(Claims*, [Claims/Amount] + [Claims/Reserve]) > 100",
        )
    }

    private val model: String = run {
        val base = DocumentModelDraft.named("policy-sum-of-terms-shapes").locale("en_US")
            .rootGroup("Policy") { p ->
                p.stringField("Holder") { }
                    .numberField("Total") { it.fractionDigits(2) }
                    .numberField("Threshold") { it.fractionDigits(2) }
                    .group("Address") { it.numberField("Zip") { z -> z.fractionDigits(0) } }
                    .group("Vehicles") { it.repeatable(2).numberField("Value") { v -> v.fractionDigits(0) } }
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
            RuntimeLaws.withRule(json, "/Policy", code, "../Holder", code, condition)
        }
        val inRow = RuntimeLaws.withRule(ruled, "/Policy", "INROW", "../Claims/Status", "INROW",
            "SumOfTerms(Claims*, [Claims/Amount]) > 100")
        val perRow = RuntimeLaws.withRule(inRow, "/Policy", "ROW", "../Claims/Amount", "ROW",
            "FieldFilled(Claims/Amount) And SumOfTerms(Claims/Parts*, [Claims/Parts/Qty]) > 5")
        RuntimeLaws.withComputation(perRow, "/Policy", "TotalComp", "../Total",
            "SumOfTerms(Claims*, [Claims/Amount] + [Claims/Reserve])")
    }

    private val documents = linkedMapOf(
        "an empty root" to """{"Policy":{}}""",
        "claim 1 holding three parts of qty 2" to
            """{"Policy":{"Holder":"Ada","Claims":[{"Amount":1,"Parts":[{"Qty":2},{"Qty":2},{"Qty":2}]}]}}""",
        "parts in two claims" to """{"Policy":{"Holder":"Ada","Claims":[""" +
            """{"Amount":1,"Parts":[{"Qty":4},{"Qty":3}]},{"Amount":1,"Parts":[{"Qty":4}]}]}}""",
        "a zip beside two claims" to
            """{"Policy":{"Holder":"Ada","Address":{"Zip":50},"Claims":[{"Amount":30},{"Amount":40}]}}""",
        "two claims over a threshold of 20" to
            """{"Policy":{"Holder":"Ada","Threshold":20,"Claims":[{"Amount":30},{"Amount":150},{"Amount":10}]}}""",
        "an open claim over the bar beside a small one" to
            """{"Policy":{"Holder":"Ada","Claims":[{"Status":"open","Amount":150},{"Status":"x","Amount":10}]}}""",
        "a row holding only a malformed deductible" to
            """{"Policy":{"Holder":"Ada","Claims":[{"Amount":120,"Reserve":10},{"Deductible":"abc"}]}}""",
        "a filter reading a malformed deductible" to
            """{"Policy":{"Holder":"Ada","Claims":[{"Amount":150,"Deductible":1},{"Amount":10,"Deductible":"abc"}]}}""",
        "a malformed amount, no stored total" to
            """{"Policy":{"Holder":"Ada","Claims":[{"Amount":30,"Reserve":10},{"Amount":"abc","Reserve":20}]}}""",
        "one vehicle beside a large claim" to
            """{"Policy":{"Holder":"Ada","Vehicles":[{"Value":7}],"Claims":[{"Amount":150}]}}""",
        "five claims, one beyond the capacity of four" to """{"Policy":{"Holder":"Ada","Claims":[""" +
            (1..5).joinToString(",") { """{"Amount":30,"Reserve":0}""" } + "]}}",
    )

    private val groovy: Map<String, DocumentObservation> by lazy {
        documents.mapValues { (_, json) -> DocumentDifferential.kernel(model, json, dynamic = true) }
    }

    private fun short(pointers: List<String>) = pointers.map { it.removePrefix("/Policy[1]/") }

    @Test
    fun theModelIsAdmitted() = assertEquals(listOf<String>(), RuntimeLaws.consistencyMessages(model))

    @Test
    fun theThreeEnginesObserveEveryDocumentAlike() {
        assertEquals(groovy, documents.mapValues { (_, json) -> DocumentDifferential.kernel(model, json, dynamic = false) },
            "generated Java parts from Groovy")
        assertEquals(groovy, documents.mapValues { (_, json) -> DocumentDifferential.interpreter(model, json) },
            "the interpreter parts from Groovy")
    }

    @Test
    fun whatGroovyReports() {
        val actual = groovy.mapValues { (_, observation) ->
            observation.messages.map { "${it.code}|${it.type}|${short(listOf(it.pointer)).single()}|" +
                "${short(it.referenced)}|${short(it.fillToFix)}" } + observation.outcomes.map { "${it.key}=${it.value}" }
        }
        val expected = linkedMapOf(
            "an empty root" to listOf(
                "CNT|OMISSION_ERROR|Holder|[Claims[0]/Amount, Holder, Vehicles[0]/Value]|[Claims[0]/Amount, Holder, Vehicles[0]/Value]",
                "Total=VALUE|0",
            ),
            "claim 1 holding three parts of qty 2" to listOf(
                "ROW|VALUE_ERROR|Claims[1]/Amount|[Claims[1]/Amount, Claims[1]/Parts[0]/Qty]|[]",
                "Total=VALUE|1",
            ),
            "parts in two claims" to listOf(
                "AGG|OMISSION_ERROR|Holder|[Claims[0]/Parts[0]/Qty, Holder]|[Claims[0]/Parts[0]/Qty]",
                "ROW|OMISSION_ERROR|Claims[1]/Amount|[Claims[1]/Amount, Claims[1]/Parts[0]/Qty]|[Claims[1]/Parts[0]/Qty]",
                "Total=VALUE|2",
            ),
            "a zip beside two claims" to listOf(
                "ZIP|OMISSION_ERROR|Holder|[Address[1]/Zip, Claims[0]/Amount, Holder]|[Address[1]/Zip, Claims[0]/Amount]",
                "Total=VALUE|70",
            ),
            "two claims over a threshold of 20" to listOf(
                "GT|OMISSION_ERROR|Holder|[Claims[0]/Amount, Claims[0]/Reserve, Holder]|[Claims[0]/Amount, Claims[0]/Reserve]",
                "INROW|OMISSION_ERROR|Claims[1]/Status|[Claims[0]/Amount]|[Claims[0]/Amount]",
                "OUT|OMISSION_ERROR|Holder|[Claims[0]/Amount, Holder, Threshold]|[Claims[0]/Amount, Threshold]",
                "UP|OMISSION_ERROR|Holder|[Claims[0]/Amount, Holder, Vehicles[0]/Value]|[]",
                "ZIP|OMISSION_ERROR|Holder|[Address[1]/Zip, Claims[0]/Amount, Holder]|[Address[1]/Zip, Claims[0]/Amount]",
                "Total=VALUE|190",
            ),
            "an open claim over the bar beside a small one" to listOf(
                "GT|OMISSION_ERROR|Holder|[Claims[0]/Amount, Claims[0]/Reserve, Holder]|[Claims[0]/Amount, Claims[0]/Reserve]",
                "INROW|OMISSION_ERROR|Claims[1]/Status|[Claims[0]/Amount]|[Claims[0]/Amount]",
                "UP|OMISSION_ERROR|Holder|[Claims[0]/Amount, Holder, Vehicles[0]/Value]|[]",
                "ZIP|OMISSION_ERROR|Holder|[Address[1]/Zip, Claims[0]/Amount, Holder]|[Address[1]/Zip, Claims[0]/Amount]",
                "Total=VALUE|160",
            ),
            "a row holding only a malformed deductible" to listOf(
                "GT|OMISSION_ERROR|Holder|[Claims[0]/Amount, Claims[0]/Reserve, Holder]|[Claims[0]/Amount, Claims[0]/Reserve]",
                "INROW|OMISSION_ERROR|Claims[1]/Status|[Claims[0]/Amount]|[Claims[0]/Amount]",
                "UP|OMISSION_ERROR|Holder|[Claims[0]/Amount, Holder, Vehicles[0]/Value]|[]",
                "zahlHatUngueltigeZeichen|VALUE_ERROR|Claims[2]/Deductible|[Claims[2]/Deductible]|[]",
                "Total=VALUE|130",
            ),
            "a filter reading a malformed deductible" to listOf(
                "DED|OMISSION_ERROR|Holder|[Claims[0]/Amount, Claims[0]/Deductible, Holder]|[Claims[0]/Amount, Claims[0]/Deductible]",
                "GT|OMISSION_ERROR|Holder|[Claims[0]/Amount, Claims[0]/Reserve, Holder]|[Claims[0]/Amount, Claims[0]/Reserve]",
                "INROW|OMISSION_ERROR|Claims[1]/Status|[Claims[0]/Amount]|[Claims[0]/Amount]",
                "UP|OMISSION_ERROR|Holder|[Claims[0]/Amount, Holder, Vehicles[0]/Value]|[]",
                "ZIP|OMISSION_ERROR|Holder|[Address[1]/Zip, Claims[0]/Amount, Holder]|[Address[1]/Zip, Claims[0]/Amount]",
                "zahlHatUngueltigeZeichen|VALUE_ERROR|Claims[2]/Deductible|[Claims[2]/Deductible]|[]",
                "Total=VALUE|160",
            ),
            "a malformed amount, no stored total" to listOf(
                "zahlHatUngueltigeZeichen|VALUE_ERROR|Claims[2]/Amount|[Claims[2]/Amount]|[]",
            ),
            "one vehicle beside a large claim" to listOf(
                "GT|OMISSION_ERROR|Holder|[Claims[0]/Amount, Claims[0]/Reserve, Holder]|[Claims[0]/Amount, Claims[0]/Reserve]",
                "INROW|OMISSION_ERROR|Claims[1]/Status|[Claims[0]/Amount]|[Claims[0]/Amount]",
                "UP|OMISSION_ERROR|Holder|[Claims[0]/Amount, Holder, Vehicles[0]/Value]|[]",
                "Total=VALUE|150",
            ),
            "five claims, one beyond the capacity of four" to listOf(
                "GT|VALUE_ERROR|Holder|[Claims[0]/Amount, Claims[0]/Reserve, Holder]|[]",
                "INROW|VALUE_ERROR|Claims[1]/Status|[Claims[0]/Amount]|[]",
                "UP|VALUE_ERROR|Holder|[Claims[0]/Amount, Holder, Vehicles[0]/Value]|[]",
                "zuGrosseKontextnummer|VALUE_ERROR|Claims[5]/Amount|[Claims[5]/Amount]|[]",
                "zuGrosseKontextnummer|VALUE_ERROR|Claims[5]/Reserve|[Claims[5]/Reserve]|[]",
                "zuGrosseZeile|VALUE_ERROR|Claims|[Claims]|[]",
                "Total=VALUE|120",
            ),
        )
        assertEquals(expected, actual)
    }

    /**
     * Partial validation's error gate, on the discriminator [SumOfTermsRuntimeLawsTest] cannot build: no first row,
     * and an erroneous second one. "Row 1 by index" and "the first row held" read it differently.
     */
    @Test
    fun partialValidationWithoutAFirstRow() {
        fun doc(erroneousRow: Int?) = buildList {
            add(Placement(PlacementKind.GROUP, "/Policy", listOf(1)))
            add(Placement(PlacementKind.FIELD, HOLDER, listOf(1, 1), "Ada"))
            for (row in 2..3) {
                add(Placement(PlacementKind.GROUP, CLAIMS, listOf(1, row)))
                add(Placement(PlacementKind.FIELD, AMOUNT, listOf(1, row, 1), "80"))
                add(Placement(PlacementKind.FIELD, RESERVE, listOf(1, row, 1), "10"))
            }
            erroneousRow?.let { add(Placement(PlacementKind.FIELD, DEDUCTIBLE, listOf(1, it, 1), "abc")) }
        }
        val relevant = Op.ValidatePart(listOf(RelevantPointer(HOLDER, listOf(1, 1)), RelevantPointer(CLAIMS, listOf(1, 0))))
        fun read(placements: List<Placement>): List<String> {
            val anchor = CorpusEngines.groovyDynamic(model, placements, relevant)
            assertEquals(anchor, CorpusEngines.javaStatic(model, placements, relevant), "generated Java parts from Groovy")
            assertEquals(anchor, CorpusEngines.interpret(model, placements, relevant), "the interpreter parts from Groovy")
            return anchor.filter { it.substringBefore('|') == "GT" }
        }
        val actual = linkedMapOf(
            "rows 2 and 3, both error-free" to read(doc(null)),
            "rows 2 and 3, row 2 erroneous" to read(doc(2)),
            "rows 2 and 3, row 3 erroneous" to read(doc(3)),
        )
        // All three decide: the gate asks of the instance at index 1, which is absent here, not of the first row
        // held ([SumOfTermsRuntimeLawsTest.partialValidationNeedsAnErrorFreeFirstInstance] holds the erroneous index 1).
        val decided = listOf("GT|OMISSION_ERROR|/Policy[1]/Holder")
        assertEquals(linkedMapOf("rows 2 and 3, both error-free" to decided, "rows 2 and 3, row 2 erroneous" to decided,
            "rows 2 and 3, row 3 erroneous" to decided), actual)
    }
}
