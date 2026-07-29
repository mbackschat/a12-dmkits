package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.ModelDocument
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Both kernel strategies lock the `DifferenceInDays` calendar-step separator. The interpreter participates
 * on its supported Berlin/UTC profile; Pacific/Apia remains kernel evidence for the deliberately refused
 * wider Java time-zone domain.
 */
class CalendarStepDifferenceInDaysDiffTest {

    @Test
    fun berlinPartialGapLandingTriChecks() {
        val fixture = fixture("Europe/Berlin")
        assertDifference(fixture, "2024-03-30T02:30:00", "2024-03-31T01:45:00", 1, true)
        assertDifference(fixture, "2024-03-30T02:30:00", "2024-03-31T01:29:59", 0, true)
        assertDifference(fixture, "2024-03-31T01:45:00", "2024-03-30T02:30:00", -1, true)
        assertDifference(fixture, "2024-03-30T02:30:00", "2024-03-31T03:30:00", 1, true)
        assertDifference(fixture, "2024-03-30T02:30:00", "2024-04-01T02:00:00", 2, true)
    }

    /**
     * SPEC-2026-07-29-01's named composite: the whole-year seed lands its candidate through the legacy YEAR
     * helper, and each subsequent day step uses its OWN current instant as the source — so a seed landing that
     * falls in the spring gap changes the carried clock and the total is 369, not the naive 370.
     */
    @Test
    fun theWholeYearSeedCompositeIsThreeHundredSixtyNine() {
        val fixture = fixture("Europe/Berlin")
        assertDifference(fixture, "2023-04-01T02:30:00", "2024-04-05T02:30:00", 369, true)
    }

    /**
     * The long spring-gap separator for the YEAR arm (a12-kernel-lean SPEC-2026-07-29-01). The 21-year
     * candidate's landing falls in the 2024 spring gap, and the two rival resolutions put it on opposite sides
     * of the later endpoint (2024-03-31T03:00 = 01:00Z):
     *
     *  - sign-independent compute-time resolution → the **post-gap** label 03:30 (01:30Z), which is AFTER the
     *    endpoint, so the 21-year candidate is rejected and the count is **7670**;
     *  - the direction-selected forward resolution → the **pre-gap** label 01:30 (00:30Z), which admits the
     *    candidate and freezes the seed one day high, giving **7671**.
     *
     * Unlike the one-year overlap construction, the day loop cannot self-correct here: it only ever adds, so an
     * admitted-but-overshooting seed is unrecoverable. That is what makes this a real discriminator.
     */
    @Test
    fun theLongSpringGapYearCandidateSeparatesTheTwoResolutions() {
        val fixture = fixture("Europe/Berlin")
        assertDifference(fixture, "2003-03-31T02:30:00", "2024-03-31T03:00:00", 7670, true)
    }

    @Test
    fun utcOrdinaryControlsStayTimeOfDayAware() {
        val fixture = fixture("UTC")
        assertDifference(fixture, "2024-06-15T23:00:00", "2024-06-16T01:00:00", 0, true)
        assertDifference(fixture, "2024-06-15T12:00:00", "2024-06-16T12:00:00", 1, true)
        assertDifference(fixture, "2024-06-16T12:00:00", "2024-06-15T12:00:00", -1, true)
    }

    @Test
    fun apiaWholeDateSkipAgreesAcrossBothKernelStrategies() {
        val fixture = fixture("Pacific/Apia")
        assertDifference(fixture, "2011-12-29T12:00:00", "2011-12-31T12:00:00", 1, false)
        assertDifference(fixture, "2011-12-29T12:00:00", "2011-12-31T11:59:59", 0, false)
        assertDifference(fixture, "2011-12-31T12:00:00", "2011-12-29T12:00:00", -1, false)
        assertDifference(fixture, "2011-12-29T12:00:00", "2012-01-01T12:00:00", 2, false)
    }

    private fun assertDifference(
        fixture: Fixture,
        observedAt: String,
        comparedAt: String,
        expected: Int,
        includeInterpreter: Boolean,
    ) {
        val document = RuntimeLaws.doc().group(ROOT, 1)
            .field(OBSERVED, observedAt, 1, 1)
            .field(COMPARED, comparedAt, 1, 1)
        val dynamic = firedDifferenceCodes(RuntimeLaws.messages(fixture.modelJson, document).map { it.code() })
        val generated = firedDifferenceCodes(
            fixture.javaEngine.messages(fixture.javaEngine.instance(document)).map { it.code() },
        )
        val expectedCode = setOf(code(expected))
        val clue = "${fixture.zone} $observedAt → $comparedAt"
        assertEquals(expectedCode, dynamic, "$clue — dynamic-Groovy")
        assertEquals(dynamic, generated, "$clue — generated Java")

        if (includeInterpreter) {
            val definition = ModelLoader.load(fixture.modelJson)
            val interpretedDocument = definition.newDocument().group(ROOT, 1)
                .field(OBSERVED, observedAt, 1, 1)
                .field(COMPARED, comparedAt, 1, 1)
            val interpreted = firedDifferenceCodes(
                DmInterpreter(definition).validateFull(interpretedDocument).map { it.code },
            )
            assertEquals(dynamic, interpreted, "$clue — interpreter")
        }
    }

    private fun fixture(zone: String): Fixture {
        val zoned = ModelDocument.read(BASE).setTimeZone(zone).toJson()
        var model = BoundModel.ofJson(zoned)
        val observedAt = model.dateTime(OBSERVED)
        val comparedAt = model.dateTime(COMPARED)
        for (expected in EXPECTED_VALUES) {
            val condition = observedAt.filled().and(Dsl.differenceInDays(observedAt, comparedAt).eq(expected.toLong()))
            val rule = model.rule(model.group(ROOT)).name(
                if (expected < 0) "DifferenceNeg${-expected}" else "Difference$expected",
            )
                .errorField(observedAt.field())
                .`when`(condition)
                .code(code(expected))
                .severity(Severity.ERROR)
                .message(Dsl.en("difference $expected"))
                .build()
            model = model.add(rule)
        }
        val modelJson = model.toJson()
        return Fixture(zone, modelJson, RuntimeLaws.kernelJavaEngine(modelJson))
    }

    private fun firedDifferenceCodes(codes: List<String>): Set<String> =
        codes.filter { it.startsWith(CODE_PREFIX) }.toSet()

    private fun code(value: Int): String = CODE_PREFIX + if (value < 0) "NEG${-value}" else value

    private data class Fixture(
        val zone: String,
        val modelJson: String,
        val javaEngine: RuntimeLaws.JavaKernelEngine,
    )

    private companion object {
        const val ROOT = "/Itinerary"
        const val OBSERVED = "$ROOT/ObservedAt"
        const val COMPARED = "$ROOT/ComparedAt"
        const val CODE_PREFIX = "CALENDAR_DAYS_"
        // 368..370 bracket SPEC-2026-07-29-01's whole-year-seed composite, so the fixture can DISCRIMINATE
        // the value rather than merely fail to match one candidate.
        val EXPECTED_VALUES = listOf(-1, 0, 1, 2, 368, 369, 370, 7670, 7671)

        val BASE: String = DocumentModelDraft.named("itinerary").locale("en_US")
            .rootGroup("Itinerary") { group ->
                group.dateTimeField("ObservedAt") { }
                group.dateTimeField("ComparedAt") { }
            }
            .build()
            .toJson()
    }
}
