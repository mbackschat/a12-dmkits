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
 * Differential — a starred GROUP operand under partial validation is evaluable exactly when every field it
 * contains is, each asked the extent gate a starred field would be asked (KERNEL-FINDINGS KF464).
 *
 * Every carrier here takes a group star: the group count `NumberOfFilledGroups` over a repeatable group and over
 * a fixed group below it, and the field-expanding `NumberOfFilledFields`, `Sum`, `MaxValue` and
 * `NumberOfValueInFields`. Each rule compares the carrier with the value full validation gives it, so under a
 * coverage it fires exactly when the carrier is evaluable there. The field star `Batches*` + `/Seal/Code` is the
 * control whose gate was already right, and a full-validation run shows every rule live on the document.
 *
 * The coverages separate the readings a reader could hold: the group path must itself be named, against its
 * fields being named in its place; concrete group rows beside field wildcards, which the wildcards encompass;
 * and one field covered concretely in every row, which leaves the extent unknown although no cell is uncovered.
 */
class PartialGroupStarFieldCoverageDiffTest {

    private companion object {
        const val ITEM = "/Item"
        const val TITLE = "/Item/Title"
        const val BATCHES = "/Item/Batches"
        const val LOT = "/Item/Batches/Lot"
        const val CODE = "/Item/Batches/Seal/Code"
        const val WEIGHT = "/Item/Batches/Seal/Weight"
        const val LINES = "/Item/Lines"
        const val QTY = "/Item/Lines/Qty"
        const val PRICE = "/Item/Lines/Price"
    }

    private val base = DocumentModelDraft.named("warehouse-partial-scope").locale("en_US")
        .rootGroup("Item") { i ->
            i.field("Title", FieldKind.STRING)
                .group("Batches") { b ->
                    b.repeatable(4).field("Lot", FieldKind.STRING)
                        .group("Seal") { it.field("Code", FieldKind.STRING).field("Weight", FieldKind.NUMBER) }
                }
                .group("Lines") { it.repeatable(3).field("Qty", FieldKind.NUMBER).field("Price", FieldKind.NUMBER) }
        }.build().toJson()

    /** Rule code → the carrier compared with its full-validation value. */
    private val carriers = linkedMapOf(
        "NFG_BATCHES" to "NumberOfFilledGroups($BATCHES*) == 2",
        "NFG_SEALS" to "NumberOfFilledGroups($BATCHES*/Seal) == 2",
        "NFF_BATCHES" to "NumberOfFilledFields($BATCHES*) == 4",
        "SUM_LINES" to "Sum($LINES*) == 10",
        "MAX_LINES" to "MaxValue($LINES*) == 4",
        "NVF_LINES" to "NumberOfValueInFields(4 In $LINES*) == 1",
        "CODE_STAR" to "NumberOfFilledFields($BATCHES*/Seal/Code) == 2",
    )

    private val model = carriers.entries.fold(base) { json, (code, carrier) ->
        RuntimeLaws.withRule(json, ITEM, code, "../Title", code, "FieldFilled($TITLE) And $carrier")
    }
    private val definition = ModelLoader.load(model)
    private val javaEngine = RuntimeLaws.kernelJavaEngine(model)

    /** Two batches, each Seal holding a `Code` and a `Weight`, and two lines holding a `Qty` and a `Price`. */
    private fun document() = Both(RuntimeLaws.doc(), definition.newDocument()).group(ITEM, 1).field(TITLE, "crate", 1, 1)
        .group(BATCHES, 1, 1).field(CODE, "S1", 1, 1, 1, 1).field(WEIGHT, "1", 1, 1, 1, 1)
        .group(BATCHES, 1, 2).field(CODE, "S2", 1, 2, 1, 1).field(WEIGHT, "2", 1, 2, 1, 1)
        .group(LINES, 1, 1).field(QTY, "1", 1, 1, 1).field(PRICE, "2", 1, 1, 1)
        .group(LINES, 1, 2).field(QTY, "3", 1, 2, 1).field(PRICE, "4", 1, 2, 1)

    private data class Selection(val path: String, val reps: List<Int>)

    private fun everyRow(path: String) = Selection(path, List(path.count { it == '/' }) { RelevantEntity.ALL })

