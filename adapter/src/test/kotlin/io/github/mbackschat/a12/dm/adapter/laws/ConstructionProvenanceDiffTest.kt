package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.DocumentDifferential.underUtc
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * SPEC-2026-10-04-02: a temporal constructor keeps WHY it has no value. A fixed unreal component (`Time(24)`) is a
 * fixed outcome, an empty partial date is a missing one, and an extremum over them keeps that provenance, so a rule
 * comparing it fires as VALUE or OMISSION accordingly. A constructed wall time inside the Berlin gap was a fixed
 * outcome on 30.8.1 too; kernel 31.1.1 moves it forward by the gap, so it is a value (KERNEL-FINDINGS KF665).
 * `DateTime(date, time)` evaluates its arguments in order and stops early only on a formally invalid Date read: a
 * partial date that has no value still lets the Time argument run, so a malformed clock poisons and a literal the
 * model zone cannot read fails there (KF513).
 *
 * The JVM default is pinned to UTC, under which the kernel's static check admits that literal
 * (`DateTimeConstantZoneSplitDiffTest`); the model zone is Europe/Berlin.
 *
 * Own domain (insurance claim intake).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConstructionProvenanceDiffTest {
    private companion object {
        const val ISO_DT = "yyyy-MM-dd'T'HH:mm:ss"
        const val GAP = "29.02.1500T10:00:00"
    }

    private val seeds = mapOf("OutConstructed" to "2001-12-25T12:30:00", "AfterConstructed" to "9",
        "OutPartial" to "2001-12-25T12:30:00", "AfterPartial" to "9", "OutGap" to "2001-12-25T12:30:00",
        "AfterGap" to "9")

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

    @Test
    fun theModelIsKernelConsistentUnderTheAdmittingDefault() {
        assertEquals(emptyList<String>(), underUtc { MessageParameterProbes.kernel(model) })
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        underUtc { DocumentDifferential.assertAgree(model, documents) }
    }

    /**
     * The separating rows, pinned against the normative kernel so the agreement above cannot pass vacuously. Each
     * names the rival account it rules out: an unreal clock read as missing or as invalid, a constructed gap still
     * read as no value, an empty partial date read as invalid, and a constructor that stops at any valueless Date
     * argument.
     */
    @Test
    fun theSeparatingRowsAreTheKernelsOwn() {
        fun run(doc: String) = underUtc { DocumentDifferential.kernel(model, documents.getValue(doc), dynamic = true) }
        fun fired(doc: String, code: String) = run(doc).messages.filter { it.code == code }.map { it.brief }
        val actual = linkedMapOf(
            "an unreal clock is a fixed outcome under Min" to fired("unreal-clock", "ClockMin"),
            "a constructed gap is a value under Min, earlier than the stored 03:30" to
                fired("constructed-gap", "ConstructedMin"),
            "a constructed gap stores the wall time one gap later" to run("constructed-gap").outcomes["OutConstructed"],
            "so its guard reads it as filled" to run("constructed-gap").outcomes["AfterConstructed"],
            "an empty partial date is a missing outcome under Min" to fired("empty-partial", "PartialMin"),
            "a valueless partial date still runs the clock argument" to
                run("empty-partial-bad-clock").outcomes["AfterPartial"],
            "and reaches the unreadable literal there" to fired("malformed-partial", "GapAfterPartial"),
        )
        assertEquals(linkedMapOf(
            "an unreal clock is a fixed outcome under Min" to listOf("ClockMin|VALUE_ERROR|/Claim[1]/Clock"),
            "a constructed gap is a value under Min, earlier than the stored 03:30" to emptyList<String>(),
            "a constructed gap stores the wall time one gap later" to "VALUE|2024-03-31T03:00:00",
            "so its guard reads it as filled" to "CLEARED",
            "an empty partial date is a missing outcome under Min" to
                listOf("PartialMin|OMISSION_ERROR|/Claim[1]/Logged"),
            "a valueless partial date still runs the clock argument" to "CLEARED",
            "and reaches the unreadable literal there" to listOf("GapAfterPartial|VALUE_ERROR|/Claim[1]/Logged"),
        ), actual)
    }
}
