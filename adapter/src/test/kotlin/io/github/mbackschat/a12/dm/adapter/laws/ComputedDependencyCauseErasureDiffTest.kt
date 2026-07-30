package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §11 / SPEC-2026-07-26-04 — once a computed target is invalid, a dependent read observes only that target's
 * invalid membership. An ERRORED producer and a producer poisoned by an invalid operand therefore give their
 * readers the same cause-blind CLEARED result; neither the producer class nor its originating message crosses
 * the dependency edge. A clean unselected producer stays distinct: it reads empty-as-zero downstream.
 */
class ComputedDependencyCauseErasureDiffTest {

    private val model = run {
        val base = BoundModel.ofJson(
            DocumentModelDraft.named("claim-projections").locale("en_US")
                .rootGroup("Claim") { claim ->
                    claim.numberField("MalformedInput") { it.fractionDigits(0) }
                        .numberField("SelectionGate") { it.fractionDigits(0) }
                        .numberField("RejectedProjection") { it.fractionDigits(0).max("9") }
                        .numberField("PoisonedProjection") { it.fractionDigits(0) }
                        .numberField("CleanEmptyProjection") { it.fractionDigits(0) }
                        .numberField("FromRejected") { it.fractionDigits(0) }
                        .numberField("FromPoisoned") { it.fractionDigits(0) }
                        .numberField("FromCleanEmpty") { it.fractionDigits(0) }
                }
                .build().toJson(),
        )
        val computations = listOf(
            base.computation(base.num(REJECTED)).name("RejectedProjectionComputation")
                .calc(NumberLiteral.of("10")).message(Dsl.en("Rejected projection.")).build(),
            base.computation(base.num(POISONED)).name("PoisonedProjectionComputation")
                .calc(base.num(MALFORMED).value()).message(Dsl.en("Poisoned projection.")).build(),
            base.computation(base.num(CLEAN_EMPTY)).name("CleanEmptyProjectionComputation")
                .commonPrecondition(base.num(SELECTION_GATE).filled())
                .calc(base.num(MALFORMED).value()).message(Dsl.en("Clean empty projection.")).build(),
            copy(base, FROM_REJECTED, REJECTED),
            copy(base, FROM_POISONED, POISONED),
            copy(base, FROM_CLEAN_EMPTY, CLEAN_EMPTY),
        )
        base.addAll(emptyList(), computations).toJson()
    }

    @Test
    fun invalidProducerClassesCollapseToOneDependentObservation() {
        val dynamic = RuntimeLaws.computeWithFormalErrors(model, document())
        val java = RuntimeLaws.kernelJavaEngine(model)
        val generated = java.computeWithFormalErrors(java.instance(document()))
        val definition = ModelLoader.load(model)
        val interpreted = DmInterpreter(definition).compute(
            definition.newDocument().group(ROOT, 1)
                .field(MALFORMED, "1.5", 1, 1)
                .seedTargets(),
        ).associate { computed ->
            computed.path to when (computed.outcome) {
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> "VALUE|${computed.value}"
            }
        }

        val expected = mapOf(
            REJECTED to "ERRORED",
            POISONED to "CLEARED",
            CLEAN_EMPTY to "CLEARED",
            FROM_REJECTED to "CLEARED",
            FROM_POISONED to "CLEARED",
            FROM_CLEAN_EMPTY to "VALUE|0",
        )
        assertEquals(expected, signatures(dynamic), "dynamic-Groovy kernel")
        assertEquals(expected, signatures(generated), "generated-Java kernel")
        assertEquals(expected, interpreted, "kernel-free interpreter")

        assertTrue(
            dynamic.formalErrorsInOperands().isNotEmpty(),
            "the malformed source must remain visible in the original formal-error inventory",
        )
        assertTrue(
            dynamic.formalErrorsInOperands().all { it.field().contains("MalformedInput") },
            "the formal-error inventory must retain only the originating input error, never a dependent cause: " +
                dynamic.formalErrorsInOperands(),
        )
        assertEquals(
            dynamic.formalErrorsInOperands().map { "${it.code()}|${it.field()}" },
            generated.formalErrorsInOperands().map { "${it.code()}|${it.field()}" },
            "both kernel strategies expose the same cause-free dependent result and original-input inventory",
        )
    }

    private fun document(): RuntimeLaws.Doc = RuntimeLaws.doc().group(ROOT, 1)
        .field(MALFORMED, "1.5", 1, 1)
        .apply {
            TARGETS.forEach { field(it, "7", 1, 1) }
        }

    private fun io.github.mbackschat.a12.dm.interpreter.eval.Document.seedTargets() = apply {
        TARGETS.forEach { field(it, "7", 1, 1) }
    }

    private fun signatures(detail: RuntimeLaws.ComputationDetail): Map<String, String> =
        detail.outcomes().associate { computed ->
            computed.path() to when {
                computed.cleared() -> "CLEARED"
                computed.errored() -> "ERRORED"
                else -> "VALUE|${computed.value().orElse("")}"
            }
        }

    private fun copy(model: BoundModel, target: String, source: String) =
        model.computation(model.num(target)).name("${target.substringAfterLast('/')}Computation")
            .calc(model.num(source).value()).message(Dsl.en("Derived projection.")).build()

    private companion object {
        const val ROOT = "/Claim"
        const val MALFORMED = "$ROOT/MalformedInput"
        const val SELECTION_GATE = "$ROOT/SelectionGate"
        const val REJECTED = "$ROOT/RejectedProjection"
        const val POISONED = "$ROOT/PoisonedProjection"
        const val CLEAN_EMPTY = "$ROOT/CleanEmptyProjection"
        const val FROM_REJECTED = "$ROOT/FromRejected"
        const val FROM_POISONED = "$ROOT/FromPoisoned"
        const val FROM_CLEAN_EMPTY = "$ROOT/FromCleanEmpty"
        val TARGETS = listOf(REJECTED, POISONED, CLEAN_EMPTY, FROM_REJECTED, FROM_POISONED, FROM_CLEAN_EMPTY)
    }
}
