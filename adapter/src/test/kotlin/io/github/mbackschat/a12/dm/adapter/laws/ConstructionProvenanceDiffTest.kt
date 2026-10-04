package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.Locale
import java.util.TimeZone

/**
 * SPEC-2026-10-04-02: a temporal constructor keeps WHY it has no value. A fixed unreal component (`Time(24)`) and a
 * constructed wall time inside the Berlin gap are fixed outcomes, an empty partial date is a missing one, and an
 * extremum over them keeps that provenance, so a rule comparing it fires as VALUE or OMISSION accordingly. The
 * constructed gap clears its target cleanly, where a gap LITERAL is a runtime failure (KF513). `DateTime(date, time)`
 * evaluates its arguments in order and stops early only on a formally invalid Date read: a partial date that has no
 * value still lets the Time argument run, so a malformed clock poisons and a gap literal fails there.
 *
 * The JVM default is pinned to UTC, under which the kernel's static check admits the gap literal
 * (`DateTimeConstantZoneSplitDiffTest`); the model zone is Europe/Berlin.
 *
 * Own domain (insurance claim intake).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConstructionProvenanceDiffTest {
    private companion object {
        const val ISO_DT = "yyyy-MM-dd'T'HH:mm:ss"
        const val GAP = "31.03.2024T02:30:00"
        val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    }

    private val seeds = mapOf("OutConstructed" to "2001-12-25T12:30:00", "AfterConstructed" to "9",
        "OutPartial" to "2001-12-25T12:30:00", "AfterPartial" to "9", "OutGap" to "2001-12-25T12:30:00",
        "AfterGap" to "9")

    private fun <T> underUtc(body: () -> T): T {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(UTC)
        try { return body() } finally { TimeZone.setDefault(previous) }
    }

    private val model: String = underUtc {
        val draft = DocumentModelDraft.named("claim-intake-construction").locale("en_US")
            .rootGroup("Claim") { c ->
                c.dateField("Incident") { it.format("yyyy-MM-dd") }
                c.dateField("Reported") { it.format("yyyy-MM-dd").datePrecision(DatePrecision.YEAR_OPTIONAL) }
                c.timeField("Clock") { it.format("HH:mm:ss") }
                c.numberField("Hour") { it.fractionDigits(0).maxLength(2).positivesOnly() }
                c.dateTimeField("Logged") { it.format(ISO_DT) }
                for (name in listOf("OutConstructed", "OutPartial", "OutGap")) c.dateTimeField(name) { it.format(ISO_DT) }
                for (name in listOf("AfterConstructed", "AfterPartial", "AfterGap")) c.numberField(name) { it.fractionDigits(0) }
            }.build().toJson()
        val session = EditSession.read(draft).also { it.config().timeZone("Europe/Berlin") }
        val c = session.group("/Claim")
        val incident = c.date("Incident")
        val clock = c.time("Clock")
        val logged = c.dateTime("Logged")
        val hourClock = Dsl.time(c.num("Hour").field())
        val reportedFirst = Dsl.valueAsDateFirstDay(c.date("Reported").field())
        val gapClock = Dsl.timeFromDateTime(Dsl.dateTimeValue(GAP))
        var json = session.toJson()
        fun comp(target: FieldRef, name: String, body: (Computation.Builder) -> Computation.Builder) {
            json = TypedRules.inject(json, c.computation(target) {
                body(it.name(name + "Comp")).message(Dsl.en("Check the intake stamp.")).build()
            })
        }
        comp(c.dateTime("OutConstructed").field(), "OutConstructed") {
            it.calc(Dsl.dateTime(incident, hourClock).expression())
        }
        comp(c.dateTime("OutPartial").field(), "OutPartial") { it.calc(Dsl.dateTime(reportedFirst, clock).expression()) }
        comp(c.dateTime("OutGap").field(), "OutGap") { it.calc(Dsl.dateTime(reportedFirst, gapClock).expression()) }
        for ((after, out) in listOf("AfterConstructed" to "OutConstructed", "AfterPartial" to "OutPartial",
            "AfterGap" to "OutGap")) {
            comp(c.num(after).field(), after) {
                it.commonPrecondition(c.dateTime(out).notFilled()).calc(Dsl.number(1).expression())
            }
        }
        val rules: List<Triple<String, FieldRef, Condition>> = listOf(
            Triple("ClockMin", clock.field(), Dsl.min(hourClock, clock).eq(clock)),
            Triple("ConstructedMin", logged.field(), Dsl.min(Dsl.dateTime(incident, hourClock), logged).eq(logged)),
            Triple("PartialMin", logged.field(), Dsl.min(Dsl.dateTime(reportedFirst, clock), logged).eq(logged)),
            Triple("GapAfterPartial", logged.field(), Dsl.dateTime(reportedFirst, gapClock).eq(logged)),
        )
        for ((code, errorField, condition) in rules) {
            json = TypedRules.inject(json, c.rule {
                it.name(code).code(code).errorField(errorField).`when`(condition)
                    .message(Dsl.en("Check the intake stamp.")).build()
            })
        }
        json
    }

    private fun document(incident: String, reported: String, clock: String, hour: String) =
        """{"Claim":{"Incident":"$incident","Reported":"$reported","Clock":"$clock","Hour":"$hour",""" +
            """"Logged":"2024-03-31T03:30:00",""" +
            seeds.entries.joinToString(",") { (k, v) -> if (k.startsWith("After")) "\"$k\":$v" else "\"$k\":\"$v\"" } +
            "}}"

    private val documents = linkedMapOf(
        "ordinary" to document("2024-03-30", "2024-02-00", "12:30:00", "5"),
        "constructed-gap" to document("2024-03-31", "2024-03-00", "12:30:00", "2"),
        "unreal-clock" to document("2024-03-30", "2024-03-00", "12:30:00", "24"),
        "empty-partial" to document("2024-03-30", "", "12:30:00", "5"),
        "malformed-partial" to document("2024-03-30", "bad", "12:30:00", "5"),
        "unknown-year-partial" to document("2024-03-30", "0000-00-00", "12:30:00", "5"),
        "empty-partial-bad-clock" to document("2024-03-30", "", "bad", "5"),
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
     * names the rival account it rules out: an unreal clock or a gap construction read as missing or as invalid,
     * a constructed gap poisoning like a gap literal, an empty partial date read as invalid, and a constructor that
     * stops at any valueless Date argument.
     */
    @Test
    fun theSeparatingRowsAreTheKernelsOwn() {
        fun run(doc: String) = kernel(dynamic = true, documents.getValue(doc))
        fun fired(doc: String, code: String) = run(doc).messages.filter { it.startsWith("$code|") }
        val actual = linkedMapOf(
            "an unreal clock is a fixed outcome under Min" to fired("unreal-clock", "ClockMin"),
            "a constructed gap is a fixed outcome under Min" to fired("constructed-gap", "ConstructedMin"),
            "a constructed gap clears its target" to run("constructed-gap").outcomes["OutConstructed"],
            "cleanly, so its guard reads it as empty" to run("constructed-gap").outcomes["AfterConstructed"],
            "an empty partial date is a missing outcome under Min" to fired("empty-partial", "PartialMin"),
            "a valueless partial date still runs the clock argument" to
                run("empty-partial-bad-clock").outcomes["AfterPartial"],
            "and reaches a gap literal there" to fired("malformed-partial", "GapAfterPartial"),
        )
        assertEquals(linkedMapOf(
            "an unreal clock is a fixed outcome under Min" to listOf("ClockMin|VALUE_ERROR|/Claim[1]/Clock"),
            "a constructed gap is a fixed outcome under Min" to listOf("ConstructedMin|VALUE_ERROR|/Claim[1]/Logged"),
            "a constructed gap clears its target" to "CLEARED",
            "cleanly, so its guard reads it as empty" to "VALUE|1",
            "an empty partial date is a missing outcome under Min" to
                listOf("PartialMin|OMISSION_ERROR|/Claim[1]/Logged"),
            "a valueless partial date still runs the clock argument" to "CLEARED",
            "and reaches a gap literal there" to listOf("GapAfterPartial|VALUE_ERROR|/Claim[1]/Logged"),
        ), actual)
    }
}
