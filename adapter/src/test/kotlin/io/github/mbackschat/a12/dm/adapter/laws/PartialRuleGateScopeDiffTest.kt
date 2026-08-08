package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * WHICH repetition of the error field the rule-run gate compares ([IF268]). The kernel runs a rule only where
 * its error field is relevant, but the comparison is over the levels the rule's ITERATION bound. Below them no
 * coordinate was ever chosen, so the kernel defaults it to the first repetition for the message POINTER and
 * does not require the relevant set to name it.
 *
 * Three blocks, and NO TWO of them settle the law — each kills a different rival reading:
 *  - a star-only rule on `/Order` reporting into the repeatable `Items` fires at `Items[1]` when only
 *    `Items[2]`'s cell is relevant; the exact-instance reading predicts silence;
 *  - the per-row twin, `Items` now bound, still gates row by row; "the path is relevant somewhere" predicts
 *    every row fires, and `Tag row2 + Note row3` also kills "the rule's element is relevant";
 *  - the third is declared exactly like the first but its NON-STARRED references reach `/Order/Items`, so the
 *    plan iterates Items. It gates per row. That kills reading the bound level off the rule's DECLARED group —
 *    which the first two blocks cannot see, because a star-only rule's anchor coincides with its declared
 *    group and the per-row rule declares itself on what it iterates. The declared-group version shipped and an
 *    external review caught it here.
 *
 * The model memo is keyed by the whole (condition, group, error path) triple on purpose: blocks two and three
 * share a condition string and differ only in placement, so a condition-keyed memo silently tests one twice.
 */
class PartialRuleGateScopeDiffTest {

    private val json = DocumentModelDraft.named("catalog").locale("en_US")
        .rootGroup("Order") { g ->
            g.stringField("Ack") { }
                .group("Items") { it.repeatable(50).stringField("Tag") { }.stringField("Note") { } }
        }.build().toJson()

    private val engines = mutableMapOf<String, RuntimeLaws.JavaKernelEngine>()
    // Keyed by the WHOLE rule shape, not the condition: two blocks here deliberately share a condition string
    // and differ only in declared group + error path, which is the very distinction under test.
    private val models = mutableMapOf<Triple<String, String, String>, String>()
    private val defs = mutableMapOf<String, ModelDef>()

    @Test
    fun aLevelBelowTheRulesIterationIsNotComparedAtAll() {
        val readings = listOf(
            "the error field's own row is relevant" to listOf(Sel(ACK, listOf(1, 1)), Sel(TAG, listOf(1, 1, 1))),
            "only ANOTHER row's cell is relevant" to listOf(Sel(ACK, listOf(1, 1)), Sel(TAG, listOf(1, 2, 1))),
            "only another row's GROUP is relevant" to listOf(Sel(ACK, listOf(1, 1)), Sel(ITEMS, listOf(1, 2))),
            "the error path is wildcarded" to listOf(Sel(ACK, listOf(1, 1)), Sel(TAG, listOf(0, 0, 0))),
            "no Items entity at all, only the sibling Ack" to listOf(Sel(ACK, listOf(1, 1))),
            "another row's cell, and NOT the condition's own field" to listOf(Sel(TAG, listOf(1, 2, 1))),
            "nothing relevant" to listOf(),
        ).map { (label, sel) -> "$label | ${verdicts(ROOT_RULE, "/Order", "../Items/Tag", sel)}" }

        assertEquals(
            listOf(
                "the error field's own row is relevant | $ROW1",
                "only ANOTHER row's cell is relevant | $ROW1",
                "only another row's GROUP is relevant | $ROW1",
                "the error path is wildcarded | $ROW1",
                "no Items entity at all, only the sibling Ack | $SILENT",
                "another row's cell, and NOT the condition's own field | $ROW1",
                "nothing relevant | $SILENT",
            ),
            readings,
            "the Items level is BELOW the rule's `/Order` element, so any Items row's Tag admits the rule and " +
                "it reports at the default first repetition — but the error PATH must still be relevant " +
                "somewhere, which is what keeps the Ack-only row silent",
        )
    }

    @Test
    fun aLevelTheIterationBindsIsComparedExactly() {
        val readings = listOf(
            "one row's cell" to listOf(Sel(TAG, listOf(1, 2, 1))),
            "one row's SIBLING field only" to listOf(Sel(NOTE, listOf(1, 2, 1))),
            "row 2's cell plus row 3's sibling field" to
                listOf(Sel(TAG, listOf(1, 2, 1)), Sel(NOTE, listOf(1, 3, 1))),
            "one row's group" to listOf(Sel(ITEMS, listOf(1, 2))),
            "the sibling Ack only" to listOf(Sel(ACK, listOf(1, 1))),
            "the error path is wildcarded" to listOf(Sel(TAG, listOf(0, 0, 0))),
        ).map { (label, sel) -> "$label | ${verdicts(REF_DRIVEN_RULE, "/Order/Items", "../Tag", sel)}" }

        assertEquals(
            listOf(
                "one row's cell | $ROW2",
                "one row's SIBLING field only | $SILENT",
                "row 2's cell plus row 3's sibling field | $ROW2",
                "one row's group | $ROW2",
                "the sibling Ack only | $SILENT",
                "the error path is wildcarded | $EVERY_ROW",
            ),
            readings,
            "here the iteration BINDS the Items level, so each row is gated on its own cell: row 3 stays " +
                "silent even though its Note makes the row's element relevant and Tag is relevant at row 2",
        )
    }

