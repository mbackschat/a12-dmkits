package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * An index-field cell is identified by its **canonical A12 value, never its stored text** ([IF272]). On a
 * **Number** index field the legal spellings `5` and `5.00` are ONE kernel key (`IndexFieldCache.normalizeValue`,
 * SPEC-2026-07-23-08), so they collide within a group and match across joined groups — while a value that does
 * not parse as a number keeps its raw text.
 *
 * Four sites read an index cell as an identity, and each one is measured here because the interpreter kept the
 * raw string in **three** of them (the fourth already keyed the typed [io.github.mbackschat.a12.dm.interpreter.ast.Val],
 * whose decimal equality is value-based since IF41):
 *
 *  - the **parallel join**'s value→row map (`Document.indexMap`) — collision *and* cross-group matching;
 *  - the auto **`uniqueIndex`** check (`AutoChecks.duplicateIndexCells`);
 *  - the **computation** engine's invalid-index detection (`ComputationEngine.invalidIndexParentCoords`);
 *  - the semantic-index **column** behind a starred aggregate over the index field (`IndexColumn`).
 *
 * `5`/`5` beside every `5`/`5.00` is the textual control: it was already exact, so a case that only passes
 * because *both* spellings are ignored cannot hide here. `5`/`6` is the clean control that still fires.
 * Both kernel strategies agree on every row.
 */
class NumericIndexKeyDiffTest {

    private val base = DocumentModelDraft.named("tariff").locale("en_US")
        .rootGroup("Tariff", Consumer { g: GroupBuilder ->
            g.stringField("ProductCode", Consumer { })
            g.numberField("Threshold", Consumer { it.fractionDigits(2) })
            for (name in listOf("Offered", "Quoted")) {
                g.group(name, Consumer { s ->
                    s.repeatable(20).indexField("Deductible")
                        .numberField("Deductible", Consumer { it.fractionDigits(2) })
                        .numberField("Premium", Consumer { it.fractionDigits(2) })
                })
            }
        }).build().toJson()

    /** The parallel join over the two sibling groups, reported by a NEGATIVE operator so an unmatched-into-an
     *  invalid group (3VL UNKNOWN) is distinguishable from a definite empty. */
    private val joinModel by lazy {
        RuntimeLaws.withRule(
            base, "/Tariff", "PJ", "../Offered/Premium", "PJ",
            "FieldsNotCollectivelyFilled(/Tariff/Offered/Premium, /Tariff/Quoted/Premium)",
        )
    }

    /** A starred aggregate over the index field itself — the [IndexColumn] site. Its cells are poisoned by the
     *  `uniqueIndex` error, so a duplicated column reads UNKNOWN and `AtLeastOne` skips it. */
    private val columnModel by lazy {
        RuntimeLaws.withRule(
            base, "/Tariff", "VL", "../Offered/Deductible", "VL",
            "AtLeastOneFieldValueIncludedInValueList(/Tariff/Offered*/Deductible In /Tariff/Threshold)",
        )
    }

    private val computeModel by lazy {
        RuntimeLaws.withComputation(base, "/Tariff/Quoted", "Copy", "../Premium", "[../Offered/Premium]")
    }

