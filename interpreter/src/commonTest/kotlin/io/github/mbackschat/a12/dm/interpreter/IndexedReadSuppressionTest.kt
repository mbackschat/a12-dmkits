package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF115 — the VALIDATION-side not-check-relevant states of an indexed read
 * `[F For key]` (distinct from compute's whole-column poison, IF107/IF108): a match on a CLEAN key cell
 * resolves the read regardless of the other cells; a match on a DUPLICATED key cell, and a NO-match over a
 * column carrying any not-check-relevant cell (malformed, empty, duplicated), are unresolvable — the
 * referencing rule (here the implicit concat validation) is SUPPRESSED, where a clean no-match stays the
 * not-given read and FIRES with `""` parts (IF113). Kernel-verified by the differential
 * `adapter.laws.IndexedReadSuppressionDiffTest`.
 */
class IndexedReadSuppressionTest {

    private val KEY = "/Shop/LookupKey"
    private val INFO = "/Shop/Info"
    private val ITEM_NO = "/Shop/Items/ItemNo"
    private val PRICE = "/Shop/Items/Price"
    private val LABEL = "/Shop/Items/Label"

    private val def = ModelDef(
        fields = listOf(
            FieldDef(KEY, Kind.NUMBER, scale = 0), FieldDef(INFO, Kind.STRING),
            FieldDef(ITEM_NO, Kind.NUMBER, scale = 0),
            FieldDef(PRICE, Kind.NUMBER, scale = 2, minFractionalDigits = 2), FieldDef(LABEL, Kind.STRING),
        ),
        rules = emptyList(),
        computations = listOf(ComputationDef(INFO, "/Shop",
            listOf(ComputationAlternative(null,
                "\"P \" + [Items/Label For LookupKey] + \" Q \" + FieldValueAsString(Items/Price For LookupKey) + \" R\"")),
            name = "InfoComp")),
        repeatableGroups = setOf("/Shop/Items"),
        indexFields = setOf(ITEM_NO),
    )

    /** [rows]: (itemNo?, price?, label?) → the fired InfoComp codes (empty = suppressed / silent). */
    private fun infoSigs(key: String, rows: List<Triple<String?, String?, String?>>): List<String> {
        val doc = def.newDocument().group("/Shop", 1).field(KEY, key, 1, 1).field(INFO, "WRONG", 1, 1)
        rows.forEachIndexed { i, (no, price, label) ->
            doc.group("/Shop/Items", 1, i + 1)
            no?.let { doc.field(ITEM_NO, it, 1, i + 1, 1) }
            price?.let { doc.field(PRICE, it, 1, i + 1, 1) }
            label?.let { doc.field(LABEL, it, 1, i + 1, 1) }
        }
        return DmInterpreter(def).validateFull(doc).map { it.code }.filter { it == "InfoComp" }
    }

    @Test fun aCleanMatchWithAMalformedCellElsewhereFires() =
        assertEquals(listOf("InfoComp"),
            infoSigs("12", listOf(Triple("12", "999.00", "AB"), Triple("9999999999999999", "0.01", "CD"))))

    @Test fun aNoMatchOverAMalformedKeyCellSuppresses() =
        assertEquals(emptyList(),
            infoSigs("274", listOf(Triple("12", "5.25", "AB"), Triple("9999999999999999", "0.01", "CD"))))

    @Test fun aMatchedMalformedTargetCellSuppresses() =
        assertEquals(emptyList(), infoSigs("12", listOf(Triple("12", "1.234", "AB"))))

    @Test fun aNoMatchOverAnEmptyKeyCellSuppresses() =
        assertEquals(emptyList(),
            infoSigs("274", listOf(Triple("12", "5.25", "AB"), Triple(null, "0.01", "CD"))))

    @Test fun aNoMatchOverADuplicatedPairSuppresses() =
        assertEquals(emptyList(),
            infoSigs("274", listOf(Triple("7", "1.00", "AA"), Triple("7", "2.00", "BB"))))

    @Test fun aMatchOnADuplicatedKeySuppresses() =
        assertEquals(emptyList(),
            infoSigs("7", listOf(Triple("7", "1.00", "AA"), Triple("7", "2.00", "BB"))))

    @Test fun aCleanNoMatchStillFires() =
        assertEquals(listOf("InfoComp"), infoSigs("274", listOf(Triple("12", "5.25", "AB"))))

    @Test fun aCleanMatchedMismatchFires() =
        assertEquals(listOf("InfoComp"), infoSigs("12", listOf(Triple("12", "5.25", "AB"))))
}
