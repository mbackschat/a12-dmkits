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
    }

    private val term: String =
        DocumentModelDraft.named("cover-term").locale("en_US").baseYear(2024)
            .rootGroup("Term") { g ->
                g.stringField("Holder") { }
                g.timeField("RenewalClock") { c -> c.format("HH:mm:ss") }
                g.dateField("StartOn") { d -> d.format("yyyy-MM-dd") }
            }
            .build().toJson()

    private fun withRule(code: String, condition: String): String =
        RuntimeLaws.withRule(term, GROUP, "R$code", "../Holder", code, "FieldFilled(Holder) And $condition")

    private fun bothFire(code: String, condition: String): Boolean {
        val model = withRule(code, condition)
        val doc = RuntimeLaws.doc().group(GROUP, 1)
        doc.field(HOLDER, "Acme Logistics", 1, 1)
        doc.field(CLOCK, "12:30:00", 1, 1)
        doc.field(START, "2024-05-05", 1, 1)
        val kernel = code in RuntimeLaws.firedCodes(model, doc)

        val def = ModelLoader.load(model)
        val iDoc = def.newDocument().group(GROUP, 1)
        iDoc.field(HOLDER, "Acme Logistics", 1, 1)
        iDoc.field(CLOCK, "12:30:00", 1, 1)
        iDoc.field(START, "2024-05-05", 1, 1)
        val interpreter = code in DmInterpreter(def).validate(iDoc).messages.map { it.code }.toSet()

        assertEquals(kernel, interpreter, "engines disagree on '$condition'")
        return kernel
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
}