    @Test
    fun aNumericallyEqualPairIsOneKeyInTheJoinAndInTheUniquenessCheck() {
        val readings = listOf(
            "distinct keys (control)" to Doc(listOf("5" to "10", "6" to "20"), listOf("5" to "30", "7" to "40")),
            "5 and 5.00 in the same group" to Doc(listOf("5" to "10", "5.00" to "20"), listOf("6" to "40")),
            "5 and 5.00 alongside a clean 6" to
                Doc(listOf("5" to "10", "5.00" to "20", "6" to "30"), listOf("7" to "40")),
            "5 and 5 in the same group (textual control)" to
                Doc(listOf("5" to "10", "5" to "20"), listOf("6" to "40")),
            "5 here, 5.00 THERE — one joined row" to Doc(listOf("5" to "10"), listOf("5.00" to "40")),
        ).map { (label, doc) -> "$label | ${validate(joinModel, doc)}" }

        assertEquals(
            listOf(
                "distinct keys (control) | groovy=$UNMATCHED_PAIR java=$UNMATCHED_PAIR interp=$UNMATCHED_PAIR",
                "5 and 5.00 in the same group | groovy=$COLLIDED java=$COLLIDED interp=$COLLIDED",
                "5 and 5.00 alongside a clean 6 | groovy=$COLLIDED_PLUS_ROW3 java=$COLLIDED_PLUS_ROW3 " +
                    "interp=$COLLIDED_PLUS_ROW3",
                "5 and 5 in the same group (textual control) | groovy=$COLLIDED java=$COLLIDED interp=$COLLIDED",
                // The two spellings are ONE join key, so both Premiums land in a single joined row and the
                // collective-fill operator is satisfied. Keying by text yields two unmatched rows and two errors.
                "5 here, 5.00 THERE — one joined row | groovy=- java=- interp=-",
            ),
            readings,
            "`5` and `5.00` are one index key: they collide (keying nothing, and flagged uniqueIndex on BOTH " +
                "rows) within a group, and match each other across the joined groups",
        )
    }

    @Test
    fun aNumericallyEqualPairPoisonsAStarredAggregateOverTheIndexField() {
        val readings = listOf(
            "distinct keys (control)" to Doc(listOf("5" to "10", "6" to "20"), listOf()),
            "5 and 5 (textual control)" to Doc(listOf("5" to "10", "5" to "20"), listOf()),
            "5 and 5.00" to Doc(listOf("5" to "10", "5.00" to "20"), listOf()),
        ).map { (label, doc) -> "$label | ${validate(columnModel, doc, only = "VL")}" }

        assertEquals(
            listOf(
                "distinct keys (control) | groovy=$VL_FIRES java=$VL_FIRES interp=$VL_FIRES",
                "5 and 5 (textual control) | groovy=- java=- interp=-",
                "5 and 5.00 | groovy=- java=- interp=-",
            ),
            readings,
            "every cell of a duplicated index value carries the uniqueIndex error and reads UNKNOWN, so the " +
                "starred aggregate skips it — a numerically equal pair exactly as a textually equal one",
        )
    }

    @Test
    fun aNumericallyEqualTargetKeyCompromisesTheComputationColumn() {
        val readings = listOf(
            "target keys 5 and 6 (control)" to Doc(listOf("5" to "30"), listOf("5" to "0", "6" to "0")),
            "target keys 5 and 5.00" to Doc(listOf("5" to "30"), listOf("5" to "0", "5.00" to "0")),
            "target keys 5 and 5 (textual control)" to Doc(listOf("5" to "30"), listOf("5" to "0", "5" to "0")),
        ).map { (label, doc) -> "$label | ${compute(doc)}" }

        assertEquals(
            listOf(
                "target keys 5 and 6 (control) | groovy=$COMPUTED java=$COMPUTED interp=$COMPUTED",
                "target keys 5 and 5.00 | groovy=$BOTH_CLEARED java=$BOTH_CLEARED interp=$BOTH_CLEARED",
                "target keys 5 and 5 (textual control) | groovy=$BOTH_CLEARED java=$BOTH_CLEARED " +
                    "interp=$BOTH_CLEARED",
            ),
            readings,
            "a numerically duplicated TARGET index compromises the computation's whole column, clearing every " +
                "instance — the kernel's own over-marking guard (IF96/IF222), reached by the canonical key",
        )
    }

    private fun validate(model: String, doc: Doc, only: String? = null): String {
        val def = ModelLoader.load(model)
        fun sig(v: List<String>) = v.sorted().joinToString(";").ifEmpty { "-" }
        val dynamic = sig(RuntimeLaws.messages(model, kernelDoc(doc))
            .filter { only == null || it.code() == only }.map { "${it.code()}@${it.field()}" })
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val static = sig(engine.messages(engine.instance(kernelDoc(doc)))
            .filter { only == null || it.code() == only }.map { "${it.code()}@${it.field()}" })
        val interp = sig(DmInterpreter(def).validateFull(interpreterDoc(def, doc))
            .filter { only == null || it.code == only }.map { "${it.code}@${it.errorPath}" })
        return "groovy=$dynamic java=$static interp=$interp"
    }

