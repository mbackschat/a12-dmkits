package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) twin of `adapter.laws.ReferenceDrivenIterationScopeDiffTest` — ordinary rule
 * iteration derives from the CONDITION's references (SPEC-2026-07-24-01; KERNEL-FINDINGS §9): compatible
 * NESTED references select the DEEPEST repeatable path, one evaluation per ACTUAL inner row with the outer
 * operand resolving against the current row's own parent coordinates, and zero rows evaluate zero times
 * while an existing row with an empty target cell evaluates exactly once.
 */
class ReferenceDrivenIterationScopeTest {

    private val WEIGHT = "/Order/Lines/Weight"
    private val QTY = "/Order/Lines/Items/Qty"
    private val NOTE = "/Order/Lines/Items/Note"

    private val def = ModelDef(
        fields = listOf(
            FieldDef(WEIGHT, Kind.NUMBER), FieldDef(QTY, Kind.NUMBER), FieldDef(NOTE, Kind.STRING)),
        rules = listOf(
            RuleDef("/Order/Lines/Items/QtyOverWeight", "/Order/Lines/Items",
                "[Qty] > [../Weight]", "OVER", "../Qty"),
            RuleDef("/Order/Lines/Items/QtyMissing", "/Order/Lines/Items",
                "FieldFilled(Note) And FieldNotFilled(Qty)", "EMPTYQ", "../Qty")),
        computations = emptyList(),
        repeatableGroups = setOf("/Order/Lines", "/Order/Lines/Items"),
    )

    /** One line: (weight, item rows) — each item row (qty?, note?), null = leave that cell empty. */
    private fun fired(code: String, lines: List<Pair<Int?, List<Pair<Int?, String?>>>>): List<String> {
        val doc = def.newDocument().group("/Order", 1)
        lines.forEachIndexed { li0, (weight, items) ->
            val li = li0 + 1
            doc.group("/Order/Lines", 1, li)
            weight?.let { doc.field(WEIGHT, "$it", 1, li, 1) }
            items.forEachIndexed { ii0, (qty, note) ->
                val ii = ii0 + 1
                doc.group("/Order/Lines/Items", 1, li, ii)
                qty?.let { doc.field(QTY, "$it", 1, li, ii, 1) }
                note?.let { doc.field(NOTE, it, 1, li, ii, 1) }
            }
        }
        return DmInterpreter(def).validateFull(doc).filter { it.code == code }.map { it.errorPath }.sorted()
    }

    private fun at(li: Int, ii: Int) = "/Order[1]/Lines[$li]/Items[$ii]/Qty"

    @Test fun nestedReferencesIterateTheDeepestPathResolvingTheOuterOperandPerParent() = assertEquals(
        listOf(at(1, 2), at(2, 1)),
        fired("OVER", listOf(10 to listOf(5 to null, 20 to null), 50 to listOf(60 to null, 30 to null))),
        "Lines[2]/Items[2] (30) exceeds Lines[1]'s weight but not its OWN line's — must not fire")

    @Test fun anOuterRowWithZeroInnerRowsContributesNoEvaluations() = assertEquals(
        listOf(at(2, 1)),
        fired("OVER", listOf(1 to emptyList(), 1 to listOf(5 to null))))

    @Test fun anExistingRowWithAnEmptyTargetCellEvaluatesExactlyOnce() = assertEquals(
        listOf(at(1, 1)),
        fired("EMPTYQ", listOf(7 to listOf(null to "fragile"))))

    @Test fun zeroRowsEvaluateZeroTimes() = assertEquals(
        emptyList(),
        fired("EMPTYQ", listOf(7 to emptyList())))
}