    /**
     * The case an external review supplied, and the one the two blocks above could NOT separate: the rule is
     * DECLARED on `/Order` like block A, but its references are non-starred and reach `/Order/Items`, so the
     * plan iterates the Items rows like block B. Rule iteration is reference-driven and the declared group
     * drives nothing (`IterationScopes.prepare`), so the bound level must come from the ITERATION. Block A is
     * blind to this because a star-only rule's iteration anchor happens to equal its declared group, and block
     * B is blind because it declares the rule on the group it iterates.
     */
    @Test
    fun theBoundLevelComesFromTheITERATIONNotTheDeclaredGroup() {
        val readings = listOf(
            "every Note relevant, one row's Tag" to
                listOf(Sel(NOTE, listOf(0, 0, 0)), Sel(TAG, listOf(1, 2, 1))),
            "one row's Tag only" to listOf(Sel(TAG, listOf(1, 2, 1))),
            "every Note relevant, no Tag at all" to listOf(Sel(NOTE, listOf(0, 0, 0))),
            "the error path is wildcarded" to listOf(Sel(TAG, listOf(0, 0, 0))),
        ).map { (label, sel) -> "$label | ${verdicts(REF_DRIVEN_RULE, "/Order", "../Items/Tag", sel)}" }

        assertEquals(
            listOf(
                "every Note relevant, one row's Tag | $ROW2",
                "one row's Tag only | $ROW2",
                "every Note relevant, no Tag at all | $SILENT",
                "the error path is wildcarded | $EVERY_ROW",
            ),
            readings,
            "the references bind the Items level even though the rule is declared on /Order, so the gate is " +
                "exact per row — reading the bound level off the DECLARED group fires all three rows on row 1",
        )
    }

    private fun verdicts(condition: String, group: String, errorRel: String, selection: List<Sel>): String {
        val model = models.getOrPut(Triple(condition, group, errorRel)) {
            RuntimeLaws.withRule(json, group, "RG", errorRel, "RG", condition)
        }
        val def = defs.getOrPut(model) { ModelLoader.load(model) }
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(ACK, "X", 1, 1)
        val iDoc = def.newDocument().group("/Order", 1).field(ACK, "X", 1, 1)
        listOf("A", "B", "C").forEachIndexed { idx, tag ->
            val r = idx + 1
            kDoc.group(ITEMS, 1, r).field(TAG, tag, 1, r, 1).field(NOTE, "n$tag", 1, r, 1)
            iDoc.group(ITEMS, 1, r).field(TAG, tag, 1, r, 1).field(NOTE, "n$tag", 1, r, 1)
        }
        val kRel = selection.map { Relevant.of(it.path, *it.reps.toIntArray()) }.toSet()
        val iRel = selection.mapTo(HashSet()) { RelevantEntity(it.path, it.reps) }
        fun sig(rows: List<String>) = rows.sorted().joinToString(";").ifEmpty { "-" }
        val dynamic = sig(RuntimeLaws.messagesPart(model, kDoc, kRel).filter { it.code() == "RG" }.map { it.field() })
        val static = sig(engines.getOrPut(model) { RuntimeLaws.kernelJavaEngine(model) }
            .messagesPart(kDoc, kRel).filter { it.code() == "RG" }.map { it.field() })
        val interp = sig(DmInterpreter(def).validatePart(iDoc, iRel).messages
            .filter { it.code == "RG" }.map { it.errorPath })
        return "groovy=$dynamic java=$static interp=$interp"
    }

    private data class Sel(val path: String, val reps: List<Int>)

    private companion object {
        const val ACK = "/Order/Ack"
        const val ITEMS = "/Order/Items"
        const val TAG = "/Order/Items/Tag"
        const val NOTE = "/Order/Items/Note"

        /** Both conditions hold on every row of the fixture; the `Or` keeps them TRUE even when the relevant
         *  set makes one side UNKNOWN, so the readings measure the GATE and not the condition. */
        const val ROOT_RULE = "FieldFilled(/Order/Ack) Or AtLeastOneFieldFilled(/Order/Items*/Tag)"
        /** Non-starred references into `/Order/Items`, so the plan iterates Items wherever this is DECLARED —
         *  which is the whole point of the third block: same text, two declared groups, one iteration. */
        const val REF_DRIVEN_RULE = "FieldFilled(/Order/Items/Note) Or FieldFilled(/Order/Items/Tag)"

        private const val R1 = "/Order[1]/Items[1]/Tag"
        private const val R2 = "/Order[1]/Items[2]/Tag"
        private const val R3 = "/Order[1]/Items[3]/Tag"
        const val ROW1 = "groovy=$R1 java=$R1 interp=$R1"
        const val ROW2 = "groovy=$R2 java=$R2 interp=$R2"
        const val EVERY_ROW = "groovy=$R1;$R2;$R3 java=$R1;$R2;$R3 interp=$R1;$R2;$R3"
        const val SILENT = "groovy=- java=- interp=-"
    }
}