    private fun compute(doc: Doc): String {
        val def = ModelLoader.load(computeModel)
        fun kernel(c: RuntimeLaws.Computed) =
            if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}"
        val dynamic = RuntimeLaws.compute(computeModel, kernelDoc(doc))
            .map { "${it.pointer()}=${kernel(it)}" }.sorted().joinToString(";")
        val engine = RuntimeLaws.kernelJavaEngine(computeModel)
        val static = engine.compute(engine.instance(kernelDoc(doc)))
            .map { "${it.pointer()}=${kernel(it)}" }.sorted().joinToString(";")
        val interp = DmInterpreter(def).compute(interpreterDoc(def, doc)).map {
            val outcome = when (it.outcome) {
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> "VALUE|${it.value}"
            }
            "${formatPointer(it.path, it.indices)}=$outcome"
        }.sorted().joinToString(";")
        return "groovy=$dynamic java=$static interp=$interp"
    }

    private fun kernelDoc(doc: Doc) = RuntimeLaws.doc().also { k ->
        doc.place({ p, r -> k.group(p, *r) }, { p, v, r -> k.field(p, v, *r) })
    }

    private fun interpreterDoc(def: ModelDef, doc: Doc): Document =
        def.newDocument().also { i -> doc.place({ p, r -> i.group(p, *r) }, { p, v, r -> i.field(p, v, *r) }) }

    /** One document shape: the two sibling groups' `(Deductible, Premium)` rows. */
    private data class Doc(val offered: List<Pair<String, String>>, val quoted: List<Pair<String, String>>) {
        fun place(group: (String, IntArray) -> Unit, field: (String, String, IntArray) -> Unit) {
            group("/Tariff", intArrayOf(1))
            field("/Tariff/ProductCode", "COMP-1", intArrayOf(1, 1))
            field("/Tariff/Threshold", "5", intArrayOf(1, 1))
            rows("/Tariff/Offered", offered, group, field)
            rows("/Tariff/Quoted", quoted, group, field)
        }

        private fun rows(g: String, values: List<Pair<String, String>>, group: (String, IntArray) -> Unit,
                field: (String, String, IntArray) -> Unit) =
            values.forEachIndexed { i, (deductible, premium) ->
                val r = i + 1
                group(g, intArrayOf(1, r))
                field("$g/Deductible", deductible, intArrayOf(1, r, 1))
                field("$g/Premium", premium, intArrayOf(1, r, 1))
            }
    }

    private companion object {
        private const val DUP1 = "uniqueIndex@/Tariff[1]/Offered[1]/Deductible"
        private const val DUP2 = "uniqueIndex@/Tariff[1]/Offered[2]/Deductible"
        private const val PJ_ROW2 = "PJ@/Tariff[1]/Offered[2]/Premium"
        private const val PJ_ROW3 = "PJ@/Tariff[1]/Offered[3]/Premium"

        /** The kernel's `IIdentifier.UNKNOWN` repetition — Offered has no row for the joined value. */
        private const val PJ_NONE = "PJ@/Tariff[1]/Offered[-5]/Premium"

        /** A valid index on both sides: this side's unmatched key fires at row 2, the far side's at `[-5]`. */
        const val UNMATCHED_PAIR = "$PJ_NONE;$PJ_ROW2"

        /** The colliding value keys nothing on either side, so no join row survives to report. */
        const val COLLIDED = "$DUP1;$DUP2"
        const val COLLIDED_PLUS_ROW3 = "$PJ_ROW3;$DUP1;$DUP2"

        const val VL_FIRES = "VL@/Tariff[1]/Offered[1]/Deductible"
        const val COMPUTED = "/Tariff[1]/Quoted[1]/Premium=VALUE|30;/Tariff[1]/Quoted[2]/Premium=VALUE|0"
        const val BOTH_CLEARED = "/Tariff[1]/Quoted[1]/Premium=CLEARED;/Tariff[1]/Quoted[2]/Premium=CLEARED"
    }
}
