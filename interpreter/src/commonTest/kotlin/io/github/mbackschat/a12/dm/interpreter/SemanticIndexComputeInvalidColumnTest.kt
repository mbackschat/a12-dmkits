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
 * Multiplatform (JVM + JS) lock for IF107 — a SEMANTIC-INDEX lookup in a COMPUTATION poisons on an INVALID
 * index COLUMN (any malformed, duplicated, or empty key cell — the IndexFieldCache marking, IF96's
 * semantic-index analog), even when a clean row matches; a CLEAN column keeps the validation read's
 * semantics (no-match and matched-but-empty both read empty-as-0). Kernel-verified by the tri-check
 * differential `adapter.laws.SemanticIndexComputeNoMatchDiffTest`.
 */
class SemanticIndexComputeInvalidColumnTest {

    private val KEY = "/Shop/LookupKey"
    private val RESULT = "/Shop/Result"
    private val ITEM_NO = "/Shop/Items/ItemNo"
    private val PRICE = "/Shop/Items/Price"

    private val def = ModelDef(
        fields = listOf(
            FieldDef(KEY, Kind.NUMBER, scale = 0), FieldDef(RESULT, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
            FieldDef(ITEM_NO, Kind.NUMBER, scale = 0), FieldDef(PRICE, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
        ),
        rules = emptyList(),
        computations = listOf(ComputationDef(RESULT, "/Shop", "[Items/Price For LookupKey]")),
        repeatableGroups = setOf("/Shop/Items"),
        indexFields = setOf(ITEM_NO),
    )

    private fun outcome(key: String, rows: List<Pair<String?, String?>>): String {
        val doc = def.newDocument().group("/Shop", 1).field(KEY, key, 1, 1).field(RESULT, "999.99", 1, 1)
        rows.forEachIndexed { i, (no, price) ->
            doc.group("/Shop/Items", 1, i + 1)
            no?.let { doc.field(ITEM_NO, it, 1, i + 1, 1) }
            price?.let { doc.field(PRICE, it, 1, i + 1, 1) }
        }
        val c = DmInterpreter(def).compute(doc).first { it.path == RESULT }
        return when (c.outcome) {
            ComputeOutcome.VALUE -> "VALUE|${c.value}"
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED"
        }
    }

    @Test fun cleanColumnMatchComputes() =
        assertEquals("VALUE|5.25", outcome("12", listOf("534" to "0.01", "12" to "5.25")))

    @Test fun cleanColumnNoMatchIsEmptyAsZero() =
        assertEquals("VALUE|0.00", outcome("274", listOf("534" to "0.01", "12" to "0.02")))

    @Test fun aMalformedKeyCellPoisonsDespiteACleanMatch() =
        assertEquals("CLEARED", outcome("12", listOf("12" to "5.25", "9999999999999999" to "0.01")))

    @Test fun aDuplicatedKeyPairPoisons() =
        assertEquals("CLEARED", outcome("12", listOf("12" to "5.25", "7" to "0.01", "7" to "0.02")))

    @Test fun anEmptyKeyCellPoisons() =
        assertEquals("CLEARED", outcome("12", listOf("12" to "5.25", null to "0.01")))

    @Test fun matchedRowWithAnEmptyTargetCellIsEmptyAsZero() =
        assertEquals("VALUE|0.00", outcome("12", listOf("12" to null, "7" to "0.01")))
}
