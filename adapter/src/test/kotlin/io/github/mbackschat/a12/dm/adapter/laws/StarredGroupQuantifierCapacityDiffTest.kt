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
 * Differential — the starred group quantifiers tally an **over-limit** instantiated row, so their extent is
 * every instantiated repetition and NOT the in-capacity one.
 *
 * **Why this file exists, and how it turned out.** a12-kernel-lean reported (`SPEC-2026-08-31-17`) that the two
 * threshold quantifiers read the in-capacity extent, and flagged our KERNEL-FINDINGS line — which says they
 * "tally the group's instantiated repetitions", with no capacity qualifier — as missing that qualifier. Measured
 * here rather than carried: **the report is refuted on this fixture and our line is correct as written.** An
 * over-limit row is tallied.
 *
 * **The precondition is asserted in the same call as the result**, because the whole measurement is void if the
 * row is not actually over capacity: the kernel emits its own `zuGrosseZeile` beside the quantifier's answer, so
 * "row 3 exceeds a capacity of 2" is observed rather than assumed.
 *
 * **The empty control is what makes the tally readable.** With no row instantiated the pair flips —
 * `NoGroupFilled` fires and `AtLeastOneGroupFilled` does not — so their agreement on rows 1 and 3 is a positive
 * reading and not a quantifier that always answers the same way.
 *
 * This does not contradict [IF27](../../../../../../../../docs/INTERPRETER-FINDINGS.md)'s exclusion of
 * over-limit rows from the star aggregate/quantifier domain so much as bound it: the starred SINGLE-GROUP form
 * these two operators admit is the carrier measured here, and no claim is made about the field-level star
 * quantifiers from these rows.
 */
class StarredGroupQuantifierCapacityDiffTest {

    /**
     * The finding, with its own precondition: row 3 of a capacity-2 group draws the kernel's over-repetition
     * report AND is counted by the quantifier.
     */
    @Test
    fun anOverLimitRowIsTalliedAndTheOverRepetitionReportProvesItIsOverLimit() {
        assertEquals(
            setOf(AT_LEAST_ONE, OVER_REPETITION),
            allKernelCodes(row = 3),
            "the over-limit row is both reported as over-limit and tallied by the quantifier",
        )
    }

    /** The in-capacity row answers identically, so capacity moves nothing here. */
    @Test
    fun anInCapacityRowIsTalliedTheSameWay() {
        assertEquals(setOf(AT_LEAST_ONE), allKernelCodes(row = 1), "row 1 is inside the declared capacity")
    }

    /**
     * The control that makes the two rows above a positive reading: with NO row instantiated the pair flips.
     * Without it, "AtLeastOneGroupFilled fires" would be equally consistent with a quantifier that never
     * answers false.
     */
    @Test
    fun withNoRowInstantiatedThePairFlips() {
        assertEquals(setOf(NONE_FILLED), kernelFiredWithNoRow(), "zero instantiated rows — the tally is 0")
    }

    /**
     * The second half of a12-kernel-lean's claim, which DOES hold: an instantiated row is a filled group
     * whatever its cells hold, so an empty row and a filled one tally alike.
     */
    @Test
    fun anInstantiatedRowCountsWhateverItsCellsHold() {
        assertEquals(
            kernelFired(row = 1),
            kernelFired(row = 1, sku = "A-1"),
            "an empty in-capacity row and a filled one tally alike",
        )
    }

    /** Both engines agree on every case, so this is a shared law and not a kernel quirk we tolerate. */
    @Test
    fun theInterpreterAgreesWithTheKernel() {
        listOf("over-limit row 3" to 3, "in-capacity row 1" to 1).forEach { (name, row) ->
            assertEquals(kernelFired(row = row), interpreterFired(row = row), "$name — kernel vs interpreter")
        }
    }

    // ---- fixtures --------------------------------------------------------------------------------

    /** The kernel document: `/Order[1]` with its reference, plus `Lines[row]` instantiated. */
    private fun kernelFired(row: Int, sku: String? = null): Set<String> =
        kernelCodes(row, sku).filterTo(sortedSetOf()) { it in CODES }

    /** Unfiltered, so the over-repetition report is observable beside the quantifier's answer. */
    private fun allKernelCodes(row: Int): Set<String> = kernelCodes(row, null).toSortedSet()

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

    private fun kernelFiredWithNoRow(): Set<String> {
        val doc = RuntimeLaws.doc()
        doc.group(ORDER, 1)
        doc.field(REF, "R-1", 1, 1)
        return RuntimeLaws.firedCodes(model, doc).filterTo(sortedSetOf()) { it in CODES }
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
        val CODES = setOf(NONE_FILLED, AT_LEAST_ONE)

        private val baseModel: String = DocumentModelDraft.named("capacity").locale("en_US")
            .rootGroup("Order") { order ->
                order.field("Reference", FieldKind.STRING)
                    .group("Lines") { lines -> lines.repeatable(2).field("Sku", FieldKind.STRING) }
            }.build().toJson()

        /** Both starred quantifiers over the same scope, so the operator is the only variable. */
        val model: String = run {
            val base = BoundModel.ofJson(baseModel)
            val lines = base.flattenGroup("$LINES*")
            val guard = Dsl.fieldFilled(base.field(REF))
            fun render(c: Condition) = DslRenderer().render(c)
            var json = baseModel
            listOf(
                NONE_FILLED to render(guard.and(Dsl.noGroupFilled(lines))),
                AT_LEAST_ONE to render(guard.and(Dsl.atLeastOneGroupFilled(lines))),
            ).forEach { (code, condition) ->
                json = RuntimeLaws.withRule(json, ORDER, code, "../Reference", code, condition)
            }
            json
        }
    }
}
