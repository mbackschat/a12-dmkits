package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DateTimeFieldBuilder
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * KERNEL-FINDINGS §6 / IF250 — a `DateTimeType` may be declared with the degenerate **time-only** `HH:mm:ss`
 * format, and such a cell must evaluate, not vanish.
 *
 * ## Why this file exists
 *
 * IF250 fixed the DATE half of the class "a declared format that omits components" and closed it with a sibling
 * sweep that excluded `DATE_TIME` on the premise that the kind admits exactly one legal format. It admits
 * **two** — the ISO `yyyy-MM-dd'T'HH:mm:ss` and the time-only `HH:mm:ss` (KERNEL-FINDINGS §6, probe-enumerated
 * 2026-06-26, i.e. already recorded when that sweep was written). The time-only format omits *every* calendar
 * component, so it sat squarely inside the class the sweep declared closed, and the interpreter resolved such a
 * cell to `Val.Unknown` — silently suppressing every referencing rule, which is IF250's own worst failure
 * direction reappearing on the kind it had ruled out.
 *
 * ## What each row establishes
 *
 * The kernel arm is what makes this a defect rather than a shared shape: agreement alone would be satisfied by
 * two engines that are equally silent, which is exactly what the bug looked like. So every row pins the dynamic
 * kernel's own reading as well as the agreement, and the comparisons run in **both** directions so a cell that
 * simply never compares cannot pass.
 */
class TimeOnlyDateTimeDiffTest {

    private companion object {
        const val CLOCKED = "/Shift/ClockedAt"
        const val OTHER = "/Shift/OtherAt"
        const val ISO = "/Shift/StartedAt"
        const val TIME_ONLY = "HH:mm:ss"
        const val ISO_FORMAT = "yyyy-MM-dd'T'HH:mm:ss"
    }

    private fun render(c: Condition): String = RuntimeLaws.render(c)

    /** Two time-only DATE_TIME fields plus an ISO one, so the kind's two legal formats both appear. */
    private val json: String = DocumentModelDraft.named("shift").locale("en_US")
        .rootGroup("Shift", Consumer { g ->
            g.dateTimeField("ClockedAt", Consumer<DateTimeFieldBuilder> { it.format(TIME_ONLY) })
            g.dateTimeField("OtherAt", Consumer<DateTimeFieldBuilder> { it.format(TIME_ONLY) })
            g.dateTimeField("StartedAt", Consumer<DateTimeFieldBuilder> { it.format(ISO_FORMAT) })
        }).build().toJson()

    private val bound: BoundModel = BoundModel.ofJson(json)

    private fun readings(
        errorRel: String,
        condition: String,
        cells: List<Pair<String, String>>,
    ): Pair<List<String>, List<String>> {
        val kModel = RuntimeLaws.withRule(json, "/Shift", "SH", errorRel, "SH", condition)
        val def = ModelLoader.load(kModel)
        val kDoc = RuntimeLaws.doc().group("/Shift", 1)
        val iDoc = def.newDocument().group("/Shift", 1)
        for ((path, value) in cells) {
            kDoc.field(path, value, 1, 1); iDoc.field(path, value, 1, 1)
        }
        fun short(field: String) = field.substringAfterLast('/')
        return RuntimeLaws.messages(kModel, kDoc).map { "${it.code()}@${short(it.field())}" } to
            DmInterpreter(def).validateFull(iDoc).map { "${it.code}@${short(it.errorPath)}" }
    }

    private fun agree(
        label: String,
        errorRel: String,
        condition: String,
        cells: List<Pair<String, String>>,
        expected: List<String>,
    ) {
        val (kernel, interpreter) = readings(errorRel, condition, cells)
        assertEquals(kernel, interpreter, "$label — kernel/interpreter messages")
        assertEquals(expected, kernel, "$label — what the dynamic kernel itself reports")
    }

    /**
     * THE case. A time-only cell is filled and compares, in both directions — the interpreter used to report
     * nothing at all here because the cell resolved to Unknown.
     */
    @Test
    fun aTimeOnlyDateTimeCellIsFilledAndComparesInBothDirections() {
        // A time-only DATE_TIME field is a time to A12, so it is authored through a time handle
        // (validate.laws.ClockDeclaredDateFieldLawsTest, DEV-API-GAPS DG132).
        val clocked = bound.time(CLOCKED)
        val other = bound.time(OTHER)

        agree("time-only cell is FILLED", "../ClockedAt",
            render(Dsl.fieldFilled(bound.field(CLOCKED))), listOf(CLOCKED to "14:30:00"), listOf("SH@ClockedAt"))
        agree("equal time-only values compare equal", "../ClockedAt",
            render(clocked.eq(other)), listOf(CLOCKED to "14:30:00", OTHER to "14:30:00"),
            listOf("SH@ClockedAt"))
        // The negative direction, so "fires" cannot be explained by a comparison that always succeeds.
        agree("different time-only values do not compare equal", "../ClockedAt",
            render(clocked.eq(other)), listOf(CLOCKED to "14:30:00", OTHER to "09:15:00"), emptyList())
        agree("time-only orders before a later one", "../ClockedAt",
            render(clocked.before(other)), listOf(CLOCKED to "09:15:00", OTHER to "14:30:00"),
            listOf("SH@ClockedAt"))
        agree("time-only does not order before an earlier one", "../ClockedAt",
            render(clocked.before(other)), listOf(CLOCKED to "14:30:00", OTHER to "09:15:00"), emptyList())
    }

    /**
     * The controls that keep the rows above attributable: the ISO format on the same kind still works (so no row
     * is explained by DATE_TIME being broken generally), an empty time-only cell is NOT filled (so "filled" is
     * not vacuous), and a value malformed FOR THE TIME-ONLY FORMAT is rejected rather than silently accepted —
     * the failure mode a too-permissive fix would introduce.
     */
    @Test
    fun theIsoFormatStillWorksAndAMalformedTimeOnlyValueIsStillRejected() {
        agree("the ISO sibling on the same kind is unaffected", "../StartedAt",
            render(Dsl.fieldFilled(bound.field(ISO))), listOf(ISO to "2024-06-01T12:30:00"),
            listOf("SH@StartedAt"))
        agree("an absent time-only cell is not filled", "../ClockedAt",
            render(Dsl.fieldFilled(bound.field(CLOCKED))), emptyList(), emptyList())

        // Out of range for its own format, and a full ISO value in a time-only field: both must be refused by
        // whatever parse the fix routes to, or the fix has traded a silent suppression for a silent acceptance.
        for ((label, value) in listOf(
            "hour out of range" to "25:00:00",
            "minute out of range" to "14:61:00",
            "an ISO datetime in a time-only field" to "2024-06-01T12:30:00",
            "missing the seconds its format requires" to "14:30",
        )) {
            val (kernel, interpreter) = readings("../ClockedAt",
                render(bound.time(CLOCKED).eq(bound.time(OTHER))),
                listOf(CLOCKED to value, OTHER to "14:30:00"))
            assertEquals(kernel, interpreter, "$label — kernel/interpreter messages")
        }
    }
}
