package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.Locale
import java.util.TimeZone

/**
 * SPEC-2026-10-04-06: a time shift's amount is reached in validation even when its source is formally
 * unavailable, so a gap label inside the amount fails the authored rule as VALUE; computation stops at the
 * poisoned source first and clears. An amount extracted from an empty clock shifts by zero, so the source's hour
 * survives, but the result carries missing provenance: rebuilt into a `Time(...)` component it is an empty
 * component, the clock has no value, and its projection is a fillable zero over which rules fire as OMISSION.
 *
 * The JVM default is pinned to UTC, under which the kernel's static check admits the gap literal
 * (`DateTimeConstantZoneSplitDiffTest`); the model zone is Europe/Berlin.
 *
 * Own domain (shipment tracking).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShiftAmountPhaseDiffTest {
    private companion object {
        const val GAP = "31.03.2024T02:30:00"
        val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    }

    private val seeds = mapOf("GapAmountHour" to "99", "GapAmountNext" to "99", "ClockAmountHour" to "99",
        "ClockAmountNext" to "99", "ClockRebuiltHour" to "99", "ClockRebuiltNext" to "99")

    private fun <T> underUtc(body: () -> T): T {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(UTC)
        try { return body() } finally { TimeZone.setDefault(previous) }
    }

    private val model: String = underUtc {
        val draft = DocumentModelDraft.named("shipment-shift-amount").locale("en_US")
            .rootGroup("Shipment") { s ->
                s.dateTimeField("Dispatched") { it.format("yyyy-MM-dd'T'HH:mm:ss") }
                s.timeField("Handling") { it.format("HH:mm:ss") }
                s.numberField("Tracked") { it.fractionDigits(0) }
                for (name in seeds.keys) s.numberField(name) { it.fractionDigits(0) }
            }.build().toJson()
        val session = EditSession.read(draft).also { it.config().timeZone("Europe/Berlin") }
        val s = session.group("/Shipment")
        val dispatched = s.dateTime("Dispatched")
        val tracked = s.num("Tracked")
        val gapAmount: NumberExpr = Dsl.hoursFromTime(Dsl.timeFromDateTime(Dsl.dateTimeValue(GAP)))
        val byGap = Dsl.hoursFromTime(Dsl.addSeconds(dispatched, gapAmount))
        val byClock = Dsl.hoursFromTime(Dsl.addSeconds(dispatched, Dsl.hoursFromTime(s.time("Handling"))))
        val rebuilt = Dsl.hoursFromTime(Dsl.time(byClock))
        var json = session.toJson()
        fun comp(target: FieldRef, name: String, body: (Computation.Builder) -> Computation.Builder) {
            json = TypedRules.inject(json, s.computation(target) {
                body(it.name(name + "Comp")).message(Dsl.en("Check the dispatch hour.")).build()
            })
        }
        comp(s.num("GapAmountHour").field(), "GapAmountHour") { it.calc(byGap.expression()) }
        comp(s.num("GapAmountNext").field(), "GapAmountNext") { it.calc(s.num("GapAmountHour").plus(1)) }
        comp(s.num("ClockAmountHour").field(), "ClockAmountHour") { it.calc(byClock.expression()) }
        comp(s.num("ClockAmountNext").field(), "ClockAmountNext") { it.calc(s.num("ClockAmountHour").plus(1)) }
        comp(s.num("ClockRebuiltHour").field(), "ClockRebuiltHour") { it.calc(rebuilt.expression()) }
        comp(s.num("ClockRebuiltNext").field(), "ClockRebuiltNext") { it.calc(s.num("ClockRebuiltHour").plus(1)) }
        val rules: List<Pair<String, Condition>> = listOf(
            "GapAmountZero" to tracked.filled().and(byGap.eq(0)),
            "ClockAmountZero" to tracked.filled().and(byClock.eq(0)),
            "ClockRebuiltZero" to tracked.filled().and(rebuilt.eq(0)),
        )
        for ((code, condition) in rules) {
            json = TypedRules.inject(json, s.rule {
                it.name(code).code(code).errorField(tracked.field()).`when`(condition)
                    .message(Dsl.en("Check the dispatch hour.")).build()
            })
        }
        json
    }

    private fun document(dispatched: String, handling: String) =
        """{"Shipment":{"Dispatched":"$dispatched","Handling":"$handling","Tracked":1,""" +
            seeds.entries.joinToString(",") { (k, v) -> "\"$k\":$v" } + "}}"

    private val documents = linkedMapOf(
        "ordinary" to document("2024-01-01T23:59:30", "05:06:07"),
        "malformed-dispatch" to document("late", "05:06:07"),
        "empty-handling" to document("2024-01-01T23:59:30", ""),
    )

    private data class Observation(val messages: List<String>, val outcomes: Map<String, String>)

    private fun kernelSignature(m: IMessage): String =
        "${m.errorCode}|${m.messageType.name}|" +
            formatPointer(m.errorFieldPointer.fullName(), m.errorFieldPointer.repetitionIndexes())

    private fun kernel(dynamic: Boolean, json: String): Observation = underUtc {
        val (messages, computation) = if (dynamic) {
            val engine = RuntimeLaws.kernelEngine(model)
            engine.validationMessages(engine.instance(json), null, Locale.US) to
                engine.computation(engine.instance(json), null, Locale.US)
        } else {
            val engine = RuntimeLaws.kernelJavaEngine(model)
            engine.validationMessages(engine.instance(json), null, Locale.US) to
                engine.computation(engine.instance(json), null, Locale.US)
        }
        Observation(
            messages.map(::kernelSignature).sorted(),
            computation.outcomes().associate {
                it.path().substringAfterLast('/') to when {
                    it.errored() -> "ERRORED|" + it.value().orElse("")
                    it.cleared() -> "CLEARED"
                    else -> "VALUE|" + it.value().orElse("")
                }
            }.toSortedMap(),
        )
    }

    private fun interpreter(json: String): Observation {
        val def = ModelLoader.load(model)
        val engine = DmInterpreter(def)
        val messages = engine.validate(def.readDocumentJson(json)).also {
            assertEquals(emptyList<Any>(), it.unsupported, "every construct must be evaluable")
        }.messages.map {
            val type = if (it.type == MsgType.VALUE) "VALUE_ERROR" else "OMISSION_ERROR"
            "${it.code}|$type|" + formatPointer(it.errorPointer.fullName, it.errorPointer.repetitionIndexes)
        }.sorted()
        // The kernel reports only cells whose stored state changes, and every target is seeded.
        val outcomes = engine.compute(def.readDocumentJson(json)).filter { c ->
            !(c.outcome.name == "VALUE" && seeds[c.path.substringAfterLast('/')] == c.value)
        }.associate {
            it.path.substringAfterLast('/') to when (it.outcome.name) {
                "ERRORED" -> "ERRORED|" + (it.value ?: "")
                "CLEARED" -> "CLEARED"
                else -> "VALUE|" + (it.value ?: "")
            }
        }.toSortedMap()
        return Observation(messages, outcomes)
    }

    @Test
    fun theModelIsKernelConsistentUnderTheAdmittingDefault() {
        assertEquals(emptyList<String>(), underUtc { MessageParameterProbes.kernel(model) })
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        for ((name, json) in documents) {
            val dynamic = kernel(dynamic = true, json)
            assertEquals(dynamic, kernel(dynamic = false, json), "$name: static Java vs dynamic Groovy")
            assertEquals(dynamic, interpreter(json), "$name: interpreter vs dynamic Groovy")
        }
    }

    /**
     * The separating rows, pinned against the normative kernel so the agreement above cannot pass vacuously. Each
     * names the rival account it rules out: validation stopping at the unavailable source like computation does,
     * an empty extracted amount removing the shift's value, and one shifting by a plain zero with no provenance.
     */
    @Test
    fun theSeparatingRowsAreTheKernelsOwn() {
        fun run(doc: String) = kernel(dynamic = true, documents.getValue(doc))
        fun fired(doc: String, code: String) = run(doc).messages.filter { it.startsWith("$code|") }
        val actual = linkedMapOf(
            "validation reaches the gap amount behind a malformed source" to
                fired("malformed-dispatch", "GapAmountZero"),
            "computation stops at the malformed source" to run("malformed-dispatch").outcomes["GapAmountHour"],
            "an empty extracted amount keeps the shifted value" to run("empty-handling").outcomes["ClockAmountHour"],
            "so a rule over it sees that value" to fired("empty-handling", "ClockAmountZero"),
            "rebuilt into a Time component it is missing" to run("empty-handling").outcomes["ClockRebuiltHour"],
            "and rules over the rebuilt clock fire as omission" to fired("empty-handling", "ClockRebuiltZero"),
        )
        assertEquals(linkedMapOf(
            "validation reaches the gap amount behind a malformed source" to
                listOf("GapAmountZero|VALUE_ERROR|/Shipment[1]/Tracked"),
            "computation stops at the malformed source" to "CLEARED",
            "an empty extracted amount keeps the shifted value" to "VALUE|23",
            "so a rule over it sees that value" to emptyList<String>(),
            "rebuilt into a Time component it is missing" to "VALUE|0",
            "and rules over the rebuilt clock fire as omission" to
                listOf("ClockRebuiltZero|OMISSION_ERROR|/Shipment[1]/Tracked"),
        ), actual)
    }
}
