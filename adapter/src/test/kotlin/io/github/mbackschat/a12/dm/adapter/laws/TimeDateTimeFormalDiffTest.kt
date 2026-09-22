package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.modelkit.DateTimeFieldBuilder
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.TimeFieldBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — the **formal check for malformed `TIME` / `DATE_TIME`** (KERNEL-FINDINGS §6 / IF8). The kernel
 * emits **`datumFormatFalsch`** (VALUE, the same code as a bad DATE — probe-verified) for a value that doesn't
 * fit the field's time/date-time format, and a referencing rule is **suppressed**. Before this, `TIME` was not
 * format-checked at all, so a malformed time even *escaped* suppression (it resolved to a plain text value).
 * This locks both the formal message and the suppression against the kernel.
 */
class TimeDateTimeFormalDiffTest {

    private val json = DocumentModelDraft.named("form").locale("en_US")
        .rootGroup("Form", Consumer { g ->
            g.timeField("T", Consumer<TimeFieldBuilder> { it.format("HH:mm:ss") })
            g.dateTimeField("DT", Consumer<DateTimeFieldBuilder> { it.format("yyyy-MM-dd'T'HH:mm:ss") })
        })
        .build().toJson()
    private val def = ModelDef(
        listOf(
            FieldDef("/Form/T", Kind.TIME, format = "HH:mm:ss"),
            FieldDef("/Form/DT", Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
        ),
        emptyList(),
    )

    // The rule condition is authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed
    // to BOTH engines — no free-text condition literal. The renderer emits absolute paths; both engines accept
    // them. The malformed TIME/DATE_TIME values that exercise the formal check stay in the doc-building below.
    private val m = BoundModel.ofJson(json)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    private fun iDoc() = def.newDocument()

    private fun assertFormalAgrees(t: String, dt: String) {
        val kDoc = RuntimeLaws.doc().group("/Form", 1).field("/Form/T", t, 1, 1).field("/Form/DT", dt, 1, 1)
        val iDoc = iDoc().group("/Form", 1).field("/Form/T", t, 1, 1).field("/Form/DT", dt, 1, 1)
        val kSigs = RuntimeLaws.messages(json, kDoc).map { "${it.code()}|${it.type().name}|${it.field()}" }.sorted()
        val iSigs = DmInterpreter(def).validateFull(iDoc)
            .map { "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}|${it.errorPath}" }.sorted()
        assertEquals(kSigs, iSigs, "formal-error signatures must match the kernel (T=\"$t\" DT=\"$dt\")")
    }

    @Test
    fun malformedTimeAndDateTimeEmitTheKernelFormalCode() {
        assertFormalAgrees("notatime", "2024-06-01T12:30:00")     // bad TIME only
        assertFormalAgrees("99:99:99", "2024-06-01T12:30:00")     // out-of-range TIME
        assertFormalAgrees("12:30:00", "notadatetime")            // bad DATE_TIME only
        assertFormalAgrees("notatime", "notadatetime")            // both bad
        assertFormalAgrees("12:30:00", "2024-06-01T12:30:00")     // both valid → no formal error
    }

    @Test
    fun aMalformedTimeSuppressesItsReferencingRule() {
        // A rule comparing the TIME must be SUPPRESSED on a malformed value (it resolves to Unknown), exactly as
        // the kernel — previously the interpreter treated the bad time as plain text and could mis-fire. A simple
        // string-equality guard that references the malformed field.
        val ruleCond = render(m.time("/Form/T").filled().and(m.time("/Form/T").eq(m.time("/Form/T"))))
        val ruleModel = RuntimeLaws.withRule(json, "/Form", "TR", "../T", "TR", ruleCond)
        val rdef = def.copy(rules = listOf(RuleDef("/Form/TR", "/Form", ruleCond, "TR", "../T")))
        val kDoc = RuntimeLaws.doc().group("/Form", 1).field("/Form/T", "notatime", 1, 1)
        val iDoc = iDoc().group("/Form", 1).field("/Form/T", "notatime", 1, 1)
        val kFiredTR = RuntimeLaws.messages(ruleModel, kDoc).any { it.code() == "TR" }
        val iFiredTR = DmInterpreter(rdef).validateFull(iDoc).any { it.code == "TR" }
        assertEquals(kFiredTR, iFiredTR, "TR firing must match the kernel on a malformed TIME (both suppressed)")
        assertTrue(!iFiredTR, "the rule referencing a malformed TIME is suppressed (not fired)")
    }
}
