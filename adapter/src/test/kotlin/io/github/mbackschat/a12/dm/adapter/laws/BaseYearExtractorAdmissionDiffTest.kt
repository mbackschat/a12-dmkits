package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — **admission is not a value**: a declared Base Year admits `YearFromDate` on a pure
 * `HH:mm:ss` field ([`TemporalExtractorBaseYearGateLawsTest`][TemporalExtractorBaseYearGateLawsTest]), and the
 * extractor then decides nothing at runtime.
 *
 * <p><b>Two comparands are what make the negative a measurement.</b> Asking only `== 2024` cannot tell "the
 * Base Year supplies the year" from "the operand yields nothing": both fail to fire when the answer is
 * nothing, and only one of them fails when the answer is 2024. Asking `== 2023` as well closes that — neither
 * fires, so the extractor is not answering with the Base Year, and it is not answering at all.
 *
 * <p><b>The liveness control is the third leg.</b> The same rule shape over an ordinary DATE field fires on the
 * same document, so a rule of this form is demonstrably reachable and the two silences above are the
 * operand's.
 *
 * <p>Reported by a12-kernel-lean as `SPEC-2026-09-05-14`, whose own limit is static admission; this is the row
 * their clause does not carry, and a consumer reading their admission row as a usable year would be wrong.
 *
 * <p>Own domain (an insurance cover term).
 */
class BaseYearExtractorAdmissionDiffTest {

    private companion object {
        const val GROUP = "/Term"
        const val HOLDER = "/Term/Holder"
        const val CLOCK = "/Term/RenewalClock"
        const val START = "/Term/StartOn"
        const val HOUR = "/Term/RenewalHour"
    }

    private val term: String =
        DocumentModelDraft.named("cover-term").locale("en_US").baseYear(2024)
            .rootGroup("Term") { g ->
                g.stringField("Holder") { }
                g.timeField("RenewalClock") { c -> c.format("HH:mm:ss") }
                g.numberField("RenewalHour") { n -> n.fractionDigits(0).min("0").max("23") }
                g.dateField("StartOn") { d -> d.format("yyyy-MM-dd") }
            }
            .build().toJson()

    private fun withRule(code: String, condition: String): String =
        RuntimeLaws.withRule(term, GROUP, "R$code", "../Holder", code, "FieldFilled(Holder) And $condition")

    /**
     * Fires on all THREE engines, or on none — dynamic-Groovy (normative), static-Java, and the interpreter.
     *
     * The static-Java arm is not decoration: an earlier version measured dynamic-Groovy alone while the finding
     * claimed "on both kernel strategies", which is a scope transfer to a carrier nobody had run. It costs one
     * codegen per model here because each condition is its own model.
     */
    private fun bothFire(code: String, condition: String): Boolean {
        val model = withRule(code, condition)
        val doc = RuntimeLaws.doc().group(GROUP, 1)
        doc.field(HOLDER, "Acme Logistics", 1, 1)
        doc.field(CLOCK, "12:30:00", 1, 1)
        doc.field(START, "2024-05-05", 1, 1)
        doc.field(HOUR, "8", 1, 1)
        val dynamic = code in RuntimeLaws.firedCodes(model, doc)

        val staticEngine = RuntimeLaws.kernelJavaEngine(model)
        val static = code in staticEngine.messages(staticEngine.instance(doc)).map { it.code() }.toSet()

        val def = ModelLoader.load(model)
        val iDoc = def.newDocument().group(GROUP, 1)
        iDoc.field(HOLDER, "Acme Logistics", 1, 1)
        iDoc.field(CLOCK, "12:30:00", 1, 1)
        iDoc.field(START, "2024-05-05", 1, 1)
        iDoc.field(HOUR, "8", 1, 1)
        val interpreter = code in DmInterpreter(def).validate(iDoc).messages.map { it.code }.toSet()

        assertEquals(dynamic, static, "generated Java vs normative dynamic-Groovy on '$condition'")
        assertEquals(dynamic, interpreter, "interpreter vs normative dynamic-Groovy on '$condition'")
        return dynamic
    }

    @Test
    fun anAdmittedYearExtractorOnAClockFieldDecidesNothingAtRuntime() {
        val actual = linkedMapOf(
            "YearFromDate(clock) == the declared base year" to
                bothFire("YRBASE", "YearFromDate(RenewalClock) == 2024"),
            "YearFromDate(clock) == another year" to
                bothFire("YRBASEOFF", "YearFromDate(RenewalClock) == 2023"),
            "YearFromDate(an ordinary DATE) == its year — liveness control" to
                bothFire("YRLIVE", "YearFromDate(StartOn) == 2024"),
        )
        val expected = linkedMapOf(
            "YearFromDate(clock) == the declared base year" to false,
            "YearFromDate(clock) == another year" to false,
            "YearFromDate(an ordinary DATE) == its year — liveness control" to true,
        )
        assertEquals(expected, actual, "the Base Year opens the static gate and supplies no runtime value")
    }

    /**
     * The year a TIME carries is the epoch's, 1970, which neither comparand above asked for (a12-kernel-lean
     * SPEC-2026-10-08-01). A clock field, a time built from an hour, and one recovered from a date-time are all
     * anchored on 1 January 1970, so their year is 1970 and not the declared Base Year; a constructed Date takes
     * that year as a component. The clock-field row corrects the test above's reading that the extractor decides
     * nothing: it decides 1970.
     */
    @Test
    fun aConstructedTimesYearIsTheEpochsNotTheBaseYear() {
        val actual = linkedMapOf(
            "YearFromDate(clock field) == 1970" to bothFire("YRCLOCK70", "YearFromDate(RenewalClock) == 1970"),
            "YearFromDate(a built time) == 1970" to bothFire("YRBUILT70", "YearFromDate(Time(RenewalHour)) == 1970"),
            "YearFromDate(a built time) == the declared base year" to
                bothFire("YRBUILTBASE", "YearFromDate(Time(RenewalHour)) == 2024"),
            "YearFromDate(a time recovered from a date-time) == 1970" to bothFire("YRREC70",
                "YearFromDate(TimeFromDateTime(DateTime(StartOn, Time(RenewalHour)))) == 1970"),
            "a Date whose year is a built time's" to bothFire("YRDATE70",
                "Date(\"01\", \"01\", YearFromDate(Time(RenewalHour))) == \"01.01.1970\""),
        )
        val expected = linkedMapOf(
            "YearFromDate(clock field) == 1970" to true,
            "YearFromDate(a built time) == 1970" to true,
            "YearFromDate(a built time) == the declared base year" to false,
            "YearFromDate(a time recovered from a date-time) == 1970" to true,
            "a Date whose year is a built time's" to true,
        )
        assertEquals(expected, actual, "a constructed time's hidden date is the epoch's")
    }
}
