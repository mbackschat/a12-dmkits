package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM+Node) lock: RepetitionNotUnique keys by the exact STORED TOKEN for a non-Number kind
 * (SPEC-2026-07-22-10). An Enumeration key (modeled as `Kind.STRING` carrying `enumValues`/`enumCategories`)
 * compares stored tokens and IGNORES the category projection; a Date key compares stored text. The JVM
 * tri-differential `RepetitionNotUniqueKeyKindDiffTest` anchors these to both kernel strategies.
 */
class RepetitionNotUniqueKeyKindTest {

    private fun model(key: String) = ModelDef(
        fields = listOf(
            FieldDef(SKU, Kind.STRING),
            FieldDef(
                BAND, Kind.STRING,
                enumValues = listOf("STANDARD", "EXPRESS", "OVERNIGHT"),
                enumCategories = mapOf("Segment" to listOf("NORMAL", "FAST", "FAST")),
            ),
            FieldDef(SHIP_DATE, Kind.DATE),
        ),
        rules = listOf(RuleDef("/Catalog/RU", "/Catalog", "RepetitionNotUnique([/Catalog/Lines/$key])", "RU", "../Lines/$key")),
        repeatableGroups = setOf("/Catalog/Lines"),
        declaredReps = mapOf("/Catalog/Lines" to 50),
        indexFields = setOf(SKU),
    )

    private fun fireCount(key: String, rows: List<Pair<String, String>>): Int {
        val model = model(key)
        val doc = model.newDocument().group("/Catalog", 1)
        rows.forEachIndexed { i, (band, date) ->
            val row = i + 1
            doc.group("/Catalog/Lines", 1, row).field(SKU, "K$row", 1, row, 1)
                .field(BAND, band, 1, row, 1).field(SHIP_DATE, date, 1, row, 1)
        }
        return DmInterpreter(model).validateFull(doc).count { it.code == "RU" }
    }

    @Test
    fun enumerationKeyComparesStoredTokenNotCategory() {
        assertEquals(2, fireCount("Band", listOf("EXPRESS" to "2024-01-01", "EXPRESS" to "2024-01-02")),
            "two rows with the same stored enum token duplicate")
        assertEquals(0, fireCount("Band", listOf("EXPRESS" to "2024-01-01", "OVERNIGHT" to "2024-01-02")),
            "different tokens sharing one category do not duplicate — RNU keys the token, not the category")
    }

    @Test
    fun temporalKeyComparesStoredText() {
        assertEquals(2, fireCount("ShipDate", listOf("STANDARD" to "2024-01-01", "EXPRESS" to "2024-01-01")),
            "identical stored dates duplicate")
        assertEquals(0, fireCount("ShipDate", listOf("STANDARD" to "2024-01-01", "EXPRESS" to "2024-01-02")),
            "different stored dates do not duplicate")
    }

    private companion object {
        const val SKU = "/Catalog/Lines/Sku"
        const val BAND = "/Catalog/Lines/Band"
        const val SHIP_DATE = "/Catalog/Lines/ShipDate"
    }
}
