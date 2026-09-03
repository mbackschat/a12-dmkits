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
 * Tri-engine differential — a year-omitting date constant on the temporal-OPERATION carrier, the third and last
 * place this project's date canonicalizer is reached from.
 *
 * **Why a third file.** The canonicalizer has exactly three callers. The assignment carrier is
 * `YearlessConstantMonthStoreDiffTest` and the comparison carrier is `YearlessConstantComparisonDiffTest`; both
 * got the target's or counterpart's declared format when `IF320`/`IF321` were fixed. The third caller — the
 * temporal leaf that feeds `DifferenceInDays`, `AddDays` and the date aggregates — kept the defaulted
 * parameter, and its reachability was ASSERTED rather than measured. It is reachable, and this file is what
 * establishes it.
 *
 * **The static picture has two gates, and only one of them is the constant gate**
 * (`rulekit.validate.laws.TemporalConstantAdmissionGateLawsTest`). A year-free FIELD cannot be a temporal
 * operation's operand at all — `MVK_WRONG_DATE_FORMAT_FOR_OP`, which refuses the year-carrying literal
 * identically, so it is reading the field. A year-free LITERAL is admitted once no year-free field is in the
 * operation: as a bare difference over two constants, and when it reaches the operation through a shift. Those
 * are the two shapes measured here.
 *
 * **Both ladders are two-sided so an absent value cannot pass for a direction.** A comparison whose operand
 * never resolved answers UNKNOWN and fires neither rule of a pair; a resolved one fires exactly one. Testing
 * only the arm that happens to be true would let "no value at all" masquerade as a correct answer, which is
 * precisely the failure this carrier had.
 *
 * Own domain (travel booking).
 */
// One instance per CLASS: the model JSON, the loaded definition and the kernel Java engine are fields, and this
// Gradle task also carries timing assertions — per-method lifecycle would redo codegen for every case.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YearlessConstantOperationDiffTest {

    private companion object {
        const val ITINERARY = "/Itinerary"
        const val LOCATOR = "/Itinerary/Locator"
        const val DEPARTS = "/Itinerary/Departs"
    }

    private val base = DocumentModelDraft.named("travel-itinerary-month-operation").locale("en_US")
        .rootGroup("Itinerary", Consumer { root: GroupBuilder ->
            root.stringField("Locator", Consumer { })
            root.dateField("Departs", Consumer<DateFieldBuilder> { it.format("MM") })
        }).build().toJson()

    private val bound = BoundModel.ofJson(base)

    private fun rule(code: String, condition: io.github.mbackschat.a12.dm.rulekit.dsl.Condition) =
        bound.rule(bound.group(ITINERARY)).name("${code}Rule").errorField(bound.str(LOCATOR))
            .`when`(Dsl.fieldFilled(bound.field(LOCATOR)).and(condition))
            .code(code).severity(Severity.ERROR).message(Dsl.en(code)).build()

    /** `DifferenceInDays("05.03.", "07.04.")` — two yearless literals, no field in the operation at all. */
    private fun difference(positive: Boolean) = rule(
        if (positive) "DIFFPOS" else "DIFFNEG",
        Dsl.differenceInDays(Dsl.dateValue("05.03."), Dsl.dateValue("07.04."))
            .let { if (positive) it.gt(0) else it.lt(0) },
    )

    /** `[Departs] </> AddDays("05.03.", 3)` — the literal reaching the operation through a shift. */
    private fun shifted(after: Boolean) = rule(
        if (after) "SHIFTAFTER" else "SHIFTBEFORE",
        Dsl.addDays(Dsl.dateValue("05.03."), 3).let {
            if (after) bound.date(DEPARTS).after(it) else bound.date(DEPARTS).before(it)
        },
    )

    /**
     * `DifferenceInDays("28.02.", "01.03.")` is 1 day in a common year and 2 in a leap year, so this pair
     * OBSERVES the reference year a yearless literal is anchored to — the thing the other two carriers cannot
     * see, and the reason this file must pin it rather than pick a plausible anchor.
     */
    private fun leap(above: Boolean) = rule(
        if (above) "LEAPGT1" else "LEAPLT2",
        Dsl.differenceInDays(Dsl.dateValue("28.02."), Dsl.dateValue("01.03."))
            .let { if (above) it.gt(1) else it.lt(2) },
    )

    /** Fires unconditionally on a filled locator — proves the harness sees this model's rules at all. */
    private val liveness = bound.rule(bound.group(ITINERARY)).name("LiveRule").errorField(bound.str(LOCATOR))
        .`when`(Dsl.fieldFilled(bound.field(LOCATOR)))
        .code("LIVE").severity(Severity.ERROR).message(Dsl.en("LIVE")).build()

    private val rules = listOf(
        difference(positive = true),
        difference(positive = false),
        shifted(after = true),
        shifted(after = false),
        leap(above = true),
        leap(above = false),
        liveness,
    )

    private val modelJson = rules.fold(base) { json, r -> TypedRules.inject(json, r) }
    private val definition = ModelLoader.load(modelJson)
    private val staticEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    @Test
    fun theProbeModelIsAdmitted() =
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(modelJson),
            "the kernel admits a yearless literal inside a temporal operation with no Base Year")

    /**
     * Each pair fires exactly one arm, so the literal was resolved to a real calendar date on all three shapes
     * rather than left as raw text — which is what the interpreter did before 2026-09-03, firing NEITHER arm of
     * any pair while the kernel fired one of each (IF322).
     *
     * <p>`LEAPGT1` is the row that pins the anchor: 28 February to 1 March is two days, so the reference year a
     * yearless literal borrows is a LEAP year on both kernel strategies. It pins the PARITY and not the year —
     * the direct route, reading the borrowed year back with `YearFromDate`, is refused outright because that
     * extractor admits no constant operand at all (`MVK_NO_CONST`, in the laws test). Any leap year satisfies
     * what is measured here.
     */
    @Test
    fun aYearlessLiteralIsResolvedOnTheOperationCarrier() =
        assertEquals(setOf("DIFFPOS", "LEAPGT1", "LIVE", "SHIFTAFTER"), fired(),
            "the difference is positive, July is after the shifted 8 March, and the anchor year is a leap year")

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

    private fun kernelDocument(): RuntimeLaws.Doc = RuntimeLaws.doc().group(ITINERARY, 1)
        .field(LOCATOR, "TRV-7781", 1, 1)
        .field(DEPARTS, "07", 1, 1)

    private fun interpreterDocument(): Document = definition.newDocument().group(ITINERARY, 1)
        .field(LOCATOR, "TRV-7781", 1, 1)
        .field(DEPARTS, "07", 1, 1)
}
