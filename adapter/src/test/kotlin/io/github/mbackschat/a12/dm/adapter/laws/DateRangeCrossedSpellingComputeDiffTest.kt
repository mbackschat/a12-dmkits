package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — a DATE_RANGE computation whose source list and target are two different SPELLINGS of one
 * YEARLESS component set: a `dd.MM`+`-` list feeding an `MM-dd`+`/` target, both `{MONTH, DAY}`
 * (SPEC-2026-08-23-03).
 *
 * **Why this is not covered by the crossing already locked.** `DateRangeFirstFilledProfileDiffTest`'s crossed
 * row runs `dd.MM.yyyy` into `yyyy-MM-dd`, which is the `{YEAR, MONTH, DAY}` pair. The yearless sets are the
 * ones a one-spelling-per-component-set implementation cannot even express. Getting the render wrong here stores
 * `15.01-20.02` where the kernel stores `01-15/02-20`, silently.
 *
 * On kernel 30.8.1 the crossing ran out of the halved `MM`+`""` into `MM`+`/`. On 31.1.1 an empty separator reads as
 * `/` (KERNEL-FINDINGS KF658), so that pair is one spelling, and the class was re-expressed on the one yearless set
 * that still has two.
 *
 * The direct two-field list is deliberate: it puts the crossing on a NON-FIRST slot in
 * [theSecondSourceIsReachedAndStillRendersInTheTargetSpelling], which a starred column cannot separate from
 * ordinary row order.
 *
 * Own domain (e-commerce promotion windows).
 */
class DateRangeCrossedSpellingComputeDiffTest {

    private val json: String = run {
        val base = DocumentModelDraft.named("promo-crossed").locale("en_US")
            .rootGroup("Promo") { promo ->
                promo.stringField("Campaign") { }
                promo.dateRangeField("PrimaryRun") { it.format("dd.MM").separator("-") }
                promo.dateRangeField("FallbackRun") { it.format("dd.MM").separator("-") }
                promo.dateRangeField("EffectiveRun") { it.format("MM-dd").separator("/") }
            }.build().toJson()
        val m = BoundModel.ofJson(base)
        val operation = RuntimeLaws.render(ROOT, 
            Dsl.firstFilledDateRange(
                m.dateRange("$ROOT/PrimaryRun").field(),
                m.dateRange("$ROOT/FallbackRun").field(),
            ).expression(),
        )
        RuntimeLaws.withComputation(base, ROOT, "EffectiveRunComp", "../EffectiveRun", operation)
    }

    /** The admission half, asserted rather than inferred from codegen succeeding. */
    @Test
    fun theCrossedYearlessSpellingIsKernelAdmitted() =
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(json),
            "the kernel admits a day-month list assigned to a month-day target")

    @Test
    fun theTargetSpellingRendersAValueTakenFromTheFirstSource() =
        agree("VALUE|06-01/09-30", primary = "01.06-30.09")

    @Test
    fun theSecondSourceIsReachedAndStillRendersInTheTargetSpelling() =
        agree("VALUE|01-15/02-20", primary = null, fallback = "15.01-20.02")

    /** The vacuity control: this fixture CAN report something other than a value. */
    @Test
    fun anAllEmptyListClearsASeededTarget() = agree("CLEARED", seed = "02-03/04-05")

    private fun agree(expected: String, primary: String? = null, fallback: String? = null, seed: String? = null) {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1)
        seed?.let { kernelDocument.field(TARGET, it, 1, 1) }
        primary?.let { kernelDocument.field("$ROOT/PrimaryRun", it, 1, 1) }
        fallback?.let { kernelDocument.field("$ROOT/FallbackRun", it, 1, 1) }
        val dynamic = signature(RuntimeLaws.compute(json, kernelDocument).firstOrNull { it.path() == TARGET })
        val engine = RuntimeLaws.kernelJavaEngine(json)
        val generated = signature(engine.compute(engine.instance(kernelDocument)).firstOrNull { it.path() == TARGET })

        val definition = ModelLoader.load(json)
        val document = definition.newDocument().group(ROOT, 1)
        seed?.let { document.field(TARGET, it, 1, 1) }
        primary?.let { document.field("$ROOT/PrimaryRun", it, 1, 1) }
        fallback?.let { document.field("$ROOT/FallbackRun", it, 1, 1) }
        val computed = DmInterpreter(definition).compute(document).firstOrNull { it.path == TARGET }
        val interpreted = when (computed?.outcome) {
            null -> "NONE"
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED|${computed.value}"
            ComputeOutcome.VALUE -> "VALUE|${computed.value}"
        }

        assertEquals(expected, dynamic, "normative dynamic Groovy")
        assertEquals(dynamic, generated, "generated Java")
        assertEquals(dynamic, interpreted, "interpreter")
    }

    private fun signature(c: RuntimeLaws.Computed?): String = when {
        c == null -> "NONE"
        c.cleared() -> "CLEARED"
        c.errored() -> "ERRORED|${c.value().orElse("")}"
        else -> "VALUE|${c.value().orElse("")}"
    }

    private companion object {
        const val ROOT = "/Promo"
        const val TARGET = "/Promo/EffectiveRun"
    }
}
