package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check — **partial validation masks a CELL-BACKED reference and does not mask the
 * structural repetition COORDINATE**, with the error-field gate separate and prior.
 *
 * Measured to discharge a12-kernel-lean's `SPEC-2026-09-22-02`, whose partial route refused a rule whose only
 * non-field operand was `CurrentRepetition(group)` as structurally unsupported while the Kernel evaluated it
 * normally under the same coverage.
 *
 * **The separator is the twin rule, not the coverage.** Both models below are built from one base and differ
 * in exactly one builder argument — the operand left of `> 1`. Guard, error field, group, comparison operator
 * and threshold are identical, so a coverage that moves one and not the other is asking about the operand and
 * nothing else. Comparing two coverages on ONE rule could not do that: every coverage difference would also be
 * a difference in what the rule reads.
 *
 * **Why "not masked" rather than "always evaluated".** The error-field gate runs first and per row, so a
 * coverage naming only the OTHER field skips the coordinate-reading rule too — although the coordinate needed
 * no coverage at all. Both rows are here because the pair is the claim; either alone reads as the wrong rule.
 */
class PartialRelevanceRepetitionCoordinateDiffTest {

    private companion object {
        const val ROWS = "/Src/Rows"
        const val FLAG = "/Src/Rows/Flag"
        const val LEVEL = "/Src/Rows/Level"
        const val CODE = "PRC"
    }

    /** Two rows, both carrying a Level above the threshold, so the cell-backed twin fires in both. */
    private val json = DocumentModelDraft.named("src").locale("en_US")
        .rootGroup("Src") { g ->
            g.group("Rows") { r ->
                r.repeatable(50).stringField("Flag") { }.numberField("Level") { it.fractionDigits(0) }
            }
        }.build().toJson()

    private val bound = BoundModel.ofJson(json)

    /**
     * Both conditions are authored through the typed DSL and rendered, so the twins differ in exactly one
     * builder call and each path is written once and validated at the call site.
     */
    private val coordinate = Dsl.currentRepetition(bound.group(ROWS)).gt(1)
    private val cell = bound.num(LEVEL).gt(1)

    /**
     * The shared guard, on the ERROR field. The kernel refuses an iterating rule that can fire on an unfilled
     * repetition (`MVK_NEG_CONDITION_IN_ITERATION`), so both twins need one — and putting it on the field the
     * discriminating coverage already names keeps it out of the measurement.
     */
    private fun guarded(condition: Condition) = Dsl.fieldFilled(bound.field(FLAG)).and(condition)

    private fun modelWith(condition: Condition) =
        RuntimeLaws.withRule(json, ROWS, "PrcRule", "../Flag", CODE, guarded(condition))

    private val staticEngines = mutableMapOf<String, RuntimeLaws.JavaKernelEngine>()

    /**
     * Where the rule fires — the row addresses, not merely a count, because the claim is that the coordinate
     * answers *from the iterated row*. Asserted equal across both kernel codegen strategies and the
     * interpreter; `null` relevance means full validation.
     */
    private fun agree(condition: Condition, relevant: Set<Relevant>?, label: String): Set<String> {
        val model = modelWith(condition)
        val def = ModelLoader.load(model)
        val kDoc = RuntimeLaws.doc().group("/Src", 1)
        val iDoc = def.newDocument().group("/Src", 1)
        for (r in 1..2) {
            kDoc.group(ROWS, 1, r).field(FLAG, "x", 1, r, 1).field(LEVEL, "5", 1, r, 1)
            iDoc.group(ROWS, 1, r).field(FLAG, "x", 1, r, 1).field(LEVEL, "5", 1, r, 1)
        }
        val iRel = relevant?.map { RelevantEntity(it.path(), it.reps()) }?.toSet()
        val dynamic = (if (relevant == null) RuntimeLaws.messages(model, kDoc)
            else RuntimeLaws.messagesPart(model, kDoc, relevant))
            .filter { it.code() == CODE }.map { it.field() }.toSortedSet()
        val static = staticEngines.getOrPut(model) { RuntimeLaws.kernelJavaEngine(model) }
            .let { if (relevant == null) it.messages(it.instance(kDoc)) else it.messagesPart(kDoc, relevant) }
            .filter { it.code() == CODE }.map { it.field() }.toSortedSet()
        val interp = (if (iRel == null) DmInterpreter(def).validateFull(iDoc)
            else DmInterpreter(def).validatePart(iDoc, iRel).messages)
            .filter { it.code == CODE }.map { "${it.errorPath}" }.toSortedSet()
        assertEquals(dynamic, static, "$label — dynamic-Groovy=$dynamic static-Java=$static")
        assertEquals(dynamic, interp, "$label — dynamic-Groovy=$dynamic interp=$interp")
        return dynamic
    }

