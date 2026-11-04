package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the two boundaries of the wholly relevant group extent a partial `NumberOfFilledGroups` is decided
 * on (KERNEL-FINDINGS KF667), asked by a12-kernel-lean's SPEC-2026-11-04-01 from a source reading: whether concrete
 * coverage of a capacity-one level folds into its wildcard, and whether a declared fixed sub-group with no field
 * below it keeps its parent from being wholly relevant.
 *
 * The first boundary has no starred carrier: a group stored at repeatability 1 is not repeatable, and A12 refuses a
 * star over it. Its fixed-level half, concrete rows of a non-repeatable group never folding, is KF667's `Seal` row.
 * The second boundary holds on both counted shapes, the starred level and a fixed group, while `GroupFilled` over the
 * same fixed group is still decided beside its empty sub-group, so the boundary belongs to the count's extent and not
 * to group presence. Each row has a control one builder call away, and every carrier compares with what full validation
 * gives it, so it fires exactly when partial validation can decide it. Dynamic Groovy, static Java and the
 * interpreter are compared on every row. Own domain (a warehouse item and its batches).
 */
class PartialWhollyRelevantBoundaryDiffTest {

    private companion object {
        const val ITEM = "/Item"
        const val TITLE = "/Item/Title"
        const val BATCHES = "/Item/Batches"
        const val LOT = "/Item/Batches/Lot"
        const val CODE = "/Item/Batches/Seal/Code"
        const val LABEL = "/Item/Label"
        const val TEXT = "/Item/Label/Text"
        const val STAMP = "/Item/Stamp"
        const val MARK = "/Item/Stamp/Mark"
    }

    private val carriers = linkedMapOf(
        "NFG_BATCHES" to "NumberOfFilledGroups($BATCHES*) == 1",
        "NFG_FIXED" to "NumberOfFilledGroups($LABEL, $STAMP) == 2",
        "GF_LABEL" to "GroupFilled($LABEL)",
    )

    /** One held batch; [emptyInBatch] and [emptyInLabel] each add a fixed `Note` group with no field. */
    private fun model(capacity: Int, emptyInBatch: Boolean = false, emptyInLabel: Boolean = false) =
        DocumentModelDraft.named("warehouse-wholly-relevant").locale("en_US")
            .rootGroup("Item") { i ->
                i.field("Title", FieldKind.STRING)
                    .group("Batches") { b ->
                        b.repeatable(capacity).field("Lot", FieldKind.STRING)
                            .group("Seal") { it.field("Code", FieldKind.STRING) }
                        if (emptyInBatch) b.group("Note") { }
                    }
                    .group("Label") { l ->
                        l.field("Text", FieldKind.STRING)
                        if (emptyInLabel) l.group("Note") { }
                    }
                    .group("Stamp") { it.field("Mark", FieldKind.STRING) }
            }.build().toJson().let { base ->
                carriers.entries.fold(base) { json, (code, carrier) ->
                    RuntimeLaws.withRule(json, ITEM, code, "../Title", code, "FieldFilled($TITLE) And $carrier")
                }
            }

    private fun everyRow(path: String) = path to List(path.count { it == '/' }) { RelevantEntity.ALL }

    private fun row(path: String, vararg reps: Int) = path to reps.toList()

    private val wildcards = listOf(LOT, CODE, TEXT, MARK).map(::everyRow)

    /** A star over a group stored at repeatability 1 is refused, so no capacity-one level can be counted. */
    @Test
    fun aStarOverAGroupStoredAtRepeatabilityOneIsRefused() {
        val stored = Regex(""""repeatability"\s*:\s*2""")
        val base = model(2)
        assertEquals(1, stored.findAll(base).count(), "the fixture stores one repeatability of 2")
        assertEquals(listOf("ERROR: Rule with path '/Item/NFG_BATCHES': Since '/Item/Batches' is not repeatable, it is " +
            "not allowed to specify an asterisk. [MVK_INVALID_WILDCARD]"),
            RuntimeLaws.consistencyMessages(base.replace(stored, "\"repeatability\" : 1")))
    }

    /** The empty-sub-group control: the batches without `Note`, same coverage, decide both counts. */
    @Test
    fun withoutAnEmptySubGroupEveryCountDecides() =
        assertFiredUnder(model(2), wildcards, carriers.keys)

    /** An empty fixed `Note` in each batch, one builder call away from the control above. */
    @Test
    fun anEmptySubGroupInTheCountedLevel() =
        assertFiredUnder(model(2, emptyInBatch = true), wildcards, setOf("NFG_FIXED", "GF_LABEL"))

    /** An empty fixed `Note` inside the fixed `Label`, the batches untouched; `GroupFilled(Label)` is the presence control. */
    @Test
    fun anEmptySubGroupInACountedFixedGroup() =
        assertFiredUnder(model(2, emptyInLabel = true), wildcards, setOf("NFG_BATCHES", "GF_LABEL"))

    private fun assertFiredUnder(model: String, coverage: List<Pair<String, List<Int>>>, expected: Set<String>) {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model), "the fixture must be admitted")
        val definition = ModelLoader.load(model)
        val javaEngine = RuntimeLaws.kernelJavaEngine(model)
        fun document() = Both(RuntimeLaws.doc(), definition.newDocument()).group(ITEM, 1).field(TITLE, "crate", 1, 1)
            .group(BATCHES, 1, 1).field(LOT, "L1", 1, 1, 1).field(CODE, "S1", 1, 1, 1, 1)
            .group(LABEL, 1, 1).field(TEXT, "fragile", 1, 1, 1)
            .group(STAMP, 1, 1).field(MARK, "checked", 1, 1, 1)
        val selections = coverage + everyRow(TITLE)
        val kRelevant = selections.map { (path, reps) -> Relevant.of(path, *reps.toIntArray()) }.toSet()
        val iRelevant = selections.map { (path, reps) -> RelevantEntity(path, reps) }.toSet()
        val d = document()
        fun ours(codes: List<String>) = codes.filter { it in carriers }.toSortedSet()
        assertEquals(
            mapOf("full" to carriers.keys.toSortedSet(), "dynamic" to expected.toSortedSet(),
                "static" to expected.toSortedSet(), "interpreter" to expected.toSortedSet()),
            mapOf(
                "full" to ours(RuntimeLaws.messages(model, document().kDoc).map { it.code() }),
                "dynamic" to ours(RuntimeLaws.messagesPart(model, d.kDoc, kRelevant).map { it.code() }),
                "static" to ours(javaEngine.messagesPart(document().kDoc, kRelevant).map { it.code() }),
                "interpreter" to ours(DmInterpreter(definition).validatePart(d.iDoc, iRelevant).messages.map { it.code }),
            ),
        )
    }
}
