package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.DocumentDifferential.underUtc
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.Date

/**
 * SPEC-2026-10-03-06: a DateTime wall label with no instant in the model zone is a RUNTIME failure raised where
 * the label is evaluated, not an unknown operand. A computation clears with `berechnungsWertFehler` and poisons
 * its readers; a validation rule stops its row loop and reports its own code once, at the first iterator address.
 *
 * The model crosses every carrier the label can sit in — a direct constant, a shift source, a nested shift, both
 * extractors, operand-list `Min`/`Max` in both operand orders — with dependents that separate a poisoned read from
 * a clean empty one (`FirstFilledValue` and a `FieldNotFilled` guard would select their fallback on a clean empty).
 * The rules cross the truth pass (`And` in both orders) with the classification pass, whose static enablement is a
 * property of the authored constructs: a field-value read or a child-group presence guard enables it, an
 * unfiltered `FieldFilled` with a field-free `Now` does not, and `FieldNotFilled` does.
 *
 * The JVM default is pinned to UTC for the whole file: under a Berlin default the kernel's STATIC check refuses
 * the gap label (`DateTimeConstantZoneSplitDiffTest`), while the runtime judges it against the declared zone.
 *
 * Own domain (parcel delivery tracking).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemporalValueFailureDiffTest {
    private companion object {
        const val ISO_DT = "yyyy-MM-dd'T'HH:mm:ss"

        /** A Julian leap day, which Europe/Berlin cannot read: the label fails when reached (KERNEL-FINDINGS KF665). */
        const val GAP = "29.02.1500T10:00:00"
        const val ORDINARY = "31.03.2024T01:30:00"
        const val FALLBACK = "05.03.2024T12:30:00"
        const val SEED = "2020-01-01T00:00:00"
        val KERNEL_CLOCK: Date = Date.from(Instant.parse("2026-10-04T10:00:00Z"))
        val CLOCK = EvalClock(today = "2026-10-04", now = "2026-10-04T12:00:00")
    }

    private val dateTimeTargets = listOf("GapStamp", "ShiftOrdinary", "ShiftGap", "NestedGap", "MinPickupFirst",
        "MinGapFirst", "MaxPickupFirst", "MinOrdinary", "OrdinaryStamp", "AfterShiftGap", "AfterMinPickupFirst",
        "AfterMinGapFirst", "AfterMinOrdinary", "FallbackAfterGap", "GuardAfterGap")

    private val model: String = underUtc { buildModel() }

    private fun buildModel(): String {
        val draft = DocumentModelDraft.named("parcel-delivery-temporal-failure").locale("en_US")
            .rootGroup("Delivery") { root ->
                root.dateTimeField("Pickup") { it.format(ISO_DT) }
                for (name in dateTimeTargets) root.dateTimeField(name) { it.format(ISO_DT) }
                root.dateField("GapDay") { it.format("yyyy-MM-dd") }
                root.timeField("GapClock") { }
                root.group("Stops") { stops ->
                    stops.repeatable(9)
                    stops.dateTimeField("Arrival") { it.format(ISO_DT) }
                    stops.stringField("Signed") { }
                    stops.numberField("Qty") { it.fractionDigits(0) }
                }
            }.build().toJson()
        val session = EditSession.read(draft).also { it.config().timeZone("Europe/Berlin") }
        val root = session.group("/Delivery")
        val pickup = root.dateTime("Pickup")
        fun dt(name: String) = root.dateTime(name)
        val gap = Dsl.dateTimeValue(GAP)
        val ordinary = Dsl.dateTimeValue(ORDINARY)
        var json = session.toJson()
        fun comp(target: io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef, name: String,
                 body: (io.github.mbackschat.a12.dm.rulekit.dsl.Computation.Builder) ->
                 io.github.mbackschat.a12.dm.rulekit.dsl.Computation.Builder) {
            json = TypedRules.inject(json, root.computation(target) {
                body(it.name(name + "Comp")).message(Dsl.en("Check the delivery time.")).build()
            })
        }
        comp(dt("GapStamp").field(), "GapStamp") { it.calc(gap.expression()) }
        comp(dt("ShiftOrdinary").field(), "ShiftOrdinary") { it.calc(Dsl.addSeconds(ordinary, 1).expression()) }
        comp(dt("ShiftGap").field(), "ShiftGap") { it.calc(Dsl.addSeconds(gap, 1).expression()) }
        comp(dt("NestedGap").field(), "NestedGap") { it.calc(Dsl.addHours(Dsl.addSeconds(gap, 1), 1).expression()) }
        comp(root.date("GapDay").field(), "GapDay") { it.calc(Dsl.dateFromDateTime(gap).expression()) }
        comp(root.time("GapClock").field(), "GapClock") { it.calc(Dsl.timeFromDateTime(gap).expression()) }
        comp(dt("MinPickupFirst").field(), "MinPickupFirst") { it.calc(Dsl.min(pickup, gap).expression()) }
        comp(dt("MinGapFirst").field(), "MinGapFirst") { it.calc(Dsl.min(gap, pickup).expression()) }
        comp(dt("MaxPickupFirst").field(), "MaxPickupFirst") { it.calc(Dsl.max(pickup, gap).expression()) }
        comp(dt("MinOrdinary").field(), "MinOrdinary") { it.calc(Dsl.min(pickup, ordinary).expression()) }
        comp(dt("OrdinaryStamp").field(), "OrdinaryStamp") { it.calc(Dsl.dateTimeValue(FALLBACK).expression()) }
        for ((target, source) in listOf("AfterShiftGap" to "ShiftGap", "AfterMinPickupFirst" to "MinPickupFirst",
            "AfterMinGapFirst" to "MinGapFirst", "AfterMinOrdinary" to "MinOrdinary")) {
            comp(dt(target).field(), target) { it.calc(Dsl.addSeconds(dt(source), 1).expression()) }
        }
        comp(dt("FallbackAfterGap").field(), "FallbackAfterGap") {
            it.calc(Dsl.firstFilledDateTime(dt("ShiftGap").field(), dt("OrdinaryStamp").field()).expression())
        }
        comp(dt("GuardAfterGap").field(), "GuardAfterGap") {
            it.commonPrecondition(dt("ShiftGap").notFilled()).calc(Dsl.dateTimeValue(FALLBACK).expression())
        }

        val stops = session.group("/Delivery/Stops")
        val arrival = stops.dateTime("Arrival")
        val signed = stops.str("Signed")
        val bulky = stops.num("Qty").asNumber().gt(100)
        val rules: List<Pair<String, Condition>> = listOf(
            "Direct" to arrival.before(gap),
            "Ordinary" to arrival.before(ordinary),
            "GapThenFalse" to arrival.before(gap).and(bulky),
            "FalseThenGap" to bulky.and(arrival.before(gap)),
            "Prefix" to signed.filled().or(arrival.before(gap)),
            "PurePrefix" to signed.filled().or(arrival.filled().and(Dsl.now().before(gap))),
            "GroupPrefix" to stops.ref().filled().and(signed.filled().or(Dsl.now().before(gap))),
            "NotFilledPrefix" to arrival.filled().and(signed.notFilled().or(Dsl.now().before(gap))),
            "Independent" to arrival.filled(),
        )
        for ((code, condition) in rules) {
            json = TypedRules.inject(json, stops.rule {
                val errorField = if (code in setOf("GroupPrefix")) signed.field()
                    else arrival.field()
                it.name(code).code(code).errorField(errorField).`when`(condition)
                    .message(Dsl.en("Check the stop.")).build()
            })
        }
        return json
    }

    private val seeded: String = "\"Pickup\":%s," + dateTimeTargets.joinToString(",") { "\"$it\":\"$SEED\"" } +
        ",\"GapDay\":\"2020-01-01\",\"GapClock\":\"00:00:00\""

    private fun document(pickup: String?, stops: String): String =
        "{\"Delivery\":{" + seeded.format(pickup?.let { "\"$it\"" } ?: "null").replace("\"Pickup\":null,", "") +
            ",\"Stops\":[$stops]}}"

    private fun stop(arrival: String?, signed: Boolean, qty: String = "1"): String =
        "{" + listOfNotNull(arrival?.let { "\"Arrival\":\"$it\"" }, "\"Signed\":\"yes\"".takeIf { signed },
            "\"Qty\":$qty").joinToString(",") + "}"

    private val documents = linkedMapOf(
        "all-signed" to document("2024-03-31T01:00:00",
            listOf(stop("2024-03-31T01:00:00", true), stop("2024-03-31T01:00:00", true),
                stop("2024-03-31T01:00:00", true)).joinToString(",")),
        "signed-unsigned-signed" to document(null,
            listOf(stop("2024-03-31T01:00:00", true), stop("2024-03-31T01:45:00", false),
                stop("2024-03-31T01:00:00", true)).joinToString(",")),
        "malformed" to document("late",
            listOf(stop("soon", true), stop("2024-03-31T01:00:00", false)).joinToString(",")),
        "after-ordinary" to document("2024-03-31T01:45:00", stop("2024-03-31T01:45:00", false)),
        "two-unsigned" to document("2024-03-31T01:00:00",
            listOf(stop("2024-03-31T01:00:00", false), stop("2024-03-31T01:00:00", false)).joinToString(",")),
    )

    private val clock = DifferentialClock(KERNEL_CLOCK, CLOCK)

    private fun observe(doc: String) =
        underUtc { DocumentDifferential.kernel(model, documents.getValue(doc), dynamic = true, clock) }

    @Test
    fun theModelIsKernelConsistentUnderTheAdmittingDefault() {
        assertEquals(emptyList<String>(), underUtc { MessageParameterProbes.kernel(model) })
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        underUtc { DocumentDifferential.assertAgree(model, documents, clock) }
    }

    /**
     * The discriminating rows, pinned against the normative kernel so the agreement above cannot pass vacuously.
     * Each names the rival account it rules out: per-row unknown instead of a failure, an `And`/`Or` that does not
     * short-circuit, a failure that discards rows already fired, `FieldNotFilled` treated as non-classifying, and
     * `Min` reaching its later literal after a malformed earlier operand.
     */
    @Test
    fun theDiscriminatingRowsAreTheKernelsOwn() {
        fun rows(doc: String, code: String) = observe(doc).messages
            .filter { it.code == code }.map { it.pointer.substringAfter("/Stops").substringBefore('/') }
        fun operandError(doc: String, target: String) =
            observe(doc).operandErrors.firstOrNull { it.pointer.endsWith("/$target") }?.code
        val actual = linkedMapOf(
            "a reached gap reports once, at the first address" to rows("all-signed", "Direct"),
            "a false And prefix skips the gap" to rows("all-signed", "FalseThenGap"),
            "a later false And cannot hide a reached gap" to rows("all-signed", "GapThenFalse"),
            "a field read enables classification, which reaches the gap" to rows("all-signed", "Prefix"),
            "no classification: a true Or prefix hides the gap on every row" to rows("all-signed", "PurePrefix"),
            "rows fired before the failure keep their messages" to rows("signed-unsigned-signed", "PurePrefix"),
            "FieldNotFilled enables classification" to rows("two-unsigned", "NotFilledPrefix"),
            "a malformed first Min operand poisons before the literal" to
                operandError("malformed", "MinPickupFirst"),
            "the literal first fails before the malformed operand" to operandError("malformed", "MinGapFirst"),
        )
        assertEquals(linkedMapOf(
            "a reached gap reports once, at the first address" to listOf("[1]"),
            "a false And prefix skips the gap" to emptyList<String>(),
            "a later false And cannot hide a reached gap" to listOf("[1]"),
            "a field read enables classification, which reaches the gap" to listOf("[1]"),
            "no classification: a true Or prefix hides the gap on every row" to listOf("[1]", "[2]", "[3]"),
            "rows fired before the failure keep their messages" to listOf("[1]", "[1]"),
            "FieldNotFilled enables classification" to listOf("[1]"),
            "a malformed first Min operand poisons before the literal" to null,
            "the literal first fails before the malformed operand" to "berechnungsWertFehler",
        ), actual)
    }
}
