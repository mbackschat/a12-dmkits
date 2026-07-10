package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF105 — a `Having`-filtered starred quantifier tallies each KEPT row's
 * cell with ROW-CORRECT invalidity: the old check resolved the cell at the ENCLOSING rule row's reps, so a
 * per-row rule whose OWN cell was formally invalid poisoned the tally of a valid kept row (the workspace
 * self-correlated period-overlap idiom fired nowhere); a filter-DROPPED row's invalid cell never suppresses
 * (the filter drops first — IF94's rule). Kernel-verified by the tri-check differential
 * `adapter.laws.SelfCorrelatedOverlapQuantifierDiffTest`.
 */
class HavingTallyRowCorrectInvalidityTest {

    private val PRICE = "/Catalog/Offers/Price"
    private val FROM = "/Catalog/Offers/From"
    private val UNTIL = "/Catalog/Offers/Until"

    private val def = ModelDef(
        fields = listOf(
            FieldDef(PRICE, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
            FieldDef(FROM, Kind.DATE), FieldDef(UNTIL, Kind.DATE),
        ),
        rules = listOf(RuleDef("/Catalog/Offers/R", "/Catalog/Offers",
            "FieldFilled(From) And AtLeastOneFieldFilled(../Offers*/Price Having " +
                "(CurrentRepetition(RuleGroup) != CurrentRepetition(\$RuleGroup) And " +
                "[From] <= [\$From] And [\$From] <= [Until]))",
            "OVERLAP", "../From")),
        computations = emptyList(),
        repeatableGroups = setOf("/Catalog/Offers"),
    )

    private fun fired(row2Price: String): List<String> {
        val doc = def.newDocument()
        doc.group("/Catalog/Offers", 1, 1).field(PRICE, "376.25", 1, 1, 1)
            .field(FROM, "2024-01-21", 1, 1, 1).field(UNTIL, "2024-11-06", 1, 1, 1)
        doc.group("/Catalog/Offers", 1, 2).field(PRICE, row2Price, 1, 2, 1)
            .field(FROM, "2024-08-28", 1, 2, 1).field(UNTIL, "2024-08-14", 1, 2, 1)
        return DmInterpreter(def).validateFull(doc).filter { it.code == "OVERLAP" }.map { it.errorPath }.sorted()
    }

    @Test fun ownRowInvalidCellDoesNotPoisonTheKeptValidRow() = assertEquals(
        listOf("/Catalog[1]/Offers[2]/From"), fired(row2Price = "404.2"),
        "row 2's own 1-frac Price is invalid but its row is filter-DROPPED (self-exclusion) — the kept valid row 1 fires the rule")

    @Test fun allValidControl() = assertEquals(listOf("/Catalog[1]/Offers[2]/From"), fired(row2Price = "404.20"))
}
