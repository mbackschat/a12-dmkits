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
 * Multiplatform (JVM + JS) lock for IF64: `RepetitionNotUnique` excludes a row whose KEY cell is FORMALLY
 * INVALID (kernel `isWertAngegeben == UNKNOWN`), mirroring `getWerteOrNullIfEinFeldNichtPruefungsRelevant`. UNKNOWN
 * covers EVERY not-check-relevant cause: a malformed value, a duplicate-index cell, and a required-EMPTY cell — the
 * kernel's mandatory check is a preliminary autogen rule that marks the field invalid BEFORE RNU builds its cache
 * (corrected 2026-07-07 — IF64's original "required-empty is a valid participant" claim was wrong). An OPTIONAL-empty key cell is
 * NOT invalid — it is a valid empty participant (locked by `RepetitionNotUniqueDiffTest.compositeEmptyComponentIsOmission`).
 * The kernel-differential is `adapter.laws.RepetitionNotUniqueFormalKeyDiffTest` (JVM only); this runs the SAME
 * evaluator on both targets.
 */
class RepetitionNotUniqueFormalKeyTest {

    private val sku = "/Order/Items/Sku"
    private val count = "/Order/Items/Count"

    private fun model(cond: String, errorRel: String) = ModelDef(
        fields = listOf(FieldDef(sku, Kind.STRING), FieldDef(count, Kind.NUMBER)),
        rules = listOf(RuleDef("/Order/R", "/Order", cond, "R", errorRel)),
        repeatableGroups = setOf("/Order/Items"),
        declaredReps = mapOf("/Order/Items" to 50),
        indexFields = setOf(sku),
    )

    private fun twoRows(m: ModelDef, s1: String, c1: String, s2: String, c2: String): Document =
        m.newDocument().group("/Order", 1)
            .group("/Order/Items", 1, 1).field(sku, s1, 1, 1, 1).field(count, c1, 1, 1, 1)
            .group("/Order/Items", 1, 2).field(sku, s2, 1, 2, 1).field(count, c2, 1, 2, 1)

    private fun fired(m: ModelDef, doc: Document) =
        DmInterpreter(m).validateFull(doc).filter { it.code == "R" }.map { it.errorPath }.sorted()

    @Test
    fun validDuplicateKeysFire() {
        // Control: two rows with an identical VALID Count and distinct Sku → RNU(Count) fires on both.
        val m = model("RepetitionNotUnique(Items/Count)", "../Items/Count")
        assertEquals(listOf("/Order[1]/Items[1]/Count", "/Order[1]/Items[2]/Count"),
            fired(m, twoRows(m, "A", "5", "B", "5")), "valid duplicate keys fire")
    }

    @Test
    fun malformedKeyCellsAreExcluded() {
        // Both rows' Count is malformed (a format error → UNKNOWN); distinct Sku so no uniqueIndex noise. The
        // malformed cells are excluded from the duplicate cache → RNU does not fire (not treated as duplicates).
        val m = model("RepetitionNotUnique(Items/Count)", "../Items/Count")
        assertEquals(emptyList(), fired(m, twoRows(m, "A", "1x", "B", "1x")), "malformed key cells excluded")
    }

    @Test
    fun duplicateIndexKeyCellsAreExcluded() {
        // RNU over the INDEX field with a duplicated index: the uniqueIndex error makes each Sku cell UNKNOWN →
        // excluded from the RNU cache → RNU does not fire (only the auto uniqueIndex would, a different code).
        val m = model("RepetitionNotUnique(Items/Sku)", "../Items/Sku")
        assertEquals(emptyList(), fired(m, twoRows(m, "DUP", "1", "DUP", "2")), "duplicate-index key cells excluded")
    }

    @Test
    fun requiredEmptyKeyCellIsExcluded() {
        // COMPOSITE key (Sku, Count) with Sku (the index → auto-required RELATIVE_TO_PARENT) left EMPTY on both
        // rows and Count duplicated. Sku is required-empty → the preliminary mandatory rule marks it UNKNOWN →
        // the row is dropped from the cache → RNU does NOT fire (IF64, corrected 2026-07-07). Two empty Sku cells do not collide, so
        // the only invalidation is the required-empty one.
        val m = model("RepetitionNotUnique(Items/Sku, Items/Count)", "../Items/Count")
        val doc = m.newDocument().group("/Order", 1)
            .group("/Order/Items", 1, 1).field(count, "5", 1, 1, 1)
            .group("/Order/Items", 1, 2).field(count, "5", 1, 2, 1)
        assertEquals(emptyList(), fired(m, doc), "required-empty key cell excludes the row")
    }
}
