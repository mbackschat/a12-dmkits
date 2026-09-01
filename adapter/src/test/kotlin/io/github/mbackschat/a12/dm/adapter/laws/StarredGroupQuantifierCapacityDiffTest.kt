package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the filled-group COUNT reads the **in-capacity** extent, and why this fixture cannot decide
 * the same question for the two starred QUANTIFIERS.
 *
 * **This file carried the opposite conclusion for one commit, and it was wrong.** It instantiated `Lines[3]` in
 * a capacity-2 group, saw `AtLeastOneGroupFilled` fire beside the kernel's own `zuGrosseZeile`, and concluded
 * that an over-limit row is tallied. a12-kernel-lean named the hole: `zuGrosseZeile` proves the GROUP is over
 * capacity, not that rows 1 and 2 are absent, so the firing was equally consistent with the kernel having
 * materialized the prefix.
 *
 * **Their ladder settles it, against the earlier reading.** A `NumberOfFilledGroups(Lines*)` equality ladder on
 * the same document answers **2** — `createGroupInstance(path, [1, 3])` materialized `Lines[1]` and `Lines[2]`,
 * the count sees exactly those two in-capacity rows and NOT the over-limit third, and the quantifier fired
 * because of them. A boolean quantifier could never have shown this; the ladder is what makes the tally visible,
 * which is the transferable half — a silent operator needs a quantity beside it, not another silence.
 *
 * **What stays open, stated as the limit of two routes rather than as unreachability.** Deciding whether the
 * QUANTIFIERS admit an over-limit row needs a document where the over-limit row exists and the prefix does not.
 * This builder cannot express one, because it materializes the prefix; a12-kernel-lean's route cannot either,
 * since it seeds from canonical Document JSON whose repeatable rows are a positional array. Two routes failing
 * is not proof that no route exists, so this is recorded as **unreached**, never as impossible.
 */
class StarredGroupQuantifierCapacityDiffTest {

    /**
     * **The prefix is materialized, so this document holds three instantiated rows and not one** — and the
     * count tallies two of them. That is the whole correction: the quantifier's firing here is explained by
     * rows 1 and 2, and the over-limit row is shown to contribute to the count not at all.
     */
    @Test
    fun instantiatingAnOverLimitRowMaterializesThePrefixAndTheCountExcludesTheOverLimitRow() {
        assertEquals(
            setOf(AT_LEAST_ONE, OVER_REPETITION, COUNT_IS_2),
            allKernelCodes(row = 3),
            "the count reads 2 — Lines[1] and Lines[2] exist and Lines[3] is not tallied",
        )
    }

    /** The in-capacity row alone tallies one, which is the ladder's positive control. */
    @Test
    fun anInCapacityRowTalliesOne() {
        assertEquals(
            setOf(AT_LEAST_ONE, COUNT_IS_1),
            allKernelCodes(row = 1),
            "one instantiated in-capacity row — the ladder reads 1",
        )
    }

    /**
     * The empty control: with no row instantiated the quantifier pair flips and the ladder reads zero. Without
     * it, every row above would be consistent with a quantifier that never answers false.
     */
    @Test
    fun withNoRowInstantiatedThePairFlipsAndTheLadderReadsZero() {
        assertEquals(setOf(NONE_FILLED, COUNT_IS_0), kernelCodesWithNoRow(), "zero instantiated rows")
    }

    /**
     * An instantiated row is a filled group whatever its cells hold — the half of a12-kernel-lean's
     * `SPEC-2026-08-31-17` that does hold here, and the reason the rows above turn on coordinates and not
     * content.
     */
    @Test
    fun anInstantiatedRowCountsWhateverItsCellsHold() {
        assertEquals(
            allKernelCodes(row = 1),
            allKernelCodes(row = 1, sku = "A-1"),
            "an empty in-capacity row and a filled one tally alike",
        )
    }

