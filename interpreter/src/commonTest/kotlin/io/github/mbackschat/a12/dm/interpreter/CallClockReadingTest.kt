package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.CallClock
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import io.github.mbackschat.a12.dm.interpreter.model.MessageLabelProvider
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedTypeRegistry
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **A call's clock is sampled when the call starts and read as wall time only when a rule reads `Today` or `Now`.**
 *
 * Reading the wall time in the model's zone is the expensive half of the clock: on Kotlin/JS the instant's Long
 * arithmetic is emulated and the zone tables initialize on first use. It cost about 1.1 ms of the browser first
 * answer's 9.6 ms interpreter construction on a model with no clock constant (interleaved jitless profile,
 * 2026-11-06), paid once for the support audit and again for the call. The audit needs only whether a clock with a
 * date-time is present, so it never has to read one.
 *
 * Deleting the laziness restores correct answers, so no behavioural test can see it. The counters can: the source
 * count proves the clock was consulted (so a zero read count is not vacuous), and the read count says whether the
 * wall time was computed.
 */
class CallClockReadingTest {

    private var sampled = 0
    private var read = 0

    private fun engine(vararg rules: RuleDef) = ModelDef(
        listOf(
            FieldDef(DUE, Kind.DATE, format = "yyyy-MM-dd"),
            FieldDef(STAMP, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
            FieldDef(QUANTITY, Kind.NUMBER, scale = 0),
        ),
        rules.toList(),
    ).let { model ->
        DmInterpreter.sharing(
            model = model,
            clock = {
                sampled++
                CallClock(hasNow = true) { read++; EvalClock(today = "2026-11-06", now = "2026-11-06T09:00:00") }
            },
            predefinedTypes = PredefinedTypeRegistry.DEFAULT,
            customConditions = emptyMap(),
            customFieldTypes = emptyMap(),
            legalCharset = LegalCharset.BMP,
            messageLabelProvider = MessageLabelProvider.MODEL_LABEL_OR_NAME,
            plans = ModelStaticPlans.of(model),
        ) to model
    }

    @Test
    fun aCallThatReadsNoClockConstantNeverReadsTheWallTime() {
        val (engine, model) = engine(RuleDef("/Order/Positive", ROOT, "[Quantity] < 0", "NEGATIVE", "../Quantity"))
        assertEquals(emptyList(), engine.supportAudit())
        val fired = engine.validate(model.newDocument().group(ROOT, 1).field(QUANTITY, "-1", 1, 1)).messages.map { it.code }
        assertEquals(listOf("NEGATIVE"), fired, "the control: the rule ran")
        assertEquals(true, sampled > 0, "the clock was consulted, so the zero below is not vacuous")
        assertEquals(0, read)
    }

    @Test
    fun theAuditAsksOnlyWhetherAClockIsPresentAndTheCallReadsItOnce() {
        val (engine, model) = engine(
            RuleDef("/Order/DueToday", ROOT, "[Due] == Today", "DUE_TODAY", "../Due"),
            RuleDef("/Order/StampedBefore", ROOT, "[Stamp] < Now", "STAMPED_BEFORE", "../Stamp"),
        )
        assertEquals(emptyList(), engine.supportAudit())
        assertEquals(0, read, "presence answers the audit")
        val document = model.newDocument().group(ROOT, 1).field(DUE, "2026-11-06", 1, 1).field(STAMP, "2026-11-06T08:00:00", 1, 1)
        assertEquals(setOf("DUE_TODAY", "STAMPED_BEFORE"), engine.validate(document).messages.map { it.code }.toSet())
        assertEquals(1, read, "both constants share the call's one reading")
    }

    private companion object {
        const val ROOT = "/Order"
        const val DUE = "/Order/Due"
        const val STAMP = "/Order/Stamp"
        const val QUANTITY = "/Order/Quantity"
    }
}
