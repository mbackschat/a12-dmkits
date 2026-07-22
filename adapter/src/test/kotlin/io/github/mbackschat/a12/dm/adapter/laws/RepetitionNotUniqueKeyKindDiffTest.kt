package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * RepetitionNotUnique keys by the exact INTERNALLY-STORED TOKEN for every non-Number kind (SPEC-2026-07-22-10):
 * an Enumeration key compares stored tokens, NOT the display/category projection, and a temporal key compares
 * stored text. Tri-differential: interpreter == dynamic-Groovy == static-Java.
 *
 * The decisive Enumeration witness: `Band` values `EXPRESS` and `OVERNIGHT` share the category `SpeedClass=FAST`
 * but are distinct tokens — RNU keys the token, so they do NOT duplicate; two `EXPRESS` rows do.
 *
 * Temporal note: fixed-width formats are 1:1 (one stored spelling per instant), so there is no
 * same-instant/different-text firing witness. The "keyed by stored text, not a parsed instant" fact rests on
 * the shared stored-token key path (source + `RepetitionNotUniqueEvaluator`) and the NUMBER-vs-text contrast in
 * `RepetitionNotUniqueExactnessDiffTest`; here we lock identical-text→duplicate, different-text→no-duplicate.
 */
class RepetitionNotUniqueKeyKindDiffTest {

    // Kernel side: an e-commerce catalog whose repeatable Lines carry an Enumeration (with a category) and a Date.
    private val kernelBase = DocumentModelDraft.named("catalog").locale("en_US")
        .rootGroup("Catalog") { root ->
            root.group("Lines") { lines ->
                lines.repeatable(50)
                    .enumField("Band") { e ->
                        e.value("STANDARD").value("EXPRESS").value("OVERNIGHT")
                            .category("Segment", "NORMAL", "FAST", "FAST")
                    }
                    .dateField("ShipDate") { d -> d.format("yyyy-MM-dd") }
                    .stringField("Sku") { }
            }
        }.build().toJson()

    // Interpreter side: the peer ModelDef (enum → Kind.STRING carrying enumValues/enumCategories).
    private val interpreterFields = listOf(
        FieldDef(SKU, Kind.STRING),
        FieldDef(
            BAND, Kind.STRING,
            enumValues = listOf("STANDARD", "EXPRESS", "OVERNIGHT"),
            enumCategories = mapOf("Segment" to listOf("NORMAL", "FAST", "FAST")),
        ),
        FieldDef(SHIP_DATE, Kind.DATE),
    )

    private fun interpreterModel(key: String) = ModelDef(
        fields = interpreterFields,
        rules = listOf(RuleDef("/Catalog/RU", "/Catalog", "RepetitionNotUnique([/Catalog/Lines/$key])", "RU", "../Lines/$key")),
        repeatableGroups = setOf("/Catalog/Lines"),
        declaredReps = mapOf("/Catalog/Lines" to 50),
        indexFields = setOf(SKU),
    )

    /** Fire count of the RNU rule on [key], asserted equal across dynamic-Groovy, static-Java, and interpreter. */
    private fun triFireCount(key: String, rows: List<Pair<String, String>>): Int {
        val kernelModel = RuntimeLaws.withRule(
            kernelBase, "/Catalog", "RU", "../Lines/$key", "RU", "RepetitionNotUnique(Lines/$key)")
        val kernelDoc = RuntimeLaws.doc().group("/Catalog", 1)
        val interpModel = interpreterModel(key)
        val interpDoc = interpModel.newDocument().group("/Catalog", 1)
        rows.forEachIndexed { i, (band, date) ->
            val row = i + 1
            kernelDoc.group("/Catalog/Lines", 1, row).field(SKU, "K$row", 1, row, 1)
                .field(BAND, band, 1, row, 1).field(SHIP_DATE, date, 1, row, 1)
            interpDoc.group("/Catalog/Lines", 1, row).field(SKU, "K$row", 1, row, 1)
                .field(BAND, band, 1, row, 1).field(SHIP_DATE, date, 1, row, 1)
        }
        val dynamic = RuntimeLaws.messages(kernelModel, kernelDoc).count { it.code() == "RU" }
        val staticEngine = RuntimeLaws.kernelJavaEngine(kernelModel)
        val static = staticEngine.messages(staticEngine.instance(kernelDoc)).count { it.code() == "RU" }
        val interp = DmInterpreter(interpModel).validateFull(interpDoc).count { it.code == "RU" }
        assertEquals(dynamic, static, "static-Java vs dynamic-Groovy ($key)")
        assertEquals(dynamic, interp, "interpreter vs dynamic-Groovy ($key)")
        return dynamic
    }

    @Test
    fun enumerationKeyComparesStoredTokenNotCategory() {
        assertEquals(
            2, triFireCount("Band", listOf("EXPRESS" to "2024-01-01", "EXPRESS" to "2024-01-02")),
            "two rows with the same stored enum token duplicate",
        )
        assertEquals(
            0, triFireCount("Band", listOf("EXPRESS" to "2024-01-01", "OVERNIGHT" to "2024-01-02")),
            "different stored tokens sharing one category do NOT duplicate — RNU keys the token, not the category",
        )
    }

    @Test
    fun temporalKeyComparesStoredText() {
        assertEquals(
            2, triFireCount("ShipDate", listOf("STANDARD" to "2024-01-01", "EXPRESS" to "2024-01-01")),
            "identical stored dates duplicate",
        )
        assertEquals(
            0, triFireCount("ShipDate", listOf("STANDARD" to "2024-01-01", "EXPRESS" to "2024-01-02")),
            "different stored dates do not duplicate",
        )
    }

    private companion object {
        const val SKU = "/Catalog/Lines/Sku"
        const val BAND = "/Catalog/Lines/Band"
        const val SHIP_DATE = "/Catalog/Lines/ShipDate"
    }
}
