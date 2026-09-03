package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DateFieldBuilder
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.function.Consumer

/**
 * Tri-engine differential — the RUNTIME half of a12-kernel-lean's `SPEC-2026-09-03-08`: a yearless date constant
 * assigned to a month-only target is not merely admitted, it stores the month ALONE. The static half — that the
 * admission gate reads temporal family and year agreement rather than component sets — is
 * `rulekit.validate.laws.TemporalConstantAdmissionGateLawsTest`.
 *
 * **Why the stored value matters separately from the admission.** A gate that admitted the constant and then
 * stored a rendered `05.03.` or a year-padded value would satisfy the accept/reject oracle and still be wrong for
 * every consumer that reads the cell back. The store is what says the target's own format governs the write.
 *
 * **The `13.07.` row is the control that the day part is dropped rather than reinterpreted.** Its day is above
 * twelve, so a swapped or component-matched reading has nowhere to put it; the target still stores `07`.
 *
 * **The `MM-dd` row is what makes the account about the TARGET rather than about the literal.** With every
 * target declaring `MM`, "the target's own format governs the write" and "a yearless literal stores its month"
 * are indistinguishable across the whole row set — the confound that no control inside a row can catch, since
 * the format is constant. `MM-dd` is the one other admitted year-free format where the DAY survives, and it
 * stores `03-05`: the format decides, and the borrowed anchor is carried through the render rather than
 * short-circuited.
 *
 * **The borrowed year this project's implementation needs is unobservable, and that is established elsewhere.**
 * A yearless literal must borrow a year to be canonicalized at all; the two shapes that could expose which year
 * are a target that renders one and the day that needs a real one, and the kernel refuses BOTH at admission —
 * `TemporalConstantAdmissionGateLawsTest` measures the year disagreement and the day-validity rows.
 *
 * Own domain (travel booking).
 */
// One instance per CLASS: the model JSON, the loaded definition and the kernel Java engine are fields, and this
// Gradle task also carries timing assertions — per-method lifecycle would redo codegen for every case.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YearlessConstantMonthStoreDiffTest {

    private companion object {
        const val BOOKING = "/Booking"
        const val REFERENCE = "/Booking/Reference"
        const val MONTH_FROM_MARCH = "/Booking/MonthFromMarch"
        const val MONTH_FROM_JULY = "/Booking/MonthFromJuly"
        const val MONTH_DAY = "/Booking/MonthAndDay"
    }

    private val base = DocumentModelDraft.named("travel-booking-month-store").locale("en_US")
        .rootGroup("Booking", Consumer { root: GroupBuilder ->
            root.stringField("Reference", Consumer { })
            root.dateField("MonthFromMarch", Consumer<DateFieldBuilder> { it.format("MM") })
            root.dateField("MonthFromJuly", Consumer<DateFieldBuilder> { it.format("MM") })
            root.dateField("MonthAndDay", Consumer<DateFieldBuilder> { it.format("MM-dd") })
        }).build().toJson()

    private val bound = BoundModel.ofJson(base)

    private fun assign(target: String, name: String, literal: String) =
        bound.computation(bound.date(target).field()).name(name)
            .calc(Dsl.dateValue(literal).expression())
            .message(Dsl.en("The month this booking is anchored to.")).build()

    private val modelJson = listOf(
        assign(MONTH_FROM_MARCH, "MarchAssign", "05.03."),
        assign(MONTH_FROM_JULY, "JulyAssign", "13.07."),
        assign(MONTH_DAY, "MonthDayAssign", "05.03."),
    ).fold(base) { json, c -> TypedRules.inject(json, c) }

    private val definition = ModelLoader.load(modelJson)
    private val staticEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    @Test
    fun theProbeModelIsAdmitted() =
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(modelJson),
            "a refused model would make the stored values below vacuous")

    /** Both targets store the month alone, and the second's day is above twelve so it cannot be a swap. */
    @Test
    fun aYearlessConstantStoresTheMonthAloneInAMonthOnlyTarget() = assertEquals(
        mapOf(MONTH_FROM_MARCH to "VALUE|03", MONTH_FROM_JULY to "VALUE|07", MONTH_DAY to "VALUE|03-05"),
        storedValues(),
        "the target's own format governs the write; neither the day nor a substituted year survives it",
    )

    // ---- the instrument ----

    private fun storedValues(): Map<String, String> {
        val dynamic = RuntimeLaws.compute(modelJson, kernelDocument())
            .associate { it.path() to signature(it.cleared(), it.errored(), it.value().orElse("")) }
        val static = staticEngine.computeOutcomesSorted(staticEngine.instance(kernelDocument()))
            .associate {
                it.substringBefore('|') to when (val v = it.substringAfter('|')) {
                    "ERR" -> "ERRORED"; "CLEARED" -> "CLEARED"; else -> "VALUE|$v"
                }
            }
        val interpreter = DmInterpreter(definition).compute(interpreterDocument()).associate {
            it.path to when (it.outcome) {
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> "VALUE|${it.value}"
            }
        }
        assertEquals(dynamic, static, "generated Java vs normative dynamic-Groovy")
        assertEquals(dynamic, interpreter, "interpreter vs normative dynamic-Groovy")
        return dynamic
    }

    private fun signature(cleared: Boolean, errored: Boolean, value: String): String = when {
        cleared -> "CLEARED"
        errored -> "ERRORED"
        else -> "VALUE|$value"
    }

    /** Both targets seeded, so a kernel clear would be REPORTED rather than absent and could not read as a pass. */
    private fun kernelDocument(): RuntimeLaws.Doc = RuntimeLaws.doc().group(BOOKING, 1)
        .field(REFERENCE, "TRV-8812", 1, 1)
        .field(MONTH_FROM_MARCH, "11", 1, 1)
        .field(MONTH_FROM_JULY, "11", 1, 1)
        .field(MONTH_DAY, "11-30", 1, 1)

    private fun interpreterDocument(): Document = definition.newDocument().group(BOOKING, 1)
        .field(REFERENCE, "TRV-8812", 1, 1)
        .field(MONTH_FROM_MARCH, "11", 1, 1)
        .field(MONTH_FROM_JULY, "11", 1, 1)
        .field(MONTH_DAY, "11-30", 1, 1)
}