    /** Both engines agree, so the extent rule is shared rather than a kernel behaviour we merely tolerate. */
    @Test
    fun theInterpreterAgreesWithTheKernel() {
        listOf("over-limit row 3" to 3, "in-capacity row 1" to 1).forEach { (name, row) ->
            assertEquals(kernelFired(row = row), interpreterFired(row = row), "$name — kernel vs interpreter")
        }
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private fun kernelFired(row: Int, sku: String? = null): Set<String> =
        kernelCodes(row, sku).filterTo(sortedSetOf()) { it in CODES }

    /** Unfiltered, so the ladder rung and the over-repetition report are visible beside the quantifiers. */
    private fun allKernelCodes(row: Int, sku: String? = null): Set<String> = kernelCodes(row, sku).toSortedSet()

    private fun kernelCodes(row: Int, sku: String?): Set<String> {
        val doc = RuntimeLaws.doc()
        doc.group(ORDER, 1)
        doc.field(REF, "R-1", 1, 1)
        doc.group(LINES, 1, row)
        if (sku != null) {
            doc.field(SKU, sku, 1, row, 1)
        }
        return RuntimeLaws.firedCodes(model, doc).toSortedSet()
    }

    private fun kernelCodesWithNoRow(): Set<String> {
        val doc = RuntimeLaws.doc()
        doc.group(ORDER, 1)
        doc.field(REF, "R-1", 1, 1)
        return RuntimeLaws.firedCodes(model, doc).toSortedSet()
    }

    /** The same document on the interpreter. Built separately because the two engines take different document
     *  types; the coordinates are the parameter, so the two builders cannot drift on what they describe. */
    private fun interpreterFired(row: Int, sku: String? = null): Set<String> {
        val def = ModelLoader.load(model)
        val doc = def.newDocument()
        doc.group(ORDER, 1)
        doc.field(REF, "R-1", 1, 1)
        doc.group(LINES, 1, row)
        if (sku != null) {
            doc.field(SKU, sku, 1, row, 1)
        }
        return DmInterpreter(def).validate(doc).messages.map { it.code }.filterTo(sortedSetOf()) { it in CODES }
    }

    private companion object {

        const val ORDER = "/Order"
        const val LINES = "/Order/Lines"
        const val REF = "/Order/Reference"
        const val SKU = "/Order/Lines/Sku"

        const val NONE_FILLED = "NONE_FILLED"
        const val AT_LEAST_ONE = "AT_LEAST_ONE"
        const val OVER_REPETITION = "zuGrosseZeile"
        const val COUNT_IS_0 = "COUNT_IS_0"
        const val COUNT_IS_1 = "COUNT_IS_1"
        const val COUNT_IS_2 = "COUNT_IS_2"
        val CODES = setOf(NONE_FILLED, AT_LEAST_ONE)

        private val baseModel: String = DocumentModelDraft.named("capacity").locale("en_US")
            .rootGroup("Order") { order ->
                order.field("Reference", FieldKind.STRING)
                    .group("Lines") { lines -> lines.repeatable(2).field("Sku", FieldKind.STRING) }
            }.build().toJson()

        /** Both starred quantifiers plus the count ladder over one scope, so the operator is the only variable. */
        val model: String = run {
            val base = BoundModel.ofJson(baseModel)
            val lines = base.flattenGroup("$LINES*")
            val counted = base.group(LINES)
            val guard = Dsl.fieldFilled(base.field(REF))
            fun render(c: Condition) = DslRenderer().render(c)
            var json = baseModel
            listOf(
                NONE_FILLED to render(guard.and(Dsl.noGroupFilled(lines))),
                AT_LEAST_ONE to render(guard.and(Dsl.atLeastOneGroupFilled(lines))),
                // The ladder: exactly one rung fires and names the tally, which a boolean quantifier cannot.
                COUNT_IS_0 to render(guard.and(Dsl.numberOfFilledGroups(counted).eq(0))),
                COUNT_IS_1 to render(guard.and(Dsl.numberOfFilledGroups(counted).eq(1))),
                COUNT_IS_2 to render(guard.and(Dsl.numberOfFilledGroups(counted).eq(2))),
            ).forEach { (code, condition) ->
                json = RuntimeLaws.withRule(json, ORDER, code, "../Reference", code, condition)
            }
            json
        }
    }
}
