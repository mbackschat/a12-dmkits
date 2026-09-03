package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DateFieldBuilder
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.function.Consumer

/**
 * Tri-engine differential — a year-omitting date constant on the COMPARISON carrier, against a year-free DATE
 * field, with no Base Year declared. `rulekit.validate.laws.TemporalConstantAdmissionGateLawsTest` measures that
 * the kernel ADMITS this shape (both operands are year-free, so the year-agreement gate has nothing to refuse);
 * this file measures what the three engines then do with it.
 *
 * **Why it is a separate file from the store.** `YearlessConstantMonthStoreDiffTest` covers the ASSIGNMENT
 * carrier, where the target's declared format is available to resolve the literal. A comparison has no target;
 * the counterpart operand is a field, and whether its declared format reaches the literal is a different
 * question with a different answer. A cold review named exactly this gap on 2026-09-03, on the grounds that the
 * assignment fix was shaped as a defaulted parameter and therefore could not force the sibling call sites to be
 * revisited.
 *
 * **The ladder is two-sided so a lexicographic accident cannot pass for a date comparison.** `MonthInput` holds
 * `07`; against the literal `05.03.` a date reading answers "after", and against `09.11.` it answers "before".
 * A comparison that never resolved the literal at all would answer the same way on both only by coincidence.
 *
 * Own domain (travel booking).
 */
// One instance per CLASS: the model JSON, the loaded definition and the kernel Java engine are fields, and this
// Gradle task also carries timing assertions — per-method lifecycle would redo codegen for every case.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YearlessConstantComparisonDiffTest {

    private companion object {
        const val BOOKING = "/Booking"
        const val REFERENCE = "/Booking/Reference"
        const val MONTH_INPUT = "/Booking/MonthInput"
    }

    private val base = DocumentModelDraft.named("travel-booking-month-compare").locale("en_US")
        .rootGroup("Booking", Consumer { root: GroupBuilder ->
            root.stringField("Reference", Consumer { })
            root.dateField("MonthInput", Consumer<DateFieldBuilder> { it.format("MM") })
        }).build().toJson()

    private val bound = BoundModel.ofJson(base)

    private fun rule(code: String, literal: String, after: Boolean) =
        bound.rule(bound.group(BOOKING)).name("${code}Rule").errorField(bound.str(REFERENCE))
            .`when`(
                Dsl.fieldFilled(bound.field(REFERENCE)).and(
                    if (after) bound.date(MONTH_INPUT).after(Dsl.dateValue(literal))
                    else bound.date(MONTH_INPUT).before(Dsl.dateValue(literal)),
                ),
            )
            .code(code).severity(Severity.ERROR).message(Dsl.en(code)).build()

    private val rules = listOf(
        rule("AFTERMARCH", "05.03.", after = true),
        rule("BEFOREMARCH", "05.03.", after = false),
        rule("AFTERNOV", "09.11.", after = true),
        rule("BEFORENOV", "09.11.", after = false),
    )

    private val modelJson = rules.fold(base) { json, r -> TypedRules.inject(json, r) }
    private val definition = ModelLoader.load(modelJson)
    private val staticEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    @Test
    fun theProbeModelIsAdmitted() =
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(modelJson),
            "the kernel admits a yearless literal against a year-free field with no Base Year")

    /** July against March and November: a date reading fires exactly one rule of each pair. */
    @Test
    fun aYearlessLiteralIsResolvedOnTheComparisonCarrier() =
        assertEquals(setOf("AFTERMARCH", "BEFORENOV"), fired(),
            "July is after 5 March and before 9 November")

    // ---- the instrument ----

    private fun fired(): Set<String> {
        val codes = rules.map { it.code() }.toSet()
        val dynamic = RuntimeLaws.messages(modelJson, kernelDocument())
            .map { it.code() }.filter { it in codes }.toSortedSet()
        val static = staticEngine.messages(staticEngine.instance(kernelDocument()))
            .map { it.code() }.filter { it in codes }.toSortedSet()
        val interpreter = DmInterpreter(definition).validateFull(interpreterDocument())
            .map { it.code }.filter { it in codes }.toSortedSet()
        assertEquals(dynamic, static, "generated Java vs normative dynamic-Groovy")
        assertEquals(dynamic, interpreter, "interpreter vs normative dynamic-Groovy")
        return dynamic
    }

    private fun kernelDocument(): RuntimeLaws.Doc = RuntimeLaws.doc().group(BOOKING, 1)
        .field(REFERENCE, "TRV-9004", 1, 1)
        .field(MONTH_INPUT, "07", 1, 1)

    private fun interpreterDocument(): Document = definition.newDocument().group(BOOKING, 1)
        .field(REFERENCE, "TRV-9004", 1, 1)
        .field(MONTH_INPUT, "07", 1, 1)
}
