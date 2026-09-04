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
 * **The static picture has several gates, and only one of them is the constant gate**
 * (`rulekit.validate.laws.TemporalConstantAdmissionGateLawsTest`). The operand rule is GRANULARITY: an `MM`
 * field is refused as a `DifferenceInDays` operand (`MVK_WRONG_DATE_FORMAT_FOR_OP`) while an `MM-dd` field is
 * admitted against a yearless literal, and a year-carrying field against a yearless literal refuses under its
 * own code (`MVK_DATE_WITH_AND_WITHOUT_YEAR`). A year-free LITERAL is admitted once the field operand allows
 * it: as a bare difference over two constants, and when it reaches the operation through a shift. Those are the
 * two shapes measured here.
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

    /**
     * The same model three times, differing in EXACTLY ONE builder call — whether a Base Year is declared, and
     * which. A within-model control: two separately authored models differ in every way nobody thought about,
     * and the anchor question is precisely the kind a stray difference would answer for you.
     */
    private fun draftJson(baseYear: Int?): String {
        val b = DocumentModelDraft.named("travel-itinerary-month-operation").locale("en_US")
        if (baseYear != null) b.baseYear(baseYear)
        return b.rootGroup("Itinerary", Consumer { root: GroupBuilder ->
            root.stringField("Locator", Consumer { })
            root.dateField("Departs", Consumer<DateFieldBuilder> { it.format("MM") })
        }).build().toJson()
    }

    private val base = draftJson(null)

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

    /** The identical rule set injected into each of the three models, so only the Base Year differs. */
    private fun modelFor(baseYear: Int?) = rules.fold(draftJson(baseYear)) { json, r -> TypedRules.inject(json, r) }

    private val modelJson = modelFor(null)
    private val definition = ModelLoader.load(modelJson)
    private val staticEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    private val leapYearModel = modelFor(2024)
    private val commonYearModel = modelFor(2023)

    @Test
    fun theProbeModelIsAdmitted() =
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(modelJson),
            "the kernel admits a yearless literal inside a temporal operation with no Base Year")

    /**
     * Each pair fires exactly one arm, so the literal was resolved to a real calendar date on all three shapes
     * rather than left as raw text — which is what the interpreter did before 2026-09-03, firing NEITHER arm of
     * any pair while the kernel fired one of each (IF322).
     *
     * <p>`LEAPGT1` fires here: 28 February to 1 March is two days when NO Base Year is declared. Read
     * [theAnchorIsTheBaseYearWhenOneIsDeclared] before generalizing that — this arm alone cannot tell "the anchor
     * is always a leap year" from "the anchor is the Base Year, and the unconfigured default happens to be leap".
     */
    @Test
    fun aYearlessLiteralIsResolvedOnTheOperationCarrier() =
        assertEquals(setOf("DIFFPOS", "LEAPGT1", "LIVE", "SHIFTAFTER"), fired(),
            "the difference is positive, July is after the shifted 8 March, and the unconfigured anchor is leap")

    /**
     * The axis the row set above holds CONSTANT, and therefore cannot speak for: every one of its rows declares
     * no Base Year. a12-kernel-lean reported on 2026-09-04 that the anchor is the model's Base Year, which their
     * rows can show and ours could not — so this test measures it here rather than citing their report, because
     * a peer's measurement is theirs to hold and this repository's code depends on the answer.
     *
     * <p>Three arms, identical but for one builder call. If the anchor were a fixed leap year the `2023` arm
     * would still answer 2 days; if it is the Base Year, that arm answers 1 and flips the pair.
     */
    @Test
    fun theAnchorIsTheBaseYearWhenOneIsDeclared() = assertEquals(
        mapOf(
            "no Base Year" to setOf("DIFFPOS", "LEAPGT1", "LIVE", "SHIFTAFTER"),
            "Base Year 2024 (leap)" to setOf("DIFFPOS", "LEAPGT1", "LIVE", "SHIFTAFTER"),
            "Base Year 2023 (common)" to setOf("DIFFPOS", "LEAPLT2", "LIVE", "SHIFTAFTER"),
        ),
        mapOf(
            "no Base Year" to fired(),
            "Base Year 2024 (leap)" to firedOn(leapArm),
            "Base Year 2023 (common)" to firedOn(commonArm),
        ),
        "the declared Base Year decides the anchor; only the leap pair moves with it",
    )

    // ---- the instrument ----

    /** One model's three engines, built once per class — codegen is expensive and this task times other work. */
    private inner class Arm(val json: String) {
        val definition = ModelLoader.load(json)
        val engine by lazy { RuntimeLaws.kernelJavaEngine(json) }
    }

    private val unconfiguredArm by lazy { Arm(modelJson) }
    private val leapArm by lazy { Arm(leapYearModel) }
    private val commonArm by lazy { Arm(commonYearModel) }

    private fun fired(): Set<String> = firedOn(unconfiguredArm)

    private fun firedOn(arm: Arm): Set<String> {
        val codes = rules.map { it.code() }.toSet()
        val dynamic = RuntimeLaws.messages(arm.json, kernelDocument())
            .map { it.code() }.filter { it in codes }.toSortedSet()
        val static = arm.engine.messages(arm.engine.instance(kernelDocument()))
            .map { it.code() }.filter { it in codes }.toSortedSet()
        val interpreter = DmInterpreter(arm.definition).validateFull(interpreterDocumentFor(arm))
            .map { it.code }.filter { it in codes }.toSortedSet()
        assertEquals(dynamic, static, "generated Java vs normative dynamic-Groovy")
        assertEquals(dynamic, interpreter, "interpreter vs normative dynamic-Groovy")
        return dynamic
    }

    private fun kernelDocument(): RuntimeLaws.Doc = RuntimeLaws.doc().group(ITINERARY, 1)
        .field(LOCATOR, "TRV-7781", 1, 1)
        .field(DEPARTS, "07", 1, 1)

    private fun interpreterDocumentFor(arm: Arm): Document = arm.definition.newDocument().group(ITINERARY, 1)
        .field(LOCATOR, "TRV-7781", 1, 1)
        .field(DEPARTS, "07", 1, 1)
}
