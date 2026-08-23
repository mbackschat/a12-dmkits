package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A formally INVALID operand makes `NumberOfFilledFields` **unavailable** — neither skipped nor counted as
 * filled (a12-kernel-lean `SPEC-2026-08-23-14`, [KF195]).
 *
 * **Why this exists beside `FilledCountLawsTest`, which already claims it.** That neighbour asserts the claim
 * with ONE rule — `count == 2` does not fire when an operand is invalid — and that assertion is
 * presence-shaped: it is satisfied by all three rival accounts. An implementation that SKIPS the invalid
 * operand counts `1` and also fails `== 2`; one that counts it as filled counts `2` and would fail only the
 * unavailability claim. Nothing there separates them, so the claim was true but untested.
 *
 * **The three-rule design is what separates them.** One count is compared against `0`, `1`, and `2` by three
 * rules in one model, so every row names its own answer and each rival account predicts a DIFFERENT firing
 * rule on the filled-beside-invalid row:
 *
 * | row | skipping predicts | counted-as-filled predicts | measured |
 * |---|---|---|---|
 * | filled + invalid | `COUNT_1` | `COUNT_2` | **nothing fires** |
 * | filled + empty | `COUNT_1` | `COUNT_1` | `COUNT_1` |
 * | filled + filled | `COUNT_2` | `COUNT_2` | `COUNT_2` |
 * | empty + empty | `COUNT_0` | `COUNT_0` | `COUNT_0` |
 *
 * The three non-invalid rows are each other's controls: each fires exactly the rule naming its own count, so
 * the silent row is an informative negative rather than a fixture that never fires. The all-empty row also
 * carries its own delta — emptiness and invalidity are two states in this operator, not one: an all-empty list
 * counts `0` and compares normally instead of answering unavailable.
 *
 * Tri-checked on dynamic Groovy, generated Java, and the interpreter.
 */
class FilledCountInvalidOperandDiffTest {

    @Test
    fun aFilledOperandBesideAnInvalidOneFiresNoCountAtAll() {
        assertFired(incidentOn = "2024-02-30", payout = "100", expected = emptySet())
    }

    @Test
    fun aFilledOperandBesideAnEmptyOneCountsOne() {
        assertFired(incidentOn = "2024-06-15", payout = null, expected = setOf("COUNT_1"))
    }

    @Test
    fun twoFilledOperandsCountTwo() {
        assertFired(incidentOn = "2024-06-15", payout = "100", expected = setOf("COUNT_2"))
    }

    /** Emptiness is a SECOND state, not the same one: an all-empty list counts 0 and compares normally. */
    @Test
    fun twoEmptyOperandsCountZeroRatherThanAnsweringUnavailable() {
        assertFired(incidentOn = null, payout = null, expected = setOf("COUNT_0"))
    }

    private fun assertFired(incidentOn: String?, payout: String?, expected: Set<String>) {
        val kernelDoc = RuntimeLaws.doc().group(ROOT, 1).field(NOTE, "seen", 1, 1)
        if (incidentOn != null) kernelDoc.field(INCIDENT_ON, incidentOn, 1, 1)
        if (payout != null) kernelDoc.field(PAYOUT, payout, 1, 1)

        val clue = "incidentOn=$incidentOn payout=$payout"
        val dynamic = ours(RuntimeLaws.messages(MODEL, kernelDoc).map { it.code() })
        val generated = ours(JAVA_ENGINE.messages(JAVA_ENGINE.instance(kernelDoc)).map { it.code() })
        assertEquals(expected, dynamic, "$clue — dynamic-Groovy")
        assertEquals(dynamic, generated, "$clue — generated Java")

        val definition = ModelLoader.load(MODEL)
        val interpretedDoc = definition.newDocument().group(ROOT, 1).field(NOTE, "seen", 1, 1)
        if (incidentOn != null) interpretedDoc.field(INCIDENT_ON, incidentOn, 1, 1)
        if (payout != null) interpretedDoc.field(PAYOUT, payout, 1, 1)
        val interpreted = ours(DmInterpreter(definition).validateFull(interpretedDoc).map { it.code })
        assertEquals(dynamic, interpreted, "$clue — interpreter")
    }

    private fun ours(codes: List<String>): Set<String> = codes.filter { it.startsWith(PREFIX) }.toSet()

    private companion object {
        const val ROOT = "/Claim"
        const val NOTE = "$ROOT/Note"
        const val INCIDENT_ON = "$ROOT/IncidentOn"
        const val PAYOUT = "$ROOT/Payout"
        const val PREFIX = "COUNT_"

        val MODEL: String = run {
            val base = DocumentModelDraft.named("motor-claim").locale("en_US")
                .rootGroup("Claim") { group ->
                    group.stringField("Note") { }
                    group.dateField("IncidentOn") { it.format("yyyy-MM-dd") }
                    group.numberField("Payout") { it.fractionDigits(0) }
                }
                .build().toJson()
            var model = BoundModel.ofJson(base)
            for (expected in 0..2) {
                val count = Dsl.numberOfFilledFields(model.field(INCIDENT_ON), model.field(PAYOUT))
                model = model.add(
                    model.rule(model.group(ROOT)).name("Count$expected")
                        .errorField(model.field(INCIDENT_ON))
                        .`when`(count.eq(expected.toLong()))
                        .code("$PREFIX$expected").severity(Severity.ERROR)
                        .message(Dsl.en("filled count is $expected"))
                        .build(),
                )
            }
            model.toJson()
        }

        val JAVA_ENGINE: RuntimeLaws.JavaKernelEngine = RuntimeLaws.kernelJavaEngine(MODEL)
    }
}