    private fun row(groupOrField: String, vararg reps: Int) = Selection(groupOrField, reps.toList())

    private val allFields = listOf(LOT, CODE, WEIGHT, QTY, PRICE).map(::everyRow)
    private val concreteGroupRows = listOf(row(BATCHES, 0, 1), row(BATCHES, 0, 2), row(LINES, 0, 1), row(LINES, 0, 2))

    @Test
    fun everyRuleFiresUnderFullValidation() {
        val d = document()
        val kernel = RuntimeLaws.messages(model, d.kDoc).map { it.code() }.filter { it in carriers }.toSortedSet()
        val interpreter = DmInterpreter(definition).validateFull(d.iDoc).map { it.code }.filter { it in carriers }.toSortedSet()
        assertEquals(carriers.keys.toSortedSet(), kernel, "the kernel, full validation")
        assertEquals(kernel, interpreter, "the interpreter, full validation")
    }

    @Test
    fun everyContainedFieldCoveredByWildcardEstablishesTheExtent() =
        assertFiredUnder(allFields, carriers.keys)

    /** `Lot` and `Price` uncovered: only the Seal count and the Code star keep every field they contain covered. */
    @Test
    fun anUncoveredContainedFieldLeavesTheExtentUnknown() =
        assertFiredUnder(listOf(CODE, WEIGHT, QTY).map(::everyRow), setOf("NFG_SEALS", "CODE_STAR"))

    /** The group entities themselves by wildcard — the path the interpreter already read. */
    @Test
    fun theGroupEntitiesByWildcardEstablishTheExtent() =
        assertFiredUnder(listOf(everyRow(BATCHES), everyRow(LINES)), carriers.keys)

    /** Concrete group rows beside field wildcards are encompassed by them and do not unsettle the extent. */
    @Test
    fun concreteGroupRowsBesideFieldWildcardsAreEncompassed() =
        assertFiredUnder(allFields + concreteGroupRows, carriers.keys)

    /** A wildcard over the `Seal` group, a descendant of `Batches`, covers the two fields below it. */
    @Test
    fun aDescendantGroupWildcardCoversTheFieldsBelowIt() =
        assertFiredUnder(listOf("/Item/Batches/Seal", LOT, QTY, PRICE).map(::everyRow), carriers.keys)

    @Test
    fun concreteGroupRowsAloneLeaveEveryExtentUnknown() =
        assertFiredUnder(concreteGroupRows, emptySet())

    /**
     * `Lot` and `Qty` covered concretely in both rows, every other field by wildcard: no cell is uncovered, and
     * still the extent of every carrier containing those two fields is unknown.
     */
    @Test
    fun aFieldCoveredConcretelyInEveryRowLeavesTheExtentUnknown() = assertFiredUnder(
        listOf(row(LOT, 0, 1, 0), row(LOT, 0, 2, 0), row(QTY, 0, 1, 0), row(QTY, 0, 2, 0)) +
            listOf(CODE, WEIGHT, PRICE).map(::everyRow),
        setOf("NFG_SEALS", "CODE_STAR"),
    )

    /** Asserts which rules dynamic Groovy fires under [coverage], then static Java and the interpreter against it. */
    private fun assertFiredUnder(coverage: List<Selection>, expected: Set<String>) {
        val selections = coverage + everyRow(TITLE)
        val kRelevant = selections.map { Relevant.of(it.path, *it.reps.toIntArray()) }.toSet()
        val iRelevant = selections.map { RelevantEntity(it.path, it.reps) }.toSet()
        val d = document()
        val dynamic = RuntimeLaws.messagesPart(model, d.kDoc, kRelevant).map { it.code() }.filter { it in carriers }.toSortedSet()
        val static = javaEngine.messagesPart(document().kDoc, kRelevant).map { it.code() }.filter { it in carriers }.toSortedSet()
        val interpreter = DmInterpreter(definition).validatePart(d.iDoc, iRelevant).messages.map { it.code }
            .filter { it in carriers }.toSortedSet()
        assertEquals(expected.toSortedSet(), dynamic, "dynamic Groovy")
        assertEquals(dynamic, static, "static Java")
        assertEquals(dynamic, interpreter, "interpreter")
    }
}
