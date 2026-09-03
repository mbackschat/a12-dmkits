package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tri-engine differential — the TWO CLEAR-KINDS part ways at a plain operand read, and poison crosses a join.
 * a12-kernel-lean's `SPEC-2026-09-03-09` extends the cascade read-rules, which this repository had measured only
 * for reads reaching INSIDE a downstream filter or precondition quantifier (KF's §11 cascade supplement, IF98):
 * they govern an ordinary operand read too, and that is the whole observable difference between the kinds.
 *
 * **The carrier is one four-deep diamond held fixed across producer states** — `Posted = [Base]`,
 * `Left = [Posted]`, `Right = [Alt]`, `Total = [Left] + [Right]` — so every row below changes the producer and
 * nothing else. `Right` never reads `Posted`, which is what makes it the liveness control on every row: a
 * document where the whole plan aborted would take `Right` down with it, and none does.
 *
 * **The three producer states and what separates them.**
 *
 * <ul>
 * <li><b>Precondition-cleared</b>, with no malformed cell anywhere: `Posted` clears, `Left` reads it EMPTY and
 *     answers `0`, and `Total` answers the other arm alone. Empty SUBSTITUTION.</li>
 * <li><b>Source-invalid</b>: `Posted` clears, `Left` is POISONED by the read and clears, and `Total` clears with
 *     it — poison crosses the join rather than being substituted away. `Right` still answers, so the plan did not
 *     abort; one failing branch is not a failing document.</li>
 * <li><b>Absent source</b> is the third leg and the reason the first two are distinguishable at all: an absent
 *     Number operand is the ordinary empty `0`, so `Posted` answers `0` and the diamond stays whole. Without it,
 *     "empty substitution" and "poison" would be told apart only by appeal to the join's shape.</li>
 * </ul>
 *
 * **The two clear-kinds are indistinguishable on the PRODUCER's own reported state** — both surface as cleared
 * and not errored — so a consumer that predicts propagation from the target's `errored` flag is wrong on the
 * source-invalid row. The dependents' outcomes are the only channel that separates them here.
 *
 * **The stale rows are the control for WHEN the clear becomes visible.** Every computed target is seeded, so a
 * clear is reported rather than absent; on the precondition rows `Posted` carries a stale value that a consumer
 * clearing at application time would let its dependent read. `Left` answers `0` on both seeds, and the two seeds
 * differ, so the answer is the cleared state rather than a coincidence of one number.
 *
 * **A bare constant operation honours a precondition too** — `Bonus` is a literal with the same guard, and it
 * writes when the guard is met and clears when it is not. `BonusEcho` reads it, so the second half of that
 * sentence carries its own witness rather than riding the first: on the precondition rows `Bonus` clears and
 * `BonusEcho` answers `0`.
 *
 * Own domain (e-commerce settlement ledger).
 */
// One instance per CLASS: the model JSON, the loaded definition and the kernel Java engine are fields, and this
// Gradle task also carries timing assertions — per-method lifecycle would redo codegen for every case.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DependencyClearKindDiffTest {

    private companion object {
        const val LEDGER = "/Ledger"
        const val GATE = "/Ledger/Gate"
        const val BASE = "/Ledger/Base"
        const val ALT = "/Ledger/Alt"
        const val POSTED = "/Ledger/Posted"
        const val LEFT = "/Ledger/Left"
        const val RIGHT = "/Ledger/Right"
        const val TOTAL = "/Ledger/Total"
        const val BONUS = "/Ledger/Bonus"
        const val BONUS_ECHO = "/Ledger/BonusEcho"

        const val MALFORMED = "pending review"
        const val SEED = "999"
    }

    private val base = DocumentModelDraft.named("settlement-ledger-cascade").locale("en_US")
        .rootGroup("Ledger") { ledger ->
            ledger.stringField("Gate") { }
            for (name in listOf("Base", "Alt", "Posted", "Left", "Right", "Total", "Bonus", "BonusEcho")) {
                ledger.numberField(name) { it.fractionDigits(0) }
            }
        }.build().toJson()

    private val bound = BoundModel.ofJson(base)

    /** The guard both precondition-bearing computations share: the gate field carries a value. */
    private fun guard() = Dsl.fieldFilled(bound.field(GATE))

    private fun computation(target: String, name: String, guarded: Boolean, calc: () -> Computation.Builder) =
        calc().let { b -> if (guarded) b.commonPrecondition(guard()) else b }
            .name(name).message(Dsl.en("A settlement ledger step.")).build()

    private val computations = listOf(
        // Guarded, so the SAME producer can be cleared by an unmet precondition or poisoned by its source.
        computation(POSTED, "PostedComp", guarded = true) {
            bound.computation(bound.num(POSTED)).calc(bound.num(BASE).asNumber().expression())
        },
        computation(LEFT, "LeftComp", guarded = false) {
            bound.computation(bound.num(LEFT)).calc(bound.num(POSTED).asNumber().expression())
        },
        computation(RIGHT, "RightComp", guarded = false) {
            bound.computation(bound.num(RIGHT)).calc(bound.num(ALT).asNumber().expression())
        },
        computation(TOTAL, "TotalComp", guarded = false) {
            bound.computation(bound.num(TOTAL))
                .calc(bound.num(LEFT).asNumber().plus(bound.num(RIGHT).asNumber()).expression())
        },
        // A bare constant under the same guard — nothing is read, so only the precondition can decide it.
        computation(BONUS, "BonusComp", guarded = true) {
            bound.computation(bound.num(BONUS)).calc(Dsl.number(4).expression())
        },
        // The READER for that constant. Without it the "its clear is read as empty by the same rule" half has no
        // witness: `Bonus` would be a target nothing consumes, so only the writing half would be measured.
        computation(BONUS_ECHO, "BonusEchoComp", guarded = false) {
            bound.computation(bound.num(BONUS_ECHO)).calc(bound.num(BONUS).asNumber().expression())
        },
    )

    private val modelJson = computations.fold(base) { json, c -> TypedRules.inject(json, c) }
    private val definition = ModelLoader.load(modelJson)
    private val staticEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    @Test
    fun theProbeModelIsAdmitted() =
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(modelJson),
            "a refused model would make every row below vacuous")

    /**
     * The whole matrix, asserted at once so a divergence names every affected document. `Right` answers `5` on
     * every row, which is the liveness control; `Total` is where the two clear-kinds separate.
     */
    @Test
    fun aPreconditionClearReadsEmptyWhileASourceInvalidClearPoisonsAcrossTheJoin() {
        val observed = linkedMapOf(
            "clean" to observe(gate = "on", base = "7", postedSeed = SEED),
            "preconditionUnmet" to observe(gate = null, base = "7", postedSeed = SEED),
            "preconditionUnmetStaleProducer" to observe(gate = null, base = "7", postedSeed = "99"),
            "sourceInvalid" to observe(gate = "on", base = MALFORMED, postedSeed = SEED),
            "sourceAbsent" to observe(gate = "on", base = null, postedSeed = SEED),
        )
        val actual = observed.mapValues { it.value.dynamic }
        assertEquals(actual, observed.mapValues { it.value.static },
            "generated Java vs normative dynamic-Groovy")
        assertEquals(actual, observed.mapValues { it.value.interpreter },
            "interpreter vs normative dynamic-Groovy")
        assertEquals(
            linkedMapOf(
                "clean" to "Posted=7 Left=7 Right=5 Total=12 Bonus=4 BonusEcho=4",
                "preconditionUnmet" to "Posted=CLEARED Left=0 Right=5 Total=5 Bonus=CLEARED BonusEcho=0",
                "preconditionUnmetStaleProducer" to
                    "Posted=CLEARED Left=0 Right=5 Total=5 Bonus=CLEARED BonusEcho=0",
                "sourceInvalid" to "Posted=CLEARED Left=CLEARED Right=5 Total=CLEARED Bonus=4 BonusEcho=4",
                "sourceAbsent" to "Posted=0 Left=0 Right=5 Total=5 Bonus=4 BonusEcho=4",
            ),
            actual,
            "a precondition clear reads EMPTY at a plain operand and a source-invalid clear POISONS; the poison " +
                "crosses the join while the independent arm keeps answering",
        )
    }

    /**
     * The producer's own reported state on the two clearing rows, which is what a consumer would read if it tried
     * to predict propagation from the target rather than from the read. It is the SAME on both — cleared and not
     * errored — so that channel cannot carry the distinction, and a consumer keying off `errored` is wrong on the
     * source-invalid row.
     */
    @Test
    fun theProducersOwnReportedStateDoesNotCarryTheDistinction() = assertEquals(
        mapOf(
            "preconditionUnmet" to "cleared=true errored=false",
            "sourceInvalid" to "cleared=true errored=false",
        ),
        mapOf(
            "preconditionUnmet" to producerState(gate = null, base = "7"),
            "sourceInvalid" to producerState(gate = "on", base = MALFORMED),
        ),
        "both clear-kinds surface identically on the producer; only the dependents' outcomes tell them apart",
    )

    /**
     * **The answer to a12-kernel-lean's instrument request**, which asks whether the probe artifact can separate
     * the two clear-kinds for a producer with NO dependents — the case where the dependents' outcomes, the only
     * channel used above, do not exist.
     *
     * <p>It can, and not by adding anything: the artifact already publishes `formalErrorsInOperands` beside the
     * outcomes. A source-invalid producer's own operand appears in that inventory and a precondition-cleared
     * one's does not, so the discriminator is whether the producer's operand set intersects it. The join is the
     * consumer's to make, deliberately — the channel is an inventory rather than a causal attribution, so it also
     * lists operands of computations that succeeded, and it names no edge to the outcome it explains.
     *
     * <p><b>The residue is real and is the part worth re-aiming the request at.</b> `Left` on the source-invalid
     * row is cleared because the COMPUTED field it read was poisoned, and a computed field is outside this
     * channel's scope by definition — so the inventory names `Base` and says nothing about `Left`. A producer
     * with no dependents that clears for THAT reason is still indistinguishable, and closing it would mean
     * publishing the kernel's per-instance computed-error message, which the artifact does not carry today.
     */
    @Test
    fun theEagerOperandInventorySeparatesTheClearKindsThatTheProducerStateCannot() = assertEquals(
        mapOf(
            "preconditionUnmet" to "",
            "sourceInvalid" to "/Ledger[1]/Base",
            "clean" to "",
        ),
        mapOf(
            "preconditionUnmet" to operandInventory(gate = null, base = "7"),
            "sourceInvalid" to operandInventory(gate = "on", base = MALFORMED),
            "clean" to operandInventory(gate = "on", base = "7"),
        ),
        "the source-invalid clear names its own operand where the precondition clear names nothing; the " +
            "downstream clear of Left names nothing either, since its poisoned source is a COMPUTED field",
    )

    // ---- the instrument ----

    private data class Observation(val dynamic: String, val static: String, val interpreter: String)

    private val reported = listOf(POSTED, LEFT, RIGHT, TOTAL, BONUS, BONUS_ECHO)

    private fun observe(gate: String?, base: String?, postedSeed: String): Observation {
        val dynamic = RuntimeLaws.compute(modelJson, kernelDocument(gate, base, postedSeed))
            .associate { it.path() to signature(it.cleared(), it.errored(), it.value().orElse("")) }
        val static = staticEngine.computeOutcomesSorted(
            staticEngine.instance(kernelDocument(gate, base, postedSeed)),
        ).associate {
            it.substringBefore('|') to when (val v = it.substringAfter('|')) {
                "ERR" -> "ERRORED"; "CLEARED" -> "CLEARED"; else -> v
            }
        }
        val interpreter = DmInterpreter(definition)
            .compute(interpreterDocument(gate, base, postedSeed)).associate {
                it.path to when (it.outcome) {
                    ComputeOutcome.CLEARED -> "CLEARED"
                    ComputeOutcome.ERRORED -> "ERRORED"
                    ComputeOutcome.VALUE -> it.value.orEmpty()
                }
            }
        return Observation(render(dynamic), render(static), render(interpreter))
    }

    /** `Posted=… Left=… …` in the diamond's own order, so a divergence reads as a row rather than as a map. */
    private fun render(outcomes: Map<String, String>): String =
        reported.joinToString(" ") { "${it.substringAfterLast('/')}=${outcomes[it] ?: "ABSENT"}" }

    private fun signature(cleared: Boolean, errored: Boolean, value: String): String = when {
        cleared -> "CLEARED"
        errored -> "ERRORED"
        else -> value
    }

    /** The eager pre-computation operand inventory, as a sorted comma-joined field list (empty when none). */
    private fun operandInventory(gate: String?, base: String?): String =
        RuntimeLaws.computeWithFormalErrors(modelJson, kernelDocument(gate, base, SEED))
            .formalErrorsInOperands().map { it.field() }.distinct().sorted().joinToString(",")

    /** The producer's raw `cleared`/`errored` pair on the dynamic engine — the channel the entry says is blind. */
    private fun producerState(gate: String?, base: String?): String =
        RuntimeLaws.compute(modelJson, kernelDocument(gate, base, SEED)).single { it.path() == POSTED }
            .let { "cleared=${it.cleared()} errored=${it.errored()}" }

    private fun kernelDocument(gate: String?, base: String?, postedSeed: String): RuntimeLaws.Doc {
        val doc = RuntimeLaws.doc().group(LEDGER, 1).field(ALT, "5", 1, 1)
        gate?.let { doc.field(GATE, it, 1, 1) }
        base?.let { doc.field(BASE, it, 1, 1) }
        doc.field(POSTED, postedSeed, 1, 1)
        for (path in listOf(LEFT, RIGHT, TOTAL, BONUS)) doc.field(path, SEED, 1, 1)
        return doc
    }

    private fun interpreterDocument(gate: String?, base: String?, postedSeed: String): Document {
        val doc = definition.newDocument().group(LEDGER, 1).field(ALT, "5", 1, 1)
        gate?.let { doc.field(GATE, it, 1, 1) }
        base?.let { doc.field(BASE, it, 1, 1) }
        doc.field(POSTED, postedSeed, 1, 1)
        for (path in listOf(LEFT, RIGHT, TOTAL, BONUS)) doc.field(path, SEED, 1, 1)
        return doc
    }
}