    /** Every row of the error field, and nothing else — the coverage the claim is about. */
    private val errorFieldOnly = setOf(Relevant.of(FLAG, 0, 0, 0))

    /** Every row of the OTHER field — the coverage that the prior error-field gate answers. */
    private val otherFieldOnly = setOf(Relevant.of(LEVEL, 0, 0, 0))

    /**
     * **THE DISCRIMINATOR.** Under a coverage naming only the error field, the coordinate-reading rule answers
     * exactly as under full validation while its cell-backed twin goes silent. A relevant set cannot drive
     * `CurrentRepetition(G)` to UNKNOWN, because it answers from the iterated row rather than from a cell.
     */
    @Test
    fun theCoordinateAnswersUnderACoverageThatSilencesItsCellBackedTwin() {
        val coordinateFull = agree(coordinate, null, "coordinate, full validation")
        val coordinatePart = agree(coordinate, errorFieldOnly, "coordinate, error field covered")
        assertEquals(coordinateFull, coordinatePart,
            "the repetition coordinate needs no coverage, so partial must answer as full does")

        val cellFull = agree(cell, null, "cell-backed twin, full validation")
        val cellPart = agree(cell, errorFieldOnly, "cell-backed twin, error field covered")
        assertEquals(emptySet<String>(), cellPart,
            "the twin's operand is a cell the coverage does not name, so it is masked to UNKNOWN")
        assertNotEquals(cellFull, cellPart,
            "…and it DID fire under full validation, so the silence above is masking rather than a false guard")
    }

    /**
     * **THE PRIOR GATE.** The error-field gate is independent of the masking above and still runs per row: a
     * coverage naming only the other field, and the empty coverage, skip the coordinate-reading rule as well —
     * although the coordinate needed no coverage at all. This is why the correct reading is "not masked" and
     * never "always evaluated".
     */
    @Test
    fun theErrorFieldGateStillSkipsTheCoordinateReadingRule() {
        assertEquals(emptySet<String>(), agree(coordinate, otherFieldOnly, "coordinate, other field covered"),
            "the error-field gate is asked first, and the coordinate does not exempt a rule from it")
        assertEquals(emptySet<String>(), agree(coordinate, emptySet(), "coordinate, empty coverage"),
            "…and the empty coverage is the same gate answering")
    }

    /**
     * **THE PREMISE.** Both twins are spliced in as raw JSON, downstream of any gate that ran on the base, so
     * "the kernel admits these models" is an assumption this file rests on rather than something it observes.
     * A cold review checked it externally and it holds; asserted here so it stops being external.
     *
     * The masking result is only about masking if the masked model is legal — a model the kernel refuses
     * would also produce an empty message set, and the discriminator could not tell the two apart.
     */
    @Test
    fun bothSplicedModelsAreKernelAdmitted() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(modelWith(coordinate)),
            "the coordinate-reading twin must be admitted")
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(modelWith(cell)),
            "…and so must its cell-backed twin, or the silence below is a refusal rather than masking")
    }

    /**
     * **THE NON-VACUITY CONTROL.** The coordinate rule must fire in one row and not the other, so the equality
     * in the discriminator compares a shape rather than two empty sets — and so an engine that ignored the
     * coordinate entirely could not produce this table.
     */
    @Test
    fun theCoordinateRuleDistinguishesTheTwoRows() {
        val fired = agree(coordinate, null, "coordinate, full validation")
        assertEquals(1, fired.size, "CurrentRepetition > 1 holds in the second row only, got $fired")
    }
}
