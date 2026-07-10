package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF108 — a semantic-index COMPUTE read whose MATCHED cell is FORMALLY
 * INVALID poisons the computation (the indexed analog of the direct-field read rule IF85), through BOTH read
 * sites: the direct operand and the `FieldValueAsString(ref For key)` coercion (which also inherits IF107's
 * invalid-COLUMN marking — the two sites share one guarded read). Without the poison, a concat swallowed the
 * invalid cell as `""`. A matched-but-EMPTY cell stays the not-given read (a numeric direct read fills 0; the
 * FieldValueAsString copy computes nothing — IF109). Kernel-verified by the tri-check differential
 * `adapter.laws.SemanticIndexMatchedInvalidCellDiffTest`.
 */
class SemanticIndexMatchedInvalidCellTest {

    private val KEY = "/Shop/LookupKey"
    private val INFO = "/Shop/Info"
    private val RESULT = "/Shop/Result"
    private val PRICE_TEXT = "/Shop/PriceText"
    private val ITEM_NO = "/Shop/Items/ItemNo"
    private val PRICE = "/Shop/Items/Price"
    private val LABEL = "/Shop/Items/Label"

    private val def = ModelDef(
        fields = listOf(
            FieldDef(KEY, Kind.NUMBER, scale = 0), FieldDef(INFO, Kind.STRING),
            FieldDef(RESULT, Kind.NUMBER, scale = 2, minFractionalDigits = 2), FieldDef(PRICE_TEXT, Kind.STRING),
            FieldDef(ITEM_NO, Kind.NUMBER, scale = 0), FieldDef(PRICE, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
            FieldDef(LABEL, Kind.STRING, maxLength = 5),
        ),
        rules = emptyList(),
        computations = listOf(
            ComputationDef(INFO, "/Shop",
                "\"P \" + [Items/Label For LookupKey] + \" Q \" + FieldValueAsString(Items/Price For LookupKey) + \" R\""),
            ComputationDef(RESULT, "/Shop", "[Items/Price For LookupKey]"),
            ComputationDef(PRICE_TEXT, "/Shop", "FieldValueAsString(Items/Price For LookupKey)"),
        ),
        repeatableGroups = setOf("/Shop/Items"),
        indexFields = setOf(ITEM_NO),
    )

    /** [rows]: (itemNo?, price?, label?) per row → the (Info, Result, PriceText) outcomes. */
    private fun outcomes(key: String, rows: List<Triple<String?, String?, String?>>): Triple<String, String, String> {
        val doc = def.newDocument().group("/Shop", 1).field(KEY, key, 1, 1)
            .field(INFO, "SEED", 1, 1).field(RESULT, "999.99", 1, 1).field(PRICE_TEXT, "SEEDTEXT", 1, 1)
        rows.forEachIndexed { i, (no, price, label) ->
            doc.group("/Shop/Items", 1, i + 1)
            no?.let { doc.field(ITEM_NO, it, 1, i + 1, 1) }
            price?.let { doc.field(PRICE, it, 1, i + 1, 1) }
            label?.let { doc.field(LABEL, it, 1, i + 1, 1) }
        }
        val computed = DmInterpreter(def).compute(doc)
        fun at(path: String) = computed.first { it.path == path }.let { c ->
            when (c.outcome) {
                ComputeOutcome.VALUE -> "VALUE|${c.value}"
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.ERRORED -> "ERRORED"
            }
        }
        return Triple(at(INFO), at(RESULT), at(PRICE_TEXT))
    }

    @Test fun matchedValidComputes() =
        assertEquals(Triple("VALUE|P AB Q 5.25 R", "VALUE|5.25", "VALUE|5.25"),
            outcomes("12", listOf(Triple("12", "5.25", "AB"))))

    @Test fun fieldValueAsStringOverAMatchedInvalidCellClears() =
        assertEquals(Triple("CLEARED", "CLEARED", "CLEARED"),
            outcomes("12", listOf(Triple("12", "1.234", "AB"))))

    @Test fun aDirectIndexedStringOperandMatchedInvalidClears() =
        assertEquals(Triple("CLEARED", "VALUE|5.25", "VALUE|5.25"),
            outcomes("12", listOf(Triple("12", "5.25", "TOOLONG"))))

    @Test fun anInvalidTargetCellInAnUnmatchedRowDoesNotPoison() =
        assertEquals(Triple("VALUE|P AB Q 5.25 R", "VALUE|5.25", "VALUE|5.25"),
            outcomes("12", listOf(Triple("12", "5.25", "AB"), Triple("7", "1.234", "XYZ"))))

    @Test fun anInvalidIndexColumnPoisonsTheFieldValueAsStringReadToo() =
        assertEquals(Triple("CLEARED", "CLEARED", "CLEARED"),
            outcomes("12", listOf(Triple("12", "5.25", "AB"), Triple("9999999999999999", "0.01", "CD"))))

    @Test fun matchedEmptyCellsConcatAsBlank() =
        assertEquals(Triple("VALUE|P  Q  R", "VALUE|0.00", "CLEARED"),
            outcomes("12", listOf(Triple("12", null, null))))

    @Test fun noMatchOverACleanColumnConcatsAsBlank() =
        assertEquals(Triple("VALUE|P  Q  R", "VALUE|0.00", "CLEARED"),
            outcomes("274", listOf(Triple("12", "5.25", "AB"))))
}
