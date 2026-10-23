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
 * Multiplatform (JVM + JS) lock for IF213 / SPEC-2026-07-23-08 — semantic-index key identity is owned
 * by the DECLARED INDEX-FIELD KIND. A NUMBER index field normalizes both the stored and the requested
 * key numerically, so `5.00` and `5` select the same row in either spelling direction, for literal and
 * field-valued keys alike; a STRING index field keeps exact stored text, so the same pair stays
 * distinct. Kernel-verified by the tri-check differentials `adapter.laws.SemanticIndexComputeNoMatchDiffTest`
 * (Number keys) and `adapter.laws.SemanticIndexDiffTest` (the String separator); this suite pins the
 * shared `indexKey` projection on both interpreter hosts.
 */
class SemanticIndexNumberKeyTest {

    @Test
    fun parallelValidationRetainsRenderedAliasesWhileComputationKeepsNumericIdentity() {
        val groups = listOf("/Tariff/Offered", "/Tariff/Quoted")
        for ((leftScale, rightScale) in listOf(0 to 0, 0 to 2, 2 to 2)) {
            for (fractional in listOf(false, true)) {
                val model = ModelDef(
                    fields = groups.zip(listOf(leftScale, rightScale)).flatMap { (group, scale) ->
                        listOf(FieldDef("$group/Deductible", Kind.NUMBER, scale = 2, minFractionalDigits = scale),
                            FieldDef("$group/Premium", Kind.STRING))
                    },
                    rules = emptyList(), declaredReps = groups.associateWith { 3 },
                    indexFields = groups.map { "$it/Deductible" }.toSet(),
                )
                val document = model.newDocument()
                for ((group, scale) in groups.zip(listOf(leftScale, rightScale))) for ((i, key) in listOf("5", "2", "10").withIndex()) {
                    document.group(group, 1, i + 1)
                        .field("$group/Deductible", key + if (fractional) ".25" else if (scale == 2) ".00" else "", 1, i + 1, 1)
                        .field("$group/Premium", "p", 1, i + 1, 1)
                }
                val visits = document.validationParallelContexts(groups, "Deductible", "/Tariff", null, null, null, null, null) {
                    model.declaredReps[it] ?: 1
                }
                val expected = listOf(3, 2, 1).flatMap { row ->
                    List(if (leftScale != rightScale && !fractional) 2 else 1) { row }
                }
                for (group in groups) {
                    assertEquals(expected, visits.map { it.repsOf("$group/Premium")[1] })
                    assertEquals(List(expected.size) { io.github.mbackschat.a12.dm.interpreter.ast.Val.Txt("p") },
                        visits.map { it.field("$group/Premium") })
                }
                assertEquals(3, document.parallelContexts(groups, "Deductible", "/Tariff").size)
            }
        }
    }


    private val KEY = "/Shop/LookupKey"
    private val RESULT = "/Shop/Result"
    private val ITEM_NO = "/Shop/Items/ItemNo"
    private val ITEM_CODE = "/Shop/Items/ItemCode"
    private val PRICE = "/Shop/Items/Price"

    private fun def(indexField: String, computation: String) = ModelDef(
        fields = listOf(
            FieldDef(KEY, Kind.NUMBER, scale = 2),
            FieldDef(RESULT, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
            FieldDef(ITEM_NO, Kind.NUMBER, scale = 2),
            FieldDef(ITEM_CODE, Kind.STRING),
            FieldDef(PRICE, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
        ),
        rules = emptyList(),
        computations = listOf(ComputationDef(RESULT, "/Shop", computation)),
        repeatableGroups = setOf("/Shop/Items"),
        indexFields = setOf(indexField),
    )

    /** Fills one keyed column ([keyed] — ItemNo or ItemCode) per row plus Price, computes Result. */
    private fun outcome(def: ModelDef, keyed: String, lookupKey: String?, rows: List<Pair<String?, String?>>): String {
        val doc = def.newDocument().group("/Shop", 1).field(RESULT, "999.99", 1, 1)
        lookupKey?.let { doc.field(KEY, it, 1, 1) }
        rows.forEachIndexed { i, (k, price) ->
            doc.group("/Shop/Items", 1, i + 1)
            k?.let { doc.field(keyed, it, 1, i + 1, 1) }
            price?.let { doc.field(PRICE, it, 1, i + 1, 1) }
        }
        val c = DmInterpreter(def).compute(doc).first { it.path == RESULT }
        return when (c.outcome) {
            ComputeOutcome.VALUE -> "VALUE|${c.value}"
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED"
        }
    }

    private val numberKeyed = def(ITEM_NO, "[Items/Price For LookupKey]")

    @Test fun fieldValuedNumberKeyMatchesADifferentlySpelledStoredKey() =
        assertEquals("VALUE|5.25", outcome(numberKeyed, ITEM_NO, "5", listOf("5.00" to "5.25", "7" to "0.01")))

    @Test fun buildSideAndQuerySideNormalizeSymmetrically() =
        assertEquals("VALUE|5.25", outcome(numberKeyed, ITEM_NO, "5.00", listOf("5" to "5.25", "7" to "0.01")))

    @Test fun literalNumberKeyMatchesADifferentlySpelledStoredKey() =
        assertEquals("VALUE|5.25",
            outcome(def(ITEM_NO, """[Items/Price For "5"]"""), ITEM_NO, null, listOf("5.00" to "5.25", "7" to "0.01")))

    @Test fun stringIndexKeyKeepsExactTextSpellingsDistinct() =
        assertEquals("VALUE|0.00",
            outcome(def(ITEM_CODE, """[Items/Price For "5"]"""), ITEM_CODE, null, listOf("5.00" to "5.25", "7" to "0.01")))

    @Test fun stringIndexKeyStillMatchesExactText() =
        assertEquals("VALUE|5.25",
            outcome(def(ITEM_CODE, """[Items/Price For "5.00"]"""), ITEM_CODE, null, listOf("5.00" to "5.25", "7" to "0.01")))
}
