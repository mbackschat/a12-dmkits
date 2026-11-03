package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.DocumentDifferential.underUtc
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * How far a `Date` construction reads its arguments past a formally unavailable one, per phase (a12-kernel-lean
 * SPEC-2026-10-05-01). The month component is extracted from a wall label the Berlin model zone skips, so reading
 * it is a runtime failure; the day before it is a field that may be malformed. Validation reads on to the month and
 * fails there; a computation stops at the malformed day. `Time` and `DateTime` read their arguments the same way,
 * per phase. A split-year construction keeps its own gate: a zero century is no real date.
 *
 * The JVM default is pinned to UTC, under which the kernel's static check admits the gap label
 * (`DateTimeConstantZoneSplitDiffTest`); the model zone is Europe/Berlin.
 *
 * Own domain (an insurance policy's start date).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConstructionArgumentPhaseDiffTest {
    private companion object {
        /** A Julian leap day, which Europe/Berlin cannot read: the label fails when reached (KERNEL-FINDINGS KF665). */
        const val GAP = "29.02.1500T10:00:00"
    }

    private val model: String = underUtc {
        val draft = DocumentModelDraft.named("policy-construction-phase").locale("en_US")
            .rootGroup("Policy") { p ->
                p.stringField("Ref") { }
                p.numberField("Day") { n -> n.fractionDigits(0).min("0").max("31") }
                p.numberField("Month") { n -> n.fractionDigits(0).min("0").max("12") }
                p.numberField("Century") { n -> n.fractionDigits(0).min("0").max("99") }
                p.numberField("Short") { n -> n.fractionDigits(0).min("0").max("99") }
                p.dateField("Start") { it.format("yyyy-MM-dd") }
                p.dateField("Effective") { it.format("yyyy-MM-dd") }
                p.numberField("Hour") { n -> n.fractionDigits(0).min("0").max("23") }
                p.dateTimeField("Stamp") { it.format("yyyy-MM-dd'T'HH:mm:ss") }
                p.timeField("Clock") { it.format("HH:mm:ss") }
            }.build().toJson()
        val session = EditSession.read(draft).also { it.config().timeZone("Europe/Berlin") }
        val p = session.group("/Policy")
        val day = p.num("Day")
        val gapMonth = Dsl.monthFromDate(Dsl.dateFromDateTime(Dsl.dateTimeValue(GAP)))
        val built = Dsl.date(day.asNumber(), gapMonth, Dsl.temporalComponent("2024"))
        val split = Dsl.date(day.asNumber(), p.num("Month").asNumber(), p.num("Century").asNumber(),
            p.num("Short").asNumber())
        val gapTime = Dsl.timeFromDateTime(Dsl.dateTimeValue(GAP))
        val stamped = Dsl.dateTime(p.date("Effective"), gapTime)
        val clocked = Dsl.time(p.num("Hour").asNumber(), Dsl.minutesFromTime(gapTime))
        val ref = p.str("Ref")
        var json = session.toJson()
        json = TypedRules.inject(json, p.computation(p.dateTime("Stamp").field()) {
            it.name("StampComp").calc(stamped.expression()).message(Dsl.en("Check the start date.")).build()
        })
        json = TypedRules.inject(json, p.rule {
            it.name("StampRule").code("StampRule").errorField(ref.field())
                .`when`(ref.filled().and(stamped.eq(Dsl.dateTimeValue("01.03.2024T08:00:00"))))
                .message(Dsl.en("Check the start date.")).build()
        })
        json = TypedRules.inject(json, p.computation(p.time("Clock").field()) {
            it.name("ClockComp").calc(clocked.expression()).message(Dsl.en("Check the start date.")).build()
        })
        json = TypedRules.inject(json, p.rule {
            it.name("ClockRule").code("ClockRule").errorField(ref.field())
                .`when`(ref.filled().and(clocked.eq(Dsl.time("08", "00")))).message(Dsl.en("Check the start date.")).build()
        })
        json = TypedRules.inject(json, p.computation(p.date("Start").field()) {
            it.name("StartComp").calc(built.expression()).message(Dsl.en("Check the start date.")).build()
        })
        json = TypedRules.inject(json, p.rule {
            it.name("StartRule").code("StartRule").errorField(ref.field())
                .`when`(ref.filled().and(built.eq(Dsl.dateValue("01.03.2024")))).message(Dsl.en("Check the start date.")).build()
        })
        json = TypedRules.inject(json, p.rule {
            it.name("SplitInvalid").code("SplitInvalid").errorField(ref.field())
                .`when`(ref.filled().and(Dsl.invalid(split))).message(Dsl.en("Check the start date.")).build()
        })
        json
    }

    private fun policy(day: String, century: String, effective: String = "2024-03-01", hour: String = "8") =
        """{"Policy":{"Ref":"P-1","Day":"$day","Month":"3","Century":"$century","Short":"24","Start":"2001-12-25",""" +
            """"Effective":"$effective","Hour":"$hour","Stamp":"2001-12-25T12:30:00","Clock":"12:30:00"}}"""

    private val documents = linkedMapOf(
        "a healthy day" to policy("1", "20"),
        "a malformed day" to policy("bad", "20"),
        "a zero century" to policy("1", "0"),
        "a malformed effective date" to policy("1", "20", effective = "bad"),
        "a malformed hour" to policy("1", "20", hour = "bad"),
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
     * The separating rows, pinned against the normative kernel: past a malformed day validation still reaches the
     * failing month and the rule aborts, while the computation stops first and leaves no failure at its target; a
     * healthy day lets both phases reach it; and a zero century is an unreal date.
     */
    @Test
    fun validationReadsPastAMalformedArgumentAndAComputationStops() {
        fun run(doc: String) = underUtc { DocumentDifferential.kernel(model, documents.getValue(doc), dynamic = true) }
        fun targetFailureAt(doc: String, target: String) =
            run(doc).operandErrors.any { it.code == "berechnungsWertFehler" && it.pointer.endsWith(target) }
        fun targetFailure(doc: String) = targetFailureAt(doc, "/Start")
        fun rule(doc: String, code: String) = run(doc).messages.filter { it.code == code }.map { it.type }
        val actual = linkedMapOf<String, Any?>(
            "a malformed day: the rule" to rule("a malformed day", "StartRule"),
            "a malformed day: a failure at the target" to targetFailure("a malformed day"),
            "a healthy day: the rule" to rule("a healthy day", "StartRule"),
            "a healthy day: a failure at the target" to targetFailure("a healthy day"),
            "a zero century" to rule("a zero century", "SplitInvalid"),
            "a nonzero century — control" to rule("a healthy day", "SplitInvalid"),
            "DateTime past a malformed date: the rule" to rule("a malformed effective date", "StampRule"),
            "DateTime past a malformed date: a failure at the target" to
                targetFailureAt("a malformed effective date", "/Stamp"),
            "Time past a malformed hour: the rule" to rule("a malformed hour", "ClockRule"),
            "Time past a malformed hour: a failure at the target" to targetFailureAt("a malformed hour", "/Clock"),
            "Time with a healthy hour: a failure at the target — control" to targetFailureAt("a healthy day", "/Clock"),
        )
        assertEquals(linkedMapOf<String, Any?>(
            "a malformed day: the rule" to listOf("VALUE_ERROR"),
            "a malformed day: a failure at the target" to false,
            "a healthy day: the rule" to listOf("VALUE_ERROR"),
            "a healthy day: a failure at the target" to true,
            "a zero century" to listOf("VALUE_ERROR"),
            "a nonzero century — control" to emptyList<String>(),
            "DateTime past a malformed date: the rule" to listOf("VALUE_ERROR"),
            "DateTime past a malformed date: a failure at the target" to false,
            "Time past a malformed hour: the rule" to listOf("VALUE_ERROR"),
            "Time past a malformed hour: a failure at the target" to false,
            "Time with a healthy hour: a failure at the target — control" to true,
        ), actual)
    }
}
