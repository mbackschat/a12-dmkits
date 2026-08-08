package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A starred `AtLeastOne` over an INDEX field whose values duplicate. Under partial validation the duplicate
 * relation is built from the RELEVANT cells only ([IF39]), so whether the matching cell is
 * `uniqueIndex`-compromised — and therefore whether the rule runs at all — depends on whether its duplicate
 * PARTNER is in the relevant set. Both kernel strategies and the interpreter agree on every row.
 *
 * This is the class that exposed [IF266]: `IndexColumn` derived `duplicated` from every cell in the column
 * with no relevance filter, while `AutoChecks.duplicateIndexCells` — the source the `uniqueIndex` messages use
 * — was relevance-aware, and the evaluation sites all read the blind one. Rows 4 and 7 are where the two
 * readings split; the remaining five are the positive controls that keep "just never compromise anything"
 * from passing.
 */
class PartialAtLeastOneScanDiffTest {

    private val json = DocumentModelDraft.named("catalog").locale("en_US")
        .rootGroup("Order") { g ->
            g.stringField("Ack") { }
                .group("Items") { it.repeatable(50).indexField("Tag").stringField("Tag") { } }
        }.build().toJson()

    // One model and one static-Java engine for all seven shapes — see the sibling class on why a per-case
    // rebuild is not acceptable in a task that also carries timing floors.
    private val engines = mutableMapOf<String, RuntimeLaws.JavaKernelEngine>()
    private val model by lazy {
        RuntimeLaws.withRule(
            json, "/Order", "ALO", "../Items/Tag", "ALO",
            "AtLeastOneFieldValueIncludedInValueList(/Order/Items*/Tag In \"A\")",
        )
    }
    private val def by lazy { ModelLoader.load(model) }

    @Test
    fun aDuplicateIndexCellCompromisesTheRuleOnlyWhenItsPartnerIsRelevant() {
        val selections = listOf(
            "the matching row only" to listOf(Sel(TAG, listOf(1, 1, 1))),
            "a non-matching row only" to listOf(Sel(TAG, listOf(1, 2, 1))),
            "the matching row plus an unrelated group row" to
                listOf(Sel(TAG, listOf(1, 1, 1)), Sel(ITEMS, listOf(1, 3))),
            "a non-matching row plus the matching row's group" to
                listOf(Sel(TAG, listOf(1, 2, 1)), Sel(ITEMS, listOf(1, 1))),
            "group rows only, covering the match" to listOf(Sel(ITEMS, listOf(1, 1))),
            "group rows only, missing the match" to listOf(Sel(ITEMS, listOf(1, 2))),
            "everything wildcarded" to listOf(Sel(TAG, listOf(0, 0, 0))),
        )

        val readings = selections.map { (label, selection) -> "$label | ${verdicts(selection)}" }

        assertEquals(
            listOf(
                "the matching row only | $FIRES",
                "a non-matching row only | $FIRES",
                "the matching row plus an unrelated group row | $FIRES",
                "a non-matching row plus the matching row's group | $SILENT",
                "group rows only, covering the match | $FIRES",
                "group rows only, missing the match | $FIRES",
                "everything wildcarded | $SILENT",
            ),
            readings,
            "the rule is suppressed exactly when BOTH duplicate partners are relevant, because only then does " +
                "the uniqueIndex check compromise the cell the rule reports on",
        )
    }

    /** Tags: row 1 matches the value list, rows 2 and 3 do not. */
    private fun verdicts(selection: List<Sel>): String {
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(ACK, "x", 1, 1)
        val iDoc = def.newDocument().group("/Order", 1).field(ACK, "x", 1, 1)
        // Duplicated index values: rows 1 and 2 both carry "A", so BOTH are uniqueIndex-compromised
        // while still being the cells that match the value list. Row 4 is empty (mandatory-empty).
        listOf("A", "A", "B", null).forEachIndexed { idx, tag ->
            val r = idx + 1
            kDoc.group(ITEMS, 1, r)
            iDoc.group(ITEMS, 1, r)
            if (tag != null) {
                kDoc.field(TAG, tag, 1, r, 1)
                iDoc.field(TAG, tag, 1, r, 1)
            }
        }
        val kRel = selection.map { Relevant.of(it.path, *it.reps.toIntArray()) }.toSet()
        val iRel = selection.mapTo(HashSet()) { RelevantEntity(it.path, it.reps) }
        fun sig(paths: List<String>) = paths.sorted().joinToString(";").ifEmpty { "-" }
        val dynamic = sig(RuntimeLaws.messagesPart(model, kDoc, kRel).filter { it.code() == "ALO" }.map { it.field() })
        val static = sig(engines.getOrPut(model) { RuntimeLaws.kernelJavaEngine(model) }
            .messagesPart(kDoc, kRel).filter { it.code() == "ALO" }.map { it.field() })
        val interp = sig(DmInterpreter(def).validatePart(iDoc, iRel).messages
            .filter { it.code == "ALO" }.map { it.errorPath })
        return "groovy=$dynamic java=$static interp=$interp"
    }

    private data class Sel(val path: String, val reps: List<Int>)

    private companion object {
        const val ACK = "/Order/Ack"
        const val ITEMS = "/Order/Items"
        const val TAG = "/Order/Items/Tag"
        const val CELL = "/Order[1]/Items[1]/Tag"
        const val FIRES = "groovy=$CELL java=$CELL interp=$CELL"
        const val SILENT = "groovy=- java=- interp=-"
    }
}
