package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM + JS lock of the interpreter's term sum (`SumOfTerms`, KERNEL-FINDINGS KF670), so the JavaScript target is
 * held to the readings the kernel differential `adapter.laws.SumOfTermsRuntimeLawsTest` measured on the JVM.
 *
 * Each row reuses one of that differential's documents on a two-row capacity: the separating rows are the full
 * capacity (VALUE rather than OMISSION), the invalid term (a division by zero counts as an empty row) and the
 * malformed read cell (no rule decides).
 */
class TermSumEvaluationTest {

    private val model = ModelDef(
        fields = listOf(FieldDef(AMOUNT, Kind.NUMBER), FieldDef(RESERVE, Kind.NUMBER), FieldDef(HOLDER, Kind.STRING)),
        rules = listOf(
            RuleDef("$ROOT/Gt", ROOT, "FieldFilled(Holder) And SumOfTerms(Claims*, [Claims/Amount] + [Claims/Reserve]) > 30",
                "GT", "../Holder"),
            RuleDef("$ROOT/Div", ROOT, "FieldFilled(Holder) And SumOfTerms(Claims*, [Claims/Amount] / [Claims/Reserve]) > 1",
                "DIV", "../Holder"),
        ),
        declaredReps = mapOf(CLAIMS to 2),
    )

    private fun messages(vararg rows: Pair<String, String>): List<String> {
        var doc = model.newDocument().group(ROOT, 1).field(HOLDER, "Ada", 1, 1)
        rows.forEachIndexed { i, (amount, reserve) ->
            doc = doc.group(CLAIMS, 1, i + 1).field(AMOUNT, amount, 1, i + 1, 1).field(RESERVE, reserve, 1, i + 1, 1)
        }
        val report = DmInterpreter(model).validate(doc)
        assertEquals(listOf(), report.unsupported, "every rule evaluates")
        // The malformed row also draws the field's own format message, which is not the term sum's to report.
        return report.messages.filter { it.code in setOf("GT", "DIV") }.map { "${it.code}|${it.type.name}" }.sorted()
    }

    @Test
    fun aTermSumBelowCapacityIsAnOmission() =
        assertEquals(listOf("DIV|OMISSION", "GT|OMISSION"), messages("30" to "10"))

    @Test
    fun aTermSumOverTheWholeCapacityIsAValueError() =
        assertEquals(listOf("DIV|VALUE", "GT|VALUE"), messages("30" to "10", "30" to "10"))

    @Test
    fun aRowDividingByZeroCountsAsAnEmptyRow() =
        assertEquals(listOf("DIV|OMISSION", "GT|VALUE"), messages("30" to "10", "30" to "0"))

    @Test
    fun aMalformedReadCellLeavesNoRuleDecided() =
        assertEquals(listOf(), messages("30" to "10", "abc" to "20"))

    private companion object {
        const val ROOT = "/Policy"
        const val CLAIMS = "/Policy/Claims"
        const val AMOUNT = "/Policy/Claims/Amount"
        const val RESERVE = "/Policy/Claims/Reserve"
        const val HOLDER = "/Policy/Holder"
    }
}
