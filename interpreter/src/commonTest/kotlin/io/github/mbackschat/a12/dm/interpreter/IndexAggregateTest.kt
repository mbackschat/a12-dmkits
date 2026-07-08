package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF86: an aggregate / count over a starred INDEX field with a NOT-CHECK-RELEVANT
 * cell (a mandatory-EMPTY cell or a DUPLICATED value — the `uniqueIndex` error) is NON-EVALUABLE → the rule is
 * SUPPRESSED (the value aggregates + NumberOfFilledFields), and a fill quantifier skips it (`AtLeastOneFieldFilled`
 * stops firing when EVERY cell is not-check-relevant). The kernel differential (both strategies) is
 * `adapter.laws.IndexAggregateDiffTest`.
 */
class IndexAggregateTest {

    // /Order/Lines* with an index field, plus a /Order/Customer field a rule references.
    private fun model(cond: String, indexField: String, kind: Kind) = ModelDef(
        fields = listOf(FieldDef("/Order/Customer", Kind.STRING), FieldDef("/Order/Lines/$indexField", kind)),
        rules = listOf(RuleDef("/Order/R", "/Order", "FieldFilled(Customer) And ($cond)", CODE, "../Customer")),
        repeatableGroups = setOf("/Order/Lines"),
        declaredReps = mapOf("/Order/Lines" to 20),
        indexFields = setOf("/Order/Lines/$indexField"),
    )

    private fun doc(m: ModelDef, indexField: String, cells: List<String?>): Document =
        m.newDocument().field("/Order/Customer", "Acme", 1, 1).also { d ->
            cells.forEachIndexed { i, v ->
                val r = i + 1
                d.group("/Order/Lines", 1, r)
                if (v == null) d.emptyField("/Order/Lines/$indexField", 1, r, 1)
                else d.field("/Order/Lines/$indexField", v, 1, r, 1)
            }
        }

    private fun fires(cond: String, indexField: String, kind: Kind, cells: List<String?>): Boolean {
        val m = model(cond, indexField, kind)
        return DmInterpreter(m).validateFull(doc(m, indexField, cells)).any { it.code == CODE }
    }

    // value aggregates + count over a STRING index → suppressed on a not-check-relevant cell, fire on a clean one
    @Test fun ndvCleanFires() = assertEquals(true, fires("NumberOfDifferentValues(Lines*/Sku) >= 0", "Sku", Kind.STRING, listOf("A", "B")))
    @Test fun ndvDuplicateSuppressed() = assertEquals(false, fires("NumberOfDifferentValues(Lines*/Sku) >= 0", "Sku", Kind.STRING, listOf("A", "A")))
    @Test fun ndvEmptySuppressed() = assertEquals(false, fires("NumberOfDifferentValues(Lines*/Sku) >= 0", "Sku", Kind.STRING, listOf(null, "A")))
    @Test fun nffDuplicateSuppressed() = assertEquals(false, fires("NumberOfFilledFields(Lines*/Sku) >= 0", "Sku", Kind.STRING, listOf("A", "A")))
    @Test fun nffEmptySuppressed() = assertEquals(false, fires("NumberOfFilledFields(Lines*/Sku) >= 0", "Sku", Kind.STRING, listOf(null, "A")))

    // numeric value aggregates over a NUMBER index (share the same non-evaluable path)
    @Test fun sumCleanFires() = assertEquals(true, fires("Sum(Lines*/LineNo) >= 0", "LineNo", Kind.NUMBER, listOf("1", "2")))
    @Test fun sumDuplicateSuppressed() = assertEquals(false, fires("Sum(Lines*/LineNo) >= 0", "LineNo", Kind.NUMBER, listOf("5", "5")))
    @Test fun sumEmptySuppressed() = assertEquals(false, fires("Sum(Lines*/LineNo) >= 0", "LineNo", Kind.NUMBER, listOf(null, "7")))
    @Test fun minValueEmptySuppressed() = assertEquals(false, fires("MinValue(Lines*/LineNo) >= 0", "LineNo", Kind.NUMBER, listOf(null, "7")))
    @Test fun maxValueDuplicateSuppressed() = assertEquals(false, fires("MaxValue(Lines*/LineNo) >= 0", "LineNo", Kind.NUMBER, listOf("5", "5")))

    // fill quantifier: skip a not-check-relevant cell
    @Test fun atLeastOneCleanFires() = assertEquals(true, fires("AtLeastOneFieldFilled(Lines*/Sku)", "Sku", Kind.STRING, listOf("A", "B")))
    @Test fun atLeastOneFullyDuplicatedNoFire() = assertEquals(false, fires("AtLeastOneFieldFilled(Lines*/Sku)", "Sku", Kind.STRING, listOf("A", "A")))
    @Test fun atLeastOneEmptyPlusPresentFires() = assertEquals(true, fires("AtLeastOneFieldFilled(Lines*/Sku)", "Sku", Kind.STRING, listOf(null, "A")))
    @Test fun atLeastOneAllEmptyNoFire() = assertEquals(false, fires("AtLeastOneFieldFilled(Lines*/Sku)", "Sku", Kind.STRING, listOf(null, null)))
}

private const val CODE = "IDX"
